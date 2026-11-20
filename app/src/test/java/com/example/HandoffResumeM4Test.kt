package com.example

import android.content.Context
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performTextInput
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.example.agent.DelegatingAgentRuntime
import com.example.agent.opencode.OpenCodeAgentRuntime
import com.example.agent.opencode.OpenCodeInstallerRepository
import com.example.agent.orchestrator.AgentOrchestrator
import com.example.agent.orchestrator.HandoffContextBuilder
import com.example.agent.orchestrator.OrchestratorEvent
import com.example.agent.orchestrator.OrchestratorTaskRequest
import com.example.agent.orchestrator.RepoMapEngine
import com.example.agent.orchestrator.RepositoryInspector
import com.example.agent.orchestrator.ResumeTaskRequest
import com.example.agent.pi.PiAgentRuntime
import com.example.agent.pi.PiInstallerRepository
import com.example.core.security.GatewayCredentialVault
import com.example.core.security.TokenCipher
import com.example.data.database.AgtDatabase
import com.example.data.gateway.GatewayRepository
import com.example.data.intelligence.DevelopmentIntelligenceRepository
import com.example.data.preferences.AppPreferencesRepository
import com.example.data.project.ProjectRepository
import com.example.data.project.WorkspaceCheckpointManager
import com.example.data.runtime.LocalRuntimeExecutor
import com.example.data.runtime.RuntimeInstallerRepository
import com.example.domain.contracts.ProjectRef
import com.example.domain.model.AgentBackendId
import com.example.domain.model.ChatMessageRecord
import com.example.domain.model.ChatMessageRole
import com.example.domain.model.FlightRecorderEventType
import com.example.domain.model.FlightRecorderFilter
import com.example.domain.model.FlightRecorderOutcome
import com.example.domain.model.GatewayModelInfo
import com.example.domain.model.GatewayProtocolMode
import com.example.domain.model.PlanStep
import com.example.domain.model.PlanStepStatus
import com.example.domain.model.ProjectSummary
import com.example.domain.model.TaskResumeCursor
import com.example.domain.model.TaskState
import com.example.domain.model.TaskStateSnapshot
import com.example.domain.model.ThemeMode
import com.example.feature.workspace.AgentView
import com.example.feature.workspace.FlightRecorderDialog
import com.example.feature.workspace.WorkspaceUiState
import com.example.ui.theme.AgtTheme
import com.sun.net.httpserver.HttpServer
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
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
class HandoffResumeM4Test {

    @get:Rule
    val composeRule = createComposeRule()

    private lateinit var context: Context
    private lateinit var db: AgtDatabase
    private lateinit var prefs: AppPreferencesRepository
    private lateinit var vault: GatewayCredentialVault
    private lateinit var gatewayRepo: GatewayRepository
    private lateinit var projectRepo: ProjectRepository
    private lateinit var checkpointManager: WorkspaceCheckpointManager
    private lateinit var intelligenceRepo: DevelopmentIntelligenceRepository
    private lateinit var handoffBuilder: HandoffContextBuilder
    private lateinit var runtimeInstaller: RuntimeInstallerRepository
    private lateinit var openCodeInstaller: OpenCodeInstallerRepository
    private lateinit var piInstaller: PiInstallerRepository
    private lateinit var executor: LocalRuntimeExecutor
    private lateinit var openCodeRuntime: OpenCodeAgentRuntime
    private lateinit var piRuntime: PiAgentRuntime
    private lateinit var delegatingRuntime: DelegatingAgentRuntime
    private lateinit var orchestrator: AgentOrchestrator
    private lateinit var server: HttpServer
    private lateinit var baseUrl: String

    private val testSecret = "jh_live_phase4_secret_token_8842"

    private val testCipher = object : TokenCipher {
        override fun encrypt(plaintext: String): ByteArray =
            plaintext.toByteArray(StandardCharsets.UTF_8).map { (it.toInt() xor 0x2F).toByte() }.toByteArray()

        override fun decrypt(payload: ByteArray): String =
            String(payload.map { (it.toInt() xor 0x2F).toByte() }.toByteArray(), StandardCharsets.UTF_8)
    }

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        File(context.filesDir, "runtime").deleteRecursively()
        File(context.filesDir, "workspaces").deleteRecursively()
        File(context.filesDir, "checkpoints").deleteRecursively()
        context.deleteDatabase("test_phase4_handoff.db")
        context.getSharedPreferences("agt_gateway_vault", Context.MODE_PRIVATE).edit().clear().commit()

        db = Room.databaseBuilder(context, AgtDatabase::class.java, "test_phase4_handoff.db")
            .allowMainThreadQueries()
            .build()
        prefs = AppPreferencesRepository(context)
        vault = GatewayCredentialVault(context, testCipher)
        gatewayRepo = GatewayRepository(prefs, vault, db.cachedModelDao())
        projectRepo = ProjectRepository(context, db.projectDao(), db.taskDao(), db.checkpointDao())
        checkpointManager = WorkspaceCheckpointManager(context, db.projectDao(), db.checkpointDao(), maxCheckpointsPerProject = 5)
        intelligenceRepo = DevelopmentIntelligenceRepository(
            chatMessageDao = db.chatMessageDao(),
            taskStateSnapshotDao = db.taskStateSnapshotDao(),
            projectMemoryDao = db.projectMemoryDao(),
            flightRecorderDao = db.flightRecorderDao(),
            knownSecretsProvider = { runBlocking { listOfNotNull(vault.readToken().getOrNull(), testSecret) } },
        )
        handoffBuilder = HandoffContextBuilder(
            intelligenceRepository = intelligenceRepo,
            checkpointManager = checkpointManager,
            repoMapEngine = intelligenceRepo.repoMapEngine,
            knownSecretsProvider = { listOf(testSecret) },
        )
        runtimeInstaller = RuntimeInstallerRepository(context, prefs)
        openCodeInstaller = OpenCodeInstallerRepository(context, runtimeInstaller, prefs)
        piInstaller = PiInstallerRepository(context, runtimeInstaller, prefs, runtimeExecutor = LocalRuntimeExecutor(runtimeInstaller))
        executor = LocalRuntimeExecutor(runtimeInstaller)
        openCodeRuntime = OpenCodeAgentRuntime(
            context = context,
            runtimeInstallerRepository = runtimeInstaller,
            openCodeInstaller = openCodeInstaller,
            runtimeExecutor = executor,
            gatewayRepository = gatewayRepo,
            preferencesRepository = prefs,
            credentialVault = vault,
            intelligenceRepository = intelligenceRepo,
        )
        piRuntime = PiAgentRuntime(
            context = context,
            runtimeInstallerRepository = runtimeInstaller,
            piInstaller = piInstaller,
            runtimeExecutor = executor,
            gatewayRepository = gatewayRepo,
            preferencesRepository = prefs,
            credentialVault = vault,
            intelligenceRepository = intelligenceRepo,
        )
        delegatingRuntime = DelegatingAgentRuntime(
            preferencesRepository = prefs,
            runtimes = mapOf(
                openCodeRuntime.id to openCodeRuntime,
                piRuntime.id to piRuntime,
            ),
        )
        orchestrator = AgentOrchestrator(
            projectRepository = projectRepo,
            checkpointManager = checkpointManager,
            preferencesRepository = prefs,
            runtimeExecutor = AgentOrchestrator.LocalRuntimeExecutorFacade(executor),
            agentRuntime = delegatingRuntime,
            maxFixRetries = 1,
            intelligenceRepository = intelligenceRepo,
            handoffContextBuilder = handoffBuilder,
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
            prefs.setActiveAgentId(AgentBackendId.OPENCODE.id)
            prefs.setAgentPolicy(20, 120, autoDebug = true, checkpoints = true, requirePlanApproval = false)
        }
        server.stop(0)
        db.close()
        context.deleteDatabase("test_phase4_handoff.db")
        context.getSharedPreferences("agt_gateway_vault", Context.MODE_PRIVATE).edit().clear().commit()
        File(context.filesDir, "runtime").deleteRecursively()
        File(context.filesDir, "workspaces").deleteRecursively()
        File(context.filesDir, "checkpoints").deleteRecursively()
    }

    @Test
    fun `handoffContextBuilder assembles all 5 sections, redacts secrets, omits raw chat history, and enforces token budget`() {
        runBlocking {
            val proj = projectRepo.createProject("handoff-builder-proj").getOrThrow()
            val root = File(proj.workspacePath)
            File(root, "src").mkdirs()
            val calcFile = File(root, "src/Calculator.kt")
            calcFile.writeText(
                """
                package demo

                class Calculator {
                    fun add(a: Int, b: Int): Int = a + b
                }
                """.trimIndent() + "\n"
            )
            File(root, ".env").writeText("GATEWAY_SECRET=$testSecret\n")
            File(root, "test.sh").writeText("#!/bin/sh\necho ok\n")

            val chk = checkpointManager.create(proj.id, "Pre-task baseline").getOrThrow()

            // Modify workspace file after checkpoint
            calcFile.writeText(
                """
                package demo

                class Calculator {
                    fun add(a: Int, b: Int): Int = a + b
                    fun divide(a: Int, b: Int): Int = a / b // FIXME $testSecret
                }
                """.trimIndent() + "\n"
            )

            val taskId = "task-handoff-1"
            // Persist raw chat transcript that must NEVER bloat/leak into ModelHandoffPacket
            repeat(10) { idx ->
                intelligenceRepo.appendChatMessage(
                    ChatMessageRecord(
                        id = "msg-$idx",
                        projectId = proj.id,
                        taskId = taskId,
                        role = ChatMessageRole.ASSISTANT,
                        content = "RAW_CHAT_TURN_SHOULD_NOT_LEAK_$idx " + "verbose chatter ".repeat(10),
                        modelId = "claude-3-7-sonnet",
                        backendId = "opencode",
                        createdAtEpochMs = System.currentTimeMillis() + idx,
                    )
                )
            }

            val inspection = RepositoryInspector.inspect(proj.id, proj.workspacePath).getOrThrow()
            intelligenceRepo.upsertProjectMemoryFromInspection(inspection)
            intelligenceRepo.recordKnownFix(
                projectId = proj.id,
                errorSignature = "ArithmeticException: / by zero",
                resolutionNote = "Guard divisor == 0 before divide",
                stackLabel = inspection.stackLabel,
            )
            intelligenceRepo.recordFlightEvent(
                projectId = proj.id,
                taskId = taskId,
                eventType = FlightRecorderEventType.VALIDATION_RUN,
                outcome = FlightRecorderOutcome.FAILED,
                toolName = "Tests",
                commandOrPath = "./test.sh",
                exitCode = 1,
                outputExcerpt = "FAIL src/Calculator.kt: divide(1, 0) threw $testSecret",
            )

            val snapshot = TaskStateSnapshot(
                taskId = taskId,
                projectId = proj.id,
                objective = "Implement safe divide() in Calculator using $testSecret",
                currentState = TaskState.FAILED,
                activeStepIndex = 1,
                planSteps = listOf(
                    PlanStep(0, "Implement divide method", PlanStepStatus.COMPLETED),
                    PlanStep(1, "Run ./test.sh validation", PlanStepStatus.FAILED),
                    PlanStep(2, "Verify workspace diff", PlanStepStatus.PENDING),
                ),
                keyDecisions = listOf("Keep divide signature returning Int"),
                modifiedFiles = listOf("src/Calculator.kt"),
                resumeCursor = TaskResumeCursor(
                    checkpointId = chk.id,
                    fixAttempts = 1,
                    lastError = "Tests (./test.sh) failed (exit=1)",
                    lastEventSeq = 12L,
                ),
                lastModelId = "claude-3-7-sonnet",
                lastBackendId = "opencode",
                updatedAtEpochMs = System.currentTimeMillis(),
            )

            val packet = handoffBuilder.buildPacket(
                snapshot = snapshot,
                inspection = inspection,
                targetModelId = "gemini-2.5-pro",
                targetBackendId = "pi",
                additionalInstruction = "Return 0 when divisor is zero",
                tokenBudget = 900,
            )

            assertEquals("opencode", packet.sourceBackendId)
            assertEquals("claude-3-7-sonnet", packet.sourceModelId)
            assertEquals("pi", packet.targetBackendId)
            assertEquals("gemini-2.5-pro", packet.targetModelId)
            assertEquals(1, packet.completedSteps.size)
            assertEquals(2, packet.remainingSteps.size)
            assertTrue(packet.recentDiffSummary.any { it.contains("Calculator.kt") })
            assertTrue(packet.targetedCodeSlices.any { it.relativePath == "src/Calculator.kt" })
            assertFalse(packet.targetedCodeSlices.any { it.relativePath.contains(".env") })

            val prompt = packet.toPromptContext(tokenBudget = 900)
            assertTrue(prompt.contains("[MODEL HANDOFF PACKET]"))
            assertTrue(prompt.contains("1. Objective & Acceptance Criteria:"))
            assertTrue(prompt.contains("2. Plan Progress"))
            assertTrue(prompt.contains("3. Architectural Decisions & Project Constraints:"))
            assertTrue(prompt.contains("4. Recent Workspace Diff & Unresolved Failures:"))
            assertTrue(prompt.contains("5. Compact Repo Map & Targeted Code Slices:"))
            assertTrue(prompt.contains("Return 0 when divisor is zero"))
            assertTrue(prompt.contains("Guard divisor == 0"))
            assertFalse("Raw secret must be redacted from packet", prompt.contains(testSecret))
            assertFalse("Raw UI chat turns must not be replayed in handoff packet", prompt.contains("RAW_CHAT_TURN_SHOULD_NOT_LEAK"))
            assertTrue(
                "Rendered handoff prompt should fit within token budget (actual=${RepoMapEngine.estimateTokens(prompt)})",
                RepoMapEngine.estimateTokens(prompt) <= 920
            )
        }
    }

    @Test
    fun `orchestrator durable resume transfers failed opencode task to pi with ModelHandoffPacket and completes`() {
        runBlocking {
            installBaseRuntime()
            val proj = projectRepo.createProject("handoff-e2e-proj").getOrThrow()
            val root = File(proj.workspacePath)
            File(root, "value.txt").writeText("INITIAL\n")
            File(root, "test.sh").writeText(
                """
                #!/bin/sh
                if grep -q "FIXED_OK" value.txt; then
                  echo "TESTS PASSED"
                  exit 0
                else
                  echo "EXPECTED FIXED_OK in value.txt" >&2
                  exit 1
                fi
                """.trimIndent() + "\n"
            )

            // OpenCode fails to fix test.sh (writes BUGGY every pass)
            installOpenCodeCliScript(
                """
                #!/bin/sh
                printf "BUGGY\n" > value.txt
                echo '{"type":"tool_use","part":{"callID":"oc-edit","tool":"edit","state":{"status":"completed","input":{"filePath":"value.txt"},"output":"Wrote BUGGY"}}}'
                echo '{"type":"text","part":{"text":"Wrote BUGGY via OpenCode."}}'
                echo '{"type":"step_finish","part":{"tokens":{"input":30,"output":12}}}'
                exit 0
                """.trimIndent()
            )

            val receivedPiPromptFile = File(root, ".pi_received_prompt.txt")
            // Pi CLI checks that incoming prompt contains [MODEL HANDOFF PACKET] + note + failing clue, then writes FIXED_OK
            installPiCliScript(
                """
                #!/bin/sh
                IN_PROMPT="${'$'}(cat "${'$'}AGT_PROMPT_FILE")"
                printf "%s" "${'$'}IN_PROMPT" > "${receivedPiPromptFile.absolutePath}"
                if echo "${'$'}IN_PROMPT" | grep -q "MODEL HANDOFF PACKET"; then
                  printf "FIXED_OK\n" > value.txt
                  echo '{"type":"tool_execution_end","toolCallId":"pi-edit","toolName":"edit","result":{"filePath":"value.txt"},"isError":false}'
                  echo '{"type":"message_update","assistantMessageEvent":{"type":"text_delta","delta":"Handoff applied and fixed value.txt via Pi."}}'
                  echo '{"type":"agent_end"}'
                  exit 0
                else
                  echo '{"type":"error","message":"Missing ModelHandoffPacket in resumed Pi prompt"}'
                  exit 1
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
                    {"data":[
                      {"id":"claude-3-7-sonnet","provider":"anthropic","capabilities":{"streaming":true,"tool_calling":true}},
                      {"id":"gemini-2.5-pro","provider":"google","capabilities":{"streaming":true,"tool_calling":true}}
                    ]}
                """.trimIndent().toByteArray()
                ex.sendResponseHeaders(200, json.size.toLong())
                ex.responseBody.use { it.write(json) }
            }
            gatewayRepo.saveConfiguration(baseUrl, GatewayProtocolMode.CHAT_COMPLETIONS, "jh_live_m4_token").getOrThrow()
            gatewayRepo.testHealthAndDiscoverModels()
            prefs.setActiveAgentId(AgentBackendId.OPENCODE.id)
            prefs.setAgentPolicy(20, 30, autoDebug = true, checkpoints = true, requirePlanApproval = false)

            val taskId = "m4-handoff-task-1"
            val initialEvents = mutableListOf<OrchestratorEvent>()
            val initialDone = CompletableDeferred<Unit>()
            val initialJob = launch {
                orchestrator.executeTask(
                    OrchestratorTaskRequest(
                        taskId = taskId,
                        project = ProjectRef(proj.id, proj.name, proj.workspacePath),
                        prompt = "Update value.txt so test.sh passes",
                        modelId = "claude-3-7-sonnet",
                        protocolMode = GatewayProtocolMode.CHAT_COMPLETIONS,
                        backendId = AgentBackendId.OPENCODE.id,
                    )
                ).collect { ev ->
                    initialEvents += ev
                    if (ev is OrchestratorEvent.SummaryReady) initialDone.complete(Unit)
                }
            }
            withTimeout(15_000) {
                initialDone.await()
                initialJob.join()
            }

            val initialSummary = initialEvents.filterIsInstance<OrchestratorEvent.SummaryReady>().single().summary
            assertEquals(TaskState.FAILED, initialSummary.finalState)
            val failedSnap = intelligenceRepo.getTaskSnapshot(taskId)
            assertNotNull(failedSnap)
            assertEquals(TaskState.FAILED, failedSnap!!.currentState)
            assertEquals("opencode", failedSnap.lastBackendId)
            assertEquals("claude-3-7-sonnet", failedSnap.lastModelId)
            assertNotNull(failedSnap.resumeCursor.checkpointId)

            // Resume task across backend (OpenCode -> Pi) and model (claude-3-7-sonnet -> gemini-2.5-pro)
            val resumeEvents = mutableListOf<OrchestratorEvent>()
            val resumeDone = CompletableDeferred<Unit>()
            val resumeJob = launch {
                orchestrator.resumeTask(
                    ResumeTaskRequest(
                        taskId = taskId,
                        project = ProjectRef(proj.id, proj.name, proj.workspacePath),
                        modelId = "gemini-2.5-pro",
                        protocolMode = GatewayProtocolMode.CHAT_COMPLETIONS,
                        backendId = AgentBackendId.PI.id,
                        additionalInstruction = "Use FIXED_OK cleanly",
                    )
                ).collect { ev ->
                    resumeEvents += ev
                    if (ev is OrchestratorEvent.SummaryReady) resumeDone.complete(Unit)
                }
            }
            withTimeout(15_000) {
                resumeDone.await()
                resumeJob.join()
            }

            val handoffEvent = resumeEvents.filterIsInstance<OrchestratorEvent.HandoffReady>().singleOrNull()
            assertNotNull("Expected OrchestratorEvent.HandoffReady", handoffEvent)
            assertEquals("opencode", handoffEvent!!.packet.sourceBackendId)
            assertEquals("claude-3-7-sonnet", handoffEvent.packet.sourceModelId)
            assertEquals("pi", handoffEvent.packet.targetBackendId)
            assertEquals("gemini-2.5-pro", handoffEvent.packet.targetModelId)

            val resumeStates = resumeEvents.filterIsInstance<OrchestratorEvent.StateChanged>().map { it.state }
            assertEquals(TaskState.PAUSED, resumeStates.first())
            assertTrue(resumeStates.contains(TaskState.CONTEXT_LOADING))
            assertTrue(resumeStates.contains(TaskState.PLANNING))
            assertTrue(resumeStates.contains(TaskState.EXECUTING))
            assertTrue(resumeStates.contains(TaskState.VALIDATING))
            assertEquals(TaskState.COMPLETED, resumeStates.last())

            val resumedSummary = resumeEvents.filterIsInstance<OrchestratorEvent.SummaryReady>().single().summary
            assertEquals(TaskState.COMPLETED, resumedSummary.finalState)
            assertEquals("FIXED_OK\n", File(root, "value.txt").readText())

            assertTrue("Pi CLI should receive ModelHandoffPacket prompt", receivedPiPromptFile.exists())
            val capturedPrompt = receivedPiPromptFile.readText()
            assertTrue(capturedPrompt.contains("[MODEL HANDOFF PACKET]"))
            assertTrue(capturedPrompt.contains("Use FIXED_OK cleanly"))
            assertTrue(capturedPrompt.contains("EXPECTED FIXED_OK") || capturedPrompt.contains("Tests"))

            val finalSnap = intelligenceRepo.getTaskSnapshot(taskId)!!
            assertEquals(TaskState.COMPLETED, finalSnap.currentState)
            assertEquals("pi", finalSnap.lastBackendId)
            assertEquals("gemini-2.5-pro", finalSnap.lastModelId)

            val flightEvents = intelligenceRepo.getFlightEventsForTask(taskId)
            assertTrue(flightEvents.any { it.commandOrPath?.contains("RESUME_PAUSED") == true })
            assertTrue(flightEvents.any { it.commandOrPath == "Handoff -> pi/gemini-2.5-pro" })
        }
    }

    @Test
    fun `interrupted snapshot recovery marks PAUSED and resume skips already completed implementation step`() {
        runBlocking {
            val proj = projectRepo.createProject("resume-skip-proj").getOrThrow()
            val root = File(proj.workspacePath)
            File(root, "test.sh").writeText("#!/bin/sh\necho VALIDATED_OK\nexit 0\n")

            val taskId = "t-skip-step0"
            projectRepo.recordSubmittedTask(proj.id, "Validate passing repo", "claude-3-7-sonnet", TaskState.VALIDATING, taskId)
            intelligenceRepo.saveTaskSnapshot(
                TaskStateSnapshot(
                    taskId = taskId,
                    projectId = proj.id,
                    objective = "Validate passing repo",
                    currentState = TaskState.VALIDATING,
                    activeStepIndex = 1,
                    planSteps = listOf(
                        PlanStep(0, "Implement changes", PlanStepStatus.COMPLETED),
                        PlanStep(1, "Run validation checks", PlanStepStatus.IN_PROGRESS),
                        PlanStep(2, "Verify workspace diff", PlanStepStatus.PENDING),
                    ),
                    keyDecisions = listOf("Step 0 finished before crash"),
                    modifiedFiles = emptyList(),
                    resumeCursor = TaskResumeCursor(checkpointId = null, fixAttempts = 0, lastError = null, lastEventSeq = 4L),
                    lastModelId = "claude-3-7-sonnet",
                    lastBackendId = "opencode",
                    updatedAtEpochMs = System.currentTimeMillis(),
                )
            )

            assertEquals(1, projectRepo.recoverInterruptedTasks(proj.id))
            assertEquals(1, intelligenceRepo.markActiveSnapshotsPaused(proj.id))
            val pausedSnap = intelligenceRepo.getTaskSnapshot(taskId)!!
            assertEquals(TaskState.PAUSED, pausedSnap.currentState)

            // Resume should skip TaskState.EXECUTING completely and jump PLANNING -> VALIDATING -> COMPLETED
            val events = mutableListOf<OrchestratorEvent>()
            val done = CompletableDeferred<Unit>()
            val job = launch {
                orchestrator.resumeTask(
                    ResumeTaskRequest(
                        taskId = taskId,
                        project = ProjectRef(proj.id, proj.name, proj.workspacePath),
                        modelId = "gemini-2.5-pro",
                        protocolMode = GatewayProtocolMode.CHAT_COMPLETIONS,
                        backendId = AgentBackendId.OPENCODE.id,
                    )
                ).collect { ev ->
                    events += ev
                    if (ev is OrchestratorEvent.SummaryReady) done.complete(Unit)
                }
            }
            withTimeout(10_000) {
                done.await()
                job.join()
            }

            val states = events.filterIsInstance<OrchestratorEvent.StateChanged>().map { it.state }
            assertFalse("Step 0 was COMPLETED cleanly so EXECUTING should be skipped", states.contains(TaskState.EXECUTING))
            assertTrue(states.contains(TaskState.VALIDATING))
            assertEquals(TaskState.COMPLETED, states.last())
            val summary = events.filterIsInstance<OrchestratorEvent.SummaryReady>().single().summary
            assertEquals(TaskState.COMPLETED, summary.finalState)
        }
    }

    @Test
    fun `FlightRecorderDialog exposes handoff backend and model controls and triggers resume callback`() {
        val pausedSnapshot = TaskStateSnapshot(
            taskId = "ui-handoff-task",
            projectId = "p1",
            objective = "Fix Gradle compilation error in MainActivity",
            currentState = TaskState.PAUSED,
            activeStepIndex = 1,
            planSteps = listOf(
                PlanStep(0, "Implement fix", PlanStepStatus.COMPLETED),
                PlanStep(1, "Run gradle check", PlanStepStatus.FAILED),
            ),
            resumeCursor = TaskResumeCursor(
                checkpointId = "chk-123456789",
                lastError = "Compile failed in MainActivity.kt",
                lastEventSeq = 7L,
            ),
            lastModelId = "claude-3-7-sonnet",
            lastBackendId = "opencode",
            keyDecisions = emptyList(),
            modifiedFiles = emptyList(),
            updatedAtEpochMs = System.currentTimeMillis(),
        )
        val models = listOf(
            GatewayModelInfo(
                id = "claude-3-7-sonnet",
                provider = "anthropic",
                supportsStreaming = true,
                supportsToolCalling = true,
            ),
            GatewayModelInfo(
                id = "gemini-2.5-pro",
                provider = "google",
                supportsStreaming = true,
                supportsToolCalling = true,
            ),
        )

        var capturedArgs: List<String?> = emptyList()
        composeRule.setContent {
            AgtTheme(themeMode = ThemeMode.LIGHT) {
                FlightRecorderDialog(
                    events = emptyList(),
                    totalEventCount = 0,
                    failureCount = 0,
                    selectedFilter = FlightRecorderFilter.ALL,
                    selectedTaskId = pausedSnapshot.taskId,
                    taskSnapshots = listOf(pausedSnapshot),
                    latestSnapshot = pausedSnapshot,
                    projectMemory = null,
                    onSelectFilter = {},
                    onSelectTask = {},
                    onInspectCheckpoint = {},
                    onDismiss = {},
                    availableModels = models,
                    activeModelId = "claude-3-7-sonnet",
                    activeBackendId = AgentBackendId.OPENCODE,
                    onResumeTask = { tId, modelId, backendId, note ->
                        capturedArgs = listOf(tId, modelId, backendId, note)
                    },
                )
            }
        }

        composeRule.onNodeWithTag("flight_recorder_handoff_section").performScrollTo().assertIsDisplayed()
        composeRule.onNodeWithTag("flight_handoff_backend_pi").performScrollTo().performClick()
        composeRule.onNodeWithTag("flight_handoff_model_option_gemini-2.5-pro").performScrollTo().performClick()
        composeRule.onNodeWithTag("flight_handoff_note_input").performScrollTo().performTextInput("Switch to Gemini")
        composeRule.onNodeWithTag("flight_recorder_resume_button").performScrollTo().performClick()

        assertEquals(
            listOf("ui-handoff-task", "gemini-2.5-pro", "pi", "Switch to Gemini"),
            capturedArgs,
        )
    }

    @Test
    fun `AgentView exposes resume banner and triggers resume callback`() {
        val pausedSnapshot = TaskStateSnapshot(
            taskId = "ui-handoff-task",
            projectId = "p1",
            objective = "Fix Gradle compilation error in MainActivity",
            currentState = TaskState.PAUSED,
            activeStepIndex = 1,
            planSteps = listOf(
                PlanStep(0, "Implement fix", PlanStepStatus.COMPLETED),
                PlanStep(1, "Run gradle check", PlanStepStatus.FAILED),
            ),
            resumeCursor = TaskResumeCursor(
                checkpointId = "chk-123456789",
                lastError = "Compile failed in MainActivity.kt",
                lastEventSeq = 7L,
            ),
            lastModelId = "claude-3-7-sonnet",
            lastBackendId = "opencode",
            keyDecisions = emptyList(),
            modifiedFiles = emptyList(),
            updatedAtEpochMs = System.currentTimeMillis(),
        )
        val models = listOf(
            GatewayModelInfo(
                id = "claude-3-7-sonnet",
                provider = "anthropic",
                supportsStreaming = true,
                supportsToolCalling = true,
            ),
            GatewayModelInfo(
                id = "gemini-2.5-pro",
                provider = "google",
                supportsStreaming = true,
                supportsToolCalling = true,
            ),
        )

        var bannerResumedTaskId: String? = null
        composeRule.setContent {
            AgtTheme(themeMode = ThemeMode.LIGHT) {
                AgentView(
                    state = WorkspaceUiState(
                        project = ProjectSummary(
                            id = "p1",
                            name = "demo",
                            workspacePath = "/tmp/demo",
                            runtimeMountPath = "/workspace",
                            stackLabel = "Gradle / JVM",
                            lastOpenedEpochMs = 1L,
                        ),
                        isLoadingProject = false,
                        availableModels = models,
                        selectedModelId = "gemini-2.5-pro",
                        activeAgentId = AgentBackendId.PI,
                        activeAgentInstalled = true,
                        taskSnapshots = listOf(pausedSnapshot),
                    ),
                    onComposerTextChanged = {},
                    onSelectModel = {},
                    onAttachDocument = { _, _ -> },
                    onAttachCameraBitmap = {},
                    onRemoveAttachment = {},
                    onExtensionAttachmentSelected = {},
                    onSendPrompt = {},
                    onStopStream = {},
                    onResumeTask = { taskId, _, _, _ -> bannerResumedTaskId = taskId },
                    onResolveApproval = { _, _ -> },
                    onResolvePlanApproval = { _, _ -> },
                    onOpenCheckpoints = {},
                    onPreviewCheckpointDiff = {},
                    onOpenGatewaySettings = {},
                    onOpenAgentSettings = {},
                )
            }
        }

        composeRule.onNodeWithTag("agent_resume_banner").assertIsDisplayed()
        composeRule.onNodeWithTag("agent_resume_button").assertIsDisplayed().performClick()
        assertEquals("ui-handoff-task", bannerResumedTaskId)
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
                  "runtime_id": "agt-ubuntu-m4",
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

    private fun installPiCliScript(scriptContent: String) {
        val binDir = File(runtimeInstaller.activeDir, "usr/local/bin").apply { mkdirs() }
        val binFile = File(binDir, "pi")
        binFile.writeText(scriptContent + "\n")
        binFile.setExecutable(true, false)
        val markerDir = File(runtimeInstaller.activeDir, ".agt-markers").apply { mkdirs() }
        File(markerDir, "pi_agent.json").writeText("""{"id":"pi_agent","version":"0.49.0-test"}""")
        piInstaller.refreshStatusSync()
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
