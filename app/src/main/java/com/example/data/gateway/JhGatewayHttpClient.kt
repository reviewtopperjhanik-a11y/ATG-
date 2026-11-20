package com.example.data.gateway

import com.example.core.common.AgtError
import com.example.core.security.GatewayUrlValidation
import com.example.core.security.GatewayUrlValidator
import com.example.core.security.SecretRedactor
import com.example.domain.contracts.GatewayChatRequest
import com.example.domain.contracts.GatewayClient
import com.example.domain.contracts.GatewayHealth
import com.example.domain.contracts.GatewayStreamEvent
import com.example.domain.model.GatewayHealthStatus
import com.example.domain.model.GatewayModelInfo
import com.example.domain.model.GatewayProtocolMode
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.io.IOException
import java.util.concurrent.TimeUnit

data class GatewayEndpointConfig(
    val baseUrl: String,
    val bearerToken: String?,
)

class GatewayApiException(val agtError: AgtError) : Exception(agtError.userMessage)

/**
 * Real JH GetWay Pro HTTP + SSE client supporting /health, /v1/models, and streaming chat endpoints.
 */
class JhGatewayHttpClient(
    private val configProvider: suspend () -> GatewayEndpointConfig,
    private val okHttpClient: OkHttpClient = defaultOkHttpClient(),
) : GatewayClient {

    override suspend fun health(): GatewayHealth = withContext(Dispatchers.IO) {
        val cfg = configProvider()
        val validation = GatewayUrlValidator.validate(cfg.baseUrl)
        if (validation is GatewayUrlValidation.Invalid) {
            return@withContext GatewayHealth(
                status = GatewayHealthStatus.ERROR,
                message = validation.reason,
                error = AgtError.PermissionDenied(userMessage = validation.reason),
            )
        }
        val baseUrl = (validation as GatewayUrlValidation.Valid).normalizedBaseUrl
        val req = buildRequest(baseUrl = baseUrl, path = "/health", token = cfg.bearerToken).build()
        val startNs = System.nanoTime()
        try {
            okHttpClient.newCall(req).execute().use { resp ->
                val latencyMs = (System.nanoTime() - startNs) / 1_000_000L
                val rawBody = resp.body?.string().orEmpty()
                if (!resp.isSuccessful) {
                    val mapped = mapHttpError(resp.code, rawBody, cfg.bearerToken)
                    return@withContext GatewayHealth(
                        status = GatewayHealthStatus.ERROR,
                        latencyMs = latencyMs,
                        message = mapped.userMessage,
                        error = mapped,
                    )
                }
                val parsedStatus = runCatching {
                    JSONObject(rawBody).optString("status").ifBlank { "ok" }
                }.getOrDefault("ok")
                GatewayHealth(
                    status = GatewayHealthStatus.CONNECTED,
                    latencyMs = latencyMs,
                    message = "Healthy ($parsedStatus) · ${latencyMs}ms",
                )
            }
        } catch (io: IOException) {
            val safeMsg = SecretRedactor.redact(
                io.message ?: "Unable to reach gateway.",
                listOfNotNull(cfg.bearerToken),
            )
            GatewayHealth(
                status = GatewayHealthStatus.ERROR,
                message = safeMsg,
                error = AgtError.NetworkUnavailable(userMessage = safeMsg, diagnosticDetail = safeMsg),
            )
        }
    }

    override suspend fun listModels(): Result<List<GatewayModelInfo>> = withContext(Dispatchers.IO) {
        val cfg = configProvider()
        val validation = GatewayUrlValidator.validate(cfg.baseUrl)
        if (validation is GatewayUrlValidation.Invalid) {
            return@withContext Result.failure(
                GatewayApiException(AgtError.PermissionDenied(userMessage = validation.reason))
            )
        }
        val baseUrl = (validation as GatewayUrlValidation.Valid).normalizedBaseUrl
        val req = buildRequest(baseUrl = baseUrl, path = "/v1/models", token = cfg.bearerToken).build()
        try {
            okHttpClient.newCall(req).execute().use { resp ->
                val rawBody = resp.body?.string().orEmpty()
                if (!resp.isSuccessful) {
                    return@withContext Result.failure(
                        GatewayApiException(mapHttpError(resp.code, rawBody, cfg.bearerToken))
                    )
                }
                val now = System.currentTimeMillis()
                val models = parseModelsResponse(rawBody, now)
                Result.success(models)
            }
        } catch (e: Exception) {
            val safe = SecretRedactor.redact(
                e.message ?: "Failed to query /v1/models",
                listOfNotNull(cfg.bearerToken),
            )
            Result.failure(
                GatewayApiException(AgtError.NetworkUnavailable(userMessage = safe, diagnosticDetail = safe))
            )
        }
    }

    override fun streamChat(request: GatewayChatRequest): Flow<GatewayStreamEvent> = callbackFlow {
        val cfg = configProvider()
        val validation = GatewayUrlValidator.validate(cfg.baseUrl)
        if (validation is GatewayUrlValidation.Invalid) {
            trySend(GatewayStreamEvent.Failed(AgtError.PermissionDenied(userMessage = validation.reason)))
            close()
            return@callbackFlow
        }
        val baseUrl = (validation as GatewayUrlValidation.Valid).normalizedBaseUrl
        val bodyJson = buildChatPayload(request).toString()
        val httpReq = buildRequest(
            baseUrl = baseUrl,
            path = request.protocolMode.path,
            token = cfg.bearerToken,
        )
            .post(bodyJson.toRequestBody(JSON_MEDIA_TYPE))
            .header("Accept", "text/event-stream")
            .build()

        val call = okHttpClient.newCall(httpReq)
        val worker = launch(Dispatchers.IO) {
            try {
                call.execute().use { resp ->
                    if (!resp.isSuccessful) {
                        val errBody = resp.body?.string().orEmpty()
                        trySend(GatewayStreamEvent.Failed(mapHttpError(resp.code, errBody, cfg.bearerToken)))
                        close()
                        return@launch
                    }

                    val source = resp.body?.source()
                    if (source == null) {
                        trySend(
                            GatewayStreamEvent.Failed(
                                AgtError.NetworkUnavailable("Gateway returned empty stream body.")
                            )
                        )
                        close()
                        return@launch
                    }

                    var promptTokens: Int? = null
                    var completionTokens: Int? = null
                    val dataBuffer = StringBuilder()

                    while (isActive && !source.exhausted()) {
                        val line = source.readUtf8Line() ?: break
                        if (line.isEmpty()) {
                            if (dataBuffer.isNotEmpty()) {
                                val payload = dataBuffer.toString().trim()
                                dataBuffer.clear()
                                if (payload == "[DONE]") {
                                    trySend(GatewayStreamEvent.Completed(promptTokens, completionTokens))
                                    close()
                                    return@launch
                                }
                                parseSsePayload(
                                    payload = payload,
                                    protocolMode = request.protocolMode,
                                    onEvent = { ev -> trySend(ev) },
                                    onUsage = { p, c ->
                                        if (p != null) promptTokens = p
                                        if (c != null) completionTokens = c
                                    },
                                    knownToken = cfg.bearerToken,
                                )
                            }
                        } else if (line.startsWith("data:")) {
                            if (dataBuffer.length < MAX_SSE_EVENT_CHARS) {
                                if (dataBuffer.isNotEmpty()) dataBuffer.append('\n')
                                dataBuffer.append(line.removePrefix("data:").trimStart())
                            }
                        }
                    }

                    if (dataBuffer.isNotEmpty()) {
                        val payload = dataBuffer.toString().trim()
                        if (payload != "[DONE]") {
                            parseSsePayload(
                                payload = payload,
                                protocolMode = request.protocolMode,
                                onEvent = { ev -> trySend(ev) },
                                onUsage = { p, c ->
                                    if (p != null) promptTokens = p
                                    if (c != null) completionTokens = c
                                },
                                knownToken = cfg.bearerToken,
                            )
                        }
                    }

                    trySend(GatewayStreamEvent.Completed(promptTokens, completionTokens))
                    close()
                }
            } catch (io: IOException) {
                if (call.isCanceled()) {
                    trySend(GatewayStreamEvent.Failed(AgtError.ProcessCancelled("Stream stopped by user.")))
                } else {
                    val safe = SecretRedactor.redact(
                        io.message ?: "Stream interrupted.",
                        listOfNotNull(cfg.bearerToken),
                    )
                    trySend(GatewayStreamEvent.Failed(AgtError.NetworkUnavailable(userMessage = safe)))
                }
                close()
            }
        }

        awaitClose {
            call.cancel()
            worker.cancel()
        }
    }.flowOn(Dispatchers.IO)

    private fun buildRequest(baseUrl: String, path: String, token: String?): Request.Builder {
        val builder = Request.Builder().url("$baseUrl$path")
        if (!token.isNullOrBlank()) {
            builder.header("Authorization", "Bearer ${token.trim()}")
        }
        return builder
    }

    private fun buildChatPayload(request: GatewayChatRequest): JSONObject {
        val json = JSONObject()
        json.put("model", request.modelId)
        json.put("stream", request.stream)
        return when (request.protocolMode) {
            GatewayProtocolMode.CHAT_COMPLETIONS -> {
                val arr = JSONArray()
                request.messages.forEach { (role, content) ->
                    arr.put(JSONObject().put("role", role).put("content", content))
                }
                json.put("messages", arr)
            }
            GatewayProtocolMode.RESPONSES -> {
                val arr = JSONArray()
                request.messages.forEach { (role, content) ->
                    arr.put(JSONObject().put("role", role).put("content", content))
                }
                json.put("input", arr)
            }
            GatewayProtocolMode.ANTHROPIC_MESSAGES -> {
                json.put("max_tokens", 2048)
                val arr = JSONArray()
                request.messages.filter { it.first != "system" }.forEach { (role, content) ->
                    arr.put(JSONObject().put("role", role).put("content", content))
                }
                json.put("messages", arr)
            }
        }
    }

    private fun parseModelsResponse(rawBody: String, syncedAtMs: Long): List<GatewayModelInfo> {
        val root = JSONObject(rawBody)
        val data = root.optJSONArray("data") ?: return emptyList()
        val result = ArrayList<GatewayModelInfo>(data.length())
        for (i in 0 until data.length()) {
            val item = data.optJSONObject(i) ?: continue
            val id = item.optString("id").trim()
            if (id.isEmpty()) continue
            val provider = item.optString("provider")
                .ifBlank { item.optString("owned_by") }
                .ifBlank { "gateway" }
            val caps = item.optJSONObject("capabilities")
            val streaming = optNullableBoolean(caps, "streaming")
                ?: optNullableBoolean(item, "supports_streaming")
            val tools = optNullableBoolean(caps, "tool_calling")
                ?: optNullableBoolean(caps, "tools")
                ?: optNullableBoolean(item, "supports_tool_calling")
                ?: optNullableBoolean(item, "supports_tools")
            val vision = optNullableBoolean(caps, "vision")
                ?: optNullableBoolean(item, "supports_vision")
            val ctx = (caps?.optInt("context_window", 0)?.takeIf { it > 0 }
                ?: item.optInt("context_window", 0).takeIf { it > 0 }
                ?: item.optInt("context_length", 0).takeIf { it > 0 })
            result.add(
                GatewayModelInfo(
                    id = id,
                    provider = provider,
                    supportsStreaming = streaming,
                    supportsToolCalling = tools,
                    supportsVision = vision,
                    contextWindow = ctx,
                    lastSyncedEpochMs = syncedAtMs,
                )
            )
        }
        return result
    }

    private fun optNullableBoolean(obj: JSONObject?, key: String): Boolean? {
        if (obj == null || !obj.has(key) || obj.isNull(key)) return null
        return obj.optBoolean(key)
    }

    private fun parseSsePayload(
        payload: String,
        protocolMode: GatewayProtocolMode,
        onEvent: (GatewayStreamEvent) -> Unit,
        onUsage: (Int?, Int?) -> Unit,
        knownToken: String?,
    ) {
        val obj = runCatching { JSONObject(payload) }.getOrNull() ?: return
        val errorObj = obj.optJSONObject("error")
        if (errorObj != null) {
            val msg = SecretRedactor.redact(
                errorObj.optString("message").ifBlank { "Gateway stream error." },
                listOfNotNull(knownToken),
            )
            onEvent(GatewayStreamEvent.Failed(AgtError.GatewayModelUnavailable(userMessage = msg)))
            return
        }

        val usage = obj.optJSONObject("usage")
        if (usage != null) {
            val prompt = usage.optInt("prompt_tokens", -1).takeIf { it >= 0 }
                ?: usage.optInt("input_tokens", -1).takeIf { it >= 0 }
            val comp = usage.optInt("completion_tokens", -1).takeIf { it >= 0 }
                ?: usage.optInt("output_tokens", -1).takeIf { it >= 0 }
            onUsage(prompt, comp)
        }

        when (protocolMode) {
            GatewayProtocolMode.CHAT_COMPLETIONS -> {
                val choices = obj.optJSONArray("choices") ?: return
                for (i in 0 until choices.length()) {
                    val choice = choices.optJSONObject(i) ?: continue
                    val delta = choice.optJSONObject("delta") ?: continue
                    if (delta.has("content") && !delta.isNull("content")) {
                        val content = delta.optString("content")
                        if (content.isNotEmpty()) {
                            onEvent(GatewayStreamEvent.TextDelta(content))
                        }
                    }
                    val toolCalls = delta.optJSONArray("tool_calls")
                    if (toolCalls != null) {
                        for (t in 0 until toolCalls.length()) {
                            val tc = toolCalls.optJSONObject(t) ?: continue
                            val tcId = tc.optString("id").ifBlank { "tc_$t" }
                            val fn = tc.optJSONObject("function")
                            val fnName = fn?.optString("name")?.takeIf { it.isNotBlank() }
                            val argsDelta = fn?.optString("arguments").orEmpty()
                            onEvent(
                                GatewayStreamEvent.ToolCallDelta(
                                    toolCallId = tcId,
                                    name = fnName,
                                    argumentsDelta = argsDelta,
                                )
                            )
                        }
                    }
                }
            }
            GatewayProtocolMode.RESPONSES -> {
                val type = obj.optString("type")
                if (type == "response.output_text.delta") {
                    val delta = obj.optString("delta")
                    if (delta.isNotEmpty()) onEvent(GatewayStreamEvent.TextDelta(delta))
                } else if (type == "response.function_call_arguments.delta") {
                    onEvent(
                        GatewayStreamEvent.ToolCallDelta(
                            toolCallId = obj.optString("call_id").ifBlank { "call" },
                            name = obj.optString("name").takeIf { it.isNotBlank() },
                            argumentsDelta = obj.optString("delta"),
                        )
                    )
                }
            }
            GatewayProtocolMode.ANTHROPIC_MESSAGES -> {
                val type = obj.optString("type")
                if (type == "content_block_delta") {
                    val delta = obj.optJSONObject("delta") ?: return
                    when (delta.optString("type")) {
                        "text_delta" -> {
                            val text = delta.optString("text")
                            if (text.isNotEmpty()) onEvent(GatewayStreamEvent.TextDelta(text))
                        }
                        "input_json_delta" -> {
                            onEvent(
                                GatewayStreamEvent.ToolCallDelta(
                                    toolCallId = "block_${obj.optInt("index", 0)}",
                                    name = null,
                                    argumentsDelta = delta.optString("partial_json"),
                                )
                            )
                        }
                    }
                }
            }
        }
    }

    private fun mapHttpError(code: Int, rawBody: String, token: String?): AgtError {
        val rawDetail = runCatching {
            JSONObject(rawBody).optJSONObject("error")?.optString("message")
                ?.ifBlank { null }
                ?: JSONObject(rawBody).optString("message").ifBlank { null }
        }.getOrNull() ?: rawBody.take(240)
        val safeDetail = SecretRedactor.redact(rawDetail, listOfNotNull(token)).ifBlank { null }

        return when (code) {
            401 -> AgtError.GatewayUnauthorized(
                userMessage = "Gateway unauthorized (401). Check your device token.",
                diagnosticDetail = safeDetail,
            )
            403 -> {
                val lower = safeDetail.orEmpty().lowercase()
                if ("model" in lower || "allowlist" in lower) {
                    AgtError.GatewayModelUnavailable(
                        userMessage = "Model is not permitted by gateway policy (403).",
                        diagnosticDetail = safeDetail,
                    )
                } else {
                    AgtError.PermissionDenied(
                        userMessage = "Gateway scope or permission denied (403).",
                        diagnosticDetail = safeDetail,
                    )
                }
            }
            404, 405, 501 -> AgtError.UnsupportedCapability(
                userMessage = "Gateway endpoint or capability unsupported (HTTP $code).",
                diagnosticDetail = safeDetail,
            )
            429 -> AgtError.NetworkUnavailable(
                userMessage = "Gateway or upstream provider rate limited (429).",
                diagnosticDetail = safeDetail,
            )
            else -> AgtError.NetworkUnavailable(
                userMessage = "Gateway returned HTTP $code.",
                diagnosticDetail = safeDetail,
            )
        }
    }

    companion object {
        private val JSON_MEDIA_TYPE = "application/json; charset=utf-8".toMediaType()
        private const val MAX_SSE_EVENT_CHARS = 256 * 1024

        fun defaultOkHttpClient(): OkHttpClient = OkHttpClient.Builder()
            .dns(GatewayUrlValidator.ssrfGuardDns())
            .connectTimeout(15, TimeUnit.SECONDS)
            .readTimeout(60, TimeUnit.SECONDS)
            .writeTimeout(30, TimeUnit.SECONDS)
            .build()
    }
}
