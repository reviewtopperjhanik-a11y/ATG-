package com.example

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.example.agent.opencode.OpenCodeAgentRuntime
import com.example.agent.opencode.OpenCodeInstallerRepository
import com.example.agent.orchestrator.AgentOrchestrator
import com.example.agent.orchestrator.OrchestratorEvent
import com.example.agent.orchestrator.OrchestratorTaskRequest
import com.example.agent.orchestrator.RepositoryInspector
import com.example.agent.orchestrator.TaskStateMachine
import com.example.agent.tools.ToolBroker
import com.example.core.common.AgtError
import com.example.core.security.GatewayCredentialVault
import com.example.core.security.TokenCipher
import com.example.data.database.AgtDatabase
import com.example.data.gateway.GatewayRepository
import com.example.data.preferences.AppPreferencesRepository
import com.example.data.project.ProjectRepository
import com.example.data.project.WorkspaceCheckpointManager
import com.example.data.runtime.LocalRuntimeExecutor
import com.example.data.runtime.RuntimeInstallerRepository
import com.example.domain.contracts.AgentEvent
import com.example.domain.contracts.ProjectRef
import com.example.domain.model.AppPreferencesState
import com.example.domain.model.FileChangeType
import com.example.domain.model.GatewayProtocolMode
import com.example.domain.model.PermissionClass
import com.example.domain.model.ProjectStack
import com.example.domain.model.TaskState
import com.example.domain.model.ToolBrokerDecision
import com.example.domain.model.ValidationOutcome
import com.sun.net.httpserver.HttpServer
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.ByteArrayOutputStream
import java.io.File
import java.net.InetSocketAddress
import java.nio.charset.StandardCharsets
import java.security.MessageDigest
import java.util.zip.GZIPOutputStream

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class OrchestratorM7Test {

    private lateinit var context: Context
    private lateinit var db: AgtDatabase
    private lateinit var prefs: AppPreferencesRepository
    private lateinit var vault: GatewayCredentialVault
    private lateinit var gatewayRepo: GatewayRepository
    private lateinit var projectRepo: ProjectRepository
    private lateinit var checkpointManager: WorkspaceCheckpointManager
    private lateinit var runtimeInstaller: RuntimeInstallerRepository
    private lateinit var openCodeInstaller: OpenCodeInstallerRepository
    private lateinit var executor: LocalRuntimeExecutor
    private lateinit var openCodeRuntime: OpenCodeAgentRuntime
    private lateinit var orchestrator: AgentOrchestrator
    private lateinit var server: HttpServer
    private lateinit var baseUrl: String

    private val testCipher = object : TokenCipher {
        override fun encrypt(plaintext: String): ByteArray =
            plaintext.toByteArray(StandardCharsets.UTF_8).map { (it.toInt() xor 0x4A).toByte() }.toByteArray()

        override fun decrypt(payload: ByteArray): String =
            String(payload.map { (it.toInt() xor 0x4A).toByte() }.toByteArray(), StandardCharsets.UTF_8)
    }

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        File(context.filesDir, "runtime").deleteRecursively()
        File(context.filesDir, "workspaces").deleteRecursively()
        File(context.filesDir, "checkpoints").deleteRecursively()
        context.deleteDatabase("test_m7_orch.db")
        context.getSharedPreferences("agt_gateway_vault", Context.MODE_PRIVATE).edit().clear().commit()

        db = Room.databaseBuilder(context, AgtDatabase::class.java, "test_m7_orch.db")
            .allowMainThreadQueries()
            .build()
        prefs = AppPreferencesRepository(context)
        vault = GatewayCredentialVault(context, testCipher)
        gatewayRepo = GatewayRepository(prefs, vault, db.cachedModelDao())
        projectRepo = ProjectRepository(context, db.projectDao(), db.taskDao(), db.checkpointDao())
        checkpointManager = WorkspaceCheckpointManager(context, db.projectDao(), db.checkpointDao(), maxCheckpointsPerProject = 5)
        runtimeInstaller = RuntimeInstallerRepository(context, prefs)
        openCodeInstaller = OpenCodeInstallerRepository(context, runtimeInstaller, prefs)
        executor = LocalRuntimeExecutor(runtimeInstaller)
        openCodeRuntime = OpenCodeAgentRuntime(
            context = context,
            runtimeInstallerRepository = runtimeInstaller,
            openCodeInstaller = openCodeInstaller,
            runtimeExecutor = executor,
            gatewayRepository = gatewayRepo,
            preferencesRepository = prefs,
            credentialVault = vault,
        )
        orchestrator = AgentOrchestrator(
            projectRepository = projectRepo,
            checkpointManager = checkpointManager,
            preferencesRepository = prefs,
            runtimeExecutor = AgentOrchestrator.LocalRuntimeExecutorFacade(executor),
            agentRuntime = openCodeRuntime,
            maxFixRetries = 2,
        )

        server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        server.start()
        baseUrl = "http://127.0.0.1:${server.address.port}"
    }

    @After
    fun tearDown() {
        runBlocking {
            executor.terminateAll()
            gatewayRepo.clearToken()
            prefs.setAgentPolicy(20, 120, autoDebug = true, checkpoints = true, requirePlanApproval = false)
        }
        server.stop(0)
        db.close()
        context.deleteDatabase("test_m7_orch.db")
        context.getSharedPreferences("agt_gateway_vault", Context.MODE_PRIVATE).edit().clear().commit()
        File(context.filesDir, "runtime").deleteRecursively()
        File(context.filesDir, "workspaces").deleteRecursively()
        File(context.filesDir, "checkpoints").deleteRecursively()
    }

    @Test
    fun `task state machine enforces legal transitions and rejects illegal transitions`() {
        val sm = TaskStateMachine(TaskState.CREATED)
        assertTrue(sm.transitionTo(TaskState.CONTEXT_LOADING).isSuccess)
        assertTrue(sm.transitionTo(TaskState.ANALYZING).isSuccess)
        assertTrue(sm.transitionTo(TaskState.PLANNING).isSuccess)
        assertTrue(sm.transitionTo(TaskState.AWAITING_PLAN_APPROVAL).isSuccess)
        assertTrue(sm.transitionTo(TaskState.EXECUTING).isSuccess)
        assertTrue(sm.transitionTo(TaskState.VALIDATING).isSuccess)
        assertTrue(sm.transitionTo(TaskState.FIXING).isSuccess)
        assertTrue(sm.transitionTo(TaskState.VALIDATING).isSuccess)
        assertTrue(sm.transitionTo(TaskState.COMPLETED).isSuccess)

        // Terminal state rejects further transitions
        assertTrue(sm.transitionTo(TaskState.EXECUTING).isFailure)

        val badJump = TaskStateMachine(TaskState.CREATED)
        assertTrue(badJump.transitionTo(TaskState.COMPLETED).isFailure)
    }

    @Test
    fun `repository inspector detects stacks, excludes secrets, and extracts real check commands`() = runBlocking {
        val nodeProject = projectRepo.createProject("node-svc").getOrThrow()
        val nodeDir = File(nodeProject.workspacePath)
        File(nodeDir, "package.json").writeText(
            """{"name":"svc","scripts":{"build":"tsc","test":"node --test","lint":"eslint ."}}"""
        )
        File(nodeDir, ".env").writeText("API_SECRET=super_secret_do_not_index")
        File(nodeDir, "node_modules/pkg").mkdirs()
        File(nodeDir, "node_modules/pkg/index.js").writeText("ignored")
        File(nodeDir, "src").mkdirs()
        File(nodeDir, "src/index.js").writeText("console.log('hi');")

        val nodeInsp = RepositoryInspector.inspect(nodeProject.id, nodeProject.workspacePath).getOrThrow()
        assertEquals(ProjectStack.NODE_JS, nodeInsp.stack)
        assertTrue(nodeInsp.directoryMap.contains("src/index.js"))
        assertFalse(nodeInsp.directoryMap.any { it.contains(".env") })
        assertFalse(nodeInsp.directoryMap.any { it.contains("node_modules") })
        assertTrue(nodeInsp.validationCommands.any { it.command == "npm test" })
        assertTrue(nodeInsp.validationCommands.any { it.command == "npm run build" })
        assertTrue(nodeInsp.validationCommands.any { it.command == "npm run lint" })

        val pyProject = projectRepo.createProject("py-lib").getOrThrow()
        val pyDir = File(pyProject.workspacePath)
        File(pyDir, "pyproject.toml").writeText("[project]\nname='py-lib'\n")
        File(pyDir, "test_math.py").writeText("def test_add(): assert 1 + 1 == 2\n")
        val pyInsp = RepositoryInspector.inspect(pyProject.id, pyProject.workspacePath).getOrThrow()
        assertEquals(ProjectStack.PYTHON, pyInsp.stack)
        assertTrue(pyInsp.validationCommands.any { it.id == "py-test" })
    }

    @Test
    fun `tool broker rejects path traversal and secret exfiltration while gating dangerous actions`() {
        val root = File(context.filesDir, "workspaces/broker-test").apply { mkdirs() }
        val secret = "jh_live_broker_secret_777"
        val broker = ToolBroker(root.absolutePath) { listOf(secret) }
        val prefsState = AppPreferencesState(
            requireDestructiveApproval = true,
            requirePushApproval = true,
            requireDeployApproval = true,
        )

        // Path traversal blocked
        val traversal = broker.evaluateToolCall(
            toolCallId = "t1",
            rawToolName = "edit",
            rawArgumentsJson = """{"filePath":"../../etc/passwd"}""",
            preferences = prefsState,
        )
        assertTrue(traversal is ToolBrokerDecision.Rejected)
        assertTrue((traversal as ToolBrokerDecision.Rejected).error is AgtError.ProjectPathRejected)

        // Secret exfiltration blocked
        val exfil = broker.evaluateToolCall(
            toolCallId = "t2",
            rawToolName = "bash",
            rawArgumentsJson = """{"command":"cat /data/data/com.example/shared_prefs/agt_gateway_vault.xml"}""",
            preferences = prefsState,
        )
        assertTrue(exfil is ToolBrokerDecision.Rejected)
        assertTrue((exfil as ToolBrokerDecision.Rejected).error is AgtError.PermissionDenied)

        // Destructive bash requires approval
        val rmDecision = broker.evaluateToolCall(
            toolCallId = "t3",
            rawToolName = "bash",
            rawArgumentsJson = """{"command":"rm -rf build/ $secret"}""",
            preferences = prefsState,
        )
        assertTrue(rmDecision is ToolBrokerDecision.RequiresApproval)
        val reqApproval = rmDecision as ToolBrokerDecision.RequiresApproval
        assertEquals(PermissionClass.DESTRUCTIVE, reqApproval.definition.permissionClass)
        assertFalse(reqApproval.sanitizedSummary.contains(secret))

        // Normal file read inside root allowed
        val safeRead = broker.evaluateToolCall(
            toolCallId = "t4",
            rawToolName = "read",
            rawArgumentsJson = """{"filePath":"src/Main.kt"}""",
            preferences = prefsState,
        )
        assertTrue(safeRead is ToolBrokerDecision.Allowed)
        assertEquals(4, broker.getAuditTrail().size)
    }

    @Test
    fun `checkpoint manager computes live diffs and restores workspace cleanly`() = runBlocking {
        val proj = projectRepo.createProject("chk-proj").getOrThrow()
        val root = File(proj.workspacePath)
        File(root, "keep.txt").writeText("line 1\nline 2\n")
        File(root, "delete_me_later.txt").writeText("original content\n")

        val chk = checkpointManager.create(proj.id, "Initial clean state").getOrThrow()

        // Mutate workspace: modify keep.txt, delete delete_me_later.txt, add extra.txt
        File(root, "keep.txt").writeText("line 1\nline 2 modified\n")
        File(root, "delete_me_later.txt").delete()
        File(root, "extra.txt").writeText("new file\n")

        val diff = checkpointManager.diff(chk.id).getOrThrow()
        assertEquals(3, diff.totalFilesChanged)
        assertTrue(diff.entries.any { it.relativePath == "keep.txt" && it.changeType == FileChangeType.MODIFIED })
        assertTrue(diff.entries.any { it.relativePath == "delete_me_later.txt" && it.changeType == FileChangeType.DELETED })
        assertTrue(diff.entries.any { it.relativePath == "extra.txt" && it.changeType == FileChangeType.ADDED })

        // Restore back to checkpoint
        checkpointManager.restore(chk.id).getOrThrow()

        assertEquals("line 1\nline 2\n", File(root, "keep.txt").readText())
        assertTrue(File(root, "delete_me_later.txt").exists())
        assertEquals("original content\n", File(root, "delete_me_later.txt").readText())
        assertFalse(File(root, "extra.txt").exists())

        // Safety backup checkpoint was created automatically before restore
        val checkpoints = checkpointManager.observeForProject(proj.id).first()
        assertTrue(checkpoints.any { it.reason.contains("Pre-restore backup") })
    }

    @Test
    fun `orchestrator completes end-to-end task with real test execution, bounded auto-debug fix loop, approval, and checkpoint restore`() = runBlocking {
        installBaseRuntime()
        val proj = projectRepo.createProject("e2e-shell-proj").getOrThrow()
        val root = File(proj.workspacePath)
        File(root, "value.txt").writeText("INITIAL\n")
        File(root, "test.sh").writeText(
            """
            #!/bin/sh
            if grep -q "FIXED_OK" value.txt; then
              echo "ALL TESTS PASSED"
              exit 0
            else
              echo "TEST FAILED: expected FIXED_OK in value.txt" >&2
              exit 1
            fi
            """.trimIndent() + "\n"
        )

        // OpenCode script:
        // - Pass 1 (initial prompt): writes BUGGY to value.txt and triggers a dangerous bash approval
        // - Pass 2 (auto-debug prompt containing "Validation failed"): writes FIXED_OK to value.txt
        installOpenCodeCliScript(
            """
            #!/bin/sh
            PROMPT="${'$'}(cat "${'$'}AGT_PROMPT_FILE")"
            if echo "${'$'}PROMPT" | grep -q "Validation failed"; then
              echo '{"type":"step_start","part":{"title":"Fixing failing test"}}'
              printf "FIXED_OK\n" > value.txt
              echo '{"type":"tool_use","part":{"callID":"edit-fix","tool":"edit","state":{"status":"completed","input":{"filePath":"value.txt"},"output":"Wrote FIXED_OK"}}}'
              echo '{"type":"text","part":{"text":"Fixed value.txt so test.sh passes."}}'
              echo '{"type":"step_finish","part":{"tokens":{"input":40,"output":15}}}'
              exit 0
            else
              echo '{"type":"step_start","part":{"title":"Implementing initial change"}}'
              echo '{"type":"permission","id":"perm-rm","tool":"bash","pattern":"rm -rf tmp_cache","title":"Clean cache","reason":"Remove tmp_cache before edit"}'
              read decision
              printf "BUGGY\n" > value.txt
              echo '{"type":"tool_use","part":{"callID":"edit-1","tool":"edit","state":{"status":"completed","input":{"filePath":"value.txt"},"output":"Wrote BUGGY"}}}'
              echo '{"type":"text","part":{"text":"Updated value.txt."}}'
              echo '{"type":"step_finish","part":{"tokens":{"input":50,"output":20}}}'
              exit 0
            fi
            """.trimIndent()
        )

        server.createContext("/health") { ex ->
            val bytes = """{"status":"ok"}""".toByteArray()
            ex.sendResponseHeaders(200, bytes.size.toLong())
            ex.responseBody.use { it.write(bytes) }
        }
        server.createContext("/v1/models") { ex ->
            val json = """
                {"data":[{"id":"claude-3-7-sonnet","provider":"anthropic","capabilities":{"streaming":true,"tool_calling":true,"vision":true,"context_window":200000}}]}
            """.trimIndent().toByteArray()
            ex.sendResponseHeaders(200, json.size.toLong())
            ex.responseBody.use { it.write(json) }
        }
        gatewayRepo.saveConfiguration(baseUrl, GatewayProtocolMode.CHAT_COMPLETIONS, "jh_live_m7_token").getOrThrow()
        gatewayRepo.testHealthAndDiscoverModels()
        prefs.setAgentPolicy(20, 30, autoDebug = true, checkpoints = true, requirePlanApproval = true)

        val taskId = "m7-e2e-task-1"
        val events = mutableListOf<OrchestratorEvent>()
        val finished = CompletableDeferred<Unit>()

        val job = launch {
            orchestrator.executeTask(
                OrchestratorTaskRequest(
                    taskId = taskId,
                    project = ProjectRef(proj.id, proj.name, proj.workspacePath),
                    prompt = "Update value.txt so test.sh passes",
                    modelId = "claude-3-7-sonnet",
                    protocolMode = GatewayProtocolMode.CHAT_COMPLETIONS,
                )
            ).collect { ev ->
                events += ev
                when (ev) {
                    is OrchestratorEvent.PlanReady -> if (ev.requiresApproval) {
                        launch { orchestrator.respondToPlanApproval(taskId, approved = true) }
                    }
                    is OrchestratorEvent.RuntimeEvent -> {
                        val inner = ev.event
                        if (inner is AgentEvent.ApprovalRequired) {
                            launch { orchestrator.respondToToolApproval(taskId, inner.approvalId, approved = true) }
                        }
                    }
                    is OrchestratorEvent.SummaryReady -> finished.complete(Unit)
                    else -> Unit
                }
            }
        }

        withTimeout(15_000) {
            finished.await()
            job.join()
        }

        // Verify state progression included planning, approval, executing, validating, fixing, completed
        val states = events.filterIsInstance<OrchestratorEvent.StateChanged>().map { it.state }
        assertTrue(states.contains(TaskState.CONTEXT_LOADING))
        assertTrue(states.contains(TaskState.PLANNING))
        assertTrue(states.contains(TaskState.AWAITING_PLAN_APPROVAL))
        assertTrue(states.contains(TaskState.EXECUTING))
        assertTrue(states.contains(TaskState.AWAITING_TOOL_APPROVAL))
        assertTrue(states.contains(TaskState.VALIDATING))
        assertTrue(states.contains(TaskState.FIXING))
        assertEquals(TaskState.COMPLETED, states.last())

        // Verify real validation ran twice: failed first (exitCode 1), then passed (exitCode 0)
        val validations = events.filterIsInstance<OrchestratorEvent.ValidationCompleted>().map { it.result }
        assertEquals(2, validations.size)
        assertEquals(ValidationOutcome.FAILED, validations[0].status)
        assertEquals(1, validations[0].exitCode)
        assertEquals(ValidationOutcome.PASSED, validations[1].status)
        assertEquals(0, validations[1].exitCode)

        // Verify TaskSummary and Room persistence
        val summary = events.filterIsInstance<OrchestratorEvent.SummaryReady>().single().summary
        assertEquals(TaskState.COMPLETED, summary.finalState)
        assertEquals(1, summary.fixAttempts)
        assertNotNull(summary.checkpointId)
        assertTrue(summary.filesChanged.any { it.contains("value.txt") })
        assertEquals("FIXED_OK\n", File(root, "value.txt").readText())

        val persistedTask = db.taskDao().getTaskById(taskId)
        assertNotNull(persistedTask)
        assertEquals("COMPLETED", persistedTask!!.state)
        assertNotNull(projectRepo.decodeTaskSummary(persistedTask.summaryJson))

        // Verify checkpoint restore returns value.txt back to INITIAL
        checkpointManager.restore(summary.checkpointId!!).getOrThrow()
        assertEquals("INITIAL\n", File(root, "value.txt").readText())
    }

    @Test
    fun `interrupted active tasks are recovered into PAUSED state`() = runBlocking {
        val proj = projectRepo.createProject("recover-proj").getOrThrow()
        projectRepo.recordSubmittedTask(proj.id, "Interrupted task", "claude-3-7-sonnet", TaskState.EXECUTING, "t-rec-1")
        val recoveredCount = projectRepo.recoverInterruptedTasks(proj.id)
        assertEquals(1, recoveredCount)
        val task = db.taskDao().getTaskById("t-rec-1")!!
        assertEquals(TaskState.PAUSED.name, task.state)
    }

    private suspend fun installBaseRuntime() {
        val tarGzBytes = buildSampleTarGz(
            entries = listOf(
                "rootfs/bin/sh" to "#!/bin/sh\nexec /bin/sh \"\$@\"\n".toByteArray(),
                "rootfs/etc/os-release" to "NAME=Ubuntu\n".toByteArray(),
            )
        )
        val sha = sha256(tarGzBytes)
        val abis = android.os.Build.SUPPORTED_ABIS.joinToString(",") { "\"$it\"" }
        server.createContext("/base-runtime.json") { ex ->
            val body = """
                {
                  "runtime_id": "agt-ubuntu-m7",
                  "version": "1.0.0",
                  "release_channel": "stable",
                  "supported_abis": [$abis],
                  "min_android_sdk": 24,
                  "min_required_free_bytes": 1024,
                  "bundle": {
                    "download_url": "$baseUrl/base-runtime.tar.gz",
                    "compressed_size_bytes": ${tarGzBytes.size},
                    "sha256": "$sha",
                    "archive_format": "tar.gz",
                    "extraction_root": "rootfs",
                    "required_paths": ["bin/sh"]
                  }
                }
            """.trimIndent().toByteArray(StandardCharsets.UTF_8)
            ex.sendResponseHeaders(200, body.size.toLong())
            ex.responseBody.use { it.write(body) }
        }
        server.createContext("/base-runtime.tar.gz") { ex ->
            ex.sendResponseHeaders(200, tarGzBytes.size.toLong())
            ex.responseBody.use { it.write(tarGzBytes) }
        }
        assertTrue(runtimeInstaller.installBlocking("$baseUrl/base-runtime.json").isSuccess)
    }

    private suspend fun installOpenCodeCliScript(scriptContent: String) {
        val archiveBytes = buildSampleTarGz(
            listOf("oc/usr/local/bin/opencode" to scriptContent.toByteArray(StandardCharsets.UTF_8))
        )
        val sha = sha256(archiveBytes)
        val abis = android.os.Build.SUPPORTED_ABIS.joinToString(",") { "\"$it\"" }
        server.createContext("/oc-cli-manifest.json") { ex ->
            val body = """
                {
                  "runtime_id": "opencode_agent",
                  "version": "1.4.0",
                  "release_channel": "stable",
                  "supported_abis": [$abis],
                  "min_android_sdk": 24,
                  "min_required_free_bytes": 1024,
                  "bundle": {
                    "download_url": "$baseUrl/oc-cli.tar.gz",
                    "compressed_size_bytes": ${archiveBytes.size},
                    "sha256": "$sha",
                    "archive_format": "tar.gz",
                    "extraction_root": "oc",
                    "required_paths": ["usr/local/bin/opencode"]
                  }
                }
            """.trimIndent().toByteArray(StandardCharsets.UTF_8)
            ex.sendResponseHeaders(200, body.size.toLong())
            ex.responseBody.use { it.write(body) }
        }
        server.createContext("/oc-cli.tar.gz") { ex ->
            ex.sendResponseHeaders(200, archiveBytes.size.toLong())
            ex.responseBody.use { it.write(archiveBytes) }
        }
        assertTrue(openCodeInstaller.installOrUpdate("$baseUrl/oc-cli-manifest.json").isSuccess)
    }

    private fun buildSampleTarGz(entries: List<Pair<String, ByteArray>>): ByteArray {
        val tarOut = ByteArrayOutputStream()
        for ((name, data) in entries) {
            val header = ByteArray(512)
            val nameBytes = name.toByteArray(StandardCharsets.UTF_8)
            System.arraycopy(nameBytes, 0, header, 0, minOf(nameBytes.size, 100))
            "0000755\u0000".toByteArray().copyInto(header, 100)
            "0000000\u0000".toByteArray().copyInto(header, 108)
            "0000000\u0000".toByteArray().copyInto(header, 116)
            String.format("%011o\u0000", data.size).toByteArray().copyInto(header, 124)
            "00000000000\u0000".toByteArray().copyInto(header, 136)
            for (i in 148 until 156) header[i] = ' '.code.toByte()
            header[156] = '0'.code.toByte()
            "ustar\u0000".toByteArray().copyInto(header, 257)
            "00".toByteArray().copyInto(header, 263)
            val sum = header.sumOf { it.toInt() and 0xFF }
            String.format("%06o\u0000 ", sum).toByteArray().copyInto(header, 148)

            tarOut.write(header)
            tarOut.write(data)
            val rem = data.size % 512
            if (rem != 0) tarOut.write(ByteArray(512 - rem))
        }
        tarOut.write(ByteArray(1024))
        val gzOut = ByteArrayOutputStream()
        GZIPOutputStream(gzOut).use { it.write(tarOut.toByteArray()) }
        return gzOut.toByteArray()
    }

    private fun sha256(bytes: ByteArray): String =
        MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }
}
