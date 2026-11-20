package com.example

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.example.agent.orchestrator.CouncilGraphEngine
import com.example.agent.orchestrator.CouncilGraphRequest
import com.example.agent.orchestrator.CouncilLlmCaller
import com.example.agent.orchestrator.CouncilLlmResult
import com.example.core.security.SearchCredentialVault
import com.example.core.security.TokenCipher
import com.example.data.preferences.AppPreferencesRepository
import com.example.data.search.SearchGatewayRepository
import com.example.domain.contracts.ProjectRef
import com.example.domain.model.CouncilAgentStatus
import com.example.domain.model.CouncilProgressEvent
import com.example.domain.model.CouncilReportSection
import com.example.domain.model.CouncilRole
import com.example.domain.model.CouncilSectionId
import com.example.domain.model.CouncilVerdict
import com.example.domain.model.GatewayProtocolMode
import com.example.domain.model.ProjectInspection
import com.example.domain.model.ProjectStack
import com.sun.net.httpserver.HttpServer
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
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
import java.net.InetSocketAddress
import java.nio.charset.StandardCharsets
import java.util.concurrent.atomic.AtomicInteger

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class CouncilGraphEngineTest {

    private lateinit var context: Context
    private lateinit var server: HttpServer
    private lateinit var baseUrl: String
    private lateinit var preferencesRepository: AppPreferencesRepository
    private lateinit var searchRepository: SearchGatewayRepository

    private val inMemoryCipher = object : TokenCipher {
        override fun encrypt(plaintext: String): ByteArray = plaintext.toByteArray(StandardCharsets.UTF_8)
        override fun decrypt(payload: ByteArray): String = payload.toString(StandardCharsets.UTF_8)
    }

    private val sampleInspection = ProjectInspection(
        projectId = "proj-1",
        projectRootPath = "/tmp/proj-1",
        stack = ProjectStack.GRADLE_JVM,
        stackLabel = "Gradle / JVM",
        entryPoints = listOf("app/src/main/java/MainActivity.kt"),
        manifestSummaries = listOf("Compose + Room"),
        directoryMap = listOf("app/build.gradle.kts", "app/src/main/java/MainActivity.kt"),
        validationCommands = emptyList(),
        isGitRepo = true,
        gitHead = "abc123",
    )

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        preferencesRepository = AppPreferencesRepository(context)
        val vault = SearchCredentialVault(context, cipher = inMemoryCipher)
        searchRepository = SearchGatewayRepository(
            preferencesRepository = preferencesRepository,
            searchCredentialVault = vault,
            publicGitHubApiBaseUrl = "http://127.0.0.1:1", // unreachable fallback unless configured
        )
        server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        server.start()
        baseUrl = "http://127.0.0.1:${server.address.port}"
    }

    @After
    fun tearDown() {
        server.stop(0)
    }

    @Test
    fun `round 1 runs decomposer, dual research, 5 parallel specialists, and 6-section synthesizer`() = runBlocking {
        val webCalls = AtomicInteger(0)
        val githubCalls = AtomicInteger(0)

        server.createContext("/v1/search/web") { ex ->
            webCalls.incrementAndGet()
            val body = """
                {
                  "query": "crdt notes",
                  "provider_used": "tavily",
                  "results": [
                    {
                      "title": "CRDT Local Sync",
                      "url": "https://example.com/crdt",
                      "snippet": "Offline notes",
                      "content": "Use HLC timestamps"
                    }
                  ]
                }
            """.trimIndent().toByteArray(StandardCharsets.UTF_8)
            ex.sendResponseHeaders(200, body.size.toLong())
            ex.responseBody.use { it.write(body) }
        }
        server.createContext("/v1/search/github") { ex ->
            githubCalls.incrementAndGet()
            val body = """
                {
                  "query": "crdt notes",
                  "mode": "repositories",
                  "results": [
                    {
                      "full_name": "notes/crdt-android",
                      "html_url": "https://github.com/notes/crdt-android",
                      "description": "CRDT Android reference",
                      "stars": 950,
                      "language": "Kotlin",
                      "license": "MIT",
                      "readme_excerpt": "# CRDT Android"
                    }
                  ]
                }
            """.trimIndent().toByteArray(StandardCharsets.UTF_8)
            ex.sendResponseHeaders(200, body.size.toLong())
            ex.responseBody.use { it.write(body) }
        }

        searchRepository.saveWebSearchConfig(baseUrl, "web-tok").getOrThrow()
        searchRepository.saveGitHubSearchConfig(baseUrl, "gh-tok").getOrThrow()

        val caller = CouncilLlmCaller { modelId, _, systemPrompt, _ ->
            when {
                "Task Decomposer" in systemPrompt -> Result.success(
                    CouncilLlmResult(
                        text = """
                            {
                              "refined_objective": "Build offline CRDT notes sync",
                              "sub_tasks": ["Define CRDT schema", "Add sync worker"],
                              "web_queries": ["crdt offline notes"],
                              "github_queries": ["crdt notes kotlin"],
                              "preferred_language": "Kotlin",
                              "rationale": "Need prior CRDT patterns"
                            }
                        """.trimIndent(),
                        promptTokens = 50,
                        completionTokens = 30,
                    )
                )
                "Lead Judge & Synthesizer" in systemPrompt -> Result.success(
                    CouncilLlmResult(
                        text = """
                            {
                              "verdict": "MODIFY",
                              "sections": {
                                "summary_verdict": "Proceed with CRDT scope cap.",
                                "research_findings": "Found notes/crdt-android (950 stars).",
                                "execution_plan": "1. Add Room entity\n2. Add sync engine",
                                "cost_resources": "Zero paid backend required if P2P/local.",
                                "risks_critique": "Clock skew risk on offline devices.",
                                "alternatives": "Use simple last-write-wins timestamp MVP."
                              }
                            }
                        """.trimIndent(),
                        promptTokens = 120,
                        completionTokens = 90,
                    )
                )
                else -> Result.success(
                    CouncilLlmResult(
                        text = """
                            {
                              "headline": "Specialist review for $modelId",
                              "verdict_tag": "SOLID",
                              "summary": "Detailed specialist analysis.",
                              "bullet_points": ["Point A", "Point B"]
                            }
                        """.trimIndent(),
                        promptTokens = 40,
                        completionTokens = 25,
                    )
                )
            }
        }

        val engine = CouncilGraphEngine(
            llmCaller = caller,
            searchGatewayRepository = searchRepository,
        )

        val events = engine.runCouncilRound(
            CouncilGraphRequest(
                project = ProjectRef("proj-1", "Notes", "/tmp/proj-1"),
                objective = "Add collaborative CRDT notes",
                defaultModelId = "gemini-2.5-pro",
                roleModelOverrides = mapOf(CouncilRole.CRITIC to "claude-sonnet"),
                inspection = sampleInspection,
            )
        ).toList()

        assertTrue(webCalls.get() >= 1)
        assertTrue(githubCalls.get() >= 1)

        val completed = events.filterIsInstance<CouncilProgressEvent.RoundCompleted>().single()
        val round = completed.round
        assertEquals(1, round.roundNumber)
        assertEquals(CouncilVerdict.MODIFY, round.verdict)
        assertEquals(5, round.opinions.size)
        assertTrue(round.opinions.all { it.status == CouncilAgentStatus.COMPLETED })
        assertEquals("claude-sonnet", round.opinions.first { it.role == CouncilRole.CRITIC }.modelId)
        assertEquals(6, round.sections.size)
        assertTrue(round.dossier.hasAnyFindings)
        assertEquals("notes/crdt-android", round.dossier.githubFindings.first().fullName)
        assertTrue(round.totalPromptTokens > 0)
        assertTrue(round.totalCompletionTokens > 0)
    }

    @Test
    fun `council completes gracefully when web search is 429 and one specialist fails`() = runBlocking {
        server.createContext("/v1/search/web") { ex ->
            val err = """{"error":{"message":"Quota exhausted"}}""".toByteArray(StandardCharsets.UTF_8)
            ex.sendResponseHeaders(429, err.size.toLong())
            ex.responseBody.use { it.write(err) }
        }
        server.createContext("/v1/search/github") { ex ->
            val body = """{"query":"q","mode":"repositories","results":[]}""".toByteArray(StandardCharsets.UTF_8)
            ex.sendResponseHeaders(200, body.size.toLong())
            ex.responseBody.use { it.write(body) }
        }
        searchRepository.saveWebSearchConfig(baseUrl, "web-tok").getOrThrow()
        searchRepository.saveGitHubSearchConfig(baseUrl, "gh-tok").getOrThrow()

        val caller = CouncilLlmCaller { _, _, systemPrompt, _ ->
            when {
                "Devil's Advocate" in systemPrompt -> Result.failure(IllegalStateException("Critic timed out"))
                "Lead Judge & Synthesizer" in systemPrompt -> Result.success(
                    // Also test non-JSON markdown fallback resilience!
                    CouncilLlmResult(text = "## Executive Summary\nProceed carefully without Critic input.")
                )
                else -> Result.success(
                    CouncilLlmResult(text = "Plain text specialist bullet response\n- Keep scope tight")
                )
            }
        }

        val engine = CouncilGraphEngine(llmCaller = caller, searchGatewayRepository = searchRepository)
        val events = engine.runCouncilRound(
            CouncilGraphRequest(
                project = ProjectRef("proj-1", "Notes", "/tmp/proj-1"),
                objective = "Add export feature",
                defaultModelId = "gpt-4.1-mini",
                protocolMode = GatewayProtocolMode.ANTHROPIC_MESSAGES,
                inspection = sampleInspection,
            )
        ).toList()

        val round = events.filterIsInstance<CouncilProgressEvent.RoundCompleted>().single().round
        assertEquals(5, round.opinions.size)
        val critic = round.opinions.first { it.role == CouncilRole.CRITIC }
        assertEquals(CouncilAgentStatus.FAILED, critic.status)
        assertTrue(critic.errorMessage?.contains("timed out") == true)
        assertEquals(4, round.opinions.count { it.status == CouncilAgentStatus.COMPLETED })
        assertTrue(round.dossier.notices.any { it.contains("429") || it.contains("rate-limited") })
        assertEquals(6, round.sections.size)
    }

    @Test
    fun `round 2 re-council reuses dossier when refreshSearch is false and passes user edits`() = runBlocking {
        val searchHits = AtomicInteger(0)
        server.createContext("/v1/search/web") { ex ->
            searchHits.incrementAndGet()
            val body = """{"query":"q","provider_used":"brave","results":[]}""".toByteArray(StandardCharsets.UTF_8)
            ex.sendResponseHeaders(200, body.size.toLong())
            ex.responseBody.use { it.write(body) }
        }
        server.createContext("/v1/search/github") { ex ->
            searchHits.incrementAndGet()
            val body = """{"query":"q","mode":"repositories","results":[]}""".toByteArray(StandardCharsets.UTF_8)
            ex.sendResponseHeaders(200, body.size.toLong())
            ex.responseBody.use { it.write(body) }
        }
        searchRepository.saveWebSearchConfig(baseUrl, "web-tok").getOrThrow()
        searchRepository.saveGitHubSearchConfig(baseUrl, "gh-tok").getOrThrow()

        var observedUserEditInSpecialist = false
        var observedAmendmentInSynth = false

        val caller = CouncilLlmCaller { _, _, systemPrompt, userPrompt ->
            if ("Architect & Planner" in systemPrompt && "Zero-cost SQLite only" in userPrompt) {
                observedUserEditInSpecialist = true
            }
            if ("Lead Judge & Synthesizer" in systemPrompt && "Use free tier only" in userPrompt) {
                observedAmendmentInSynth = true
            }
            Result.success(
                CouncilLlmResult(
                    text = """{"verdict":"GO","headline":"OK","summary":"Updated","bullet_points":["OK"],"sections":{"summary_verdict":"GO","research_findings":"Cached","execution_plan":"Local SQLite","cost_resources":"$0","risks_critique":"Low","alternatives":"None"}}"""
                )
            )
        }

        val engine = CouncilGraphEngine(llmCaller = caller, searchGatewayRepository = searchRepository)
        val round1 = engine.runCouncilRound(
            CouncilGraphRequest(
                project = ProjectRef("proj-1", "Notes", "/tmp/proj-1"),
                objective = "Build sync",
                defaultModelId = "gemini-2.5-flash",
                inspection = sampleInspection,
            )
        ).toList().filterIsInstance<CouncilProgressEvent.RoundCompleted>().single().round

        val hitsAfterRound1 = searchHits.get()
        assertTrue(hitsAfterRound1 > 0)

        val editedSections = round1.sections.map { sec ->
            if (sec.sectionId == CouncilSectionId.COST_RESOURCES) {
                sec.copy(editedContent = "Zero-cost SQLite only", isModifiedByUser = true)
            } else {
                sec
            }
        }

        val round2 = engine.runCouncilRound(
            CouncilGraphRequest(
                project = ProjectRef("proj-1", "Notes", "/tmp/proj-1"),
                objective = "Build sync",
                defaultModelId = "gemini-2.5-flash",
                inspection = sampleInspection,
                roundNumber = 2,
                priorDossier = round1.dossier,
                priorEditedSections = editedSections,
                userAmendmentNote = "Use free tier only",
                refreshSearch = false,
            )
        ).toList().filterIsInstance<CouncilProgressEvent.RoundCompleted>().single().round

        assertEquals(2, round2.roundNumber)
        assertEquals(hitsAfterRound1, searchHits.get())
        assertTrue(observedUserEditInSpecialist)
        assertTrue(observedAmendmentInSynth)
        assertNotNull(round2.toHandoffMarkdown())
    }
}
