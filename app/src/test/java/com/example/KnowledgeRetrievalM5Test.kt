package com.example

import android.content.Context
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performTextInput
import androidx.room.Room
import androidx.sqlite.db.SupportSQLiteDatabase
import androidx.sqlite.db.SupportSQLiteOpenHelper
import androidx.sqlite.db.framework.FrameworkSQLiteOpenHelperFactory
import androidx.test.core.app.ApplicationProvider
import com.example.agent.opencode.OpenCodeAgentRuntime
import com.example.agent.opencode.OpenCodeInstallerRepository
import com.example.agent.orchestrator.AgentOrchestrator
import com.example.agent.orchestrator.HandoffContextBuilder
import com.example.agent.orchestrator.OrchestratorEvent
import com.example.agent.orchestrator.OrchestratorTaskRequest
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
import com.example.domain.model.FlightRecorderFilter
import com.example.domain.model.GatewayProtocolMode
import com.example.domain.model.KnowledgeCategory
import com.example.domain.model.PlanStep
import com.example.domain.model.PlanStepStatus
import com.example.domain.model.ProjectInspection
import com.example.domain.model.ProjectKnowledgeEntry
import com.example.domain.model.ProjectStack
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
class KnowledgeRetrievalM5Test {

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
    private lateinit var executor: LocalRuntimeExecutor
    private lateinit var openCodeRuntime: OpenCodeAgentRuntime
    private lateinit var orchestrator: AgentOrchestrator
    private lateinit var server: HttpServer
    private lateinit var baseUrl: String

    private val testSecret = "jh_live_phase5_fts_secret_token_774"

    private val testCipher = object : TokenCipher {
        override fun encrypt(plaintext: String): ByteArray =
            plaintext.toByteArray(StandardCharsets.UTF_8).map { (it.toInt() xor 0x42).toByte() }.toByteArray()

        override fun decrypt(payload: ByteArray): String =
            String(payload.map { (it.toInt() xor 0x42).toByte() }.toByteArray(), StandardCharsets.UTF_8)
    }

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        File(context.filesDir, "runtime").deleteRecursively()
        File(context.filesDir, "workspaces").deleteRecursively()
        File(context.filesDir, "checkpoints").deleteRecursively()
        context.deleteDatabase("test_phase5_knowledge.db")
        context.deleteDatabase("test_phase5_v5_v6.db")
        context.getSharedPreferences("agt_gateway_vault", Context.MODE_PRIVATE).edit().clear().commit()

        db = Room.databaseBuilder(context, AgtDatabase::class.java, "test_phase5_knowledge.db")
            .addMigrations(*AgtDatabase.ALL_MIGRATIONS)
            .addCallback(AgtDatabase.FTS_CALLBACK)
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
            projectKnowledgeDao = db.projectKnowledgeDao(),
            database = db,
        )
        handoffBuilder = HandoffContextBuilder(
            intelligenceRepository = intelligenceRepo,
            checkpointManager = checkpointManager,
            repoMapEngine = intelligenceRepo.repoMapEngine,
            knownSecretsProvider = { listOf(testSecret) },
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
            prefs.setAgentPolicy(20, 120, autoDebug = true, checkpoints = true, requirePlanApproval = false)
        }
        server.stop(0)
        db.close()
        context.deleteDatabase("test_phase5_knowledge.db")
        context.deleteDatabase("test_phase5_v5_v6.db")
        context.getSharedPreferences("agt_gateway_vault", Context.MODE_PRIVATE).edit().clear().commit()
        File(context.filesDir, "runtime").deleteRecursively()
        File(context.filesDir, "workspaces").deleteRecursively()
        File(context.filesDir, "checkpoints").deleteRecursively()
    }

    @Test
    fun `migration 5 to 6 preserves v5 data and installs FTS5 virtual table and sync triggers`() {
        runBlocking {
            val dbName = "test_phase5_v5_v6.db"
            context.deleteDatabase(dbName)

            val v5Helper = FrameworkSQLiteOpenHelperFactory().create(
                SupportSQLiteOpenHelper.Configuration.builder(context)
                    .name(dbName)
                    .callback(object : SupportSQLiteOpenHelper.Callback(5) {
                        override fun onCreate(db: SupportSQLiteDatabase) {
                            db.execSQL(
                                """
                                CREATE TABLE IF NOT EXISTS `projects` (
                                    `id` TEXT NOT NULL, `name` TEXT NOT NULL, `workspacePath` TEXT NOT NULL,
                                    `runtimeMountPath` TEXT NOT NULL, `remoteUrl` TEXT, `branchName` TEXT,
                                    `stackLabel` TEXT NOT NULL, `createdAtEpochMs` INTEGER NOT NULL,
                                    `lastOpenedEpochMs` INTEGER NOT NULL, PRIMARY KEY(`id`)
                                )
                                """.trimIndent()
                            )
                            db.execSQL(
                                """
                                CREATE TABLE IF NOT EXISTS `agent_tasks` (
                                    `id` TEXT NOT NULL, `projectId` TEXT NOT NULL, `prompt` TEXT NOT NULL,
                                    `state` TEXT NOT NULL, `modelId` TEXT, `createdAtEpochMs` INTEGER NOT NULL,
                                    `updatedAtEpochMs` INTEGER NOT NULL, `summaryJson` TEXT, PRIMARY KEY(`id`),
                                    FOREIGN KEY(`projectId`) REFERENCES `projects`(`id`) ON UPDATE NO ACTION ON DELETE CASCADE
                                )
                                """.trimIndent()
                            )
                            db.execSQL("CREATE INDEX IF NOT EXISTS `index_agent_tasks_projectId` ON `agent_tasks` (`projectId`)")
                            db.execSQL(
                                """
                                CREATE TABLE IF NOT EXISTS `checkpoints` (
                                    `id` TEXT NOT NULL, `projectId` TEXT NOT NULL, `reason` TEXT NOT NULL,
                                    `snapshotPath` TEXT NOT NULL, `gitHead` TEXT, `createdAtEpochMs` INTEGER NOT NULL,
                                    `fileCount` INTEGER NOT NULL DEFAULT 0, PRIMARY KEY(`id`),
                                    FOREIGN KEY(`projectId`) REFERENCES `projects`(`id`) ON UPDATE NO ACTION ON DELETE CASCADE
                                )
                                """.trimIndent()
                            )
                            db.execSQL("CREATE INDEX IF NOT EXISTS `index_checkpoints_projectId` ON `checkpoints` (`projectId`)")
                            db.execSQL(
                                """
                                CREATE TABLE IF NOT EXISTS `cached_models` (
                                    `id` TEXT NOT NULL, `provider` TEXT NOT NULL, `supportsStreaming` INTEGER,
                                    `supportsToolCalling` INTEGER, `supportsVision` INTEGER,
                                    `contextWindow` INTEGER, `lastSyncedEpochMs` INTEGER NOT NULL, PRIMARY KEY(`id`)
                                )
                                """.trimIndent()
                            )
                            db.execSQL(
                                """
                                CREATE TABLE IF NOT EXISTS `extensions` (
                                    `id` TEXT NOT NULL, `name` TEXT NOT NULL, `description` TEXT NOT NULL,
                                    `category` TEXT NOT NULL, `publisher` TEXT NOT NULL, `version` TEXT NOT NULL,
                                    `availableVersion` TEXT, `license` TEXT NOT NULL, `compatibility` TEXT NOT NULL,
                                    `sourceUri` TEXT NOT NULL, `sha256` TEXT NOT NULL, `permissionsCsv` TEXT NOT NULL,
                                    `installState` TEXT NOT NULL, `isBuiltIn` INTEGER NOT NULL, `endpointUrl` TEXT,
                                    `skillContentPath` TEXT, `discoveredToolsJson` TEXT, `healthMessage` TEXT,
                                    `installedAtEpochMs` INTEGER NOT NULL, `updatedAtEpochMs` INTEGER NOT NULL, PRIMARY KEY(`id`)
                                )
                                """.trimIndent()
                            )
                            AgtDatabase.MIGRATION_4_5.migrate(db)
                            db.execSQL(
                                "INSERT INTO `projects` VALUES ('proj-v5', 'V5Repo', '/tmp/v5', '/tmp/m5', NULL, NULL, 'Gradle Kotlin', 100, 200)"
                            )
                            db.execSQL(
                                "INSERT INTO `project_memory_entries` VALUES ('proj-v5', 'Gradle Kotlin', '[\"Use KSP\"]', '[]', '', 0, '[]', 200)"
                            )
                        }

                        override fun onUpgrade(db: SupportSQLiteDatabase, oldVersion: Int, newVersion: Int) = Unit
                    })
                    .build()
            )
            v5Helper.writableDatabase.close()
            v5Helper.close()

            val migratedDb = Room.databaseBuilder(context, AgtDatabase::class.java, dbName)
                .addMigrations(*AgtDatabase.ALL_MIGRATIONS)
                .addCallback(AgtDatabase.FTS_CALLBACK)
                .allowMainThreadQueries()
                .build()

            val migratedProject = migratedDb.projectDao().getProjectById("proj-v5")
            val migratedMemory = migratedDb.projectMemoryDao().getMemoryForProject("proj-v5")
            assertNotNull(migratedProject)
            assertNotNull(migratedMemory)
            assertEquals("Gradle Kotlin", migratedMemory!!.stackLabel)

            val sqliteDb = migratedDb.openHelper.writableDatabase
            val ftsReady = AgtDatabase.isFts5Active(sqliteDb) || AgtDatabase.isFts4FallbackActive(sqliteDb)
            assertTrue("Expected FTS5 or FTS4 table to exist after v5->v6 migration", ftsReady)

            val repo = DevelopmentIntelligenceRepository(
                chatMessageDao = migratedDb.chatMessageDao(),
                taskStateSnapshotDao = migratedDb.taskStateSnapshotDao(),
                projectMemoryDao = migratedDb.projectMemoryDao(),
                flightRecorderDao = migratedDb.flightRecorderDao(),
                projectKnowledgeDao = migratedDb.projectKnowledgeDao(),
                database = migratedDb,
            )
            val saved = repo.recordKnowledgeFact(
                projectId = "proj-v5",
                factKey = "arch:ksp",
                category = KnowledgeCategory.ARCHITECTURAL_DECISION,
                title = "Room uses KSP compiler",
                content = "Keep kapt disabled and process Room annotations via KSP 2.",
                tags = "room ksp gradle",
            )
            assertNotNull(saved)
            val search = repo.retrieveRelevantKnowledge("proj-v5", "KSP annotations Room")
            assertEquals(1, search.hits.size)
            assertEquals("arch:ksp", search.hits.first().entry.factKey)

            migratedDb.close()
            context.deleteDatabase(dbName)
        }
    }

    @Test
    fun `temporal superseding closes old factKey entries, supports as-of queries, and redacts secrets`() {
        runBlocking {
            val proj = projectRepo.createProject("temporal-knowledge-proj").getOrThrow()

            // T = 1000: Record initial architectural decision with an embedded secret
            val v1 = intelligenceRepo.recordKnowledgeFact(
                projectId = proj.id,
                factKey = "auth:strategy",
                category = KnowledgeCategory.ARCHITECTURAL_DECISION,
                title = "Use legacy static API key header",
                content = "Send X-Api-Key=$testSecret directly from client middleware.",
                tags = "auth middleware header",
                confidence = 0.8f,
                nowEpochMs = 1000L,
            )
            assertNotNull(v1)
            assertTrue(v1!!.isCurrentlyValid)
            assertFalse(v1.content.contains(testSecret))

            // Duplicate identical fact at T = 1500 reinforces without creating a second superseded row
            val reinforced = intelligenceRepo.recordKnowledgeFact(
                projectId = proj.id,
                factKey = "auth:strategy",
                category = KnowledgeCategory.ARCHITECTURAL_DECISION,
                title = "Use legacy static API key header",
                content = "Send X-Api-Key=$testSecret directly from client middleware.",
                confidence = 0.95f,
                nowEpochMs = 1500L,
            )
            assertEquals(v1.id, reinforced!!.id)
            assertEquals(0.95f, reinforced.confidence, 0.001f)

            // T = 2000: Supersede auth:strategy with OAuth2 PKCE + secret redaction
            val v2 = intelligenceRepo.recordKnowledgeFact(
                projectId = proj.id,
                factKey = "auth:strategy",
                category = KnowledgeCategory.ARCHITECTURAL_DECISION,
                title = "Migrate authentication to OAuth2 PKCE Bearer tokens",
                content = "Removed static header; refresh Bearer tokens cleanly (never store $testSecret).",
                tags = "auth oauth2 pkce bearer",
                confidence = 0.92f,
                nowEpochMs = 2000L,
            )
            assertNotNull(v2)
            assertTrue(v2!!.isCurrentlyValid)
            assertFalse(v2.content.contains(testSecret))

            // Inspect DB rows: v1 is closed out cleanly via supersededById and validUntilEpochMs == 2000L
            val allEntries = intelligenceRepo.observeAllKnowledge(proj.id).first()
            assertEquals(2, allEntries.size)
            val closedV1 = allEntries.first { it.id == v1.id }
            assertFalse(closedV1.isCurrentlyValid)
            assertEquals(v2.id, closedV1.supersededById)
            assertEquals(2000L, closedV1.validUntilEpochMs)

            // Default active retrieval excludes superseded v1
            val activeSearch = intelligenceRepo.retrieveRelevantKnowledge(
                projectId = proj.id,
                query = "legacy static header authentication",
            )
            assertFalse(activeSearch.hits.any { it.entry.id == v1.id })

            val activePkce = intelligenceRepo.retrieveRelevantKnowledge(
                projectId = proj.id,
                query = "OAuth2 PKCE authentication",
            )
            assertEquals(v2.id, activePkce.hits.first().entry.id)

            // Point-in-time query at T = 1600 sees v1 and NOT v2
            val asOfT1600 = intelligenceRepo.retrieveRelevantKnowledge(
                projectId = proj.id,
                query = "static header authentication",
                asOfEpochMs = 1600L,
            )
            assertTrue(asOfT1600.hits.any { it.entry.id == v1.id })
            assertFalse(asOfT1600.hits.any { it.entry.id == v2.id })

            // includeSuperseded = true surfaces full history
            val withHistory = intelligenceRepo.retrieveRelevantKnowledge(
                projectId = proj.id,
                query = "auth strategy header",
                includeSuperseded = true,
            )
            assertTrue(withHistory.hits.size >= 2)
        }
    }

    @Test
    fun `FTS5 bm25 ranking recalls targeted knowledge within strict token budget`() {
        runBlocking {
            val proj = projectRepo.createProject("fts5-budget-proj").getOrThrow()

            // Populate 15 unrelated facts + 2 highly relevant facts for PostgreSQL pooling
            for (i in 1..15) {
                intelligenceRepo.recordKnowledgeFact(
                    projectId = proj.id,
                    factKey = "ui:widget-$i",
                    category = KnowledgeCategory.CONVENTIONS,
                    title = "Widget styling rule #$i",
                    content = "Ensure Compose card component #$i keeps 16dp spacing and Material3 borders in view.",
                    tags = "compose ui theme card",
                    nowEpochMs = 1000L + i,
                )
            }
            intelligenceRepo.recordKnowledgeFact(
                projectId = proj.id,
                factKey = "fix:postgres-pool",
                category = KnowledgeCategory.KNOWN_FIX,
                title = "Fix PostgreSQL connection pool exhaustion",
                content = "Set HikariCP maximumPoolSize=12 and close prepared statements in transaction finally blocks.",
                tags = "postgres database hikaricp pool connection",
                confidence = 0.95f,
                nowEpochMs = 5000L,
            )
            intelligenceRepo.recordKnowledgeFact(
                projectId = proj.id,
                factKey = "arch:db-migrations",
                category = KnowledgeCategory.ARCHITECTURAL_DECISION,
                title = "PostgreSQL migration safety rule",
                content = "Always add concurrent indexes for large tables before altering nullable constraints.",
                tags = "postgres migrations sql",
                confidence = 0.9f,
                nowEpochMs = 5001L,
            )

            // Porter stemming ("exhausted" -> "exhaust", "connections" -> "connect") + token budget constraint
            val result = intelligenceRepo.retrieveRelevantKnowledge(
                projectId = proj.id,
                query = "PostgreSQL connections exhausted in database pool!",
                limit = 8,
                tokenBudget = 120,
            )

            assertTrue(result.hits.isNotEmpty())
            assertEquals("fix:postgres-pool", result.hits.first().entry.factKey)
            assertTrue(result.estimatedTokens <= 120)
            assertTrue(result.hits.none { it.entry.factKey.startsWith("ui:widget") })

            val promptBlock = result.toPromptContext(120)
            assertTrue(promptBlock.contains("[RECALLED LONG-TERM PROJECT KNOWLEDGE]"))
            assertTrue(promptBlock.contains("PostgreSQL"))
        }
    }

    @Test
    fun `orchestrator indexes validation fix into FTS5 knowledge and recalls it into next task and handoff packet`() {
        runBlocking {
            installBaseRuntime()
            val proj = projectRepo.createProject("orch-knowledge-e2e").getOrThrow()
            val root = File(proj.workspacePath)
            File(root, "config.txt").writeText("BROKEN\n")
            File(root, "test.sh").writeText(
                """
                #!/bin/sh
                if grep -q "FIXED_POOL" config.txt; then
                  echo "PASS"
                  exit 0
                else
                  echo "Error: pool exhausted in config.txt" >&2
                  exit 1
                fi
                """.trimIndent() + "\n"
            )

            val capturedPromptsFile = File(root, "captured_prompts.log")
            installOpenCodeCliScript(
                """
                #!/bin/sh
                PROMPT="${'$'}(cat "${'$'}AGT_PROMPT_FILE")"
                echo "===PROMPT===" >> "${capturedPromptsFile.absolutePath}"
                printf "%s\n" "${'$'}PROMPT" >> "${capturedPromptsFile.absolutePath}"
                if echo "${'$'}PROMPT" | grep -q "Validation failed"; then
                  printf "FIXED_POOL\n" > config.txt
                  echo '{"type":"tool_use","part":{"callID":"fix-1","tool":"edit","state":{"status":"completed","input":{"filePath":"config.txt"},"output":"FIXED_POOL"}}}'
                  echo '{"type":"text","part":{"text":"Updated config.txt"}}'
                  exit 0
                else
                  echo '{"type":"text","part":{"text":"Running check"}}'
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
                    {"data":[{"id":"gemini-2.5-pro","provider":"google","capabilities":{"streaming":true,"tool_calling":true,"vision":true,"context_window":1000000}}]}
                """.trimIndent().toByteArray()
                ex.sendResponseHeaders(200, json.size.toLong())
                ex.responseBody.use { it.write(json) }
            }
            gatewayRepo.saveConfiguration(baseUrl, GatewayProtocolMode.CHAT_COMPLETIONS, "jh_live_k5_token").getOrThrow()
            gatewayRepo.testHealthAndDiscoverModels()
            prefs.setAgentPolicy(20, 30, autoDebug = true, checkpoints = true, requirePlanApproval = false)

            // Run Task 1: fails validation first, auto-debugs, passes -> auto-indexes FlightRecorder resolution & completion decision
            val done1 = CompletableDeferred<Unit>()
            val job1 = launch {
                orchestrator.executeTask(
                    OrchestratorTaskRequest(
                        taskId = "task-k1",
                        project = ProjectRef(proj.id, proj.name, proj.workspacePath),
                        prompt = "Repair pool configuration in config.txt so test.sh passes",
                        modelId = "gemini-2.5-pro",
                        protocolMode = GatewayProtocolMode.CHAT_COMPLETIONS,
                        backendId = "opencode",
                    )
                ).collect { ev ->
                    if (ev is OrchestratorEvent.SummaryReady) done1.complete(Unit)
                }
            }
            withTimeout(15_000) {
                done1.await()
                job1.join()
            }

            val indexedAfterTask1 = intelligenceRepo.observeActiveKnowledge(proj.id).first()
            assertTrue("Expected auto-indexed knowledge entries after task 1", indexedAfterTask1.isNotEmpty())
            assertTrue(indexedAfterTask1.any { it.category == KnowledgeCategory.KNOWN_FIX })

            // Verify HandoffContextBuilder recalls that knowledge into a ModelHandoffPacket
            val inspection = ProjectInspection(
                projectId = proj.id,
                projectRootPath = proj.workspacePath,
                stack = ProjectStack.SHELL_MAKE,
                stackLabel = "Shell",
                entryPoints = listOf("test.sh", "config.txt"),
                manifestSummaries = listOf("test.sh"),
                directoryMap = listOf("test.sh", "config.txt"),
                validationCommands = emptyList(),
                isGitRepo = false,
            )
            val handoffPacket = handoffBuilder.buildPacket(
                snapshot = TaskStateSnapshot(
                    taskId = "task-handoff",
                    projectId = proj.id,
                    objective = "Check config.txt pool error regression",
                    currentState = TaskState.PAUSED,
                    activeStepIndex = 0,
                    planSteps = listOf(PlanStep(0, "Inspect", PlanStepStatus.PENDING)),
                    keyDecisions = emptyList(),
                    modifiedFiles = listOf("config.txt"),
                    resumeCursor = TaskResumeCursor(),
                    lastModelId = "gemini-2.5-pro",
                    lastBackendId = "opencode",
                    updatedAtEpochMs = System.currentTimeMillis(),
                ),
                inspection = inspection,
                targetModelId = "claude-3-7-sonnet",
                targetBackendId = "pi",
            )
            assertTrue(handoffPacket.recalledKnowledge.isNotEmpty())
            assertTrue(handoffPacket.toPromptContext().contains("Recalled [Known Fix]"))

            // Run Task 2: verify prompt injected [RECALLED LONG-TERM PROJECT KNOWLEDGE]
            capturedPromptsFile.writeText("")
            val done2 = CompletableDeferred<Unit>()
            val job2 = launch {
                orchestrator.executeTask(
                    OrchestratorTaskRequest(
                        taskId = "task-k2",
                        project = ProjectRef(proj.id, proj.name, proj.workspacePath),
                        prompt = "Verify config.txt pool fix stability",
                        modelId = "gemini-2.5-pro",
                        protocolMode = GatewayProtocolMode.CHAT_COMPLETIONS,
                        backendId = "opencode",
                    )
                ).collect { ev ->
                    if (ev is OrchestratorEvent.SummaryReady) done2.complete(Unit)
                }
            }
            withTimeout(15_000) {
                done2.await()
                job2.join()
            }
            val promptLog = capturedPromptsFile.readText()
            assertTrue(promptLog.contains("[RECALLED LONG-TERM PROJECT KNOWLEDGE]"))
        }
    }

    @Test
    fun `flight recorder dialog renders expandable Long-Term Knowledge FTS5 panel and search`() {
        val activeEntry = ProjectKnowledgeEntry(
            id = "k-active-1",
            projectId = "p1",
            factKey = "auth:token-refresh",
            category = KnowledgeCategory.KNOWN_FIX,
            title = "Refresh OAuth token cleanly",
            content = "Retry 401 once after refreshing bearer credential.",
            tags = "oauth bearer",
            confidence = 0.92f,
            validFromEpochMs = 2000L,
            createdAtEpochMs = 2000L,
            updatedAtEpochMs = 2000L,
        )
        val supersededEntry = ProjectKnowledgeEntry(
            id = "k-old-1",
            projectId = "p1",
            factKey = "auth:token-refresh",
            category = KnowledgeCategory.KNOWN_FIX,
            title = "Legacy retry loop",
            content = "Old static retry strategy.",
            confidence = 0.7f,
            validFromEpochMs = 1000L,
            validUntilEpochMs = 2000L,
            supersededById = "k-active-1",
            createdAtEpochMs = 1000L,
            updatedAtEpochMs = 2000L,
        )

        var query by mutableStateOf("")
        var includeSuperseded by mutableStateOf(false)

        composeRule.setContent {
            AgtTheme {
                FlightRecorderDialog(
                    events = emptyList(),
                    totalEventCount = 0,
                    failureCount = 0,
                    selectedFilter = FlightRecorderFilter.ALL,
                    selectedTaskId = null,
                    taskSnapshots = emptyList(),
                    latestSnapshot = null,
                    projectMemory = null,
                    onSelectFilter = {},
                    onSelectTask = {},
                    onInspectCheckpoint = {},
                    onDismiss = {},
                    knowledgeEntries = listOf(activeEntry, supersededEntry),
                    knowledgeSearchQuery = query,
                    knowledgeSearchResults = emptyList(),
                    knowledgeIncludeSuperseded = includeSuperseded,
                    onKnowledgeSearchChanged = { query = it },
                    onToggleKnowledgeSuperseded = { includeSuperseded = it },
                )
            }
        }

        composeRule.waitForIdle()
        composeRule.onNodeWithTag("flight_recorder_knowledge_card").assertIsDisplayed()
        composeRule.onNodeWithText("1 active · 1 superseded").assertIsDisplayed()

        // Expand Long-Term Knowledge section
        composeRule.onNodeWithTag("flight_recorder_knowledge_toggle").performClick()
        composeRule.waitForIdle()

        composeRule.onNodeWithTag("flight_recorder_knowledge_search").assertIsDisplayed()
        composeRule.onNodeWithTag("flight_recorder_knowledge_entry_k-active-1").assertIsDisplayed()
        assertEquals(0, composeRule.onAllNodesWithText("Legacy retry loop").fetchSemanticsNodes().size)

        // Enable Show superseded -> old entry becomes visible
        composeRule.onNodeWithTag("flight_recorder_knowledge_include_superseded").performClick()
        composeRule.waitForIdle()
        composeRule.onNodeWithTag("flight_recorder_knowledge_entry_k-old-1")
            .performScrollTo()
            .assertIsDisplayed()

        // Typing into search updates callback
        composeRule.onNodeWithTag("flight_recorder_knowledge_search")
            .performScrollTo()
            .performTextInput("oauth")
        composeRule.waitForIdle()
        assertEquals("oauth", query)
    }

    @Test
    fun `FTS4 fallback installs triggers cleanly and retrieves ranked hits when FTS5 is absent`() {
        runBlocking {
            val proj = projectRepo.createProject("fts4-fallback-proj").getOrThrow()
            val sqliteDb = db.openHelper.writableDatabase

            // Simulate a stock Android SQLite environment without FTS5 table active by dropping FTS5 and installing FTS4
            sqliteDb.execSQL("DROP TRIGGER IF EXISTS `trg_knowledge_fts5_ai`")
            sqliteDb.execSQL("DROP TRIGGER IF EXISTS `trg_knowledge_fts5_au`")
            sqliteDb.execSQL("DROP TRIGGER IF EXISTS `trg_knowledge_fts5_ad`")
            sqliteDb.execSQL("DROP TABLE IF EXISTS `${AgtDatabase.FTS5_TABLE_NAME}`")
            assertTrue(AgtDatabase.installFts4Fallback(sqliteDb))
            assertTrue(AgtDatabase.isFts4FallbackActive(sqliteDb))
            assertFalse(AgtDatabase.isFts5Active(sqliteDb))

            // Calling ensureKnowledgeFtsIndex again preserves FTS4 without recreating FTS5
            assertFalse(AgtDatabase.ensureKnowledgeFtsIndex(sqliteDb))
            assertTrue(AgtDatabase.isFts4FallbackActive(sqliteDb))
            assertFalse(AgtDatabase.isFts5Active(sqliteDb))

            intelligenceRepo.recordKnowledgeFact(
                projectId = proj.id,
                factKey = "ui:spacing",
                category = KnowledgeCategory.CONVENTIONS,
                title = "Card spacing guideline",
                content = "Keep 16dp padding across cards.",
                tags = "compose ui",
                nowEpochMs = 1000L,
            )
            intelligenceRepo.recordKnowledgeFact(
                projectId = proj.id,
                factKey = "fix:sqlite-fts4",
                category = KnowledgeCategory.KNOWN_FIX,
                title = "Use FTS4 porter tokenizer on stock Android SQLite",
                content = "Check PRAGMA compile_options before FTS5 so Android SQLiteLog never aborts.",
                tags = "sqlite fts4 android",
                confidence = 0.95f,
                nowEpochMs = 2000L,
            )

            val hits = intelligenceRepo.retrieveRelevantKnowledge(
                projectId = proj.id,
                query = "Android SQLite FTS4 porter tokenizer",
            )
            assertFalse(hits.usedFts5)
            assertTrue(hits.hits.isNotEmpty())
            assertEquals("fix:sqlite-fts4", hits.hits.first().entry.factKey)
        }
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
                  "runtime_id": "agt-ubuntu-m5",
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
