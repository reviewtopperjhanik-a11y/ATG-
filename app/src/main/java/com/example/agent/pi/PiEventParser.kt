package com.example.agent.pi

import com.example.agent.opencode.OpenCodeEventParser
import com.example.agent.opencode.ToolUseStatus
import com.example.core.common.AgtError
import com.example.core.security.SecretRedactor
import com.example.domain.model.PermissionClass
import org.json.JSONArray
import org.json.JSONObject

sealed interface PiParsedEvent {
    data class Status(val message: String) : PiParsedEvent
    data class TextDelta(val text: String) : PiParsedEvent
    data class ToolUse(
        val callId: String,
        val toolName: String,
        val status: ToolUseStatus,
        val permissionClass: PermissionClass,
        val summary: String,
        val argumentsJson: String,
        val outputSummary: String,
        val affectedRelativePath: String?,
        val rawArgumentsJson: String = argumentsJson,
    ) : PiParsedEvent

    data class StepFinished(
        val promptTokens: Int?,
        val completionTokens: Int?,
    ) : PiParsedEvent

    data class ErrorEvent(
        val error: AgtError,
    ) : PiParsedEvent
}

/**
 * Parses Pi Coding Agent CLI `--mode json` NDJSON stdout events into strongly typed AGT events,
 * classifying tool permission classes (`bash`, `edit`, `write`, `read`, `grep`, `find`, `ls`, etc.)
 * and redacting any secrets.
 */
object PiEventParser {

    fun parseLine(
        rawLine: String,
        knownSecrets: Collection<String> = emptyList(),
    ): PiParsedEvent? {
        val trimmed = rawLine.trim()
        if (trimmed.isEmpty() || !trimmed.startsWith("{")) {
            return null
        }
        val obj = runCatching { JSONObject(trimmed) }.getOrNull() ?: return null
        val type = obj.optString("type").trim().lowercase()

        return when (type) {
            "session" -> {
                val sid = obj.optString("id").ifBlank { "active" }
                PiParsedEvent.Status("Pi Coding Agent session ($sid)")
            }

            "agent_start" -> PiParsedEvent.Status("Pi agent turn started")

            "turn_start" -> null

            "message_start" -> null

            "message_update" -> parseMessageUpdate(obj, knownSecrets)

            "message_end" -> parseMessageEnd(obj, knownSecrets)

            "tool_execution_start" -> parseToolStart(obj, knownSecrets)

            "tool_execution_update" -> parseToolUpdate(obj, knownSecrets)

            "tool_execution_end" -> parseToolEnd(obj, knownSecrets)

            "turn_end", "agent_end" -> parseTurnOrAgentEnd(obj, knownSecrets)

            "error", "agent_error" -> {
                val errObj = obj.optJSONObject("error")
                val rawMsg = errObj?.optString("message")?.ifBlank { null }
                    ?: obj.optString("errorMessage").ifBlank { null }
                    ?: obj.optString("message").ifBlank { null }
                    ?: "Pi Coding Agent reported an execution error."
                PiParsedEvent.ErrorEvent(mapError(rawMsg, errObj?.optString("code").orEmpty(), knownSecrets))
            }

            else -> null
        }
    }

    private fun parseMessageUpdate(
        obj: JSONObject,
        knownSecrets: Collection<String>,
    ): PiParsedEvent? {
        val assistantEvent = obj.optJSONObject("assistantMessageEvent") ?: obj.optJSONObject("event") ?: obj
        val subType = assistantEvent.optString("type").trim().lowercase()
        return when (subType) {
            "text_delta", "text" -> {
                val delta = assistantEvent.optString("delta").takeIf { it.isNotEmpty() }
                    ?: assistantEvent.optString("text").takeIf { it.isNotEmpty() }
                    ?: return null
                PiParsedEvent.TextDelta(SecretRedactor.redact(delta, knownSecrets))
            }
            "error" -> {
                val msg = assistantEvent.optString("errorMessage").ifBlank { null }
                    ?: assistantEvent.optString("reason").ifBlank { null }
                    ?: "Pi model stream failed."
                PiParsedEvent.ErrorEvent(mapError(msg, "", knownSecrets))
            }
            else -> null
        }
    }

    private fun parseMessageEnd(
        obj: JSONObject,
        knownSecrets: Collection<String>,
    ): PiParsedEvent? {
        val message = obj.optJSONObject("message") ?: return null
        val stopReason = message.optString("stopReason").trim().lowercase()
        if (stopReason == "error" || stopReason == "aborted") {
            val rawErr = message.optString("errorMessage").ifBlank { null }
                ?: "Pi execution ${if (stopReason == "aborted") "aborted" else "failed"}."
            val err = if (stopReason == "aborted") {
                AgtError.ProcessCancelled(SecretRedactor.redact(rawErr, knownSecrets))
            } else {
                mapError(rawErr, "", knownSecrets)
            }
            return PiParsedEvent.ErrorEvent(err)
        }
        val usage = message.optJSONObject("usage") ?: return null
        val (input, output) = extractUsage(usage)
        return if (input != null || output != null) {
            PiParsedEvent.StepFinished(input, output)
        } else {
            null
        }
    }

    private fun parseToolStart(
        obj: JSONObject,
        knownSecrets: Collection<String>,
    ): PiParsedEvent.ToolUse {
        val toolName = obj.optString("toolName").ifBlank { obj.optString("name") }.ifBlank { "tool" }
        val callId = obj.optString("toolCallId").ifBlank { obj.optString("id") }
            .ifBlank { "${toolName}_${System.nanoTime()}" }
        val argsObj = obj.optJSONObject("args") ?: obj.optJSONObject("input")
        val rawArgsText = argsObj?.toString()
            ?: obj.optString("args").takeIf { it.isNotBlank() }
            ?: "{}"
        val safeArgs = SecretRedactor.redact(rawArgsText.take(600), knownSecrets)
        val filePath = extractAffectedPath(argsObj)
        val cmdText = argsObj?.optString("command").orEmpty()
            .ifBlank { argsObj?.optString("cmd").orEmpty() }
            .ifBlank { safeArgs }
        val permClass = OpenCodeEventParser.classifyPermission(toolName, cmdText)
        val summary = buildSummary(toolName, filePath, cmdText, safeArgs, knownSecrets)
        return PiParsedEvent.ToolUse(
            callId = callId,
            toolName = toolName,
            status = ToolUseStatus.RUNNING,
            permissionClass = permClass,
            summary = summary,
            argumentsJson = safeArgs,
            outputSummary = "",
            affectedRelativePath = filePath,
            rawArgumentsJson = rawArgsText.take(2000),
        )
    }

    private fun parseToolUpdate(
        obj: JSONObject,
        knownSecrets: Collection<String>,
    ): PiParsedEvent.ToolUse {
        val toolName = obj.optString("toolName").ifBlank { obj.optString("name") }.ifBlank { "tool" }
        val callId = obj.optString("toolCallId").ifBlank { obj.optString("id") }
            .ifBlank { "${toolName}_${System.nanoTime()}" }
        val argsObj = obj.optJSONObject("args")
        val filePath = extractAffectedPath(argsObj)
        val partialText = extractResultText(obj.opt("partialResult"))
        val safeOutput = SecretRedactor.redact(partialText.take(800), knownSecrets)
        val cmdText = argsObj?.optString("command").orEmpty()
        val permClass = OpenCodeEventParser.classifyPermission(toolName, cmdText)
        val rawArgs = argsObj?.toString().orEmpty()
        return PiParsedEvent.ToolUse(
            callId = callId,
            toolName = toolName,
            status = ToolUseStatus.RUNNING,
            permissionClass = permClass,
            summary = buildSummary(toolName, filePath, cmdText, rawArgs, knownSecrets),
            argumentsJson = SecretRedactor.redact(rawArgs.take(600), knownSecrets),
            outputSummary = safeOutput,
            affectedRelativePath = filePath,
            rawArgumentsJson = rawArgs.take(2000),
        )
    }

    private fun parseToolEnd(
        obj: JSONObject,
        knownSecrets: Collection<String>,
    ): PiParsedEvent.ToolUse {
        val toolName = obj.optString("toolName").ifBlank { obj.optString("name") }.ifBlank { "tool" }
        val callId = obj.optString("toolCallId").ifBlank { obj.optString("id") }
            .ifBlank { "${toolName}_${System.nanoTime()}" }
        val isError = obj.optBoolean("isError", false)
        val argsObj = obj.optJSONObject("args")
        val filePath = extractAffectedPath(argsObj)
        val cmdText = argsObj?.optString("command").orEmpty()
        val rawArgs = argsObj?.toString().orEmpty()
        val safeArgs = SecretRedactor.redact(rawArgs.take(600), knownSecrets)
        val resultText = extractResultText(obj.opt("result"))
            .ifBlank { obj.optString("output").orEmpty() }
            .ifBlank { obj.optString("error").orEmpty() }
        val safeOutput = SecretRedactor.redact(resultText.take(800), knownSecrets)
        val permClass = OpenCodeEventParser.classifyPermission(toolName, cmdText)
        return PiParsedEvent.ToolUse(
            callId = callId,
            toolName = toolName,
            status = if (isError) ToolUseStatus.ERROR else ToolUseStatus.COMPLETED,
            permissionClass = permClass,
            summary = buildSummary(toolName, filePath, cmdText, safeArgs, knownSecrets),
            argumentsJson = safeArgs,
            outputSummary = safeOutput,
            affectedRelativePath = filePath,
            rawArgumentsJson = rawArgs.take(2000),
        )
    }

    private fun parseTurnOrAgentEnd(
        obj: JSONObject,
        knownSecrets: Collection<String>,
    ): PiParsedEvent? {
        val message = obj.optJSONObject("message")
        if (message != null) {
            val stopReason = message.optString("stopReason").trim().lowercase()
            if (stopReason == "error") {
                val rawErr = message.optString("errorMessage").ifBlank { "Pi execution failed." }
                return PiParsedEvent.ErrorEvent(mapError(rawErr, "", knownSecrets))
            }
            val usage = message.optJSONObject("usage")
            if (usage != null) {
                val (input, output) = extractUsage(usage)
                if (input != null || output != null) {
                    return PiParsedEvent.StepFinished(input, output)
                }
            }
        }
        val messages = obj.optJSONArray("messages")
        if (messages != null && messages.length() > 0) {
            for (i in messages.length() - 1 downTo 0) {
                val msg = messages.optJSONObject(i) ?: continue
                if (msg.optString("role") == "assistant") {
                    val stopReason = msg.optString("stopReason").trim().lowercase()
                    if (stopReason == "error") {
                        val rawErr = msg.optString("errorMessage").ifBlank { "Pi execution failed." }
                        return PiParsedEvent.ErrorEvent(mapError(rawErr, "", knownSecrets))
                    }
                    val usage = msg.optJSONObject("usage")
                    if (usage != null) {
                        val (input, output) = extractUsage(usage)
                        if (input != null || output != null) {
                            return PiParsedEvent.StepFinished(input, output)
                        }
                    }
                    break
                }
            }
        }
        return null
    }

    private fun extractUsage(usage: JSONObject): Pair<Int?, Int?> {
        val input = usage.optInt("input", -1).takeIf { it >= 0 }
            ?: usage.optInt("inputTokens", -1).takeIf { it >= 0 }
            ?: usage.optInt("prompt_tokens", -1).takeIf { it >= 0 }
        val output = usage.optInt("output", -1).takeIf { it >= 0 }
            ?: usage.optInt("outputTokens", -1).takeIf { it >= 0 }
            ?: usage.optInt("completion_tokens", -1).takeIf { it >= 0 }
        return input to output
    }

    private fun extractResultText(rawResult: Any?): String {
        return when (rawResult) {
            is String -> rawResult
            is JSONObject -> {
                val content = rawResult.optJSONArray("content")
                if (content != null) {
                    extractContentArrayText(content)
                } else {
                    rawResult.optString("text")
                        .ifBlank { rawResult.optString("output") }
                        .ifBlank { rawResult.toString() }
                }
            }
            is JSONArray -> extractContentArrayText(rawResult)
            else -> ""
        }
    }

    private fun extractContentArrayText(arr: JSONArray): String = buildString {
        for (i in 0 until arr.length()) {
            val item = arr.optJSONObject(i) ?: continue
            val text = item.optString("text")
            if (text.isNotEmpty()) {
                if (isNotEmpty()) append('\n')
                append(text)
            }
        }
    }

    private fun extractAffectedPath(argsObj: JSONObject?): String? {
        if (argsObj == null) return null
        val candidates = listOf("path", "file_path", "filePath", "targetPath", "target")
        return candidates.firstNotNullOfOrNull { key ->
            argsObj.optString(key).trim().takeIf { it.isNotEmpty() }
        }
    }

    private fun buildSummary(
        toolName: String,
        filePath: String?,
        cmdText: String,
        fallbackArgs: String,
        knownSecrets: Collection<String>,
    ): String {
        val raw = when {
            filePath != null -> filePath
            cmdText.isNotBlank() -> cmdText.take(180)
            fallbackArgs.isNotBlank() && fallbackArgs != "{}" -> fallbackArgs.take(180)
            else -> "Executing $toolName"
        }
        return SecretRedactor.redact(raw, knownSecrets)
    }

    private fun mapError(
        rawMsg: String,
        rawCode: String,
        knownSecrets: Collection<String>,
    ): AgtError {
        val safeMsg = SecretRedactor.redact(rawMsg, knownSecrets)
        val code = rawCode.lowercase()
        val lower = safeMsg.lowercase()
        return when {
            "unauthorized" in code || "401" in lower || "invalid api key" in lower ->
                AgtError.GatewayUnauthorized(userMessage = safeMsg)
            "unsupported" in code || ("tool" in lower && "support" in lower) ->
                AgtError.UnsupportedCapability(userMessage = safeMsg)
            "model" in code || ("model" in lower && ("not found" in lower || "unavailable" in lower)) ->
                AgtError.GatewayModelUnavailable(userMessage = safeMsg)
            else ->
                AgtError.ProcessStartFailed(userMessage = safeMsg)
        }
    }
}
