package com.example.data.council

import com.example.core.security.SecretRedactor
import com.example.data.database.CouncilDao
import com.example.data.intelligence.DevelopmentIntelligenceRepository
import com.example.domain.model.CouncilRound
import com.example.domain.model.CouncilRoundDiff
import com.example.domain.model.CouncilSectionId
import com.example.domain.model.CouncilSession
import com.example.domain.model.FlightRecorderEventType
import com.example.domain.model.FlightRecorderOutcome
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.withContext

/**
 * Durable Room repository for Multi-Agent Council sessions, multi-round debate history,
 * per-section inline user edits, and round-to-round diff inspection.
 */
@OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
class CouncilRepository(
    private val councilDao: CouncilDao,
    private val intelligenceRepository: DevelopmentIntelligenceRepository? = null,
    private val knownSecretsProvider: () -> Collection<String> = { emptyList() },
) {
    fun observeSessionsForProject(projectId: String): Flow<List<CouncilSession>> =
        councilDao.observeSessionsForProject(projectId).map { entities ->
            entities.map { entity ->
                val roundEntities = councilDao.getRoundsForSession(entity.sessionId)
                CouncilSerialization.toDomainSession(entity, roundEntities)
            }
        }

    fun observeSession(sessionId: String): Flow<CouncilSession?> =
        councilDao.observeSessionById(sessionId).flatMapLatest { entity ->
            if (entity == null) {
                flowOf(null)
            } else {
                councilDao.observeRoundsForSession(sessionId).map { rounds ->
                    CouncilSerialization.toDomainSession(entity, rounds)
                }
            }
        }

    suspend fun getSession(sessionId: String): CouncilSession? = withContext(Dispatchers.IO) {
        val entity = councilDao.getSessionById(sessionId) ?: return@withContext null
        val rounds = councilDao.getRoundsForSession(sessionId)
        CouncilSerialization.toDomainSession(entity, rounds)
    }

    suspend fun getSessionByTaskId(taskId: String): CouncilSession? = withContext(Dispatchers.IO) {
        val entity = councilDao.getSessionByTaskId(taskId) ?: return@withContext null
        val rounds = councilDao.getRoundsForSession(entity.sessionId)
        CouncilSerialization.toDomainSession(entity, rounds)
    }

    suspend fun saveSession(session: CouncilSession): CouncilSession = withContext(Dispatchers.IO) {
        val secrets = knownSecretsProvider()
        val now = System.currentTimeMillis()
        val normalized = session.copy(updatedAtEpochMs = now)
        councilDao.upsertSession(CouncilSerialization.toSessionEntity(normalized, secrets))
        normalized.rounds.forEach { round ->
            councilDao.upsertRound(CouncilSerialization.toRoundEntity(normalized.sessionId, round, secrets))
        }
        getSession(normalized.sessionId) ?: normalized
    }

    suspend fun saveRound(
        sessionId: String,
        round: CouncilRound,
    ): CouncilSession? = withContext(Dispatchers.IO) {
        val existing = getSession(sessionId) ?: return@withContext null
        val secrets = knownSecretsProvider()
        val now = System.currentTimeMillis()
        councilDao.upsertRound(CouncilSerialization.toRoundEntity(sessionId, round, secrets))
        val updatedSession = existing.replaceRound(round).copy(updatedAtEpochMs = now)
        councilDao.upsertSession(CouncilSerialization.toSessionEntity(updatedSession, secrets))
        getSession(sessionId) ?: updatedSession
    }

    suspend fun editSection(
        sessionId: String,
        roundNumber: Int,
        sectionId: CouncilSectionId,
        newContent: String,
    ): Result<CouncilSession> = withContext(Dispatchers.IO) {
        val session = getSession(sessionId)
            ?: return@withContext Result.failure(IllegalArgumentException("Council session $sessionId not found."))
        val targetRound = session.roundByNumber(roundNumber)
            ?: return@withContext Result.failure(IllegalArgumentException("Round $roundNumber not found in session $sessionId."))

        val secrets = knownSecretsProvider()
        val redactedContent = SecretRedactor.redact(newContent, secrets)
        val updatedRound = targetRound.updateSection(sectionId, redactedContent)
        val updatedSession = session.replaceRound(updatedRound)

        councilDao.upsertRound(CouncilSerialization.toRoundEntity(sessionId, updatedRound, secrets))
        councilDao.upsertSession(CouncilSerialization.toSessionEntity(updatedSession, secrets))

        intelligenceRepository?.recordFlightEvent(
            projectId = updatedSession.projectId,
            taskId = updatedSession.taskId,
            eventType = FlightRecorderEventType.COUNCIL_USER_EDIT,
            outcome = FlightRecorderOutcome.INFO,
            toolName = sectionId.title,
            commandOrPath = "Round $roundNumber · ${sectionId.id}",
            outputExcerpt = "Edited by user (${redactedContent.length} chars)",
        )

        Result.success(getSession(sessionId) ?: updatedSession)
    }

    suspend fun revertSection(
        sessionId: String,
        roundNumber: Int,
        sectionId: CouncilSectionId,
    ): Result<CouncilSession> = withContext(Dispatchers.IO) {
        val session = getSession(sessionId)
            ?: return@withContext Result.failure(IllegalArgumentException("Council session $sessionId not found."))
        val targetRound = session.roundByNumber(roundNumber)
            ?: return@withContext Result.failure(IllegalArgumentException("Round $roundNumber not found in session $sessionId."))

        val secrets = knownSecretsProvider()
        val updatedRound = targetRound.revertSection(sectionId)
        val updatedSession = session.replaceRound(updatedRound)

        councilDao.upsertRound(CouncilSerialization.toRoundEntity(sessionId, updatedRound, secrets))
        councilDao.upsertSession(CouncilSerialization.toSessionEntity(updatedSession, secrets))

        intelligenceRepository?.recordFlightEvent(
            projectId = updatedSession.projectId,
            taskId = updatedSession.taskId,
            eventType = FlightRecorderEventType.COUNCIL_USER_EDIT,
            outcome = FlightRecorderOutcome.INFO,
            toolName = sectionId.title,
            commandOrPath = "Revert Round $roundNumber · ${sectionId.id}",
            outputExcerpt = "Reverted section to synthesized original.",
        )

        Result.success(getSession(sessionId) ?: updatedSession)
    }

    suspend fun markApprovedForCoding(sessionId: String): CouncilSession? = withContext(Dispatchers.IO) {
        val session = getSession(sessionId) ?: return@withContext null
        val secrets = knownSecretsProvider()
        val approved = session.copy(
            approvedForCoding = true,
            updatedAtEpochMs = System.currentTimeMillis(),
        )
        councilDao.upsertSession(CouncilSerialization.toSessionEntity(approved, secrets))
        getSession(sessionId) ?: approved
    }

    suspend fun computeRoundDiff(
        sessionId: String,
        fromRound: Int,
        toRound: Int,
    ): CouncilRoundDiff? = withContext(Dispatchers.IO) {
        val session = getSession(sessionId) ?: return@withContext null
        val prev = session.roundByNumber(fromRound) ?: return@withContext null
        val next = session.roundByNumber(toRound) ?: return@withContext null
        next.diffSummaryAgainst(prev)
    }

    suspend fun deleteSession(sessionId: String) = withContext(Dispatchers.IO) {
        councilDao.deleteSessionById(sessionId)
    }
}
