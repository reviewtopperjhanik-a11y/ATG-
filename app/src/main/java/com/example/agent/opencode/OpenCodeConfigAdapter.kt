package com.example.agent.opencode

import com.example.core.common.AgtError
import com.example.domain.model.AppPreferencesState
import com.example.domain.model.ComposerAttachmentKind
import com.example.domain.model.GatewayModelInfo
import com.example.domain.model.GatewayProtocolMode
import com.example.domain.model.AgentAttachment
import com.example.domain.model.McpServerRuntimeConfig
import com.example.domain.model.McpTransportType
import com.example.domain.model.PluginRuntimeConfig
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/**
 * Translates AGT Gateway state, discovered model capabilities, enabled MCP servers, and
 * security preferences into OpenCode's configuration (`opencode.json`) pointing strictly at the
 * ephemeral loopback [OpenCodeGatewayBridgeServer] and referencing MCP tokens solely via `{env:...}`
 * placeholders (never embedding Keystore tokens on disk).
 */
object OpenCodeConfigAdapter {

    fun validateTaskCapabilities(
        modelInfo: GatewayModelInfo?,
        requiresToolCalling: Boolean,
        attachments: List<AgentAttachment>,
    ): AgtError? {
        if (modelInfo == null) {
            return AgtError.GatewayModelUnavailable(
                "No gateway model selected. Configure JH GetWay Pro in Settings → Gateway and discover models."
            )
        }
        if (requiresToolCalling && modelInfo.supportsToolCalling == false) {
            return AgtError.UnsupportedCapability(
                "Selected model '${modelInfo.id}' reports tool_calling=false on JH GetWay Pro and does not support OpenCode autonomous tool sessions."
            )
        }
        val hasImage = attachments.any {
            it.kind == ComposerAttachmentKind.IMAGE || it.kind == ComposerAttachmentKind.CAMERA
        }
        if (hasImage && modelInfo.supportsVision == false) {
            return AgtError.UnsupportedCapability(
                "Selected model '${modelInfo.id}' does not support vision/image inputs."
            )
        }
        return null
    }

    fun buildOpenCodeConfigJson(
        bridgeBaseUrl: String,
        ephemeralSessionNonce: String,
        protocolMode: GatewayProtocolMode,
        model: GatewayModelInfo,
        preferences: AppPreferencesState,
        mcpServers: List<McpServerRuntimeConfig> = emptyList(),
        plugins: List<PluginRuntimeConfig> = emptyList(),
    ): JSONObject {
        val npmAdapter = when (protocolMode) {
            GatewayProtocolMode.CHAT_COMPLETIONS -> "@ai-sdk/openai-compatible"
            GatewayProtocolMode.RESPONSES -> "@ai-sdk/openai"
            GatewayProtocolMode.ANTHROPIC_MESSAGES -> "@ai-sdk/anthropic"
        }
        val providerBaseUrl = "$bridgeBaseUrl/v1"

        val modelSpec = JSONObject()
            .put("name", model.id)
            .put("tool_call", model.supportsToolCalling ?: true)
            .put("attachment", model.supportsVision ?: false)
            .apply {
                model.contextWindow?.let { ctx ->
                    put("limit", JSONObject().put("context", ctx).put("output", 8192))
                }
            }

        val providerSpec = JSONObject()
            .put("npm", npmAdapter)
            .put("name", "JH GetWay Pro")
            .put(
                "options",
                JSONObject()
                    .put("baseURL", providerBaseUrl)
                    .put("apiKey", ephemeralSessionNonce)
            )
            .put("models", JSONObject().put(model.id, modelSpec))

        val bashPermissions = JSONObject()
            .put("*", "allow")
            .put("*agt_gateway_vault*", "deny")
            .put("*agt_github_vault*", "deny")
            .put("*agt_extension_vault*", "deny")
            .put("*/proc/self/environ*", "deny")
            .put("rm -rf *", if (preferences.requireDestructiveApproval) "ask" else "allow")
            .put("rm -fr *", if (preferences.requireDestructiveApproval) "ask" else "allow")
            .put("mkfs*", if (preferences.requireDestructiveApproval) "ask" else "allow")
            .put("dd if=*", if (preferences.requireDestructiveApproval) "ask" else "allow")
            .put("git reset --hard*", if (preferences.requireDestructiveApproval) "ask" else "allow")
            .put("git clean -fd*", if (preferences.requireDestructiveApproval) "ask" else "allow")
            .put("git push *", if (preferences.requirePushApproval) "ask" else "allow")
            .put("gh pr create*", if (preferences.requirePushApproval) "ask" else "allow")
            .put("gh repo delete*", if (preferences.requireDestructiveApproval || preferences.requirePushApproval) "ask" else "allow")

        val permissionSpec = JSONObject()
            .put("edit", if (preferences.requirePlanApproval) "ask" else "allow")
            .put("external_directory", "deny")
            .put("bash", bashPermissions)

        val root = JSONObject()
            .put("\$schema", "https://opencode.ai/config.json")
            .put("model", "jh_gateway/${model.id}")
            .put("provider", JSONObject().put("jh_gateway", providerSpec))
            .put("permission", permissionSpec)

        if (mcpServers.isNotEmpty()) {
            val mcpObj = JSONObject()
            mcpServers.forEach { srv ->
                val srvSpec = if (srv.transportType == McpTransportType.LOCAL_STDIO && srv.localCommand.isNotEmpty()) {
                    JSONObject()
                        .put("type", "local")
                        .put("command", JSONArray(srv.localCommand))
                        .put("enabled", true)
                        .apply {
                            srv.envVarTokenName?.let { envKey ->
                                put("environment", JSONObject().put(envKey, "{env:$envKey}"))
                            }
                        }
                } else {
                    JSONObject()
                        .put("type", "remote")
                        .put("url", srv.endpointUrl)
                        .put("enabled", true)
                        .apply {
                            srv.envVarTokenName?.let { envKey ->
                                put(
                                    "headers",
                                    JSONObject().put("Authorization", "Bearer {env:$envKey}")
                                )
                            }
                        }
                }
                mcpObj.put(srv.alias, srvSpec)
            }
            root.put("mcp", mcpObj)
        }

        if (plugins.isNotEmpty()) {
            root.put(
                "plugin",
                JSONArray(plugins.map { "./plugins/${it.slug}/${it.entrypointRelPath}" })
            )
        }

        return root
    }

    fun writeSessionConfig(
        sessionDir: File,
        configJson: JSONObject,
    ): File {
        sessionDir.mkdirs()
        val configFile = File(sessionDir, "opencode.json")
        configFile.writeText(configJson.toString(2))
        return configFile
    }
}
