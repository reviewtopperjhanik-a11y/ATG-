package com.example.domain.model

import androidx.annotation.StringRes
import com.example.R

enum class ThemeMode(
    val label: String,
    @StringRes val labelRes: Int,
) {
    LIGHT("Light", R.string.theme_light),
    DARK("Dark", R.string.theme_dark),
    SYSTEM("System", R.string.theme_system),
}

enum class AgentBackendId(
    val id: String,
    val displayName: String,
    val shortLabel: String,
) {
    OPENCODE("opencode", "OpenCode Agent", "OpenCode"),
    PI("pi", "Pi Coding Agent", "Pi");

    companion object {
        fun fromId(raw: String?): AgentBackendId =
            entries.firstOrNull { it.id.equals(raw?.trim(), ignoreCase = true) } ?: OPENCODE
    }
}

enum class ComponentInstallStatus {
    WAITING,
    NOT_INSTALLED,
    DOWNLOADING,
    VERIFYING,
    EXTRACTING,
    INSTALLING,
    COMPLETED,
    FAILED,
}

data class SetupComponentItem(
    val id: String,
    val name: String,
    val description: String,
    val status: ComponentInstallStatus,
    val progressPercent: Int? = null,
    val version: String? = null,
    val detailMessage: String? = null,
)

enum class GitHubAuthStatus {
    NOT_CONNECTED,
    AUTHORIZING,
    CONNECTED,
    ERROR,
}

data class GitHubAccountState(
    val status: GitHubAuthStatus = GitHubAuthStatus.NOT_CONNECTED,
    val username: String? = null,
    val scopes: List<String> = emptyList(),
    val errorMessage: String? = null,
)

data class ProjectSummary(
    val id: String,
    val name: String,
    val workspacePath: String,
    val runtimeMountPath: String,
    val remoteUrl: String? = null,
    val branchName: String? = null,
    val lastOpenedEpochMs: Long,
    val stackLabel: String = "Local Workspace",
)

enum class WorkspaceTab {
    AGENT,
    FILES,
    TERMINAL,
    PREVIEW,
}

enum class ComposerAttachmentKind(
    val label: String,
    @StringRes val labelRes: Int,
) {
    CAMERA("Camera", R.string.attach_camera),
    IMAGE("Image", R.string.attach_image),
    FILE("File", R.string.attach_file),
    SKILLS("Skills", R.string.attach_skills),
    MCP_SERVERS("MCP Servers", R.string.attach_mcp_servers),
    PLUGINS("Plugins", R.string.attach_plugins),
}

data class AgentAttachment(
    val id: String,
    val kind: ComposerAttachmentKind,
    val displayName: String,
    val localPath: String? = null,
    val mimeType: String? = null,
)

enum class TaskState {
    CREATED,
    CONTEXT_LOADING,
    ANALYZING,
    RESEARCHING,
    COUNCIL_DELIBERATING,
    SYNTHESIZING,
    AWAITING_COUNCIL_APPROVAL,
    PLANNING,
    AWAITING_PLAN_APPROVAL,
    EXECUTING,
    AWAITING_TOOL_APPROVAL,
    VALIDATING,
    FIXING,
    COMPLETED,
    FAILED,
    CANCELLED,
    PAUSED,
}

enum class PermissionClass {
    READ_ONLY,
    PROJECT_WRITE,
    LOCAL_EXECUTION,
    NETWORK_ACCESS,
    EXTERNAL_ACCOUNT_ACTION,
    DESTRUCTIVE,
    PRODUCTION_DEPLOYMENT,
}

sealed interface AgentTimelineItem {
    val id: String
    val timestampMs: Long

    data class UserPrompt(
        override val id: String,
        override val timestampMs: Long,
        val text: String,
        val modelId: String?,
    ) : AgentTimelineItem

    data class AssistantResponse(
        override val id: String,
        override val timestampMs: Long,
        val text: String,
        val modelId: String?,
        val isStreaming: Boolean = false,
        val promptTokens: Int? = null,
        val completionTokens: Int? = null,
    ) : AgentTimelineItem

    data class SystemNotice(
        override val id: String,
        override val timestampMs: Long,
        val title: String,
        val detail: String,
        val isWarning: Boolean = false,
    ) : AgentTimelineItem

    data class ToolCallCard(
        override val id: String,
        override val timestampMs: Long,
        val toolName: String,
        val status: String,
        val permissionClass: PermissionClass,
        val summary: String,
        val outputSummary: String = "",
    ) : AgentTimelineItem

    data class ApprovalPrompt(
        override val id: String,
        override val timestampMs: Long,
        val title: String,
        val reason: String,
        val permissionClass: PermissionClass,
        val resolved: Boolean = false,
        val approved: Boolean? = null,
        val toolCallId: String? = null,
    ) : AgentTimelineItem

    data class PlanCard(
        override val id: String,
        override val timestampMs: Long,
        val plan: TaskPlan,
        val requiresApproval: Boolean = false,
        val approvalResolved: Boolean = false,
        val approved: Boolean? = null,
    ) : AgentTimelineItem

    data class CheckpointCard(
        override val id: String,
        override val timestampMs: Long,
        val checkpointId: String,
        val reason: String,
        val fileCount: Int,
        val restored: Boolean = false,
    ) : AgentTimelineItem

    data class ValidationCard(
        override val id: String,
        override val timestampMs: Long,
        val result: ValidationCheckResult,
    ) : AgentTimelineItem

    data class TaskSummaryCard(
        override val id: String,
        override val timestampMs: Long,
        val summary: TaskExecutionSummary,
    ) : AgentTimelineItem

    data class CouncilSessionCard(
        override val id: String,
        override val timestampMs: Long,
        val taskId: String,
        val objective: String,
        val executionMode: CouncilExecutionMode = CouncilExecutionMode.COUNCIL_AND_CODE,
        val currentRoundNumber: Int = 1,
        val isRunning: Boolean = true,
        val activeStage: String? = null,
        val activeStageDetail: String? = null,
        val roleStatuses: Map<CouncilRole, CouncilAgentStatus> = emptyMap(),
        val roleModels: Map<CouncilRole, String> = emptyMap(),
        val liveDossier: ResearchDossier? = null,
        val liveOpinions: List<CouncilAgentOpinion> = emptyList(),
        val session: CouncilSession? = null,
        val diffFromPrevious: CouncilRoundDiff? = null,
        val requiresDecision: Boolean = false,
        val tokenBudget: Int = 24_000,
    ) : AgentTimelineItem
}

data class FileTreeEntry(
    val name: String,
    val relativePath: String,
    val isDirectory: Boolean,
    val sizeBytes: Long,
)

data class TerminalLine(
    val id: String,
    val text: String,
    val isError: Boolean = false,
)

enum class PreviewServerStatus {
    STOPPED,
    STARTING,
    RUNNING,
    ERROR,
}

enum class ExtensionCategory {
    SKILLS,
    MCP_SERVERS,
    PLUGINS,
}

enum class ExtensionInstallState {
    NOT_INSTALLED,
    INSTALLED_ENABLED,
    INSTALLED_DISABLED,
    UPDATE_AVAILABLE,
}

data class ExtensionRecord(
    val id: String,
    val name: String,
    val description: String,
    val category: ExtensionCategory,
    val publisher: String,
    val version: String,
    val license: String,
    val compatibility: String,
    val permissions: List<String>,
    val installState: ExtensionInstallState,
    val isBuiltIn: Boolean = false,
    val sourceUri: String = "builtin://agt",
    val sha256: String = "",
    val endpointUrl: String? = null,
    val skillMarkdownPreview: String? = null,
    val discoveredTools: List<McpToolDescriptor> = emptyList(),
    val hasStoredToken: Boolean = false,
    val healthMessage: String? = null,
    val availableVersion: String? = null,
    val agentTarget: ExtensionAgentTarget = ExtensionAgentTarget.UNIVERSAL,
    val tags: List<String> = emptyList(),
    val docsUrl: String? = null,
    val packageFormat: ExtensionPackageFormat = ExtensionPackageFormat.INLINE_SKILL,
    val requiresBearerToken: Boolean = false,
    val mcpTransportType: McpTransportType = McpTransportType.HTTP_SSE,
    val localMcpCommand: List<String> = emptyList(),
    val pluginEntrypoint: String? = null,
)

enum class GatewayHealthStatus {
    UNCONFIGURED,
    CHECKING,
    CONNECTED,
    ERROR,
}

enum class GatewayProtocolMode(val label: String, val path: String) {
    CHAT_COMPLETIONS("Chat Completions (/v1/chat/completions)", "/v1/chat/completions"),
    RESPONSES("Responses (/v1/responses)", "/v1/responses"),
    ANTHROPIC_MESSAGES("Messages (/v1/messages)", "/v1/messages"),
}

data class GatewayModelInfo(
    val id: String,
    val provider: String,
    val supportsStreaming: Boolean?,
    val supportsToolCalling: Boolean?,
    val supportsVision: Boolean? = null,
    val contextWindow: Int? = null,
    val lastSyncedEpochMs: Long = 0L,
)

data class GatewayConnectionSummary(
    val status: GatewayHealthStatus = GatewayHealthStatus.UNCONFIGURED,
    val latencyMs: Long? = null,
    val lastCheckedEpochMs: Long? = null,
    val lastSuccessfulEpochMs: Long? = null,
    val statusMessage: String = "Not tested",
)

enum class SettingsCategory(
    val routeKey: String,
    val title: String,
    val subtitle: String,
    @StringRes val titleRes: Int,
    @StringRes val subtitleRes: Int,
) {
    GATEWAY(
        routeKey = "gateway",
        title = "Gateway",
        subtitle = "JH GetWay Pro endpoint, token, and health",
        titleRes = R.string.settings_cat_gateway_title,
        subtitleRes = R.string.settings_cat_gateway_subtitle,
    ),
    GITHUB(
        routeKey = "github",
        title = "GitHub",
        subtitle = "OAuth account, default branch, and Git identity",
        titleRes = R.string.settings_cat_github_title,
        subtitleRes = R.string.settings_cat_github_subtitle,
    ),
    AGENT(
        routeKey = "agent",
        title = "Agent",
        subtitle = "OpenCode runtime, step limits, and checkpoints",
        titleRes = R.string.settings_cat_agent_title,
        subtitleRes = R.string.settings_cat_agent_subtitle,
    ),
    RUNTIME(
        routeKey = "runtime",
        title = "Runtime",
        subtitle = "Linux rootfs, toolchains, and repair",
        titleRes = R.string.settings_cat_runtime_title,
        subtitleRes = R.string.settings_cat_runtime_subtitle,
    ),
    MODELS(
        routeKey = "models",
        title = "Models",
        subtitle = "Live gateway models and default selection",
        titleRes = R.string.settings_cat_models_title,
        subtitleRes = R.string.settings_cat_models_subtitle,
    ),
    SECURITY(
        routeKey = "security",
        title = "Security",
        subtitle = "Approval gates and secret log masking",
        titleRes = R.string.settings_cat_security_title,
        subtitleRes = R.string.settings_cat_security_subtitle,
    ),
    APPEARANCE(
        routeKey = "appearance",
        title = "Appearance",
        subtitle = "Light, Dark, and System theme mode",
        titleRes = R.string.settings_cat_appearance_title,
        subtitleRes = R.string.settings_cat_appearance_subtitle,
    ),
    NOTIFICATIONS(
        routeKey = "notifications",
        title = "Notifications",
        subtitle = "Task completion, failures, and approvals",
        titleRes = R.string.settings_cat_notifications_title,
        subtitleRes = R.string.settings_cat_notifications_subtitle,
    ),
    STORAGE(
        routeKey = "storage",
        title = "Storage",
        subtitle = "Project workspaces, checkpoints, and cache",
        titleRes = R.string.settings_cat_storage_title,
        subtitleRes = R.string.settings_cat_storage_subtitle,
    ),
    ABOUT(
        routeKey = "about",
        title = "About",
        subtitle = "Version, device architecture, and licenses",
        titleRes = R.string.settings_cat_about_title,
        subtitleRes = R.string.settings_cat_about_subtitle,
    );

    companion object {
        fun fromRouteKey(key: String?): SettingsCategory? =
            entries.firstOrNull { it.routeKey == key }
    }
}

data class AppPreferencesState(
    val hasCompletedOnboarding: Boolean = false,
    val themeMode: ThemeMode = ThemeMode.SYSTEM,
    val gatewayBaseUrl: String = "http://127.0.0.1:8787",
    val gatewayProtocol: GatewayProtocolMode = GatewayProtocolMode.CHAT_COMPLETIONS,
    val hasGatewayToken: Boolean = false,
    val webSearchBaseUrl: String = "",
    val hasWebSearchToken: Boolean = false,
    val githubSearchBaseUrl: String = "",
    val hasGitHubSearchToken: Boolean = false,
    val defaultModelId: String? = null,
    val runtimeManifestUrl: String = "",
    val openCodeManifestUrl: String = "",
    val activeAgentId: String = AgentBackendId.OPENCODE.id,
    val piManifestUrl: String = "",
    val gitDefaultBranch: String = "main",
    val gitAuthorName: String = "",
    val gitAuthorEmail: String = "",
    val githubOAuthClientId: String = "",
    val githubUsername: String? = null,
    val githubScopes: List<String> = emptyList(),
    val agentStepLimit: Int = 20,
    val commandTimeoutSeconds: Int = 120,
    val autoDebugEnabled: Boolean = true,
    val checkpointsEnabled: Boolean = true,
    val requirePlanApproval: Boolean = false,
    val requireDestructiveApproval: Boolean = true,
    val requirePushApproval: Boolean = true,
    val requireDeployApproval: Boolean = true,
    val secretMaskingEnabled: Boolean = true,
    val notifyTaskComplete: Boolean = true,
    val notifyTaskFailure: Boolean = true,
    val notifyApprovals: Boolean = true,
    val notifyRuntimeEvents: Boolean = true,
    val allowProotSandboxExtensions: Boolean = false,
    val councilDefaultMode: CouncilExecutionMode = CouncilExecutionMode.DIRECT_CODE,
    val councilRoleModelOverrides: Map<CouncilRole, String> = emptyMap(),
    val councilEnabledSpecialists: Set<CouncilRole> = CouncilRole.DEFAULT_SPECIALISTS,
    val councilTokenBudget: Int = 24_000,
)
