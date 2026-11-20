package com.example.agent.orchestrator

import com.example.domain.model.TaskState

/**
 * Enforces legal task lifecycle transitions per spec 05 (Section 3) and records
 * transition history. Illegal transitions are rejected with an explicit failure.
 */
class TaskStateMachine(
    initialState: TaskState = TaskState.CREATED,
) {
    var currentState: TaskState = initialState
        private set

    private val _history = mutableListOf(initialState)
    val history: List<TaskState> get() = _history.toList()

    fun canTransitionTo(next: TaskState): Boolean {
        if (next == currentState) return true
        return next in allowedTransitions[currentState].orEmpty()
    }

    fun transitionTo(next: TaskState): Result<TaskState> {
        if (next == currentState) return Result.success(currentState)
        if (!canTransitionTo(next)) {
            return Result.failure(
                IllegalStateException("Illegal task state transition: $currentState -> $next")
            )
        }
        currentState = next
        _history += next
        return Result.success(next)
    }

    companion object {
        private val allowedTransitions: Map<TaskState, Set<TaskState>> = mapOf(
            TaskState.CREATED to setOf(
                TaskState.CONTEXT_LOADING,
                TaskState.RESEARCHING,
                TaskState.FAILED,
                TaskState.CANCELLED,
                TaskState.PAUSED,
            ),
            TaskState.CONTEXT_LOADING to setOf(
                TaskState.ANALYZING,
                TaskState.RESEARCHING,
                TaskState.COUNCIL_DELIBERATING,
                TaskState.PLANNING,
                TaskState.FAILED,
                TaskState.CANCELLED,
                TaskState.PAUSED,
            ),
            TaskState.ANALYZING to setOf(
                TaskState.RESEARCHING,
                TaskState.COUNCIL_DELIBERATING,
                TaskState.PLANNING,
                TaskState.FAILED,
                TaskState.CANCELLED,
                TaskState.PAUSED,
            ),
            TaskState.RESEARCHING to setOf(
                TaskState.COUNCIL_DELIBERATING,
                TaskState.SYNTHESIZING,
                TaskState.PLANNING,
                TaskState.FAILED,
                TaskState.CANCELLED,
                TaskState.PAUSED,
            ),
            TaskState.COUNCIL_DELIBERATING to setOf(
                TaskState.SYNTHESIZING,
                TaskState.RESEARCHING,
                TaskState.FAILED,
                TaskState.CANCELLED,
                TaskState.PAUSED,
            ),
            TaskState.SYNTHESIZING to setOf(
                TaskState.AWAITING_COUNCIL_APPROVAL,
                TaskState.PLANNING,
                TaskState.EXECUTING,
                TaskState.COMPLETED,
                TaskState.FAILED,
                TaskState.CANCELLED,
                TaskState.PAUSED,
            ),
            TaskState.AWAITING_COUNCIL_APPROVAL to setOf(
                TaskState.RESEARCHING,
                TaskState.COUNCIL_DELIBERATING,
                TaskState.PLANNING,
                TaskState.EXECUTING,
                TaskState.COMPLETED,
                TaskState.CANCELLED,
                TaskState.PAUSED,
                TaskState.FAILED,
            ),
            TaskState.PLANNING to setOf(
                TaskState.AWAITING_PLAN_APPROVAL,
                TaskState.EXECUTING,
                TaskState.VALIDATING,
                TaskState.FAILED,
                TaskState.CANCELLED,
                TaskState.PAUSED,
            ),
            TaskState.AWAITING_PLAN_APPROVAL to setOf(
                TaskState.EXECUTING,
                TaskState.CANCELLED,
                TaskState.PAUSED,
                TaskState.FAILED,
            ),
            TaskState.EXECUTING to setOf(
                TaskState.AWAITING_TOOL_APPROVAL,
                TaskState.VALIDATING,
                TaskState.COMPLETED,
                TaskState.FAILED,
                TaskState.CANCELLED,
                TaskState.PAUSED,
            ),
            TaskState.AWAITING_TOOL_APPROVAL to setOf(
                TaskState.EXECUTING,
                TaskState.CANCELLED,
                TaskState.FAILED,
                TaskState.PAUSED,
            ),
            TaskState.VALIDATING to setOf(
                TaskState.FIXING,
                TaskState.COMPLETED,
                TaskState.FAILED,
                TaskState.CANCELLED,
                TaskState.PAUSED,
            ),
            TaskState.FIXING to setOf(
                TaskState.EXECUTING,
                TaskState.AWAITING_TOOL_APPROVAL,
                TaskState.VALIDATING,
                TaskState.COMPLETED,
                TaskState.FAILED,
                TaskState.CANCELLED,
                TaskState.PAUSED,
            ),
            TaskState.PAUSED to setOf(
                TaskState.CONTEXT_LOADING,
                TaskState.RESEARCHING,
                TaskState.COUNCIL_DELIBERATING,
                TaskState.SYNTHESIZING,
                TaskState.AWAITING_COUNCIL_APPROVAL,
                TaskState.PLANNING,
                TaskState.EXECUTING,
                TaskState.VALIDATING,
                TaskState.FIXING,
                TaskState.CANCELLED,
                TaskState.FAILED,
            ),
            TaskState.COMPLETED to emptySet(),
            TaskState.FAILED to emptySet(),
            TaskState.CANCELLED to emptySet(),
        )

        fun isTerminal(state: TaskState): Boolean =
            state == TaskState.COMPLETED || state == TaskState.FAILED || state == TaskState.CANCELLED
    }
}
