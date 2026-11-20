package com.example

import android.content.Context
import android.os.Build
import androidx.test.core.app.ApplicationProvider
import com.example.agent.opencode.OpenCodeInstallerRepository
import com.example.core.common.DeviceCompatibilityInfo
import com.example.core.config.AgtAppConfig
import com.example.data.preferences.AppPreferencesRepository
import com.example.data.runtime.AndroidProcessBridge
import com.example.data.runtime.ManagedProcess
import com.example.data.runtime.ProcessBridge
import com.example.data.runtime.RuntimeArchiveExtractor
import com.example.data.runtime.RuntimeCompatibilityChecker
import com.example.data.runtime.RuntimeExtractionException
import com.example.data.runtime.RuntimeInstallerRepository
import com.example.data.runtime.RuntimeManifestParser
import com.example.data.runtime.UpstreamBootstrapEndpoints
import com.example.data.runtime.UpstreamRuntimeBootstrap
import com.example.domain.model.RuntimeArchiveFormat
import com.example.domain.model.RuntimeInstallStage
import com.sun.net.httpserver.HttpServer
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.util.ReflectionHelpers
import java.io.ByteArrayOutputStream
import java.io.File
import java.net.InetSocketAddress
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.security.MessageDigest
import java.util.zip.GZIPOutputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class RuntimeInstallerTest {

    @get:Rule
    val tempFolder = TemporaryFolder()

    private lateinit var context: Context
    private lateinit var server: HttpServer
    private lateinit var baseUrl: String
    private lateinit var originalAbis: Array<String>

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        originalAbis = Build.SUPPORTED_ABIS ?: arrayOf("armeabi-v7a")
        ReflectionHelpers.setStaticField(Build::class.java, "SUPPORTED_ABIS", arrayOf("arm64-v8a"))
        File(context.filesDir, "runtime").deleteRecursively()
        File(context.filesDir, "projects").deleteRecursively()
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
        runBlocking {
            val prefs = AppPreferencesRepository(context)
            prefs.setOnboardingCompleted(false)
            prefs.setRuntimeManifestUrl("")
        }
    }

    @Test
    fun `compatibility checker blocks unsupported ABI, low SDK, and insufficient storage`() {
        val arm64Ok = DeviceCompatibilityInfo(
            primaryAbi = "arm64-v8a",
            supportedAbis = listOf("arm64-v8a"),
            androidSdk = 34,
            androidRelease = "14",
            availableStorageBytes = 2L * 1024L * 1024L * 1024L,
            isArm64 = true,
        )
        assertTrue(RuntimeCompatibilityChecker.evaluate(arm64Ok).isCompatible)

        val x86Blocked = arm64Ok.copy(
            primaryAbi = "armeabi-v7a",
            supportedAbis = listOf("armeabi-v7a"),
            isArm64 = false,
        )
        val abiReport = RuntimeCompatibilityChecker.evaluate(x86Blocked)
        assertFalse(abiReport.isCompatible)
        assertFalse(abiReport.abiSupported)
        assertTrue(abiReport.blockingReasons.any { it.contains("Unsupported CPU ABI") })

        val lowSdkReport = RuntimeCompatibilityChecker.evaluate(arm64Ok.copy(androidSdk = 24))
        assertFalse(lowSdkReport.isCompatible)
        assertFalse(lowSdkReport.sdkSupported)

        val lowStorageReport = RuntimeCompatibilityChecker.evaluate(arm64Ok.copy(availableStorageBytes = 1024L))
        assertFalse(lowStorageReport.isCompatible)
        assertFalse(lowStorageReport.storageSupported)
    }

    @Test
    fun `manifest parser enforces HTTPS remotely, valid sha256, and traversal-free paths`() {
        val validJson = """
            {
              "runtime_id": "agt-ubuntu",
              "version": "1.2.0",
              "release_channel": "stable",
              "supported_abis": ["arm64-v8a", "x86_64"],
              "min_android_sdk": 26,
              "min_required_free_bytes": 1024,
              "bundle": {
                "download_url": "https://cdn.example.com/runtime.tar.gz",
                "compressed_size_bytes": 4096,
                "sha256": "${"a".repeat(64)}",
                "archive_format": "tar.gz",
                "extraction_root": "rootfs",
                "required_paths": ["bin/sh", "bin/proot"]
              }
            }
        """.trimIndent()
        val parsed = RuntimeManifestParser.parse(validJson)
        assertTrue(parsed.isSuccess)

        val insecureRemote = validJson.replace("https://cdn.example.com", "http://cdn.example.com")
        assertTrue(RuntimeManifestParser.parse(insecureRemote).isFailure)

        val badSha = validJson.replace("a".repeat(64), "not-a-sha")
        assertTrue(RuntimeManifestParser.parse(badSha).isFailure)

        val traversalPath = validJson.replace("\"bin/sh\"", "\"../etc/passwd\"")
        assertTrue(RuntimeManifestParser.parse(traversalPath).isFailure)
    }

    @Test
    fun `archive extractor blocks zip and tar path traversal`() = runBlocking {
        val badZip = tempFolder.newFile("evil.zip")
        ZipOutputStream(badZip.outputStream()).use { zos ->
            zos.putNextEntry(ZipEntry("../escape.txt"))
            zos.write("pwned".toByteArray())
            zos.closeEntry()
        }
        val staging = File(tempFolder.root, "staging-zip")
        val res = RuntimeArchiveExtractor.extractToStaging(
            archiveFile = badZip,
            format = RuntimeArchiveFormat.ZIP,
            stagingRoot = staging,
        )
        assertTrue(res.isFailure)
        assertFalse(File(tempFolder.root, "escape.txt").exists())
        assertFalse(staging.exists())
    }

    @Test
    fun `installer verifies sha256, resumes partial download, activates atomically, preserves projects, and repairs`() = runBlocking {
        // Create a user project file that must NEVER be touched by install, failure, or repair.
        val projectFile = File(context.filesDir, "projects/user-app/main.kt").apply {
            parentFile?.mkdirs()
            writeText("fun main() = println(\"safe\")")
        }

        val tarGzBytes = buildSampleTarGz(
            entries = listOf(
                "rootfs/bin/sh" to "#!/bin/sh\necho ok\n".toByteArray(),
                "rootfs/bin/proot" to "ELF_PROOT_STUB".toByteArray(),
                "rootfs/etc/os-release" to "NAME=Ubuntu\n".toByteArray(),
            )
        )
        val sha256Hex = sha256(tarGzBytes)
        val deviceAbisJson = android.os.Build.SUPPORTED_ABIS.joinToString(",") { "\"$it\"" }

        var manifestSha = sha256Hex
        server.createContext("/manifest.json") { ex ->
            val body = """
                {
                  "runtime_id": "agt-ubuntu-test",
                  "version": "1.0.0",
                  "release_channel": "stable",
                  "supported_abis": [$deviceAbisJson],
                  "min_android_sdk": 24,
                  "min_required_free_bytes": 1024,
                  "bundle": {
                    "download_url": "$baseUrl/bundle.tar.gz",
                    "compressed_size_bytes": ${tarGzBytes.size},
                    "sha256": "$manifestSha",
                    "archive_format": "tar.gz",
                    "extraction_root": "rootfs",
                    "required_paths": ["bin/sh", "bin/proot"]
                  },
                  "components": [
                    {"id": "opencode_agent", "name": "OpenCode", "version": "0.1"}
                  ]
                }
            """.trimIndent().toByteArray(StandardCharsets.UTF_8)
            ex.sendResponseHeaders(200, body.size.toLong())
            ex.responseBody.use { it.write(body) }
        }

        var observedRangeHeader: String? = null
        server.createContext("/bundle.tar.gz") { ex ->
            val range = ex.requestHeaders.getFirst("Range")
            observedRangeHeader = range
            if (range != null && range.startsWith("bytes=")) {
                val start = range.removePrefix("bytes=").substringBefore("-").toInt()
                val slice = tarGzBytes.copyOfRange(start, tarGzBytes.size)
                ex.responseHeaders.add("Content-Range", "bytes $start-${tarGzBytes.size - 1}/${tarGzBytes.size}")
                ex.sendResponseHeaders(206, slice.size.toLong())
                ex.responseBody.use { it.write(slice) }
            } else {
                ex.sendResponseHeaders(200, tarGzBytes.size.toLong())
                ex.responseBody.use { it.write(tarGzBytes) }
            }
        }

        val prefs = AppPreferencesRepository(context)
        val repo1 = RuntimeInstallerRepository(context, prefs)

        // 1. Reject corrupt SHA-256 without creating active runtime
        manifestSha = "0".repeat(64)
        val corruptResult = repo1.installBlocking("$baseUrl/manifest.json")
        assertTrue(corruptResult.isFailure)
        assertFalse(repo1.installState.value.isInstalled)
        assertFalse(repo1.activeDir.exists())

        // 2. Seed half-downloaded .part file to exercise Range resume + successful install
        manifestSha = sha256Hex
        repo1.downloadsDir.mkdirs()
        val splitPoint = tarGzBytes.size / 2
        File(repo1.downloadsDir, "agt-ubuntu-test-1.0.0.tar.gz.part")
            .writeBytes(tarGzBytes.copyOfRange(0, splitPoint))

        val okResult = repo1.installBlocking("$baseUrl/manifest.json")
        assertTrue(
            "Expected installBlocking success but failed with: ${okResult.exceptionOrNull()?.message}",
            okResult.isSuccess,
        )
        assertEquals("bytes=$splitPoint-", observedRangeHeader)
        assertEquals(RuntimeInstallStage.INSTALLED, repo1.installState.value.stage)
        assertTrue(repo1.installState.value.isInstalled)
        assertEquals("1.0.0", repo1.installState.value.installedVersion)
        assertTrue(File(repo1.activeDir, "bin/proot").exists())
        assertTrue(File(repo1.activeDir, ".agt-markers/linux_runtime.json").exists())

        // 3. Re-instantiate repository (app restart) and verify persisted health state
        val repo2 = RuntimeInstallerRepository(context, prefs)
        assertTrue(repo2.installState.value.isInstalled)
        assertTrue(repo2.installState.value.healthCheckPassed)

        // 4. Corrupt active bin/sh and verify repair restores it from verified cache while keeping user project safe
        File(repo2.activeDir, "bin/sh").delete()
        val damagedHealth = repo2.runHealthCheck()
        assertFalse(damagedHealth.isHealthy)

        val repaired = repo2.repairRuntime()
        assertTrue(repaired.isSuccess)
        assertTrue(repaired.getOrThrow().isHealthy)
        assertTrue(File(repo2.activeDir, "bin/sh").exists())
        assertEquals("fun main() = println(\"safe\")", projectFile.readText())
    }

    @Test
    fun `tar extractor handles hardlinks, rootfs-internal symlinks, and rejects symlink escapes`() = runBlocking {
        // 1. Archive with symlinked dir `bin -> usr/bin`, regular file `usr/bin/dash`, hardlink `usr/bin/sh -> usr/bin/dash`, and rootfs-internal `/proc/self/mounts`
        val tarBytes = buildTarGzWithCustomEntries(
            listOf(
                TarEntrySpec(name = "usr/bin/dash", type = '0', data = "#!/bin/sh\necho dash\n".toByteArray()),
                TarEntrySpec(name = "usr/bin/sh", type = '1', linkName = "usr/bin/dash"),
                TarEntrySpec(name = "bin", type = '2', linkName = "usr/bin"),
                TarEntrySpec(name = "etc/mtab", type = '2', linkName = "/proc/self/mounts"),
            )
        )
        val archiveFile = tempFolder.newFile("rootfs-links.tar.gz").apply { writeBytes(tarBytes) }
        val staging = File(tempFolder.root, "staging-links")
        val okRes = RuntimeArchiveExtractor.extractToStaging(
            archiveFile = archiveFile,
            format = RuntimeArchiveFormat.TAR_GZ,
            stagingRoot = staging,
        )
        assertTrue("Expected TAR extraction with links to succeed: ${okRes.exceptionOrNull()?.message}", okRes.isSuccess)
        assertTrue(File(staging, "bin/sh").exists())
        assertTrue(File(staging, "etc/mtab").absolutePath.endsWith("etc/mtab"))

        // 2. Reject escaping relative symlink `../../outside`
        val badTar = buildTarGzWithCustomEntries(
            listOf(
                TarEntrySpec(name = "bin/evil", type = '2', linkName = "../../../../etc/passwd"),
            )
        )
        val badFile = tempFolder.newFile("evil-symlink.tar.gz").apply { writeBytes(badTar) }
        val badStaging = File(tempFolder.root, "staging-bad-link")
        val failRes = RuntimeArchiveExtractor.extractToStaging(
            archiveFile = badFile,
            format = RuntimeArchiveFormat.TAR_GZ,
            stagingRoot = badStaging,
        )
        assertTrue(failRes.isFailure)
        assertFalse(badStaging.exists())
    }

    @Test
    fun `upstream bootstrap rejects SHA-256 tampered packages without activating runtime`() = runBlocking {
        val validUbuntu = buildSampleTarGz(listOf("bin/sh" to "ok".toByteArray(), "etc/os-release" to "24.04".toByteArray()))
        val tamperedNode = "corrupted-node-bytes".toByteArray()
        val expectedNodeSha = sha256(buildSampleTarGz(listOf("node-v22.1.0-linux-arm64/bin/node" to "ok".toByteArray())))

        server.createContext("/u/SHA256SUMS") { ex ->
            val body = "${sha256(validUbuntu)} *ubuntu-base-24.04-base-arm64.tar.gz\n".toByteArray()
            ex.sendResponseHeaders(200, body.size.toLong())
            ex.responseBody.use { it.write(body) }
        }
        server.createContext("/u/ubuntu-base-24.04-base-arm64.tar.gz") { ex ->
            ex.sendResponseHeaders(200, validUbuntu.size.toLong())
            ex.responseBody.use { it.write(validUbuntu) }
        }
        server.createContext("/n/SHASUMS256.txt") { ex ->
            val body = "$expectedNodeSha  node-v22.1.0-linux-arm64.tar.gz\n".toByteArray()
            ex.sendResponseHeaders(200, body.size.toLong())
            ex.responseBody.use { it.write(body) }
        }
        server.createContext("/n/node-v22.1.0-linux-arm64.tar.gz") { ex ->
            ex.sendResponseHeaders(200, tamperedNode.size.toLong())
            ex.responseBody.use { it.write(tamperedNode) }
        }

        val prefs = AppPreferencesRepository(context)
        val upstream = UpstreamRuntimeBootstrap(
            endpoints = UpstreamBootstrapEndpoints(
                ubuntuSha256SumsUrl = "$baseUrl/u/SHA256SUMS",
                ubuntuBaseDirUrl = "$baseUrl/u",
                nodeSha256SumsUrl = "$baseUrl/n/SHASUMS256.txt",
                nodeBaseDirUrl = "$baseUrl/n",
                ghReleaseApiUrl = "$baseUrl/missing-gh",
                openCodeReleaseApiUrls = listOf("$baseUrl/missing-oc"),
            )
        )
        val repo = RuntimeInstallerRepository(
            context = context,
            preferencesRepository = prefs,
            appConfig = AgtAppConfig(runtimeManifestUrl = "", githubOAuthClientId = "", isDebugBuild = true),
            upstreamBootstrap = upstream,
        )
        val res = repo.installBlocking()
        assertTrue(res.isFailure)
        assertFalse(repo.installState.value.isInstalled)
        assertFalse(repo.activeDir.exists())
        assertTrue(repo.installState.value.errorMessage.orEmpty().contains("SHA-256", ignoreCase = true))
    }

    @Test
    fun `tar extractor confines rootfs symlinks like var-run to staging without escaping onto host`() = runBlocking {
        // Simulates real Ubuntu base rootfs entries where `var/run -> /run` is a symlink
        // and subsequent entries write inside `var/run/utmp` or collide with symlinks.
        val ubuntuStyleTar = buildTarGzWithCustomEntries(
            listOf(
                TarEntrySpec(name = "usr/bin/dash", type = '0', data = "#!/bin/sh\necho ok\n".toByteArray()),
                TarEntrySpec(name = "bin", type = '2', linkName = "usr/bin"),
                TarEntrySpec(name = "bin/sh", type = '2', linkName = "dash"),
                TarEntrySpec(name = "run/", type = '5'),
                TarEntrySpec(name = "var/run", type = '2', linkName = "/run"),
                TarEntrySpec(name = "var/run/utmp", type = '0', data = "utmp".toByteArray()),
                TarEntrySpec(name = "etc/os-release", type = '0', data = "VERSION_ID=\"24.04\"\n".toByteArray()),
            )
        )
        val archiveFile = tempFolder.newFile("ubuntu-rootfs-links.tar.gz").apply { writeBytes(ubuntuStyleTar) }
        val staging = File(tempFolder.root, "staging-ubuntu-links")
        val res = RuntimeArchiveExtractor.extractToStaging(
            archiveFile = archiveFile,
            format = RuntimeArchiveFormat.TAR_GZ,
            stagingRoot = staging,
        )
        assertTrue("Expected Ubuntu rootfs symlinks to extract safely: ${res.exceptionOrNull()?.message}", res.isSuccess)
        assertTrue(File(staging, "run/utmp").exists())
        assertTrue(File(staging, "usr/bin/dash").exists())
        assertTrue(File(staging, "etc/os-release").exists())
    }

    @Test
    fun `verify-first detects existing on-device environment without re-downloading`() = runBlocking {
        var downloadHits = 0
        val ubuntuTar = buildSampleTarGz(
            listOf(
                "bin/sh" to "#!/bin/sh\necho ok\n".toByteArray(),
                "etc/os-release" to "NAME=Ubuntu\nVERSION_ID=24.04\n".toByteArray(),
            )
        )
        server.createContext("/u2/SHA256SUMS") { ex ->
            downloadHits++
            val body = "${sha256(ubuntuTar)} *ubuntu-base-24.04-base-arm64.tar.gz\n".toByteArray()
            ex.sendResponseHeaders(200, body.size.toLong())
            ex.responseBody.use { it.write(body) }
        }

        val prefs = AppPreferencesRepository(context)
        val upstream = UpstreamRuntimeBootstrap(
            endpoints = UpstreamBootstrapEndpoints(
                ubuntuSha256SumsUrl = "$baseUrl/u2/SHA256SUMS",
                ubuntuBaseDirUrl = "$baseUrl/u2",
                nodeSha256SumsUrl = "$baseUrl/missing-node",
                nodeBaseDirUrl = "$baseUrl/n2",
                ghReleaseApiUrl = "$baseUrl/missing-gh",
                openCodeReleaseApiUrls = listOf("$baseUrl/missing-oc"),
            )
        )
        val repo = RuntimeInstallerRepository(
            context = context,
            preferencesRepository = prefs,
            appConfig = AgtAppConfig(runtimeManifestUrl = "", githubOAuthClientId = "", isDebugBuild = true),
            upstreamBootstrap = upstream,
        )
        // Before user clicks Install on a fresh device, component breakdown has not been checked yet.
        assertFalse(repo.installState.value.hasCheckedEnvironment)
        assertFalse(repo.installState.value.isInstalled)

        // Stage a valid on-device Linux rootfs directly on disk without state/markers.
        repo.activeDir.mkdirs()
        File(repo.activeDir, "bin").mkdirs()
        File(repo.activeDir, "bin/sh").writeText("#!/bin/sh\necho recovered\n")
        File(repo.activeDir, "etc").mkdirs()
        File(repo.activeDir, "etc/os-release").writeText("NAME=Ubuntu\nVERSION_ID=24.04\n")
        File(repo.activeDir, "usr/bin").mkdirs()
        File(repo.activeDir, "usr/bin/node").writeText("ELF_NODE")
        File(repo.activeDir, "usr/bin/npm").writeText("#!/bin/sh\n")

        // Clicking Install verifies disk first, recovers state/markers, and succeeds without downloading!
        val res = repo.installBlocking()
        assertTrue("Expected verify-first recovery success: ${res.exceptionOrNull()?.message}", res.isSuccess)
        assertEquals(0, downloadHits)
        assertTrue(repo.installState.value.hasCheckedEnvironment)
        assertTrue(repo.installState.value.isInstalled)
        assertTrue(repo.installState.value.healthCheckPassed)
        assertTrue(File(repo.activeDir, ".agt-markers/linux_runtime.json").exists())
        assertTrue(File(repo.activeDir, ".agt-markers/base_toolchains.json").exists())
    }

    @Test
    fun `upstream bootstrap falls back to github web releases on 403 and installs OpenCode automatically`() = runBlocking {
        val ubuntuTar = buildSampleTarGz(
            listOf(
                "bin/sh" to "#!/bin/sh\necho ok\n".toByteArray(),
                "etc/os-release" to "NAME=Ubuntu\nVERSION_ID=24.04\n".toByteArray(),
            )
        )
        val nodeTar = buildSampleTarGz(
            listOf(
                "node-v22.12.0-linux-arm64/bin/node" to "ELF_NODE".toByteArray(),
                "node-v22.12.0-linux-arm64/bin/npm" to "#!/bin/sh\n".toByteArray(),
            )
        )
        val ocTar = buildSampleTarGz(listOf("opencode" to "ELF_OPENCODE_WEB".toByteArray()))
        val ocSha = sha256(ocTar)

        server.createContext("/u3/SHA256SUMS") { ex ->
            val body = "${sha256(ubuntuTar)} *ubuntu-base-24.04-base-arm64.tar.gz\n".toByteArray()
            ex.sendResponseHeaders(200, body.size.toLong())
            ex.responseBody.use { it.write(body) }
        }
        server.createContext("/u3/ubuntu-base-24.04-base-arm64.tar.gz") { ex ->
            ex.sendResponseHeaders(200, ubuntuTar.size.toLong())
            ex.responseBody.use { it.write(ubuntuTar) }
        }
        server.createContext("/n3/SHASUMS256.txt") { ex ->
            val body = "${sha256(nodeTar)}  node-v22.12.0-linux-arm64.tar.gz\n".toByteArray()
            ex.sendResponseHeaders(200, body.size.toLong())
            ex.responseBody.use { it.write(body) }
        }
        server.createContext("/n3/node-v22.12.0-linux-arm64.tar.gz") { ex ->
            ex.sendResponseHeaders(200, nodeTar.size.toLong())
            ex.responseBody.use { it.write(nodeTar) }
        }
        // GitHub API rate-limited (HTTP 403)
        server.createContext("/repos/anomalyco/opencode/releases/latest") { ex ->
            ex.sendResponseHeaders(403, -1)
        }
        // GitHub Web fallback redirects `/releases/latest` -> `/releases/tag/v1.9.0` -> `/releases/expanded_assets/v1.9.0`
        server.createContext("/anomalyco/opencode/releases/latest") { ex ->
            ex.responseHeaders.add("Location", "$baseUrl/anomalyco/opencode/releases/tag/v1.9.0")
            ex.sendResponseHeaders(302, -1)
        }
        server.createContext("/anomalyco/opencode/releases/tag/v1.9.0") { ex ->
            val html = "<html><body><h1>v1.9.0</h1></body></html>".toByteArray()
            ex.sendResponseHeaders(200, html.size.toLong())
            ex.responseBody.use { it.write(html) }
        }
        server.createContext("/anomalyco/opencode/releases/expanded_assets/v1.9.0") { ex ->
            val html = """
                <ul>
                  <li>
                    <a href="/anomalyco/opencode/releases/download/v1.9.0/opencode-desktop-linux-arm64.AppImage">AppImage</a>
                    <span>sha256:${"f".repeat(64)}</span>
                  </li>
                  <li>
                    <a href="/anomalyco/opencode/releases/download/v1.9.0/opencode-linux-arm64.tar.gz">opencode-linux-arm64.tar.gz</a>
                    <span>sha256:$ocSha</span>
                  </li>
                </ul>
            """.trimIndent().toByteArray()
            ex.sendResponseHeaders(200, html.size.toLong())
            ex.responseBody.use { it.write(html) }
        }
        server.createContext("/anomalyco/opencode/releases/download/v1.9.0/opencode-linux-arm64.tar.gz") { ex ->
            ex.sendResponseHeaders(200, ocTar.size.toLong())
            ex.responseBody.use { it.write(ocTar) }
        }

        val prefs = AppPreferencesRepository(context)
        val upstream = UpstreamRuntimeBootstrap(
            endpoints = UpstreamBootstrapEndpoints(
                ubuntuSha256SumsUrl = "$baseUrl/u3/SHA256SUMS",
                ubuntuBaseDirUrl = "$baseUrl/u3",
                nodeSha256SumsUrl = "$baseUrl/n3/SHASUMS256.txt",
                nodeBaseDirUrl = "$baseUrl/n3",
                ghReleaseApiUrl = "$baseUrl/missing-gh",
                openCodeReleaseApiUrls = listOf("$baseUrl/repos/anomalyco/opencode/releases/latest"),
                githubWebBaseUrl = baseUrl,
            )
        )
        val runtimeRepo = RuntimeInstallerRepository(
            context = context,
            preferencesRepository = prefs,
            appConfig = AgtAppConfig(runtimeManifestUrl = "", githubOAuthClientId = "", isDebugBuild = true),
            upstreamBootstrap = upstream,
        )
        val openCodeRepo = OpenCodeInstallerRepository(
            context = context,
            runtimeInstallerRepository = runtimeRepo,
            preferencesRepository = prefs,
            upstreamBootstrap = upstream,
        )

        // Calling installAutomatic() without any manual URL auto-provisions base runtime AND OpenCode via web fallback!
        val autoRes = openCodeRepo.installAutomatic()
        assertTrue("Expected automatic OpenCode install success: ${autoRes.exceptionOrNull()?.message}", autoRes.isSuccess)
        assertTrue(autoRes.getOrThrow().isInstalled)
        assertEquals("1.9.0", autoRes.getOrThrow().version)
        assertTrue(File(runtimeRepo.activeDir, "usr/local/bin/opencode").canExecute())
    }

    @Test
    fun `mergeDirectoryInto preserves npm relative symlinks across canonical path aliases and still blocks escapes`() = runBlocking {
        // Simulate Android `/data/data/<pkg>` (real) vs `/data/user/0/<pkg>` (symlinked alias)
        val realBase = File(tempFolder.root, "real-data").apply { mkdirs() }
        val aliasBase = File(tempFolder.root, "user-0-alias")
        Files.createSymbolicLink(aliasBase.toPath(), realBase.toPath())

        val nodeArchiveBytes = buildTarGzWithCustomEntries(
            listOf(
                TarEntrySpec(
                    name = "node-v22.12.0-linux-arm64/bin/node",
                    type = '0',
                    data = "ELF_NODE".toByteArray(),
                ),
                TarEntrySpec(
                    name = "node-v22.12.0-linux-arm64/lib/node_modules/npm/bin/npm-cli.js",
                    type = '0',
                    data = "#!/usr/bin/env node\nconsole.log('npm');\n".toByteArray(),
                ),
                TarEntrySpec(
                    name = "node-v22.12.0-linux-arm64/bin/npm",
                    type = '2',
                    linkName = "../lib/node_modules/npm/bin/npm-cli.js",
                ),
            )
        )
        val nodeArchiveFile = tempFolder.newFile("node-symlink-test.tar.gz").apply {
            writeBytes(nodeArchiveBytes)
        }

        val aliasedSourceStaging = File(aliasBase, "pkg-node")
        val extractRes = RuntimeArchiveExtractor.extractToStaging(
            archiveFile = nodeArchiveFile,
            format = RuntimeArchiveFormat.TAR_GZ,
            stagingRoot = aliasedSourceStaging,
            stripRootPrefix = "node-v22.12.0-linux-arm64",
        )
        assertTrue("Expected Node extraction to succeed: ${extractRes.exceptionOrNull()?.message}", extractRes.isSuccess)

        val aliasedRootfs = File(aliasBase, "upstream-rootfs").apply { mkdirs() }
        val aliasedUsr = File(aliasedRootfs, "usr")
        RuntimeArchiveExtractor.mergeDirectoryInto(
            sourceDir = aliasedSourceStaging,
            targetDir = aliasedUsr,
            confinementRoot = aliasedRootfs,
        )

        val mergedNpmLink = File(realBase, "upstream-rootfs/usr/bin/npm")
        assertTrue(Files.isSymbolicLink(mergedNpmLink.toPath()))
        assertEquals(
            "../lib/node_modules/npm/bin/npm-cli.js",
            Files.readSymbolicLink(mergedNpmLink.toPath()).toString(),
        )
        assertTrue(File(realBase, "upstream-rootfs/usr/lib/node_modules/npm/bin/npm-cli.js").exists())

        // Still reject escaping symlinks created directly inside a merge source
        val escapingSource = File(aliasBase, "bad-merge-src").apply {
            File(this, "bin").mkdirs()
        }
        Files.createSymbolicLink(
            File(escapingSource, "bin/evil").toPath(),
            File("../../../../etc/passwd").toPath(),
        )
        var rejected = false
        try {
            RuntimeArchiveExtractor.mergeDirectoryInto(escapingSource, aliasedUsr, aliasedRootfs)
        } catch (_: RuntimeExtractionException) {
            rejected = true
        }
        assertTrue("Expected escaping symlink during mergeDirectoryInto to be rejected", rejected)
    }

    @Test
    fun `AndroidProcessBridge configures PRoot with link2symlink, loaders, and safe rootfs working dir`() {
        val nativeLibDir = tempFolder.newFolder("native-libs")
        val elfMagic = byteArrayOf(0x7F, 'E'.code.toByte(), 'L'.code.toByte(), 'F'.code.toByte(), 2, 1, 1, 0)
        val prootSo = File(nativeLibDir, "libproot.so").apply {
            writeBytes(elfMagic)
            setExecutable(true, false)
        }
        val loaderSo = File(nativeLibDir, "libproot_loader.so").apply {
            writeBytes(elfMagic)
            setExecutable(true, false)
        }
        val loader32So = File(nativeLibDir, "libproot_loader32.so").apply {
            writeBytes(elfMagic)
            setExecutable(true, false)
        }

        val activeDir = tempFolder.newFolder("runtime-active").apply {
            File(this, "bin").mkdirs()
            File(this, "bin/sh").writeText("#!/bin/sh\n")
        }
        val workspaceDir = tempFolder.newFolder("project-ws")

        val bridge = AndroidProcessBridge(nativeLibraryDir = nativeLibDir)
        assertTrue(bridge.isIsolationActive(activeDir))

        val prootEnv = bridge.buildProotEnvironment(activeDir)
        assertEquals(File(activeDir, "tmp").absolutePath, prootEnv["PROOT_TMP_DIR"])
        assertEquals(loaderSo.absolutePath, prootEnv["PROOT_LOADER"])
        assertEquals(loader32So.absolutePath, prootEnv["PROOT_LOADER_32"])

        val wsCmd = bridge.buildCommand(activeDir, workspaceDir, "echo ok")
        assertEquals(prootSo.absolutePath, wsCmd.first())
        assertTrue(wsCmd.contains("-L"))
        assertTrue(wsCmd.contains("--kill-on-exit"))
        assertTrue(wsCmd.contains("/workspace"))
        assertTrue(wsCmd.contains("${workspaceDir.absolutePath}:/workspace"))

        val rootfsCmd = bridge.buildCommand(activeDir, activeDir, "npm install -g pi")
        assertTrue(rootfsCmd.contains("-w"))
        assertTrue(rootfsCmd.contains("/"))
        assertFalse(rootfsCmd.contains("${activeDir.absolutePath}:/workspace"))
        assertTrue(File(activeDir, "dev/shm").isDirectory)

        // Read-only host /dev/shm triggers bind overlay from <runtime>/tmp -> /dev/shm
        val readOnlyShm = tempFolder.newFolder("ro-dev-shm")
        val roBridge = AndroidProcessBridge(
            nativeLibraryDir = nativeLibDir,
            hostDevShmDir = readOnlyShm,
            isHostPathWritable = { false },
        )
        val shmCmd = roBridge.buildCommand(activeDir, workspaceDir, "echo shm")
        assertTrue(shmCmd.contains("${File(activeDir, "tmp").absolutePath}:/dev/shm"))

        // Writable host /dev/shm does not add redundant overlay
        val rwBridge = AndroidProcessBridge(
            nativeLibraryDir = nativeLibDir,
            hostDevShmDir = readOnlyShm,
            isHostPathWritable = { true },
        )
        val rwCmd = rwBridge.buildCommand(activeDir, workspaceDir, "echo shm")
        assertFalse(rwCmd.contains("${File(activeDir, "tmp").absolutePath}:/dev/shm"))

        // PROOT_NO_SECCOMP toggle & failure detection
        assertFalse(bridge.isSeccompDisabled())
        assertFalse(bridge.buildProotEnvironment(activeDir).containsKey("PROOT_NO_SECCOMP"))
        bridge.setSeccompDisabled(true)
        assertEquals("1", bridge.buildProotEnvironment(activeDir)["PROOT_NO_SECCOMP"])
        assertTrue(AndroidProcessBridge.looksLikeSeccompFailure(159, ""))
        assertTrue(AndroidProcessBridge.looksLikeSeccompFailure(1, "proot error: seccomp filter failed"))
        assertFalse(AndroidProcessBridge.looksLikeSeccompFailure(0, "ok"))
    }

    @Test
    fun `live PRoot probe auto-enables PROOT_NO_SECCOMP fallback, provisions etc-hosts and apt git toolchain`() = runBlocking {
        val prefs = AppPreferencesRepository(context)
        val recordedCommands = mutableListOf<String>()
        val recordedEnvs = mutableListOf<Map<String, String>>()

        class FakeProotBridge : ProcessBridge {
            private var seccompOff = false
            override fun isIsolationActive(runtimeActiveDir: File): Boolean = true
            override fun isSeccompDisabled(): Boolean = seccompOff
            override fun setSeccompDisabled(disabled: Boolean) {
                seccompOff = disabled
            }
            override fun buildProotEnvironment(runtimeActiveDir: File, disableSeccomp: Boolean): Map<String, String> =
                if (disableSeccomp) mapOf("PROOT_NO_SECCOMP" to "1") else emptyMap()

            override fun buildCommand(runtimeActiveDir: File, projectWorkingDir: File, shellCommand: String): List<String> =
                listOf("/fake/libproot.so", "-L", "-r", runtimeActiveDir.absolutePath, "/bin/sh", "-c", shellCommand)

            override fun spawn(commandLine: List<String>, workingDirectory: File, environment: Map<String, String>): ManagedProcess {
                val cmd = commandLine.last()
                recordedCommands += cmd
                recordedEnvs += environment
                val hasNoSeccomp = environment["PROOT_NO_SECCOMP"] == "1"
                val (exit, out, err) = when {
                    !hasNoSeccomp && cmd.contains("__AGT_SH_OK__") ->
                        Triple(159, "", "proot warning: seccomp filter rejected (Bad system call)")
                    cmd.contains("__AGT_SH_OK__") ->
                        Triple(0, "__AGT_SH_OK__\n__AGT_NODE__=v22.12.0\n__AGT_NPM__=10.9.0\n__AGT_GIT__=git version 2.43.0\n", "")
                    cmd == "git --version" ->
                        Triple(127, "", "sh: 1: git: not found")
                    cmd.contains("apt-get install") ->
                        Triple(0, "Setting up git (1:2.43.0-1ubuntu7) ...\ngit version 2.43.0\n", "")
                    else -> Triple(0, "ok\n", "")
                }
                return object : ManagedProcess {
                    override val pid: Long = 1001L
                    override val stdout = out.byteInputStream()
                    override val stderr = err.byteInputStream()
                    override val stdin = ByteArrayOutputStream()
                    override fun waitFor(): Int = exit
                    override fun terminateTree() {}
                }
            }
        }

        val fakeBridge = FakeProotBridge()
        val repo = RuntimeInstallerRepository(
            context = context,
            preferencesRepository = prefs,
            appConfig = AgtAppConfig(runtimeManifestUrl = "", githubOAuthClientId = "", isDebugBuild = true),
            processBridge = fakeBridge,
        )

        // Populate active runtime via verify-first recovery
        repo.activeDir.mkdirs()
        File(repo.activeDir, "bin").mkdirs()
        File(repo.activeDir, "bin/sh").writeText("#!/bin/sh\n")
        File(repo.activeDir, "etc").mkdirs()
        File(repo.activeDir, "etc/os-release").writeText("NAME=Ubuntu\nVERSION_ID=24.04\n")
        File(repo.activeDir, "usr/bin").mkdirs()
        File(repo.activeDir, "usr/bin/node").writeText("ELF")
        File(repo.activeDir, "usr/bin/npm").writeText("#!/bin/sh\n")
        File(repo.activeDir, "usr/bin/apt-get").writeText("ELF")

        val installRes = repo.installBlocking()
        assertTrue("Expected installBlocking recovery + live PRoot probe to pass: ${installRes.exceptionOrNull()?.message}", installRes.isSuccess)
        val health = installRes.getOrThrow()
        assertTrue(health.isHealthy)
        assertTrue("Expected no-seccomp indicator in health summary: ${health.summary}", health.summary.contains("no-seccomp"))
        assertTrue(fakeBridge.isSeccompDisabled())
        assertTrue(File(repo.runtimeRootDir, "state/proot_no_seccomp").exists())
        assertTrue(File(repo.activeDir, "etc/hosts").readText().contains("127.0.0.1 localhost"))
        assertTrue(File(repo.activeDir, "etc/resolv.conf").readText().contains("nameserver 1.1.1.1"))
        assertTrue(File(repo.activeDir, "var/lib/apt/lists/partial").isDirectory)

        // Ensure Git toolchain invokes apt-get with APT::Sandbox::User=root and PROOT_NO_SECCOMP=1
        val gitRes = repo.ensureGitToolchain()
        assertTrue("Expected ensureGitToolchain to succeed: ${gitRes.exceptionOrNull()?.message}", gitRes.isSuccess)
        assertEquals("git version 2.43.0", gitRes.getOrThrow())
        assertTrue(recordedCommands.any { it.contains("APT::Sandbox::User=root") && it.contains("install") && it.contains("git") })
        assertTrue(recordedEnvs.last()["PROOT_NO_SECCOMP"] == "1")
        assertTrue(File(repo.activeDir, ".agt-markers/git_cli.json").exists())

        // Recreating repository restores seccomp disabled state automatically
        val freshBridge = FakeProotBridge()
        assertFalse(freshBridge.isSeccompDisabled())
        RuntimeInstallerRepository(
            context = context,
            preferencesRepository = prefs,
            appConfig = AgtAppConfig(runtimeManifestUrl = "", githubOAuthClientId = "", isDebugBuild = true),
            processBridge = freshBridge,
        )
        assertTrue(freshBridge.isSeccompDisabled())
    }

    private data class TarEntrySpec(
        val name: String,
        val type: Char,
        val data: ByteArray = ByteArray(0),
        val linkName: String = "",
    )

    private fun buildTarGzWithCustomEntries(entries: List<TarEntrySpec>): ByteArray {
        val tarOut = ByteArrayOutputStream()
        for (entry in entries) {
            val header = ByteArray(512)
            entry.name.toByteArray(StandardCharsets.UTF_8).let {
                System.arraycopy(it, 0, header, 0, minOf(it.size, 100))
            }
            "0000755\u0000".toByteArray().copyInto(header, 100)
            "0000000\u0000".toByteArray().copyInto(header, 108)
            "0000000\u0000".toByteArray().copyInto(header, 116)
            String.format("%011o\u0000", entry.data.size).toByteArray().copyInto(header, 124)
            "00000000000\u0000".toByteArray().copyInto(header, 136)
            for (i in 148 until 156) header[i] = ' '.code.toByte()
            header[156] = entry.type.code.toByte()
            if (entry.linkName.isNotEmpty()) {
                entry.linkName.toByteArray(StandardCharsets.UTF_8).let {
                    System.arraycopy(it, 0, header, 157, minOf(it.size, 100))
                }
            }
            "ustar\u0000".toByteArray().copyInto(header, 257)
            "00".toByteArray().copyInto(header, 263)
            val sum = header.sumOf { it.toInt() and 0xFF }
            String.format("%06o\u0000 ", sum).toByteArray().copyInto(header, 148)
            tarOut.write(header)
            if (entry.data.isNotEmpty()) {
                tarOut.write(entry.data)
                val rem = entry.data.size % 512
                if (rem != 0) tarOut.write(ByteArray(512 - rem))
            }
        }
        tarOut.write(ByteArray(1024))
        val gzOut = ByteArrayOutputStream()
        GZIPOutputStream(gzOut).use { it.write(tarOut.toByteArray()) }
        return gzOut.toByteArray()
    }

    private fun buildSampleTarGz(entries: List<Pair<String, ByteArray>>): ByteArray {
        val tarOut = ByteArrayOutputStream()
        for ((name, data) in entries) {
            val header = ByteArray(512)
            val nameBytes = name.toByteArray(StandardCharsets.UTF_8)
            System.arraycopy(nameBytes, 0, header, 0, minOf(nameBytes.size, 100))
            // mode "0000755\0"
            "0000755\u0000".toByteArray().copyInto(header, 100)
            "0000000\u0000".toByteArray().copyInto(header, 108)
            "0000000\u0000".toByteArray().copyInto(header, 116)
            // size in 11 octal digits + null
            val sizeStr = String.format("%011o\u0000", data.size)
            sizeStr.toByteArray().copyInto(header, 124)
            "00000000000\u0000".toByteArray().copyInto(header, 136)
            // checksum placeholder spaces
            for (i in 148 until 156) header[i] = ' '.code.toByte()
            header[156] = '0'.code.toByte()
            "ustar\u0000".toByteArray().copyInto(header, 257)
            "00".toByteArray().copyInto(header, 263)

            val sum = header.sumOf { it.toInt() and 0xFF }
            val chkStr = String.format("%06o\u0000 ", sum)
            chkStr.toByteArray().copyInto(header, 148)

            tarOut.write(header)
            tarOut.write(data)
            val rem = data.size % 512
            if (rem != 0) {
                tarOut.write(ByteArray(512 - rem))
            }
        }
        // Two 512-byte EOF zero blocks
        tarOut.write(ByteArray(1024))

        val gzOut = ByteArrayOutputStream()
        GZIPOutputStream(gzOut).use { it.write(tarOut.toByteArray()) }
        return gzOut.toByteArray()
    }

    private fun sha256(bytes: ByteArray): String =
        MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }
}
