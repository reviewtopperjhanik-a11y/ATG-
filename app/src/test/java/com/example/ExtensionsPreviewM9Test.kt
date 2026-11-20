package com.example

import android.content.Context
import android.os.Build
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.example.agent.opencode.OpenCodeConfigAdapter
import com.example.agent.opencode.OpenCodeInstallerRepository
import com.example.agent.orchestrator.AgentOrchestrator
import com.example.agent.opencode.OpenCodeAgentRuntime
import com.example.agent.tools.ToolBroker
import com.example.core.security.ExtensionCredentialVault
import com.example.core.security.GatewayCredentialVault
import com.example.core.security.TokenCipher
import com.example.data.database.AgtDatabase
import com.example.data.extensions.ExtensionPackageValidator
import com.example.data.extensions.ExtensionRegistryRepository
import com.example.data.extensions.McpHttpClient
import com.example.data.gateway.GatewayRepository
import com.example.data.github.GitHubApiClient
import com.example.data.github.GitHubRepository
import com.example.data.github.GitRepositoryService
import com.example.data.preferences.AppPreferencesRepository
import com.example.data.project.ProjectRepository
import com.example.data.project.WorkspaceCheckpointManager
import com.example.data.runtime.LocalRuntimeExecutor
import com.example.data.runtime.PreviewServerManager
import com.example.data.runtime.RuntimeInstallerRepository
import com.example.domain.contracts.RuntimeAvailability
import com.example.domain.model.AppPreferencesState
import com.example.domain.model.ComposerAttachmentKind
import com.example.domain.model.ExtensionInstallState
import com.example.domain.model.GatewayModelInfo
import com.example.domain.model.GatewayProtocolMode
import com.example.domain.model.McpServerRuntimeConfig
import com.example.domain.model.PermissionClass
import com.example.domain.model.PreviewServerStatus
import com.example.domain.model.ToolBrokerDecision
import com.example.feature.extensions.ExtensionsScreen
import com.example.feature.extensions.ExtensionsViewModel
import com.example.feature.workspace.PreviewView
import com.example.feature.workspace.WorkspaceUiState
import com.example.feature.workspace.WorkspaceViewModel
import com.example.ui.theme.AgtTheme
import com.sun.net.httpserver.HttpServer
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
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
class ExtensionsPreviewM9Test {

    @get:Rule
    val composeRule = createComposeRule()

    private lateinit var context: Context
    private lateinit var prefs: AppPreferencesRepository
    private lateinit var db: AgtDatabase
    private lateinit var vault: ExtensionCredentialVault
    private lateinit var registry: ExtensionRegistryRepository
    private lateinit var runtimeInstaller: RuntimeInstallerRepository
    private lateinit var runtimeExecutor: LocalRuntimeExecutor
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
        context.deleteDatabase("test_m9_ext.db")
        context.getSharedPreferences("agt_extension_vault", Context.MODE_PRIVATE).edit().clear().commit()
        File(context.filesDir, "extensions").deleteRecursively()
        File(context.filesDir, "runtime").deleteRecursively()
        File(context.filesDir, "projects").deleteRecursively()

        prefs = AppPreferencesRepository(context)
        db = Room.databaseBuilder(context, AgtDatabase::class.java, "test_m9_ext.db")
            .allowMainThreadQueries()
            .build()
        vault = ExtensionCredentialVault(context, testCipher)
        registry = ExtensionRegistryRepository(
            context = context,
            extensionDao = db.extensionDao(),
            credentialVault = vault,
            mcpHttpClient = McpHttpClient(),
        )
        runtimeInstaller = RuntimeInstallerRepository(context, prefs)
        runtimeExecutor = LocalRuntimeExecutor(runtimeInstaller)

        server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        server.start()
        baseUrl = "http://127.0.0.1:${server.address.port}"
    }

    @After
    fun tearDown() {
        runBlocking {
            runtimeExecutor.terminateAll()
            prefs.setRuntimeManifestUrl("")
        }
        server.stop(0)
        db.close()
        context.deleteDatabase("test_m9_ext.db")
        context.getSharedPreferences("agt_extension_vault", Context.MODE_PRIVATE).edit().clear().commit()
        File(context.filesDir, "extensions").deleteRecursively()
        File(context.filesDir, "runtime").deleteRecursively()
        File(context.filesDir, "projects").deleteRecursively()
    }

    @Test
    fun `skill install disable update and uninstall persist in Room and exclude disabled skills from prompt injection`() = runBlocking {
        registry.installBuiltInSkill("agt.core.repo-inspect").getOrThrow()

        val afterInstall = registry.getAllExtensions().first { it.id == "agt.core.repo-inspect" }
        assertEquals(ExtensionInstallState.INSTALLED_ENABLED, afterInstall.installState)

        val skillFile = File(context.filesDir, "extensions/agt.core.repo-inspect/SKILL.md")
        assertTrue(skillFile.exists())
        assertEquals(1, registry.getActiveSkillPrompts().size)

        registry.setEnabled("agt.core.repo-inspect", false).getOrThrow()
        val afterDisable = registry.getAllExtensions().first { it.id == "agt.core.repo-inspect" }
        assertEquals(ExtensionInstallState.INSTALLED_DISABLED, afterDisable.installState)
        assertTrue(registry.getActiveSkillPrompts().isEmpty())

        registry.uninstall("agt.core.repo-inspect").getOrThrow()
        val afterUninstall = registry.getAllExtensions().first { it.id == "agt.core.repo-inspect" }
        assertEquals(ExtensionInstallState.NOT_INSTALLED, afterUninstall.installState)
        assertFalse(skillFile.exists())
        assertTrue(registry.getActiveSkillPrompts().isEmpty())
    }

    @Test
    fun `skill package validator verifies SHA256 rejects tampering path traversal scripts and executable plugins`() = runBlocking {
        val validSkill = """
            ---
            id: skill.custom-audit
            name: Custom Audit
            description: Audits Android accessibility semantics.
            license: MIT
            permissions: PROJECT_READ
            ---
            # Custom Audit
            Check content descriptions and minimum touch targets.
        """.trimIndent()
        val digest = ExtensionPackageValidator.sha256Hex(validSkill.toByteArray(StandardCharsets.UTF_8))

        val installed = registry.importSkillPackage(validSkill, expectedSha256 = digest).getOrThrow()
        assertEquals("skill.custom-audit", installed.id)
        assertEquals(digest, installed.sha256)

        val tampered = registry.importSkillPackage(validSkill, expectedSha256 = "0".repeat(64))
        assertTrue(tampered.isFailure)

        val scriptImport = registry.importSkillPackage("#!/bin/sh\nrm -rf /")
        assertTrue(scriptImport.isFailure)

        val pluginManifest = """
            {"id":"com.evil.plugin","name":"Evil Plugin","type":"PLUGIN","skillMarkdown":"# Bad"}
        """.trimIndent()
        val pluginResult = registry.importSkillPackage(pluginManifest)
        assertTrue(pluginResult.isFailure)

        assertTrue(ExtensionPackageValidator.validateExtensionId("../escape").isFailure)
    }

    @Test
    fun `HTTP SSE MCP server registers verifies discovers tools enforces ToolBroker allowlist and revokes on uninstall`() = runBlocking {
        var lastAuthHeader: String? = null
        server.createContext("/mcp") { ex ->
            lastAuthHeader = ex.requestHeaders.getFirst("Authorization")
            val body = ex.requestBody.readBytes().toString(StandardCharsets.UTF_8)
            val rpc = JSONObject(body)
            val method = rpc.optString("method")
            when (method) {
                "initialize" -> {
                    val sse = """
                        event: message
                        data: {"jsonrpc":"2.0","id":1,"result":{"protocolVersion":"2024-11-05","serverInfo":{"name":"DocsMCP","version":"2.1.0"},"capabilities":{"tools":{}}}}

                    """.trimIndent()
                    val bytes = sse.toByteArray(StandardCharsets.UTF_8)
                    ex.responseHeaders.add("Content-Type", "text/event-stream")
                    ex.responseHeaders.add("Mcp-Session-Id", "sess-mcp-42")
                    ex.sendResponseHeaders(200, bytes.size.toLong())
                    ex.responseBody.use { it.write(bytes) }
                }
                "tools/list" -> {
                    val json = """
                        {
                          "jsonrpc": "2.0",
                          "id": 2,
                          "result": {
                            "tools": [
                              {"name": "read_docs", "description": "Search API docs"},
                              {"name": "delete_index", "description": "Delete search index", "annotations": {"destructiveHint": true}}
                            ]
                          }
                        }
                    """.trimIndent()
                    val bytes = json.toByteArray(StandardCharsets.UTF_8)
                    ex.responseHeaders.add("Content-Type", "application/json")
                    ex.sendResponseHeaders(200, bytes.size.toLong())
                    ex.responseBody.use { it.write(bytes) }
                }
                else -> {
                    val bytes = "{}".toByteArray(StandardCharsets.UTF_8)
                    ex.sendResponseHeaders(200, bytes.size.toLong())
                    ex.responseBody.use { it.write(bytes) }
                }
            }
        }

        val registered = registry.registerMcpServer(
            name = "Docs MCP",
            endpointUrl = "$baseUrl/mcp",
            bearerToken = "mcp_secret_token_99",
            verifyConnection = true,
        ).getOrThrow()

        assertEquals("Bearer mcp_secret_token_99", lastAuthHeader)
        assertEquals(2, registered.discoveredTools.size)
        assertTrue(registered.hasStoredToken)
        assertEquals(ExtensionInstallState.INSTALLED_ENABLED, registered.installState)

        // Token must NEVER appear plaintext in Room or opencode.json
        val dbRow = db.extensionDao().getById(registered.id)!!
        assertFalse(dbRow.toString().contains("mcp_secret_token_99"))
        val runtimeConfigs = registry.getEnabledMcpRuntimeConfigs()
        assertEquals(1, runtimeConfigs.size)
        val openCodeJson = OpenCodeConfigAdapter.buildOpenCodeConfigJson(
            bridgeBaseUrl = "http://127.0.0.1:9999",
            ephemeralSessionNonce = "nonce",
            protocolMode = GatewayProtocolMode.CHAT_COMPLETIONS,
            model = GatewayModelInfo("gpt-4o", "openai", true, true),
            preferences = AppPreferencesState(),
            mcpServers = runtimeConfigs,
        )
        val openCodeJsonText = openCodeJson.toString()
        assertFalse(openCodeJsonText.contains("mcp_secret_token_99"))
        assertTrue(openCodeJsonText.contains("{env:${runtimeConfigs[0].envVarTokenName}}"))

        // ToolBroker enforces active policy: enabled read tool -> RequiresApproval(NETWORK_ACCESS)
        val broker = ToolBroker(
            projectRootPath = File(context.filesDir, "dummy_ws").apply { mkdirs() }.absolutePath,
            extensionPolicyProvider = { runBlocking { registry.buildActiveExtensionPolicy() } },
        )
        val alias = ExtensionRegistryRepository.mcpAliasForId(registered.id)
        val readDecision = broker.evaluateToolCall(
            toolCallId = "c1",
            rawToolName = "mcp_${alias}_read_docs",
            rawArgumentsJson = "{\"query\":\"compose\"}",
            preferences = AppPreferencesState(),
        )
        assertTrue(readDecision is ToolBrokerDecision.RequiresApproval)

        // Destructive tool escalates to DESTRUCTIVE permission class
        val deleteDecision = broker.evaluateToolCall(
            toolCallId = "c2",
            rawToolName = "mcp_${alias}_delete_index",
            rawArgumentsJson = "{\"index\":\"prod\"}",
            preferences = AppPreferencesState(),
        )
        assertTrue(deleteDecision is ToolBrokerDecision.RequiresApproval)
        assertEquals(
            PermissionClass.DESTRUCTIVE,
            (deleteDecision as ToolBrokerDecision.RequiresApproval).definition.permissionClass,
        )

        // Disable MCP server -> ToolBroker immediately REJECTS calls
        registry.setEnabled(registered.id, false).getOrThrow()
        val disabledDecision = broker.evaluateToolCall(
            toolCallId = "c3",
            rawToolName = "mcp_${alias}_read_docs",
            rawArgumentsJson = "{}",
            preferences = AppPreferencesState(),
        )
        assertTrue(disabledDecision is ToolBrokerDecision.Rejected)

        // Uninstall revokes both DB registration and Keystore token
        registry.uninstall(registered.id).getOrThrow()
        assertFalse(vault.hasToken(registered.id))
        assertNull(db.extensionDao().getById(registered.id))
    }

    @Test
    fun `PreviewServerManager detects frameworks extracts dynamic ports and enforces loopback URLs`() {
        val viteDir = File(context.filesDir, "ws_vite").apply { mkdirs() }
        File(viteDir, "package.json").writeText(
            """{"scripts":{"dev":"vite"},"devDependencies":{"vite":"^5.0.0"}}"""
        )
        val viteDetected = PreviewServerManager.detectPreviewServer(viteDir)
        assertNotNull(viteDetected)
        assertEquals("Vite", viteDetected!!.frameworkLabel)
        assertEquals("http://127.0.0.1:5173", viteDetected.suggestedUrl)

        val staticDir = File(context.filesDir, "ws_static").apply { mkdirs() }
        File(staticDir, "index.html").writeText("<h1>Hi</h1>")
        val staticDetected = PreviewServerManager.detectPreviewServer(staticDir)
        assertNotNull(staticDetected)
        assertEquals("Static HTML", staticDetected!!.frameworkLabel)
        assertTrue(staticDetected.command.contains("http.server"))

        val staticNoPython = PreviewServerManager.detectPreviewServer(staticDir, hasPython3 = false)
        assertNotNull(staticNoPython)
        assertEquals("Static HTML", staticNoPython!!.frameworkLabel)
        assertTrue(staticNoPython.command.startsWith("node -e "))
        assertTrue(staticNoPython.command.contains("127.0.0.1"))

        assertEquals(
            "http://127.0.0.1:4173",
            PreviewServerManager.extractLocalUrlFromOutput("  ➜  Local:   http://localhost:4173/"),
        )
        assertEquals(
            "http://127.0.0.1:9123",
            PreviewServerManager.extractLocalUrlFromOutput("Serving HTTP on 0.0.0.0 port 9123"),
        )
        assertTrue(PreviewServerManager.validatePreviewUrl("http://127.0.0.1:8080").isSuccess)
        val queryPreview = PreviewServerManager.validatePreviewUrl("http://127.0.0.1:5173/?tab=home#/dashboard")
        assertTrue(queryPreview.isSuccess)
        assertEquals("http://127.0.0.1:5173/?tab=home#/dashboard", queryPreview.getOrNull())
        assertTrue(PreviewServerManager.validatePreviewUrl("https://evil.example.com").isFailure)
    }

    @Test
    fun `WorkspaceViewModel runs preview dev server discovers port transitions RUNNING and stops cleanly`() {
        assumeTrue("Host OS has /bin/sh", File("/bin/sh").exists())
        val bundle = buildSampleTarGz(
            listOf(
                "rootfs/bin/sh" to "#!/bin/sh\nexec /bin/sh \"\$@\"\n".toByteArray(StandardCharsets.UTF_8),
            )
        )
        val digest = sha256Hex(bundle)
        server.createContext("/bundle.tar.gz") { ex ->
            ex.sendResponseHeaders(200, bundle.size.toLong())
            ex.responseBody.use { it.write(bundle) }
        }
        server.createContext("/manifest.json") { ex ->
            val abi = Build.SUPPORTED_ABIS.firstOrNull() ?: "arm64-v8a"
            val abisJson = Build.SUPPORTED_ABIS.ifEmpty { arrayOf(abi) }
                .joinToString(",") { "\"$it\"" }
            val manifest = """
                {
                  "runtime_id": "agt-ubuntu-m9",
                  "version": "1.0.0-m9",
                  "release_channel": "stable",
                  "supported_abis": [$abisJson],
                  "min_android_sdk": 24,
                  "min_required_free_bytes": 1024,
                  "bundle": {
                    "download_url": "$baseUrl/bundle.tar.gz",
                    "compressed_size_bytes": ${bundle.size},
                    "sha256": "$digest",
                    "archive_format": "tar.gz",
                    "extraction_root": "rootfs",
                    "required_paths": ["bin/sh"]
                  }
                }
            """.trimIndent()
            val b = manifest.toByteArray(StandardCharsets.UTF_8)
            ex.sendResponseHeaders(200, b.size.toLong())
            ex.responseBody.use { it.write(b) }
        }

        val (project, vm) = runBlocking {
            prefs.setRuntimeManifestUrl("$baseUrl/manifest.json")
            runtimeInstaller.installBlocking("$baseUrl/manifest.json").getOrThrow()

            val projectRepo = ProjectRepository(context, db.projectDao(), db.taskDao(), db.checkpointDao())
            val proj = projectRepo.createProject("preview-demo").getOrThrow()
            val gatewayRepo = GatewayRepository(prefs, GatewayCredentialVault(context, testCipher), db.cachedModelDao())
            val ghRepo = GitHubRepository(prefs, com.example.core.security.GitHubCredentialVault(context, testCipher), GitHubApiClient())
            val gitService = GitRepositoryService(runtimeExecutor, runtimeInstaller, projectRepo, com.example.core.security.GitHubCredentialVault(context, testCipher), prefs)
            val ocInstaller = OpenCodeInstallerRepository(context, runtimeInstaller, prefs)
            val checkpointMgr = WorkspaceCheckpointManager(context, db.projectDao(), db.checkpointDao())
            val ocRuntime = OpenCodeAgentRuntime(
                context = context,
                runtimeInstallerRepository = runtimeInstaller,
                openCodeInstaller = ocInstaller,
                runtimeExecutor = runtimeExecutor,
                gatewayRepository = gatewayRepo,
                preferencesRepository = prefs,
                credentialVault = GatewayCredentialVault(context, testCipher),
                extensionRegistryRepository = registry,
            )
            val orchestrator = AgentOrchestrator(
                projectRepository = projectRepo,
                checkpointManager = checkpointMgr,
                preferencesRepository = prefs,
                runtimeExecutor = AgentOrchestrator.LocalRuntimeExecutorFacade(runtimeExecutor),
                agentRuntime = ocRuntime,
            )
            registry.installBuiltInSkill("agt.core.repo-inspect").getOrThrow()
            val model = WorkspaceViewModel(
                appContext = context,
                projectId = proj.id,
                projectRepository = projectRepo,
                checkpointManager = checkpointMgr,
                gatewayRepository = gatewayRepo,
                gitHubRepository = ghRepo,
                gitRepositoryService = gitService,
                preferencesRepository = prefs,
                runtimeInstallerRepository = runtimeInstaller,
                openCodeInstallerRepository = ocInstaller,
                runtimeExecutor = runtimeExecutor,
                agentRuntime = ocRuntime,
                agentOrchestrator = orchestrator,
                extensionRegistryRepository = registry,
            )
            proj to model
        }

        composeRule.setContent {
            val state = vm.uiState.collectAsStateWithLifecycle()
            AgtTheme {
                PreviewView(
                    state = state.value,
                    onPreviewUrlChanged = vm::updatePreviewUrl,
                    onPreviewCommandChanged = vm::updatePreviewCommandDraft,
                    onStartServer = vm::startPreviewServer,
                    onStopServer = vm::stopPreviewServer,
                    onReloadOrOpenUrl = vm::openOrReloadPreviewUrl,
                    onToggleLogs = vm::togglePreviewLogs,
                    onWebViewError = vm::reportPreviewWebViewError,
                )
            }
        }

        composeRule.waitUntil(5_000) {
            org.robolectric.shadows.ShadowLooper.idleMainLooper()
            val s = vm.uiState.value
            s.project?.id == project.id && s.enabledSkills.isNotEmpty()
        }
        composeRule.runOnIdle {
            vm.onExtensionAttachmentSelected(ComposerAttachmentKind.SKILLS)
        }
        composeRule.waitUntil(3_000) {
            vm.uiState.value.pendingExtensionPickerKind == ComposerAttachmentKind.SKILLS
        }
        val skillToAttach = vm.uiState.value.enabledSkills.first()
        composeRule.runOnIdle {
            vm.attachExtensionRecord(skillToAttach)
        }
        composeRule.waitUntil(3_000) {
            vm.uiState.value.composerAttachments.size == 1 &&
                vm.uiState.value.pendingExtensionPickerKind == null
        }

        composeRule.runOnIdle {
            vm.updatePreviewCommandDraft("echo 'Server ready at http://127.0.0.1:4567' && sleep 5")
        }
        composeRule.waitUntil(2_000) {
            vm.uiState.value.previewCommandDraft.contains("4567")
        }
        composeRule.runOnIdle {
            vm.startPreviewServer()
        }
        val afterStart = vm.uiState.value
        assertTrue(
            "Expected startPreviewServer to leave STOPPED, got status=${afterStart.previewStatus}, " +
                "msg='${afterStart.previewStatusMessage}', cmd='${afterStart.previewCommandDraft}', " +
                "proj='${afterStart.project?.workspacePath}', logs=${afterStart.previewConsoleLines.map { it.text }}",
            afterStart.previewStatus != PreviewServerStatus.STOPPED || afterStart.previewConsoleLines.isNotEmpty(),
        )

        try {
            composeRule.waitUntil(5_000) {
                org.robolectric.shadows.ShadowLooper.idleMainLooper()
                vm.uiState.value.previewStatus == PreviewServerStatus.RUNNING
            }
        } catch (t: Throwable) {
            val s = vm.uiState.value
            throw AssertionError(
                "Preview did not reach RUNNING. activeProcs=${runtimeExecutor.activeProcessCount.value}, " +
                    "project=${s.project?.workspacePath}, " +
                    "cmd='${s.previewCommandDraft}', status=${s.previewStatus}, msg='${s.previewStatusMessage}', " +
                    "url='${s.previewUrl}', err='${s.previewUrlError}', logs=${s.previewConsoleLines.map { it.text }}",
                t,
            )
        }
        val runningState = vm.uiState.value
        assertEquals("http://127.0.0.1:4567", runningState.previewUrl)
        assertTrue(runningState.previewBrowserActive)
        composeRule.waitUntil(3_000) {
            org.robolectric.shadows.ShadowLooper.idleMainLooper()
            composeRule.onAllNodesWithText("Running").fetchSemanticsNodes().isNotEmpty() &&
                composeRule.onAllNodesWithTag("preview_webview").fetchSemanticsNodes().isNotEmpty()
        }

        composeRule.runOnIdle {
            vm.stopPreviewServer()
        }
        composeRule.waitUntil(3_000) {
            org.robolectric.shadows.ShadowLooper.idleMainLooper()
            vm.uiState.value.previewStatus == PreviewServerStatus.STOPPED
        }
        assertEquals(PreviewServerStatus.STOPPED, vm.uiState.value.previewStatus)
    }

    @Test
    fun `PreviewView renders command url status badge and log toggle cleanly`() {
        composeRule.setContent {
            AgtTheme {
                PreviewView(
                    state = WorkspaceUiState(
                        previewUrl = "http://127.0.0.1:5173",
                        previewCommandDraft = "npm run dev -- --host 127.0.0.1",
                        previewDetectedFramework = "Vite",
                        previewStatus = PreviewServerStatus.STOPPED,
                        previewBrowserActive = false,
                    ),
                    onPreviewUrlChanged = {},
                    onPreviewCommandChanged = {},
                    onStartServer = {},
                    onStopServer = {},
                    onReloadOrOpenUrl = {},
                    onToggleLogs = {},
                    onWebViewError = { _, _ -> },
                )
            }
        }
        composeRule.onNodeWithTag("preview_url_input").assertIsDisplayed()
        composeRule.onNodeWithTag("preview_command_input").assertIsDisplayed()
        composeRule.onNodeWithTag("preview_start_button").assertIsDisplayed()
        composeRule.onNodeWithTag("preview_status_badge").assertIsDisplayed()
        composeRule.onNodeWithTag("preview_logs_toggle").assertIsDisplayed()
    }

    @Test
    fun `ExtensionsScreen supports Skills install disable uninstall and Plugins sandbox notice`() {
        val vm = ExtensionsViewModel(registry)
        composeRule.setContent {
            AgtTheme {
                ExtensionsScreen(viewModel = vm)
            }
        }

        // Wait for Room Flow initial emission, then inspect and install built-in Skill
        composeRule.waitUntil(3_000) {
            composeRule.onAllNodesWithTag("ext_item_agt.core.repo-inspect").fetchSemanticsNodes().isNotEmpty()
        }
        composeRule.onNodeWithTag("ext_item_agt.core.repo-inspect").performClick()
        composeRule.waitUntil(3_000) {
            composeRule.onAllNodesWithTag("ext_install_button").fetchSemanticsNodes().isNotEmpty()
        }
        composeRule.onNodeWithTag("ext_install_button").performClick()
        composeRule.waitUntil(3_000) {
            composeRule.onAllNodesWithText("Enabled").fetchSemanticsNodes().isNotEmpty()
        }
        composeRule.onNodeWithText("Cancel").performClick()
        composeRule.waitForIdle()

        // Switch to Plugins tab and verify honest sandbox gate message
        composeRule.onNodeWithTag("ext_tab_plugins").performClick()
        composeRule.waitForIdle()
        composeRule.onNodeWithText("Sandboxed Plugin Host Required").assertIsDisplayed()
    }

    private fun sha256Hex(bytes: ByteArray): String =
        MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }

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
}
