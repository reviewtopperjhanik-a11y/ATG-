package com.example

import android.content.Context
import android.os.Build
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assertHeightIsAtLeast
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.unit.dp
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.example.agent.opencode.OpenCodeInstallerRepository
import com.example.agent.tools.ToolBroker
import com.example.core.common.AgtCrashHandler
import com.example.core.common.AgtError
import com.example.core.security.GatewayCredentialVault
import com.example.core.security.GatewayUrlValidation
import com.example.core.security.GatewayUrlValidator
import com.example.core.security.GitHubCredentialVault
import com.example.core.security.SecretRedactor
import com.example.core.security.TokenCipher
import com.example.data.database.AgtDatabase
import com.example.data.gateway.GatewayRepository
import com.example.data.github.GitHubApiClient
import com.example.data.github.GitHubRepository
import com.example.data.github.GitRepositoryService
import com.example.data.preferences.AppPreferencesRepository
import com.example.data.project.ProjectRepository
import com.example.data.runtime.LocalRuntimeExecutor
import com.example.data.runtime.RuntimeArchiveExtractor
import com.example.data.runtime.RuntimeDownloader
import com.example.data.runtime.RuntimeInstallerRepository
import com.example.domain.contracts.ProcessEvent
import com.example.domain.contracts.RuntimeCommand
import com.example.domain.model.AppPreferencesState
import com.example.domain.model.RuntimeArchiveFormat
import com.example.domain.model.RuntimeBundleSpec
import com.example.domain.model.SettingsCategory
import com.example.domain.model.ToolBrokerDecision
import com.example.feature.home.HomeScreen
import com.example.feature.home.HomeViewModel
import com.example.feature.settings.SettingsDetailScreen
import com.example.feature.settings.SettingsListScreen
import com.example.feature.settings.SettingsViewModel
import com.example.ui.theme.AgtTheme
import com.sun.net.httpserver.HttpServer
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
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
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.UnknownHostException
import java.nio.charset.StandardCharsets
import java.security.MessageDigest
import java.util.zip.GZIPOutputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class HardeningReleaseM10Test {

    @get:Rule
    val composeRule = createComposeRule()

    private lateinit var context: Context
    private lateinit var prefs: AppPreferencesRepository
    private lateinit var db: AgtDatabase
    private lateinit var runtimeInstaller: RuntimeInstallerRepository
    private lateinit var runtimeExecutor: LocalRuntimeExecutor

    private val testCipher = object : TokenCipher {
        override fun encrypt(plaintext: String): ByteArray =
            plaintext.toByteArray(StandardCharsets.UTF_8).map { (it.toInt() xor 0x3C).toByte() }.toByteArray()

        override fun decrypt(payload: ByteArray): String =
            String(payload.map { (it.toInt() xor 0x3C).toByte() }.toByteArray(), StandardCharsets.UTF_8)
    }

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        context.deleteDatabase("test_m10.db")
        AgtCrashHandler.clearLastCrash(context)
        File(context.filesDir, "runtime").deleteRecursively()
        File(context.filesDir, "projects").deleteRecursively()

        prefs = AppPreferencesRepository(context)
        db = Room.databaseBuilder(context, AgtDatabase::class.java, "test_m10.db")
            .allowMainThreadQueries()
            .build()
        runtimeInstaller = RuntimeInstallerRepository(context, prefs)
        runtimeExecutor = LocalRuntimeExecutor(runtimeInstaller, maxConcurrentProcesses = 2)
    }

    @After
    fun tearDown() {
        runBlocking {
            runtimeExecutor.terminateAll()
            prefs.setRuntimeManifestUrl("")
        }
        AgtCrashHandler.clearLastCrash(context)
        db.close()
        context.deleteDatabase("test_m10.db")
        File(context.filesDir, "runtime").deleteRecursively()
        File(context.filesDir, "projects").deleteRecursively()
    }

    @Test
    fun `SecretRedactor and GatewayUrlValidator enforce token masking and SSRF defenses`() {
        val rawLog = "Authorization: Bearer super_secret_gw_123 " +
            "https://alice:mypassword@example.com/repo.git " +
            "ghp_abcdefghijklmnopqrstuvwxyz123456 " +
            "github_pat_11AAAAA00_bbbbbbbbbbbbbbbbbbbbbb"
        val redacted = SecretRedactor.redact(rawLog, listOf("explicit_token_xyz"))
        assertFalse(redacted.contains("super_secret_gw_123"))
        assertFalse(redacted.contains("mypassword"))
        assertFalse(redacted.contains("ghp_abcdefghijklmnopqrstuvwxyz"))
        assertFalse(redacted.contains("github_pat_11AAAAA00"))
        assertTrue(redacted.contains("[REDACTED]"))

        // Loopback HTTP allowed for local JH GetWay Pro
        assertTrue(GatewayUrlValidator.validate("http://127.0.0.1:8787") is GatewayUrlValidation.Valid)
        assertTrue(GatewayUrlValidator.validate("http://localhost:8787") is GatewayUrlValidation.Valid)

        // SSRF literal private, link-local, metadata, CGNAT, unspecified hosts blocked
        val blockedUrls = listOf(
            "https://169.254.169.254/latest/meta-data",
            "https://metadata.google.internal/computeMetadata/v1",
            "https://10.0.0.1:8443",
            "https://172.16.4.2",
            "https://192.168.1.50",
            "https://100.64.0.1",
            "https://0.0.0.0:8080",
            "https://user:pass@gateway.example.com",
            "http://gateway.example.com",
        )
        blockedUrls.forEach { bad ->
            assertTrue("Expected SSRF/URL rejection for $bad", GatewayUrlValidator.validate(bad) is GatewayUrlValidation.Invalid)
        }

        // DNS rebinding guard blocks public hostname resolving to loopback or private IP
        val rebindCheck = GatewayUrlValidator.validateResolvedHost("https://rebind.example.com") {
            listOf(InetAddress.getByAddress(byteArrayOf(127, 0, 0, 1)))
        }
        assertTrue(rebindCheck is GatewayUrlValidation.Invalid)

        val okDnsCheck = GatewayUrlValidator.validateResolvedHost("https://api.example.com") {
            listOf(InetAddress.getByAddress(byteArrayOf(93, 184.toByte(), 216.toByte(), 34)))
        }
        assertTrue(okDnsCheck is GatewayUrlValidation.Valid)

        var thrownOnRebind = false
        try {
            val mockDns = object : okhttp3.Dns {
                override fun lookup(hostname: String): List<InetAddress> =
                    listOf(InetAddress.getByAddress(byteArrayOf(169.toByte(), 254.toByte(), 169.toByte(), 254.toByte())))
            }
            GatewayUrlValidator.ssrfGuardDns(mockDns).lookup("rebind.example.com")
        } catch (_: UnknownHostException) {
            thrownOnRebind = true
        }
        assertTrue(thrownOnRebind)
    }

    @Test
    fun `RuntimeDownloader rejects insecure remote and SSRF bundle URLs`() = runBlocking {
        val downloader = RuntimeDownloader()
        val downloadsDir = File(context.filesDir, "runtime/downloads")

        val badHttp = downloader.downloadAndVerify(
            bundle = RuntimeBundleSpec(
                downloadUrl = "http://cdn.example.com/rootfs.tar.gz",
                compressedSizeBytes = 100L,
                sha256 = "a".repeat(64),
                archiveFormat = RuntimeArchiveFormat.TAR_GZ,
            ),
            downloadsDir = downloadsDir,
            targetArchiveName = "rootfs.tar.gz",
            onProgress = { _, _ -> },
        )
        assertTrue(badHttp.isFailure)

        val badSsrf = downloader.downloadAndVerify(
            bundle = RuntimeBundleSpec(
                downloadUrl = "https://169.254.169.254/latest.tar.gz",
                compressedSizeBytes = 100L,
                sha256 = "a".repeat(64),
                archiveFormat = RuntimeArchiveFormat.TAR_GZ,
            ),
            downloadsDir = downloadsDir,
            targetArchiveName = "rootfs.tar.gz",
            onProgress = { _, _ -> },
        )
        assertTrue(badSsrf.isFailure)
    }

    @Test
    fun `ToolBroker blocks vault diagnostics and environ exfiltration and backup rules exclude vaults`() {
        val ws = File(context.filesDir, "projects/m10-sec").apply { mkdirs() }
        val broker = ToolBroker(projectRootPath = ws.absolutePath)

        val targets = listOf(
            "cat /data/data/com.example/shared_prefs/agt_gateway_vault.xml",
            "cat /data/data/com.example/shared_prefs/agt_extension_vault.xml",
            "cat files/diagnostics/last_crash.json",
            "cat /proc/self/environ",
        )
        targets.forEachIndexed { idx, cmd ->
            val decision = broker.evaluateToolCall(
                toolCallId = "sec-$idx",
                rawToolName = "bash",
                rawArgumentsJson = """{"command":"$cmd"}""",
                preferences = AppPreferencesState(),
            )
            assertTrue("Expected ToolBroker rejection for: $cmd", decision is ToolBrokerDecision.Rejected)
        }

        val backupXml = context.resources.getXml(R.xml.backup_rules).let { parser ->
            buildString {
                var ev = parser.eventType
                while (ev != org.xmlpull.v1.XmlPullParser.END_DOCUMENT) {
                    if (ev == org.xmlpull.v1.XmlPullParser.START_TAG) {
                        append(parser.name).append(' ')
                        for (i in 0 until parser.attributeCount) {
                            append(parser.getAttributeName(i)).append('=').append(parser.getAttributeValue(i)).append(' ')
                        }
                    }
                    ev = parser.next()
                }
            }
        }
        assertTrue(backupXml.contains("agt_gateway_vault.xml"))
        assertTrue(backupXml.contains("agt_github_vault.xml"))
        assertTrue(backupXml.contains("agt_extension_vault.xml"))
        assertTrue(backupXml.contains("diagnostics"))
    }

    @Test
    fun `RuntimeArchiveExtractor rejects zip bombs exceeding size or entry limits`() = runBlocking {
        val zipFile = File(context.cacheDir, "bomb_test.zip")
        val stagingDir = File(context.cacheDir, "bomb_staging")
        try {
            ZipOutputStream(zipFile.outputStream()).use { zos ->
                repeat(12) { idx ->
                    zos.putNextEntry(ZipEntry("dir/file_$idx.txt"))
                    zos.write("0123456789ABCDEF".repeat(32).toByteArray())
                    zos.closeEntry()
                }
            }

            // Exceeds maxEntries = 5
            val tooManyEntries = RuntimeArchiveExtractor.extractToStaging(
                archiveFile = zipFile,
                format = RuntimeArchiveFormat.ZIP,
                stagingRoot = stagingDir,
                maxEntries = 5,
            )
            assertTrue(tooManyEntries.isFailure)
            assertFalse(stagingDir.exists())

            // Exceeds maxExtractedBytes = 500
            val tooLarge = RuntimeArchiveExtractor.extractToStaging(
                archiveFile = zipFile,
                format = RuntimeArchiveFormat.ZIP,
                stagingRoot = stagingDir,
                maxExtractedBytes = 500L,
            )
            assertTrue(tooLarge.isFailure)
            assertFalse(stagingDir.exists())
        } finally {
            zipFile.delete()
            stagingDir.deleteRecursively()
        }
    }

    @Test
    fun `LocalRuntimeExecutor bounds massive line flood and enforces maxConcurrentProcesses`() = runBlocking {
        assumeTrue("Host OS has /bin/sh", File("/bin/sh").exists())
        val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        server.start()
        try {
            installTestRuntime(server)
            val projectDir = File(context.filesDir, "projects/m10-perf").apply { mkdirs() }

            // 1) Massive line flood must be bounded quickly without OOM or hang
            val floodEvents = withTimeout(10_000) {
                runtimeExecutor.execute(
                    RuntimeCommand(
                        command = "i=1; while [ \$i -le 1200 ]; do echo \"flood-\$i\"; i=\$((i+1)); done",
                        workingDirectory = projectDir.absolutePath,
                        maxOutputLines = 100,
                    )
                ).toList()
            }
            val stdouts = floodEvents.filterIsInstance<ProcessEvent.Stdout>()
            val stderrs = floodEvents.filterIsInstance<ProcessEvent.Stderr>()
            assertEquals(100, stdouts.size)
            assertTrue(stderrs.any { it.line.contains("Output truncated after 100 lines") })
            assertEquals(0, runtimeExecutor.activeProcessCount.value)

            // 2) Concurrent process limit (maxConcurrentProcesses = 2) rejects 3rd process cleanly
            val s1 = kotlinx.coroutines.CompletableDeferred<Unit>()
            val s2 = kotlinx.coroutines.CompletableDeferred<Unit>()
            val job1 = launch {
                runtimeExecutor.execute(
                    RuntimeCommand("echo P1; sleep 5", projectDir.absolutePath, processId = "p1", timeoutSeconds = 10)
                ).collect { if (it is ProcessEvent.Started) s1.complete(Unit) }
            }
            val job2 = launch {
                runtimeExecutor.execute(
                    RuntimeCommand("echo P2; sleep 5", projectDir.absolutePath, processId = "p2", timeoutSeconds = 10)
                ).collect { if (it is ProcessEvent.Started) s2.complete(Unit) }
            }
            withTimeout(3_000) {
                s1.await()
                s2.await()
            }
            val thirdEvents = runtimeExecutor.execute(
                RuntimeCommand("echo P3", projectDir.absolutePath, processId = "p3")
            ).toList()
            val thirdFail = thirdEvents.single() as ProcessEvent.Failed
            assertTrue(thirdFail.error is AgtError.ProcessStartFailed)

            runtimeExecutor.terminateAll()
            job1.join()
            job2.join()
            assertEquals(0, runtimeExecutor.activeProcessCount.value)
        } finally {
            server.stop(0)
        }
    }

    @Test
    fun `AgtCrashHandler redacts secrets persists summary and clears via Settings and Home UI`() {
        val secret = "jh_live_super_secret_987"
        val recorded = AgtCrashHandler.recordCrash(
            context = context,
            throwable = IllegalStateException("Crash calling Gateway with Bearer $secret"),
            knownSecrets = listOf(secret),
        )
        assertFalse(recorded.message.contains(secret))
        assertFalse(recorded.stackExcerpt.contains(secret))
        assertTrue(recorded.message.contains("[REDACTED]"))

        val readBack = AgtCrashHandler.readLastCrash(context)
        assertNotNull(readBack)
        assertEquals(IllegalStateException::class.java.name, readBack!!.exceptionClass)

        val projectRepo = ProjectRepository(context, db.projectDao(), db.taskDao(), db.checkpointDao())
        val gwVault = GatewayCredentialVault(context, testCipher)
        val ghVault = GitHubCredentialVault(context, testCipher)
        val gwRepo = GatewayRepository(prefs, gwVault, db.cachedModelDao())
        val ghRepo = GitHubRepository(prefs, ghVault, GitHubApiClient())
        val ocInstaller = OpenCodeInstallerRepository(context, runtimeInstaller, prefs)

        val settingsVm = SettingsViewModel(
            appContext = context,
            preferencesRepository = prefs,
            projectRepository = projectRepo,
            gatewayRepository = gwRepo,
            gitHubRepository = ghRepo,
            runtimeInstallerRepository = runtimeInstaller,
            openCodeInstallerRepository = ocInstaller,
        )

        composeRule.setContent {
            AgtTheme {
                SettingsDetailScreen(
                    category = SettingsCategory.ABOUT,
                    viewModel = settingsVm,
                    onBack = {},
                    onNavigateToGateway = {},
                )
            }
        }

        // Verify Crash Diagnostics, Release Signing & Update Plan, License Notices, Headings, and >= 48dp target
        composeRule.onNodeWithTag("about_crash_card").performScrollTo().assertIsDisplayed()
        composeRule.onNodeWithTag("about_clear_crash_button")
            .performScrollTo()
            .assertIsDisplayed()
            .assertHeightIsAtLeast(48.dp)
        composeRule.onNodeWithTag("about_release_plan_card").performScrollTo().assertIsDisplayed()
        composeRule.onNodeWithTag("about_licenses_card").performScrollTo().assertIsDisplayed()
        composeRule.onNodeWithText("PRoot", substring = true).performScrollTo().assertIsDisplayed()
        composeRule.onNodeWithText("GPL-2.0", substring = true).performScrollTo().assertIsDisplayed()
        composeRule.onNodeWithText("AntiGem", substring = true).performScrollTo().assertIsDisplayed()

        // Verify semantic headings exist
        val headings = composeRule.onAllNodes(SemanticsMatcher.keyIsDefined(SemanticsProperties.Heading))
            .fetchSemanticsNodes()
        assertTrue(headings.size >= 4)

        // Clear crash diagnostics cleanly
        composeRule.onNodeWithTag("about_clear_crash_button").performScrollTo().performClick()
        composeRule.waitUntil(3_000) {
            AgtCrashHandler.readLastCrash(context) == null
        }
        assertNull(AgtCrashHandler.readLastCrash(context))
    }

    @Test
    fun `HomeScreen surfaces redacted crash recovery banner and dismisses it cleanly`() {
        val secret = "ghp_secretpat1234567890abcdef"
        AgtCrashHandler.recordCrash(
            context = context,
            throwable = RuntimeException("Sync error with token $secret"),
            knownSecrets = listOf(secret),
        )

        val projectRepo = ProjectRepository(context, db.projectDao(), db.taskDao(), db.checkpointDao())
        val ghVault = GitHubCredentialVault(context, testCipher)
        val ghRepo = GitHubRepository(prefs, ghVault, GitHubApiClient())
        val gitService = GitRepositoryService(runtimeExecutor, runtimeInstaller, projectRepo, ghVault, prefs)
        val homeVm = HomeViewModel(
            appContext = context,
            projectRepository = projectRepo,
            preferencesRepository = prefs,
            gitHubRepository = ghRepo,
            gitRepositoryService = gitService,
        )

        composeRule.setContent {
            AgtTheme {
                HomeScreen(viewModel = homeVm, onOpenProject = {})
            }
        }

        composeRule.waitUntil(3_000) {
            composeRule.onAllNodesWithTag("home_crash_recovery_banner").fetchSemanticsNodes().isNotEmpty()
        }
        composeRule.onNodeWithTag("home_crash_recovery_banner").assertIsDisplayed()
        composeRule.onNodeWithTag("home_crash_dismiss_button")
            .assertIsDisplayed()
            .assertHeightIsAtLeast(48.dp)
            .performClick()

        composeRule.waitUntil(3_000) {
            composeRule.onAllNodesWithTag("home_crash_recovery_banner").fetchSemanticsNodes().isEmpty()
        }
        assertNull(AgtCrashHandler.readLastCrash(context))
    }

    @Test
    @Config(sdk = [36], qualifiers = "bn")
    fun `Bengali localization loads translated strings and renders SettingsListScreen with accessible targets`() {
        val bnTitle = context.getString(R.string.settings_title)
        assertEquals("সেটিংস", bnTitle)
        assertEquals("হোম", context.getString(R.string.nav_home))
        assertEquals("এক্সটেনশন", context.getString(R.string.nav_extensions))
        assertEquals("প্রজেক্টসমূহ", context.getString(R.string.home_title))
        assertTrue(context.getString(R.string.about_licenses_title).isNotBlank())
        assertTrue(context.getString(R.string.about_release_plan_title).isNotBlank())

        composeRule.setContent {
            AgtTheme {
                SettingsListScreen(onOpenCategory = {})
            }
        }

        composeRule.onNodeWithText("সেটিংস").assertIsDisplayed()
        composeRule.onNodeWithTag("settings_row_gateway")
            .assertIsDisplayed()
            .assertHeightIsAtLeast(56.dp)
        composeRule.onNodeWithText("গেটওয়ে").assertIsDisplayed()
    }

    private suspend fun installTestRuntime(server: HttpServer) {
        val baseUrl = "http://127.0.0.1:${server.address.port}"
        val tarGzBytes = buildSampleTarGz(
            listOf(
                "rootfs/bin/sh" to "#!/bin/sh\nexec /bin/sh \"\$@\"\n".toByteArray(),
            )
        )
        val sha = MessageDigest.getInstance("SHA-256").digest(tarGzBytes).joinToString("") { "%02x".format(it) }
        val deviceAbisJson = Build.SUPPORTED_ABIS.joinToString(",") { "\"$it\"" }

        server.createContext("/m10-manifest.json") { ex ->
            val body = """
                {
                  "runtime_id": "agt-ubuntu-m10",
                  "version": "1.0.0",
                  "release_channel": "stable",
                  "supported_abis": [$deviceAbisJson],
                  "min_android_sdk": 24,
                  "min_required_free_bytes": 1024,
                  "bundle": {
                    "download_url": "$baseUrl/m10-bundle.tar.gz",
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
        server.createContext("/m10-bundle.tar.gz") { ex ->
            ex.sendResponseHeaders(200, tarGzBytes.size.toLong())
            ex.responseBody.use { it.write(tarGzBytes) }
        }
        assertTrue(runtimeInstaller.installBlocking("$baseUrl/m10-manifest.json").isSuccess)
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
}
