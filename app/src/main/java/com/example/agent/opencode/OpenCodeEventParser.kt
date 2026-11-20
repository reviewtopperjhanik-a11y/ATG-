package com.example.agent.opencode

import com.example.core.common.AgtError
import com.example.core.security.SecretRedactor
import com.example.domain.model.PermissionClass
import org.json.JSONObject

sealed interface OpenCodeParsedEvent {
    data class TextDelta(val text: String) : OpenCodeParsedEvent
    data class Status(val message: String) : OpenCodeParsedEvent
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
    ) : OpenCodeParsedEvent

    data class PermissionAsked(
        val approvalId: String,
        val title: String,
        val reason: String,
        val permissionClass: PermissionClass,
        val toolCallId: String?,
    ) : OpenCodeParsedEvent

    data class StepFinished(
        val promptTokens: Int?,
        val completionTokens: Int?,
    ) : OpenCodeParsedEvent

    data class ErrorEvent(
        val error: AgtError,
    ) : OpenCodeParsedEvent
}

enum class ToolUseStatus {
    PENDING,
    RUNNING,
    COMPLETED,
    ERROR,
}

/**
 * Parses OpenCode CLI `--format json` (NDJSON) events into strongly typed AGT structures,
 * classifying tool permission classes and redacting any secrets.
 */
object OpenCodeEventParser {

    fun parseLine(
        rawLine: String,
        knownSecrets: Collection<String> = emptyList(),
    ): OpenCodeParsedEvent? {
        val trimmed = rawLine.trim()
        if (trimmed.isEmpty() || !trimmed.startsWith("{")) {
            return null
        }
        val obj = runCatching { JSONObject(trimmed) }.getOrNull() ?: return null
        val type = obj.optString("type").trim().lowercase()
        val part = obj.optJSONObject("part")

        return when (type) {
            "step_start", "session.started", "step.start" -> {
                val title = part?.optString("title")?.ifBlank { null }
                    ?: obj.optString("title").ifBlank { null }
                    ?: "OpenCode step started"
                OpenCodeParsedEvent.Status(SecretRedactor.redact(title, knownSecrets))
            }

            "text", "text_delta", "message.part.updated" -> {
                val text = part?.optString("text")?.takeIf { it.isNotEmpty() }
                    ?: obj.optString("text").takeIf { it.isNotEmpty() }
                    ?: obj.optString("delta").takeIf { it.isNotEmpty() }
                    ?: return null
                OpenCodeParsedEvent.TextDelta(SecretRedactor.redact(text, knownSecrets))
            }

            "tool_use", "tool_call", "tool_result" -> {
                parseToolEvent(obj, part, knownSecrets)
            }

            "permission", "permission.ask", "approval_required" -> {
                val approvalId = obj.optString("id")
                    .ifBlank { part?.optString("id").orEmpty() }
                    .ifBlank { java.util.UUID.randomUUID().toString() }
                val toolName = obj.optString("tool")
                    .ifBlank { part?.optString("tool").orEmpty() }
                    .ifBlank { "action" }
                val pattern = obj.optString("pattern")
                    .ifBlank { obj.optString("command") }
                    .ifBlank { part?.optString("pattern").orEmpty() }
                val title = obj.optString("title")
                    .ifBlank { "Approve $toolName" }
                val rawReason = obj.optString("reason")
                    .ifBlank { obj.optString("message") }
                    .ifBlank { "OpenCode requested permission for $toolName${if (pattern.isNotBlank()) ": $pattern" else ""}" }
                val permClass = classifyPermission(toolName, pattern)
                OpenCodeParsedEvent.PermissionAsked(
                    approvalId = approvalId,
                    title = SecretRedactor.redact(title, knownSecrets),
                    reason = SecretRedactor.redact(rawReason, knownSecrets),
                    permissionClass = permClass,
                    toolCallId = obj.optString("callID").ifBlank { obj.optString("call_id") }.ifBlank { null },
                )
            }

            "step_finish", "step.finish", "done", "session.idle" -> {
                val tokens = part?.optJSONObject("tokens") ?: obj.optJSONObject("tokens") ?: obj.optJSONObject("usage")
                val input = tokens?.let {
                    it.optInt("input", -1).takeIf { v -> v >= 0 }
                        ?: it.optInt("prompt_tokens", -1).takeIf { v -> v >= 0 }
                }
                val output = tokens?.let {
                    it.optInt("output", -1).takeIf { v -> v >= 0 }
                        ?: it.optInt("completion_tokens", -1).takeIf { v -> v >= 0 }
                }
                OpenCodeParsedEvent.StepFinished(input, output)
            }

            "error", "session.error" -> {
                val errObj = obj.optJSONObject("error") ?: part?.optJSONObject("error")
                val code = errObj?.optString("code").orEmpty().ifBlank { obj.optString("code") }.lowercase()
                val rawMsg = errObj?.optString("message")?.ifBlank { null }
                    ?: obj.optString("message").ifBlank { null }
                    ?: "OpenCode reported an execution error."
                val safeMsg = SecretRedactor.redact(rawMsg, knownSecrets)
                val mapped = when {
                    "unauthorized" in code || "401" in safeMsg ->
                        AgtError.GatewayUnauthorized(userMessage = safeMsg)
                    "unsupported" in code || "tool" in code && "support" in safeMsg.lowercase() ->
                        AgtError.UnsupportedCapability(userMessage = safeMsg)
                    "model" in code || "model" in safeMsg.lowercase() && "unavailable" in safeMsg.lowercase() ->
                        AgtError.GatewayModelUnavailable(userMessage = safeMsg)
                    else ->
                        AgtError.ProcessStartFailed(userMessage = safeMsg)
                }
                OpenCodeParsedEvent.ErrorEvent(mapped)
            }

            else -> null
        }
    }

    private fun parseToolEvent(
        obj: JSONObject,
        part: JSONObject?,
        knownSecrets: Collection<String>,
    ): OpenCodeParsedEvent.ToolUse {
        val stateObj = part?.optJSONObject("state") ?: obj.optJSONObject("state")
        val toolName = part?.optString("tool")?.ifBlank { null }
            ?: obj.optString("tool").ifBlank { null }
            ?: obj.optString("name").ifBlank { "tool" }
        val callId = part?.optString("callID")?.ifBlank { null }
            ?: part?.optString("id")?.ifBlank { null }
            ?: obj.optString("callID").ifBlank { null }
            ?: obj.optString("id").ifBlank { "${toolName}_${System.nanoTime()}" }

        val statusRaw = stateObj?.optString("status")
            ?: obj.optString("status")
        val status = when (statusRaw.trim().lowercase()) {
            "completed", "done", "success" -> ToolUseStatus.COMPLETED
            "error", "failed", "denied" -> ToolUseStatus.ERROR
            "running" -> ToolUseStatus.RUNNING
            else -> if (obj.optString("type").equals("tool_result", ignoreCase = true)) {
                ToolUseStatus.COMPLETED
            } else {
                ToolUseStatus.PENDING
            }
        }

        val inputObj = stateObj?.optJSONObject("input") ?: obj.optJSONObject("input")
        val rawInputText = inputObj?.toString()
            ?: stateObj?.optString("input").orEmpty()
            ?: obj.optString("arguments").orEmpty()
        val safeInput = SecretRedactor.redact(rawInputText.take(600), knownSecrets)

        val rawOutput = stateObj?.optString("output").orEmpty()
            .ifBlank { stateObj?.optString("error").orEmpty() }
            .ifBlank { obj.optString("output").orEmpty() }
        val safeOutput = SecretRedactor.redact(rawOutput.take(800), knownSecrets)

        val filePath = extractAffectedPath(inputObj)
        val cmdText = inputObj?.optString("command").orEmpty()
            .ifBlank { inputObj?.optString("cmd").orEmpty() }
            .ifBlank { safeInput }

        val permClass = classifyPermission(toolName, cmdText)
        val summary = buildToolSummary(toolName, filePath, cmdText, safeInput, knownSecrets)

        return OpenCodeParsedEvent.ToolUse(
            callId = callId,
            toolName = toolName,
            status = status,
            permissionClass = permClass,
            summary = summary,
            argumentsJson = safeInput,
            outputSummary = safeOutput,
            affectedRelativePath = filePath,
            rawArgumentsJson = rawInputText.take(2000),
        )
    }

    private fun extractAffectedPath(inputObj: JSONObject?): String? {
        if (inputObj == null) return null
        val candidates = listOf("filePath", "file_path", "path", "targetPath", "target")
        return candidates.firstNotNullOfOrNull { key ->
            inputObj.optString(key).trim().takeIf { it.isNotEmpty() }
        }
    }

    private fun buildToolSummary(
        toolName: String,
        filePath: String?,
        cmdText: String,
        fallbackInput: String,
        knownSecrets: Collection<String>,
    ): String {
        val raw = when {
            filePath != null -> filePath
            cmdText.isNotBlank() -> cmdText.take(180)
            fallbackInput.isNotBlank() -> fallbackInput.take(180)
            else -> "Executing $toolName"
        }
        return SecretRedactor.redact(raw, knownSecrets)
    }

    fun classifyPermission(toolName: String, rawArgumentText: String): PermissionClass {
        val tool = toolName.trim().lowercase()
        val arg = rawArgumentText.lowercase()
        return when {
            tool in setOf("read", "glob", "grep", "list", "ls", "find", "todoread", "view") ->
                PermissionClass.READ_ONLY

            tool in setOf("edit", "write", "patch", "multiedit", "todowrite", "create_file") ->
                PermissionClass.PROJECT_WRITE

            tool in setOf("webfetch", "websearch", "http") || tool.startsWith("mcp_") -> {
                if ("delete" in arg || "drop" in arg || "destroy" in arg) {
                    PermissionClass.DESTRUCTIVE
                } else {
                    PermissionClass.NETWORK_ACCESS
                }
            }

            tool in setOf("bash", "shell", "exec", "terminal") -> when {
                isDeployCommand(arg) -> PermissionClass.PRODUCTION_DEPLOYMENT
                isExternalPushCommand(arg) -> PermissionClass.EXTERNAL_ACCOUNT_ACTION
                isDestructiveCommand(arg) -> PermissionClass.DESTRUCTIVE
                else -> PermissionClass.LOCAL_EXECUTION
            }

            else -> PermissionClass.LOCAL_EXECUTION
        }
    }

    private fun isDestructiveCommand(cmd: String): Boolean {
        val patterns = listOf(
            "rm -rf", "rm -fr", "mkfs", "dd if=", "git reset --hard",
            "git clean -fd", "chmod -r 777", ":(){", "shutdown", "reboot",
        )
        return patterns.any { it in cmd }
    }

    private fun isExternalPushCommand(cmd: String): Boolean =
        "git push" in cmd || "gh pr create" in cmd || "gh repo delete" in cmd

    private fun isDeployCommand(cmd: String): Boolean =
        "firebase deploy" in cmd || "vercel --prod" in cmd || "netlify deploy --prod" in cmd || "kubectl apply" in cmd
}
