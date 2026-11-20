package com.example.data.extensions

import com.example.core.security.SecretRedactor
import com.example.data.runtime.LocalRuntimeExecutor
import com.example.domain.contracts.ProcessEvent
import com.example.domain.contracts.RuntimeCommand
import com.example.domain.model.McpInitializeResult
import com.example.domain.model.McpToolDescriptor
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.util.UUID

/**
 * Probes local stdio / `npx` MCP servers strictly inside the PRoot Linux rootfs via [LocalRuntimeExecutor]
 * using newline-delimited JSON-RPC (`initialize`, `notifications/initialized`, `tools/list`).
 */
class McpStdioClient(
    private val runtimeExecutor: LocalRuntimeExecutor,
) {
    suspend fun probeServer(
        commandTokens: List<String>,
        workingDirectory: File,
        environment: Map<String, String> = emptyMap(),
        secretsToRedact: List<String> = emptyList(),
        timeoutSeconds: Int = 25,
    ): Result<Pair<McpInitializeResult, List<McpToolDescriptor>>> = withContext(Dispatchers.IO) {
        runCatching {
            val validatedTokens = ExtensionPackageValidator.validateLocalMcpCommandTokens(commandTokens).getOrThrow()
            val quotedCmd = validatedTokens.joinToString(" ") { posixQuote(it) }
            val initReq = JSONObject()
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
                .toString()
                .replace("\\/", "/")
            val initializedNotif = JSONObject()
                .put("jsonrpc", "2.0")
                .put("method", "notifications/initialized")
                .toString()
                .replace("\\/", "/")
            val listToolsReq = JSONObject()
                .put("jsonrpc", "2.0")
                .put("id", 2)
                .put("method", "tools/list")
                .put("params", JSONObject())
                .toString()
                .replace("\\/", "/")

            val boundedTimeout = timeoutSeconds.coerceIn(5, 60)
            val shellScript = "printf '%s\\n%s\\n%s\\n' " +
                "${posixQuote(initReq)} ${posixQuote(initializedNotif)} ${posixQuote(listToolsReq)} " +
                "| timeout ${boundedTimeout}s $quotedCmd"

            val stdoutLines = mutableListOf<String>()
            val stderrLines = mutableListOf<String>()
            var exitCode: Int? = null
            var failureMessage: String? = null

            runtimeExecutor.execute(
                RuntimeCommand(
                    command = shellScript,
                    workingDirectory = workingDirectory.absolutePath,
                    timeoutSeconds = boundedTimeout + 5,
                    processId = "mcp-probe-${UUID.randomUUID().toString().take(8)}",
                    environment = environment,
                    maxOutputLines = 2000,
                )
            ).collect { ev ->
                when (ev) {
                    is ProcessEvent.Started -> Unit
                    is ProcessEvent.Stdout -> stdoutLines += ev.line
                    is ProcessEvent.Stderr -> stderrLines += SecretRedactor.redact(ev.line, secretsToRedact)
                    is ProcessEvent.Exited -> exitCode = ev.exitCode
                    is ProcessEvent.Failed -> failureMessage = ev.error.userMessage
                }
            }

            if (failureMessage != null && stdoutLines.isEmpty()) {
                throw IllegalStateException(failureMessage!!)
            }

            parseJsonRpcResponses(stdoutLines).getOrElse { parseErr ->
                val stderrExcerpt = stderrLines.takeLast(4).joinToString(" | ")
                val extra = if (stderrExcerpt.isNotBlank()) " ($stderrExcerpt)" else ""
                val codeNote = exitCode?.let { " [exit $it]" }.orEmpty()
                throw IllegalStateException("${parseErr.message}$codeNote$extra", parseErr)
            }
        }
    }

    companion object {
        fun parseJsonRpcResponses(
            stdoutLines: List<String>,
        ): Result<Pair<McpInitializeResult, List<McpToolDescriptor>>> = runCatching {
            var initObj: JSONObject? = null
            var toolsObj: JSONObject? = null

            for (rawLine in stdoutLines) {
                val trimmed = rawLine.trim()
                if (!trimmed.startsWith("{")) continue
                val obj = runCatching { JSONObject(trimmed) }.getOrNull() ?: continue
                if (!obj.has("result") && !obj.has("error")) continue

                obj.optJSONObject("error")?.let { errObj ->
                    val code = errObj.optInt("code", -1)
                    val msg = errObj.optString("message", "Unknown stdio MCP JSON-RPC error")
                    throw IllegalStateException("Local MCP error ($code): $msg")
                }

                when (obj.opt("id")) {
                    1, "1" -> initObj = obj
                    2, "2" -> toolsObj = obj
                    else -> {
                        val res = obj.optJSONObject("result")
                        if (res?.has("serverInfo") == true && initObj == null) {
                            initObj = obj
                        } else if (res?.has("tools") == true && toolsObj == null) {
                            toolsObj = obj
                        }
                    }
                }
            }

            val resolvedToolsObj = toolsObj
            val resolvedInitObj = initObj
            require(resolvedInitObj != null || resolvedToolsObj != null) {
                "Local stdio MCP server produced no valid JSON-RPC initialize or tools/list response."
            }

            val initResultObj = resolvedInitObj?.optJSONObject("result") ?: JSONObject()
            val serverInfo = initResultObj.optJSONObject("serverInfo")
            val initResult = McpInitializeResult(
                serverName = serverInfo?.optString("name")?.takeIf { it.isNotBlank() },
                serverVersion = serverInfo?.optString("version")?.takeIf { it.isNotBlank() },
                protocolVersion = initResultObj.optString("protocolVersion").takeIf { it.isNotBlank() },
                sessionId = null,
            )

            val toolsArr = resolvedToolsObj?.optJSONObject("result")?.optJSONArray("tools") ?: JSONArray()
            val tools = mutableListOf<McpToolDescriptor>()
            for (i in 0 until toolsArr.length()) {
                val t = toolsArr.optJSONObject(i) ?: continue
                val name = t.optString("name").trim()
                if (name.isEmpty()) continue
                val desc = t.optString("description").trim()
                val annotations = t.optJSONObject("annotations")
                val destructiveHint = annotations?.optBoolean("destructiveHint", false) == true
                tools += McpToolDescriptor(
                    name = name,
                    description = desc,
                    isDestructive = destructiveHint || McpHttpClient.isDestructiveToolName(name),
                )
            }
            initResult to tools
        }

        private fun posixQuote(value: String): String =
            "'" + value.replace("'", "'\"'\"'") + "'"
    }
}
