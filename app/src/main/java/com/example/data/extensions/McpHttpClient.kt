package com.example.data.extensions

import com.example.core.security.GatewayUrlValidation
import com.example.core.security.SecretRedactor
import com.example.data.gateway.JhGatewayHttpClient
import com.example.domain.model.McpInitializeResult
import com.example.domain.model.McpToolCallResult
import com.example.domain.model.McpToolDescriptor
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject

/**
 * Real JSON-RPC 2.0 HTTP/SSE client for remote/loopback MCP servers.
 * Supports both `application/json` and `text/event-stream` responses per the
 * Model Context Protocol Streamable HTTP / SSE transport specification.
 */
class McpHttpClient(
    private val okHttpClient: OkHttpClient = JhGatewayHttpClient.defaultOkHttpClient(),
) {
    private val jsonMediaType = "application/json; charset=utf-8".toMediaType()

    suspend fun initialize(
        endpointUrl: String,
        bearerToken: String? = null,
    ): Result<McpInitializeResult> = withContext(Dispatchers.IO) {
        runCatching {
            val validated = validateEndpoint(endpointUrl)
            val payload = JSONObject()
                .put("jsonrpc", "2.0")
                .put("id", 1)
                .put("method", "initialize")
                .put(
                    "params",
                    JSONObject()
                        .put("protocolVersion", "2024-11-05")
                        .put("capabilities", JSONObject())
                        .put(
                            "clientInfo",
                            JSONObject()
                                .put("name", "AGT-Code")
                                .put("version", "1.0.0")
                        )
                )
            val (rpcObj, sessionId) = executeJsonRpc(
                endpointUrl = validated.normalizedBaseUrl,
                payload = payload,
                bearerToken = bearerToken,
                sessionId = null,
            )
            val resultObj = rpcObj.optJSONObject("result") ?: JSONObject()
            val serverInfo = resultObj.optJSONObject("serverInfo")
            val initResult = McpInitializeResult(
                serverName = serverInfo?.optString("name")?.takeIf { it.isNotBlank() },
                serverVersion = serverInfo?.optString("version")?.takeIf { it.isNotBlank() },
                protocolVersion = resultObj.optString("protocolVersion").takeIf { it.isNotBlank() },
                sessionId = sessionId,
            )
            // Best-effort notifications/initialized
            runCatching {
                val notifPayload = JSONObject()
                    .put("jsonrpc", "2.0")
                    .put("method", "notifications/initialized")
                sendNotification(
                    endpointUrl = validated.normalizedBaseUrl,
                    payload = notifPayload,
                    bearerToken = bearerToken,
                    sessionId = sessionId,
                )
            }
            initResult
        }
    }

    suspend fun listTools(
        endpointUrl: String,
        bearerToken: String? = null,
        sessionId: String? = null,
    ): Result<List<McpToolDescriptor>> = withContext(Dispatchers.IO) {
        runCatching {
            val validated = validateEndpoint(endpointUrl)
            val payload = JSONObject()
                .put("jsonrpc", "2.0")
                .put("id", 2)
                .put("method", "tools/list")
                .put("params", JSONObject())
            val (rpcObj, _) = executeJsonRpc(
                endpointUrl = validated.normalizedBaseUrl,
                payload = payload,
                bearerToken = bearerToken,
                sessionId = sessionId,
            )
            val resultObj = rpcObj.optJSONObject("result") ?: JSONObject()
            val toolsArr = resultObj.optJSONArray("tools") ?: JSONArray()
            val out = mutableListOf<McpToolDescriptor>()
            for (i in 0 until toolsArr.length()) {
                val t = toolsArr.optJSONObject(i) ?: continue
                val name = t.optString("name").trim()
                if (name.isEmpty()) continue
                val desc = t.optString("description").trim()
                val annotations = t.optJSONObject("annotations")
                val destructiveHint = annotations?.optBoolean("destructiveHint", false) == true
                out += McpToolDescriptor(
                    name = name,
                    description = desc,
                    isDestructive = destructiveHint || isDestructiveToolName(name),
                )
            }
            out
        }
    }

    suspend fun callTool(
        endpointUrl: String,
        toolName: String,
        argumentsJson: String,
        bearerToken: String? = null,
        sessionId: String? = null,
    ): Result<McpToolCallResult> = withContext(Dispatchers.IO) {
        runCatching {
            val validated = validateEndpoint(endpointUrl)
            val argsObj = runCatching {
                val trimmed = argumentsJson.trim()
                if (trimmed.isEmpty()) JSONObject() else JSONObject(trimmed)
            }.getOrDefault(JSONObject())
            val payload = JSONObject()
                .put("jsonrpc", "2.0")
                .put("id", 3)
                .put("method", "tools/call")
                .put(
                    "params",
                    JSONObject()
                        .put("name", toolName)
                        .put("arguments", argsObj)
                )
            val (rpcObj, _) = executeJsonRpc(
                endpointUrl = validated.normalizedBaseUrl,
                payload = payload,
                bearerToken = bearerToken,
                sessionId = sessionId,
            )
            val resultObj = rpcObj.optJSONObject("result") ?: JSONObject()
            val isError = resultObj.optBoolean("isError", false)
            val contentArr = resultObj.optJSONArray("content")
            val text = if (contentArr != null && contentArr.length() > 0) {
                buildString {
                    for (i in 0 until contentArr.length()) {
                        val part = contentArr.optJSONObject(i) ?: continue
                        val partText = part.optString("text").ifBlank { part.toString() }
                        if (partText.isNotBlank()) {
                            if (isNotEmpty()) append('\n')
                            append(partText)
                        }
                    }
                }
            } else {
                resultObj.toString()
            }
            McpToolCallResult(
                isError = isError,
                outputText = SecretRedactor.redact(text, listOfNotNull(bearerToken)),
            )
        }
    }

    private fun validateEndpoint(endpointUrl: String): GatewayUrlValidation.Valid {
        return when (val v = ExtensionPackageValidator.validateMcpEndpoint(endpointUrl)) {
            is GatewayUrlValidation.Valid -> v
            is GatewayUrlValidation.Invalid -> throw IllegalArgumentException(v.reason)
        }
    }

    private fun sendNotification(
        endpointUrl: String,
        payload: JSONObject,
        bearerToken: String?,
        sessionId: String?,
    ) {
        val builder = Request.Builder()
            .url(endpointUrl)
            .post(payload.toString().toRequestBody(jsonMediaType))
            .header("Accept", "application/json, text/event-stream")
            .header("Content-Type", "application/json")
        if (!bearerToken.isNullOrBlank()) {
            builder.header("Authorization", "Bearer $bearerToken")
        }
        if (!sessionId.isNullOrBlank()) {
            builder.header("Mcp-Session-Id", sessionId)
        }
        okHttpClient.newCall(builder.build()).execute().close()
    }

    private fun executeJsonRpc(
        endpointUrl: String,
        payload: JSONObject,
        bearerToken: String?,
        sessionId: String?,
    ): Pair<JSONObject, String?> {
        val builder = Request.Builder()
            .url(endpointUrl)
            .post(payload.toString().toRequestBody(jsonMediaType))
            .header("Accept", "application/json, text/event-stream")
            .header("Content-Type", "application/json")
        if (!bearerToken.isNullOrBlank()) {
            builder.header("Authorization", "Bearer $bearerToken")
        }
        if (!sessionId.isNullOrBlank()) {
            builder.header("Mcp-Session-Id", sessionId)
        }
        val secretList = listOfNotNull(bearerToken)
        okHttpClient.newCall(builder.build()).execute().use { resp ->
            val rawBody = resp.body?.string().orEmpty()
            val returnedSessionId = resp.header("Mcp-Session-Id")?.takeIf { it.isNotBlank() } ?: sessionId
            if (!resp.isSuccessful) {
                val safeExcerpt = SecretRedactor.redact(rawBody.take(300), secretList)
                throw IllegalStateException("MCP HTTP ${resp.code}: ${safeExcerpt.ifBlank { resp.message }}")
            }
            val contentType = resp.header("Content-Type").orEmpty().lowercase()
            val jsonObj = if (contentType.contains("text/event-stream") || rawBody.lstrip().startsWith("event:") || rawBody.lstrip().startsWith("data:")) {
                parseSseJsonRpc(rawBody)
            } else {
                JSONObject(rawBody)
            }
            jsonObj.optJSONObject("error")?.let { errObj ->
                val code = errObj.optInt("code", -1)
                val msg = SecretRedactor.redact(errObj.optString("message", "Unknown MCP JSON-RPC error"), secretList)
                throw IllegalStateException("MCP error ($code): $msg")
            }
            return jsonObj to returnedSessionId
        }
    }

    private fun parseSseJsonRpc(sseText: String): JSONObject {
        val dataLines = mutableListOf<String>()
        var lastParsed: JSONObject? = null
        for (line in sseText.lines()) {
            when {
                line.startsWith("data:") -> {
                    val payload = line.removePrefix("data:").trim()
                    if (payload.isNotEmpty() && payload != "[DONE]") {
                        dataLines += payload
                    }
                }
                line.isBlank() && dataLines.isNotEmpty() -> {
                    val candidate = dataLines.joinToString("\n")
                    dataLines.clear()
                    runCatching { JSONObject(candidate) }.getOrNull()?.let { obj ->
                        if (obj.has("result") || obj.has("error") || obj.has("jsonrpc")) {
                            lastParsed = obj
                        }
                    }
                }
            }
        }
        if (dataLines.isNotEmpty()) {
            val candidate = dataLines.joinToString("\n")
            runCatching { JSONObject(candidate) }.getOrNull()?.let { obj ->
                if (obj.has("result") || obj.has("error") || obj.has("jsonrpc")) {
                    lastParsed = obj
                }
            }
        }
        return lastParsed ?: throw IllegalStateException("MCP SSE response contained no valid JSON-RPC payload.")
    }

    private fun String.lstrip(): String = trimStart()

    companion object {
        fun isDestructiveToolName(name: String): Boolean {
            val lower = name.lowercase()
            return listOf("delete", "destroy", "drop", "remove", "force_push", "reset_hard", "truncate")
                .any { it in lower }
        }
    }
}
