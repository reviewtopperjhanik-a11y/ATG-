package com.example.agent.tools

import com.example.core.common.AgtError
import com.example.core.common.ProjectPathValidator
import com.example.core.security.SecretRedactor
import com.example.domain.model.ActiveExtensionPolicy
import com.example.domain.model.AppPreferencesState
import com.example.domain.model.PermissionClass
import com.example.domain.model.ToolAuditEntry
import com.example.domain.model.ToolBrokerDecision
import com.example.domain.model.ToolDefinition
import org.json.JSONObject
import java.io.File
import java.util.Collections

/**
 * Mediates and validates every agent-requested tool call before execution or UI approval.
 *
 * Enforces:
 * - Tool schema and required argument validation
 * - Project-root confinement (rejects '..' traversal and out-of-root paths)
 * - Sensitive credential file & secret exfiltration blocking
 * - Active extension/MCP server authorization & permission checks
 * - Policy-based permission classification and approval gating
 * - Secret-redacted audit trail
 */
class ToolBroker(
    private val projectRootPath: String,
    private val extensionPolicyProvider: () -> ActiveExtensionPolicy = { ActiveExtensionPolicy() },
    private val knownSecretsProvider: () -> Collection<String> = { emptyList() },
    private val onAuditRecorded: ((ToolAuditEntry) -> Unit)? = null,
) {
    private val auditLog = Collections.synchronizedList(mutableListOf<ToolAuditEntry>())

    fun getAuditTrail(): List<ToolAuditEntry> = synchronized(auditLog) { auditLog.toList() }

    fun evaluateToolCall(
        toolCallId: String,
        rawToolName: String,
        rawArgumentsJson: String,
        preferences: AppPreferencesState,
        alreadyApproved: Boolean = false,
    ): ToolBrokerDecision {
        val toolName = rawToolName.trim().lowercase()
        val knownSecrets = knownSecretsProvider()
        val extPolicy = extensionPolicyProvider()
        val definition = resolveDefinition(toolName)
            ?: return reject(
                toolCallId = toolCallId,
                toolName = rawToolName,
                permissionClass = PermissionClass.LOCAL_EXECUTION,
                error = AgtError.PermissionDenied("Tool '$rawToolName' is not registered in the AGT Tool Broker."),
            )

        if (definition.toolId.startsWith("mcp.") && extPolicy.enforceMcpAllowlist) {
            val isAllowedByName = toolName in extPolicy.enabledMcpToolNames
            val isAllowedByPrefix = extPolicy.enabledMcpPrefixes.any { prefix -> toolName.startsWith(prefix) }
            val hasExplicitToolsForPrefix = extPolicy.enabledMcpToolNames.any { it.startsWith("mcp_") }
            val authorized = if (hasExplicitToolsForPrefix) {
                isAllowedByName
            } else {
                isAllowedByPrefix || isAllowedByName
            }
            if (!authorized) {
                return reject(
                    toolCallId = toolCallId,
                    toolName = rawToolName,
                    permissionClass = PermissionClass.NETWORK_ACCESS,
                    error = AgtError.PermissionDenied(
                        "MCP server or tool '$rawToolName' is disabled, uninstalled, or lacks granted permission."
                    ),
                )
            }
        }

        val parsedArgs = parseJsonArguments(rawArgumentsJson)
        if (rawArgumentsJson.isNotBlank() && parsedArgs == null && definition.requiredArgs.isNotEmpty()) {
            return reject(
                toolCallId = toolCallId,
                toolName = rawToolName,
                permissionClass = definition.permissionClass,
                error = AgtError.PermissionDenied("Malformed JSON arguments for tool '$rawToolName'."),
            )
        }

        // Validate file-path confinement for filesystem tools
        if (definition.toolId in FILE_TOOLS) {
            val pathArg = extractPathArg(parsedArgs)
            if (pathArg != null) {
                val rootFile = File(projectRootPath)
                val resolved = ProjectPathValidator.resolveWithinRoot(rootFile, pathArg)
                if (resolved.isFailure) {
                    return reject(
                        toolCallId = toolCallId,
                        toolName = rawToolName,
                        permissionClass = definition.permissionClass,
                        error = AgtError.ProjectPathRejected(
                            "Blocked path outside project root: ${SecretRedactor.redact(pathArg, knownSecrets)}"
                        ),
                    )
                }
            }
        }

        // Evaluate shell command safety & permission escalation
        var effectivePermission = definition.permissionClass
        val commandText = extractCommandArg(parsedArgs, rawArgumentsJson)
        if (definition.toolId == "exec.run") {
            if (isCredentialExfiltrationAttempt(commandText)) {
                return reject(
                    toolCallId = toolCallId,
                    toolName = rawToolName,
                    permissionClass = PermissionClass.DESTRUCTIVE,
                    error = AgtError.PermissionDenied("Blocked command attempting to access protected app secrets or keystore."),
                )
            }
            effectivePermission = classifyCommandPermission(commandText)
        } else if (definition.toolId.startsWith("mcp.")) {
            if (toolName in extPolicy.destructiveMcpTools || isDangerousMcpArgs(commandText)) {
                effectivePermission = PermissionClass.DESTRUCTIVE
            }
        }

        val sanitizedSummary = SecretRedactor.redact(
            commandText.ifBlank { extractPathArg(parsedArgs) ?: definition.description }.take(200),
            knownSecrets,
        )

        val needsApproval = !alreadyApproved && requiresInteractiveApproval(
            permissionClass = effectivePermission,
            toolId = definition.toolId,
            preferences = preferences,
        )

        val effectiveDef = definition.copy(permissionClass = effectivePermission)
        return if (needsApproval) {
            recordAudit(toolCallId, rawToolName, effectivePermission, "AWAITING_APPROVAL", sanitizedSummary)
            ToolBrokerDecision.RequiresApproval(
                definition = effectiveDef,
                reason = "Policy requires explicit approval for ${effectivePermission.name}: $sanitizedSummary",
                sanitizedSummary = sanitizedSummary,
            )
        } else {
            recordAudit(toolCallId, rawToolName, effectivePermission, "ALLOWED", sanitizedSummary)
            ToolBrokerDecision.Allowed(
                definition = effectiveDef,
                sanitizedSummary = sanitizedSummary,
            )
        }
    }

    private fun resolveDefinition(toolName: String): ToolDefinition? {
        if (toolName.startsWith("mcp_")) {
            return ToolDefinition(
                toolId = "mcp.$toolName",
                aliases = setOf(toolName),
                permissionClass = PermissionClass.NETWORK_ACCESS,
                timeoutSeconds = 60,
                maxOutputBytes = 64 * 1024,
                description = "MCP tool invocation ($toolName)",
            )
        }
        return STANDARD_TOOLS.firstOrNull { def ->
            def.toolId == toolName || toolName in def.aliases
        }
    }

    private fun parseJsonArguments(raw: String): JSONObject? {
        val trimmed = raw.trim()
        if (trimmed.isEmpty() || !trimmed.startsWith("{")) return null
        return runCatching { JSONObject(trimmed) }.getOrNull()
    }

    private fun extractPathArg(obj: JSONObject?): String? {
        if (obj == null) return null
        val keys = listOf("filePath", "file_path", "path", "targetPath", "target")
        return keys.firstNotNullOfOrNull { k -> obj.optString(k).trim().takeIf { it.isNotEmpty() } }
    }

    private fun extractCommandArg(obj: JSONObject?, rawFallback: String): String {
        if (obj != null) {
            val cmd = obj.optString("command").ifBlank { obj.optString("cmd") }
            if (cmd.isNotBlank()) return cmd
        }
        return rawFallback
    }

    private fun requiresInteractiveApproval(
        permissionClass: PermissionClass,
        toolId: String,
        preferences: AppPreferencesState,
    ): Boolean {
        return when (permissionClass) {
            PermissionClass.DESTRUCTIVE -> preferences.requireDestructiveApproval
            PermissionClass.EXTERNAL_ACCOUNT_ACTION -> preferences.requirePushApproval
            PermissionClass.PRODUCTION_DEPLOYMENT -> preferences.requireDeployApproval
            PermissionClass.NETWORK_ACCESS -> toolId.startsWith("mcp.")
            else -> false
        }
    }

    private fun classifyCommandPermission(rawCmd: String): PermissionClass {
        val cmd = rawCmd.lowercase()
        return when {
            "firebase deploy" in cmd || "vercel --prod" in cmd || "netlify deploy --prod" in cmd || "kubectl apply" in cmd ->
                PermissionClass.PRODUCTION_DEPLOYMENT
            "git push" in cmd || "gh pr create" in cmd || "gh repo delete" in cmd ->
                PermissionClass.EXTERNAL_ACCOUNT_ACTION
            DESTRUCTIVE_PATTERNS.any { it in cmd } ->
                PermissionClass.DESTRUCTIVE
            else -> PermissionClass.LOCAL_EXECUTION
        }
    }

    private fun isCredentialExfiltrationAttempt(rawCmd: String): Boolean {
        val cmd = rawCmd.lowercase()
        return "agt_gateway_vault" in cmd ||
            "agt_github_vault" in cmd ||
            "agt_extension_vault" in cmd ||
            "agt-cred-" in cmd ||
            "agt-askpass-" in cmd ||
            "last_crash.json" in cmd ||
            "/proc/self/environ" in cmd ||
            "masterkey" in cmd ||
            "encryptedsharedpreferences" in cmd ||
            ("shared_prefs" in cmd && "com.example" in cmd) ||
            ("/data/data/" in cmd && "keystore" in cmd)
    }

    private fun isDangerousMcpArgs(rawArgs: String): Boolean {
        val lower = rawArgs.lowercase()
        return "delete" in lower || "destroy" in lower || "drop " in lower || "force_push" in lower
    }

    private fun reject(
        toolCallId: String,
        toolName: String,
        permissionClass: PermissionClass,
        error: AgtError,
    ): ToolBrokerDecision.Rejected {
        recordAudit(toolCallId, toolName, permissionClass, "REJECTED", error.userMessage)
        return ToolBrokerDecision.Rejected(error)
    }

    private fun recordAudit(
        toolCallId: String,
        toolName: String,
        permissionClass: PermissionClass,
        decision: String,
        summary: String,
    ) {
        val entry = ToolAuditEntry(
            timestampMs = System.currentTimeMillis(),
            toolCallId = toolCallId,
            toolName = toolName,
            permissionClass = permissionClass,
            decision = decision,
            summary = summary,
        )
        auditLog.add(entry)
        onAuditRecorded?.invoke(entry)
    }

    companion object {
        private val FILE_TOOLS = setOf("fs.read", "fs.list", "fs.write", "fs.delete", "fs.search")

        private val DESTRUCTIVE_PATTERNS = listOf(
            "rm -rf", "rm -fr", "mkfs", "dd if=", "git reset --hard",
            "git clean -fd", "chmod -r 777", ":(){", "shutdown", "reboot",
        )

        val STANDARD_TOOLS: List<ToolDefinition> = listOf(
            ToolDefinition(
                toolId = "fs.read",
                aliases = setOf("read", "view"),
                permissionClass = PermissionClass.READ_ONLY,
                description = "Read file within project workspace",
            ),
            ToolDefinition(
                toolId = "fs.list",
                aliases = setOf("glob", "list", "ls", "find", "todoread"),
                permissionClass = PermissionClass.READ_ONLY,
                description = "List or search files within project workspace",
            ),
            ToolDefinition(
                toolId = "fs.search",
                aliases = setOf("grep"),
                permissionClass = PermissionClass.READ_ONLY,
                description = "Search file contents within project workspace",
            ),
            ToolDefinition(
                toolId = "fs.write",
                aliases = setOf("edit", "write", "patch", "multiedit", "todowrite", "create_file"),
                permissionClass = PermissionClass.PROJECT_WRITE,
                description = "Create or modify file inside project workspace",
            ),
            ToolDefinition(
                toolId = "fs.delete",
                aliases = setOf("delete", "remove_file"),
                permissionClass = PermissionClass.DESTRUCTIVE,
                description = "Delete file or folder inside project workspace",
            ),
            ToolDefinition(
                toolId = "exec.run",
                aliases = setOf("bash", "shell", "exec", "terminal"),
                permissionClass = PermissionClass.LOCAL_EXECUTION,
                timeoutSeconds = 120,
                description = "Execute shell command inside Linux runtime",
            ),
            ToolDefinition(
                toolId = "net.fetch",
                aliases = setOf("webfetch", "websearch", "http"),
                permissionClass = PermissionClass.NETWORK_ACCESS,
                timeoutSeconds = 30,
                description = "Read-only network fetch",
            ),
            ToolDefinition(
                toolId = "checkpoint.create",
                aliases = setOf("create_checkpoint"),
                permissionClass = PermissionClass.PROJECT_WRITE,
                description = "Create workspace snapshot checkpoint",
            ),
            ToolDefinition(
                toolId = "checkpoint.restore",
                aliases = setOf("restore_checkpoint"),
                permissionClass = PermissionClass.DESTRUCTIVE,
                description = "Restore workspace to a prior checkpoint",
            ),
        )
    }
}
