package com.example.domain.model

enum class FlightRecorderEventType(val label: String) {
    STATE_TRANSITION("State"),
    TOOL_CHECK("Tool Policy"),
    TOOL_EXECUTION("Tool Run"),
    VALIDATION_RUN("Validation"),
    RUNTIME_ERROR("Error"),
    CHECKPOINT("Checkpoint"),
    FIX_LOOP("Auto-Fix"),
    PLAN_DECISION("Plan"),
    APPROVAL("Approval"),
    COUNCIL_RESEARCH("Council Research"),
    COUNCIL_OPINION("Council Opinion"),
    COUNCIL_SYNTHESIS("Council Synthesis"),
    COUNCIL_USER_EDIT("Council Edit"),
}

enum class FlightRecorderOutcome(val label: String) {
    INFO("Info"),
    ALLOWED("Allowed"),
    REQUIRES_APPROVAL("Approval"),
    REJECTED("Blocked"),
    PASSED("Passed"),
    FAILED("Failed"),
    TIMED_OUT("Timed Out"),
    CANCELLED("Cancelled"),
    SKIPPED("Skipped"),
}

enum class FlightRecorderFilter(val label: String) {
    ALL("All"),
    FAILURES("Failures"),
    TOOLS("Tools"),
    VALIDATION("Validation"),
    STATES("States"),
}

data class FlightRecorderEvent(
    val id: String,
    val projectId: String,
    val taskId: String,
    val seq: Long,
    val eventType: FlightRecorderEventType,
    val toolName: String? = null,
    val commandOrPath: String? = null,
    val outcome: FlightRecorderOutcome,
    val exitCode: Int? = null,
    val outputExcerpt: String = "",
    val modelId: String? = null,
    val backendId: String? = null,
    val stepIndex: Int? = null,
    val checkpointId: String? = null,
    val timestampMs: Long,
) {
    val isFailureOrBlock: Boolean
        get() = outcome == FlightRecorderOutcome.FAILED ||
            outcome == FlightRecorderOutcome.TIMED_OUT ||
            outcome == FlightRecorderOutcome.REJECTED ||
            eventType == FlightRecorderEventType.RUNTIME_ERROR
}

data class TaskResumeCursor(
    val checkpointId: String? = null,
    val fixAttempts: Int = 0,
    val lastError: String? = null,
    val lastEventSeq: Long = 0L,
    val pendingApprovalId: String? = null,
    val pendingApprovalTitle: String? = null,
)

data class TaskStateSnapshot(
    val taskId: String,
    val projectId: String,
    val objective: String,
    val currentState: TaskState,
    val activeStepIndex: Int,
    val planSteps: List<PlanStep>,
    val keyDecisions: List<String>,
    val modifiedFiles: List<String>,
    val resumeCursor: TaskResumeCursor,
    val lastModelId: String? = null,
    val lastBackendId: String? = null,
    val updatedAtEpochMs: Long,
)

data class KnownFixEntry(
    val errorSignature: String,
    val resolutionNote: String,
    val recordedAtEpochMs: Long,
)

data class ProjectMemory(
    val projectId: String,
    val stackLabel: String,
    val curatedRules: List<String>,
    val validationCommands: List<String>,
    val repoMapCache: String,
    val repoMapUpdatedAtEpochMs: Long,
    val knownFixes: List<KnownFixEntry>,
    val updatedAtEpochMs: Long,
)

enum class ChatMessageRole {
    USER,
    ASSISTANT,
    SYSTEM_NOTICE,
    SUMMARY,
}

data class ChatMessageRecord(
    val id: String,
    val projectId: String,
    val taskId: String? = null,
    val role: ChatMessageRole,
    val title: String? = null,
    val content: String,
    val modelId: String? = null,
    val backendId: String? = null,
    val promptTokens: Int? = null,
    val completionTokens: Int? = null,
    val isWarning: Boolean = false,
    val createdAtEpochMs: Long,
)
