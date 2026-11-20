package com.example.agent.orchestrator

import com.example.core.common.AgtError
import com.example.core.security.SecretRedactor
import com.example.domain.contracts.ProcessEvent
import com.example.domain.contracts.RuntimeCommand
import com.example.domain.contracts.RuntimeExecutor
import com.example.domain.model.ValidationCheckResult
import com.example.domain.model.ValidationCommand
import com.example.domain.model.ValidationOutcome
import kotlinx.coroutines.CancellationException
import java.util.UUID

/**
 * Executes real project build/test/lint commands through [RuntimeExecutor] inside the
 * workspace root and reports truthful outcomes with exit codes, durations, and bounded logs.
 */
class ValidationRunner(
    private val runtimeExecutor: RuntimeExecutor,
    private val knownSecretsProvider: () -> Collection<String> = { emptyList() },
) {
    suspend fun runChecks(
        workingDirectory: String,
        commands: List<ValidationCommand>,
        timeoutSeconds: Int,
        onStarted: suspend (ValidationCommand) -> Unit = {},
        onFinished: suspend (ValidationCheckResult) -> Unit = {},
    ): List<ValidationCheckResult> {
        if (commands.isEmpty()) return emptyList()

        val readiness = runtimeExecutor.ensureReady()
        if (!readiness.isInstalled) {
            return commands.map { cmd ->
                val skipped = ValidationCheckResult(
                    commandId = cmd.id,
                    label = cmd.label,
                    command = cmd.command,
                    status = ValidationOutcome.SKIPPED,
                    exitCode = null,
                    durationMs = 0L,
                    outputExcerpt = "Skipped: ${readiness.detail}",
                )
                onFinished(skipped)
                skipped
            }
        }

        val results = mutableListOf<ValidationCheckResult>()
        for (cmd in commands) {
            onStarted(cmd)
            val result = executeSingleCheck(
                workingDirectory = workingDirectory,
                command = cmd,
                timeoutSeconds = timeoutSeconds,
            )
            onFinished(result)
            results += result
        }
        return results
    }

    private suspend fun executeSingleCheck(
        workingDirectory: String,
        command: ValidationCommand,
        timeoutSeconds: Int,
    ): ValidationCheckResult {
        val processId = "val-${command.id}-${UUID.randomUUID().toString().take(6)}"
        val startedMs = System.currentTimeMillis()
        val outputLines = ArrayDeque<String>()
        var exitCode: Int? = null
        var outcome = ValidationOutcome.FAILED
        val secrets = knownSecretsProvider()

        try {
            runtimeExecutor.execute(
                RuntimeCommand(
                    command = command.command,
                    workingDirectory = workingDirectory,
                    timeoutSeconds = timeoutSeconds.coerceIn(10, 600),
                    processId = processId,
                    maxOutputLines = 500,
                )
            ).collect { ev ->
                when (ev) {
                    is ProcessEvent.Started -> Unit
                    is ProcessEvent.Stdout -> appendBounded(outputLines, SecretRedactor.redact(ev.line, secrets))
                    is ProcessEvent.Stderr -> appendBounded(outputLines, SecretRedactor.redact(ev.line, secrets))
                    is ProcessEvent.Exited -> {
                        exitCode = ev.exitCode
                        outcome = if (ev.exitCode == 0) ValidationOutcome.PASSED else ValidationOutcome.FAILED
                    }
                    is ProcessEvent.Failed -> {
                        outcome = when (ev.error) {
                            is AgtError.ProcessTimedOut -> ValidationOutcome.TIMED_OUT
                            is AgtError.ProcessCancelled -> ValidationOutcome.CANCELLED
                            is AgtError.RuntimeNotInstalled -> ValidationOutcome.SKIPPED
                            else -> ValidationOutcome.FAILED
                        }
                        appendBounded(outputLines, SecretRedactor.redact(ev.error.userMessage, secrets))
                    }
                }
            }
        } catch (ce: CancellationException) {
            runtimeExecutor.terminate(processId)
            throw ce
        }

        val durationMs = (System.currentTimeMillis() - startedMs).coerceAtLeast(0L)
        return ValidationCheckResult(
            commandId = command.id,
            label = command.label,
            command = command.command,
            status = outcome,
            exitCode = exitCode,
            durationMs = durationMs,
            outputExcerpt = outputLines.joinToString("\n").take(2400),
        )
    }

    private fun appendBounded(deque: ArrayDeque<String>, line: String) {
        if (line.isBlank()) return
        if (deque.size >= MAX_EXCERPT_LINES) {
            deque.removeFirst()
        }
        deque.addLast(line)
    }

    private companion object {
        const val MAX_EXCERPT_LINES = 40
    }
}
