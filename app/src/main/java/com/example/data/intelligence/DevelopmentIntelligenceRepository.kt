package com.example.data.intelligence

import androidx.sqlite.db.SimpleSQLiteQuery
import com.example.agent.orchestrator.RepoMapEngine
import com.example.agent.orchestrator.RepositoryInspector
import com.example.core.security.SecretRedactor
import com.example.data.database.AgtDatabase
import com.example.data.database.ChatMessageDao
import com.example.data.database.ChatMessageEntity
import com.example.data.database.FlightRecorderDao
import com.example.data.database.FlightRecorderEventEntity
import com.example.data.database.ProjectKnowledgeDao
import com.example.data.database.ProjectKnowledgeEntity
import com.example.data.database.ProjectMemoryDao
import com.example.data.database.ProjectMemoryEntity
import com.example.data.database.TaskStateSnapshotDao
import com.example.data.database.TaskStateSnapshotEntity
import com.example.domain.model.ChatMessageRecord
import com.example.domain.model.ChatMessageRole
import com.example.domain.model.FlightRecorderEvent
import com.example.domain.model.FlightRecorderEventType
import com.example.domain.model.FlightRecorderOutcome
import com.example.domain.model.KnowledgeCategory
import com.example.domain.model.KnowledgeRetrievalHit
import com.example.domain.model.KnowledgeRetrievalResult
import com.example.domain.model.KnownFixEntry
import com.example.domain.model.PlanStep
import com.example.domain.model.PlanStepStatus
import com.example.domain.model.ProjectInspection
import com.example.domain.model.ProjectKnowledgeEntry
import com.example.domain.model.ProjectMemory
import com.example.domain.model.RepoMapResult
import com.example.domain.model.TaskResumeCursor
import com.example.domain.model.TaskState
import com.example.domain.model.TaskStateSnapshot
import com.example.domain.model.ValidationCheckResult
import com.example.domain.model.ValidationOutcome
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.util.UUID

/**
 * Native Kotlin + Room 4-Layer Development Intelligence Core + Phase 5 Long-Term Knowledge Engine:
 * 1. Chat History ([ChatMessageDao]) - human-readable UI transcript only
 * 2. Persistent Task State ([TaskStateSnapshotDao]) - authoritative resume cursor & plan steps
 * 3. Project Memory ([ProjectMemoryDao]) - curated stack/validation/repo-map/fix knowledge
 * 4. Flight Recorder ([FlightRecorderDao]) - append-only SQLite journal of transitions, tool checks, validations, and errors
 * 5. Long-Term Project Knowledge ([ProjectKnowledgeDao]) - SQLite FTS5 full-text index with temporal validity & superseding rules
 */
class DevelopmentIntelligenceRepository(
    private val chatMessageDao: ChatMessageDao,
    private val taskStateSnapshotDao: TaskStateSnapshotDao,
    private val projectMemoryDao: ProjectMemoryDao,
    private val flightRecorderDao: FlightRecorderDao,
    private val knownSecretsProvider: () -> Collection<String> = { emptyList() },
    val repoMapEngine: RepoMapEngine = RepoMapEngine(),
    private val projectKnowledgeDao: ProjectKnowledgeDao? = null,
    private val database: AgtDatabase? = null,
) {
    private val seqMutex = Mutex()
    private val knowledgeMutex = Mutex()
    private val taskSeqCache = mutableMapOf<String, Long>()

    fun observeChatMessages(projectId: String): Flow<List<ChatMessageRecord>> =
        chatMessageDao.observeMessagesForProject(projectId).map { list ->
            list.map { it.toDomain() }
        }

    fun observeTaskSnapshots(projectId: String): Flow<List<TaskStateSnapshot>> =
        taskStateSnapshotDao.observeSnapshotsForProject(projectId).map { list ->
            list.mapNotNull { it.toDomainOrNull() }
        }

    fun observeLatestTaskSnapshot(projectId: String): Flow<TaskStateSnapshot?> =
        taskStateSnapshotDao.observeLatestSnapshotForProject(projectId).map { it?.toDomainOrNull() }

    fun observeProjectMemory(projectId: String): Flow<ProjectMemory?> =
        projectMemoryDao.observeMemoryForProject(projectId).map { it?.toDomain() }

    fun observeFlightEventsForProject(projectId: String, limit: Int = 500): Flow<List<FlightRecorderEvent>> =
        flightRecorderDao.observeEventsForProject(projectId, limit).map { list ->
            list.map { it.toDomain() }
        }

    fun observeFlightEventsForTask(taskId: String): Flow<List<FlightRecorderEvent>> =
        flightRecorderDao.observeEventsForTask(taskId).map { list ->
            list.map { it.toDomain() }
        }

    fun observeActiveKnowledge(projectId: String): Flow<List<ProjectKnowledgeEntry>> =
        projectKnowledgeDao?.observeActiveForProject(projectId)?.map { list ->
            list.map { it.toDomain() }
        } ?: flowOf(emptyList())

    fun observeAllKnowledge(projectId: String): Flow<List<ProjectKnowledgeEntry>> =
        projectKnowledgeDao?.observeAllForProject(projectId)?.map { list ->
            list.map { it.toDomain() }
        } ?: flowOf(emptyList())

    suspend fun getTaskSnapshot(taskId: String): TaskStateSnapshot? = withContext(Dispatchers.IO) {
        taskStateSnapshotDao.getSnapshotByTaskId(taskId)?.toDomainOrNull()
    }

    suspend fun markActiveSnapshotsPaused(projectId: String): Int = withContext(Dispatchers.IO) {
        runCatching {
            taskStateSnapshotDao.markActiveSnapshotsPaused(projectId, System.currentTimeMillis())
        }.getOrDefault(0)
    }

    suspend fun getFlightEventsForTask(taskId: String): List<FlightRecorderEvent> = withContext(Dispatchers.IO) {
        flightRecorderDao.getEventsForTask(taskId).map { it.toDomain() }
    }

    suspend fun appendChatMessage(record: ChatMessageRecord) = withContext(Dispatchers.IO) {
        val secrets = knownSecretsProvider()
        runCatching {
            chatMessageDao.upsertMessage(
                ChatMessageEntity(
                    id = record.id,
                    projectId = record.projectId,
                    taskId = record.taskId,
                    role = record.role.name,
                    title = record.title?.let { SecretRedactor.redact(it, secrets) },
                    content = SecretRedactor.redact(record.content, secrets),
                    modelId = record.modelId,
                    backendId = record.backendId,
                    promptTokens = record.promptTokens,
                    completionTokens = record.completionTokens,
                    isWarning = record.isWarning,
                    createdAtEpochMs = record.createdAtEpochMs,
                )
            )
        }
    }

    suspend fun recordFlightEvent(
        projectId: String,
        taskId: String,
        eventType: FlightRecorderEventType,
        outcome: FlightRecorderOutcome,
        toolName: String? = null,
        commandOrPath: String? = null,
        exitCode: Int? = null,
        outputExcerpt: String = "",
        modelId: String? = null,
        backendId: String? = null,
        stepIndex: Int? = null,
        checkpointId: String? = null,
        timestampMs: Long = System.currentTimeMillis(),
    ): Long = withContext(Dispatchers.IO) {
        val secrets = knownSecretsProvider()
        val safeCommand = commandOrPath?.let { SecretRedactor.redact(it, secrets).take(MAX_COMMAND_CHARS) }
        val safeExcerpt = SecretRedactor.redact(outputExcerpt, secrets).take(MAX_EXCERPT_CHARS)
        val nextSeq = seqMutex.withLock {
            val currentMax = taskSeqCache[taskId] ?: flightRecorderDao.getMaxSeqForTask(taskId)
            val seq = currentMax + 1L
            taskSeqCache[taskId] = seq
            seq
        }
        runCatching {
            flightRecorderDao.insertEvent(
                FlightRecorderEventEntity(
                    id = UUID.randomUUID().toString(),
                    projectId = projectId,
                    taskId = taskId,
                    seq = nextSeq,
                    eventType = eventType.name,
                    toolName = toolName,
                    commandOrPath = safeCommand,
                    outcome = outcome.name,
                    exitCode = exitCode,
                    outputExcerpt = safeExcerpt,
                    modelId = modelId,
                    backendId = backendId,
                    stepIndex = stepIndex,
                    checkpointId = checkpointId,
                    timestampMs = timestampMs,
                )
            )
        }
        nextSeq
    }

    suspend fun saveTaskSnapshot(snapshot: TaskStateSnapshot) = withContext(Dispatchers.IO) {
        val secrets = knownSecretsProvider()
        val safeObjective = SecretRedactor.redact(snapshot.objective, secrets)
        val safeDecisions = snapshot.keyDecisions.map { SecretRedactor.redact(it, secrets) }
        val safeCursor = snapshot.resumeCursor.copy(
            lastError = snapshot.resumeCursor.lastError?.let { SecretRedactor.redact(it, secrets).take(800) },
            pendingApprovalTitle = snapshot.resumeCursor.pendingApprovalTitle?.let { SecretRedactor.redact(it, secrets) },
        )
        runCatching {
            taskStateSnapshotDao.upsertSnapshot(
                TaskStateSnapshotEntity(
                    taskId = snapshot.taskId,
                    projectId = snapshot.projectId,
                    objective = safeObjective,
                    currentState = snapshot.currentState.name,
                    activeStepIndex = snapshot.activeStepIndex,
                    serializedPlanStepsJson = encodePlanSteps(snapshot.planSteps),
                    keyDecisionsJson = encodeStringList(safeDecisions),
                    modifiedFilesJson = encodeStringList(snapshot.modifiedFiles),
                    resumePayloadJson = encodeResumeCursor(safeCursor),
                    lastModelId = snapshot.lastModelId,
                    lastBackendId = snapshot.lastBackendId,
                    updatedAtEpochMs = snapshot.updatedAtEpochMs,
                )
            )
        }
    }

    suspend fun upsertProjectMemoryFromInspection(inspection: ProjectInspection) = withContext(Dispatchers.IO) {
        val now = System.currentTimeMillis()
        val existing = projectMemoryDao.getMemoryForProject(inspection.projectId)
        val validationMeta = inspection.validationCommands.map { "${it.label}: ${it.command}" }
        val defaultRules = if (existing != null && existing.curatedRulesJson != "[]") {
            decodeStringList(existing.curatedRulesJson)
        } else {
            buildList {
                add("Confine all file operations within ${inspection.projectRootPath}")
                if (inspection.entryPoints.isNotEmpty()) {
                    add("Key entry points: ${inspection.entryPoints.take(5).joinToString(", ")}")
                }
            }
        }
        val repoMapText = inspection.repoMap.takeIf { it.isNotBlank() } ?: existing?.repoMapCache.orEmpty()
        val repoMapTime = if (inspection.repoMap.isNotBlank()) now else (existing?.repoMapUpdatedAtEpochMs ?: 0L)
        runCatching {
            projectMemoryDao.upsertMemory(
                ProjectMemoryEntity(
                    projectId = inspection.projectId,
                    stackLabel = inspection.stackLabel,
                    curatedRulesJson = encodeStringList(defaultRules),
                    validationMetadataJson = encodeStringList(validationMeta),
                    repoMapCache = repoMapText,
                    repoMapUpdatedAtEpochMs = repoMapTime,
                    knownFixesJson = existing?.knownFixesJson ?: "[]",
                    updatedAtEpochMs = now,
                )
            )
        }
        if (validationMeta.isNotEmpty()) {
            recordKnowledgeFact(
                projectId = inspection.projectId,
                factKey = "validation:commands",
                category = KnowledgeCategory.VALIDATION_INSIGHT,
                title = "${inspection.stackLabel} validation commands",
                content = validationMeta.joinToString("; "),
                tags = "${inspection.stackLabel} validation build test",
                confidence = 0.9f,
                nowEpochMs = now,
            )
        }
        if (inspection.entryPoints.isNotEmpty()) {
            recordKnowledgeFact(
                projectId = inspection.projectId,
                factKey = "conventions:entrypoints",
                category = KnowledgeCategory.CONVENTIONS,
                title = "${inspection.stackLabel} workspace entry points",
                content = "Primary entry points: ${inspection.entryPoints.take(6).joinToString(", ")}",
                tags = "${inspection.stackLabel} entrypoint ${inspection.entryPoints.take(4).joinToString(" ")}",
                confidence = 0.85f,
                nowEpochMs = now,
            )
        }
    }

    suspend fun getOrBuildRepoMap(
        inspection: ProjectInspection,
        recentlyEditedPaths: Collection<String> = emptyList(),
        objectiveHint: String = "",
        tokenBudget: Int = RepoMapEngine.DEFAULT_TOKEN_BUDGET,
    ): RepoMapResult = withContext(Dispatchers.IO) {
        val existing = projectMemoryDao.getMemoryForProject(inspection.projectId)
        val persistedValid = existing?.repoMapCache?.isNotBlank() == true && existing.repoMapUpdatedAtEpochMs > 0L
        val result = repoMapEngine.buildFromInspection(
            inspection = inspection,
            recentlyEditedPaths = recentlyEditedPaths,
            objectiveHint = objectiveHint,
            tokenBudget = tokenBudget,
            persistedCachePresent = persistedValid,
        )
        if (!result.cacheHit || existing?.repoMapCache != result.renderedMap) {
            val secrets = knownSecretsProvider()
            val redactedMap = SecretRedactor.redact(result.renderedMap, secrets)
            val now = result.generatedAtEpochMs
            val validationMeta = inspection.validationCommands.map { "${it.label}: ${it.command}" }
            val rulesJson = existing?.curatedRulesJson ?: encodeStringList(
                buildList {
                    add("Confine all file operations within ${inspection.projectRootPath}")
                    if (inspection.entryPoints.isNotEmpty()) {
                        add("Key entry points: ${inspection.entryPoints.take(5).joinToString(", ")}")
                    }
                }
            )
            runCatching {
                projectMemoryDao.upsertMemory(
                    ProjectMemoryEntity(
                        projectId = inspection.projectId,
                        stackLabel = inspection.stackLabel,
                        curatedRulesJson = rulesJson,
                        validationMetadataJson = if (existing?.validationMetadataJson?.isNotBlank() == true) {
                            existing.validationMetadataJson
                        } else {
                            encodeStringList(validationMeta)
                        },
                        repoMapCache = redactedMap,
                        repoMapUpdatedAtEpochMs = now,
                        knownFixesJson = existing?.knownFixesJson ?: "[]",
                        updatedAtEpochMs = now,
                    )
                )
            }
            return@withContext result.copy(renderedMap = redactedMap)
        }
        result
    }

    suspend fun rebuildRepoMap(
        projectId: String,
        projectRootPath: String,
        recentlyEditedPaths: Collection<String> = emptyList(),
        objectiveHint: String = "",
        tokenBudget: Int = RepoMapEngine.DEFAULT_TOKEN_BUDGET,
    ): Result<RepoMapResult> = withContext(Dispatchers.IO) {
        val inspection = RepositoryInspector.inspect(projectId, projectRootPath).getOrElse {
            return@withContext Result.failure(it)
        }
        val res = getOrBuildRepoMap(
            inspection = inspection,
            recentlyEditedPaths = recentlyEditedPaths,
            objectiveHint = objectiveHint,
            tokenBudget = tokenBudget,
        )
        Result.success(res)
    }

    suspend fun invalidateRepoMap(
        projectId: String,
        changedRelativePaths: Collection<String> = emptyList(),
    ) = withContext(Dispatchers.IO) {
        repoMapEngine.invalidate(projectId, changedRelativePaths)
        val existing = projectMemoryDao.getMemoryForProject(projectId) ?: return@withContext
        if (existing.repoMapUpdatedAtEpochMs != 0L) {
            runCatching {
                projectMemoryDao.upsertMemory(
                    existing.copy(repoMapUpdatedAtEpochMs = 0L)
                )
            }
        }
    }

    suspend fun recordKnownFix(
        projectId: String,
        errorSignature: String,
        resolutionNote: String,
        stackLabel: String = "Local Workspace",
    ) = withContext(Dispatchers.IO) {
        val secrets = knownSecretsProvider()
        val safeSig = SecretRedactor.redact(errorSignature, secrets).take(300)
        val safeNote = SecretRedactor.redact(resolutionNote, secrets).take(500)
        val now = System.currentTimeMillis()
        val existing = projectMemoryDao.getMemoryForProject(projectId)
        val currentFixes = existing?.let { decodeKnownFixes(it.knownFixesJson) }.orEmpty()
        val updatedFixes = (currentFixes.filterNot { it.errorSignature == safeSig } + KnownFixEntry(
            errorSignature = safeSig,
            resolutionNote = safeNote,
            recordedAtEpochMs = now,
        )).takeLast(MAX_KNOWN_FIXES)

        runCatching {
            projectMemoryDao.upsertMemory(
                ProjectMemoryEntity(
                    projectId = projectId,
                    stackLabel = existing?.stackLabel ?: stackLabel,
                    curatedRulesJson = existing?.curatedRulesJson ?: "[]",
                    validationMetadataJson = existing?.validationMetadataJson ?: "[]",
                    repoMapCache = existing?.repoMapCache.orEmpty(),
                    repoMapUpdatedAtEpochMs = existing?.repoMapUpdatedAtEpochMs ?: 0L,
                    knownFixesJson = encodeKnownFixes(updatedFixes),
                    updatedAtEpochMs = now,
                )
            )
        }
        recordKnowledgeFact(
            projectId = projectId,
            factKey = "fix:${normalizeFactKey(safeSig)}",
            category = KnowledgeCategory.KNOWN_FIX,
            title = "Fix: ${safeSig.take(80)}",
            content = safeNote,
            tags = "$stackLabel fix $safeSig",
            confidence = 0.92f,
            nowEpochMs = now,
        )
    }

    /**
     * Records a temporal fact keyed by [factKey] for [projectId].
     * Any currently-active entry with the same normalized [factKey] is closed out cleanly via
     * `supersededById` and `validUntilEpochMs` unless the title, category, and content are identical
     * (in which case confidence & updated timestamp are reinforced without duplicating).
     */
    suspend fun recordKnowledgeFact(
        projectId: String,
        factKey: String,
        category: KnowledgeCategory,
        title: String,
        content: String,
        tags: String = "",
        sourceTaskId: String? = null,
        confidence: Float = 0.85f,
        nowEpochMs: Long = System.currentTimeMillis(),
    ): ProjectKnowledgeEntry? = withContext(Dispatchers.IO) {
        val dao = projectKnowledgeDao ?: return@withContext null
        ensureFtsIndexReady()
        val secrets = knownSecretsProvider()
        val safeKey = normalizeFactKey(SecretRedactor.redact(factKey, secrets))
        val safeTitle = SecretRedactor.redact(title.trim(), secrets).take(MAX_KNOWLEDGE_TITLE_CHARS)
        val safeContent = SecretRedactor.redact(content.trim(), secrets).take(MAX_KNOWLEDGE_CONTENT_CHARS)
        val safeTags = SecretRedactor.redact(tags.trim(), secrets).take(MAX_KNOWLEDGE_TAGS_CHARS)
        if (safeKey.isBlank() || safeTitle.isBlank() || safeContent.isBlank()) return@withContext null
        val boundedConfidence = confidence.coerceIn(0.05f, 1.0f)

        knowledgeMutex.withLock {
            val activeExisting = dao.getActiveByFactKey(projectId, safeKey)
            val identical = activeExisting.firstOrNull {
                it.category == category.name &&
                    it.title.equals(safeTitle, ignoreCase = true) &&
                    it.content.equals(safeContent, ignoreCase = true)
            }
            if (identical != null) {
                val reinforced = identical.copy(
                    tags = safeTags.ifBlank { identical.tags },
                    sourceTaskId = sourceTaskId ?: identical.sourceTaskId,
                    confidence = maxOf(identical.confidence, boundedConfidence),
                    updatedAtEpochMs = maxOf(identical.updatedAtEpochMs, nowEpochMs),
                )
                dao.upsertEntry(reinforced)
                return@withLock reinforced.toDomain()
            }

            val newId = UUID.randomUUID().toString()
            val newValidFrom = activeExisting.maxOfOrNull { it.validFromEpochMs }?.let { prevFrom ->
                if (nowEpochMs <= prevFrom) prevFrom + 1L else nowEpochMs
            } ?: nowEpochMs

            for (old in activeExisting) {
                val closedUntil = maxOf(old.validFromEpochMs, newValidFrom)
                dao.markSuperseded(
                    id = old.id,
                    supersededById = newId,
                    validUntilEpochMs = closedUntil,
                    updatedAtEpochMs = nowEpochMs,
                )
            }

            val entity = ProjectKnowledgeEntity(
                id = newId,
                projectId = projectId,
                factKey = safeKey,
                category = category.name,
                title = safeTitle,
                content = safeContent,
                tags = safeTags,
                sourceTaskId = sourceTaskId,
                confidence = boundedConfidence,
                validFromEpochMs = newValidFrom,
                validUntilEpochMs = null,
                supersededById = null,
                createdAtEpochMs = nowEpochMs,
                updatedAtEpochMs = nowEpochMs,
            )
            dao.upsertEntry(entity)
            entity.toDomain()
        }
    }

    suspend fun invalidateKnowledgeEntry(
        entryId: String,
        nowEpochMs: Long = System.currentTimeMillis(),
    ) = withContext(Dispatchers.IO) {
        val dao = projectKnowledgeDao ?: return@withContext
        dao.invalidateById(
            id = entryId,
            validUntilEpochMs = nowEpochMs,
            updatedAtEpochMs = nowEpochMs,
        )
    }

    suspend fun indexFlightRecorderResolutionsForTask(
        projectId: String,
        taskId: String,
        stackLabel: String,
        modelId: String?,
        backendId: String?,
        finalChecks: List<ValidationCheckResult>,
        fixAttempts: Int,
        nowEpochMs: Long = System.currentTimeMillis(),
    ) = withContext(Dispatchers.IO) {
        if (projectKnowledgeDao == null) return@withContext
        val events = runCatching { flightRecorderDao.getEventsForTask(taskId) }.getOrDefault(emptyList())
        if (events.isEmpty()) return@withContext

        val passedLabels = finalChecks
            .filter { it.status == ValidationOutcome.PASSED }
            .mapNotNull { it.label.takeIf(String::isNotBlank) }
            .toSet()

        val failedValidations = events.filter { ev ->
            ev.eventType == FlightRecorderEventType.VALIDATION_RUN.name &&
                (ev.outcome == FlightRecorderOutcome.FAILED.name || ev.outcome == FlightRecorderOutcome.TIMED_OUT.name)
        }
        val groupedByCheck = failedValidations.groupBy { it.toolName ?: it.commandOrPath ?: "validation" }
        for ((checkName, failures) in groupedByCheck) {
            if (checkName !in passedLabels && finalChecks.any { it.status == ValidationOutcome.FAILED }) continue
            val lastFailure = failures.lastOrNull() ?: continue
            val errSnippet = lastFailure.outputExcerpt
                .lineSequence()
                .map { it.trim() }
                .firstOrNull { it.isNotBlank() && !it.endsWith("ms") }
                ?.take(140)
                ?: "exit=${lastFailure.exitCode ?: "?"}"
            val fixNote = buildString {
                append("Resolved validation failure in '$checkName' (${lastFailure.commandOrPath.orEmpty()})")
                append(" [error: $errSnippet]")
                if (fixAttempts > 0) {
                    append(" after $fixAttempts auto-debug pass(es)")
                }
                if (!backendId.isNullOrBlank() || !modelId.isNullOrBlank()) {
                    append(" via ${backendId ?: "agent"}/${modelId ?: "model"}")
                }
                append(".")
            }
            recordKnowledgeFact(
                projectId = projectId,
                factKey = "validation-fix:${normalizeFactKey(checkName)}",
                category = KnowledgeCategory.KNOWN_FIX,
                title = "Resolved $checkName failure",
                content = fixNote,
                tags = "$stackLabel validation $checkName ${lastFailure.commandOrPath.orEmpty()}",
                sourceTaskId = taskId,
                confidence = 0.9f,
                nowEpochMs = nowEpochMs,
            )
        }
    }

    suspend fun recordTaskCompletionKnowledge(
        projectId: String,
        taskId: String,
        objective: String,
        keyDecisions: List<String>,
        changedFiles: List<String>,
        stackLabel: String,
        modelId: String?,
        backendId: String?,
        nowEpochMs: Long = System.currentTimeMillis(),
    ) = withContext(Dispatchers.IO) {
        if (projectKnowledgeDao == null) return@withContext
        val cleanFiles = changedFiles.map { it.substringAfter(": ").trim() }.filter { it.isNotBlank() }.distinct()
        val substantiveDecisions = keyDecisions.filterNot { dec ->
            dec.startsWith("Detected stack ") ||
                dec.startsWith("Pre-task checkpoint ") ||
                dec.startsWith("Pre-resume checkpoint ") ||
                dec.startsWith("Model Handoff:") ||
                dec.startsWith("Execution plan ") ||
                dec.startsWith("Step 1 already completed")
        }
        if (cleanFiles.isEmpty() && substantiveDecisions.isEmpty()) return@withContext

        val primaryTarget = cleanFiles.firstOrNull() ?: objective.take(48)
        val factKey = "decision:${normalizeFactKey(primaryTarget)}"
        val title = objective.trim().replace('\n', ' ').take(90)
        val content = buildString {
            if (cleanFiles.isNotEmpty()) {
                append("Modified: ${cleanFiles.take(6).joinToString(", ")}. ")
            }
            if (substantiveDecisions.isNotEmpty()) {
                append("Decisions: ${substantiveDecisions.take(3).joinToString("; ")}. ")
            }
            if (!backendId.isNullOrBlank() || !modelId.isNullOrBlank()) {
                append("Completed by ${backendId ?: "agent"}/${modelId ?: "model"}.")
            }
        }.trim()
        val tags = buildString {
            append(stackLabel)
            cleanFiles.take(6).forEach { path ->
                append(' ').append(path)
                val ext = path.substringAfterLast('.', "")
                if (ext.isNotBlank() && ext.length <= 8) append(' ').append(ext)
            }
        }
        recordKnowledgeFact(
            projectId = projectId,
            factKey = factKey,
            category = KnowledgeCategory.ARCHITECTURAL_DECISION,
            title = title,
            content = content,
            tags = tags,
            sourceTaskId = taskId,
            confidence = 0.85f,
            nowEpochMs = nowEpochMs,
        )
    }

    /**
     * Searches long-term project knowledge using SQLite FTS5 (`bm25` ranking with Porter stemming)
     * paired with temporal validity filtering and strict token-budget packing.
     *
     * @param asOfEpochMs When null (default), only active (`supersededById == null && validUntilEpochMs == null`)
     * facts are returned unless [includeSuperseded] is true. When specified, returns facts valid at that exact timestamp.
     */
    suspend fun retrieveRelevantKnowledge(
        projectId: String,
        query: String,
        categories: Set<KnowledgeCategory>? = null,
        limit: Int = 8,
        tokenBudget: Int = KnowledgeRetrievalResult.DEFAULT_KNOWLEDGE_TOKEN_BUDGET,
        asOfEpochMs: Long? = null,
        includeSuperseded: Boolean = false,
        nowEpochMs: Long = System.currentTimeMillis(),
    ): KnowledgeRetrievalResult = withContext(Dispatchers.IO) {
        val dao = projectKnowledgeDao
        if (dao == null) {
            return@withContext retrieveFromLegacyMemoryFallback(
                projectId = projectId,
                query = query,
                categories = categories,
                limit = limit,
                tokenBudget = tokenBudget,
            )
        }

        ensureFtsIndexReady()
        val db = database?.openHelper?.writableDatabase
        val fts5Active = db != null && AgtDatabase.isFts5Active(db)
        val fts4Active = !fts5Active && db != null && AgtDatabase.isFts4FallbackActive(db)

        val allEntries = dao.getAllForProject(projectId).map { it.toDomain() }
        val activeCount = allEntries.count { it.isCurrentlyValid }
        val tokens = tokenizeFtsQuery(query)

        var usedFts5 = false
        val scoredCandidates = mutableListOf<KnowledgeRetrievalHit>()

        if (tokens.isNotEmpty() && (fts5Active || fts4Active)) {
            val matchExpr = if (fts5Active) {
                tokens.joinToString(" OR ") { "\"$it\"*" }
            } else {
                tokens.joinToString(" OR ") { "$it*" }
            }
            val args = mutableListOf<Any>()
            val sql = buildString {
                if (fts5Active) {
                    append(
                        "SELECT e.*, f.ftsRank AS ftsRank FROM project_knowledge_entries e " +
                            "INNER JOIN (SELECT entryId, (-bm25(`${AgtDatabase.FTS5_TABLE_NAME}`, 0.0, 0.0, 2.5, 1.0, 1.8, 1.4)) AS ftsRank " +
                            "FROM `${AgtDatabase.FTS5_TABLE_NAME}` WHERE `${AgtDatabase.FTS5_TABLE_NAME}` MATCH ? AND `projectId` = ?) f " +
                            "ON f.entryId = e.id "
                    )
                } else {
                    append(
                        "SELECT e.*, 1.0 AS ftsRank FROM project_knowledge_entries e " +
                            "INNER JOIN (SELECT entryId " +
                            "FROM `${AgtDatabase.FTS4_TABLE_NAME}` WHERE `${AgtDatabase.FTS4_TABLE_NAME}` MATCH ? AND `projectId` = ?) f " +
                            "ON f.entryId = e.id "
                    )
                }
                args += matchExpr
                args += projectId
                append("WHERE e.projectId = ? ")
                args += projectId

                if (asOfEpochMs != null) {
                    append("AND e.validFromEpochMs <= ? AND (e.validUntilEpochMs IS NULL OR e.validUntilEpochMs > ?) ")
                    args += asOfEpochMs
                    args += asOfEpochMs
                } else if (!includeSuperseded) {
                    append("AND e.supersededById IS NULL AND e.validUntilEpochMs IS NULL ")
                }

                if (!categories.isNullOrEmpty()) {
                    val placeholders = categories.joinToString(", ") { "?" }
                    append("AND e.category IN ($placeholders) ")
                    categories.forEach { args += it.name }
                }

                append("ORDER BY ftsRank DESC, e.updatedAtEpochMs DESC LIMIT ?")
                args += (limit * 3).coerceAtLeast(15)
            }

            val rawHits = runCatching {
                dao.searchFtsRaw(SimpleSQLiteQuery(sql, args.toTypedArray()))
            }.getOrNull()

            if (rawHits != null) {
                usedFts5 = fts5Active
                for (row in rawHits) {
                    val domain = row.entity.toDomain()
                    val recency = computeRecencyScore(domain.updatedAtEpochMs, nowEpochMs)
                    val relevance = if (fts5Active) {
                        row.ftsRank.coerceAtLeast(0.05)
                    } else {
                        scoreTokenOverlap(domain, tokens).coerceAtLeast(0.2)
                    }
                    val score = relevance + (domain.confidence * 0.6) + (recency * 0.4)
                    scoredCandidates += KnowledgeRetrievalHit(
                        entry = domain,
                        score = score,
                        matchedViaFts5 = fts5Active,
                    )
                }
            }
        }

        // Supplement / fallback with Kotlin temporal filtering & keyword scoring
        if (scoredCandidates.isEmpty()) {
            val temporallyEligible = allEntries.filter { entry ->
                val temporalMatch = when {
                    asOfEpochMs != null -> entry.isValidAt(asOfEpochMs)
                    includeSuperseded -> true
                    else -> entry.isCurrentlyValid
                }
                val catMatch = categories.isNullOrEmpty() || entry.category in categories
                temporalMatch && catMatch
            }
            for (entry in temporallyEligible) {
                val overlap = if (tokens.isEmpty()) 0.2 else scoreTokenOverlap(entry, tokens)
                if (tokens.isNotEmpty() && overlap <= 0.0) continue
                val recency = computeRecencyScore(entry.updatedAtEpochMs, nowEpochMs)
                val score = overlap + (entry.confidence * 0.6) + (recency * 0.4)
                scoredCandidates += KnowledgeRetrievalHit(
                    entry = entry,
                    score = score,
                    matchedViaFts5 = false,
                )
            }
        }

        val ordered = scoredCandidates
            .distinctBy { it.entry.id }
            .sortedWith(compareByDescending<KnowledgeRetrievalHit> { it.score }.thenByDescending { it.entry.updatedAtEpochMs })
            .take(limit.coerceAtLeast(1))

        val packed = packWithinTokenBudget(ordered, tokenBudget)
        val renderedTokens = KnowledgeRetrievalResult(
            query = query,
            hits = packed,
            totalActiveEntries = activeCount,
            usedFts5 = usedFts5,
            estimatedTokens = 0,
        ).let { KnowledgeRetrievalResult.estimateTokens(it.toPromptContext(tokenBudget)) }

        KnowledgeRetrievalResult(
            query = query,
            hits = packed,
            totalActiveEntries = activeCount,
            usedFts5 = usedFts5,
            estimatedTokens = renderedTokens,
        )
    }

    private suspend fun retrieveFromLegacyMemoryFallback(
        projectId: String,
        query: String,
        categories: Set<KnowledgeCategory>?,
        limit: Int,
        tokenBudget: Int,
    ): KnowledgeRetrievalResult {
        val mem = projectMemoryDao.getMemoryForProject(projectId)?.toDomain()
            ?: return KnowledgeRetrievalResult(query, emptyList(), 0, usedFts5 = false, estimatedTokens = 0)
        val tokens = tokenizeFtsQuery(query)
        val synthetic = buildList {
            mem.knownFixes.forEachIndexed { idx, fix ->
                add(
                    ProjectKnowledgeEntry(
                        id = "legacy-fix-$idx-${fix.errorSignature.hashCode()}",
                        projectId = projectId,
                        factKey = "fix:${normalizeFactKey(fix.errorSignature)}",
                        category = KnowledgeCategory.KNOWN_FIX,
                        title = "Fix: ${fix.errorSignature.take(80)}",
                        content = fix.resolutionNote,
                        tags = mem.stackLabel,
                        confidence = 0.85f,
                        validFromEpochMs = fix.recordedAtEpochMs,
                        createdAtEpochMs = fix.recordedAtEpochMs,
                        updatedAtEpochMs = fix.recordedAtEpochMs,
                    )
                )
            }
        }.filter { categories.isNullOrEmpty() || it.category in categories }

        val hits = synthetic.mapNotNull { entry ->
            val overlap = if (tokens.isEmpty()) 0.2 else scoreTokenOverlap(entry, tokens)
            if (tokens.isNotEmpty() && overlap <= 0.0) return@mapNotNull null
            KnowledgeRetrievalHit(entry = entry, score = overlap + entry.confidence, matchedViaFts5 = false)
        }.sortedByDescending { it.score }.take(limit)

        val packed = packWithinTokenBudget(hits, tokenBudget)
        val tokensUsed = KnowledgeRetrievalResult.estimateTokens(
            KnowledgeRetrievalResult(query, packed, synthetic.size, false, 0).toPromptContext(tokenBudget)
        )
        return KnowledgeRetrievalResult(
            query = query,
            hits = packed,
            totalActiveEntries = synthetic.size,
            usedFts5 = false,
            estimatedTokens = tokensUsed,
        )
    }

    private fun ensureFtsIndexReady() {
        val db = database?.openHelper?.writableDatabase ?: return
        runCatching { AgtDatabase.ensureKnowledgeFtsIndex(db) }
    }

    private fun packWithinTokenBudget(
        hits: List<KnowledgeRetrievalHit>,
        tokenBudget: Int,
    ): List<KnowledgeRetrievalHit> {
        if (hits.isEmpty()) return emptyList()
        val headerTokens = KnowledgeRetrievalResult.estimateTokens("[RECALLED LONG-TERM PROJECT KNOWLEDGE]\n")
        var used = headerTokens
        val out = mutableListOf<KnowledgeRetrievalHit>()
        for (hit in hits) {
            val e = hit.entry
            val line = "- [${e.category.label}] ${e.title}: ${e.content}\n"
            val lineTokens = KnowledgeRetrievalResult.estimateTokens(line)
            if (used + lineTokens > tokenBudget && out.isNotEmpty()) break
            if (used + lineTokens <= tokenBudget || out.isEmpty()) {
                out += hit
                used += lineTokens
            }
        }
        return out
    }

    private fun computeRecencyScore(updatedAtEpochMs: Long, nowEpochMs: Long): Double {
        val ageDays = ((nowEpochMs - updatedAtEpochMs).coerceAtLeast(0L)).toDouble() / 86_400_000.0
        return 1.0 / (1.0 + ageDays / 14.0)
    }

    private fun scoreTokenOverlap(entry: ProjectKnowledgeEntry, tokens: List<String>): Double {
        if (tokens.isEmpty()) return 0.0
        val titleLower = entry.title.lowercase()
        val tagsLower = entry.tags.lowercase()
        val keyLower = entry.factKey.lowercase()
        val contentLower = entry.content.lowercase()
        var score = 0.0
        for (tok in tokens) {
            if (titleLower.contains(tok)) score += 2.5
            if (tagsLower.contains(tok)) score += 1.8
            if (keyLower.contains(tok)) score += 1.4
            if (contentLower.contains(tok)) score += 1.0
        }
        return score
    }

    private fun ChatMessageEntity.toDomain(): ChatMessageRecord = ChatMessageRecord(
        id = id,
        projectId = projectId,
        taskId = taskId,
        role = runCatching { ChatMessageRole.valueOf(role) }.getOrDefault(ChatMessageRole.SYSTEM_NOTICE),
        title = title,
        content = content,
        modelId = modelId,
        backendId = backendId,
        promptTokens = promptTokens,
        completionTokens = completionTokens,
        isWarning = isWarning,
        createdAtEpochMs = createdAtEpochMs,
    )

    private fun FlightRecorderEventEntity.toDomain(): FlightRecorderEvent = FlightRecorderEvent(
        id = id,
        projectId = projectId,
        taskId = taskId,
        seq = seq,
        eventType = runCatching { FlightRecorderEventType.valueOf(eventType) }
            .getOrDefault(FlightRecorderEventType.STATE_TRANSITION),
        toolName = toolName,
        commandOrPath = commandOrPath,
        outcome = runCatching { FlightRecorderOutcome.valueOf(outcome) }
            .getOrDefault(FlightRecorderOutcome.INFO),
        exitCode = exitCode,
        outputExcerpt = outputExcerpt,
        modelId = modelId,
        backendId = backendId,
        stepIndex = stepIndex,
        checkpointId = checkpointId,
        timestampMs = timestampMs,
    )

    private fun TaskStateSnapshotEntity.toDomainOrNull(): TaskStateSnapshot? = runCatching {
        TaskStateSnapshot(
            taskId = taskId,
            projectId = projectId,
            objective = objective,
            currentState = runCatching { TaskState.valueOf(currentState) }.getOrDefault(TaskState.PAUSED),
            activeStepIndex = activeStepIndex,
            planSteps = decodePlanSteps(serializedPlanStepsJson),
            keyDecisions = decodeStringList(keyDecisionsJson),
            modifiedFiles = decodeStringList(modifiedFilesJson),
            resumeCursor = decodeResumeCursor(resumePayloadJson),
            lastModelId = lastModelId,
            lastBackendId = lastBackendId,
            updatedAtEpochMs = updatedAtEpochMs,
        )
    }.getOrNull()

    private fun ProjectMemoryEntity.toDomain(): ProjectMemory = ProjectMemory(
        projectId = projectId,
        stackLabel = stackLabel,
        curatedRules = decodeStringList(curatedRulesJson),
        validationCommands = decodeStringList(validationMetadataJson),
        repoMapCache = repoMapCache,
        repoMapUpdatedAtEpochMs = repoMapUpdatedAtEpochMs,
        knownFixes = decodeKnownFixes(knownFixesJson),
        updatedAtEpochMs = updatedAtEpochMs,
    )

    private fun ProjectKnowledgeEntity.toDomain(): ProjectKnowledgeEntry = ProjectKnowledgeEntry(
        id = id,
        projectId = projectId,
        factKey = factKey,
        category = runCatching { KnowledgeCategory.valueOf(category) }
            .getOrDefault(KnowledgeCategory.ARCHITECTURAL_DECISION),
        title = title,
        content = content,
        tags = tags,
        sourceTaskId = sourceTaskId,
        confidence = confidence,
        validFromEpochMs = validFromEpochMs,
        validUntilEpochMs = validUntilEpochMs,
        supersededById = supersededById,
        createdAtEpochMs = createdAtEpochMs,
        updatedAtEpochMs = updatedAtEpochMs,
    )

    private fun encodePlanSteps(steps: List<PlanStep>): String {
        val arr = JSONArray()
        steps.forEach { step ->
            arr.put(
                JSONObject()
                    .put("index", step.index)
                    .put("title", step.title)
                    .put("status", step.status.name)
            )
        }
        return arr.toString()
    }

    private fun decodePlanSteps(raw: String): List<PlanStep> {
        if (raw.isBlank()) return emptyList()
        return runCatching {
            val arr = JSONArray(raw)
            (0 until arr.length()).mapNotNull { idx ->
                val obj = arr.optJSONObject(idx) ?: return@mapNotNull null
                PlanStep(
                    index = obj.optInt("index", idx),
                    title = obj.optString("title"),
                    status = runCatching { PlanStepStatus.valueOf(obj.optString("status")) }
                        .getOrDefault(PlanStepStatus.PENDING),
                )
            }
        }.getOrDefault(emptyList())
    }

    private fun encodeStringList(items: List<String>): String {
        val arr = JSONArray()
        items.forEach { arr.put(it) }
        return arr.toString()
    }

    private fun decodeStringList(raw: String): List<String> {
        if (raw.isBlank()) return emptyList()
        return runCatching {
            val arr = JSONArray(raw)
            (0 until arr.length()).mapNotNull { arr.optString(it).takeIf(String::isNotBlank) }
        }.getOrDefault(emptyList())
    }

    private fun encodeResumeCursor(cursor: TaskResumeCursor): String =
        JSONObject()
            .put("checkpointId", cursor.checkpointId ?: "")
            .put("fixAttempts", cursor.fixAttempts)
            .put("lastError", cursor.lastError ?: "")
            .put("lastEventSeq", cursor.lastEventSeq)
            .put("pendingApprovalId", cursor.pendingApprovalId ?: "")
            .put("pendingApprovalTitle", cursor.pendingApprovalTitle ?: "")
            .toString()

    private fun decodeResumeCursor(raw: String): TaskResumeCursor {
        if (raw.isBlank()) return TaskResumeCursor()
        return runCatching {
            val obj = JSONObject(raw)
            TaskResumeCursor(
                checkpointId = obj.optString("checkpointId").ifBlank { null },
                fixAttempts = obj.optInt("fixAttempts", 0),
                lastError = obj.optString("lastError").ifBlank { null },
                lastEventSeq = obj.optLong("lastEventSeq", 0L),
                pendingApprovalId = obj.optString("pendingApprovalId").ifBlank { null },
                pendingApprovalTitle = obj.optString("pendingApprovalTitle").ifBlank { null },
            )
        }.getOrDefault(TaskResumeCursor())
    }

    private fun encodeKnownFixes(fixes: List<KnownFixEntry>): String {
        val arr = JSONArray()
        fixes.forEach { f ->
            arr.put(
                JSONObject()
                    .put("errorSignature", f.errorSignature)
                    .put("resolutionNote", f.resolutionNote)
                    .put("recordedAtEpochMs", f.recordedAtEpochMs)
            )
        }
        return arr.toString()
    }

    private fun decodeKnownFixes(raw: String): List<KnownFixEntry> {
        if (raw.isBlank()) return emptyList()
        return runCatching {
            val arr = JSONArray(raw)
            (0 until arr.length()).mapNotNull { idx ->
                val obj = arr.optJSONObject(idx) ?: return@mapNotNull null
                val sig = obj.optString("errorSignature").trim()
                val note = obj.optString("resolutionNote").trim()
                if (sig.isBlank() || note.isBlank()) return@mapNotNull null
                KnownFixEntry(
                    errorSignature = sig,
                    resolutionNote = note,
                    recordedAtEpochMs = obj.optLong("recordedAtEpochMs", 0L),
                )
            }
        }.getOrDefault(emptyList())
    }

    companion object {
        private const val MAX_COMMAND_CHARS = 500
        private const val MAX_EXCERPT_CHARS = 2400
        private const val MAX_KNOWN_FIXES = 25
        private const val MAX_KNOWLEDGE_TITLE_CHARS = 180
        private const val MAX_KNOWLEDGE_CONTENT_CHARS = 900
        private const val MAX_KNOWLEDGE_TAGS_CHARS = 240

        private val FTS_STOP_WORDS = setOf(
            "a", "an", "and", "or", "not", "near", "the", "to", "of", "in",
            "on", "at", "is", "it", "by", "for", "with", "as", "be", "this",
            "that", "from", "are", "was", "were", "into", "your", "have", "been",
            "will", "when", "what", "which", "would", "should", "could",
        )

        internal fun normalizeFactKey(raw: String): String =
            raw.trim()
                .lowercase()
                .replace(Regex("[^a-z0-9:._/-]+"), "-")
                .trim('-')
                .take(120)

        internal fun tokenizeFtsQuery(query: String): List<String> =
            Regex("[A-Za-z0-9_]+")
                .findAll(query.lowercase())
                .map { it.value }
                .filter { it.length >= 2 && it !in FTS_STOP_WORDS }
                .distinct()
                .take(12)
                .toList()
    }
}
