package com.example.agent.pi

import com.example.core.common.AgtError
import com.example.domain.model.AgentAttachment
import com.example.domain.model.AppPreferencesState
import com.example.domain.model.ComposerAttachmentKind
import com.example.domain.model.ExtensionRecord
import com.example.domain.model.GatewayModelInfo
import com.example.domain.model.GatewayProtocolMode
import com.example.domain.model.McpServerRuntimeConfig
import com.example.domain.model.McpTransportType
import com.example.domain.model.PluginRuntimeConfig
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/**
 * Generates isolated per-session Pi Coding Agent configuration files inside
 * `$PI_CODING_AGENT_DIR` (`models.json`, `settings.json`, `auth.json`, and optional
 * `append_system.md`), routing all LLM calls exclusively through AGT's loopback
 * [com.example.agent.opencode.OpenCodeGatewayBridgeServer] with ephemeral session nonces.
 */
object PiConfigAdapter {

    const val PROVIDER_ID = "jh_gateway"

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
                "Selected model '${modelInfo.id}' reports tool_calling=false on JH GetWay Pro and does not support Pi autonomous tool sessions."
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

    fun mapProtocolApi(protocolMode: GatewayProtocolMode): String = when (protocolMode) {
        GatewayProtocolMode.CHAT_COMPLETIONS -> "openai-completions"
        GatewayProtocolMode.RESPONSES -> "openai-responses"
        GatewayProtocolMode.ANTHROPIC_MESSAGES -> "anthropic-messages"
    }

    fun buildPiModelsJson(
        bridgeBaseUrl: String,
        ephemeralSessionNonce: String,
        protocolMode: GatewayProtocolMode,
        model: GatewayModelInfo,
    ): JSONObject {
        val apiType = mapProtocolApi(protocolMode)
        val inputModalities = JSONArray().put("text").apply {
            if (model.supportsVision != false) {
                put("image")
            }
        }
        val modelEntry = JSONObject()
            .put("id", model.id)
            .put("name", model.id)
            .put("reasoning", false)
            .put("input", inputModalities)
            .put("contextWindow", model.contextWindow ?: 128000)
            .put("maxTokens", 8192)
            .put(
                "cost",
                JSONObject()
                    .put("input", 0)
                    .put("output", 0)
                    .put("cacheRead", 0)
                    .put("cacheWrite", 0)
            )

        val providerSpec = JSONObject()
            .put("baseUrl", "$bridgeBaseUrl/v1")
            .put("api", apiType)
            .put("apiKey", ephemeralSessionNonce)
            .put("models", JSONArray().put(modelEntry))

        return JSONObject()
            .put("providers", JSONObject().put(PROVIDER_ID, providerSpec))
    }

    fun buildPiSettingsJson(
        model: GatewayModelInfo,
        preferences: AppPreferencesState,
    ): JSONObject = JSONObject()
        .put("defaultProvider", PROVIDER_ID)
        .put("defaultModel", model.id)
        .put("theme", "dark")
        .put("hideThinkingBlock", false)
        .put("compaction", JSONObject().put("enabled", preferences.agentStepLimit > 10))

    fun buildSystemAppendMarkdown(
        activeSkills: List<Pair<ExtensionRecord, String>>,
        mcpServers: List<McpServerRuntimeConfig>,
        activePlugins: List<PluginRuntimeConfig> = emptyList(),
    ): String {
        if (activeSkills.isEmpty() && mcpServers.isEmpty() && activePlugins.isEmpty()) return ""
        return buildString {
            appendLine("# AGT Workspace Policies & Extensions")
            if (activeSkills.isNotEmpty()) {
                appendLine()
                appendLine("## Active Non-Executable Skills (SKILL.md)")
                activeSkills.forEach { (record, markdown) ->
                    appendLine()
                    appendLine("### ${record.name} (${record.id}, v${record.version})")
                    appendLine(markdown.take(4000))
                }
            }
            if (mcpServers.isNotEmpty()) {
                appendLine()
                appendLine("## Connected MCP Endpoints")
                mcpServers.forEach { srv ->
                    if (srv.transportType == McpTransportType.LOCAL_STDIO && srv.localCommand.isNotEmpty()) {
                        appendLine("- ${srv.alias}: local PRoot stdio (${srv.localCommand.joinToString(" ")})")
                    } else {
                        appendLine("- ${srv.alias}: ${srv.endpointUrl}")
                    }
                }
            }
            if (activePlugins.isNotEmpty()) {
                appendLine()
                appendLine("## Active Sandboxed Extensions (\$PI_CODING_AGENT_DIR/extensions/)")
                activePlugins.forEach { cfg ->
                    appendLine("- ${cfg.id}: ./extensions/${cfg.slug}/${cfg.entrypointRelPath}")
                }
            }
        }.trim()
    }

    data class WrittenAgentDir(
        val agentDir: File,
        val modelsFile: File,
        val settingsFile: File,
        val authFile: File,
        val appendSystemFile: File?,
    )

    fun writeSessionAgentDir(
        sessionDir: File,
        modelsJson: JSONObject,
        settingsJson: JSONObject,
        systemAppendMarkdown: String,
    ): WrittenAgentDir {
        val agentDir = File(sessionDir, "pi-agent").apply { mkdirs() }
        val modelsFile = File(agentDir, "models.json").apply {
            writeText(modelsJson.toString(2))
        }
        val settingsFile = File(agentDir, "settings.json").apply {
            writeText(settingsJson.toString(2))
        }
        val authFile = File(agentDir, "auth.json").apply {
            writeText("{}\n")
        }
        val appendFile = if (systemAppendMarkdown.isNotBlank()) {
            File(agentDir, "append_system.md").apply {
                writeText(systemAppendMarkdown)
            }
        } else {
            null
        }
        return WrittenAgentDir(
            agentDir = agentDir,
            modelsFile = modelsFile,
            settingsFile = settingsFile,
            authFile = authFile,
            appendSystemFile = appendFile,
        )
    }
}
