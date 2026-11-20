package com.example

import android.content.Context
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextInput
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.example.core.security.ExtensionCredentialVault
import com.example.core.security.TokenCipher
import com.example.data.database.AgtDatabase
import com.example.data.extensions.ExtensionCatalog
import com.example.data.extensions.ExtensionPackageValidator
import com.example.data.extensions.ExtensionRegistryRepository
import com.example.data.extensions.McpHttpClient
import com.example.domain.model.ExtensionAgentTarget
import com.example.domain.model.ExtensionInstallState
import com.example.domain.model.ExtensionPackageFormat
import com.example.feature.extensions.ExtensionsScreen
import com.example.feature.extensions.ExtensionsViewModel
import com.example.ui.theme.AgtTheme
import com.sun.net.httpserver.HttpServer
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
import java.io.ByteArrayOutputStream
import java.io.File
import java.net.InetSocketAddress
import java.nio.charset.StandardCharsets
import java.util.zip.GZIPOutputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class UniversalExtensionHubTest {

    @get:Rule
    val composeRule = createComposeRule()

    private lateinit var context: Context
    private lateinit var db: AgtDatabase
    private lateinit var vault: ExtensionCredentialVault
    private lateinit var registry: ExtensionRegistryRepository
    private lateinit var server: HttpServer
    private lateinit var baseUrl: String

    private val testCipher = object : TokenCipher {
        override fun encrypt(plaintext: String): ByteArray =
            plaintext.toByteArray(StandardCharsets.UTF_8).map { (it.toInt() xor 0x33).toByte() }.toByteArray()

        override fun decrypt(payload: ByteArray): String =
            String(payload.map { (it.toInt() xor 0x33).toByte() }.toByteArray(), StandardCharsets.UTF_8)
    }

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        context.deleteDatabase("test_phase61_ext.db")
        context.getSharedPreferences("agt_extension_vault", Context.MODE_PRIVATE).edit().clear().commit()
        File(context.filesDir, "extensions").deleteRecursively()

        db = Room.databaseBuilder(context, AgtDatabase::class.java, "test_phase61_ext.db")
            .allowMainThreadQueries()
            .build()
        vault = ExtensionCredentialVault(context, testCipher)
        registry = ExtensionRegistryRepository(
            context = context,
            extensionDao = db.extensionDao(),
            credentialVault = vault,
            mcpHttpClient = McpHttpClient(),
        )
        server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        server.start()
        baseUrl = "http://127.0.0.1:${server.address.port}"
    }

    @After
    fun tearDown() {
        server.stop(0)
        db.close()
        context.deleteDatabase("test_phase61_ext.db")
        context.getSharedPreferences("agt_extension_vault", Context.MODE_PRIVATE).edit().clear().commit()
        File(context.filesDir, "extensions").deleteRecursively()
    }

    @Test
    fun `github and owner repo sources resolve accurately to archive and raw urls`() {
        val treeResolved = ExtensionPackageValidator.resolveRemoteSource(
            "https://github.com/badlogic/pi-skills/tree/main/brave-search"
        )
        assertNotNull(treeResolved)
        assertEquals(
            listOf("https://github.com/badlogic/pi-skills/archive/refs/heads/main.zip"),
            treeResolved!!.candidateUrls,
        )
        assertEquals("brave-search", treeResolved.archiveSubpath)
        assertEquals(ExtensionAgentTarget.PI, treeResolved.agentTargetHint)

        val blobResolved = ExtensionPackageValidator.resolveRemoteSource(
            "https://github.com/obra/superpowers/blob/main/skills/systematic-debugging/SKILL.md"
        )
        assertNotNull(blobResolved)
        assertEquals(
            listOf("https://raw.githubusercontent.com/obra/superpowers/main/skills/systematic-debugging/SKILL.md"),
            blobResolved!!.candidateUrls,
        )
        assertEquals(ExtensionPackageFormat.RAW_SKILL_MD, blobResolved.formatHint)
        assertEquals(ExtensionAgentTarget.OPENCODE, blobResolved.agentTargetHint)

        val shortResolved = ExtensionPackageValidator.resolveRemoteSource("badlogic/pi-skills/browser-tools")
        assertNotNull(shortResolved)
        assertEquals("browser-tools", shortResolved!!.archiveSubpath)
        assertEquals(ExtensionAgentTarget.PI, shortResolved.agentTargetHint)
    }

    @Test
    fun `zip skill archive downloads verifies sha256 extracts docs safely omits scripts and blocks forbidden binaries`() = runBlocking {
        val zipBytes = buildZipArchive(
            mapOf(
                "repo-main/SKILL.md" to "---\nid: pi.test.search\nname: Web Search Helper\n---\n# Web Search Helper\nAlways verify sources.\n",
                "repo-main/references/api.md" to "## Reference\nUse structured citations.",
                "repo-main/run.sh" to "#!/bin/sh\necho hi\n",
            )
        )
        val zipSha = ExtensionPackageValidator.sha256Hex(zipBytes)
        server.createContext("/skill.zip") { ex ->
            ex.responseHeaders.add("Content-Type", "application/zip")
            ex.sendResponseHeaders(200, zipBytes.size.toLong())
            ex.responseBody.use { it.write(zipBytes) }
        }

        val installed = registry.importSkillPackage(
            rawSourceOrContent = "$baseUrl/skill.zip",
            expectedSha256 = zipSha,
        ).getOrThrow()

        assertEquals("pi.test.search", installed.id)
        assertEquals(ExtensionInstallState.INSTALLED_ENABLED, installed.installState)
        assertEquals(zipSha, installed.sha256)

        val destDir = File(context.filesDir, "extensions/pi.test.search")
        assertTrue(File(destDir, "SKILL.md").exists())
        assertTrue(File(destDir, "references/api.md").exists())
        assertFalse("Helper scripts should be skipped in Step 1", File(destDir, "run.sh").exists())

        // Tampered digest should fail
        val tampered = registry.importSkillPackage("$baseUrl/skill.zip", expectedSha256 = "f".repeat(64))
        assertTrue(tampered.isFailure)

        // Forbidden native binary inside archive should be rejected
        val evilZip = buildZipArchive(
            mapOf(
                "bad-main/SKILL.md" to "# Bad\nContains native payload.",
                "bad-main/payload.so" to "ELF_BINARY_STUB",
            )
        )
        server.createContext("/evil.zip") { ex ->
            ex.sendResponseHeaders(200, evilZip.size.toLong())
            ex.responseBody.use { it.write(evilZip) }
        }
        val evilRes = registry.importSkillPackage("$baseUrl/evil.zip")
        assertTrue(evilRes.isFailure)
    }

    @Test
    fun `remote catalog sync caches tar gz catalog entries installs them and syncs native agent skill folders`() = runBlocking {
        val tarGzBytes = buildTarGzArchive(
            mapOf(
                "opencode-pack-main/skills/planner/SKILL.md" to "---\nname: Sprint Planner\n---\n# Sprint Planner\nPlan modular diffs.",
            )
        )
        val tarSha = ExtensionPackageValidator.sha256Hex(tarGzBytes)
        server.createContext("/planner.tar.gz") { ex ->
            ex.sendResponseHeaders(200, tarGzBytes.size.toLong())
            ex.responseBody.use { it.write(tarGzBytes) }
        }

        val catalogJson = """
            {
              "extensions": [
                {
                  "id": "opencode.remote.planner",
                  "name": "Remote Sprint Planner",
                  "description": "Synced from remote registry.",
                  "category": "SKILLS",
                  "publisher": "Community OpenCode",
                  "version": "1.2.0",
                  "agentTarget": "OPENCODE",
                  "format": "TAR_GZ",
                  "downloadUrl": "$baseUrl/planner.tar.gz",
                  "archiveSubpath": "skills/planner",
                  "sha256": "$tarSha",
                  "tags": ["planning", "opencode"]
                }
              ]
            }
        """.trimIndent()
        server.createContext("/catalog.json") { ex ->
            val bytes = catalogJson.toByteArray(StandardCharsets.UTF_8)
            ex.responseHeaders.add("Content-Type", "application/json")
            ex.sendResponseHeaders(200, bytes.size.toLong())
            ex.responseBody.use { it.write(bytes) }
        }

        val syncedCount = registry.syncRemoteCatalog("$baseUrl/catalog.json").getOrThrow()
        assertEquals(1, syncedCount)
        assertTrue(File(context.filesDir, "extensions/${ExtensionCatalog.OFFICIAL_CATALOG_CACHE_FILE}").exists())

        val installed = registry.installCatalogExtension("opencode.remote.planner").getOrThrow()
        assertEquals("opencode.remote.planner", installed.id)
        assertEquals(ExtensionAgentTarget.OPENCODE, installed.agentTarget)
        assertEquals(ExtensionInstallState.INSTALLED_ENABLED, installed.installState)

        // Native dual-agent sync into OpenCode & Pi session dirs
        val ocSessionSkillsDir = File(context.filesDir, "extensions/tmp_oc_skills")
        val piSessionSkillsDir = File(context.filesDir, "extensions/tmp_pi_skills")
        assertEquals(1, registry.syncEnabledSkillsToDirectory(ocSessionSkillsDir))
        assertEquals(1, registry.syncEnabledSkillsToDirectory(piSessionSkillsDir))
        assertTrue(File(ocSessionSkillsDir, "opencode.remote.planner/SKILL.md").isFile)
        assertTrue(File(piSessionSkillsDir, "opencode.remote.planner/SKILL.md").isFile)
        assertFalse(File(ocSessionSkillsDir, "opencode.remote.planner/.agt_meta.json").exists())

        // Disabled skill is excluded
        registry.setEnabled("opencode.remote.planner", false).getOrThrow()
        val disabledCheckDir = File(context.filesDir, "extensions/tmp_disabled_check")
        assertEquals(0, registry.syncEnabledSkillsToDirectory(disabledCheckDir))
    }

    @Test
    fun `extensions hub ui filters OpenCode vs Pi skills searches and opens prefilled MCP preset dialog`() {
        val viewModel = ExtensionsViewModel(registry)
        composeRule.setContent {
            AgtTheme {
                ExtensionsScreen(viewModel = viewModel)
            }
        }
        composeRule.waitForIdle()

        // Wait for initial catalog emission
        composeRule.waitUntil(3_000) {
            composeRule.onAllNodesWithTag("ext_item_agt.core.repo-inspect").fetchSemanticsNodes().isNotEmpty()
        }

        // Filter to Pi Agent -> brings Pi skills to top, hides OpenCode & Universal skills
        composeRule.onNodeWithTag("ext_agent_filter_pi").performClick()
        composeRule.waitUntil(3_000) {
            composeRule.onAllNodesWithTag("ext_item_pi.skills.brave-search").fetchSemanticsNodes().isNotEmpty()
        }
        composeRule.onNodeWithTag("ext_item_pi.skills.brave-search").assertIsDisplayed()
        assertTrue(
            composeRule.onAllNodesWithTag("ext_item_opencode.skills.systematic-debugging")
                .fetchSemanticsNodes().isEmpty()
        )

        // Filter back to All and search for Context7 on MCP tab
        composeRule.onNodeWithTag("ext_agent_filter_all").performClick()
        composeRule.onNodeWithTag("ext_tab_mcp_servers").performClick()
        composeRule.waitForIdle()
        composeRule.onNodeWithTag("ext_search_input").performTextInput("Context7")
        composeRule.waitUntil(3_000) {
            composeRule.onAllNodesWithTag("ext_item_mcp.preset.context7").fetchSemanticsNodes().isNotEmpty()
        }
        composeRule.onNodeWithTag("ext_item_mcp.preset.context7").assertIsDisplayed()

        // Tap Context7 preset -> Connect opens prefilled AddMcpServerDialog
        composeRule.onNodeWithTag("ext_item_mcp.preset.context7").performClick()
        composeRule.waitUntil(3_000) {
            composeRule.onAllNodesWithTag("ext_install_button").fetchSemanticsNodes().isNotEmpty()
        }
        composeRule.onNodeWithTag("ext_install_button").performClick()
        composeRule.waitUntil(3_000) {
            composeRule.onAllNodesWithTag("ext_mcp_url_input").fetchSemanticsNodes().isNotEmpty()
        }
        composeRule.onNodeWithTag("ext_mcp_url_input").assertIsDisplayed()
        composeRule.onNodeWithText("https://mcp.context7.com/mcp").assertIsDisplayed()
    }

    private fun buildZipArchive(entries: Map<String, String>): ByteArray {
        val bos = ByteArrayOutputStream()
        ZipOutputStream(bos).use { zos ->
            entries.forEach { (path, content) ->
                val data = content.toByteArray(StandardCharsets.UTF_8)
                zos.putNextEntry(ZipEntry(path))
                zos.write(data)
                zos.closeEntry()
            }
        }
        return bos.toByteArray()
    }

    private fun buildTarGzArchive(entries: Map<String, String>): ByteArray {
        val bos = ByteArrayOutputStream()
        GZIPOutputStream(bos).use { gz ->
            entries.forEach { (name, content) ->
                val data = content.toByteArray(StandardCharsets.UTF_8)
                val header = ByteArray(512)
                val nameBytes = name.toByteArray(StandardCharsets.UTF_8)
                System.arraycopy(nameBytes, 0, header, 0, minOf(nameBytes.size, 100))
                System.arraycopy("0000644\u0000".toByteArray(), 0, header, 100, 8)
                System.arraycopy("0000000\u0000".toByteArray(), 0, header, 108, 8)
                System.arraycopy("0000000\u0000".toByteArray(), 0, header, 116, 8)
                val sizeOct = String.format("%011o\u0000", data.size).toByteArray()
                System.arraycopy(sizeOct, 0, header, 124, 12)
                System.arraycopy("00000000000\u0000".toByteArray(), 0, header, 136, 12)
                for (i in 148 until 156) header[i] = ' '.code.toByte()
                header[156] = '0'.code.toByte()
                val sum = header.sumOf { it.toInt() and 0xFF }
                val sumBytes = String.format("%06o\u0000 ", sum).toByteArray()
                System.arraycopy(sumBytes, 0, header, 148, 8)
                gz.write(header)
                gz.write(data)
                val mod = data.size % 512
                if (mod != 0) gz.write(ByteArray(512 - mod))
            }
            gz.write(ByteArray(1024))
        }
        return bos.toByteArray()
    }
}
