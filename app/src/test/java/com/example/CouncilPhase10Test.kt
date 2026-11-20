package com.example

import android.content.Context
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performScrollToIndex
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.performTextReplacement
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.example.agent.opencode.OpenCodeAgentRuntime
import com.example.agent.opencode.OpenCodeInstallerRepository
import com.example.agent.orchestrator.AgentOrchestrator
import com.example.agent.orchestrator.CouncilGraphEngine
import com.example.agent.orchestrator.CouncilGraphRequest
import com.example.agent.orchestrator.CouncilLlmCaller
import com.example.agent.orchestrator.CouncilLlmResult
import com.example.agent.orchestrator.HandoffContextBuilder
import com.example.core.security.GatewayCredentialVault
import com.example.core.security.SearchCredentialVault
import com.example.core.security.TokenCipher
import com.example.data.council.CouncilRepository
import com.example.data.database.AgtDatabase
import com.example.data.gateway.GatewayRepository
import com.example.data.github.GitHubRepository
import com.example.data.github.GitRepositoryService
import com.example.data.intelligence.DevelopmentIntelligenceRepository
import com.example.data.preferences.AppPreferencesRepository
import com.example.data.project.ProjectRepository
import com.example.data.project.WorkspaceCheckpointManager
import com.example.data.runtime.LocalRuntimeExecutor
import com.example.data.runtime.RuntimeInstallerRepository
import com.example.data.search.SearchGatewayRepository
import com.example.domain.contracts.ProjectRef
import com.example.domain.model.CouncilExecutionMode
import com.example.domain.model.CouncilProgressEvent
import com.example.domain.model.CouncilRole
import com.example.domain.model.CouncilRound
import com.example.domain.model.CouncilSectionId
import com.example.domain.model.CouncilVerdict
import com.example.domain.model.GatewayHealthStatus
import com.example.domain.model.GatewayProtocolMode
import com.example.domain.model.ProjectInspection
import com.example.domain.model.SettingsCategory
import com.example.domain.model.TaskState
import com.example.feature.settings.SettingsDetailScreen
import com.example.feature.settings.SettingsViewModel
import com.example.feature.workspace.WorkspaceScreen
import com.example.feature.workspace.WorkspaceViewModel
import com.example.ui.theme.AgtTheme
import com.sun.net.httpserver.HttpServer
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
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
import java.io.File
import java.net.InetSocketAddress
import java.nio.charset.StandardCharsets

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class CouncilPhase10Test {

    @get:Rule
    val composeRule = createComposeRule()

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
    private lateinit var gitHubRepo: GitHubRepository
    private lateinit var gitService: GitRepositoryService
    private lateinit var server: HttpServer
    private lateinit var baseUrl: String

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
        context.deleteDatabase("test_phase10.db")
        context.getSharedPreferences("agt_gateway_vault", Context.MODE_PRIVATE).edit().clear().commit()
        context.getSharedPreferences("agt_search_gateway_tokens_v1", Context.MODE_PRIVATE).edit().clear().commit()

        db = Room.databaseBuilder(context, AgtDatabase::class.java, "test_phase10.db")
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
            knownSecretsProvider = { emptyList() },
            projectKnowledgeDao = db.projectKnowledgeDao(),
            database = db,
        )
        councilRepo = CouncilRepository(
            councilDao = db.councilDao(),
            intelligenceRepository = intelligenceRepo,
            knownSecretsProvider = { emptyList() },
        )
        handoffBuilder = HandoffContextBuilder(
            intelligenceRepository = intelligenceRepo,
            checkpointManager = checkpointManager,
            repoMapEngine = intelligenceRepo.repoMapEngine,
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
        val ghVault = com.example.core.security.GitHubCredentialVault(context, testCipher)
        gitHubRepo = GitHubRepository(
            preferencesRepository = prefs,
            credentialVault = ghVault,
            apiClient = com.example.data.github.GitHubApiClient(),
        )
        gitService = GitRepositoryService(
            runtimeExecutor = executor,
            runtimeInstallerRepository = runtimeInstaller,
            projectRepository = projectRepo,
            credentialVault = ghVault,
            preferencesRepository = prefs,
        )

        server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        server.createContext("/health") { ex ->
            val bytes = """{"status":"ok"}""".toByteArray(StandardCharsets.UTF_8)
            ex.sendResponseHeaders(200, bytes.size.toLong())
            ex.responseBody.use { it.write(bytes) }
        }
        server.createContext("/v1/models") { ex ->
            val json = """
                {
                  "data": [
                    {
                      "id": "fast-haiku",
                      "provider": "anthropic",
                      "capabilities": {"streaming": true, "tool_calling": false, "context_window": 128000}
                    },
                    {
                      "id": "flagship-sonnet",
                      "provider": "anthropic",
                      "capabilities": {"streaming": true, "tool_calling": true, "context_window": 200000}
                    }
                  ]
                }
            """.trimIndent()
            val bytes = json.toByteArray(StandardCharsets.UTF_8)
            ex.sendResponseHeaders(200, bytes.size.toLong())
            ex.responseBody.use { it.write(bytes) }
        }
        server.start()
        baseUrl = "http://127.0.0.1:${server.address.port}"
    }

    @After
    fun tearDown() {
        runBlocking {
            executor.terminateAll()
            gatewayRepo.clearToken()
            prefs.setCouncilDefaultMode(CouncilExecutionMode.DIRECT_CODE)
            prefs.setCouncilTokenBudget(24_000)
            CouncilRole.entries.forEach { prefs.setCouncilRoleModel(it, null) }
            CouncilRole.DEFAULT_SPECIALISTS.forEach { prefs.setCouncilSpecialistEnabled(it, true) }
        }
        server.stop(0)
        db.close()
        context.deleteDatabase("test_phase10.db")
        context.getSharedPreferences("agt_gateway_vault", Context.MODE_PRIVATE).edit().clear().commit()
        context.getSharedPreferences("agt_search_gateway_tokens_v1", Context.MODE_PRIVATE).edit().clear().commit()
        File(context.filesDir, "runtime").deleteRecursively()
        File(context.filesDir, "workspaces").deleteRecursively()
        File(context.filesDir, "checkpoints").deleteRecursively()
    }

    @Test
    fun `preferences repository persists council mode, role model overrides, token budget clamping, and minimum one specialist guard`(): Unit = runBlocking {
        prefs.setCouncilDefaultMode(CouncilExecutionMode.COUNCIL_ONLY)
        prefs.setCouncilRoleModel(CouncilRole.DECOMPOSER, "fast-haiku")
        prefs.setCouncilRoleModel(CouncilRole.SYNTHESIZER, "flagship-sonnet")
        prefs.setCouncilTokenBudget(100) // clamps up to 4000

        var state = prefs.preferences.first()
        assertEquals(CouncilExecutionMode.COUNCIL_ONLY, state.councilDefaultMode)
        assertEquals("fast-haiku", state.councilRoleModelOverrides[CouncilRole.DECOMPOSER])
        assertEquals("flagship-sonnet", state.councilRoleModelOverrides[CouncilRole.SYNTHESIZER])
        assertEquals(4_000, state.councilTokenBudget)

        prefs.setCouncilTokenBudget(500_000) // clamps down to 128000
        assertEquals(128_000, prefs.preferences.first().councilTokenBudget)

        // Try disabling all 5 specialists -> must retain at least 1
        CouncilRole.DEFAULT_SPECIALISTS.forEach { role ->
            prefs.setCouncilSpecialistEnabled(role, false)
        }
        state = prefs.preferences.first()
        assertTrue(state.councilEnabledSpecialists.isNotEmpty())
        assertEquals(1, state.councilEnabledSpecialists.size)

        // Clear a role model override
        prefs.setCouncilRoleModel(CouncilRole.DECOMPOSER, null)
        assertFalse(prefs.preferences.first().councilRoleModelOverrides.containsKey(CouncilRole.DECOMPOSER))
    }

    @Test
    fun `council budget guardrail detects exceeded token budget and routes custom role models`(): Unit = runBlocking {
        val routedCalls = mutableListOf<String>()
        val caller = CouncilLlmCaller { modelId, _, systemPrompt, _ ->
            routedCalls += modelId
            when {
                "Decomposer" in systemPrompt -> Result.success(
                    CouncilLlmResult(
                        text = """{"refinedObjective":"Build app","subTasks":["Step A"],"webQueries":[],"githubQueries":[]}""",
                        promptTokens = 40,
                        completionTokens = 20,
                    )
                )
                "Synthesizer" in systemPrompt -> Result.success(
                    CouncilLlmResult(
                        text = """
                        {
                          "verdict": "GO",
                          "summary_verdict": "Ready",
                          "research_findings": "Findings",
                          "execution_plan": "Plan",
                          "cost_resources": "Low",
                          "risks_critique": "Minimal",
                          "alternatives": "None"
                        }
                        """.trimIndent(),
                        promptTokens = 80,
                        completionTokens = 50,
                    )
                )
                else -> Result.success(
                    CouncilLlmResult(
                        text = """{"headline":"Checked","summary":"OK","bullets":["One"],"verdictTag":"OK"}""",
                        promptTokens = 30,
                        completionTokens = 15,
                    )
                )
            }
        }

        val engine = CouncilGraphEngine(
            llmCaller = caller,
            searchGatewayRepository = searchRepo,
        )
        val events = engine.runCouncilRound(
            CouncilGraphRequest(
                project = ProjectRef("p1", "Demo", context.filesDir.absolutePath),
                objective = "Analyze feasibility",
                defaultModelId = "default-model",
                protocolMode = GatewayProtocolMode.CHAT_COMPLETIONS,
                roleModelOverrides = mapOf(
                    CouncilRole.DECOMPOSER to "fast-haiku",
                    CouncilRole.SYNTHESIZER to "flagship-sonnet",
                ),
                enabledSpecialists = setOf(CouncilRole.PLANNER, CouncilRole.COST_ANALYST),
                inspection = ProjectInspection(
                    projectId = "p1",
                    projectRootPath = context.filesDir.absolutePath,
                    stack = com.example.domain.model.ProjectStack.GRADLE_JVM,
                    stackLabel = "Kotlin",
                    entryPoints = listOf("Main.kt"),
                    manifestSummaries = emptyList(),
                    directoryMap = listOf("Main.kt"),
                    validationCommands = emptyList(),
                    isGitRepo = false,
                ),
                maxTokenBudget = 100,
            )
        ).toList()

        val completed = events.filterIsInstance<CouncilProgressEvent.RoundCompleted>().single().round
        assertTrue(routedCalls.contains("fast-haiku"))
        assertTrue(routedCalls.contains("flagship-sonnet"))
        assertTrue(routedCalls.contains("default-model"))
        assertTrue(completed.totalTokens > 100)
        assertTrue(completed.exceedsBudget(100))
        assertFalse(completed.exceedsBudget(5_000))
        assertEquals(CouncilVerdict.GO, completed.verdict)
    }

    @Test
    fun `settings screens configure council policy, specialist toggles, and per-role model routing`(): Unit = runBlocking {
        gatewayRepo.saveConfiguration(baseUrl, GatewayProtocolMode.CHAT_COMPLETIONS, "jh_live_test_tok").getOrThrow()
        val health = gatewayRepo.testHealthAndDiscoverModels()
        assertEquals(GatewayHealthStatus.CONNECTED, health.status)

        val settingsVm = SettingsViewModel(
            appContext = context,
            preferencesRepository = prefs,
            projectRepository = projectRepo,
            gatewayRepository = gatewayRepo,
            gitHubRepository = gitHubRepo,
            runtimeInstallerRepository = runtimeInstaller,
            openCodeInstallerRepository = openCodeInstaller,
            searchGatewayRepository = searchRepo,
        )

        // 1. Verify Agent Settings -> Multi-Agent Council Policy card
        composeRule.setContent {
            AgtTheme {
                SettingsDetailScreen(
                    category = SettingsCategory.AGENT,
                    viewModel = settingsVm,
                    onBack = {},
                    onNavigateToGateway = {},
                )
            }
        }
        composeRule.waitForIdle()

        composeRule.onNodeWithTag("settings_council_policy_card").performScrollTo().assertIsDisplayed()
        composeRule.onNodeWithTag("settings_council_mode_council_only").performScrollTo().performClick()
        composeRule.onNodeWithTag("settings_council_budget_input").performScrollTo().performTextReplacement("16000")
        composeRule.onNodeWithTag("settings_council_role_toggle_critic").performScrollTo().performClick()
        composeRule.onNodeWithTag("settings_council_save_policy_button").performScrollTo().performClick()
        composeRule.waitUntil(timeoutMillis = 5_000) {
            runBlocking {
                val p = prefs.preferences.first()
                p.councilDefaultMode == CouncilExecutionMode.COUNCIL_ONLY &&
                    p.councilTokenBudget == 16_000 &&
                    CouncilRole.CRITIC !in p.councilEnabledSpecialists
            }
        }
        composeRule.waitForIdle()

        val updatedPrefs = prefs.preferences.first()
        assertEquals(CouncilExecutionMode.COUNCIL_ONLY, updatedPrefs.councilDefaultMode)
        assertEquals(16_000, updatedPrefs.councilTokenBudget)
        assertFalse(CouncilRole.CRITIC in updatedPrefs.councilEnabledSpecialists)
    }

    @Test
    fun `models settings screen assigns per-role council model overrides`(): Unit = runBlocking {
        gatewayRepo.saveConfiguration(baseUrl, GatewayProtocolMode.CHAT_COMPLETIONS, "jh_live_test_tok").getOrThrow()
        gatewayRepo.testHealthAndDiscoverModels()

        val settingsVm = SettingsViewModel(
            appContext = context,
            preferencesRepository = prefs,
            projectRepository = projectRepo,
            gatewayRepository = gatewayRepo,
            gitHubRepository = gitHubRepo,
            runtimeInstallerRepository = runtimeInstaller,
            openCodeInstallerRepository = openCodeInstaller,
            searchGatewayRepository = searchRepo,
        )

        composeRule.setContent {
            AgtTheme {
                SettingsDetailScreen(
                    category = SettingsCategory.MODELS,
                    viewModel = settingsVm,
                    onBack = {},
                    onNavigateToGateway = {},
                )
            }
        }
        composeRule.waitUntil(timeoutMillis = 5_000) {
            composeRule.onAllNodesWithTag("settings_council_routing_card").fetchSemanticsNodes().isNotEmpty()
        }
        composeRule.waitForIdle()

        composeRule.onNodeWithTag("settings_council_role_model_decomposer").performScrollTo().performClick()
        composeRule.waitForIdle()
        composeRule.onNodeWithTag("settings_council_role_decomposer_fast-haiku").performClick()
        composeRule.waitForIdle()

        assertEquals("fast-haiku", prefs.preferences.first().councilRoleModelOverrides[CouncilRole.DECOMPOSER])
    }

    @Test
    fun `workspace composer switches modes, runs Council Only without CLI, supports inline edit, re-council, and save`(): Unit = runBlocking {
        gatewayRepo.saveConfiguration(baseUrl, GatewayProtocolMode.CHAT_COMPLETIONS, "jh_live_test_tok").getOrThrow()
        gatewayRepo.testHealthAndDiscoverModels()

        val project = projectRepo.createProject("council-ws").getOrThrow()
        projectRepo.writeText(project.workspacePath, "README.md", "# Council Demo").getOrThrow()

        var roundCounter = 0
        val fakeCaller = CouncilLlmCaller { modelId, _, systemPrompt, userPrompt ->
            when {
                "Decomposer" in systemPrompt -> {
                    if ("User Amendment" in userPrompt || "RE-COUNCIL" in userPrompt) roundCounter++ else roundCounter = 1
                    Result.success(
                        CouncilLlmResult(
                            text = """{"refinedObjective":"Analyze architecture (R$roundCounter)","subTasks":["Check storage"],"webQueries":[],"githubQueries":[]}""",
                            promptTokens = 120,
                            completionTokens = 40,
                        )
                    )
                }
                "Synthesizer" in systemPrompt -> Result.success(
                    CouncilLlmResult(
                        text = if (roundCounter <= 1) {
                            """
                            {
                              "verdict": "MODIFY",
                              "summary_verdict": "Round 1 summary for $modelId",
                              "research_findings": "Round 1 web/github synthesis",
                              "execution_plan": "Original Room SQLite plan",
                              "cost_resources": "Zero licensing cost",
                              "risks_critique": "Initial risk check",
                              "alternatives": "DataStore fallback"
                            }
                            """.trimIndent()
                        } else {
                            """
                            {
                              "verdict": "GO",
                              "summary_verdict": "Round 2 approved summary incorporating amendment",
                              "research_findings": "Updated findings",
                              "execution_plan": "Revised plan without kapt",
                              "cost_resources": "Low budget",
                              "risks_critique": "Mitigated risks",
                              "alternatives": "Direct SQLite"
                            }
                            """.trimIndent()
                        },
                        promptTokens = 300,
                        completionTokens = 140,
                    )
                )
                else -> Result.success(
                    CouncilLlmResult(
                        text = """{"headline":"Role check passed","summary":"Detailed role analysis","bullets":["Point 1"],"verdictTag":"OK"}""",
                        promptTokens = 90,
                        completionTokens = 35,
                    )
                )
            }
        }

        val engine = CouncilGraphEngine(
            llmCaller = fakeCaller,
            searchGatewayRepository = searchRepo,
        )
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
        val workspaceVm = WorkspaceViewModel(
            appContext = context,
            projectId = project.id,
            projectRepository = projectRepo,
            checkpointManager = checkpointManager,
            gatewayRepository = gatewayRepo,
            gitHubRepository = gitHubRepo,
            gitRepositoryService = gitService,
            preferencesRepository = prefs,
            runtimeInstallerRepository = runtimeInstaller,
            openCodeInstallerRepository = openCodeInstaller,
            runtimeExecutor = executor,
            agentRuntime = openCodeRuntime,
            agentOrchestrator = orchestrator,
            intelligenceRepository = intelligenceRepo,
        )

        composeRule.setContent {
            AgtTheme {
                WorkspaceScreen(
                    viewModel = workspaceVm,
                    onBack = {},
                    onOpenGatewaySettings = {},
                    onOpenAgentSettings = {},
                )
            }
        }

        composeRule.waitUntil(timeoutMillis = 5_000) {
            composeRule.onAllNodesWithTag("composer_mode_council_only").fetchSemanticsNodes().isNotEmpty()
        }
        composeRule.waitForIdle()

        // Switch composer execution mode to Council Only (OpenCode CLI is NOT installed!)
        composeRule.onNodeWithTag("composer_mode_council_only").performClick()
        composeRule.waitForIdle()
        composeRule.onNodeWithText("Multi-Agent Council Ready", substring = true).performScrollTo().assertIsDisplayed()

        // Submit prompt for Council Only analysis
        composeRule.onNodeWithTag("agent_composer_input").performTextInput("Design offline cache")
        composeRule.onNodeWithTag("agent_send_button").performClick()

        // Wait until Round 1 synthesized sections and HITL decision controls render
        composeRule.waitUntil(timeoutMillis = 10_000) {
            composeRule.onAllNodesWithTag("council_save_only_button").fetchSemanticsNodes().isNotEmpty()
        }
        composeRule.waitForIdle()

        composeRule.onNodeWithTag("council_telemetry_tokens").performScrollTo().assertIsDisplayed()
        composeRule.onNodeWithTag("council_section_execution_plan").performScrollTo().assertIsDisplayed()

        // Inline Edit Execution Plan section before Re-Council
        composeRule.onNodeWithTag("council_section_edit_execution_plan").performScrollTo().performClick()
        composeRule.waitForIdle()
        composeRule.onNodeWithTag("council_section_input_execution_plan").performScrollTo()
            .performTextReplacement("Edited Step 1: Avoid kapt completely")
        composeRule.onNodeWithTag("council_section_save_execution_plan").performScrollTo().performClick()
        composeRule.waitUntil(timeoutMillis = 5_000) {
            composeRule.onAllNodes(androidx.compose.ui.test.hasText("Edited Step 1: Avoid kapt completely")).fetchSemanticsNodes().isNotEmpty()
        }
        composeRule.waitForIdle()

        composeRule.onNodeWithText("Edited Step 1: Avoid kapt completely").performScrollTo().assertIsDisplayed()
        composeRule.onNodeWithTag("council_section_revert_execution_plan").performScrollTo().assertIsDisplayed()

        // Enter Amendment Note and trigger Re-Council (Round 2)
        composeRule.onNodeWithTag("council_amendment_input").performScrollTo().performTextInput("Use KSP only")
        composeRule.onNodeWithTag("council_refresh_search_toggle").performScrollTo().performClick()
        composeRule.onNodeWithTag("council_recouncil_button").performScrollTo().performClick()

        composeRule.waitUntil(timeoutMillis = 10_000) {
            composeRule.onAllNodesWithTag("agent_timeline_list").fetchSemanticsNodes()
            val card = workspaceVm.uiState.value.timeline
                .filterIsInstance<com.example.domain.model.AgentTimelineItem.CouncilSessionCard>()
                .firstOrNull()
            card != null && !card.isRunning && (card.session?.rounds?.size ?: 0) >= 2
        }
        composeRule.onNodeWithTag("agent_timeline_list").performScrollToIndex(1)
        composeRule.waitForIdle()

        composeRule.onNodeWithTag("council_round_tab_2").performScrollTo().assertIsDisplayed()
        composeRule.onNodeWithTag("council_diff_strip").performScrollTo().assertIsDisplayed()
        composeRule.onNodeWithText("Round 2 approved summary incorporating amendment").performScrollTo().assertIsDisplayed()

        // Save Council Plan (Council Only completes cleanly without OpenCode CLI)
        composeRule.onNodeWithTag("council_save_only_button").performScrollTo().performClick()

        composeRule.waitUntil(timeoutMillis = 10_000) {
            composeRule.onAllNodesWithTag("agent_timeline_list").fetchSemanticsNodes()
            workspaceVm.uiState.value.activeTaskState == TaskState.COMPLETED
        }
        composeRule.waitForIdle()

        assertEquals(TaskState.COMPLETED, workspaceVm.uiState.value.activeTaskState)
        assertNotNull(workspaceVm.uiState.value.timeline.firstOrNull {
            it is com.example.domain.model.AgentTimelineItem.CouncilSessionCard
        })
    }
}
