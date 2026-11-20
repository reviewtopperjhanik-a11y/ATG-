package com.example

import android.content.Context
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performScrollToNode
import androidx.compose.ui.test.performTextInput
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.example.core.security.GatewayCredentialVault
import com.example.core.security.TokenCipher
import com.example.data.database.AgtDatabase
import com.example.data.gateway.GatewayRepository
import com.example.data.preferences.AppPreferencesRepository
import com.example.data.project.ProjectRepository
import com.example.domain.model.GatewayHealthStatus
import com.example.domain.model.GatewayProtocolMode
import com.sun.net.httpserver.HttpServer
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.net.InetSocketAddress
import java.nio.charset.StandardCharsets

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class ExampleRobolectricTest {

    @get:Rule
    val composeRule = createAndroidComposeRule<MainActivity>()

    @Test
    fun `app name matches AGT Code`() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        assertEquals("AGT Code", context.getString(R.string.app_name))
    }

    @Test
    fun `projects persist across database reopen and maintain recent order`() {
        runBlocking {
            val context = ApplicationProvider.getApplicationContext<Context>()
            val dbName = "test_agt_restart.db"
            context.deleteDatabase(dbName)

            val db1 = Room.databaseBuilder(context, AgtDatabase::class.java, dbName)
                .allowMainThreadQueries()
                .build()
            val repo1 = ProjectRepository(
                context = context,
                projectDao = db1.projectDao(),
                taskDao = db1.taskDao(),
                checkpointDao = db1.checkpointDao(),
            )

            val first = repo1.createProject("alpha-app").getOrThrow()
            Thread.sleep(5)
            val second = repo1.createProject("beta-api").getOrThrow()

            // Write package.json to alpha-app and reopen it so it becomes most recent with Node.js stack
            repo1.writeText(first.workspacePath, "package.json", "{\"name\":\"alpha\"}").getOrThrow()
            Thread.sleep(5)
            repo1.markOpened(first.id)
            db1.close()

            // Simulate app restart with fresh Room database & repository instance
            val db2 = Room.databaseBuilder(context, AgtDatabase::class.java, dbName)
                .allowMainThreadQueries()
                .build()
            val repo2 = ProjectRepository(
                context = context,
                projectDao = db2.projectDao(),
                taskDao = db2.taskDao(),
                checkpointDao = db2.checkpointDao(),
            )

            val recent = repo2.observeRecentProjects().first()
            assertEquals(2, recent.size)
            assertEquals(first.id, recent[0].id)
            assertEquals("Node.js", recent[0].stackLabel)
            assertEquals(second.id, recent[1].id)

            // Verify confined read & traversal rejection after restart
            assertTrue(repo2.readText(first.workspacePath, "package.json").isSuccess)
            assertTrue(repo2.readText(first.workspacePath, "../outside.txt").isFailure)

            repo2.deleteProject(first.id).getOrThrow()
            repo2.deleteProject(second.id).getOrThrow()
            db2.close()
            context.deleteDatabase(dbName)
        }
    }

    @Test
    fun `fresh launch opens Setup and navigates Setup to GitHub to Home to Workspace and Settings`() {
        composeRule.waitUntil(timeoutMillis = 15_000) {
            composeRule.onAllNodesWithTag("setup_skip_button").fetchSemanticsNodes().isNotEmpty()
        }
        assertEquals(0, composeRule.onAllNodesWithTag("setup_manifest_url_input").fetchSemanticsNodes().size)
        composeRule.onNodeWithTag("setup_install_button").assertIsDisplayed()
        composeRule.onNodeWithTag("setup_skip_button").assertIsDisplayed().performClick()
        composeRule.waitUntil(timeoutMillis = 5_000) {
            composeRule.onAllNodesWithTag("github_skip_button").fetchSemanticsNodes().isNotEmpty()
        }
        composeRule.waitForIdle()

        composeRule.onNodeWithTag("github_skip_button").assertIsDisplayed().performClick()
        composeRule.waitForIdle()

        // Home bottom bar & Create Project
        composeRule.onNodeWithTag("nav_home").assertIsDisplayed()
        composeRule.onNodeWithTag("nav_extensions").assertIsDisplayed()
        composeRule.onNodeWithTag("nav_settings").assertIsDisplayed()

        composeRule.onNodeWithTag("home_create_project_button").performClick()
        composeRule.waitForIdle()

        composeRule.onNodeWithTag("project_name_input").performTextInput("demo-api")
        composeRule.onNodeWithTag("create_project_confirm_button").performClick()
        composeRule.waitUntil(timeoutMillis = 5_000) {
            composeRule.onAllNodesWithTag("workspace_tab_agent").fetchSemanticsNodes().isNotEmpty()
        }
        composeRule.waitForIdle()

        // Workspace shows 4 horizontal tabs and Agent composer controls
        composeRule.onNodeWithTag("workspace_tab_agent").assertIsDisplayed()
        composeRule.onNodeWithTag("workspace_tab_files").assertIsDisplayed()
        composeRule.onNodeWithTag("workspace_tab_terminal").assertIsDisplayed()
        composeRule.onNodeWithTag("workspace_tab_preview").assertIsDisplayed()
        composeRule.onNodeWithTag("agent_plus_button").assertIsDisplayed()
        composeRule.onNodeWithTag("agent_model_selector").assertIsDisplayed()
        composeRule.onNodeWithTag("agent_send_button").assertIsDisplayed()
        composeRule.onNodeWithTag("agent_configure_opencode_button").assertIsDisplayed()

        // Create a file in Files tab and verify it appears
        composeRule.onNodeWithTag("workspace_tab_files").performClick()
        composeRule.waitUntil(timeoutMillis = 5_000) {
            composeRule.onAllNodesWithTag("files_create_button").fetchSemanticsNodes().isNotEmpty()
        }
        composeRule.onNodeWithTag("files_create_button").performClick()
        composeRule.waitForIdle()
        composeRule.onNodeWithTag("new_file_name_input").performTextInput("README.md")
        composeRule.onNodeWithTag("confirm_create_file_button").performClick()
        composeRule.waitUntil(timeoutMillis = 5_000) {
            composeRule.onAllNodesWithTag("delete_entry_README.md").fetchSemanticsNodes().isNotEmpty()
        }
        composeRule.onNodeWithText("README.md").assertIsDisplayed()

        // Verify Git workflow button and create a workspace checkpoint from Files tab
        composeRule.onNodeWithTag("files_git_button").assertIsDisplayed()
        composeRule.onNodeWithTag("files_checkpoints_button").assertIsDisplayed().performClick()
        composeRule.waitUntil(timeoutMillis = 5_000) {
            composeRule.onAllNodesWithTag("create_manual_checkpoint_button").fetchSemanticsNodes().isNotEmpty()
        }
        composeRule.onNodeWithTag("create_manual_checkpoint_button").performClick()
        composeRule.waitForIdle()
        composeRule.onNodeWithText("Close").performClick()
        composeRule.waitForIdle()

        // Switch to Terminal tab and verify M5 terminal controls & feedback
        composeRule.onNodeWithTag("workspace_tab_terminal").performClick()
        composeRule.waitUntil(timeoutMillis = 5_000) {
            composeRule.onAllNodesWithTag("terminal_command_input").fetchSemanticsNodes().isNotEmpty()
        }
        composeRule.onNodeWithTag("terminal_stop_button").assertIsDisplayed()
        composeRule.onNodeWithTag("terminal_clear_button").assertIsDisplayed()
        composeRule.onNodeWithTag("terminal_command_input").performTextInput("pwd")
        composeRule.onNodeWithTag("terminal_run_button").performClick()
        composeRule.waitUntil(timeoutMillis = 5_000) {
            composeRule.onAllNodesWithTag("terminal_output_list").fetchSemanticsNodes().isNotEmpty()
        }
        composeRule.onNodeWithTag("terminal_clear_button").performClick()

        // Back to Home -> Settings -> Appearance detail page
        composeRule.onNodeWithTag("workspace_back_button").performClick()
        composeRule.waitUntil(timeoutMillis = 5_000) {
            composeRule.onAllNodesWithTag("nav_settings").fetchSemanticsNodes().isNotEmpty()
        }
        composeRule.waitForIdle()

        composeRule.onNodeWithTag("nav_settings").performClick()
        composeRule.waitUntil(timeoutMillis = 5_000) {
            composeRule.onAllNodesWithTag("settings_list").fetchSemanticsNodes().isNotEmpty()
        }
        composeRule.waitForIdle()

        composeRule.onNodeWithTag("settings_list")
            .performScrollToNode(hasTestTag("settings_row_appearance"))
        composeRule.onNodeWithTag("settings_row_appearance").assertIsDisplayed().performClick()
        composeRule.waitUntil(timeoutMillis = 5_000) {
            composeRule.onAllNodesWithTag("appearance_option_light").fetchSemanticsNodes().isNotEmpty()
        }
        composeRule.waitForIdle()

        composeRule.onNodeWithTag("appearance_option_light").assertIsDisplayed().performClick()
        composeRule.onNodeWithText("Theme Mode", ignoreCase = true).assertIsDisplayed()

        // Navigate back to Settings list -> Gateway, Agent (OpenCode), Runtime, and Models detail pages
        composeRule.onNodeWithTag("settings_detail_back").performClick()
        composeRule.waitUntil(timeoutMillis = 5_000) {
            composeRule.onAllNodesWithTag("settings_list").fetchSemanticsNodes().isNotEmpty()
        }
        composeRule.onNodeWithTag("settings_list")
            .performScrollToNode(hasTestTag("settings_row_gateway"))
        composeRule.onNodeWithTag("settings_row_gateway").assertIsDisplayed().performClick()
        composeRule.waitUntil(timeoutMillis = 5_000) {
            composeRule.onAllNodesWithTag("gateway_url_input").fetchSemanticsNodes().isNotEmpty()
        }
        composeRule.onNodeWithTag("gateway_url_input").performScrollTo().assertIsDisplayed()
        composeRule.onNodeWithTag("gateway_token_input").performScrollTo().assertIsDisplayed()
        composeRule.onNodeWithTag("gateway_save_button").performScrollTo().assertIsDisplayed()
        composeRule.onNodeWithTag("gateway_test_button").performScrollTo().assertIsDisplayed()

        composeRule.onNodeWithTag("settings_detail_back").performClick()
        composeRule.waitUntil(timeoutMillis = 5_000) {
            composeRule.onAllNodesWithTag("settings_list").fetchSemanticsNodes().isNotEmpty()
        }
        composeRule.onNodeWithTag("settings_list")
            .performScrollToNode(hasTestTag("settings_row_agent"))
        composeRule.onNodeWithTag("settings_row_agent").assertIsDisplayed().performClick()
        composeRule.waitUntil(timeoutMillis = 5_000) {
            composeRule.onAllNodesWithTag("settings_opencode_install_button").fetchSemanticsNodes().isNotEmpty()
        }
        assertEquals(0, composeRule.onAllNodesWithTag("settings_opencode_manifest_input").fetchSemanticsNodes().size)
        assertEquals(0, composeRule.onAllNodesWithTag("settings_pi_manifest_input").fetchSemanticsNodes().size)
        composeRule.onNodeWithTag("settings_opencode_install_button").performScrollTo().assertIsDisplayed()
        composeRule.onNodeWithTag("settings_opencode_verify_button").performScrollTo().assertIsDisplayed()
        composeRule.onNodeWithTag("settings_pi_npm_install_button").performScrollTo().assertIsDisplayed()
        composeRule.onNodeWithTag("settings_pi_verify_button").performScrollTo().assertIsDisplayed()

        composeRule.onNodeWithTag("settings_detail_back").performClick()
        composeRule.waitUntil(timeoutMillis = 5_000) {
            composeRule.onAllNodesWithTag("settings_list").fetchSemanticsNodes().isNotEmpty()
        }
        composeRule.onNodeWithTag("settings_list")
            .performScrollToNode(hasTestTag("settings_row_runtime"))
        composeRule.onNodeWithTag("settings_row_runtime").assertIsDisplayed().performClick()
        composeRule.waitUntil(timeoutMillis = 5_000) {
            composeRule.onAllNodesWithTag("settings_runtime_install_button").fetchSemanticsNodes().isNotEmpty()
        }
        assertEquals(0, composeRule.onAllNodesWithTag("settings_runtime_manifest_input").fetchSemanticsNodes().size)
        composeRule.onNodeWithTag("settings_runtime_install_button").performScrollTo().assertIsDisplayed()
        composeRule.onNodeWithTag("settings_runtime_health_button").performScrollTo().assertIsDisplayed()
        composeRule.onNodeWithTag("settings_runtime_repair_button").performScrollTo().assertIsDisplayed()

        composeRule.onNodeWithTag("settings_detail_back").performClick()
        composeRule.waitUntil(timeoutMillis = 5_000) {
            composeRule.onAllNodesWithTag("settings_list").fetchSemanticsNodes().isNotEmpty()
        }
        composeRule.onNodeWithTag("settings_list")
            .performScrollToNode(hasTestTag("settings_row_models"))
        composeRule.onNodeWithTag("settings_row_models").assertIsDisplayed().performClick()
        composeRule.waitUntil(timeoutMillis = 5_000) {
            composeRule.onAllNodesWithTag("settings_detail_back").fetchSemanticsNodes().isNotEmpty()
        }
        composeRule.onNodeWithText("No Live Models Discovered").assertIsDisplayed()
    }

    @Test
    fun `gateway configuration, encrypted vault, and discovered models persist across restart`() {
        runBlocking {
            val context = ApplicationProvider.getApplicationContext<Context>()
            val dbName = "test_agt_gateway.db"
            context.deleteDatabase(dbName)
            context.getSharedPreferences("agt_gateway_vault", Context.MODE_PRIVATE).edit().clear().commit()

            val testCipher = object : TokenCipher {
                override fun encrypt(plaintext: String): ByteArray =
                    plaintext.toByteArray(StandardCharsets.UTF_8).map { (it.toInt() xor 0x5A).toByte() }.toByteArray()

                override fun decrypt(payload: ByteArray): String =
                    String(payload.map { (it.toInt() xor 0x5A).toByte() }.toByteArray(), StandardCharsets.UTF_8)
            }

            val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
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
                          "id": "claude-3-7-sonnet",
                          "provider": "anthropic",
                          "capabilities": {"streaming": true, "tool_calling": true, "vision": true, "context_window": 200000}
                        }
                      ]
                    }
                """.trimIndent()
                val bytes = json.toByteArray(StandardCharsets.UTF_8)
                ex.sendResponseHeaders(200, bytes.size.toLong())
                ex.responseBody.use { it.write(bytes) }
            }
            server.start()

            try {
                val gwUrl = "http://127.0.0.1:${server.address.port}"
                val prefsRepo = AppPreferencesRepository(context)
                val db1 = Room.databaseBuilder(context, AgtDatabase::class.java, dbName)
                    .addMigrations(AgtDatabase.MIGRATION_1_2)
                    .allowMainThreadQueries()
                    .build()
                val vault1 = GatewayCredentialVault(context, testCipher)
                val repo1 = GatewayRepository(prefsRepo, vault1, db1.cachedModelDao())

                repo1.saveConfiguration(gwUrl, GatewayProtocolMode.CHAT_COMPLETIONS, "jh_live_token_999").getOrThrow()
                assertTrue(repo1.hasGatewayToken.value)
                val rawStored = context.getSharedPreferences("agt_gateway_vault", Context.MODE_PRIVATE)
                    .getString("encrypted_gateway_token", "")
                assertNotEquals("jh_live_token_999", rawStored)

                val health = repo1.testHealthAndDiscoverModels()
                assertEquals(GatewayHealthStatus.CONNECTED, health.status)
                assertEquals(1, repo1.discoveredModels.first().size)
                assertEquals("claude-3-7-sonnet", prefsRepo.preferences.first().defaultModelId)
                db1.close()

                // Reopen database & vault to verify persistence across restart
                val db2 = Room.databaseBuilder(context, AgtDatabase::class.java, dbName)
                    .addMigrations(AgtDatabase.MIGRATION_1_2)
                    .allowMainThreadQueries()
                    .build()
                val vault2 = GatewayCredentialVault(context, testCipher)
                val repo2 = GatewayRepository(prefsRepo, vault2, db2.cachedModelDao())

                assertTrue(repo2.hasGatewayToken.value)
                assertEquals("jh_live_token_999", vault2.readToken().getOrThrow())
                val persistedModels = repo2.discoveredModels.first()
                assertEquals(1, persistedModels.size)
                assertEquals("claude-3-7-sonnet", persistedModels[0].id)
                assertEquals(true, persistedModels[0].supportsToolCalling)

                repo2.clearToken()
                assertFalse(repo2.hasGatewayToken.value)
                assertTrue(repo2.discoveredModels.first().isEmpty())
                prefsRepo.setGatewayBaseUrl("http://127.0.0.1:8787")
                db2.close()
            } finally {
                server.stop(0)
                context.deleteDatabase(dbName)
            }
        }
    }
}
