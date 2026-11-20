package com.example

import android.content.Context
import androidx.room.Room
import androidx.sqlite.db.SupportSQLiteDatabase
import androidx.sqlite.db.SupportSQLiteOpenHelper
import androidx.sqlite.db.framework.FrameworkSQLiteOpenHelperFactory
import androidx.test.core.app.ApplicationProvider
import com.example.agent.opencode.OpenCodeAgentRuntime
import com.example.agent.opencode.OpenCodeInstallerRepository
import com.example.agent.orchestrator.AgentOrchestrator
import com.example.agent.orchestrator.CouncilGraphEngine
import com.example.agent.orchestrator.CouncilLlmCaller
import com.example.agent.orchestrator.CouncilLlmResult
import com.example.agent.orchestrator.HandoffContextBuilder
import com.example.agent.orchestrator.OrchestratorEvent
import com.example.agent.orchestrator.OrchestratorTaskRequest
import com.example.agent.orchestrator.TaskStateMachine
import com.example.core.security.GatewayCredentialVault
import com.example.core.security.SearchCredentialVault
import com.example.core.security.TokenCipher
import com.example.data.council.CouncilRepository
import com.example.data.database.AgtDatabase
import com.example.data.gateway.GatewayRepository
import com.example.data.intelligence.DevelopmentIntelligenceRepository
import com.example.data.preferences.AppPreferencesRepository
import com.example.data.project.ProjectRepository
import com.example.data.project.WorkspaceCheckpointManager
import com.example.data.runtime.LocalRuntimeExecutor
import com.example.data.runtime.RuntimeInstallerRepository
import com.example.data.search.SearchGatewayRepository
import com.example.domain.contracts.ProjectRef
import com.example.domain.model.CouncilAgentOpinion
import com.example.domain.model.CouncilAgentStatus
import com.example.domain.model.CouncilDecomposition
import com.example.domain.model.CouncilExecutionMode
import com.example.domain.model.CouncilReportSection
import com.example.domain.model.CouncilRole
import com.example.domain.model.CouncilRound
import com.example.domain.model.CouncilSectionId
import com.example.domain.model.CouncilSession
import com.example.domain.model.CouncilUserDecision
import com.example.domain.model.CouncilVerdict
import com.example.domain.model.FlightRecorderEventType
import com.example.domain.model.GatewayProtocolMode
import com.example.domain.model.KnowledgeCategory
import com.example.domain.model.ResearchDossier
import com.example.domain.model.TaskState
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
import java.util.concurrent.atomic.AtomicInteger
import java.util.zip.GZIPOutputStream

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class CouncilPhase9Test {

    private lateinit var context: Context
    private lateinit var db: AgtDatabase
    private lateinit var prefs: AppPreferencesRepository
    private lateinit var gatewayVault: GatewayCredentialVault
    private lateinit var searchVault: SearchCredentialVault
    private lateinit var gatewayRepo: GatewayRepository
    private lateinit var searchRepo: SearchGatewayRepository
    private lateinit var projectRepo: ProjectRepository
    private lateinit var checkpointManager: WorkspaceCheckpointManager
    private lateinit var intelligenceRepo: DevelopmentIntelligenceRepository
    private lateinit var councilRepo: CouncilRepository
    private lateinit var handoffBuilder: HandoffContextBuilder
    private lateinit var runtimeInstaller: RuntimeInstallerRepository
    private lateinit var openCodeInstaller: OpenCodeInstallerRepository
    private lateinit var executor: LocalRuntimeExecutor
    private lateinit var openCodeRuntime: OpenCodeAgentRuntime
    private lateinit var server: HttpServer
    private lateinit var baseUrl: String

    private val testSecret = "jh_live_phase9_secret_token_884"

    private val testCipher = object : TokenCipher {
        override fun encrypt(plaintext: String): ByteArray =
            plaintext.toByteArray(StandardCharsets.UTF_8).map { (it.toInt() xor 0x5A).toByte() }.toByteArray()

        override fun decrypt(payload: ByteArray): String =
            String(payload.map { (it.toInt() xor 0x5A).toByte() }.toByteArray(), StandardCharsets.UTF_8)
    }

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        File(context.filesDir, "runtime").deleteRecursively()
        File(context.filesDir, "workspaces").deleteRecursively()
        File(context.filesDir, "checkpoints").deleteRecursively()
        context.deleteDatabase("test_phase9.db")
        context.deleteDatabase("test_phase9_v6_v7.db")
        context.getSharedPreferences("agt_gateway_vault", Context.MODE_PRIVATE).edit().clear().commit()
        context.getSharedPreferences("agt_search_gateway_tokens_v1", Context.MODE_PRIVATE).edit().clear().commit()

        db = Room.databaseBuilder(context, AgtDatabase::class.java, "test_phase9.db")
            .addMigrations(*AgtDatabase.ALL_MIGRATIONS)
            .addCallback(AgtDatabase.FTS_CALLBACK)
            .allowMainThreadQueries()
            .build()
        prefs = AppPreferencesRepository(context)
        gatewayVault = GatewayCredentialVault(context, testCipher)
        searchVault = SearchCredentialVault(context, testCipher)
        gatewayRepo = GatewayRepository(prefs, gatewayVault, db.cachedModelDao())
        searchRepo = SearchGatewayRepository(
            preferencesRepository = prefs,
            searchCredentialVault = searchVault,
            publicGitHubApiBaseUrl = "http://127.0.0.1:1",
        )
        projectRepo = ProjectRepository(context, db.projectDao(), db.taskDao(), db.checkpointDao())
        checkpointManager = WorkspaceCheckpointManager(context, db.projectDao(), db.checkpointDao(), maxCheckpointsPerProject = 5)
        intelligenceRepo = DevelopmentIntelligenceRepository(
            chatMessageDao = db.chatMessageDao(),
            taskStateSnapshotDao = db.taskStateSnapshotDao(),
            projectMemoryDao = db.projectMemoryDao(),
            flightRecorderDao = db.flightRecorderDao(),
            knownSecretsProvider = { listOf(testSecret) },
            projectKnowledgeDao = db.projectKnowledgeDao(),
            database = db,
        )
        councilRepo = CouncilRepository(
            councilDao = db.councilDao(),
            intelligenceRepository = intelligenceRepo,
            knownSecretsProvider = { listOf(testSecret) },
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
            credentialVault = gatewayVault,
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
        context.deleteDatabase("test_phase9.db")
        context.deleteDatabase("test_phase9_v6_v7.db")
        context.getSharedPreferences("agt_gateway_vault", Context.MODE_PRIVATE).edit().clear().commit()
        context.getSharedPreferences("agt_search_gateway_tokens_v1", Context.MODE_PRIVATE).edit().clear().commit()
        File(context.filesDir, "runtime").deleteRecursively()
        File(context.filesDir, "workspaces").deleteRecursively()
        File(context.filesDir, "checkpoints").deleteRecursively()
    }

    @Test
    fun `migration 6 to 7 preserves v6 data and enables council_sessions and council_rounds`(): Unit = runBlocking {
        val dbName = "test_phase9_v6_v7.db"
        context.deleteDatabase(dbName)

        val v6Helper = FrameworkSQLiteOpenHelperFactory().create(
            SupportSQLiteOpenHelper.Configuration.builder(context)
                .name(dbName)
                .callback(object : SupportSQLiteOpenHelper.Callback(6) {
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
                        AgtDatabase.MIGRATION_5_6.migrate(db)
                        db.execSQL(
                            "INSERT INTO `projects` VALUES ('proj-v6', 'LegacyV6', '/tmp/v6', '/tmp/m6', NULL, NULL, 'Kotlin', 100, 200)"
                        )
                    }

                    override fun onUpgrade(db: SupportSQLiteDatabase, oldVersion: Int, newVersion: Int) = Unit
                })
                .build()
        )
        v6Helper.writableDatabase.close()
        v6Helper.close()

        val migratedDb = Room.databaseBuilder(context, AgtDatabase::class.java, dbName)
            .addMigrations(*AgtDatabase.ALL_MIGRATIONS)
            .addCallback(AgtDatabase.FTS_CALLBACK)
            .allowMainThreadQueries()
            .build()

        val surviving = migratedDb.projectDao().getProjectById("proj-v6")
        assertNotNull(surviving)
        assertEquals("LegacyV6", surviving!!.name)

        val repo = CouncilRepository(migratedDb.councilDao())
        val saved = repo.saveSession(
            CouncilSession(
                sessionId = "cs-migrated",
                projectId = "proj-v6",
                taskId = "task-migrated",
                objective = "Migrate cleanly",
                rounds = listOf(
                    sampleRound(roundNumber = 1, verdict = CouncilVerdict.MODIFY, planText = "Step 1: Schema")
                ),
            )
        )
        assertEquals(1, saved.rounds.size)
        assertEquals("Step 1: Schema", saved.latestRound?.sections?.first { it.sectionId == CouncilSectionId.EXECUTION_PLAN }?.effectiveContent)

        migratedDb.close()
        context.deleteDatabase(dbName)
    }

    @Test
    fun `task state machine enforces council, re-council, and council-only transitions`() {
        val sm = TaskStateMachine(TaskState.CREATED)
        assertTrue(sm.transitionTo(TaskState.CONTEXT_LOADING).isSuccess)
        assertTrue(sm.transitionTo(TaskState.RESEARCHING).isSuccess)
        assertTrue(sm.transitionTo(TaskState.COUNCIL_DELIBERATING).isSuccess)
        assertTrue(sm.transitionTo(TaskState.SYNTHESIZING).isSuccess)
        assertTrue(sm.transitionTo(TaskState.AWAITING_COUNCIL_APPROVAL).isSuccess)
        // Re-Council loops back into deliberation or research
        assertTrue(sm.transitionTo(TaskState.COUNCIL_DELIBERATING).isSuccess)
        assertTrue(sm.transitionTo(TaskState.SYNTHESIZING).isSuccess)
        assertTrue(sm.transitionTo(TaskState.AWAITING_COUNCIL_APPROVAL).isSuccess)
        // Proceed to coding
        assertTrue(sm.transitionTo(TaskState.PLANNING).isSuccess)
        assertTrue(sm.transitionTo(TaskState.EXECUTING).isSuccess)
        assertTrue(sm.transitionTo(TaskState.COMPLETED).isSuccess)

        // Council-only terminal completion directly from approval state
        val councilOnly = TaskStateMachine(TaskState.AWAITING_COUNCIL_APPROVAL)
        assertTrue(councilOnly.transitionTo(TaskState.COMPLETED).isSuccess)

        // Illegal jump rejected
        val invalid = TaskStateMachine(TaskState.RESEARCHING)
        assertTrue(invalid.transitionTo(TaskState.EXECUTING).isFailure)
    }

    @Test
    fun `council repository supports inline editing, secret redaction, reverts, and round diffs`(): Unit = runBlocking {
        val proj = projectRepo.createProject("council-edit-proj").getOrThrow()
        val round1 = sampleRound(roundNumber = 1, verdict = CouncilVerdict.MODIFY, planText = "Use OAuth with Ktor")
        val session = councilRepo.saveSession(
            CouncilSession(
                sessionId = "cs-edit-1",
                projectId = proj.id,
                taskId = "task-edit-1",
                objective = "Build sync service",
                rounds = listOf(round1),
            )
        )
        assertEquals(1, session.rounds.size)

        // Inline edit with embedded secret
        val edited = councilRepo.editSection(
            sessionId = "cs-edit-1",
            roundNumber = 1,
            sectionId = CouncilSectionId.EXECUTION_PLAN,
            newContent = "Use manual DI instead of Hilt; never leak $testSecret",
        ).getOrThrow()

        val editedPlan = edited.roundByNumber(1)!!.sections.first { it.sectionId == CouncilSectionId.EXECUTION_PLAN }
        assertTrue(editedPlan.isModifiedByUser)
        assertFalse(editedPlan.effectiveContent.contains(testSecret))
        assertTrue(editedPlan.effectiveContent.contains("manual DI"))

        val events = intelligenceRepo.getFlightEventsForTask("task-edit-1")
        assertTrue(events.any { it.eventType == FlightRecorderEventType.COUNCIL_USER_EDIT })

        // Round 2 arrives with GO verdict
        val round2 = sampleRound(
            roundNumber = 2,
            verdict = CouncilVerdict.GO,
            planText = "Verified manual DI + rate limiter plan",
            amendment = "Switched to manual DI",
        )
        councilRepo.saveRound("cs-edit-1", round2)
        val diff = councilRepo.computeRoundDiff("cs-edit-1", fromRound = 1, toRound = 2)
        assertNotNull(diff)
        assertTrue(diff!!.verdictChanged)
        assertEquals(CouncilVerdict.MODIFY, diff.fromVerdict)
        assertEquals(CouncilVerdict.GO, diff.toVerdict)
        assertTrue(diff.userModifiedSectionsInBase.contains(CouncilSectionId.EXECUTION_PLAN))
        assertTrue(diff.changedSections.any { it.sectionId == CouncilSectionId.EXECUTION_PLAN && it.changed })

        // Revert Round 1 edit restores originalContent
        val reverted = councilRepo.revertSection("cs-edit-1", 1, CouncilSectionId.EXECUTION_PLAN).getOrThrow()
        val revertedSec = reverted.roundByNumber(1)!!.sections.first { it.sectionId == CouncilSectionId.EXECUTION_PLAN }
        assertFalse(revertedSec.isModifiedByUser)
        assertEquals("Use OAuth with Ktor", revertedSec.effectiveContent)
    }

    @Test
    fun `orchestrator runs council, waits for inline edit and re-council without re-searching, then hands off to coder`(): Unit = runBlocking {
        installBaseRuntime()
        val proj = projectRepo.createProject("council-e2e-proj").getOrThrow()
        val root = File(proj.workspacePath)
        File(root, "result.txt").writeText("UNSET\n")
        File(root, "test.sh").writeText(
            """
            #!/bin/sh
            if grep -q "MANUAL_DI_READY" result.txt; then
              echo "PASS"
              exit 0
            else
              echo "FAIL" >&2
              exit 1
            fi
            """.trimIndent() + "\n"
        )

        val capturedCoderPrompt = File(root, "coder_prompt.log")
        installOpenCodeCliScript(
            """
            #!/bin/sh
            PROMPT="${'$'}(cat "${'$'}AGT_PROMPT_FILE")"
            printf "%s\n" "${'$'}PROMPT" > "${capturedCoderPrompt.absolutePath}"
            printf "MANUAL_DI_READY\n" > result.txt
            echo '{"type":"tool_use","part":{"callID":"council-code-1","tool":"edit","state":{"status":"completed","input":{"filePath":"result.txt"},"output":"MANUAL_DI_READY"}}}'
            echo '{"type":"text","part":{"text":"Implemented approved Council Round 2 blueprint."}}'
            exit 0
            """.trimIndent()
        )

        val webSearchCalls = AtomicInteger(0)
        server.createContext("/health") { ex ->
            val b = """{"status":"ok"}""".toByteArray()
            ex.sendResponseHeaders(200, b.size.toLong())
            ex.responseBody.use { it.write(b) }
        }
        server.createContext("/v1/models") { ex ->
            val b = """{"data":[{"id":"gemini-2.5-pro","provider":"google","capabilities":{"streaming":true,"tool_calling":true,"vision":true,"context_window":1000000}}]}""".toByteArray()
            ex.sendResponseHeaders(200, b.size.toLong())
            ex.responseBody.use { it.write(b) }
        }
        server.createContext("/v1/search/web") { ex ->
            webSearchCalls.incrementAndGet()
            val b = """{"query":"offline android di","provider_used":"tavily","results":[{"title":"Manual DI Kotlin","url":"https://example.com/di","snippet":"Fast startup"}]}""".toByteArray()
            ex.sendResponseHeaders(200, b.size.toLong())
            ex.responseBody.use { it.write(b) }
        }

        gatewayRepo.saveConfiguration(baseUrl, GatewayProtocolMode.CHAT_COMPLETIONS, "jh_live_c9_tok").getOrThrow()
        gatewayRepo.testHealthAndDiscoverModels()
        searchRepo.saveWebSearchConfig("$baseUrl/v1/search/web", "jh_web_c9_tok").getOrThrow()
        prefs.setAgentPolicy(20, 30, autoDebug = false, checkpoints = true, requirePlanApproval = false)

        val sawUserAmendmentInRound2 = CompletableDeferred<Boolean>()
        val fakeLlm = CouncilLlmCaller { _, _, systemPrompt, userPrompt ->
            val isReCouncilPrompt = userPrompt.contains("User Amendment Note:") || userPrompt.contains("Use manual DI")
            if (isReCouncilPrompt && !sawUserAmendmentInRound2.isCompleted) {
                sawUserAmendmentInRound2.complete(true)
            }
            val text = when {
                systemPrompt.contains("Task Decomposer") -> """{"refined_objective":"Build offline sync","sub_tasks":["Create DI module"],"web_queries":["offline android di"],"github_queries":[],"preferred_language":"Kotlin","rationale":"Focused"}"""
                systemPrompt.contains("Lead Judge") -> {
                    if (isReCouncilPrompt) {
                        """{"verdict":"GO","sections":{"summary_verdict":"Approved after manual DI switch","research_findings":"Manual DI validated via Tavily","execution_plan":"1. Apply MANUAL_DI_READY to result.txt","cost_resources":"Zero extra runtime cost","risks_critique":"Critic objections resolved","alternatives":"Hilt remains optional"}}"""
                    } else {
                        """{"verdict":"MODIFY","sections":{"summary_verdict":"Recommend switching DI","research_findings":"Tavily found lightweight pattern","execution_plan":"1. Add heavy Hilt setup","cost_resources":"Medium effort","risks_critique":"Heavy annotation overhead","alternatives":"Manual DI"}}"""
                    }
                }
                else -> """{"headline":"Specialist review","summary":"Evaluated architecture.","bullet_points":["Verified"],"verdict_tag":"${if (isReCouncilPrompt) "GO" else "CAUTION"}"}"""
            }
            Result.success(CouncilLlmResult(text = text, promptTokens = 30, completionTokens = 20))
        }

        val engine = CouncilGraphEngine(fakeLlm, searchRepo, intelligenceRepo)
        val orchestrator = AgentOrchestrator(
            projectRepository = projectRepo,
            checkpointManager = checkpointManager,
            preferencesRepository = prefs,
            runtimeExecutor = AgentOrchestrator.LocalRuntimeExecutorFacade(executor),
            agentRuntime = openCodeRuntime,
            intelligenceRepository = intelligenceRepo,
            handoffContextBuilder = handoffBuilder,
            councilGraphEngine = engine,
            councilRepository = councilRepo,
        )

        val taskId = "council-e2e-task"
        val round1Ready = CompletableDeferred<CouncilSession>()
        val round2Ready = CompletableDeferred<Pair<CouncilSession, Boolean>>()
        val finished = CompletableDeferred<Unit>()
        val events = mutableListOf<OrchestratorEvent>()

        val job = launch {
            orchestrator.executeTask(
                OrchestratorTaskRequest(
                    taskId = taskId,
                    project = ProjectRef(proj.id, proj.name, proj.workspacePath),
                    prompt = "Implement DI module and update result.txt",
                    modelId = "gemini-2.5-pro",
                    protocolMode = GatewayProtocolMode.CHAT_COMPLETIONS,
                    backendId = "opencode",
                    executionMode = CouncilExecutionMode.COUNCIL_AND_CODE,
                )
            ).collect { ev ->
                events += ev
                if (ev is OrchestratorEvent.CouncilSessionUpdated && ev.requiresDecision) {
                    if (ev.session.currentRoundNumber == 1 && !round1Ready.isCompleted) {
                        round1Ready.complete(ev.session)
                    } else if (ev.session.currentRoundNumber == 2 && !round2Ready.isCompleted) {
                        round2Ready.complete(ev.session to (ev.diffFromPrevious != null))
                    }
                }
                if (ev is OrchestratorEvent.SummaryReady) {
                    finished.complete(Unit)
                }
            }
        }

        // Wait for Round 1 -> Edit Execution Plan -> Trigger Fast Re-Council (refreshSearch = false)
        val s1 = withTimeout(15_000) { round1Ready.await() }
        assertEquals(CouncilVerdict.MODIFY, s1.latestRound?.verdict)
        val webCallsAfterR1 = webSearchCalls.get()
        assertTrue(webCallsAfterR1 >= 1)

        orchestrator.editCouncilSection(
            sessionId = s1.sessionId,
            roundNumber = 1,
            sectionId = CouncilSectionId.EXECUTION_PLAN,
            newContent = "1. Use manual DI instead of Hilt and write MANUAL_DI_READY",
        ).getOrThrow()

        assertTrue(
            orchestrator.submitCouncilDecision(
                taskId,
                CouncilUserDecision.ReCouncil(
                    userAmendmentNote = "Avoid kapt/Hilt overhead on mobile",
                    refreshSearch = false,
                ),
            )
        )

        val (s2, hasDiff) = withTimeout(15_000) { round2Ready.await() }
        assertTrue(sawUserAmendmentInRound2.await())
        assertTrue("Expected Round 2 diff summary", hasDiff)
        assertEquals(CouncilVerdict.GO, s2.latestRound?.verdict)
        // Re-Council with refreshSearch=false reused Round 1 web dossier without extra search calls!
        assertEquals(webCallsAfterR1, webSearchCalls.get())

        // Approve Round 2 for Coding
        assertTrue(orchestrator.submitCouncilDecision(taskId, CouncilUserDecision.ApproveForCoding))

        withTimeout(15_000) {
            finished.await()
            job.join()
        }

        val summary = events.filterIsInstance<OrchestratorEvent.SummaryReady>().single().summary
        assertEquals(TaskState.COMPLETED, summary.finalState)

        val handoff = events.filterIsInstance<OrchestratorEvent.HandoffReady>().singleOrNull()?.packet
        assertNotNull("Expected HandoffReady carrying approved Council blueprint", handoff)
        assertTrue(handoff!!.approvedCouncilPlan.orEmpty().contains("MANUAL_DI_READY"))

        val coderPromptText = capturedCoderPrompt.readText()
        assertTrue(coderPromptText.contains("Approved Multi-Agent Council Blueprint"))
        assertTrue(coderPromptText.contains("MANUAL_DI_READY"))

        val flightEvents = intelligenceRepo.getFlightEventsForTask(taskId)
        assertTrue(flightEvents.any { it.eventType == FlightRecorderEventType.COUNCIL_RESEARCH })
        assertTrue(flightEvents.any { it.eventType == FlightRecorderEventType.COUNCIL_OPINION })
        assertTrue(flightEvents.any { it.eventType == FlightRecorderEventType.COUNCIL_SYNTHESIS })
        assertTrue(flightEvents.any { it.eventType == FlightRecorderEventType.COUNCIL_USER_EDIT })

        val persistedSession = councilRepo.getSession(s1.sessionId)
        assertNotNull(persistedSession)
        assertTrue(persistedSession!!.approvedForCoding)
        assertEquals(2, persistedSession.rounds.size)
    }

    @Test
    fun `council-only mode completes without modifying workspace files and indexes knowledge`(): Unit = runBlocking {
        val proj = projectRepo.createProject("council-only-proj").getOrThrow()
        File(proj.workspacePath, "existing.txt").writeText("UNTOUCHED\n")

        val fakeLlm = CouncilLlmCaller { _, _, sys, _ ->
            val txt = when {
                sys.contains("Task Decomposer") -> """{"refined_objective":"Evaluate SQLite vs Realm","sub_tasks":["Compare"],"web_queries":[],"github_queries":[],"preferred_language":"Kotlin","rationale":"Arch review"}"""
                sys.contains("Lead Judge") -> """{"verdict":"GO","summary_verdict":"Stick with Room SQLite","research_findings":"Room already active","execution_plan":"Keep Room MIGRATION pattern","cost_resources":"Zero migration rewrite cost","risks_critique":"Avoid mixing DBs","alternatives":"SQLDelight"}"""
                else -> """{"headline":"Keep Room","summary":"Matches stack.","bullet_points":["Native FTS5"],"verdict_tag":"GO"}"""
            }
            Result.success(CouncilLlmResult(txt, 10, 10))
        }
        val engine = CouncilGraphEngine(fakeLlm, searchRepo, intelligenceRepo)
        val orchestrator = AgentOrchestrator(
            projectRepository = projectRepo,
            checkpointManager = checkpointManager,
            preferencesRepository = prefs,
            runtimeExecutor = AgentOrchestrator.LocalRuntimeExecutorFacade(executor),
            agentRuntime = openCodeRuntime,
            intelligenceRepository = intelligenceRepo,
            handoffContextBuilder = handoffBuilder,
            councilGraphEngine = engine,
            councilRepository = councilRepo,
        )

        val taskId = "council-only-task"
        val waitingApproval = CompletableDeferred<Unit>()
        val done = CompletableDeferred<Unit>()
        val events = mutableListOf<OrchestratorEvent>()

        val job = launch {
            orchestrator.executeTask(
                OrchestratorTaskRequest(
                    taskId = taskId,
                    project = ProjectRef(proj.id, proj.name, proj.workspacePath),
                    prompt = "Evaluate Room vs Realm architecture",
                    modelId = "gemini-2.5-pro",
                    protocolMode = GatewayProtocolMode.CHAT_COMPLETIONS,
                    executionMode = CouncilExecutionMode.COUNCIL_ONLY,
                )
            ).collect { ev ->
                events += ev
                if (ev is OrchestratorEvent.CouncilSessionUpdated && ev.requiresDecision && !waitingApproval.isCompleted) {
                    waitingApproval.complete(Unit)
                }
                if (ev is OrchestratorEvent.SummaryReady) {
                    done.complete(Unit)
                }
            }
        }

        withTimeout(10_000) { waitingApproval.await() }
        assertTrue(orchestrator.submitCouncilDecision(taskId, CouncilUserDecision.SaveCouncilOnly))
        withTimeout(10_000) {
            done.await()
            job.join()
        }

        val summary = events.filterIsInstance<OrchestratorEvent.SummaryReady>().single().summary
        assertEquals(TaskState.COMPLETED, summary.finalState)
        assertTrue(summary.filesChanged.isEmpty())
        assertEquals("UNTOUCHED\n", File(proj.workspacePath, "existing.txt").readText())

        val knowledge = intelligenceRepo.observeActiveKnowledge(proj.id).first()
        assertTrue(knowledge.any { it.category == KnowledgeCategory.ARCHITECTURAL_DECISION && it.title.contains("Council R1") })
    }

    private fun sampleRound(
        roundNumber: Int,
        verdict: CouncilVerdict,
        planText: String,
        amendment: String? = null,
    ): CouncilRound = CouncilRound(
        roundNumber = roundNumber,
        userAmendmentNote = amendment,
        decomposition = CouncilDecomposition(refinedObjective = "Obj"),
        dossier = ResearchDossier(webSummary = "Web summary"),
        opinions = listOf(
            CouncilAgentOpinion(
                role = CouncilRole.PLANNER,
                modelId = "gemini-2.5-pro",
                status = CouncilAgentStatus.COMPLETED,
                headline = "Plan headline",
                summary = "Detailed plan",
            )
        ),
        verdict = verdict,
        sections = CouncilSectionId.entries.map { sec ->
            CouncilReportSection(
                sectionId = sec,
                originalContent = if (sec == CouncilSectionId.EXECUTION_PLAN) planText else "Content for ${sec.title}",
            )
        },
        totalPromptTokens = 50,
        totalCompletionTokens = 30,
        durationMs = 120L,
    )

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
                  "runtime_id": "agt-ubuntu-c9",
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
