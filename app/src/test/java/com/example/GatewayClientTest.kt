package com.example

import com.example.core.common.AgtError
import com.example.core.security.GatewayUrlValidation
import com.example.core.security.GatewayUrlValidator
import com.example.core.security.SecretRedactor
import com.example.data.gateway.GatewayApiException
import com.example.data.gateway.GatewayEndpointConfig
import com.example.data.gateway.JhGatewayHttpClient
import com.example.domain.contracts.GatewayChatRequest
import com.example.domain.contracts.GatewayStreamEvent
import com.example.domain.model.GatewayHealthStatus
import com.example.domain.model.GatewayProtocolMode
import com.sun.net.httpserver.HttpServer
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
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
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class GatewayClientTest {

    private lateinit var server: HttpServer
    private var baseUrl: String = ""

    @Before
    fun setUp() {
        server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        server.start()
        baseUrl = "http://127.0.0.1:${server.address.port}"
    }

    @After
    fun tearDown() {
        server.stop(0)
    }

    @Test
    fun `gateway url validator enforces HTTPS for remote and allows loopback HTTP`() {
        val local = GatewayUrlValidator.validate("http://127.0.0.1:8787/")
        assertTrue(local is GatewayUrlValidation.Valid)
        assertEquals("http://127.0.0.1:8787", (local as GatewayUrlValidation.Valid).normalizedBaseUrl)
        assertTrue(local.isLoopback)

        val localhost = GatewayUrlValidator.validate("http://localhost:8787")
        assertTrue(localhost is GatewayUrlValidation.Valid)
        assertTrue((localhost as GatewayUrlValidation.Valid).isLoopback)

        val remoteHttps = GatewayUrlValidator.validate("https://gw.example.com/api/")
        assertTrue(remoteHttps is GatewayUrlValidation.Valid)
        assertEquals("https://gw.example.com/api", (remoteHttps as GatewayUrlValidation.Valid).normalizedBaseUrl)
        assertFalse(remoteHttps.isLoopback)

        assertTrue(GatewayUrlValidator.validate("http://gw.example.com:8787") is GatewayUrlValidation.Invalid)
        assertTrue(GatewayUrlValidator.validate("https://user:pass@gw.example.com") is GatewayUrlValidation.Invalid)
        assertTrue(GatewayUrlValidator.validate("https://gw.example.com?foo=bar") is GatewayUrlValidation.Invalid)
        assertTrue(GatewayUrlValidator.validate("https://gw.example.com#frag") is GatewayUrlValidation.Invalid)
        assertTrue(GatewayUrlValidator.validate("   ") is GatewayUrlValidation.Invalid)
    }

    @Test
    fun `secret redactor masks bearer headers, api tokens, and known secrets`() {
        val raw = "Authorization: Bearer my-secret-token-value failed with sk-abcdef1234567890 and ghp_1234567890abcdef and jh_dev_9876543210"
        val redacted = SecretRedactor.redact(raw, knownSecrets = listOf("my-secret-token-value"))
        assertFalse(redacted.contains("my-secret-token-value"))
        assertFalse(redacted.contains("sk-abcdef1234567890"))
        assertFalse(redacted.contains("ghp_1234567890abcdef"))
        assertFalse(redacted.contains("jh_dev_9876543210"))
        assertTrue(redacted.contains("[REDACTED]"))
    }

    @Test
    fun `health returns connected on 200 and maps 401 and 403`() = runBlocking {
        var nextStatus = 200
        var nextBody = """{"status":"ok"}"""
        var observedAuth: String? = null

        server.createContext("/health") { ex ->
            observedAuth = ex.requestHeaders.getFirst("Authorization")
            val bytes = nextBody.toByteArray(StandardCharsets.UTF_8)
            ex.sendResponseHeaders(nextStatus, bytes.size.toLong())
            ex.responseBody.use { it.write(bytes) }
        }

        val client = JhGatewayHttpClient(
            configProvider = { GatewayEndpointConfig(baseUrl = baseUrl, bearerToken = "jh_test_token_12345") }
        )

        val ok = client.health()
        assertEquals(GatewayHealthStatus.CONNECTED, ok.status)
        assertEquals("Bearer jh_test_token_12345", observedAuth)
        assertNotNull(ok.latencyMs)

        nextStatus = 401
        nextBody = """{"error":{"message":"Invalid token"}}"""
        val unauth = client.health()
        assertEquals(GatewayHealthStatus.ERROR, unauth.status)
        assertTrue(unauth.error is AgtError.GatewayUnauthorized)

        nextStatus = 403
        nextBody = """{"error":{"message":"Scope denied"}}"""
        val forbidden = client.health()
        assertEquals(GatewayHealthStatus.ERROR, forbidden.status)
        assertTrue(forbidden.error is AgtError.PermissionDenied)
    }

    @Test
    fun `listModels parses gateway model capabilities and redacts secret on error`() = runBlocking {
        val secret = "jh_secret_abc12345"
        var failWith403 = false

        server.createContext("/v1/models") { ex ->
            if (failWith403) {
                val err = """{"error":{"message":"Model blocked by allowlist for token $secret"}}"""
                val bytes = err.toByteArray(StandardCharsets.UTF_8)
                ex.sendResponseHeaders(403, bytes.size.toLong())
                ex.responseBody.use { it.write(bytes) }
            } else {
                val json = """
                    {
                      "data": [
                        {
                          "id": "gpt-4.1-mini",
                          "provider": "openai",
                          "capabilities": {
                            "streaming": true,
                            "tool_calling": true,
                            "vision": true,
                            "context_window": 128000
                          }
                        },
                        {
                          "id": "legacy-chat",
                          "owned_by": "local-vllm",
                          "supports_streaming": true,
                          "supports_tool_calling": false
                        }
                      ]
                    }
                """.trimIndent()
                val bytes = json.toByteArray(StandardCharsets.UTF_8)
                ex.sendResponseHeaders(200, bytes.size.toLong())
                ex.responseBody.use { it.write(bytes) }
            }
        }

        val client = JhGatewayHttpClient(
            configProvider = { GatewayEndpointConfig(baseUrl = baseUrl, bearerToken = secret) }
        )

        val models = client.listModels().getOrThrow()
        assertEquals(2, models.size)
        assertEquals("gpt-4.1-mini", models[0].id)
        assertEquals("openai", models[0].provider)
        assertEquals(true, models[0].supportsStreaming)
        assertEquals(true, models[0].supportsToolCalling)
        assertEquals(true, models[0].supportsVision)
        assertEquals(128000, models[0].contextWindow)

        assertEquals("legacy-chat", models[1].id)
        assertEquals("local-vllm", models[1].provider)
        assertEquals(false, models[1].supportsToolCalling)
        assertEquals(null, models[1].supportsVision)

        failWith403 = true
        val errResult = client.listModels()
        assertTrue(errResult.isFailure)
        val apiErr = errResult.exceptionOrNull() as GatewayApiException
        assertTrue(apiErr.agtError is AgtError.GatewayModelUnavailable)
        assertFalse((apiErr.agtError.diagnosticDetail ?: "").contains(secret))
    }

    @Test
    fun `streamChat parses SSE text deltas, tool call deltas, usage, and DONE`() = runBlocking {
        server.createContext("/v1/chat/completions") { ex ->
            ex.responseHeaders.add("Content-Type", "text/event-stream")
            ex.sendResponseHeaders(200, 0)
            ex.responseBody.bufferedWriter(StandardCharsets.UTF_8).use { writer ->
                writer.write("data: {\"choices\":[{\"delta\":{\"content\":\"Hello \"}}]}\n\n")
                writer.write("data: {\"choices\":[{\"delta\":{\"content\":\"World\"}}]}\n\n")
                writer.write("data: {\"choices\":[{\"delta\":{\"tool_calls\":[{\"id\":\"call_1\",\"function\":{\"name\":\"read_file\",\"arguments\":\"{\\\"path\\\":\\\"README.md\\\"}\"}}]}}]}\n\n")
                writer.write("data: {\"usage\":{\"prompt_tokens\":12,\"completion_tokens\":8}}\n\n")
                writer.write("data: [DONE]\n\n")
                writer.flush()
            }
        }

        val client = JhGatewayHttpClient(
            configProvider = { GatewayEndpointConfig(baseUrl = baseUrl, bearerToken = "jh_token_stream_1") }
        )

        val events = client.streamChat(
            GatewayChatRequest(
                modelId = "gpt-4.1-mini",
                messages = listOf("user" to "hi"),
                stream = true,
                protocolMode = GatewayProtocolMode.CHAT_COMPLETIONS,
            )
        ).toList()

        val text = events.filterIsInstance<GatewayStreamEvent.TextDelta>().joinToString("") { it.text }
        assertEquals("Hello World", text)

        val toolCalls = events.filterIsInstance<GatewayStreamEvent.ToolCallDelta>()
        assertEquals(1, toolCalls.size)
        assertEquals("read_file", toolCalls[0].name)
        assertEquals("{\"path\":\"README.md\"}", toolCalls[0].argumentsDelta)

        val completed = events.filterIsInstance<GatewayStreamEvent.Completed>().single()
        assertEquals(12, completed.promptTokens)
        assertEquals(8, completed.completionTokens)
    }

    @Test
    fun `streamChat supports Responses and Anthropic Messages SSE formats and cancellation`() = runBlocking {
        server.createContext("/v1/responses") { ex ->
            ex.responseHeaders.add("Content-Type", "text/event-stream")
            ex.sendResponseHeaders(200, 0)
            ex.responseBody.bufferedWriter(StandardCharsets.UTF_8).use { writer ->
                writer.write("data: {\"type\":\"response.output_text.delta\",\"delta\":\"Resp OK\"}\n\n")
                writer.write("data: [DONE]\n\n")
                writer.flush()
            }
        }
        server.createContext("/v1/messages") { ex ->
            ex.responseHeaders.add("Content-Type", "text/event-stream")
            ex.sendResponseHeaders(200, 0)
            ex.responseBody.bufferedWriter(StandardCharsets.UTF_8).use { writer ->
                writer.write("data: {\"type\":\"content_block_delta\",\"delta\":{\"type\":\"text_delta\",\"text\":\"Msg OK\"}}\n\n")
                writer.write("data: [DONE]\n\n")
                writer.flush()
            }
        }

        val firstChunkSeen = CompletableDeferred<Unit>()
        server.createContext("/hang/v1/chat/completions") { ex ->
            ex.responseHeaders.add("Content-Type", "text/event-stream")
            ex.sendResponseHeaders(200, 0)
            val writer = ex.responseBody.bufferedWriter(StandardCharsets.UTF_8)
            runCatching {
                writer.write("data: {\"choices\":[{\"delta\":{\"content\":\"chunk\"}}]}\n\n")
                writer.flush()
                Thread.sleep(5_000)
            }
            runCatching { writer.close() }
        }

        val client = JhGatewayHttpClient(
            configProvider = { GatewayEndpointConfig(baseUrl = baseUrl, bearerToken = null) }
        )

        val respText = client.streamChat(
            GatewayChatRequest("m1", listOf("user" to "hi"), protocolMode = GatewayProtocolMode.RESPONSES)
        ).toList().filterIsInstance<GatewayStreamEvent.TextDelta>().joinToString("") { it.text }
        assertEquals("Resp OK", respText)

        val msgText = client.streamChat(
            GatewayChatRequest("m2", listOf("user" to "hi"), protocolMode = GatewayProtocolMode.ANTHROPIC_MESSAGES)
        ).toList().filterIsInstance<GatewayStreamEvent.TextDelta>().joinToString("") { it.text }
        assertEquals("Msg OK", msgText)

        val hangClient = JhGatewayHttpClient(
            configProvider = { GatewayEndpointConfig(baseUrl = "$baseUrl/hang", bearerToken = null) }
        )
        val job = launch {
            hangClient.streamChat(
                GatewayChatRequest("m1", listOf("user" to "hi"))
            ).collect { ev ->
                if (ev is GatewayStreamEvent.TextDelta) {
                    firstChunkSeen.complete(Unit)
                }
            }
        }
        withTimeout(3_000) { firstChunkSeen.await() }
        job.cancelAndJoin()
        assertTrue(job.isCancelled)
    }
}
