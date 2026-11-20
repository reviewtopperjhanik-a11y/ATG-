package com.example

import android.content.Context
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performScrollTo
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.example.agent.opencode.OpenCodeInstallerRepository
import com.example.core.common.AgtError
import com.example.core.config.AgtAppConfig
import com.example.core.security.GatewayCredentialVault
import com.example.core.security.GitHubCredentialVault
import com.example.core.security.SearchCredentialVault
import com.example.core.security.TokenCipher
import com.example.data.database.AgtDatabase
import com.example.data.gateway.GatewayEndpointConfig
import com.example.data.gateway.GatewayRepository
import com.example.data.github.GitHubApiClient
import com.example.data.github.GitHubRepository
import com.example.data.preferences.AppPreferencesRepository
import com.example.data.project.ProjectRepository
import com.example.data.runtime.LocalRuntimeExecutor
import com.example.data.runtime.RuntimeInstallerRepository
import com.example.data.search.GatewayGitHubSearchClient
import com.example.data.search.GatewayWebSearchClient
import com.example.data.search.SearchGatewayRepository
import com.example.domain.model.GatewayHealthStatus
import com.example.domain.model.GitHubSearchMode
import com.example.domain.model.GitHubSearchRequest
import com.example.domain.model.SearchOutcome
import com.example.domain.model.SettingsCategory
import com.example.domain.model.WebSearchRequest
import com.example.feature.settings.SettingsDetailScreen
import com.example.feature.settings.SettingsViewModel
import com.example.ui.theme.AgtTheme
import com.sun.net.httpserver.HttpServer
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.net.InetSocketAddress
import java.nio.charset.StandardCharsets

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class SearchGatewayTest {

    @get:Rule
    val composeRule = createComposeRule()

    private lateinit var context: Context
    private lateinit var server: HttpServer
    private lateinit var baseUrl: String
    private lateinit var preferencesRepository: AppPreferencesRepository
    private lateinit var database: AgtDatabase

    private val inMemoryCipher = object : TokenCipher {
        override fun encrypt(plaintext: String): ByteArray = plaintext.toByteArray(StandardCharsets.UTF_8)
        override fun decrypt(payload: ByteArray): String = payload.toString(StandardCharsets.UTF_8)
    }

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        preferencesRepository = AppPreferencesRepository(context)
        database = Room.inMemoryDatabaseBuilder(context, AgtDatabase::class.java)
            .allowMainThreadQueries()
            .build()
        server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        server.start()
        baseUrl = "http://127.0.0.1:${server.address.port}"
    }

    @After
    fun tearDown() {
        server.stop(0)
        database.close()
    }

    @Test
    fun `search credential vault isolates and clears web and github search tokens`() = runBlocking {
        val vault = SearchCredentialVault(context, cipher = inMemoryCipher)
        vault.clearWebSearchToken()
        vault.clearGitHubSearchToken()

        assertFalse(vault.hasWebSearchToken.value)
        assertFalse(vault.hasGitHubSearchToken.value)

        vault.saveWebSearchToken("web_tok_alpha_123").getOrThrow()
        vault.saveGitHubSearchToken("gh_search_tok_beta_456").getOrThrow()

        assertTrue(vault.hasWebSearchToken.value)
        assertTrue(vault.hasGitHubSearchToken.value)
        assertEquals("web_tok_alpha_123", vault.readWebSearchToken().getOrNull())
        assertEquals("gh_search_tok_beta_456", vault.readGitHubSearchToken().getOrNull())
        assertEquals(2, vault.allStoredTokens().size)

        vault.clearWebSearchToken()
        assertFalse(vault.hasWebSearchToken.value)
        assertNull(vault.readWebSearchToken().getOrNull())
        assertTrue(vault.hasGitHubSearchToken.value)
        assertEquals("gh_search_tok_beta_456", vault.readGitHubSearchToken().getOrNull())

        vault.clearGitHubSearchToken()
        assertFalse(vault.hasGitHubSearchToken.value)
    }

    @Test
    fun `web search client parses health, search results, and redacts secret on 429`() = runBlocking {
        val secret = "web_secret_token_999"
        var observedAuth: String? = null
        var forceRateLimit = false

        server.createContext("/v1/search/web/health") { ex ->
            observedAuth = ex.requestHeaders.getFirst("Authorization")
            val body = """
                {
                  "status": "ok",
                  "service": "web_search",
                  "active_providers": ["tavily", "brave"],
                  "available_keys_count": 4,
                  "cache_enabled": true
                }
            """.trimIndent().toByteArray(StandardCharsets.UTF_8)
            ex.sendResponseHeaders(200, body.size.toLong())
            ex.responseBody.use { it.write(body) }
        }

        server.createContext("/v1/search/web") { ex ->
            if (forceRateLimit) {
                val err = """{"error":{"code":"rate_limit_exhausted","message":"All keys throttled for $secret"}}"""
                    .toByteArray(StandardCharsets.UTF_8)
                ex.sendResponseHeaders(429, err.size.toLong())
                ex.responseBody.use { it.write(err) }
            } else {
                val body = """
                    {
                      "query": "kotlin multiplatform crdt",
                      "provider_used": "tavily",
                      "cached": false,
                      "latency_ms": 145,
                      "results": [
                        {
                          "title": "CRDT Notes Architecture",
                          "url": "https://example.com/crdt",
                          "snippet": "Local-first notes sync",
                          "content": "Full excerpt about CRDT sync",
                          "published_date": "2026-01-10",
                          "score": 0.94
                        }
                      ]
                    }
                """.trimIndent().toByteArray(StandardCharsets.UTF_8)
                ex.sendResponseHeaders(200, body.size.toLong())
                ex.responseBody.use { it.write(body) }
            }
        }

        val client = GatewayWebSearchClient(
            configProvider = {
                GatewayEndpointConfig(
                    baseUrl = "$baseUrl/v1/search/web",
                    bearerToken = secret,
                )
            }
        )

        val health = client.health()
        assertEquals(GatewayHealthStatus.CONNECTED, health.status)
        assertEquals("Bearer $secret", observedAuth)
        assertEquals(listOf("tavily", "brave"), health.activeProviders)
        assertEquals(4, health.availableKeysCount)
        assertEquals(true, health.cacheEnabled)

        val searchResp = client.search(WebSearchRequest(query = "kotlin multiplatform crdt")).getOrThrow()
        assertEquals("tavily", searchResp.providerUsed)
        assertEquals(1, searchResp.results.size)
        assertEquals("CRDT Notes Architecture", searchResp.results[0].title)
        assertEquals("https://example.com/crdt", searchResp.results[0].url)

        forceRateLimit = true
        val err = client.search(WebSearchRequest(query = "kotlin multiplatform crdt")).exceptionOrNull()
        assertNotNull(err)
        assertFalse((err?.message ?: "").contains(secret))
    }

    @Test
    fun `github search client parses health, repos, topics, readme, and repository handles graceful degradation and fallback`() = runBlocking {
        val secret = "gh_gateway_secret_777"

        server.createContext("/v1/search/github/health") { ex ->
            val body = """
                {
                  "status": "ok",
                  "service": "github_search",
                  "pat_pool_size": 6,
                  "healthy_tokens": 5,
                  "cache_enabled": true
                }
            """.trimIndent().toByteArray(StandardCharsets.UTF_8)
            ex.sendResponseHeaders(200, body.size.toLong())
            ex.responseBody.use { it.write(body) }
        }

        server.createContext("/v1/search/github") { ex ->
            val body = """
                {
                  "query": "android markdown editor",
                  "mode": "repositories",
                  "cached": true,
                  "latency_ms": 22,
                  "total_count": 120,
                  "results": [
                    {
                      "full_name": "acme/note-sync",
                      "html_url": "https://github.com/acme/note-sync",
                      "description": "Offline-first Kotlin notes",
                      "stars": 1420,
                      "forks": 90,
                      "language": "Kotlin",
                      "license": "MIT",
                      "updated_at": "2026-05-01T10:00:00Z",
                      "topics": ["android", "kotlin"],
                      "readme_excerpt": "# Note Sync\nUses Room + CRDT."
                    }
                  ]
                }
            """.trimIndent().toByteArray(StandardCharsets.UTF_8)
            ex.sendResponseHeaders(200, body.size.toLong())
            ex.responseBody.use { it.write(body) }
        }

        server.createContext("/search/repositories") { ex ->
            val body = """
                {
                  "total_count": 1,
                  "items": [
                    {
                      "full_name": "fallback/repo",
                      "html_url": "https://github.com/fallback/repo",
                      "description": "Public fallback repository",
                      "stargazers_count": 300,
                      "forks_count": 15,
                      "language": "Kotlin",
                      "license": { "spdx_id": "Apache-2.0" },
                      "topics": ["compose"]
                    }
                  ]
                }
            """.trimIndent().toByteArray(StandardCharsets.UTF_8)
            ex.sendResponseHeaders(200, body.size.toLong())
            ex.responseBody.use { it.write(body) }
        }

        val vault = SearchCredentialVault(context, cipher = inMemoryCipher)
        val repo = SearchGatewayRepository(
            preferencesRepository = preferencesRepository,
            searchCredentialVault = vault,
            publicGitHubApiBaseUrl = baseUrl,
        )

        // When unconfigured: Web Search degrades gracefully, GitHub Search uses public fallback
        repo.saveWebSearchConfig("", null).getOrThrow()
        repo.saveGitHubSearchConfig("", null).getOrThrow()
        val degradedWeb = repo.executeWebSearch(WebSearchRequest("compose navigation"))
        assertTrue(degradedWeb is SearchOutcome.Degraded)

        val fallbackGh = repo.executeGitHubSearch(GitHubSearchRequest("compose navigation"))
        assertTrue(fallbackGh is SearchOutcome.Success)
        fallbackGh as SearchOutcome.Success
        assertTrue(fallbackGh.fallbackUsed)
        assertEquals("fallback/repo", fallbackGh.data.results.first().fullName)

        // When configured: API 3 gateway responds directly
        repo.saveGitHubSearchConfig(baseUrl, secret).getOrThrow()
        val ghHealth = repo.testGitHubSearchHealth()
        assertEquals(GatewayHealthStatus.CONNECTED, ghHealth.status)
        assertEquals(5, ghHealth.availableKeysCount)

        val ghRes = repo.executeGitHubSearch(
            GitHubSearchRequest(query = "android markdown editor", mode = GitHubSearchMode.REPOSITORIES)
        )
        assertTrue(ghRes is SearchOutcome.Success)
        ghRes as SearchOutcome.Success
        assertFalse(ghRes.fallbackUsed)
        assertEquals("acme/note-sync", ghRes.data.results[0].fullName)
        assertEquals(1420, ghRes.data.results[0].stars)
        assertEquals(listOf("android", "kotlin"), ghRes.data.results[0].topics)
        assertTrue(ghRes.data.results[0].readmeExcerpt.contains("CRDT"))
        assertEquals(baseUrl, preferencesRepository.preferences.first().githubSearchBaseUrl)
    }

    @Test
    fun `settings gateway screen renders all 3 api gateway sections`() {
        val searchVault = SearchCredentialVault(context, cipher = inMemoryCipher)
        val searchRepo = SearchGatewayRepository(
            preferencesRepository = preferencesRepository,
            searchCredentialVault = searchVault,
        )
        val gatewayRepo = GatewayRepository(
            preferencesRepository = preferencesRepository,
            credentialVault = GatewayCredentialVault(context, cipher = inMemoryCipher),
            cachedModelDao = database.cachedModelDao(),
        )
        val runtimeInstaller = RuntimeInstallerRepository(
            context = context,
            preferencesRepository = preferencesRepository,
            appConfig = AgtAppConfig(),
        )
        val openCodeInstaller = OpenCodeInstallerRepository(
            context = context,
            runtimeInstallerRepository = runtimeInstaller,
            preferencesRepository = preferencesRepository,
            runtimeExecutor = LocalRuntimeExecutor(runtimeInstaller),
        )
        val gitHubRepo = GitHubRepository(
            preferencesRepository = preferencesRepository,
            credentialVault = GitHubCredentialVault(context, cipher = inMemoryCipher),
            apiClient = GitHubApiClient(),
            appConfig = AgtAppConfig(),
        )
        val projectRepo = ProjectRepository(
            context = context,
            projectDao = database.projectDao(),
            taskDao = database.taskDao(),
            checkpointDao = database.checkpointDao(),
        )

        val vm = SettingsViewModel(
            appContext = context,
            preferencesRepository = preferencesRepository,
            projectRepository = projectRepo,
            gatewayRepository = gatewayRepo,
            gitHubRepository = gitHubRepo,
            runtimeInstallerRepository = runtimeInstaller,
            openCodeInstallerRepository = openCodeInstaller,
            appConfig = AgtAppConfig(),
            searchGatewayRepository = searchRepo,
        )

        composeRule.setContent {
            AgtTheme {
                SettingsDetailScreen(
                    category = SettingsCategory.GATEWAY,
                    viewModel = vm,
                    onBack = {},
                    onNavigateToGateway = {},
                )
            }
        }

        composeRule.waitForIdle()
        composeRule.onNodeWithTag("gateway_url_input").assertIsDisplayed()
        composeRule.onNodeWithTag("web_search_url_input").performScrollTo().assertIsDisplayed()
        composeRule.onNodeWithTag("web_search_test_button").performScrollTo().assertIsDisplayed()
        composeRule.onNodeWithTag("github_search_url_input").performScrollTo().assertIsDisplayed()
        composeRule.onNodeWithTag("github_search_test_button").performScrollTo().assertIsDisplayed()
    }
}
