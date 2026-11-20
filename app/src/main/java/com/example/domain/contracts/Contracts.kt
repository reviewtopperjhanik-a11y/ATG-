package com.example.domain.contracts

import com.example.domain.model.FileTreeEntry
import com.example.domain.model.GatewayHealthStatus
import com.example.domain.model.GatewayModelInfo
import com.example.domain.model.TaskState
import kotlinx.coroutines.flow.Flow

data class ProjectRef(
    val id: String,
    val name: String,
    val rootPath: String,
)

data class GatewayHealth(
    val status: GatewayHealthStatus,
    val latencyMs: Long? = null,
    val message: String,
    val error: com.example.core.common.AgtError? = null,
)

data class GatewayChatRequest(
    val modelId: String,
    val messages: List<Pair<String, String>>,
    val stream: Boolean = true,
    val protocolMode: com.example.domain.model.GatewayProtocolMode =
        com.example.domain.model.GatewayProtocolMode.CHAT_COMPLETIONS,
)

sealed interface GatewayStreamEvent {
    data class TextDelta(val text: String) : GatewayStreamEvent
    data class ToolCallDelta(val toolCallId: String, val name: String?, val argumentsDelta: String) : GatewayStreamEvent
    data class Completed(val promptTokens: Int?, val completionTokens: Int?) : GatewayStreamEvent
    data class Failed(val error: com.example.core.common.AgtError) : GatewayStreamEvent
}

interface GatewayClient {
    suspend fun health(): GatewayHealth
    suspend fun listModels(): Result<List<GatewayModelInfo>>
    fun streamChat(request: GatewayChatRequest): Flow<GatewayStreamEvent>
}

data class RuntimeAvailability(
    val isInstalled: Boolean,
    val version: String?,
    val detail: String,
)

data class AgentSession(
    val sessionId: String,
    val project: ProjectRef,
)

data class AgentTask(
    val taskId: String,
    val prompt: String,
    val modelId: String?,
    val protocolMode: com.example.domain.model.GatewayProtocolMode =
        com.example.domain.model.GatewayProtocolMode.CHAT_COMPLETIONS,
    val attachments: List<com.example.domain.model.AgentAttachment> = emptyList(),
    val requiresToolCalling: Boolean = true,
)

sealed interface AgentEvent {
    data class SessionStarted(val sessionId: String, val backendLabel: String) : AgentEvent
    data class StateChanged(val state: TaskState) : AgentEvent
    data class StatusMessage(val text: String) : AgentEvent
    data class TextDelta(val text: String) : AgentEvent
    data class ToolRequested(
        val toolCallId: String,
        val toolName: String,
        val permissionClass: com.example.domain.model.PermissionClass,
        val summary: String,
        val argumentsJson: String = "",
    ) : AgentEvent
    data class ApprovalRequired(
        val approvalId: String,
        val title: String,
        val reason: String,
        val permissionClass: com.example.domain.model.PermissionClass,
        val toolCallId: String? = null,
    ) : AgentEvent
    data class ToolStarted(
        val toolCallId: String,
        val toolName: String,
        val summary: String,
    ) : AgentEvent
    data class ToolCompleted(
        val toolCallId: String,
        val toolName: String,
        val success: Boolean,
        val outputSummary: String,
    ) : AgentEvent
    data class FileChanged(val relativePath: String, val summary: String) : AgentEvent
    data class Completed(val promptTokens: Int? = null, val completionTokens: Int? = null) : AgentEvent
    data class Failed(
        val reason: String,
        val error: com.example.core.common.AgtError? = null,
    ) : AgentEvent
}

interface AgentRuntime {
    val id: String
    suspend fun checkAvailability(): RuntimeAvailability
    suspend fun startSession(project: ProjectRef): Result<AgentSession>
    fun submitTask(session: AgentSession, task: AgentTask): Flow<AgentEvent>
    suspend fun respondToApproval(session: AgentSession, approvalId: String, approved: Boolean): Result<Unit>
    suspend fun stop(session: AgentSession)
}

data class RuntimeCommand(
    val command: String,
    val workingDirectory: String,
    val timeoutSeconds: Int = 120,
    val processId: String = java.util.UUID.randomUUID().toString(),
    val environment: Map<String, String> = emptyMap(),
    val maxOutputLines: Int = 2000,
)

sealed interface ProcessEvent {
    data class Started(val processId: String, val pid: Long?) : ProcessEvent
    data class Stdout(val line: String) : ProcessEvent
    data class Stderr(val line: String) : ProcessEvent
    data class Exited(val exitCode: Int) : ProcessEvent
    data class Failed(val error: com.example.core.common.AgtError) : ProcessEvent
}

interface RuntimeExecutor {
    suspend fun ensureReady(): RuntimeAvailability
    fun execute(command: RuntimeCommand): Flow<ProcessEvent>
    suspend fun sendInput(processId: String, input: String): Result<Unit>
    suspend fun terminate(processId: String)
}

interface ProjectFileStore {
    suspend fun list(projectRootPath: String, relativePath: String): Result<List<FileTreeEntry>>
    suspend fun readText(projectRootPath: String, relativePath: String): Result<String>
    suspend fun writeText(projectRootPath: String, relativePath: String, content: String): Result<Unit>
    suspend fun delete(projectRootPath: String, relativePath: String): Result<Unit>
}

data class Checkpoint(
    val id: String,
    val projectId: String,
    val reason: String,
    val createdAtEpochMs: Long,
)

interface CheckpointManager {
    suspend fun create(projectId: String, reason: String): Result<Checkpoint>
    suspend fun diff(checkpointId: String): Result<com.example.domain.model.ProjectDiff>
    suspend fun restore(checkpointId: String): Result<Unit>
    fun observeForProject(projectId: String): Flow<List<com.example.domain.model.CheckpointSummary>>
}
