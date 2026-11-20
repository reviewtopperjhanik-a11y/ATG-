package com.example.data.runtime

import com.example.core.common.AgtError
import com.example.domain.contracts.ProcessEvent
import com.example.domain.contracts.RuntimeAvailability
import com.example.domain.contracts.RuntimeCommand
import com.example.domain.contracts.RuntimeExecutor
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.channels.ProducerScope
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.BufferedReader
import java.io.File
import java.io.InputStream
import java.io.InputStreamReader
import java.nio.charset.StandardCharsets
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger

/**
 * Production M5 [RuntimeExecutor] coordinating real shell execution against verified
 * M4 Linux runtime files inside a project directory.
 *
 * Guarantees:
 * - Refuses execution if runtime is not installed/healthy.
 * - Validates and confines working directory to an existing project directory.
 * - Streams stdout & stderr concurrently with bounded output lines.
 * - Enforces command timeout and cleans up child process trees on Stop/cancel.
 * - Scrubs sensitive token environment variables before spawning processes.
 */
class LocalRuntimeExecutor(
    private val runtimeInstallerRepository: RuntimeInstallerRepository,
    private val processBridge: ProcessBridge = AndroidProcessBridge(),
    private val maxConcurrentProcesses: Int = 4,
) : RuntimeExecutor {

    private data class RunningEntry(
        val processId: String,
        val process: ManagedProcess,
        val cancelHook: () -> Unit,
    )

    private val activeProcesses = ConcurrentHashMap<String, RunningEntry>()

    private val _activeProcessCount = MutableStateFlow(0)
    val activeProcessCount: StateFlow<Int> = _activeProcessCount.asStateFlow()

    override suspend fun ensureReady(): RuntimeAvailability {
        val health = runtimeInstallerRepository.runHealthCheck()
        val state = runtimeInstallerRepository.installState.value
        return RuntimeAvailability(
            isInstalled = health.isHealthy && state.isInstalled,
            version = state.installedVersion,
            detail = health.summary,
        )
    }

    override fun execute(command: RuntimeCommand): Flow<ProcessEvent> = callbackFlow {
        val state = runtimeInstallerRepository.installState.value
        if (!state.isInstalled || !runtimeInstallerRepository.activeDir.exists()) {
            trySend(
                ProcessEvent.Failed(
                    AgtError.RuntimeNotInstalled(
                        "Linux runtime is not installed. Complete Setup or Settings -> Runtime first."
                    )
                )
            )
            close()
            return@callbackFlow
        }

        if (command.command.isBlank()) {
            trySend(
                ProcessEvent.Failed(
                    AgtError.ProcessStartFailed("Shell command cannot be empty.")
                )
            )
            close()
            return@callbackFlow
        }

        if (activeProcesses.size >= maxConcurrentProcesses) {
            trySend(
                ProcessEvent.Failed(
                    AgtError.ProcessStartFailed("Too many concurrent terminal processes (max $maxConcurrentProcesses). Stop an active process first.")
                )
            )
            close()
            return@callbackFlow
        }

        val workDir = runCatching { File(command.workingDirectory).canonicalFile }.getOrNull()
        if (workDir == null || !workDir.exists() || !workDir.isDirectory) {
            trySend(
                ProcessEvent.Failed(
                    AgtError.ProjectPathRejected(
                        "Project working directory does not exist: ${command.workingDirectory}"
                    )
                )
            )
            close()
            return@callbackFlow
        }

        val scopedEnv = buildScopedEnvironment(
            runtimeActiveDir = runtimeInstallerRepository.activeDir,
            workDir = workDir,
            extra = command.environment,
        )

        val argv = processBridge.buildCommand(
            runtimeActiveDir = runtimeInstallerRepository.activeDir,
            projectWorkingDir = workDir,
            shellCommand = command.command,
        )

        val proc: ManagedProcess = try {
            processBridge.spawn(argv, workDir, scopedEnv)
        } catch (t: Throwable) {
            trySend(
                ProcessEvent.Failed(
                    AgtError.ProcessStartFailed(
                        userMessage = t.message ?: "Failed to start Linux process.",
                        diagnosticDetail = t.javaClass.simpleName,
                    )
                )
            )
            close()
            return@callbackFlow
        }

        var killedManually = false
        var timedOut = false
        var worker: kotlinx.coroutines.Job? = null

        val entry = RunningEntry(
            processId = command.processId,
            process = proc,
            cancelHook = {
                killedManually = true
                proc.terminateTree()
                worker?.cancel()
            },
        )
        registerProcess(entry)

        worker = launch(Dispatchers.IO) {
            val timeoutMs = command.timeoutSeconds.coerceIn(1, 3600) * 1000L
            val watchdog = launch {
                kotlinx.coroutines.delay(timeoutMs)
                timedOut = true
                proc.terminateTree()
            }
            try {
                trySend(ProcessEvent.Started(command.processId, proc.pid))
                val lineCounter = AtomicInteger(0)
                val maxLines = command.maxOutputLines.coerceIn(50, 20_000)

                val exitCode = coroutineScope {
                    val outJob = async {
                        drainStream(
                            stream = proc.stdout,
                            lineCounter = lineCounter,
                            maxLines = maxLines,
                            scope = this@callbackFlow,
                            isErr = false,
                        )
                    }
                    val errSample = StringBuilder()
                    val errJob = async {
                        drainStream(
                            stream = proc.stderr,
                            lineCounter = lineCounter,
                            maxLines = maxLines,
                            scope = this@callbackFlow,
                            isErr = true,
                            captureBuffer = errSample,
                        )
                    }
                    val code = proc.waitFor()
                    outJob.await()
                    errJob.await()
                    if (code != 0 &&
                        !timedOut &&
                        !killedManually &&
                        processBridge.isIsolationActive(runtimeInstallerRepository.activeDir) &&
                        !processBridge.isSeccompDisabled() &&
                        AndroidProcessBridge.looksLikeSeccompFailure(code, errSample.toString())
                    ) {
                        runtimeInstallerRepository.enableProotSeccompFallback()
                    }
                    code
                }
                watchdog.cancel()

                if (timedOut) {
                    trySend(
                        ProcessEvent.Failed(
                            AgtError.ProcessTimedOut(
                                "Command timed out after ${command.timeoutSeconds}s and was stopped."
                            )
                        )
                    )
                } else if (killedManually) {
                    trySend(
                        ProcessEvent.Failed(
                            AgtError.ProcessCancelled("Process stopped by user.")
                        )
                    )
                } else {
                    trySend(ProcessEvent.Exited(exitCode))
                }
                close()
            } catch (ce: CancellationException) {
                watchdog.cancel()
                proc.terminateTree()
                if (timedOut) {
                    trySend(
                        ProcessEvent.Failed(
                            AgtError.ProcessTimedOut(
                                "Command timed out after ${command.timeoutSeconds}s and was stopped."
                            )
                        )
                    )
                } else {
                    trySend(ProcessEvent.Failed(AgtError.ProcessCancelled("Process stopped.")))
                }
                close()
                throw ce
            } catch (t: Throwable) {
                watchdog.cancel()
                proc.terminateTree()
                trySend(
                    ProcessEvent.Failed(
                        AgtError.ProcessStartFailed(
                            userMessage = t.message ?: "Process stream interrupted.",
                        )
                    )
                )
                close()
            } finally {
                watchdog.cancel()
                unregisterProcess(command.processId)
            }
        }

        awaitClose {
            val wasActive = activeProcesses.remove(command.processId) != null
            if (wasActive) {
                _activeProcessCount.value = activeProcesses.size
                proc.terminateTree()
            }
            worker?.cancel()
        }
    }.flowOn(Dispatchers.IO)

    override suspend fun sendInput(processId: String, input: String): Result<Unit> =
        withContext(Dispatchers.IO) {
            val entry = activeProcesses[processId]
                ?: return@withContext Result.failure(IllegalStateException("No active process with id $processId"))
            runCatching {
                val bytes = (input + "\n").toByteArray(StandardCharsets.UTF_8)
                entry.process.stdin.write(bytes)
                entry.process.stdin.flush()
            }
        }

    override suspend fun terminate(processId: String) {
        withContext(Dispatchers.IO) {
            val entry = activeProcesses.remove(processId)
            _activeProcessCount.value = activeProcesses.size
            entry?.cancelHook?.invoke()
        }
    }

    suspend fun terminateAll() {
        withContext(Dispatchers.IO) {
            val ids = activeProcesses.keys.toList()
            ids.forEach { id ->
                activeProcesses.remove(id)?.cancelHook?.invoke()
            }
            _activeProcessCount.value = 0
        }
    }

    private fun registerProcess(entry: RunningEntry) {
        activeProcesses[entry.processId] = entry
        _activeProcessCount.value = activeProcesses.size
    }

    private fun unregisterProcess(processId: String) {
        if (activeProcesses.remove(processId) != null) {
            _activeProcessCount.value = activeProcesses.size
        }
    }

    private suspend fun drainStream(
        stream: InputStream,
        lineCounter: AtomicInteger,
        maxLines: Int,
        scope: ProducerScope<ProcessEvent>,
        isErr: Boolean,
        captureBuffer: StringBuilder? = null,
    ) {
        var truncatedNotified = false
        val result = runCatching {
            BufferedReader(InputStreamReader(stream, StandardCharsets.UTF_8)).use { reader ->
                while (true) {
                    val rawLine = reader.readLine() ?: break
                    val boundedLine = if (rawLine.length > MAX_LINE_CHARS) {
                        rawLine.take(MAX_LINE_CHARS) + "… [truncated]"
                    } else {
                        rawLine
                    }
                    if (captureBuffer != null && captureBuffer.length < 2048) {
                        captureBuffer.appendLine(boundedLine)
                    }
                    val count = lineCounter.incrementAndGet()
                    if (count <= maxLines) {
                        scope.send(
                            if (isErr) ProcessEvent.Stderr(boundedLine) else ProcessEvent.Stdout(boundedLine)
                        )
                    } else if (!truncatedNotified) {
                        truncatedNotified = true
                        scope.send(
                            ProcessEvent.Stderr("[agt-runtime] Output truncated after $maxLines lines.")
                        )
                    }
                }
            }
        }
        val err = result.exceptionOrNull()
        if (err is CancellationException) throw err
    }

    fun isProotIsolationActive(): Boolean =
        processBridge.isIsolationActive(runtimeInstallerRepository.activeDir)

    private fun buildScopedEnvironment(
        runtimeActiveDir: File,
        workDir: File,
        extra: Map<String, String>,
    ): Map<String, String> {
        val runtimeTmp = File(runtimeActiveDir, "tmp").apply { mkdirs() }
        val npmCacheDir = File(runtimeTmp, "npm-cache").apply { mkdirs() }
        val ghConfigDir = File(runtimeTmp, "gh-config").apply { mkdirs() }
        val caCertFile = File(runtimeActiveDir, "etc/ssl/certs/ca-certificates.crt")
        val chrootActive = processBridge.isIsolationActive(runtimeActiveDir)
        val isSameAsRootfs = runCatching {
            workDir.canonicalPath == runtimeActiveDir.canonicalPath
        }.getOrDefault(false)

        val map = if (chrootActive) {
            if (isSameAsRootfs) {
                File(runtimeActiveDir, "root").mkdirs()
            }
            linkedMapOf(
                "TERM" to "xterm-256color",
                "LANG" to "C.UTF-8",
                "HOME" to if (isSameAsRootfs) "/root" else "/workspace",
                "PWD" to if (isSameAsRootfs) "/" else "/workspace",
                "PATH" to "/usr/local/sbin:/usr/local/bin:/usr/sbin:/usr/bin:/sbin:/bin",
                "TMPDIR" to "/tmp",
                "npm_config_cache" to "/tmp/npm-cache",
                "GH_CONFIG_DIR" to "/tmp/gh-config",
                "AGT_RUNTIME_ROOT" to "/",
                "AGT_IS_PROOT" to "1",
            ).apply {
                if (caCertFile.exists()) {
                    put("SSL_CERT_FILE", "/etc/ssl/certs/ca-certificates.crt")
                }
                putAll(
                    processBridge.buildProotEnvironment(
                        runtimeActiveDir = runtimeActiveDir,
                        disableSeccomp = processBridge.isSeccompDisabled(),
                    )
                )
            }
        } else {
            val runtimePath = listOf(
                File(runtimeActiveDir, "usr/local/bin").absolutePath,
                File(runtimeActiveDir, "usr/bin").absolutePath,
                File(runtimeActiveDir, "bin").absolutePath,
                "/usr/local/bin",
                "/usr/bin",
                "/bin",
                "/system/bin",
            ).joinToString(":")

            linkedMapOf(
                "TERM" to "xterm-256color",
                "LANG" to "C.UTF-8",
                "HOME" to workDir.absolutePath,
                "PWD" to workDir.absolutePath,
                "PATH" to runtimePath,
                "TMPDIR" to runtimeTmp.absolutePath,
                "npm_config_cache" to npmCacheDir.absolutePath,
                "GH_CONFIG_DIR" to ghConfigDir.absolutePath,
                "AGT_RUNTIME_ROOT" to runtimeActiveDir.absolutePath,
            ).apply {
                if (caCertFile.exists()) {
                    put("SSL_CERT_FILE", caCertFile.absolutePath)
                }
            }
        }

        // Copy only safe non-secret custom environment entries (explicitly permitting scoped MCP bridge tokens)
        val blockedKeys = setOf("GATEWAY_TOKEN", "GITHUB_TOKEN", "AUTHORIZATION", "API_KEY")
        extra.forEach { (k, v) ->
            val upper = k.uppercase()
            val isMcpToken = upper.startsWith("AGT_MCP_TOKEN_")
            val isBlocked = !isMcpToken && (upper in blockedKeys || upper.contains("SECRET") || upper.contains("TOKEN"))
            if (!isBlocked) {
                map[k] = v
            }
        }
        return map
    }

    private companion object {
        const val MAX_LINE_CHARS = 4096
    }
}
