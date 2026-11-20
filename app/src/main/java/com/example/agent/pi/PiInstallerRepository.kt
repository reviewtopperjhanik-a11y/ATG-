package com.example.agent.pi

import android.content.Context
import com.example.core.common.AgtError
import com.example.core.common.ProjectPathValidator
import com.example.data.preferences.AppPreferencesRepository
import com.example.data.runtime.LocalRuntimeExecutor
import com.example.data.runtime.RuntimeArchiveExtractor
import com.example.data.runtime.RuntimeDownloader
import com.example.data.runtime.RuntimeInstallerRepository
import com.example.domain.contracts.ProcessEvent
import com.example.domain.contracts.RuntimeCommand
import com.example.domain.model.PiRuntimeState
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.distinctUntilChangedBy
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.File

/**
 * Manages detection, installation (via SHA-256 HTTPS manifest or npm inside the active Node/Linux runtime),
 * and verification of the `@mariozechner/pi-coding-agent` (`pi`) CLI inside `runtime/active`.
 */
class PiInstallerRepository(
    context: Context,
    private val runtimeInstallerRepository: RuntimeInstallerRepository,
    private val preferencesRepository: AppPreferencesRepository,
    private val downloader: RuntimeDownloader = RuntimeDownloader(),
    private val runtimeExecutor: LocalRuntimeExecutor? = null,
) {
    private val appContext = context.applicationContext
    private val mutex = Mutex()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    private val _state = MutableStateFlow(PiRuntimeState())
    val state: StateFlow<PiRuntimeState> = _state.asStateFlow()

    init {
        refreshStatusSync()
        scope.launch {
            runtimeInstallerRepository.installState
                .distinctUntilChangedBy { Triple(it.isInstalled, it.isInstalling, it.installedAtEpochMs) }
                .collectLatest { runtimeState ->
                    if (!runtimeState.isInstalling && !_state.value.isInstalling) {
                        refreshStatusSync()
                    }
                }
        }
    }

    fun resolveBinaryFile(): File? {
        val activeDir = runtimeInstallerRepository.activeDir
        if (!activeDir.exists()) return null
        val candidates = listOf(
            "usr/local/bin/pi",
            "usr/bin/pi",
            "bin/pi",
            "usr/local/lib/node_modules/@mariozechner/pi-coding-agent/dist/cli.js",
            "usr/lib/node_modules/@mariozechner/pi-coding-agent/dist/cli.js",
            "pi",
        )
        return candidates
            .map { File(activeDir, it) }
            .firstOrNull { it.exists() && it.isFile }
    }

    fun hasNodeAndNpm(): Boolean {
        val activeDir = runtimeInstallerRepository.activeDir
        if (!activeDir.exists()) return false
        val nodeExists = listOf("usr/local/bin/node", "usr/bin/node", "bin/node")
            .any { File(activeDir, it).let { f -> f.exists() && f.isFile } }
        val npmExists = listOf("usr/local/bin/npm", "usr/bin/npm", "bin/npm")
            .any { File(activeDir, it).let { f -> f.exists() && f.isFile } }
        return nodeExists && npmExists
    }

    fun refreshStatusSync(): PiRuntimeState {
        val runtimeInstalled = runtimeInstallerRepository.installState.value.isInstalled
        val bin = resolveBinaryFile()
        val nodeAvailable = runtimeInstalled && hasNodeAndNpm()
        val markerFile = File(runtimeInstallerRepository.activeDir, ".agt-markers/pi_agent.json")
        val markerVersion = if (markerFile.exists()) {
            runCatching { JSONObject(markerFile.readText()).optString("version").ifBlank { null } }.getOrNull()
        } else {
            null
        }

        val installed = runtimeInstalled && bin != null && (bin.canExecute() || bin.setExecutable(true, false))
        val updated = PiRuntimeState(
            isInstalled = installed,
            isInstalling = false,
            version = if (installed) markerVersion ?: "detected" else null,
            binaryPath = if (installed) bin?.absolutePath else null,
            nodeAvailable = nodeAvailable,
            statusDetail = when {
                !runtimeInstalled -> "Linux runtime not installed yet. Tap Install via npm to verify & set up automatically."
                installed -> "Pi Coding Agent v${markerVersion ?: "detected"} ready inside Linux runtime."
                nodeAvailable -> "Pi CLI not found (Node.js ready). Tap Install via npm to install automatically."
                else -> "Pi CLI not found. Tap Install via npm to prepare Node.js and install automatically."
            },
            errorMessage = null,
        )
        _state.value = updated
        return updated
    }

    suspend fun verifyInstallation(): PiRuntimeState {
        val base = refreshStatusSync()
        val executor = runtimeExecutor
        if (!base.isInstalled || executor == null || !executor.isProotIsolationActive()) {
            return base
        }
        return withContext(Dispatchers.IO) {
            var exitCode = -1
            var failureMsg: String? = null
            val stdout = mutableListOf<String>()
            val stderr = mutableListOf<String>()
            executor.execute(
                RuntimeCommand(
                    command = "pi --version",
                    workingDirectory = runtimeInstallerRepository.activeDir.absolutePath,
                    timeoutSeconds = 20,
                )
            ).collect { ev ->
                when (ev) {
                    is ProcessEvent.Stdout -> stdout += ev.line
                    is ProcessEvent.Stderr -> stderr += ev.line
                    is ProcessEvent.Exited -> exitCode = ev.exitCode
                    is ProcessEvent.Failed -> failureMsg = ev.error.userMessage
                    is ProcessEvent.Started -> Unit
                }
            }
            if (failureMsg != null || exitCode != 0) {
                val msg = failureMsg ?: stderr.lastOrNull()?.takeIf { it.isNotBlank() }
                    ?: "Pi CLI exited with code $exitCode inside PRoot."
                val failed = base.copy(
                    isInstalled = false,
                    statusDetail = "Pi CLI verification failed inside PRoot.",
                    errorMessage = msg,
                )
                _state.value = failed
                failed
            } else {
                val liveVer = stdout.lastOrNull { it.isNotBlank() }?.trim()?.removePrefix("v")
                    ?: base.version
                val ok = base.copy(
                    isInstalled = true,
                    version = liveVer,
                    statusDetail = "Pi Coding Agent v$liveVer verified inside PRoot.",
                    errorMessage = null,
                )
                _state.value = ok
                ok
            }
        }
    }

    suspend fun installViaNpm(forceUpdate: Boolean = false): Result<PiRuntimeState> = mutex.withLock {
        withContext(Dispatchers.IO) {
            val executor = runtimeExecutor ?: run {
                val msg = "Runtime executor unavailable for npm install."
                _state.update { it.copy(isInstalling = false, errorMessage = msg, statusDetail = msg) }
                return@withContext Result.failure(IllegalStateException(msg))
            }

            _state.update {
                it.copy(
                    isInstalling = true,
                    errorMessage = null,
                    statusDetail = "Verifying Linux environment, Node.js, and Pi CLI…",
                )
            }

            val healthBefore = runtimeInstallerRepository.runHealthCheck()
            val existingBin = resolveBinaryFile()
            if (!forceUpdate && healthBefore.isHealthy && existingBin != null &&
                (existingBin.canExecute() || existingBin.setExecutable(true, false))
            ) {
                val refreshed = refreshStatusSync()
                return@withContext Result.success(refreshed)
            }

            if (!healthBefore.isHealthy || !runtimeInstallerRepository.activeDir.exists() || !hasNodeAndNpm()) {
                _state.update {
                    it.copy(statusDetail = "Setting up Linux runtime & Node.js before installing Pi CLI…")
                }
                val rtRes = runtimeInstallerRepository.installBlocking()
                if (rtRes.isFailure) {
                    val err = rtRes.exceptionOrNull()!!
                    val msg = err.message ?: "Base Linux runtime setup failed."
                    _state.update { it.copy(isInstalling = false, errorMessage = msg, statusDetail = msg) }
                    return@withContext Result.failure(err)
                }
            }

            if (!hasNodeAndNpm()) {
                val msg = "Node.js and npm were not found inside the Linux runtime."
                _state.update { it.copy(isInstalling = false, errorMessage = msg, statusDetail = msg) }
                return@withContext Result.failure(IllegalStateException(msg))
            }

            _state.update {
                it.copy(
                    isInstalling = true,
                    errorMessage = null,
                    statusDetail = "Installing @mariozechner/pi-coding-agent via npm…",
                )
            }

            try {
                var exitCode = -1
                var failureErr: Throwable? = null
                val stdoutLines = mutableListOf<String>()
                val stderrLines = mutableListOf<String>()

                executor.execute(
                    RuntimeCommand(
                        command = "npm install -g @mariozechner/pi-coding-agent --no-audit --no-fund && pi --version",
                        workingDirectory = runtimeInstallerRepository.activeDir.absolutePath,
                        timeoutSeconds = 300,
                    )
                ).collect { ev ->
                    when (ev) {
                        is ProcessEvent.Stdout -> stdoutLines += ev.line
                        is ProcessEvent.Stderr -> stderrLines += ev.line
                        is ProcessEvent.Exited -> exitCode = ev.exitCode
                        is ProcessEvent.Failed -> failureErr = IllegalStateException(ev.error.userMessage)
                        is ProcessEvent.Started -> Unit
                    }
                }

                if (failureErr != null || exitCode != 0) {
                    val detail = failureErr?.message
                        ?: stderrLines.lastOrNull()?.takeIf { it.isNotBlank() }
                        ?: "npm install exited with code $exitCode."
                    _state.update { it.copy(isInstalling = false, errorMessage = detail, statusDetail = detail) }
                    return@withContext Result.failure(IllegalStateException(detail))
                }

                val bin = resolveBinaryFile()
                    ?: run {
                        val msg = "npm finished, but pi executable was not found inside Linux runtime."
                        _state.update { it.copy(isInstalling = false, errorMessage = msg, statusDetail = msg) }
                        return@withContext Result.failure(IllegalStateException(msg))
                    }
                bin.setExecutable(true, false)

                val detectedVersion = stdoutLines
                    .lastOrNull { line -> line.trim().matches(Regex("v?\\d+\\.\\d+\\.\\d+.*")) }
                    ?.trim()
                    ?.removePrefix("v")
                    ?: "npm"

                val activeDir = runtimeInstallerRepository.activeDir
                val relBin = bin.canonicalFile
                    .relativeTo(activeDir.canonicalFile)
                    .path
                    .replace('\\', '/')
                val markerDir = File(activeDir, ".agt-markers").apply { mkdirs() }
                val marker = JSONObject()
                    .put("id", "pi_agent")
                    .put("name", "Pi Coding Agent Runtime")
                    .put("version", detectedVersion)
                    .put("source", "npm:@mariozechner/pi-coding-agent")
                    .put("binary", relBin)
                    .put("installed_at_ms", System.currentTimeMillis())
                File(markerDir, "pi_agent.json").writeText(marker.toString(2))

                runtimeInstallerRepository.runHealthCheck()
                val refreshed = refreshStatusSync()
                Result.success(refreshed)
            } catch (ce: CancellationException) {
                _state.update { it.copy(isInstalling = false, statusDetail = "Pi installation cancelled.") }
                throw ce
            } catch (t: Throwable) {
                val msg = t.message ?: "Pi npm installation failed."
                _state.update { it.copy(isInstalling = false, errorMessage = msg, statusDetail = msg) }
                Result.failure(t)
            }
        }
    }

    suspend fun installOrUpdate(manifestUrl: String): Result<PiRuntimeState> {
        val trimmed = manifestUrl.trim()
        if (trimmed.isEmpty()) {
            return installViaNpm(forceUpdate = false)
        }
        return mutex.withLock {
            withContext(Dispatchers.IO) {

            val runtimeHealth = runtimeInstallerRepository.runHealthCheck()
            if (!runtimeHealth.isHealthy || !runtimeInstallerRepository.activeDir.exists()) {
                val msg = "Base Linux runtime must be installed and healthy before installing Pi Coding Agent."
                _state.update { it.copy(isInstalling = false, errorMessage = msg, statusDetail = msg) }
                return@withContext Result.failure(IllegalStateException(msg))
            }

            preferencesRepository.setPiManifestUrl(trimmed)
            _state.update {
                it.copy(
                    isInstalling = true,
                    errorMessage = null,
                    statusDetail = "Fetching Pi release manifest…",
                )
            }

            val stagingRoot = File(runtimeInstallerRepository.stagingDir, "pi-${System.currentTimeMillis()}")
            try {
                val manifest = downloader.fetchManifest(trimmed).getOrElse { err ->
                    val msg = err.message ?: "Failed to fetch Pi manifest."
                    _state.update { it.copy(isInstalling = false, errorMessage = msg, statusDetail = msg) }
                    return@withContext Result.failure(err)
                }

                _state.update {
                    it.copy(statusDetail = "Downloading Pi Coding Agent v${manifest.version}…")
                }

                val archiveName = "pi-${manifest.version}.${manifest.bundle.archiveFormat.extensionHint}"
                val downloaded = downloader.downloadAndVerify(
                    bundle = manifest.bundle,
                    downloadsDir = runtimeInstallerRepository.downloadsDir,
                    targetArchiveName = archiveName,
                    onProgress = { dl, total ->
                        val pct = if (total > 0) ((dl * 100L) / total).toInt().coerceIn(0, 100) else null
                        _state.update { st ->
                            st.copy(
                                statusDetail = if (pct != null) {
                                    "Downloading Pi v${manifest.version} ($pct%)…"
                                } else {
                                    "Downloading Pi v${manifest.version} ($dl bytes)…"
                                }
                            )
                        }
                    },
                ).getOrElse { err ->
                    val msg = err.message ?: "Pi download/verification failed."
                    _state.update { it.copy(isInstalling = false, errorMessage = msg, statusDetail = msg) }
                    return@withContext Result.failure(err)
                }

                _state.update { it.copy(statusDetail = "Extracting Pi Coding Agent into staging…") }
                RuntimeArchiveExtractor.extractToStaging(
                    archiveFile = downloaded.archiveFile,
                    format = manifest.bundle.archiveFormat,
                    stagingRoot = stagingRoot,
                    stripRootPrefix = manifest.bundle.extractionRoot,
                ).getOrElse { err ->
                    val msg = err.message ?: "Failed to extract Pi archive."
                    _state.update { it.copy(isInstalling = false, errorMessage = msg, statusDetail = msg) }
                    return@withContext Result.failure(err)
                }

                val stagedBinary = findPiBinary(stagingRoot)
                    ?: run {
                        stagingRoot.deleteRecursively()
                        val msg = "Verified archive does not contain a pi binary."
                        _state.update { it.copy(isInstalling = false, errorMessage = msg, statusDetail = msg) }
                        return@withContext Result.failure(IllegalStateException(msg))
                    }

                val activeDir = runtimeInstallerRepository.activeDir
                val rawRelBin = stagedBinary.canonicalFile
                    .relativeTo(stagingRoot.canonicalFile)
                    .path
                    .replace('\\', '/')
                val relBin = if (rawRelBin == "pi") "usr/local/bin/pi" else rawRelBin
                val targetBin = ProjectPathValidator.resolveWithinRoot(activeDir, relBin)
                    .getOrThrow()
                targetBin.parentFile?.mkdirs()
                val tmpTarget = File(targetBin.parentFile, "${targetBin.name}.tmp")
                stagedBinary.copyTo(tmpTarget, overwrite = true)
                tmpTarget.setExecutable(true, false)
                if (targetBin.exists()) targetBin.delete()
                if (!tmpTarget.renameTo(targetBin)) {
                    tmpTarget.copyTo(targetBin, overwrite = true)
                    tmpTarget.delete()
                }
                targetBin.setExecutable(true, false)

                val markerDir = File(activeDir, ".agt-markers").apply { mkdirs() }
                val marker = JSONObject()
                    .put("id", "pi_agent")
                    .put("name", "Pi Coding Agent Runtime")
                    .put("version", manifest.version)
                    .put("sha256", downloaded.sha256Hex)
                    .put("binary", relBin)
                    .put("installed_at_ms", System.currentTimeMillis())
                File(markerDir, "pi_agent.json").writeText(marker.toString(2))

                stagingRoot.deleteRecursively()
                runtimeInstallerRepository.runHealthCheck()
                val refreshed = refreshStatusSync()
                Result.success(refreshed)
            } catch (ce: CancellationException) {
                stagingRoot.deleteRecursively()
                _state.update {
                    it.copy(
                        isInstalling = false,
                        statusDetail = "Pi installation cancelled.",
                    )
                }
                throw ce
            } catch (t: Throwable) {
                stagingRoot.deleteRecursively()
                val msg = t.message ?: AgtError.RuntimeVerificationFailed().userMessage
                _state.update { it.copy(isInstalling = false, errorMessage = msg, statusDetail = msg) }
                Result.failure(t)
            }
        }
        }
    }

    private fun findPiBinary(root: File): File? {
        val preferred = listOf("usr/local/bin/pi", "usr/bin/pi", "bin/pi", "pi")
            .mapNotNull { rel -> ProjectPathValidator.resolveWithinRoot(root, rel).getOrNull() }
            .firstOrNull { it.exists() && it.isFile }
        if (preferred != null) return preferred
        return root.walkTopDown().firstOrNull { it.isFile && it.name == "pi" }
    }
}
