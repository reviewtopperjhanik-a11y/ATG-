package com.example

import android.content.Context
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollToNode
import androidx.room.Room
import androidx.sqlite.db.SupportSQLiteDatabase
import androidx.sqlite.db.SupportSQLiteOpenHelper
import androidx.sqlite.db.framework.FrameworkSQLiteOpenHelperFactory
import androidx.test.core.app.ApplicationProvider
import com.example.agent.opencode.OpenCodeAgentRuntime
import com.example.agent.opencode.OpenCodeInstallerRepository
import com.example.agent.orchestrator.AgentOrchestrator
import com.example.agent.orchestrator.OrchestratorEvent
import com.example.agent.orchestrator.OrchestratorTaskRequest
import com.example.core.security.GatewayCredentialVault
import com.example.core.security.TokenCipher
import com.example.data.database.AgtDatabase
import com.example.data.database.ChatMessageEntity
import com.example.data.database.FlightRecorderEventEntity
import com.example.data.database.ProjectMemoryEntity
import com.example.data.database.TaskStateSnapshotEntity
import com.example.data.gateway.GatewayRepository
import com.example.data.intelligence.DevelopmentIntelligenceRepository
import com.example.data.preferences.AppPreferencesRepository
import com.example.data.project.ProjectRepository
import com.example.data.project.WorkspaceCheckpointManager
import com.example.data.runtime.LocalRuntimeExecutor
import com.example.data.runtime.RuntimeInstallerRepository
import com.example.domain.contracts.AgentEvent
import com.example.domain.contracts.ProjectRef
import com.example.domain.model.ChatMessageRecord
import com.example.domain.model.ChatMessageRole
import com.example.domain.model.FlightRecorderEvent
import com.example.domain.model.FlightRecorderEventType
import com.example.domain.model.FlightRecorderFilter
import com.example.domain.model.FlightRecorderOutcome
import com.example.domain.model.GatewayProtocolMode
import com.example.domain.model.KnownFixEntry
import com.example.domain.model.PlanStep
import com.example.domain.model.PlanStepStatus
import com.example.domain.model.ProjectMemory
import com.example.domain.model.TaskResumeCursor
import com.example.domain.model.TaskState
import com.example.domain.model.TaskStateSnapshot
import com.example.feature.workspace.FlightRecorderDialog
import com.example.ui.theme.AgtTheme
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
class FlightRecorderM2Test {

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
    private lateinit var runtimeInstaller: RuntimeInstallerRepository
    private lateinit var openCodeInstaller: OpenCodeInstallerRepository
    private lateinit var executor: LocalRuntimeExecutor
    private lateinit var openCodeRuntime: OpenCodeAgentRuntime
    private lateinit var orchestrator: AgentOrchestrator
    private lateinit var server: HttpServer
    private lateinit var baseUrl: String

    private val testSecret = "jh_live_phase2_super_secret_token_991"

    private val testCipher = object : TokenCipher {
        override fun encrypt(plaintext: String): ByteArray =
            plaintext.toByteArray(StandardCharsets.UTF_8).map { (it.toInt() xor 0x3D).toByte() }.toByteArray()

        override fun decrypt(payload: ByteArray): String =
            String(payload.map { (it.toInt() xor 0x3D).toByte() }.toByteArray(), StandardCharsets.UTF_8)
    }

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        File(context.filesDir, "runtime").deleteRecursively()
        File(context.filesDir, "workspaces").deleteRecursively()
        File(context.filesDir, "checkpoints").deleteRecursively()
        context.deleteDatabase("test_phase2_intel.db")
        context.deleteDatabase("test_phase2_migration.db")
        context.getSharedPreferences("agt_gateway_vault", Context.MODE_PRIVATE).edit().clear().commit()

        db = Room.databaseBuilder(context, AgtDatabase::class.java, "test_phase2_intel.db")
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
            intelligenceRepository = intelligenceRepo,
        )
        orchestrator = AgentOrchestrator(
            projectRepository = projectRepo,
            checkpointManager = checkpointManager,
            preferencesRepository = prefs,
            runtimeExecutor = AgentOrchestrator.LocalRuntimeExecutorFacade(executor),
            agentRuntime = openCodeRuntime,
            maxFixRetries = 2,
            intelligenceRepository = intelligenceRepo,
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
        context.deleteDatabase("test_phase2_intel.db")
        context.deleteDatabase("test_phase2_migration.db")
        context.getSharedPreferences("agt_gateway_vault", Context.MODE_PRIVATE).edit().clear().commit()
        File(context.filesDir, "runtime").deleteRecursively()
        File(context.filesDir, "workspaces").deleteRecursively()
        File(context.filesDir, "checkpoints").deleteRecursively()
    }

    @Test
    fun `migration 4 to 5 upgrades v4 database preserving rows and enabling all 4 intelligence tables`() {
        runBlocking {
        val dbName = "test_phase2_migration.db"
        context.deleteDatabase(dbName)

        // 1. Create a realistic v4 SQLite database using SupportSQLiteOpenHelper directly
        val v4Helper = FrameworkSQLiteOpenHelperFactory().create(
            SupportSQLiteOpenHelper.Configuration.builder(context)
                .name(dbName)
                .callback(object : SupportSQLiteOpenHelper.Callback(4) {
                    override fun onCreate(db: SupportSQLiteDatabase) {
                        db.execSQL(
                            """
                            CREATE TABLE IF NOT EXISTS `projects` (
                                `id` TEXT NOT NULL,
                                `name` TEXT NOT NULL,
                                `workspacePath` TEXT NOT NULL,
                                `runtimeMountPath` TEXT NOT NULL,
                                `remoteUrl` TEXT,
                                `branchName` TEXT,
                                `stackLabel` TEXT NOT NULL,
                                `createdAtEpochMs` INTEGER NOT NULL,
                                `lastOpenedEpochMs` INTEGER NOT NULL,
                                PRIMARY KEY(`id`)
                            )
                            """.trimIndent()
                        )
                        db.execSQL(
                            """
                            CREATE TABLE IF NOT EXISTS `agent_tasks` (
                                `id` TEXT NOT NULL,
                                `projectId` TEXT NOT NULL,
                                `prompt` TEXT NOT NULL,
                                `state` TEXT NOT NULL,
                                `modelId` TEXT,
                                `createdAtEpochMs` INTEGER NOT NULL,
                                `updatedAtEpochMs` INTEGER NOT NULL,
                                `summaryJson` TEXT,
                                PRIMARY KEY(`id`),
                                FOREIGN KEY(`projectId`) REFERENCES `projects`(`id`) ON UPDATE NO ACTION ON DELETE CASCADE
                            )
                            """.trimIndent()
                        )
                        db.execSQL("CREATE INDEX IF NOT EXISTS `index_agent_tasks_projectId` ON `agent_tasks` (`projectId`)")
                        db.execSQL(
                            """
                            CREATE TABLE IF NOT EXISTS `checkpoints` (
                                `id` TEXT NOT NULL,
                                `projectId` TEXT NOT NULL,
                                `reason` TEXT NOT NULL,
                                `snapshotPath` TEXT NOT NULL,
                                `gitHead` TEXT,
                                `createdAtEpochMs` INTEGER NOT NULL,
                                `fileCount` INTEGER NOT NULL DEFAULT 0,
                                PRIMARY KEY(`id`),
                                FOREIGN KEY(`projectId`) REFERENCES `projects`(`id`) ON UPDATE NO ACTION ON DELETE CASCADE
                            )
                            """.trimIndent()
                        )
                        db.execSQL("CREATE INDEX IF NOT EXISTS `index_checkpoints_projectId` ON `checkpoints` (`projectId`)")
                        db.execSQL(
                            """
                            CREATE TABLE IF NOT EXISTS `cached_models` (
                                `id` TEXT NOT NULL,
                                `provider` TEXT NOT NULL,
                                `supportsStreaming` INTEGER,
                                `supportsToolCalling` INTEGER,
                                `supportsVision` INTEGER,
                                `contextWindow` INTEGER,
                                `lastSyncedEpochMs` INTEGER NOT NULL,
                                PRIMARY KEY(`id`)
                            )
                            """.trimIndent()
                        )
                        db.execSQL(
                            """
                            CREATE TABLE IF NOT EXISTS `extensions` (
                                `id` TEXT NOT NULL,
                                `name` TEXT NOT NULL,
                                `description` TEXT NOT NULL,
                                `category` TEXT NOT NULL,
                                `publisher` TEXT NOT NULL,
                                `version` TEXT NOT NULL,
                                `availableVersion` TEXT,
                                `license` TEXT NOT NULL,
                                `compatibility` TEXT NOT NULL,
                                `sourceUri` TEXT NOT NULL,
                                `sha256` TEXT NOT NULL,
                                `permissionsCsv` TEXT NOT NULL,
                                `installState` TEXT NOT NULL,
                                `isBuiltIn` INTEGER NOT NULL,
                                `endpointUrl` TEXT,
                                `skillContentPath` TEXT,
                                `discoveredToolsJson` TEXT,
                                `healthMessage` TEXT,
                                `installedAtEpochMs` INTEGER NOT NULL,
                                `updatedAtEpochMs` INTEGER NOT NULL,
                                PRIMARY KEY(`id`)
                            )
                            """.trimIndent()
                        )

                        // Seed pre-migration rows
                        db.execSQL(
                            "INSERT INTO `projects` VALUES ('proj-v4', 'LegacyApp', '/tmp/p', '/tmp/m', NULL, NULL, 'Node.js', 100, 200)"
                        )
                        db.execSQL(
                            "INSERT INTO `agent_tasks` VALUES ('task-v4', 'proj-v4', 'Build feature', 'COMPLETED', 'claude-3-7', 100, 200, NULL)"
                        )
                    }

                    override fun onUpgrade(db: SupportSQLiteDatabase, oldVersion: Int, newVersion: Int) = Unit
                })
                .build()
        )
        v4Helper.writableDatabase.close()
        v4Helper.close()

        // 2. Open with Room at latest version using ALL_MIGRATIONS (no fallbackToDestructiveMigration!)
        val migratedDb = Room.databaseBuilder(context, AgtDatabase::class.java, dbName)
            .addMigrations(*AgtDatabase.ALL_MIGRATIONS)
            .allowMainThreadQueries()
            .build()

        // Existing v4 records survived intact
        val project = migratedDb.projectDao().getProjectById("proj-v4")
        val task = migratedDb.taskDao().getTaskById("task-v4")
        assertNotNull(project)
        assertEquals("LegacyApp", project!!.name)
        assertNotNull(task)
        assertEquals("Build feature", task!!.prompt)

        // All 4 new tables accept inserts & reads cleanly
        migratedDb.chatMessageDao().upsertMessage(
            ChatMessageEntity(
                id = "msg-1",
                projectId = "proj-v4",
                taskId = "task-v4",
                role = "USER",
                content = "Build feature",
                createdAtEpochMs = 300L,
            )
        )
        migratedDb.taskStateSnapshotDao().upsertSnapshot(
            TaskStateSnapshotEntity(
                taskId = "task-v4",
                projectId = "proj-v4",
                objective = "Build feature",
                currentState = "COMPLETED",
                activeStepIndex = 2,
                serializedPlanStepsJson = "[]",
                keyDecisionsJson = "[]",
                modifiedFilesJson = "[]",
                resumePayloadJson = "{}",
                lastModelId = "claude-3-7",
                lastBackendId = "opencode",
                updatedAtEpochMs = 310L,
            )
        )
        migratedDb.projectMemoryDao().upsertMemory(
            ProjectMemoryEntity(
                projectId = "proj-v4",
                stackLabel = "Node.js",
                curatedRulesJson = "[\"Keep API stable\"]",
                validationMetadataJson = "[\"Tests: npm test\"]",
                repoMapCache = "",
                repoMapUpdatedAtEpochMs = 0L,
                knownFixesJson = "[]",
                updatedAtEpochMs = 320L,
            )
        )
        migratedDb.flightRecorderDao().insertEvent(
            FlightRecorderEventEntity(
                id = "ev-1",
                projectId = "proj-v4",
                taskId = "task-v4",
                seq = 1L,
                eventType = "STATE_TRANSITION",
                outcome = "PASSED",
                outputExcerpt = "Done",
                timestampMs = 330L,
            )
        )

        assertEquals(1, migratedDb.chatMessageDao().observeMessagesForProject("proj-v4").first().size)
        assertNotNull(migratedDb.taskStateSnapshotDao().getSnapshotByTaskId("task-v4"))
        assertNotNull(migratedDb.projectMemoryDao().getMemoryForProject("proj-v4"))
        assertEquals(1, migratedDb.flightRecorderDao().getEventsForTask("task-v4").size)

        migratedDb.close()
        context.deleteDatabase(dbName)
        }
    }

    @Test
    fun `intelligence repository redacts secrets, maintains monotonic seq across restarts, and decouples chat from snapshots`() {
        runBlocking {
        val proj = projectRepo.createProject("intel-core-proj").getOrThrow()
        val taskId = "task-seq-1"
        projectRepo.recordSubmittedTask(proj.id, "Fix auth header", "gemini-2.5-pro", TaskState.CREATED, taskId)

        // Record events with an embedded secret
        val seq1 = intelligenceRepo.recordFlightEvent(
            projectId = proj.id,
            taskId = taskId,
            eventType = FlightRecorderEventType.TOOL_CHECK,
            outcome = FlightRecorderOutcome.REJECTED,
            toolName = "bash",
            commandOrPath = "curl -H 'Authorization: Bearer $testSecret' https://example.com",
            outputExcerpt = "Blocked secret $testSecret in command",
        )
        val seq2 = intelligenceRepo.recordFlightEvent(
            projectId = proj.id,
            taskId = taskId,
            eventType = FlightRecorderEventType.VALIDATION_RUN,
            outcome = FlightRecorderOutcome.FAILED,
            toolName = "Tests",
            commandOrPath = "npm test",
            exitCode = 1,
            outputExcerpt = "Assertion failed with token=$testSecret",
        )
        assertEquals(1L, seq1)
        assertEquals(2L, seq2)

        // Re-create repository instance (simulating process restart without cached seq map)
        val restartedRepo = DevelopmentIntelligenceRepository(
            chatMessageDao = db.chatMessageDao(),
            taskStateSnapshotDao = db.taskStateSnapshotDao(),
            projectMemoryDao = db.projectMemoryDao(),
            flightRecorderDao = db.flightRecorderDao(),
            knownSecretsProvider = { listOf(testSecret) },
        )
        val seq3 = restartedRepo.recordFlightEvent(
            projectId = proj.id,
            taskId = taskId,
            eventType = FlightRecorderEventType.STATE_TRANSITION,
            outcome = FlightRecorderOutcome.PASSED,
            commandOrPath = "VALIDATING -> COMPLETED",
        )
        assertEquals(3L, seq3)

        val events = restartedRepo.getFlightEventsForTask(taskId)
        assertEquals(3, events.size)
        events.forEach { ev ->
            assertFalse(ev.commandOrPath.orEmpty().contains(testSecret))
            assertFalse(ev.outputExcerpt.contains(testSecret))
        }

        // Verify chat layer is independent from TaskStateSnapshot handoff state
        restartedRepo.appendChatMessage(
            ChatMessageRecord(
                id = "chat-u1",
                projectId = proj.id,
                taskId = taskId,
                role = ChatMessageRole.USER,
                content = "Please redact $testSecret",
                createdAtEpochMs = 1000L,
            )
        )
        restartedRepo.saveTaskSnapshot(
            TaskStateSnapshot(
                taskId = taskId,
                projectId = proj.id,
                objective = "Fix auth with $testSecret",
                currentState = TaskState.PAUSED,
                activeStepIndex = 1,
                planSteps = listOf(PlanStep(0, "Implement", PlanStepStatus.COMPLETED), PlanStep(1, "Validate", PlanStepStatus.IN_PROGRESS)),
                keyDecisions = listOf("Used Bearer redaction for $testSecret"),
                modifiedFiles = listOf("src/auth.ts"),
                resumeCursor = TaskResumeCursor(
                    checkpointId = "chk-99",
                    fixAttempts = 1,
                    lastError = "Failure with $testSecret",
                    lastEventSeq = seq3,
                ),
                lastModelId = "gemini-2.5-pro",
                lastBackendId = "opencode",
                updatedAtEpochMs = 1100L,
            )
        )

        val chat = restartedRepo.observeChatMessages(proj.id).first()
        val snap = restartedRepo.getTaskSnapshot(taskId)
        assertEquals(1, chat.size)
        assertFalse(chat.first().content.contains(testSecret))
        assertNotNull(snap)
        assertEquals(1, snap!!.activeStepIndex)
        assertEquals(3L, snap.resumeCursor.lastEventSeq)
        assertFalse(snap.objective.contains(testSecret))
        assertFalse(snap.resumeCursor.lastError.orEmpty().contains(testSecret))
        assertEquals("src/auth.ts", snap.modifiedFiles.single())
        }
    }

    @Test
    fun `orchestrator populates flight recorder timeline, snapshots, and project memory end-to-end`() {
        runBlocking {
        installBaseRuntime()
        val proj = projectRepo.createProject("fr-e2e-proj").getOrThrow()
        val root = File(proj.workspacePath)
        File(root, "status.txt").writeText("INIT\n")
        File(root, "test.sh").writeText(
            """
            #!/bin/sh
            if grep -q "OK_DONE" status.txt; then
              echo "PASS"
              exit 0
            else
              echo "FAIL" >&2
              exit 1
            fi
            """.trimIndent() + "\n"
        )

        // OpenCode emits an allowed tool_use on pass 1 (writing BROKEN), then fixes status.txt on pass 2.
        installOpenCodeCliScript(
            """
            #!/bin/sh
            PROMPT="${'$'}(cat "${'$'}AGT_PROMPT_FILE")"
            if echo "${'$'}PROMPT" | grep -q "Validation failed"; then
              printf "OK_DONE\n" > status.txt
              echo '{"type":"tool_use","part":{"callID":"fix-edit","tool":"edit","state":{"status":"completed","input":{"filePath":"status.txt"},"output":"OK_DONE written"}}}'
              echo '{"type":"text","part":{"text":"Fixed status.txt"}}'
              exit 0
            else
              printf "BROKEN\n" > status.txt
              echo '{"type":"tool_use","part":{"callID":"init-edit","tool":"edit","state":{"status":"completed","input":{"filePath":"status.txt"},"output":"BROKEN written"}}}'
              echo '{"type":"text","part":{"text":"Initial write"}}'
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
        gatewayRepo.saveConfiguration(baseUrl, GatewayProtocolMode.CHAT_COMPLETIONS, "jh_live_fr_token").getOrThrow()
        gatewayRepo.testHealthAndDiscoverModels()
        prefs.setAgentPolicy(20, 30, autoDebug = true, checkpoints = true, requirePlanApproval = false)

        val taskId = "fr-e2e-task"
        val finished = CompletableDeferred<Unit>()
        val job = launch {
            orchestrator.executeTask(
                OrchestratorTaskRequest(
                    taskId = taskId,
                    project = ProjectRef(proj.id, proj.name, proj.workspacePath),
                    prompt = "Make test.sh pass cleanly",
                    modelId = "claude-3-7-sonnet",
                    protocolMode = GatewayProtocolMode.CHAT_COMPLETIONS,
                    backendId = "opencode",
                )
            ).collect { ev ->
                if (ev is OrchestratorEvent.SummaryReady) {
                    finished.complete(Unit)
                }
            }
        }

        withTimeout(15_000) {
            finished.await()
            job.join()
        }

        val flightEvents = intelligenceRepo.getFlightEventsForTask(taskId)
        assertTrue(flightEvents.isNotEmpty())
        assertEquals((1L..flightEvents.size.toLong()).toList(), flightEvents.map { it.seq })

        assertTrue(flightEvents.any { it.eventType == FlightRecorderEventType.STATE_TRANSITION && it.commandOrPath?.contains("COMPLETED") == true })
        assertTrue(flightEvents.any { it.eventType == FlightRecorderEventType.CHECKPOINT && it.outcome == FlightRecorderOutcome.PASSED })
        assertTrue(flightEvents.any { it.eventType == FlightRecorderEventType.PLAN_DECISION })
        assertTrue(flightEvents.any { it.eventType == FlightRecorderEventType.TOOL_CHECK && it.outcome == FlightRecorderOutcome.ALLOWED })
        assertTrue(flightEvents.any { it.eventType == FlightRecorderEventType.TOOL_EXECUTION && it.toolName == "edit" })
        assertTrue(flightEvents.any { it.eventType == FlightRecorderEventType.VALIDATION_RUN && it.outcome == FlightRecorderOutcome.FAILED && it.exitCode == 1 })
        assertTrue(flightEvents.any { it.eventType == FlightRecorderEventType.FIX_LOOP })
        assertTrue(flightEvents.any { it.eventType == FlightRecorderEventType.VALIDATION_RUN && it.outcome == FlightRecorderOutcome.PASSED && it.exitCode == 0 })

        val snapshot = intelligenceRepo.getTaskSnapshot(taskId)
        assertNotNull(snapshot)
        assertEquals(TaskState.COMPLETED, snapshot!!.currentState)
        assertEquals("claude-3-7-sonnet", snapshot.lastModelId)
        assertEquals("opencode", snapshot.lastBackendId)
        assertTrue(snapshot.modifiedFiles.any { it.contains("status.txt") })
        assertTrue(snapshot.resumeCursor.lastEventSeq >= flightEvents.last().seq)

        val memory = intelligenceRepo.observeProjectMemory(proj.id).first()
        assertNotNull(memory)
        assertTrue(memory!!.knownFixes.isNotEmpty())
        }
    }

    @Test
    fun `orchestrator records tool broker rejection and runtime error in flight recorder`() {
        runBlocking {
        installBaseRuntime()
        val proj = projectRepo.createProject("fr-reject-proj").getOrThrow()
        installOpenCodeCliScript(
            """
            #!/bin/sh
            echo '{"type":"tool_use","part":{"callID":"bad-read","tool":"read","state":{"status":"completed","input":{"filePath":"../../etc/passwd"},"output":"denied"}}}'
            exit 0
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
        gatewayRepo.saveConfiguration(baseUrl, GatewayProtocolMode.CHAT_COMPLETIONS, "jh_live_fr_rej").getOrThrow()
        gatewayRepo.testHealthAndDiscoverModels()
        prefs.setAgentPolicy(20, 30, autoDebug = false, checkpoints = false, requirePlanApproval = false)

        val taskId = "fr-reject-task"
        val finished = CompletableDeferred<Unit>()
        val job = launch {
            orchestrator.executeTask(
                OrchestratorTaskRequest(
                    taskId = taskId,
                    project = ProjectRef(proj.id, proj.name, proj.workspacePath),
                    prompt = "Attempt out-of-bounds read",
                    modelId = "claude-3-7-sonnet",
                    protocolMode = GatewayProtocolMode.CHAT_COMPLETIONS,
                    backendId = "opencode",
                )
            ).collect { ev ->
                if (ev is OrchestratorEvent.SummaryReady) {
                    finished.complete(Unit)
                }
            }
        }
        withTimeout(15_000) {
            finished.await()
            job.join()
        }

        val flightEvents = intelligenceRepo.getFlightEventsForTask(taskId)
        assertTrue(flightEvents.any { it.eventType == FlightRecorderEventType.TOOL_CHECK && it.outcome == FlightRecorderOutcome.REJECTED })
        assertTrue(flightEvents.any { it.eventType == FlightRecorderEventType.RUNTIME_ERROR && it.outcome == FlightRecorderOutcome.FAILED })
        val snapshot = intelligenceRepo.getTaskSnapshot(taskId)
        assertNotNull(snapshot)
        assertEquals(TaskState.FAILED, snapshot!!.currentState)
        assertFalse(snapshot.resumeCursor.lastError.isNullOrBlank())
        }
    }

    @Test
    fun `flight recorder dialog displays resume cursor, failure counts, and filters events interactively`() {
        val sampleEvents = listOf(
            FlightRecorderEvent(
                id = "ev-fail",
                projectId = "p1",
                taskId = "t1",
                seq = 3L,
                eventType = FlightRecorderEventType.VALIDATION_RUN,
                toolName = "Tests",
                commandOrPath = "npm test",
                outcome = FlightRecorderOutcome.FAILED,
                exitCode = 1,
                outputExcerpt = "Expected 200 but got 500",
                modelId = "gemini-2.5-pro",
                backendId = "opencode",
                stepIndex = 1,
                timestampMs = 1_700_000_003_000L,
            ),
            FlightRecorderEvent(
                id = "ev-tool",
                projectId = "p1",
                taskId = "t1",
                seq = 2L,
                eventType = FlightRecorderEventType.TOOL_CHECK,
                toolName = "read",
                commandOrPath = "../../etc/passwd",
                outcome = FlightRecorderOutcome.REJECTED,
                outputExcerpt = "Path traversal blocked",
                modelId = "gemini-2.5-pro",
                backendId = "opencode",
                stepIndex = 0,
                timestampMs = 1_700_000_002_000L,
            ),
            FlightRecorderEvent(
                id = "ev-state",
                projectId = "p1",
                taskId = "t1",
                seq = 1L,
                eventType = FlightRecorderEventType.STATE_TRANSITION,
                commandOrPath = "CREATED -> CONTEXT_LOADING",
                outcome = FlightRecorderOutcome.INFO,
                modelId = "gemini-2.5-pro",
                backendId = "opencode",
                stepIndex = 0,
                timestampMs = 1_700_000_001_000L,
            ),
        )
        val snapshot = TaskStateSnapshot(
            taskId = "t1",
            projectId = "p1",
            objective = "Repair login endpoint tests",
            currentState = TaskState.FAILED,
            activeStepIndex = 1,
            planSteps = listOf(
                PlanStep(0, "Implement", PlanStepStatus.COMPLETED),
                PlanStep(1, "Validate", PlanStepStatus.FAILED),
            ),
            keyDecisions = listOf("Used existing router"),
            modifiedFiles = listOf("src/login.ts"),
            resumeCursor = TaskResumeCursor(
                checkpointId = "chk-abcdef123456",
                fixAttempts = 1,
                lastError = "Tests failed (exit=1)",
                lastEventSeq = 3L,
            ),
            lastModelId = "gemini-2.5-pro",
            lastBackendId = "opencode",
            updatedAtEpochMs = 1_700_000_003_000L,
        )
        val memory = ProjectMemory(
            projectId = "p1",
            stackLabel = "Node.js",
            curatedRules = listOf("Use strict typing"),
            validationCommands = listOf("Tests: npm test"),
            repoMapCache = "",
            repoMapUpdatedAtEpochMs = 0L,
            knownFixes = listOf(KnownFixEntry("Tests", "Fixed status code", 1_700_000_000_000L)),
            updatedAtEpochMs = 1_700_000_003_000L,
        )

        var activeFilter by mutableStateOf(FlightRecorderFilter.ALL)
        var inspectedCheckpoint: String? = null
        var dismissed = false

        composeRule.setContent {
            AgtTheme {
                val filtered = when (activeFilter) {
                    FlightRecorderFilter.ALL -> sampleEvents
                    FlightRecorderFilter.FAILURES -> sampleEvents.filter { it.isFailureOrBlock }
                    FlightRecorderFilter.TOOLS -> sampleEvents.filter {
                        it.eventType == FlightRecorderEventType.TOOL_CHECK || it.eventType == FlightRecorderEventType.TOOL_EXECUTION
                    }
                    FlightRecorderFilter.VALIDATION -> sampleEvents.filter { it.eventType == FlightRecorderEventType.VALIDATION_RUN }
                    FlightRecorderFilter.STATES -> sampleEvents.filter { it.eventType == FlightRecorderEventType.STATE_TRANSITION }
                }
                FlightRecorderDialog(
                    events = filtered,
                    totalEventCount = sampleEvents.size,
                    failureCount = sampleEvents.count { it.isFailureOrBlock },
                    selectedFilter = activeFilter,
                    selectedTaskId = null,
                    taskSnapshots = listOf(snapshot),
                    latestSnapshot = snapshot,
                    projectMemory = memory,
                    onSelectFilter = { activeFilter = it },
                    onSelectTask = {},
                    onInspectCheckpoint = { inspectedCheckpoint = it },
                    onDismiss = { dismissed = true },
                )
            }
        }

        composeRule.waitForIdle()
        composeRule.onNodeWithTag("flight_recorder_resume_card").assertIsDisplayed()
        composeRule.onNodeWithText("Repair login endpoint tests").assertIsDisplayed()
        composeRule.onNodeWithTag("flight_filter_failures").assertIsDisplayed()

        // Checkpoint button triggers callback while resume card is in view
        composeRule.onNodeWithTag("flight_recorder_checkpoint_btn").performClick()
        composeRule.waitForIdle()
        assertEquals("chk-abcdef123456", inspectedCheckpoint)

        // Scroll to events in LazyColumn and verify both fail and state events render
        composeRule.onNodeWithTag("flight_recorder_event_list")
            .performScrollToNode(hasTestTag("flight_event_ev-fail"))
        composeRule.onNodeWithTag("flight_event_ev-fail").assertIsDisplayed()

        composeRule.onNodeWithTag("flight_recorder_event_list")
            .performScrollToNode(hasTestTag("flight_event_ev-state"))
        composeRule.onNodeWithTag("flight_event_ev-state").assertIsDisplayed()

        // Filter to Validation only
        composeRule.onNodeWithTag("flight_filter_validation").performClick()
        composeRule.waitForIdle()
        composeRule.onNodeWithTag("flight_recorder_event_list")
            .performScrollToNode(hasTestTag("flight_event_ev-fail"))
        assertEquals(1, composeRule.onAllNodesWithTag("flight_event_ev-fail").fetchSemanticsNodes().size)
        assertEquals(0, composeRule.onAllNodesWithTag("flight_event_ev-state").fetchSemanticsNodes().size)

        // Close button triggers dismiss
        composeRule.onNodeWithTag("flight_recorder_close_button").performClick()
        composeRule.waitForIdle()
        assertTrue(dismissed)
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
                  "runtime_id": "agt-ubuntu-m2",
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
