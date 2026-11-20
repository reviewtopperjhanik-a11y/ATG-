package com.example.agent

import com.example.data.preferences.AppPreferencesRepository
import com.example.domain.contracts.AgentEvent
import com.example.domain.contracts.AgentRuntime
import com.example.domain.contracts.AgentSession
import com.example.domain.contracts.AgentTask
import com.example.domain.contracts.ProjectRef
import com.example.domain.contracts.RuntimeAvailability
import com.example.domain.model.AgentBackendId
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.runBlocking
import java.util.concurrent.ConcurrentHashMap

/**
 * Dynamic [AgentRuntime] router dispatching lifecycle calls to whichever coding agent backend
 * (`opencode`, `pi`, ...) is currently selected in [AppPreferencesRepository], while pinning
 * in-flight sessions to the specific [AgentRuntime] that created them.
 */
class DelegatingAgentRuntime(
    private val preferencesRepository: AppPreferencesRepository,
    private val runtimes: Map<String, AgentRuntime>,
    private val defaultAgentId: String = AgentBackendId.OPENCODE.id,
) : AgentRuntime {

    private val sessionRuntimes = ConcurrentHashMap<String, AgentRuntime>()

    override val id: String
        get() = runCatching {
            runBlocking { preferencesRepository.preferences.first().activeAgentId }
        }.getOrDefault(defaultAgentId)

    private suspend fun resolveActiveRuntime(): AgentRuntime {
        val selectedId = preferencesRepository.preferences.first().activeAgentId
        return resolveRuntime(selectedId)
    }

    fun resolveRuntime(backendId: String?): AgentRuntime {
        val key = backendId?.takeIf { it.isNotBlank() }
        if (key != null) {
            runtimes[key]?.let { return it }
        }
        return runtimes[defaultAgentId] ?: runtimes.values.first()
    }

    override suspend fun checkAvailability(): RuntimeAvailability =
        resolveActiveRuntime().checkAvailability()

    suspend fun checkAvailabilityForBackend(backendId: String?): RuntimeAvailability =
        resolveRuntime(backendId).checkAvailability()

    override suspend fun startSession(project: ProjectRef): Result<AgentSession> =
        startSessionForBackend(project, backendId = null)

    suspend fun startSessionForBackend(project: ProjectRef, backendId: String?): Result<AgentSession> {
        val runtime = if (backendId.isNullOrBlank()) resolveActiveRuntime() else resolveRuntime(backendId)
        val res = runtime.startSession(project)
        res.getOrNull()?.let { session ->
            sessionRuntimes[session.sessionId] = runtime
        }
        return res
    }

    override fun submitTask(session: AgentSession, task: AgentTask): Flow<AgentEvent> {
        val runtime = sessionRuntimes[session.sessionId]
        if (runtime != null) {
            return runtime.submitTask(session, task)
        }
        return flow {
            val active = resolveActiveRuntime()
            active.submitTask(session, task).collect { emit(it) }
        }
    }

    override suspend fun respondToApproval(
        session: AgentSession,
        approvalId: String,
        approved: Boolean,
    ): Result<Unit> {
        val runtime = sessionRuntimes[session.sessionId] ?: resolveActiveRuntime()
        return runtime.respondToApproval(session, approvalId, approved)
    }

    override suspend fun stop(session: AgentSession) {
        val runtime = sessionRuntimes.remove(session.sessionId) ?: resolveActiveRuntime()
        runtime.stop(session)
    }
}
