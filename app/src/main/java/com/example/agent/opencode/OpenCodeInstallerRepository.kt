package com.example.agent.opencode

import android.content.Context
import com.example.core.common.AgtError
import com.example.core.common.DeviceEnvironment
import com.example.core.common.ProjectPathValidator
import com.example.data.preferences.AppPreferencesRepository
import com.example.data.runtime.LocalRuntimeExecutor
import com.example.data.runtime.RuntimeArchiveExtractor
import com.example.data.runtime.RuntimeDownloader
import com.example.data.runtime.RuntimeInstallerRepository
import com.example.data.runtime.UpstreamRuntimeBootstrap
import com.example.domain.contracts.ProcessEvent
import com.example.domain.contracts.RuntimeCommand
import com.example.domain.model.OpenCodeRuntimeState
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
 * Manages detection, SHA-256 verified installation/update, and verification of the
 * OpenCode agent binary inside the active M4 Linux runtime (`runtime/active`).
 */
class OpenCodeInstallerRepository(
    context: Context,
    private val runtimeInstallerRepository: RuntimeInstallerRepository,
    private val preferencesRepository: AppPreferencesRepository,
    private val downloader: RuntimeDownloader = RuntimeDownloader(),
    private val upstreamBootstrap: UpstreamRuntimeBootstrap = UpstreamRuntimeBootstrap(),
    private val runtimeExecutor: LocalRuntimeExecutor? = null,
) {
    private val appContext = context.applicationContext
    private val mutex = Mutex()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    private val _state = MutableStateFlow(OpenCodeRuntimeState())
    val state: StateFlow<OpenCodeRuntimeState> = _state.asStateFlow()

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
            "usr/local/bin/opencode",
            "usr/bin/opencode",
            "bin/opencode",
            "opencode",
        )
        return candidates
            .map { File(activeDir, it) }
            .firstOrNull { it.exists() && it.isFile }
    }

    fun refreshStatusSync(): OpenCodeRuntimeState {
        val runtimeInstalled = runtimeInstallerRepository.installState.value.isInstalled
        val bin = resolveBinaryFile()
        val markerFile = File(runtimeInstallerRepository.activeDir, ".agt-markers/opencode_agent.json")
        val markerVersion = if (markerFile.exists()) {
            runCatching { JSONObject(markerFile.readText()).optString("version").ifBlank { null } }.getOrNull()
        } else {
            null
        }

        val installed = runtimeInstalled && bin != null && (bin.canExecute() || bin.setExecutable(true, false))
        val updated = OpenCodeRuntimeState(
            isInstalled = installed,
            isInstalling = false,
            version = if (installed) markerVersion ?: "detected" else null,
            binaryPath = if (installed) bin?.absolutePath else null,
            statusDetail = when {
                !runtimeInstalled -> "Linux runtime not installed yet. Tap Install OpenCode to verify and set up automatically."
                installed -> "OpenCode v${markerVersion ?: "detected"} ready inside Linux runtime."
                else -> "OpenCode binary not found in Linux runtime. Tap Install OpenCode to download automatically."
            },
            errorMessage = null,
        )
        _state.value = updated
        return updated
    }

    suspend fun verifyInstallation(): OpenCodeRuntimeState {
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
                    command = "opencode --version",
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
                    ?: "OpenCode exited with code $exitCode inside PRoot."
                val failed = base.copy(
                    isInstalled = false,
                    statusDetail = "OpenCode binary check failed inside PRoot.",
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
                    statusDetail = "OpenCode v$liveVer verified inside PRoot.",
                    errorMessage = null,
                )
                _state.value = ok
                ok
            }
        }
    }

    suspend fun installAutomatic(forceUpdate: Boolean = false): Result<OpenCodeRuntimeState> = mutex.withLock {
        withContext(Dispatchers.IO) {
            _state.update {
                it.copy(
                    isInstalling = true,
                    errorMessage = null,
                    statusDetail = "Verifying Linux environment and OpenCode binary…",
                )
            }

            try {
                val healthBefore = runtimeInstallerRepository.runHealthCheck()
                val existingBin = resolveBinaryFile()
                if (!forceUpdate && healthBefore.isHealthy && existingBin != null &&
                    (existingBin.canExecute() || existingBin.setExecutable(true, false))
                ) {
                    val refreshed = refreshStatusSync()
                    return@withContext Result.success(refreshed)
                }

                if (!healthBefore.isHealthy || !runtimeInstallerRepository.activeDir.exists()) {
                    _state.update {
                        it.copy(statusDetail = "Setting up base Linux environment before installing OpenCode…")
                    }
                    val rtRes = runtimeInstallerRepository.installBlocking()
                    if (rtRes.isFailure) {
                        val err = rtRes.exceptionOrNull()!!
                        val msg = err.message ?: "Base Linux runtime setup failed."
                        _state.update { it.copy(isInstalling = false, errorMessage = msg, statusDetail = msg) }
                        return@withContext Result.failure(err)
                    }
                }

                val postRuntimeBin = resolveBinaryFile()
                if (!forceUpdate && postRuntimeBin != null &&
                    (postRuntimeBin.canExecute() || postRuntimeBin.setExecutable(true, false))
                ) {
                    runtimeInstallerRepository.runHealthCheck()
                    val refreshed = refreshStatusSync()
                    return@withContext Result.success(refreshed)
                }

                val deviceInfo = DeviceEnvironment.inspect(appContext)
                val targetAbi = deviceInfo.supportedAbis.firstOrNull {
                    it.equals("arm64-v8a", ignoreCase = true) || it.equals("x86_64", ignoreCase = true)
                }?.lowercase() ?: deviceInfo.primaryAbi.lowercase()

                _state.update { it.copy(statusDetail = "Resolving official OpenCode release…") }
                val upstreamRes = upstreamBootstrap.resolveOpenCode(targetAbi)
                if (upstreamRes.isSuccess) {
                    val pkg = upstreamRes.getOrThrow()
                    val stagingRoot = File(
                        runtimeInstallerRepository.stagingDir,
                        "opencode-auto-${System.currentTimeMillis()}",
                    )
                    try {
                        _state.update { it.copy(statusDetail = "Downloading ${pkg.displayName}…") }
                        val downloaded = downloader.downloadAndVerify(
                            bundle = pkg.bundle,
                            downloadsDir = runtimeInstallerRepository.downloadsDir,
                            targetArchiveName = pkg.archiveName,
                            onProgress = { dl, total ->
                                val pct = if (total > 0) ((dl * 100L) / total).toInt().coerceIn(0, 100) else null
                                _state.update { st ->
                                    st.copy(
                                        statusDetail = if (pct != null) {
                                            "Downloading ${pkg.displayName} ($pct%)…"
                                        } else {
                                            "Downloading ${pkg.displayName} ($dl bytes)…"
                                        }
                                    )
                                }
                            },
                        ).getOrThrow()

                        _state.update { it.copy(statusDetail = "Extracting ${pkg.displayName}…") }
                        RuntimeArchiveExtractor.extractToStaging(
                            archiveFile = downloaded.archiveFile,
                            format = pkg.bundle.archiveFormat,
                            stagingRoot = stagingRoot,
                            stripRootPrefix = pkg.bundle.extractionRoot,
                        ).getOrThrow()

                        val stagedBinary = findOpenCodeBinary(stagingRoot)
                            ?: error("Official archive does not contain an opencode binary.")
                        installStagedBinaryIntoActive(
                            stagedBinary = stagedBinary,
                            stagingRoot = stagingRoot,
                            version = pkg.version,
                            sha256Hex = downloaded.sha256Hex,
                        )
                        stagingRoot.deleteRecursively()
                        runtimeInstallerRepository.runHealthCheck()
                        return@withContext Result.success(refreshStatusSync())
                    } catch (ce: CancellationException) {
                        stagingRoot.deleteRecursively()
                        throw ce
                    } catch (upstreamInstallErr: Throwable) {
                        stagingRoot.deleteRecursively()
                        if (upstreamInstallErr.message?.contains("SHA-256", ignoreCase = true) == true) {
                            val msg = upstreamInstallErr.message ?: "OpenCode SHA-256 verification failed."
                            _state.update { it.copy(isInstalling = false, errorMessage = msg, statusDetail = msg) }
                            return@withContext Result.failure(upstreamInstallErr)
                        }
                        // Fallback to npm if binary release asset download/extract fails cleanly
                        val npmFallback = tryInstallOpenCodeViaNpm()
                        if (npmFallback.isSuccess) return@withContext npmFallback
                        val msg = upstreamInstallErr.message ?: npmFallback.exceptionOrNull()?.message
                            ?: "Failed to install OpenCode."
                        _state.update { it.copy(isInstalling = false, errorMessage = msg, statusDetail = msg) }
                        return@withContext Result.failure(upstreamInstallErr)
                    }
                } else {
                    val npmFallback = tryInstallOpenCodeViaNpm()
                    if (npmFallback.isSuccess) return@withContext npmFallback
                    val err = upstreamRes.exceptionOrNull()
                        ?: npmFallback.exceptionOrNull()
                        ?: IllegalStateException("Unable to install OpenCode.")
                    val msg = err.message ?: "Unable to resolve or install OpenCode."
                    _state.update { it.copy(isInstalling = false, errorMessage = msg, statusDetail = msg) }
                    return@withContext Result.failure(err)
                }
            } catch (ce: CancellationException) {
                _state.update {
                    it.copy(
                        isInstalling = false,
                        statusDetail = "OpenCode installation cancelled.",
                    )
                }
                throw ce
            } catch (t: Throwable) {
                val msg = t.message ?: AgtError.RuntimeVerificationFailed().userMessage
                _state.update { it.copy(isInstalling = false, errorMessage = msg, statusDetail = msg) }
                Result.failure(t)
            }
        }
    }

    private suspend fun tryInstallOpenCodeViaNpm(): Result<OpenCodeRuntimeState> {
        val executor = runtimeExecutor ?: return Result.failure(
            IllegalStateException("Runtime executor unavailable for npm fallback.")
        )
        val activeDir = runtimeInstallerRepository.activeDir
        val hasNpm = listOf("usr/local/bin/npm", "usr/bin/npm", "bin/npm")
            .any { File(activeDir, it).exists() }
        if (!hasNpm) {
            return Result.failure(IllegalStateException("npm toolchain not installed in Linux runtime."))
        }

        _state.update { it.copy(statusDetail = "Installing OpenCode CLI via npm…") }
        var exitCode = -1
        var failureErr: Throwable? = null
        val stdoutLines = mutableListOf<String>()
        val stderrLines = mutableListOf<String>()
        executor.execute(
            RuntimeCommand(
                command = "npm install -g opencode-ai --no-audit --no-fund && opencode --version",
                workingDirectory = activeDir.absolutePath,
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
            return Result.failure(IllegalStateException(detail))
        }
        val bin = resolveBinaryFile()
            ?: return Result.failure(IllegalStateException("npm finished, but opencode executable was not found."))
        bin.setExecutable(true, false)
        val detectedVersion = stdoutLines
            .lastOrNull { it.trim().matches(Regex("v?\\d+\\.\\d+\\.\\d+.*")) }
            ?.trim()
            ?.removePrefix("v")
            ?: "npm"
        val relBin = bin.canonicalFile.relativeTo(activeDir.canonicalFile).path.replace('\\', '/')
        val markerDir = File(activeDir, ".agt-markers").apply { mkdirs() }
        val marker = JSONObject()
            .put("id", "opencode_agent")
            .put("name", "OpenCode Agent Runtime")
            .put("version", detectedVersion)
            .put("source", "npm:opencode-ai")
            .put("binary", relBin)
            .put("installed_at_ms", System.currentTimeMillis())
        File(markerDir, "opencode_agent.json").writeText(marker.toString(2))
        runtimeInstallerRepository.runHealthCheck()
        return Result.success(refreshStatusSync())
    }

    suspend fun installOrUpdate(manifestUrl: String): Result<OpenCodeRuntimeState> {
        val trimmed = manifestUrl.trim()
        if (trimmed.isEmpty()) {
            return installAutomatic(forceUpdate = false)
        }
        return mutex.withLock {
            withContext(Dispatchers.IO) {
                val runtimeHealth = runtimeInstallerRepository.runHealthCheck()
                if (!runtimeHealth.isHealthy || !runtimeInstallerRepository.activeDir.exists()) {
                    val msg = "Base Linux runtime must be installed and healthy before installing OpenCode."
                    _state.update { it.copy(isInstalling = false, errorMessage = msg, statusDetail = msg) }
                    return@withContext Result.failure(IllegalStateException(msg))
                }

                preferencesRepository.setOpenCodeManifestUrl(trimmed)
                _state.update {
                    it.copy(
                        isInstalling = true,
                        errorMessage = null,
                        statusDetail = "Fetching OpenCode release manifest…",
                    )
                }

                val stagingRoot = File(runtimeInstallerRepository.stagingDir, "opencode-${System.currentTimeMillis()}")
                try {
                    val manifest = downloader.fetchManifest(trimmed).getOrElse { err ->
                        val msg = err.message ?: "Failed to fetch OpenCode manifest."
                        _state.update { it.copy(isInstalling = false, errorMessage = msg, statusDetail = msg) }
                        return@withContext Result.failure(err)
                    }

                    _state.update {
                        it.copy(statusDetail = "Downloading OpenCode v${manifest.version}…")
                    }

                    val archiveName = "opencode-${manifest.version}.${manifest.bundle.archiveFormat.extensionHint}"
                    val downloaded = downloader.downloadAndVerify(
                        bundle = manifest.bundle,
                        downloadsDir = runtimeInstallerRepository.downloadsDir,
                        targetArchiveName = archiveName,
                        onProgress = { dl, total ->
                            val pct = if (total > 0) ((dl * 100L) / total).toInt().coerceIn(0, 100) else null
                            _state.update { st ->
                                st.copy(
                                    statusDetail = if (pct != null) {
                                        "Downloading OpenCode v${manifest.version} ($pct%)…"
                                    } else {
                                        "Downloading OpenCode v${manifest.version} ($dl bytes)…"
                                    }
                                )
                            }
                        },
                    ).getOrElse { err ->
                        val msg = err.message ?: "OpenCode download/verification failed."
                        _state.update { it.copy(isInstalling = false, errorMessage = msg, statusDetail = msg) }
                        return@withContext Result.failure(err)
                    }

                    _state.update { it.copy(statusDetail = "Extracting OpenCode into staging…") }
                    RuntimeArchiveExtractor.extractToStaging(
                        archiveFile = downloaded.archiveFile,
                        format = manifest.bundle.archiveFormat,
                        stagingRoot = stagingRoot,
                        stripRootPrefix = manifest.bundle.extractionRoot,
                    ).getOrElse { err ->
                        val msg = err.message ?: "Failed to extract OpenCode archive."
                        _state.update { it.copy(isInstalling = false, errorMessage = msg, statusDetail = msg) }
                        return@withContext Result.failure(err)
                    }

                    val stagedBinary = findOpenCodeBinary(stagingRoot)
                        ?: run {
                            stagingRoot.deleteRecursively()
                            val msg = "Verified archive does not contain an opencode binary."
                            _state.update { it.copy(isInstalling = false, errorMessage = msg, statusDetail = msg) }
                            return@withContext Result.failure(IllegalStateException(msg))
                        }

                    installStagedBinaryIntoActive(
                        stagedBinary = stagedBinary,
                        stagingRoot = stagingRoot,
                        version = manifest.version,
                        sha256Hex = downloaded.sha256Hex,
                    )

                    stagingRoot.deleteRecursively()
                    runtimeInstallerRepository.runHealthCheck()
                    val refreshed = refreshStatusSync()
                    Result.success(refreshed)
                } catch (ce: CancellationException) {
                    stagingRoot.deleteRecursively()
                    _state.update {
                        it.copy(
                            isInstalling = false,
                            statusDetail = "OpenCode installation cancelled.",
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

    private fun installStagedBinaryIntoActive(
        stagedBinary: File,
        stagingRoot: File,
        version: String,
        sha256Hex: String,
    ) {
        val activeDir = runtimeInstallerRepository.activeDir
        val rawRelBin = stagedBinary.canonicalFile
            .relativeTo(stagingRoot.canonicalFile)
            .path
            .replace('\\', '/')
        val relBin = if (rawRelBin == "opencode") "usr/local/bin/opencode" else rawRelBin
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
            .put("id", "opencode_agent")
            .put("name", "OpenCode Agent Runtime")
            .put("version", version)
            .put("sha256", sha256Hex)
            .put("binary", relBin)
            .put("installed_at_ms", System.currentTimeMillis())
        File(markerDir, "opencode_agent.json").writeText(marker.toString(2))
    }

    private fun findOpenCodeBinary(root: File): File? {
        val preferred = listOf("usr/local/bin/opencode", "usr/bin/opencode", "bin/opencode", "opencode")
            .mapNotNull { rel -> ProjectPathValidator.resolveWithinRoot(root, rel).getOrNull() }
            .firstOrNull { it.exists() && it.isFile }
        if (preferred != null) return preferred
        return root.walkTopDown().firstOrNull { it.isFile && it.name == "opencode" }
    }
}
