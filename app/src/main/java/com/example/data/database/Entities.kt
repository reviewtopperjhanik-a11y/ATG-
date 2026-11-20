package com.example.data.database

import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey

@Entity(tableName = "projects")
data class ProjectEntity(
    @PrimaryKey val id: String,
    val name: String,
    val workspacePath: String,
    val runtimeMountPath: String,
    val remoteUrl: String? = null,
    val branchName: String? = null,
    val stackLabel: String = "Local Workspace",
    val createdAtEpochMs: Long,
    val lastOpenedEpochMs: Long,
)

@Entity(
    tableName = "agent_tasks",
    foreignKeys = [
        ForeignKey(
            entity = ProjectEntity::class,
            parentColumns = ["id"],
            childColumns = ["projectId"],
            onDelete = ForeignKey.CASCADE,
        ),
    ],
    indices = [Index("projectId")],
)
data class TaskEntity(
    @PrimaryKey val id: String,
    val projectId: String,
    val prompt: String,
    val state: String,
    val modelId: String?,
    val createdAtEpochMs: Long,
    val updatedAtEpochMs: Long,
    val summaryJson: String? = null,
)

@Entity(
    tableName = "checkpoints",
    foreignKeys = [
        ForeignKey(
            entity = ProjectEntity::class,
            parentColumns = ["id"],
            childColumns = ["projectId"],
            onDelete = ForeignKey.CASCADE,
        ),
    ],
    indices = [Index("projectId")],
)
data class CheckpointEntity(
    @PrimaryKey val id: String,
    val projectId: String,
    val reason: String,
    val snapshotPath: String,
    val gitHead: String? = null,
    val createdAtEpochMs: Long,
    val fileCount: Int = 0,
)

@Entity(tableName = "cached_models")
data class CachedModelEntity(
    @PrimaryKey val id: String,
    val provider: String,
    val supportsStreaming: Boolean?,
    val supportsToolCalling: Boolean?,
    val supportsVision: Boolean?,
    val contextWindow: Int?,
    val lastSyncedEpochMs: Long,
)

@Entity(tableName = "extensions")
data class ExtensionEntity(
    @PrimaryKey val id: String,
    val name: String,
    val description: String,
    val category: String,
    val publisher: String,
    val version: String,
    val availableVersion: String? = null,
    val license: String,
    val compatibility: String,
    val sourceUri: String,
    val sha256: String,
    val permissionsCsv: String,
    val installState: String,
    val isBuiltIn: Boolean,
    val endpointUrl: String? = null,
    val skillContentPath: String? = null,
    val discoveredToolsJson: String? = null,
    val healthMessage: String? = null,
    val installedAtEpochMs: Long,
    val updatedAtEpochMs: Long,
)

@Entity(
    tableName = "chat_messages",
    foreignKeys = [
        ForeignKey(
            entity = ProjectEntity::class,
            parentColumns = ["id"],
            childColumns = ["projectId"],
            onDelete = ForeignKey.CASCADE,
        ),
    ],
    indices = [
        Index("projectId"),
        Index("taskId"),
        Index(value = ["projectId", "createdAtEpochMs"]),
    ],
)
data class ChatMessageEntity(
    @PrimaryKey val id: String,
    val projectId: String,
    val taskId: String? = null,
    val role: String,
    val title: String? = null,
    val content: String,
    val modelId: String? = null,
    val backendId: String? = null,
    val promptTokens: Int? = null,
    val completionTokens: Int? = null,
    val isWarning: Boolean = false,
    val createdAtEpochMs: Long,
)

@Entity(
    tableName = "task_state_snapshots",
    foreignKeys = [
        ForeignKey(
            entity = TaskEntity::class,
            parentColumns = ["id"],
            childColumns = ["taskId"],
            onDelete = ForeignKey.CASCADE,
        ),
        ForeignKey(
            entity = ProjectEntity::class,
            parentColumns = ["id"],
            childColumns = ["projectId"],
            onDelete = ForeignKey.CASCADE,
        ),
    ],
    indices = [
        Index("projectId"),
        Index(value = ["projectId", "updatedAtEpochMs"]),
    ],
)
data class TaskStateSnapshotEntity(
    @PrimaryKey val taskId: String,
    val projectId: String,
    val objective: String,
    val currentState: String,
    val activeStepIndex: Int,
    val serializedPlanStepsJson: String,
    val keyDecisionsJson: String,
    val modifiedFilesJson: String,
    val resumePayloadJson: String,
    val lastModelId: String? = null,
    val lastBackendId: String? = null,
    val updatedAtEpochMs: Long,
)

@Entity(
    tableName = "project_memory_entries",
    foreignKeys = [
        ForeignKey(
            entity = ProjectEntity::class,
            parentColumns = ["id"],
            childColumns = ["projectId"],
            onDelete = ForeignKey.CASCADE,
        ),
    ],
)
data class ProjectMemoryEntity(
    @PrimaryKey val projectId: String,
    val stackLabel: String,
    val curatedRulesJson: String,
    val validationMetadataJson: String,
    val repoMapCache: String,
    val repoMapUpdatedAtEpochMs: Long,
    val knownFixesJson: String,
    val updatedAtEpochMs: Long,
)

@Entity(
    tableName = "flight_recorder_events",
    foreignKeys = [
        ForeignKey(
            entity = ProjectEntity::class,
            parentColumns = ["id"],
            childColumns = ["projectId"],
            onDelete = ForeignKey.CASCADE,
        ),
    ],
    indices = [
        Index("projectId"),
        Index(value = ["taskId", "seq"]),
        Index(value = ["projectId", "timestampMs"]),
    ],
)
data class FlightRecorderEventEntity(
    @PrimaryKey val id: String,
    val projectId: String,
    val taskId: String,
    val seq: Long,
    val eventType: String,
    val toolName: String? = null,
    val commandOrPath: String? = null,
    val outcome: String,
    val exitCode: Int? = null,
    val outputExcerpt: String = "",
    val modelId: String? = null,
    val backendId: String? = null,
    val stepIndex: Int? = null,
    val checkpointId: String? = null,
    val timestampMs: Long,
)

@Entity(
    tableName = "project_knowledge_entries",
    foreignKeys = [
        ForeignKey(
            entity = ProjectEntity::class,
            parentColumns = ["id"],
            childColumns = ["projectId"],
            onDelete = ForeignKey.CASCADE,
        ),
    ],
    indices = [
        Index("projectId"),
        Index(value = ["projectId", "factKey", "supersededById"]),
        Index(value = ["projectId", "category", "validUntilEpochMs"]),
        Index(value = ["projectId", "updatedAtEpochMs"]),
    ],
)
data class ProjectKnowledgeEntity(
    @PrimaryKey val id: String,
    val projectId: String,
    val factKey: String,
    val category: String,
    val title: String,
    val content: String,
    val tags: String = "",
    val sourceTaskId: String? = null,
    val confidence: Float = 0.85f,
    val validFromEpochMs: Long,
    val validUntilEpochMs: Long? = null,
    val supersededById: String? = null,
    val createdAtEpochMs: Long,
    val updatedAtEpochMs: Long,
)

@Entity(
    tableName = "council_sessions",
    foreignKeys = [
        ForeignKey(
            entity = ProjectEntity::class,
            parentColumns = ["id"],
            childColumns = ["projectId"],
            onDelete = ForeignKey.CASCADE,
        ),
    ],
    indices = [
        Index("projectId"),
        Index("taskId"),
        Index(value = ["projectId", "updatedAtEpochMs"]),
    ],
)
data class CouncilSessionEntity(
    @PrimaryKey val sessionId: String,
    val projectId: String,
    val taskId: String,
    val objective: String,
    val mode: String,
    val currentRoundNumber: Int,
    val approvedForCoding: Boolean,
    val createdAtEpochMs: Long,
    val updatedAtEpochMs: Long,
)

@Entity(
    tableName = "council_rounds",
    foreignKeys = [
        ForeignKey(
            entity = CouncilSessionEntity::class,
            parentColumns = ["sessionId"],
            childColumns = ["sessionId"],
            onDelete = ForeignKey.CASCADE,
        ),
    ],
    indices = [
        Index("sessionId"),
        Index(value = ["sessionId", "roundNumber"], unique = true),
    ],
)
data class CouncilRoundEntity(
    @PrimaryKey val id: String,
    val sessionId: String,
    val roundNumber: Int,
    val userAmendmentNote: String? = null,
    val refreshedSearch: Boolean = false,
    val decompositionJson: String,
    val dossierJson: String,
    val opinionsJson: String,
    val verdict: String,
    val sectionsJson: String,
    val totalPromptTokens: Int = 0,
    val totalCompletionTokens: Int = 0,
    val durationMs: Long = 0L,
    val createdAtEpochMs: Long,
)

