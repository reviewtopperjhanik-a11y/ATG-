package com.example.data.database

import androidx.room.Dao
import androidx.room.Embedded
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.RawQuery
import androidx.room.Upsert
import androidx.sqlite.db.SupportSQLiteQuery
import kotlinx.coroutines.flow.Flow

@Dao
interface ProjectDao {
    @Query("SELECT * FROM projects ORDER BY lastOpenedEpochMs DESC")
    fun observeRecentProjects(): Flow<List<ProjectEntity>>

    @Query("SELECT * FROM projects WHERE id = :projectId LIMIT 1")
    fun observeProjectById(projectId: String): Flow<ProjectEntity?>

    @Query("SELECT * FROM projects WHERE id = :projectId LIMIT 1")
    suspend fun getProjectById(projectId: String): ProjectEntity?

    @Query("SELECT COUNT(*) FROM projects")
    fun observeProjectCount(): Flow<Int>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertProject(project: ProjectEntity)

    @Query("UPDATE projects SET lastOpenedEpochMs = :timestampMs, stackLabel = :stackLabel WHERE id = :projectId")
    suspend fun updateOpenedMetadata(projectId: String, timestampMs: Long, stackLabel: String)

    @Query("UPDATE projects SET remoteUrl = :remoteUrl, branchName = :branchName WHERE id = :projectId")
    suspend fun updateGitMetadata(projectId: String, remoteUrl: String?, branchName: String?)

    @Query("DELETE FROM projects WHERE id = :projectId")
    suspend fun deleteProjectById(projectId: String)
}

@Dao
interface TaskDao {
    @Query("SELECT * FROM agent_tasks WHERE projectId = :projectId ORDER BY createdAtEpochMs ASC")
    fun observeTasksForProject(projectId: String): Flow<List<TaskEntity>>

    @Query("SELECT * FROM agent_tasks WHERE id = :taskId LIMIT 1")
    suspend fun getTaskById(taskId: String): TaskEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertTask(task: TaskEntity)

    @Query("UPDATE agent_tasks SET state = :state, updatedAtEpochMs = :updatedAtEpochMs WHERE id = :taskId")
    suspend fun updateTaskState(taskId: String, state: String, updatedAtEpochMs: Long)

    @Query("UPDATE agent_tasks SET state = :state, summaryJson = :summaryJson, updatedAtEpochMs = :updatedAtEpochMs WHERE id = :taskId")
    suspend fun updateTaskCompletion(taskId: String, state: String, summaryJson: String, updatedAtEpochMs: Long)

    @Query(
        "UPDATE agent_tasks SET state = 'PAUSED', updatedAtEpochMs = :updatedAtEpochMs " +
            "WHERE projectId = :projectId AND state IN ('CREATED','CONTEXT_LOADING','ANALYZING','RESEARCHING','COUNCIL_DELIBERATING','SYNTHESIZING','AWAITING_COUNCIL_APPROVAL','PLANNING','AWAITING_PLAN_APPROVAL','EXECUTING','AWAITING_TOOL_APPROVAL','VALIDATING','FIXING')"
    )
    suspend fun markActiveTasksPaused(projectId: String, updatedAtEpochMs: Long): Int

    @Query("SELECT COUNT(*) FROM agent_tasks")
    fun observeTaskCount(): Flow<Int>
}

@Dao
interface CheckpointDao {
    @Query("SELECT * FROM checkpoints WHERE projectId = :projectId ORDER BY createdAtEpochMs DESC")
    fun observeCheckpointsForProject(projectId: String): Flow<List<CheckpointEntity>>

    @Query("SELECT * FROM checkpoints WHERE projectId = :projectId ORDER BY createdAtEpochMs DESC")
    suspend fun getCheckpointsForProject(projectId: String): List<CheckpointEntity>

    @Query("SELECT * FROM checkpoints WHERE id = :checkpointId LIMIT 1")
    suspend fun getCheckpointById(checkpointId: String): CheckpointEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertCheckpoint(checkpoint: CheckpointEntity)

    @Query("SELECT COUNT(*) FROM checkpoints")
    fun observeCheckpointCount(): Flow<Int>

    @Query("DELETE FROM checkpoints WHERE id = :checkpointId")
    suspend fun deleteCheckpointById(checkpointId: String)
}

@Dao
interface CachedModelDao {
    @Query("SELECT * FROM cached_models ORDER BY id ASC")
    fun observeModels(): Flow<List<CachedModelEntity>>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertModels(models: List<CachedModelEntity>)

    @Query("DELETE FROM cached_models")
    suspend fun clearModels()

    @androidx.room.Transaction
    suspend fun replaceModels(models: List<CachedModelEntity>) {
        clearModels()
        if (models.isNotEmpty()) {
            insertModels(models)
        }
    }
}

@Dao
interface ExtensionDao {
    @Query("SELECT * FROM extensions ORDER BY name ASC")
    fun observeAll(): Flow<List<ExtensionEntity>>

    @Query("SELECT * FROM extensions ORDER BY name ASC")
    suspend fun getAll(): List<ExtensionEntity>

    @Query("SELECT * FROM extensions WHERE id = :id LIMIT 1")
    suspend fun getById(id: String): ExtensionEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(entity: ExtensionEntity)

    @Query("UPDATE extensions SET installState = :installState, updatedAtEpochMs = :updatedAtEpochMs WHERE id = :id")
    suspend fun updateState(id: String, installState: String, updatedAtEpochMs: Long)

    @Query(
        "UPDATE extensions SET discoveredToolsJson = :discoveredToolsJson, healthMessage = :healthMessage, " +
            "updatedAtEpochMs = :updatedAtEpochMs WHERE id = :id"
    )
    suspend fun updateHealthAndTools(
        id: String,
        discoveredToolsJson: String?,
        healthMessage: String?,
        updatedAtEpochMs: Long,
    )

    @Query("DELETE FROM extensions WHERE id = :id")
    suspend fun deleteById(id: String)
}

@Dao
interface ChatMessageDao {
    @Query("SELECT * FROM chat_messages WHERE projectId = :projectId ORDER BY createdAtEpochMs ASC")
    fun observeMessagesForProject(projectId: String): Flow<List<ChatMessageEntity>>

    @Query("SELECT * FROM chat_messages WHERE projectId = :projectId ORDER BY createdAtEpochMs ASC")
    suspend fun getMessagesForProject(projectId: String): List<ChatMessageEntity>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertMessage(message: ChatMessageEntity)

    @Query("DELETE FROM chat_messages WHERE projectId = :projectId")
    suspend fun clearMessagesForProject(projectId: String)
}

@Dao
interface TaskStateSnapshotDao {
    @Query("SELECT * FROM task_state_snapshots WHERE projectId = :projectId ORDER BY updatedAtEpochMs DESC")
    fun observeSnapshotsForProject(projectId: String): Flow<List<TaskStateSnapshotEntity>>

    @Query("SELECT * FROM task_state_snapshots WHERE projectId = :projectId ORDER BY updatedAtEpochMs DESC LIMIT 1")
    fun observeLatestSnapshotForProject(projectId: String): Flow<TaskStateSnapshotEntity?>

    @Query("SELECT * FROM task_state_snapshots WHERE taskId = :taskId LIMIT 1")
    suspend fun getSnapshotByTaskId(taskId: String): TaskStateSnapshotEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertSnapshot(snapshot: TaskStateSnapshotEntity)

    @Query(
        "UPDATE task_state_snapshots SET currentState = 'PAUSED', updatedAtEpochMs = :updatedAtEpochMs " +
            "WHERE projectId = :projectId AND currentState IN ('CREATED','CONTEXT_LOADING','ANALYZING','RESEARCHING','COUNCIL_DELIBERATING','SYNTHESIZING','AWAITING_COUNCIL_APPROVAL','PLANNING','AWAITING_PLAN_APPROVAL','EXECUTING','AWAITING_TOOL_APPROVAL','VALIDATING','FIXING')"
    )
    suspend fun markActiveSnapshotsPaused(projectId: String, updatedAtEpochMs: Long): Int

    @Query("DELETE FROM task_state_snapshots WHERE taskId = :taskId")
    suspend fun deleteSnapshotByTaskId(taskId: String)
}

@Dao
interface ProjectMemoryDao {
    @Query("SELECT * FROM project_memory_entries WHERE projectId = :projectId LIMIT 1")
    fun observeMemoryForProject(projectId: String): Flow<ProjectMemoryEntity?>

    @Query("SELECT * FROM project_memory_entries WHERE projectId = :projectId LIMIT 1")
    suspend fun getMemoryForProject(projectId: String): ProjectMemoryEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertMemory(memory: ProjectMemoryEntity)
}

@Dao
interface FlightRecorderDao {
    @Query("SELECT * FROM flight_recorder_events WHERE projectId = :projectId ORDER BY timestampMs DESC, seq DESC LIMIT :limit")
    fun observeEventsForProject(projectId: String, limit: Int = 500): Flow<List<FlightRecorderEventEntity>>

    @Query("SELECT * FROM flight_recorder_events WHERE taskId = :taskId ORDER BY seq ASC")
    fun observeEventsForTask(taskId: String): Flow<List<FlightRecorderEventEntity>>

    @Query("SELECT * FROM flight_recorder_events WHERE taskId = :taskId ORDER BY seq ASC")
    suspend fun getEventsForTask(taskId: String): List<FlightRecorderEventEntity>

    @Query("SELECT COALESCE(MAX(seq), 0) FROM flight_recorder_events WHERE taskId = :taskId")
    suspend fun getMaxSeqForTask(taskId: String): Long

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertEvent(event: FlightRecorderEventEntity)

    @Query("DELETE FROM flight_recorder_events WHERE projectId = :projectId")
    suspend fun clearEventsForProject(projectId: String)
}

data class KnowledgeFtsScoredRow(
    @Embedded val entity: ProjectKnowledgeEntity,
    val ftsRank: Double,
)

@Dao
interface ProjectKnowledgeDao {
    @Query("SELECT * FROM project_knowledge_entries WHERE projectId = :projectId ORDER BY updatedAtEpochMs DESC")
    fun observeAllForProject(projectId: String): Flow<List<ProjectKnowledgeEntity>>

    @Query(
        "SELECT * FROM project_knowledge_entries " +
            "WHERE projectId = :projectId AND supersededById IS NULL AND validUntilEpochMs IS NULL " +
            "ORDER BY updatedAtEpochMs DESC"
    )
    fun observeActiveForProject(projectId: String): Flow<List<ProjectKnowledgeEntity>>

    @Query("SELECT * FROM project_knowledge_entries WHERE projectId = :projectId ORDER BY updatedAtEpochMs DESC")
    suspend fun getAllForProject(projectId: String): List<ProjectKnowledgeEntity>

    @Query(
        "SELECT * FROM project_knowledge_entries " +
            "WHERE projectId = :projectId AND supersededById IS NULL AND validUntilEpochMs IS NULL " +
            "ORDER BY updatedAtEpochMs DESC"
    )
    suspend fun getActiveForProject(projectId: String): List<ProjectKnowledgeEntity>

    @Query(
        "SELECT * FROM project_knowledge_entries " +
            "WHERE projectId = :projectId AND factKey = :factKey " +
            "AND supersededById IS NULL AND validUntilEpochMs IS NULL " +
            "ORDER BY updatedAtEpochMs DESC"
    )
    suspend fun getActiveByFactKey(projectId: String, factKey: String): List<ProjectKnowledgeEntity>

    @Query("SELECT * FROM project_knowledge_entries WHERE id = :id LIMIT 1")
    suspend fun getById(id: String): ProjectKnowledgeEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertEntry(entry: ProjectKnowledgeEntity)

    @Query(
        "UPDATE project_knowledge_entries " +
            "SET supersededById = :supersededById, validUntilEpochMs = :validUntilEpochMs, updatedAtEpochMs = :updatedAtEpochMs " +
            "WHERE id = :id AND supersededById IS NULL"
    )
    suspend fun markSuperseded(
        id: String,
        supersededById: String,
        validUntilEpochMs: Long,
        updatedAtEpochMs: Long,
    )

    @Query(
        "UPDATE project_knowledge_entries " +
            "SET validUntilEpochMs = :validUntilEpochMs, updatedAtEpochMs = :updatedAtEpochMs " +
            "WHERE id = :id AND validUntilEpochMs IS NULL"
    )
    suspend fun invalidateById(
        id: String,
        validUntilEpochMs: Long,
        updatedAtEpochMs: Long,
    )

    @Query("DELETE FROM project_knowledge_entries WHERE projectId = :projectId")
    suspend fun deleteByProject(projectId: String)

    @RawQuery(observedEntities = [ProjectKnowledgeEntity::class])
    suspend fun searchFtsRaw(query: SupportSQLiteQuery): List<KnowledgeFtsScoredRow>
}

@Dao
interface CouncilDao {
    @Query("SELECT * FROM council_sessions WHERE projectId = :projectId ORDER BY updatedAtEpochMs DESC")
    fun observeSessionsForProject(projectId: String): Flow<List<CouncilSessionEntity>>

    @Query("SELECT * FROM council_sessions WHERE projectId = :projectId ORDER BY updatedAtEpochMs DESC")
    suspend fun getSessionsForProject(projectId: String): List<CouncilSessionEntity>

    @Query("SELECT * FROM council_sessions WHERE sessionId = :sessionId LIMIT 1")
    fun observeSessionById(sessionId: String): Flow<CouncilSessionEntity?>

    @Query("SELECT * FROM council_sessions WHERE sessionId = :sessionId LIMIT 1")
    suspend fun getSessionById(sessionId: String): CouncilSessionEntity?

    @Query("SELECT * FROM council_sessions WHERE taskId = :taskId LIMIT 1")
    suspend fun getSessionByTaskId(taskId: String): CouncilSessionEntity?

    @Upsert
    suspend fun upsertSession(session: CouncilSessionEntity)

    @Query("SELECT * FROM council_rounds WHERE sessionId = :sessionId ORDER BY roundNumber ASC")
    fun observeRoundsForSession(sessionId: String): Flow<List<CouncilRoundEntity>>

    @Query("SELECT * FROM council_rounds WHERE sessionId = :sessionId ORDER BY roundNumber ASC")
    suspend fun getRoundsForSession(sessionId: String): List<CouncilRoundEntity>

    @Query("SELECT * FROM council_rounds WHERE sessionId = :sessionId AND roundNumber = :roundNumber LIMIT 1")
    suspend fun getRound(sessionId: String, roundNumber: Int): CouncilRoundEntity?

    @Upsert
    suspend fun upsertRound(round: CouncilRoundEntity)

    @Query("DELETE FROM council_sessions WHERE sessionId = :sessionId")
    suspend fun deleteSessionById(sessionId: String)
}

