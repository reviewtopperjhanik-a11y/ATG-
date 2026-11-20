package com.example

import android.Manifest
import android.app.Application
import android.content.ClipboardManager
import android.content.Context
import android.net.Uri
import android.os.Build
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertTextEquals
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performScrollToNode
import androidx.compose.ui.test.performTextInput
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.example.agent.opencode.OpenCodeInstallerRepository
import com.example.core.config.AgtAppConfig
import com.example.core.config.ConfigResolution
import com.example.core.config.ConfigSource
import com.example.core.security.GatewayCredentialVault
import com.example.core.security.GitHubCredentialVault
import com.example.core.security.TokenCipher
import com.example.ui.theme.AgtTheme
import com.example.data.database.AgtDatabase
import com.example.data.gateway.GatewayRepository
import com.example.data.github.GitHubApiClient
import com.example.data.github.GitHubRepository
import com.example.data.preferences.AppPreferencesRepository
import com.example.data.project.ProjectRepository
import com.example.data.runtime.RuntimeInstallerRepository
import com.example.data.runtime.RuntimeManifestParser
import com.example.data.runtime.UpstreamBootstrapEndpoints
import com.example.data.runtime.UpstreamRuntimeBootstrap
import com.example.domain.model.ComponentInstallStatus
import com.example.domain.model.GitHubAuthStatus
import com.example.domain.model.RuntimeInstallStage
import com.example.domain.model.SettingsCategory
import com.example.feature.github.GitHubConnectScreen
import com.example.feature.github.GitHubConnectViewModel
import com.example.feature.settings.SettingsDetailScreen
import com.example.feature.settings.SettingsViewModel
import com.example.feature.setup.SetupScreen
import com.example.feature.setup.SetupViewModel
import com.sun.net.httpserver.HttpServer
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows
import org.robolectric.annotation.Config
import org.robolectric.util.ReflectionHelpers
import java.io.ByteArrayOutputStream
import java.io.File
import java.net.InetSocketAddress
import java.nio.charset.StandardCharsets
import java.security.MessageDigest
import java.util.zip.GZIPOutputStream

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class AutoConfigReleaseTest {

    @get:Rule
    val composeRule = createComposeRule()

    private lateinit var context: Context
    private lateinit var prefs: AppPreferencesRepository
    private lateinit var server: HttpServer
    private lateinit var baseUrl: String
    private lateinit var originalAbis: Array<String>

    private val testCipher = object : TokenCipher {
        override fun encrypt(plaintext: String): ByteArray =
            plaintext.toByteArray(StandardCharsets.UTF_8).map { (it.toInt() xor 0x4B).toByte() }.toByteArray()

        override fun decrypt(payload: ByteArray): String =
            String(payload.map { (it.toInt() xor 0x4B).toByte() }.toByteArray(), StandardCharsets.UTF_8)
    }

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        Shadows.shadowOf(context as Application).grantPermissions(Manifest.permission.POST_NOTIFICATIONS)
        originalAbis = Build.SUPPORTED_ABIS ?: arrayOf("armeabi-v7a")
        ReflectionHelpers.setStaticField(Build::class.java, "SUPPORTED_ABIS", arrayOf("arm64-v8a"))

        File(context.filesDir, "runtime").deleteRecursively()
        File(context.filesDir, "projects").deleteRecursively()
        context.getSharedPreferences("agt_github_vault", Context.MODE_PRIVATE).edit().clear().commit()
        context.getSharedPreferences("agt_gateway_vault", Context.MODE_PRIVATE).edit().clear().commit()
        context.deleteDatabase("test_autoconfig.db")

        prefs = AppPreferencesRepository(context)
        runBlocking {
            prefs.setOnboardingCompleted(false)
            prefs.setRuntimeManifestUrl("")
            prefs.setGitHubOAuthClientId("")
            prefs.setGitHubAccountMetadata(null, emptyList())
        }

        server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        server.start()
        baseUrl = "http://127.0.0.1:${server.address.port}"
    }

    @After
    fun tearDown() {
        server.stop(0)
        ReflectionHelpers.setStaticField(Build::class.java, "SUPPORTED_ABIS", originalAbis)
        File(context.filesDir, "runtime").deleteRecursively()
        File(context.filesDir, "projects").deleteRecursively()
        context.getSharedPreferences("agt_github_vault", Context.MODE_PRIVATE).edit().clear().commit()
        context.getSharedPreferences("agt_gateway_vault", Context.MODE_PRIVATE).edit().clear().commit()
        context.deleteDatabase("test_autoconfig.db")
        runBlocking {
            prefs.setOnboardingCompleted(false)
            prefs.setRuntimeManifestUrl("")
            prefs.setGitHubOAuthClientId("")
            prefs.setGitHubAccountMetadata(null, emptyList())
        }
    }

    // =========================================================================
    // Runtime Manifest Automatic Configuration & Verification Tests
    // =========================================================================

    @Test
    fun `runtime manifest loads from application config and installs without manual URL input`() {
        val tarBytes = buildSampleTarGz(
            entries = listOf(
                "rootfs/bin/sh" to "#!/bin/sh\necho ok\n".toByteArray(),
                "rootfs/bin/proot" to "ELF_PROOT".toByteArray(),
            )
        )
        val sha = sha256(tarBytes)

        server.createContext("/official-manifest.json") { ex ->
            val json = """
                {
                  "runtime_id": "agt-ubuntu-official",
                  "version": "2.1.0",
                  "release_channel": "stable",
                  "supported_abis": ["arm64-v8a"],
                  "min_android_sdk": 24,
                  "min_required_free_bytes": 1024,
                  "bundle": {
                    "download_url": "$baseUrl/runtime-arm64.tar.gz",
                    "compressed_size_bytes": ${tarBytes.size},
                    "sha256": "$sha",
                    "archive_format": "tar.gz",
                    "extraction_root": "rootfs",
                    "required_paths": ["bin/sh", "bin/proot"]
                  }
                }
            """.trimIndent().toByteArray(StandardCharsets.UTF_8)
            ex.sendResponseHeaders(200, json.size.toLong())
            ex.responseBody.use { it.write(json) }
        }
        server.createContext("/runtime-arm64.tar.gz") { ex ->
            ex.sendResponseHeaders(200, tarBytes.size.toLong())
            ex.responseBody.use { it.write(tarBytes) }
        }

        val config = AgtAppConfig(
            runtimeManifestUrl = "$baseUrl/official-manifest.json",
            githubOAuthClientId = "Ov23liOfficialApp",
            isDebugBuild = true,
        )
        val installer = RuntimeInstallerRepository(context, prefs, appConfig = config)
        val viewModel = SetupViewModel(
            appContext = context,
            preferencesRepository = prefs,
            runtimeInstallerRepository = installer,
            appConfig = config,
            useForegroundService = false,
        )

        var screenContext: Context? = null
        composeRule.setContent {
            screenContext = androidx.compose.ui.platform.LocalContext.current
            AgtTheme {
                SetupScreen(viewModel = viewModel, onContinueToGitHub = {})
            }
        }

        val permStatus = androidx.core.content.ContextCompat.checkSelfPermission(
            screenContext ?: context,
            Manifest.permission.POST_NOTIFICATIONS,
        )
        assertEquals(
            "Expected POST_NOTIFICATIONS granted on screenContext",
            android.content.pm.PackageManager.PERMISSION_GRANTED,
            permStatus,
        )
        assertEquals(0, composeRule.onAllNodesWithTag("setup_manifest_url_input").fetchSemanticsNodes().size)
        composeRule.onNodeWithTag("setup_install_button").assertIsDisplayed().performClick()
        composeRule.waitForIdle()

        runCatching {
            composeRule.waitUntil(timeoutMillis = 8_000) {
                val st = installer.installState.value
                val uiErr = viewModel.uiState.value.errorNotice
                st.isInstalled || st.stage == RuntimeInstallStage.FAILED || uiErr != null
            }
        }
        val finalState = installer.installState.value
        assertTrue(
            "Expected installed runtime, got stage=${finalState.stage}, isInstalling=${finalState.isInstalling}, error=${finalState.errorMessage}, detail=${finalState.statusDetail}, uiError=${viewModel.uiState.value.errorNotice}, configErr=${viewModel.uiState.value.configError}",
            finalState.isInstalled,
        )
        assertTrue(finalState.healthCheckPassed)
        assertEquals("2.1.0", finalState.installedVersion)
        assertEquals("arm64-v8a", finalState.installedAbi)
        assertTrue(File(installer.activeDir, "bin/sh").exists())
        composeRule.onNodeWithTag("setup_continue_button").assertIsDisplayed()
    }

    @Test
    fun `missing runtime manifest configuration displays actionable error without manual URL input`() {
        val emptyConfig = AgtAppConfig(
            runtimeManifestUrl = "",
            githubOAuthClientId = "",
            isDebugBuild = false,
            allowUpstreamBootstrap = false,
        )
        val installer = RuntimeInstallerRepository(context, prefs, appConfig = emptyConfig)
        val setupVm = SetupViewModel(context, prefs, installer, appConfig = emptyConfig)

        composeRule.setContent {
            AgtTheme {
                SetupScreen(viewModel = setupVm, onContinueToGitHub = {})
            }
        }

        assertEquals(0, composeRule.onAllNodesWithTag("setup_manifest_url_input").fetchSemanticsNodes().size)
        composeRule.onNodeWithTag("setup_list")
            .performScrollToNode(hasTestTag("setup_error_banner"))
        composeRule.onNodeWithTag("setup_error_banner").assertIsDisplayed()
        composeRule.onNodeWithText("AGT_RUNTIME_MANIFEST_URL", substring = true).assertIsDisplayed()

        composeRule.onNodeWithTag("setup_install_button").performClick()
        composeRule.waitForIdle()
        assertFalse(installer.installState.value.isInstalled)
    }

    @Test
    fun `runtime installer rejects invalid manifest structure, handles network failure, selects ABI bundle, and checks SHA-256`() = runBlocking {
        // 1. Invalid manifest JSON
        server.createContext("/bad-manifest.json") { ex ->
            val bytes = """{"runtime_id": "broken"}""".toByteArray(StandardCharsets.UTF_8)
            ex.sendResponseHeaders(200, bytes.size.toLong())
            ex.responseBody.use { it.write(bytes) }
        }
        val badConfig = AgtAppConfig(
            runtimeManifestUrl = "$baseUrl/bad-manifest.json",
            githubOAuthClientId = "Ov23liOfficialApp",
            isDebugBuild = true,
        )
        val badInstaller = RuntimeInstallerRepository(context, prefs, appConfig = badConfig)
        val badRes = badInstaller.installBlocking()
        assertTrue(badRes.isFailure)
        assertEquals(RuntimeInstallStage.FAILED, badInstaller.installState.value.stage)

        // 2. Network failure (HTTP 500)
        server.createContext("/error-500.json") { ex ->
            ex.sendResponseHeaders(500, -1)
        }
        val netConfig = badConfig.copy(runtimeManifestUrl = "$baseUrl/error-500.json")
        val netInstaller = RuntimeInstallerRepository(context, prefs, appConfig = netConfig)
        val netRes = netInstaller.installBlocking()
        assertTrue(netRes.isFailure)
        assertTrue(netInstaller.installState.value.errorMessage.orEmpty().isNotBlank())

        // 3. Multi-ABI bundles: picks x86_64 when device ABI is x86_64, rejects unsupported ABI, and verifies SHA-256
        val armBytes = buildSampleTarGz(listOf("rootfs/bin/sh" to "arm64".toByteArray()))
        val x86Bytes = buildSampleTarGz(listOf("rootfs/bin/sh" to "x86_64".toByteArray()))
        var serveTamperedX86 = false
        server.createContext("/multi-abi-manifest.json") { ex ->
            val json = """
                {
                  "runtime_id": "agt-multi-abi",
                  "version": "3.0.0",
                  "release_channel": "stable",
                  "supported_abis": ["arm64-v8a", "x86_64"],
                  "min_android_sdk": 24,
                  "min_required_free_bytes": 1024,
                  "bundles": {
                    "arm64-v8a": {
                      "download_url": "$baseUrl/arm64.tar.gz",
                      "compressed_size_bytes": ${armBytes.size},
                      "sha256": "${sha256(armBytes)}",
                      "archive_format": "tar.gz",
                      "extraction_root": "rootfs",
                      "required_paths": ["bin/sh"]
                    },
                    "x86_64": {
                      "download_url": "$baseUrl/x86_64.tar.gz",
                      "compressed_size_bytes": ${x86Bytes.size},
                      "sha256": "${sha256(x86Bytes)}",
                      "archive_format": "tar.gz",
                      "extraction_root": "rootfs",
                      "required_paths": ["bin/sh"]
                    }
                  }
                }
            """.trimIndent().toByteArray(StandardCharsets.UTF_8)
            ex.sendResponseHeaders(200, json.size.toLong())
            ex.responseBody.use { it.write(json) }
        }
        server.createContext("/arm64.tar.gz") { ex ->
            ex.sendResponseHeaders(200, armBytes.size.toLong())
            ex.responseBody.use { it.write(armBytes) }
        }
        server.createContext("/x86_64.tar.gz") { ex ->
            val payload = if (serveTamperedX86) "corrupted-bytes".toByteArray() else x86Bytes
            ex.sendResponseHeaders(200, payload.size.toLong())
            ex.responseBody.use { it.write(payload) }
        }

        // Verify unsupported ABI rejection
        ReflectionHelpers.setStaticField(Build::class.java, "SUPPORTED_ABIS", arrayOf("mips64"))
        val multiConfig = badConfig.copy(runtimeManifestUrl = "$baseUrl/multi-abi-manifest.json")
        val unsupportedInstaller = RuntimeInstallerRepository(context, prefs, appConfig = multiConfig)
        val unsupportedRes = unsupportedInstaller.installBlocking()
        assertTrue(unsupportedRes.isFailure)
        assertTrue(unsupportedRes.exceptionOrNull()?.message.orEmpty().contains("Unsupported CPU ABI", ignoreCase = true))

        // Verify SHA-256 rejection on tampered x86_64 download
        ReflectionHelpers.setStaticField(Build::class.java, "SUPPORTED_ABIS", arrayOf("x86_64"))
        serveTamperedX86 = true
        val tamperInstaller = RuntimeInstallerRepository(context, prefs, appConfig = multiConfig)
        val tamperRes = tamperInstaller.installBlocking()
        assertTrue(tamperRes.isFailure)
        assertFalse(tamperInstaller.installState.value.isInstalled)

        // Verify clean x86_64 install selects the x86_64 bundle automatically
        serveTamperedX86 = false
        val x86OkRes = tamperInstaller.installBlocking()
        assertTrue("Expected x86_64 install to succeed: ${x86OkRes.exceptionOrNull()?.message}", x86OkRes.isSuccess)
        assertEquals("x86_64", tamperInstaller.installState.value.installedAbi)
        assertEquals("x86_64", File(tamperInstaller.activeDir, "bin/sh").readText())
    }

    @Test
    fun `settings runtime screen hides manual manifest input and displays missing config error when unset`() {
        val db = Room.databaseBuilder(context, AgtDatabase::class.java, "test_autoconfig.db")
            .allowMainThreadQueries()
            .build()
        try {
            val missingConfig = AgtAppConfig(
                runtimeManifestUrl = "",
                githubOAuthClientId = "Ov23liOfficialApp",
                isDebugBuild = false,
                allowUpstreamBootstrap = false,
            )
            val installer = RuntimeInstallerRepository(context, prefs, appConfig = missingConfig)
            val openCode = OpenCodeInstallerRepository(context, installer, prefs)
            val projectRepo = ProjectRepository(context, db.projectDao(), db.taskDao(), db.checkpointDao())
            val gatewayVault = GatewayCredentialVault(context, testCipher)
            val gatewayRepo = GatewayRepository(prefs, gatewayVault, db.cachedModelDao())
            val ghVault = GitHubCredentialVault(context, testCipher)
            val ghRepo = GitHubRepository(prefs, ghVault, appConfig = missingConfig)

            val settingsVm = SettingsViewModel(
                appContext = context,
                preferencesRepository = prefs,
                projectRepository = projectRepo,
                gatewayRepository = gatewayRepo,
                gitHubRepository = ghRepo,
                runtimeInstallerRepository = installer,
                openCodeInstallerRepository = openCode,
                appConfig = missingConfig,
            )

            composeRule.setContent {
                AgtTheme {
                    SettingsDetailScreen(
                        category = SettingsCategory.RUNTIME,
                        viewModel = settingsVm,
                        onBack = {},
                        onNavigateToGateway = {},
                    )
                }
            }

            assertEquals(0, composeRule.onAllNodesWithTag("settings_runtime_manifest_input").fetchSemanticsNodes().size)
            composeRule.onNodeWithTag("settings_runtime_config_error").performScrollTo().assertIsDisplayed()
            composeRule.onNodeWithTag("settings_runtime_install_button").performScrollTo().assertIsDisplayed()
        } finally {
            db.close()
        }
    }

    // =========================================================================
    // GitHub PAT Primary & Backup Client ID (Device Flow + PKCE) Tests
    // =========================================================================

    @Test
    fun `GitHub Connect UI prioritizes PAT main system, hides manual Client ID when build configured, and supports PKCE`() {
        val config = AgtAppConfig(
            runtimeManifestUrl = "$baseUrl/manifest.json",
            githubOAuthClientId = "Ov23liOfficialClient77",
            isDebugBuild = true,
        )
        val ghVault = GitHubCredentialVault(context, testCipher)
        val apiClient = GitHubApiClient(apiBaseUrl = baseUrl, oauthBaseUrl = baseUrl)
        val ghRepo = GitHubRepository(prefs, ghVault, apiClient, appConfig = config)
        val vm = GitHubConnectViewModel(ghRepo, prefs, appConfig = config)

        composeRule.setContent {
            AgtTheme {
                GitHubConnectScreen(viewModel = vm, onBack = {}, onSkipToHome = {})
            }
        }

        // PAT is primary; manual Client ID input is hidden when build config supplies Client ID
        composeRule.onNodeWithTag("github_pat_input").performScrollTo().assertIsDisplayed()
        composeRule.onNodeWithTag("github_connect_button")
            .performScrollTo()
            .assertIsDisplayed()
            .assertTextEquals("Connect with Token")
        assertEquals(0, composeRule.onAllNodesWithTag("github_oauth_client_id_input").fetchSemanticsNodes().size)
        composeRule.onNodeWithTag("github_oauth_device_button").performScrollTo().assertIsDisplayed()

        // PKCE OAuth API continues to work cleanly with build config Client ID
        var capturedBody = ""
        server.createContext("/login/oauth/access_token") { ex ->
            capturedBody = ex.requestBody.readBytes().toString(StandardCharsets.UTF_8)
            val json = """{"access_token":"gho_auto_token_123","scope":"read:user,repo","token_type":"bearer"}"""
                .toByteArray(StandardCharsets.UTF_8)
            ex.sendResponseHeaders(200, json.size.toLong())
            ex.responseBody.use { it.write(json) }
        }
        server.createContext("/user") { ex ->
            ex.responseHeaders.add("X-OAuth-Scopes", "read:user, repo")
            val json = """{"login":"grace-hopper","name":"Grace"}""".toByteArray(StandardCharsets.UTF_8)
            ex.sendResponseHeaders(200, json.size.toLong())
            ex.responseBody.use { it.write(json) }
        }
        server.createContext("/user/repos") { ex ->
            val json = """
                [
                  {
                    "id": 9,
                    "name": "cobol-compiler",
                    "full_name": "grace-hopper/cobol-compiler",
                    "owner": {"login": "grace-hopper"},
                    "description": "Compiler",
                    "clone_url": "https://github.com/grace-hopper/cobol-compiler.git",
                    "html_url": "https://github.com/grace-hopper/cobol-compiler",
                    "default_branch": "main",
                    "private": false,
                    "updated_at": "2026-11-18T00:00:00Z"
                  }
                ]
            """.trimIndent().toByteArray(StandardCharsets.UTF_8)
            ex.sendResponseHeaders(200, json.size.toLong())
            ex.responseBody.use { it.write(json) }
        }

        val req = runBlocking { ghRepo.beginOAuthFlow(includePrivateRepos = true).getOrThrow() }
        val authUri = Uri.parse(req.authorizationUrl)
        assertEquals("Ov23liOfficialClient77", authUri.getQueryParameter("client_id"))
        assertEquals("S256", authUri.getQueryParameter("code_challenge_method"))
        assertTrue(!authUri.getQueryParameter("code_challenge").isNullOrBlank())
        assertFalse(req.authorizationUrl.contains("client_secret"))

        // Callback completes auth, fetches profile/repos, and never sends client_secret
        val callbackUri = Uri.parse("agtcode://oauth/github?code=code_999&state=${req.state}")
        val account = runBlocking { ghRepo.handleOAuthCallback(callbackUri).getOrThrow() }
        assertEquals(GitHubAuthStatus.CONNECTED, account.status)
        assertEquals("grace-hopper", account.username)
        assertTrue(capturedBody.contains("code_verifier="))
        assertTrue(capturedBody.contains("client_id=Ov23liOfficialClient77"))
        assertFalse(capturedBody.contains("client_secret"))
        assertEquals("grace-hopper/cobol-compiler", ghRepo.repositories.value.single().fullName)

        // Disconnect and reconnect work seamlessly
        runBlocking { ghRepo.disconnect() }
        assertEquals(GitHubAuthStatus.NOT_CONNECTED, ghRepo.accountState.value.status)
        assertTrue(ghRepo.repositories.value.isEmpty())

        val req2 = runBlocking { ghRepo.beginOAuthFlow().getOrThrow() }
        val account2 = runBlocking {
            ghRepo.handleOAuthCallback(Uri.parse("agtcode://oauth/github?code=code_reconnect&state=${req2.state}"))
                .getOrThrow()
        }
        assertEquals(GitHubAuthStatus.CONNECTED, account2.status)
    }

    @Test
    fun `PAT connects cleanly as main method when Client ID is unset and backup Device Flow auto-copies code`() {
        server.createContext("/user") { ex ->
            val auth = ex.requestHeaders.getFirst("Authorization")
            if (auth != "Bearer ghp_main_pat_12345" && auth != "Bearer gho_device_flow_token") {
                ex.sendResponseHeaders(401, -1)
                return@createContext
            }
            ex.responseHeaders.add("X-OAuth-Scopes", "read:user, repo")
            val login = if (auth == "Bearer ghp_main_pat_12345") "pat-user" else "device-user"
            val json = """{"login":"$login","name":"Dev"}""".toByteArray(StandardCharsets.UTF_8)
            ex.sendResponseHeaders(200, json.size.toLong())
            ex.responseBody.use { it.write(json) }
        }
        server.createContext("/user/repos") { ex ->
            val json = "[]".toByteArray(StandardCharsets.UTF_8)
            ex.sendResponseHeaders(200, json.size.toLong())
            ex.responseBody.use { it.write(json) }
        }
        server.createContext("/login/device/code") { ex ->
            val json = """
                {
                  "device_code": "dc_secret_999",
                  "user_code": "ABCD-1234",
                  "verification_uri": "https://github.com/login/device",
                  "expires_in": 600,
                  "interval": 1
                }
            """.trimIndent().toByteArray(StandardCharsets.UTF_8)
            ex.sendResponseHeaders(200, json.size.toLong())
            ex.responseBody.use { it.write(json) }
        }
        var pollCount = 0
        server.createContext("/login/oauth/access_token") { ex ->
            pollCount += 1
            val json = if (pollCount == 1) {
                """{"error":"authorization_pending"}"""
            } else {
                """{"access_token":"gho_device_flow_token","token_type":"bearer","scope":"read:user repo"}"""
            }.toByteArray(StandardCharsets.UTF_8)
            ex.sendResponseHeaders(200, json.size.toLong())
            ex.responseBody.use { it.write(json) }
        }

        val noBuildClientIdConfig = AgtAppConfig(
            runtimeManifestUrl = "$baseUrl/manifest.json",
            githubOAuthClientId = "",
            isDebugBuild = false,
        )
        val ghVault = GitHubCredentialVault(context, testCipher)
        val apiClient = GitHubApiClient(apiBaseUrl = baseUrl, oauthBaseUrl = baseUrl)
        val ghRepo = GitHubRepository(prefs, ghVault, apiClient, appConfig = noBuildClientIdConfig)
        val ghVm = GitHubConnectViewModel(ghRepo, prefs, appConfig = noBuildClientIdConfig)

        composeRule.setContent {
            AgtTheme {
                GitHubConnectScreen(viewModel = ghVm, onBack = {}, onSkipToHome = {})
            }
        }

        // No scary error banner when build Client ID is unset because PAT is primary
        assertEquals(0, composeRule.onAllNodesWithTag("github_error_banner").fetchSemanticsNodes().size)
        composeRule.onNodeWithTag("github_pat_input").performScrollTo().assertIsDisplayed()
        composeRule.onNodeWithTag("github_oauth_client_id_input").performScrollTo().assertIsDisplayed()

        // 1. Connect via PAT (Main system)
        composeRule.onNodeWithTag("github_pat_input").performScrollTo().performTextInput("ghp_main_pat_12345")
        composeRule.onNodeWithTag("github_connect_button").performScrollTo().performClick()
        runCatching {
            composeRule.waitUntil(timeoutMillis = 5_000) {
                ghRepo.accountState.value.status == GitHubAuthStatus.CONNECTED
            }
        }
        val patSnap = ghVm.uiState.value
        assertEquals(
            "Expected CONNECTED after PAT click; status=${ghRepo.accountState.value.status}, err=${patSnap.errorMessage}, info=${patSnap.infoMessage}, isBusy=${patSnap.isBusy}",
            GitHubAuthStatus.CONNECTED,
            ghRepo.accountState.value.status,
        )
        assertEquals("pat-user", ghRepo.accountState.value.username)

        // Disconnect to verify backup Device Flow
        composeRule.onNodeWithTag("github_disconnect_button").performScrollTo().performClick()
        composeRule.waitUntil(timeoutMillis = 5_000) {
            ghRepo.accountState.value.status == GitHubAuthStatus.NOT_CONNECTED
        }

        // 2. Connect via Backup Client ID Device Flow
        composeRule.onNodeWithTag("github_oauth_client_id_input").performScrollTo().performTextInput("Ov23liBackupClient")
        composeRule.onNodeWithTag("github_oauth_device_button").performScrollTo().performClick()

        runCatching {
            composeRule.waitUntil(timeoutMillis = 5_000) {
                composeRule.onAllNodesWithTag("github_device_user_code").fetchSemanticsNodes().isNotEmpty()
            }
        }
        val uiSnapshot = ghVm.uiState.value
        assertTrue(
            "Expected device_user_code node; devicePrompt=${ghRepo.devicePrompt.value}, uiPrompt=${uiSnapshot.devicePrompt}, status=${uiSnapshot.accountState.status}, err=${uiSnapshot.errorMessage}, info=${uiSnapshot.infoMessage}, isBusy=${uiSnapshot.isBusy}, clientIdPref=${runBlocking { prefs.preferences.first().githubOAuthClientId }}",
            composeRule.onAllNodesWithTag("github_device_user_code").fetchSemanticsNodes().isNotEmpty(),
        )
        composeRule.onNodeWithTag("github_device_user_code").performScrollTo().assertTextEquals("ABCD-1234")

        val cm = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        val copied = cm.primaryClip?.getItemAt(0)?.text?.toString()
        assertEquals("ABCD-1234", copied)

        // Polling completes automatically without deep link callback
        composeRule.waitUntil(timeoutMillis = 8_000) {
            ghRepo.accountState.value.status == GitHubAuthStatus.CONNECTED
        }
        assertEquals("device-user", ghRepo.accountState.value.username)
        assertEquals("Ov23liBackupClient", runBlocking { prefs.preferences.first().githubOAuthClientId })
    }

    @Test
    fun `Settings GitHub screen shows PAT as main method and backup Client ID input without config error`() {
        val db = Room.databaseBuilder(context, AgtDatabase::class.java, "test_autoconfig.db")
            .allowMainThreadQueries()
            .build()
        try {
            val missingOAuthConfig = AgtAppConfig(
                runtimeManifestUrl = "$baseUrl/manifest.json",
                githubOAuthClientId = "",
                isDebugBuild = false,
            )
            val ghVault = GitHubCredentialVault(context, testCipher)
            val apiClient = GitHubApiClient(apiBaseUrl = baseUrl, oauthBaseUrl = baseUrl)
            val ghRepo = GitHubRepository(prefs, ghVault, apiClient, appConfig = missingOAuthConfig)
            val installer = RuntimeInstallerRepository(context, prefs, appConfig = missingOAuthConfig)
            val openCode = OpenCodeInstallerRepository(context, installer, prefs)
            val projectRepo = ProjectRepository(context, db.projectDao(), db.taskDao(), db.checkpointDao())
            val gatewayRepo = GatewayRepository(prefs, GatewayCredentialVault(context, testCipher), db.cachedModelDao())
            val settingsVm = SettingsViewModel(
                appContext = context,
                preferencesRepository = prefs,
                projectRepository = projectRepo,
                gatewayRepository = gatewayRepo,
                gitHubRepository = ghRepo,
                runtimeInstallerRepository = installer,
                openCodeInstallerRepository = openCode,
                appConfig = missingOAuthConfig,
            )

            composeRule.setContent {
                AgtTheme {
                    SettingsDetailScreen(
                        category = SettingsCategory.GITHUB,
                        viewModel = settingsVm,
                        onBack = {},
                        onNavigateToGateway = {},
                    )
                }
            }

            assertEquals(0, composeRule.onAllNodesWithTag("settings_github_config_error").fetchSemanticsNodes().size)
            composeRule.onNodeWithTag("settings_github_pat_input").performScrollTo().assertIsDisplayed()
            composeRule.onNodeWithTag("settings_github_pat_connect_button").performScrollTo().assertIsDisplayed()
            composeRule.onNodeWithTag("settings_github_client_id_input").performScrollTo().assertIsDisplayed()
            composeRule.onNodeWithTag("settings_github_oauth_button").performScrollTo().assertIsDisplayed()
        } finally {
            db.close()
        }
    }

    // =========================================================================
    // Backward Compatibility & Release Configuration Validation Tests
    // =========================================================================

    @Test
    fun `legacy saved preferences migrate to official build config safely while preserving installed runtime and GitHub session`() = runBlocking {
        // Simulate legacy user settings stored prior to auto-configuration update
        val legacyLocalUrl = "$baseUrl/legacy-manifest.json"
        prefs.setRuntimeManifestUrl(legacyLocalUrl)
        prefs.setGitHubOAuthClientId("Ov23liLegacy123")

        // When build config is unconfigured, valid legacy preferences are still respected safely
        val emptyBuildConfig = AgtAppConfig(runtimeManifestUrl = "", githubOAuthClientId = "", isDebugBuild = true)
        val fallbackManifest = emptyBuildConfig.resolveRuntimeManifestUrl(legacyLocalUrl)
        assertTrue(fallbackManifest is ConfigResolution.Configured)
        assertEquals(ConfigSource.LEGACY_PREFERENCE, (fallbackManifest as ConfigResolution.Configured).source)

        val fallbackClient = emptyBuildConfig.resolveGitHubOAuthClientId("Ov23liLegacy123")
        assertTrue(fallbackClient is ConfigResolution.Configured)
        assertEquals(ConfigSource.LEGACY_PREFERENCE, (fallbackClient as ConfigResolution.Configured).source)

        // When official build config is supplied, official config takes precedence and stale legacy preference is cleared
        val tarBytes = buildSampleTarGz(listOf("rootfs/bin/sh" to "ok".toByteArray()))
        server.createContext("/prod-manifest.json") { ex ->
            val json = """
                {
                  "runtime_id": "agt-prod",
                  "version": "1.5.0",
                  "release_channel": "stable",
                  "supported_abis": ["arm64-v8a"],
                  "min_android_sdk": 24,
                  "min_required_free_bytes": 1024,
                  "bundle": {
                    "download_url": "$baseUrl/prod.tar.gz",
                    "compressed_size_bytes": ${tarBytes.size},
                    "sha256": "${sha256(tarBytes)}",
                    "archive_format": "tar.gz",
                    "extraction_root": "rootfs",
                    "required_paths": ["bin/sh"]
                  }
                }
            """.trimIndent().toByteArray(StandardCharsets.UTF_8)
            ex.sendResponseHeaders(200, json.size.toLong())
            ex.responseBody.use { it.write(json) }
        }
        server.createContext("/prod.tar.gz") { ex ->
            ex.sendResponseHeaders(200, tarBytes.size.toLong())
            ex.responseBody.use { it.write(tarBytes) }
        }

        val officialConfig = AgtAppConfig(
            runtimeManifestUrl = "$baseUrl/prod-manifest.json",
            githubOAuthClientId = "Ov23liOfficialProd",
            isDebugBuild = true,
        )
        val installer = RuntimeInstallerRepository(context, prefs, appConfig = officialConfig)
        assertTrue(installer.installBlocking().isSuccess)
        assertEquals("", prefs.preferences.first().runtimeManifestUrl)

        val ghVault = GitHubCredentialVault(context, testCipher)
        ghVault.saveToken("gho_existing_session_token").getOrThrow()
        prefs.setGitHubAccountMetadata("existing-user", listOf("read:user", "repo"))

        val apiClient = GitHubApiClient(apiBaseUrl = baseUrl, oauthBaseUrl = baseUrl)
        val ghRepo = GitHubRepository(prefs, ghVault, apiClient, appConfig = officialConfig)
        // Existing connected session remains intact
        val acc = ghRepo.accountState.first { it.status == GitHubAuthStatus.CONNECTED }
        assertEquals("existing-user", acc.username)

        // New OAuth flow uses official clientId and migrates/clears legacy preference
        val req = ghRepo.beginOAuthFlow().getOrThrow()
        assertTrue(req.authorizationUrl.contains("client_id=Ov23liOfficialProd"))
        assertEquals("", prefs.preferences.first().githubOAuthClientId)
    }

    @Test
    fun `production configuration validation enforces HTTPS, non-placeholder values, and forbids secrets`() {
        assertFalse(AgtAppConfig("", "", isDebugBuild = false).validateProductionConfiguration().isValid)
        assertFalse(
            AgtAppConfig("http://127.0.0.1:8080/manifest.json", "Ov23liValidClient", isDebugBuild = false)
                .validateProductionConfiguration().isValid
        )
        assertFalse(
            AgtAppConfig("https://example.com/manifest.json", "Ov23liValidClient", isDebugBuild = false)
                .validateProductionConfiguration().isValid
        )
        assertFalse(
            AgtAppConfig("https://releases.agtcode.dev/runtime/manifest.json", "ghp_secret_must_never_be_here", isDebugBuild = false)
                .validateProductionConfiguration().isValid
        )

        val validProd = AgtAppConfig(
            runtimeManifestUrl = "https://releases.agtcode.dev/runtime/manifest.json",
            githubOAuthClientId = "Ov23liOfficial987654",
            isDebugBuild = false,
        ).validateProductionConfiguration()
        assertTrue("Expected valid production config: ${validProd.errors}", validProd.isValid)

        // Multi-ABI manifest parser smoke check
        val multiAbiJson = """
            {
              "runtime_id": "agt-ubuntu",
              "version": "1.0.0",
              "release_channel": "stable",
              "supported_abis": ["arm64-v8a", "x86_64"],
              "min_android_sdk": 26,
              "min_required_free_bytes": 1024,
              "bundles": {
                "arm64-v8a": {
                  "download_url": "https://releases.agtcode.dev/arm64.tar.gz",
                  "compressed_size_bytes": 1024,
                  "sha256": "${"a".repeat(64)}",
                  "archive_format": "tar.gz",
                  "extraction_root": "rootfs",
                  "required_paths": ["bin/sh"]
                },
                "x86_64": {
                  "download_url": "https://releases.agtcode.dev/x86_64.tar.gz",
                  "compressed_size_bytes": 1024,
                  "sha256": "${"b".repeat(64)}",
                  "archive_format": "tar.gz",
                  "extraction_root": "rootfs",
                  "required_paths": ["bin/sh"]
                }
              }
            }
        """.trimIndent()
        val parsed = RuntimeManifestParser.parse(multiAbiJson).getOrThrow()
        assertEquals("arm64-v8a", parsed.selectBundleForDevice(listOf("arm64-v8a"))?.first)
        assertEquals("x86_64", parsed.selectBundleForDevice(listOf("x86_64"))?.first)
    }

    @Test
    fun `arm64 build config installs and verifies all five components and clears config error`() {
        val tarBytes = buildSampleTarGz(
            entries = listOf(
                "rootfs/bin/sh" to "#!/bin/sh\necho ok\n".toByteArray(),
                "rootfs/bin/proot" to "ELF_ARM64_PROOT".toByteArray(),
                "rootfs/etc/os-release" to "NAME=Ubuntu\nVERSION_ID=24.04\n".toByteArray(),
                "rootfs/usr/bin/git" to "ELF_ARM64_GIT".toByteArray(),
                "rootfs/usr/bin/node" to "ELF_ARM64_NODE".toByteArray(),
                "rootfs/usr/local/bin/opencode" to "ELF_ARM64_OPENCODE".toByteArray(),
                "rootfs/usr/bin/gh" to "ELF_ARM64_GH".toByteArray(),
                "rootfs/usr/local/share/agt/skills/registry.json" to """{"skills":[]}""".toByteArray(),
            )
        )
        val sha = sha256(tarBytes)
        server.createContext("/arm64-five-comp-manifest.json") { ex ->
            val json = """
                {
                  "manifest_schema_version": 1,
                  "runtime_id": "agt-ubuntu-arm64",
                  "version": "2.4.0",
                  "release_channel": "stable",
                  "supported_abis": ["arm64-v8a"],
                  "min_android_sdk": 26,
                  "min_required_free_bytes": 1024,
                  "bundles": {
                    "arm64-v8a": {
                      "download_url": "$baseUrl/agt-arm64-v2.4.0.tar.gz",
                      "compressed_size_bytes": ${tarBytes.size},
                      "sha256": "$sha",
                      "archive_format": "tar.gz",
                      "extraction_root": "rootfs",
                      "required_paths": ["bin/sh", "bin/proot"]
                    }
                  },
                  "components": [
                    {
                      "id": "linux_runtime",
                      "name": "Linux Runtime (PRoot / Ubuntu)",
                      "version": "2.4.0",
                      "required_paths": ["bin/sh", "bin/proot", "etc/os-release"],
                      "dependencies": []
                    },
                    {
                      "id": "base_toolchains",
                      "name": "Base Toolchains",
                      "version": "2.4.0",
                      "required_paths": ["usr/bin/git", "usr/bin/node"],
                      "dependencies": ["linux_runtime"]
                    },
                    {
                      "id": "opencode_agent",
                      "name": "OpenCode Agent Runtime",
                      "version": "1.2.0",
                      "required_paths": ["usr/local/bin/opencode"],
                      "dependencies": ["linux_runtime", "base_toolchains"]
                    },
                    {
                      "id": "github_cli",
                      "name": "GitHub CLI (gh)",
                      "version": "2.60.0",
                      "required_paths": ["usr/bin/gh"],
                      "dependencies": ["linux_runtime", "base_toolchains"]
                    },
                    {
                      "id": "base_skills_mcp",
                      "name": "Base Skills & MCP Registry",
                      "version": "1.0.0",
                      "required_paths": ["usr/local/share/agt/skills/registry.json"],
                      "dependencies": ["linux_runtime", "opencode_agent"]
                    }
                  ]
                }
            """.trimIndent().toByteArray(StandardCharsets.UTF_8)
            ex.sendResponseHeaders(200, json.size.toLong())
            ex.responseBody.use { it.write(json) }
        }
        server.createContext("/agt-arm64-v2.4.0.tar.gz") { ex ->
            ex.sendResponseHeaders(200, tarBytes.size.toLong())
            ex.responseBody.use { it.write(tarBytes) }
        }

        val config = AgtAppConfig(
            runtimeManifestUrl = "$baseUrl/arm64-five-comp-manifest.json",
            githubOAuthClientId = "Ov23liOfficialApp",
            isDebugBuild = true,
        )
        val installer = RuntimeInstallerRepository(context, prefs, appConfig = config)
        val viewModel = SetupViewModel(
            appContext = context,
            preferencesRepository = prefs,
            runtimeInstallerRepository = installer,
            appConfig = config,
            useForegroundService = false,
        )

        composeRule.setContent {
            AgtTheme {
                SetupScreen(viewModel = viewModel, onContinueToGitHub = {})
            }
        }

        assertEquals(0, composeRule.onAllNodesWithTag("setup_error_banner").fetchSemanticsNodes().size)
        composeRule.onNodeWithText("ARM64 Ready").assertIsDisplayed()
        composeRule.onNodeWithTag("setup_install_button").assertIsDisplayed().performClick()
        composeRule.waitForIdle()

        composeRule.waitUntil(timeoutMillis = 8_000) {
            val st = installer.installState.value
            st.isInstalled || st.stage == RuntimeInstallStage.FAILED
        }

        val finalState = installer.installState.value
        assertTrue("Expected install success, got ${finalState.errorMessage}", finalState.isInstalled)
        assertEquals("arm64-v8a", finalState.installedAbi)
        assertEquals(5, finalState.components.size)
        assertTrue(finalState.components.all { it.status == ComponentInstallStatus.COMPLETED })
        assertTrue(File(installer.activeDir, "usr/bin/gh").exists())
        assertTrue(File(installer.activeDir, "usr/local/bin/opencode").canExecute())
        assertTrue(File(installer.activeDir, ".agt-markers/base_skills_mcp.json").exists())

        composeRule.onNodeWithTag("setup_list")
            .performScrollToNode(hasText("5 / 5 installed"))
        composeRule.onNodeWithText("5 / 5 installed").assertIsDisplayed()
        composeRule.onNodeWithTag("setup_continue_button").assertIsDisplayed()

        // Removing a component binary triggers unhealthy health check, and repair restores all 5 components.
        File(installer.activeDir, "usr/bin/gh").delete()
        val damaged = runBlocking { installer.runHealthCheck() }
        assertFalse(damaged.isHealthy)
        assertFalse(installer.installState.value.isInstalled)

        val repaired = runBlocking { installer.repairRuntime() }
        assertTrue(repaired.isSuccess)
        assertTrue(installer.installState.value.isInstalled)
        assertTrue(installer.installState.value.components.all { it.status == ComponentInstallStatus.COMPLETED })
        assertTrue(File(installer.activeDir, "usr/bin/gh").exists())
    }

    @Test
    fun `missing component binary or invalid dependency graph aborts install without marking components installed`() = runBlocking {
        // 1. Missing component binary (`usr/bin/gh` absent inside archive)
        val incompleteTar = buildSampleTarGz(
            entries = listOf(
                "rootfs/bin/sh" to "#!/bin/sh\n".toByteArray(),
                "rootfs/bin/proot" to "ELF_PROOT".toByteArray(),
                "rootfs/usr/bin/git" to "ELF_GIT".toByteArray(),
            )
        )
        server.createContext("/missing-comp-file.json") { ex ->
            val json = """
                {
                  "runtime_id": "agt-incomplete",
                  "version": "1.0.0",
                  "release_channel": "stable",
                  "supported_abis": ["arm64-v8a"],
                  "min_android_sdk": 26,
                  "min_required_free_bytes": 1024,
                  "bundles": {
                    "arm64-v8a": {
                      "download_url": "$baseUrl/incomplete.tar.gz",
                      "compressed_size_bytes": ${incompleteTar.size},
                      "sha256": "${sha256(incompleteTar)}",
                      "archive_format": "tar.gz",
                      "extraction_root": "rootfs",
                      "required_paths": ["bin/sh", "bin/proot"]
                    }
                  },
                  "components": [
                    {
                      "id": "github_cli",
                      "name": "GitHub CLI (gh)",
                      "version": "2.60.0",
                      "required_paths": ["usr/bin/gh"],
                      "dependencies": ["linux_runtime"]
                    }
                  ]
                }
            """.trimIndent().toByteArray(StandardCharsets.UTF_8)
            ex.sendResponseHeaders(200, json.size.toLong())
            ex.responseBody.use { it.write(json) }
        }
        server.createContext("/incomplete.tar.gz") { ex ->
            ex.sendResponseHeaders(200, incompleteTar.size.toLong())
            ex.responseBody.use { it.write(incompleteTar) }
        }

        val cfg = AgtAppConfig(
            runtimeManifestUrl = "$baseUrl/missing-comp-file.json",
            githubOAuthClientId = "Ov23liOfficialApp",
            isDebugBuild = true,
        )
        val installer = RuntimeInstallerRepository(context, prefs, appConfig = cfg)
        val failRes = installer.installBlocking()
        assertTrue(failRes.isFailure)
        assertFalse(installer.installState.value.isInstalled)
        assertTrue(installer.installState.value.errorMessage.orEmpty().contains("usr/bin/gh"))
        assertFalse(installer.activeDir.exists())
        assertTrue(installer.installState.value.components.none { it.status == ComponentInstallStatus.COMPLETED })

        // 2. Circular dependency in manifest is rejected at parse stage
        val cyclicJson = """
            {
              "runtime_id": "agt-cyclic",
              "version": "1.0.0",
              "release_channel": "stable",
              "supported_abis": ["arm64-v8a"],
              "bundle": {
                "download_url": "https://cdn.agtcode.org/runtime.tar.gz",
                "compressed_size_bytes": 1024,
                "sha256": "${"c".repeat(64)}",
                "archive_format": "tar.gz",
                "required_paths": ["bin/sh"]
              },
              "components": [
                {"id": "opencode_agent", "name": "OpenCode", "dependencies": ["base_skills_mcp"]},
                {"id": "base_skills_mcp", "name": "Skills", "dependencies": ["opencode_agent"]}
              ]
            }
        """.trimIndent()
        assertTrue(RuntimeManifestParser.parse(cyclicJson).isFailure)
    }

    @Test
    fun `default empty manifest config activates upstream bootstrap and installs all components`() {
        val ubuntuTar = buildSampleTarGz(
            entries = listOf(
                "bin/sh" to "#!/bin/sh\necho ubuntu\n".toByteArray(),
                "etc/os-release" to "NAME=Ubuntu\nVERSION_ID=24.04\n".toByteArray(),
            )
        )
        val nodeTar = buildSampleTarGz(
            entries = listOf(
                "node-v22.12.0-linux-arm64/bin/node" to "ELF_NODE".toByteArray(),
                "node-v22.12.0-linux-arm64/bin/npm" to "#!/bin/sh\necho npm\n".toByteArray(),
            )
        )
        val ghTar = buildSampleTarGz(
            entries = listOf(
                "gh_2.63.0_linux_arm64/bin/gh" to "ELF_GH".toByteArray(),
            )
        )
        val ocTar = buildSampleTarGz(
            entries = listOf(
                "opencode" to "ELF_OPENCODE".toByteArray(),
            )
        )

        server.createContext("/ubuntu/SHA256SUMS") { ex ->
            val body = "${sha256(ubuntuTar)} *ubuntu-base-24.04.1-base-arm64.tar.gz\n".toByteArray()
            ex.sendResponseHeaders(200, body.size.toLong())
            ex.responseBody.use { it.write(body) }
        }
        server.createContext("/ubuntu/ubuntu-base-24.04.1-base-arm64.tar.gz") { ex ->
            ex.sendResponseHeaders(200, ubuntuTar.size.toLong())
            ex.responseBody.use { it.write(ubuntuTar) }
        }
        server.createContext("/node/SHASUMS256.txt") { ex ->
            val body = "${sha256(nodeTar)}  node-v22.12.0-linux-arm64.tar.gz\n".toByteArray()
            ex.sendResponseHeaders(200, body.size.toLong())
            ex.responseBody.use { it.write(body) }
        }
        server.createContext("/node/node-v22.12.0-linux-arm64.tar.gz") { ex ->
            ex.sendResponseHeaders(200, nodeTar.size.toLong())
            ex.responseBody.use { it.write(nodeTar) }
        }
        server.createContext("/gh/releases/latest") { ex ->
            val body = """
                {
                  "tag_name": "v2.63.0",
                  "assets": [
                    {
                      "name": "gh_2.63.0_linux_arm64.tar.gz",
                      "size": ${ghTar.size},
                      "browser_download_url": "$baseUrl/gh/gh_2.63.0_linux_arm64.tar.gz"
                    },
                    {
                      "name": "gh_2.63.0_checksums.txt",
                      "size": 128,
                      "browser_download_url": "$baseUrl/gh/gh_2.63.0_checksums.txt"
                    }
                  ]
                }
            """.trimIndent().toByteArray()
            ex.sendResponseHeaders(200, body.size.toLong())
            ex.responseBody.use { it.write(body) }
        }
        server.createContext("/gh/gh_2.63.0_checksums.txt") { ex ->
            val body = "${sha256(ghTar)}  gh_2.63.0_linux_arm64.tar.gz\n".toByteArray()
            ex.sendResponseHeaders(200, body.size.toLong())
            ex.responseBody.use { it.write(body) }
        }
        server.createContext("/gh/gh_2.63.0_linux_arm64.tar.gz") { ex ->
            ex.sendResponseHeaders(200, ghTar.size.toLong())
            ex.responseBody.use { it.write(ghTar) }
        }
        server.createContext("/opencode/releases/latest") { ex ->
            val body = """
                {
                  "tag_name": "v1.4.0",
                  "assets": [
                    {
                      "name": "opencode-linux-arm64.tar.gz",
                      "size": ${ocTar.size},
                      "digest": "sha256:${sha256(ocTar)}",
                      "browser_download_url": "$baseUrl/opencode/opencode-linux-arm64.tar.gz"
                    }
                  ]
                }
            """.trimIndent().toByteArray()
            ex.sendResponseHeaders(200, body.size.toLong())
            ex.responseBody.use { it.write(body) }
        }
        server.createContext("/opencode/opencode-linux-arm64.tar.gz") { ex ->
            ex.sendResponseHeaders(200, ocTar.size.toLong())
            ex.responseBody.use { it.write(ocTar) }
        }

        val defaultConfig = AgtAppConfig(
            runtimeManifestUrl = "",
            githubOAuthClientId = "Ov23liOfficialApp",
            isDebugBuild = true,
        )
        val upstream = UpstreamRuntimeBootstrap(
            endpoints = UpstreamBootstrapEndpoints(
                ubuntuSha256SumsUrl = "$baseUrl/ubuntu/SHA256SUMS",
                ubuntuBaseDirUrl = "$baseUrl/ubuntu",
                nodeSha256SumsUrl = "$baseUrl/node/SHASUMS256.txt",
                nodeBaseDirUrl = "$baseUrl/node",
                ghReleaseApiUrl = "$baseUrl/gh/releases/latest",
                openCodeReleaseApiUrls = listOf("$baseUrl/opencode/releases/latest"),
            )
        )
        val installer = RuntimeInstallerRepository(
            context = context,
            preferencesRepository = prefs,
            appConfig = defaultConfig,
            upstreamBootstrap = upstream,
        )
        val openCodeRepo = OpenCodeInstallerRepository(context, installer, prefs)
        val viewModel = SetupViewModel(
            appContext = context,
            preferencesRepository = prefs,
            runtimeInstallerRepository = installer,
            appConfig = defaultConfig,
            useForegroundService = false,
        )

        composeRule.setContent {
            AgtTheme {
                SetupScreen(viewModel = viewModel, onContinueToGitHub = {})
            }
        }

        assertEquals(0, composeRule.onAllNodesWithTag("setup_error_banner").fetchSemanticsNodes().size)
        composeRule.onNodeWithTag("setup_list")
            .performScrollToNode(hasTestTag("setup_upstream_banner"))
        composeRule.onNodeWithTag("setup_upstream_banner").assertIsDisplayed()

        composeRule.onNodeWithTag("setup_install_button").assertIsDisplayed().performClick()
        composeRule.waitForIdle()

        composeRule.waitUntil(timeoutMillis = 10_000) {
            val st = installer.installState.value
            st.isInstalled || st.stage == RuntimeInstallStage.FAILED
        }

        val finalState = installer.installState.value
        assertTrue("Expected upstream install success, got ${finalState.errorMessage}", finalState.isInstalled)
        assertEquals("24.04.1", finalState.installedVersion)
        assertEquals("arm64-v8a", finalState.installedAbi)
        assertTrue(finalState.components.all { it.status == ComponentInstallStatus.COMPLETED })
        assertTrue(File(installer.activeDir, "bin/sh").exists())
        assertTrue(File(installer.activeDir, "usr/bin/node").canExecute())
        assertTrue(File(installer.activeDir, "usr/bin/npm").canExecute())
        assertTrue(File(installer.activeDir, "usr/bin/gh").canExecute())
        assertTrue(File(installer.activeDir, "usr/local/bin/opencode").canExecute())
        assertTrue(File(installer.activeDir, "usr/local/share/agt/skills/registry.json").exists())
        assertTrue(File(installer.activeDir, "etc/resolv.conf").exists())
        assertTrue(openCodeRepo.refreshStatusSync().isInstalled)

        composeRule.onNodeWithTag("setup_continue_button").assertIsDisplayed()
    }

    @Test
    fun `settings runtime screen displays upstream bootstrap banner under default config without error`() {
        val db = Room.databaseBuilder(context, AgtDatabase::class.java, "test_autoconfig.db")
            .allowMainThreadQueries()
            .build()
        try {
            val defaultConfig = AgtAppConfig(
                runtimeManifestUrl = "",
                githubOAuthClientId = "Ov23liOfficialApp",
                isDebugBuild = false,
            )
            val installer = RuntimeInstallerRepository(context, prefs, appConfig = defaultConfig)
            val openCode = OpenCodeInstallerRepository(context, installer, prefs)
            val projectRepo = ProjectRepository(context, db.projectDao(), db.taskDao(), db.checkpointDao())
            val gatewayVault = GatewayCredentialVault(context, testCipher)
            val gatewayRepo = GatewayRepository(prefs, gatewayVault, db.cachedModelDao())
            val ghVault = GitHubCredentialVault(context, testCipher)
            val ghRepo = GitHubRepository(prefs, ghVault, appConfig = defaultConfig)

            val settingsVm = SettingsViewModel(
                appContext = context,
                preferencesRepository = prefs,
                projectRepository = projectRepo,
                gatewayRepository = gatewayRepo,
                gitHubRepository = ghRepo,
                runtimeInstallerRepository = installer,
                openCodeInstallerRepository = openCode,
                appConfig = defaultConfig,
            )

            composeRule.setContent {
                AgtTheme {
                    SettingsDetailScreen(
                        category = SettingsCategory.RUNTIME,
                        viewModel = settingsVm,
                        onBack = {},
                        onNavigateToGateway = {},
                    )
                }
            }

            assertEquals(0, composeRule.onAllNodesWithTag("settings_runtime_config_error").fetchSemanticsNodes().size)
            composeRule.onNodeWithTag("settings_runtime_upstream_banner").performScrollTo().assertIsDisplayed()
            composeRule.onNodeWithTag("settings_runtime_install_button").performScrollTo().assertIsDisplayed()
        } finally {
            db.close()
        }
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
