package com.example.domain.model

enum class ProjectStack(val label: String) {
    GRADLE_JVM("Gradle / JVM"),
    NODE_JS("Node.js"),
    PYTHON("Python"),
    RUST("Rust"),
    GO("Go"),
    WEB_STATIC("Web"),
    SHELL_MAKE("Shell / Make"),
    UNKNOWN("Local Workspace"),
}

enum class ValidationKind(val label: String) {
    BUILD("Build"),
    TEST("Unit Tests"),
    LINT("Lint / Syntax"),
    TYPECHECK("Type Check"),
}

data class ValidationCommand(
    val id: String,
    val label: String,
    val command: String,
    val kind: ValidationKind,
)

data class ProjectInspection(
    val projectId: String,
    val projectRootPath: String,
    val stack: ProjectStack,
    val stackLabel: String,
    val entryPoints: List<String>,
    val manifestSummaries: List<String>,
    val directoryMap: List<String>,
    val validationCommands: List<ValidationCommand>,
    val isGitRepo: Boolean,
    val gitHead: String? = null,
    val gitStatusSummary: String? = null,
    val recentTaskSummaries: List<String> = emptyList(),
    val repoMap: String = "",
) {
    fun toPromptContext(): String = buildString {
        appendLine("Project Stack: $stackLabel")
        if (isGitRepo) {
            appendLine("Git HEAD: ${gitHead ?: "unknown"}${gitStatusSummary?.let { " ($it)" }.orEmpty()}")
        }
        if (entryPoints.isNotEmpty()) {
            appendLine("Key Entry Points: ${entryPoints.joinToString(", ")}")
        }
        if (manifestSummaries.isNotEmpty()) {
            appendLine("Manifests:")
            manifestSummaries.forEach { appendLine("  - $it") }
        }
        if (validationCommands.isNotEmpty()) {
            appendLine("Detected Validation Commands:")
            validationCommands.forEach { appendLine("  - ${it.label}: ${it.command}") }
        }
        if (repoMap.isNotBlank()) {
            appendLine("Ranked Repo Map:")
            appendLine(repoMap)
        } else if (directoryMap.isNotEmpty()) {
            appendLine("Project Files (bounded):")
            directoryMap.take(40).forEach { appendLine("  - $it") }
            if (directoryMap.size > 40) {
                appendLine("  ... (${directoryMap.size - 40} more)")
            }
        }
        if (recentTaskSummaries.isNotEmpty()) {
            appendLine("Recent Tasks:")
            recentTaskSummaries.take(3).forEach { appendLine("  - $it") }
        }
    }.trim()
}

enum class PlanStepStatus {
    PENDING,
    IN_PROGRESS,
    COMPLETED,
    FAILED,
    SKIPPED,
}

data class PlanStep(
    val index: Int,
    val title: String,
    val status: PlanStepStatus = PlanStepStatus.PENDING,
)

data class TaskPlan(
    val taskId: String,
    val objective: String,
    val steps: List<PlanStep>,
    val acceptanceCriteria: List<String>,
    val validationChecks: List<String>,
)

data class ToolDefinition(
    val toolId: String,
    val aliases: Set<String>,
    val permissionClass: PermissionClass,
    val requiredArgs: List<String> = emptyList(),
    val timeoutSeconds: Int = 120,
    val maxOutputBytes: Int = 64 * 1024,
    val description: String,
)

sealed interface ToolBrokerDecision {
    data class Allowed(
        val definition: ToolDefinition,
        val sanitizedSummary: String,
    ) : ToolBrokerDecision

    data class RequiresApproval(
        val definition: ToolDefinition,
        val reason: String,
        val sanitizedSummary: String,
    ) : ToolBrokerDecision

    data class Rejected(
        val error: com.example.core.common.AgtError,
    ) : ToolBrokerDecision
}

data class ToolAuditEntry(
    val timestampMs: Long,
    val toolCallId: String,
    val toolName: String,
    val permissionClass: PermissionClass,
    val decision: String,
    val summary: String,
)

enum class FileChangeType(val label: String) {
    ADDED("Added"),
    MODIFIED("Modified"),
    DELETED("Deleted"),
}

data class FileDiffEntry(
    val relativePath: String,
    val changeType: FileChangeType,
    val beforeSizeBytes: Long,
    val afterSizeBytes: Long,
    val unifiedDiff: String,
)

data class ProjectDiff(
    val checkpointId: String,
    val checkpointReason: String,
    val createdAtEpochMs: Long,
    val entries: List<FileDiffEntry>,
) {
    val totalFilesChanged: Int get() = entries.size
    val hasChanges: Boolean get() = entries.isNotEmpty()
}

data class CheckpointSummary(
    val id: String,
    val projectId: String,
    val reason: String,
    val gitHead: String?,
    val fileCount: Int,
    val createdAtEpochMs: Long,
)

enum class ValidationOutcome(val label: String) {
    PASSED("Passed"),
    FAILED("Failed"),
    TIMED_OUT("Timed Out"),
    CANCELLED("Cancelled"),
    SKIPPED("Skipped"),
}

data class ValidationCheckResult(
    val commandId: String,
    val label: String,
    val command: String,
    val status: ValidationOutcome,
    val exitCode: Int? = null,
    val durationMs: Long = 0L,
    val outputExcerpt: String = "",
)

data class TaskExecutionSummary(
    val taskId: String,
    val objective: String,
    val finalState: TaskState,
    val filesChanged: List<String>,
    val checksExecuted: List<ValidationCheckResult>,
    val fixAttempts: Int,
    val checkpointId: String? = null,
    val gitStatusNote: String = "Local workspace (not committed)",
    val unresolvedIssues: List<String> = emptyList(),
    val completedAtEpochMs: Long = System.currentTimeMillis(),
)
