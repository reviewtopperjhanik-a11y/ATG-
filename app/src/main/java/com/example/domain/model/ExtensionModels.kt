package com.example.domain.model

data class McpToolDescriptor(
    val name: String,
    val description: String = "",
    val isDestructive: Boolean = false,
)

data class ExtensionManifest(
    val id: String,
    val name: String,
    val description: String,
    val category: ExtensionCategory,
    val publisher: String = "Community",
    val version: String = "1.0.0",
    val license: String = "MIT",
    val compatibility: String = "AGT API >= 1.0",
    val permissions: List<String> = emptyList(),
    val expectedSha256: String? = null,
    val endpointUrl: String? = null,
    val skillMarkdown: String? = null,
    val sourceUri: String = "manual://import",
)

data class McpInitializeResult(
    val serverName: String?,
    val serverVersion: String?,
    val protocolVersion: String?,
    val sessionId: String?,
)

data class McpToolCallResult(
    val isError: Boolean,
    val outputText: String,
)

enum class McpTransportType {
    HTTP_SSE,
    LOCAL_STDIO,
}

data class ActiveExtensionPolicy(
    val enforceMcpAllowlist: Boolean = false,
    val enabledMcpPrefixes: Set<String> = emptySet(),
    val enabledMcpToolNames: Set<String> = emptySet(),
    val destructiveMcpTools: Set<String> = emptySet(),
    val enabledPluginIds: Set<String> = emptySet(),
)

data class McpServerRuntimeConfig(
    val id: String,
    val alias: String,
    val endpointUrl: String,
    val envVarTokenName: String? = null,
    val bearerToken: String? = null,
    val transportType: McpTransportType = McpTransportType.HTTP_SSE,
    val localCommand: List<String> = emptyList(),
)

data class PluginRuntimeConfig(
    val id: String,
    val slug: String,
    val entrypointRelPath: String,
    val agentTarget: ExtensionAgentTarget = ExtensionAgentTarget.UNIVERSAL,
)

data class DetectedPreviewServer(
    val frameworkLabel: String,
    val command: String,
    val suggestedUrl: String,
)

data class PreviewConsoleLine(
    val id: String,
    val text: String,
    val isError: Boolean = false,
    val source: String = "SERVER",
)

enum class ExtensionAgentTarget(val label: String) {
    UNIVERSAL("Universal"),
    OPENCODE("OpenCode"),
    PI("Pi Agent"),
}

enum class ExtensionPackageFormat {
    INLINE_SKILL,
    RAW_SKILL_MD,
    ZIP,
    TAR_GZ,
    REMOTE_MCP_TEMPLATE,
    LOCAL_STDIO_MCP_TEMPLATE,
    JS_TS_PLUGIN,
}

enum class ExtensionInstallStage {
    IDLE,
    DOWNLOADING,
    VERIFYING,
    EXTRACTING,
    INSTALLING,
    FAILED,
}

data class ExtensionInstallProgress(
    val extensionId: String,
    val extensionName: String,
    val stage: ExtensionInstallStage,
    val progressPercent: Int? = null,
    val detail: String = "",
)

data class ExtensionCatalogEntry(
    val record: ExtensionRecord,
    val format: ExtensionPackageFormat,
    val downloadUrl: String? = null,
    val archiveSubpath: String? = null,
    val expectedSha256: String? = null,
    val inlineSkillMarkdown: String? = null,
    val localCommand: List<String> = emptyList(),
    val pluginEntrypoint: String? = null,
)


