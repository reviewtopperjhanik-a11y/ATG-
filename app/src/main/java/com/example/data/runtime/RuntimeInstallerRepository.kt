package com.example.data.runtime

import android.content.Context
import com.example.core.common.AgtError
import com.example.core.common.DeviceEnvironment
import com.example.core.common.ProjectPathValidator
import com.example.core.config.AgtAppConfig
import com.example.core.config.ConfigResolution
import com.example.core.config.ConfigSource
import com.example.data.preferences.AppPreferencesRepository
import com.example.domain.model.ComponentInstallStatus
import com.example.domain.model.RuntimeArchiveFormat
import com.example.domain.model.RuntimeComponentSpec
import com.example.domain.model.RuntimeHealthReport
import com.example.domain.model.RuntimeInstallStage
import com.example.domain.model.RuntimeInstallState
import com.example.domain.model.RuntimeManifest
import com.example.domain.model.SetupComponentItem
import com.example.domain.model.defaultRuntimeLicenses
import com.example.domain.model.defaultSetupComponents
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.nio.charset.StandardCharsets
import java.util.concurrent.CompletableFuture
import java.util.concurrent.TimeUnit

/**
 * Coordinates M4 Linux runtime provisioning:
 * compatibility checks -> manifest fetch -> resumable download -> SHA-256 verification ->
 * isolated staging extraction -> required binary/layout validation -> atomic activation with
 * rollback backup -> health checks -> persisted markers -> safe repair/recovery.
 *
 * Strictly leaves user project workspaces outside `filesDir/runtime` untouched.
 */
class RuntimeInstallerRepository(
    context: Context,
    private val preferencesRepository: AppPreferencesRepository,
    okHttpClient: OkHttpClient = RuntimeDownloader.defaultClient(),
    private val appConfig: AgtAppConfig = AgtAppConfig.fromBuildConfig(),
    private val upstreamBootstrap: UpstreamRuntimeBootstrap = UpstreamRuntimeBootstrap(okHttpClient),
    private val processBridge: ProcessBridge = AndroidProcessBridge(
        nativeLibraryDir = context.applicationContext.applicationInfo?.nativeLibraryDir?.let(::File),
    ),
) {
    private val appContext = context.applicationContext
    private val downloader = RuntimeDownloader(okHttpClient)
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val installMutex = Mutex()
    private var activeInstallJob: Job? = null

    val runtimeRootDir = File(appContext.filesDir, "runtime")
    val downloadsDir = File(runtimeRootDir, "downloads")
    val stagingDir = File(runtimeRootDir, "staging")
    val activeDir = File(runtimeRootDir, "active")
    val backupDir = File(runtimeRootDir, "backup")
    private val stateDir = File(runtimeRootDir, "state")
    private val stateFile = File(stateDir, "runtime_state.json")
    private val prootNoSeccompMarker = File(stateDir, "proot_no_seccomp")

    private val _installState = MutableStateFlow(RuntimeInstallState())
    val installState: StateFlow<RuntimeInstallState> = _installState.asStateFlow()

    init {
        recoverAndLoadStateSync()
    }

    fun enableProotSeccompFallback() {
        processBridge.setSeccompDisabled(true)
        runCatching {
            stateDir.mkdirs()
            prootNoSeccompMarker.writeText("1\n")
        }
    }

    fun evaluateDeviceCompatibility() =
        RuntimeCompatibilityChecker.evaluate(DeviceEnvironment.inspect(appContext))

    fun startInstall(manifestUrl: String? = null, forceUpdate: Boolean = false): Job {
        val current = activeInstallJob
        if (current != null && current.isActive) {
            return current
        }
        val job = scope.launch {
            installBlocking(manifestUrl, forceUpdate = forceUpdate)
        }
        activeInstallJob = job
        return job
    }

    fun cancelInstall() {
        activeInstallJob?.cancel()
        activeInstallJob = null
    }

    suspend fun installBlocking(
        manifestUrl: String? = null,
        forceUpdate: Boolean = false,
    ): Result<RuntimeHealthReport> = installMutex.withLock {
        if (!forceUpdate && manifestUrl == null) {
            verifyExistingEnvironmentBeforeDownload()?.let { existingHealthy ->
                return@withLock Result.success(existingHealthy)
            }
        }

        val prefs = preferencesRepository.preferences.first()
        val trimmedUrl: String = if (manifestUrl != null) {
            val explicit = manifestUrl.trim()
            if (explicit.isEmpty()) {
                val msg = "Runtime manifest endpoint cannot be empty."
                _installState.update {
                    it.copy(
                        stage = RuntimeInstallStage.FAILED,
                        isInstalling = false,
                        hasCheckedEnvironment = true,
                        errorMessage = msg,
                        statusDetail = msg,
                    )
                }
                return Result.failure(RuntimeManifestException(AgtError.RuntimeManifestInvalid(msg)))
            }
            explicit
        } else {
            when (val resolution = appConfig.resolveRuntimeManifestUrl(prefs.runtimeManifestUrl)) {
                is ConfigResolution.MissingOrInvalid -> {
                    if (appConfig.allowUpstreamBootstrap) {
                        return@withLock installFromUpstreamBootstrapLocked()
                    }
                    _installState.update {
                        it.copy(
                            stage = RuntimeInstallStage.FAILED,
                            isInstalling = false,
                            hasCheckedEnvironment = true,
                            errorMessage = resolution.userMessage,
                            statusDetail = resolution.userMessage,
                        )
                    }
                    return Result.failure(
                        RuntimeManifestException(
                            AgtError.RuntimeManifestInvalid(
                                userMessage = resolution.userMessage,
                                diagnosticDetail = resolution.diagnosticDetail,
                            )
                        )
                    )
                }
                is ConfigResolution.Configured -> {
                    if (resolution.source == ConfigSource.BUILD_CONFIG && prefs.runtimeManifestUrl.isNotBlank()) {
                        // Migrate away from stale legacy manual override when official build config is present.
                        preferencesRepository.setRuntimeManifestUrl("")
                    }
                    resolution.value
                }
            }
        }

        var currentStaging: File? = null
        try {
            // Stage 1: Baseline device compatibility check
            updateStage(
                stage = RuntimeInstallStage.CHECKING_COMPATIBILITY,
                detail = "Checking CPU ABI, Android version, and free storage…",
                componentId = "linux_runtime",
                componentStatus = ComponentInstallStatus.WAITING,
            )
            val deviceInfo = DeviceEnvironment.inspect(appContext)
            val preCompat = RuntimeCompatibilityChecker.evaluate(deviceInfo, manifest = null)

            // Stage 2: Fetch & validate manifest
            updateStage(
                stage = RuntimeInstallStage.FETCHING_MANIFEST,
                detail = "Fetching official runtime manifest…",
                componentId = "linux_runtime",
                componentStatus = ComponentInstallStatus.DOWNLOADING,
            )
            val manifestResult = downloader.fetchManifest(trimmedUrl)
            val manifest = manifestResult.getOrElse { err ->
                val agt = (err as? RuntimeManifestException)?.agtError
                    ?: (err as? RuntimeDownloadException)?.agtError
                    ?: AgtError.RuntimeManifestInvalid(err.message ?: "Manifest fetch failed.")
                if (!preCompat.abiSupported) {
                    return failInstall(preCompat.blockingReasons.joinToString(" "))
                }
                return failInstall(agt.userMessage, err)
            }

            // Stage 2b: Re-evaluate compatibility against manifest constraints & select compatible ABI bundle
            val manifestCompat = RuntimeCompatibilityChecker.evaluate(deviceInfo, manifest)
            if (!manifestCompat.isCompatible) {
                return failInstall(manifestCompat.blockingReasons.joinToString(" "))
            }
            val (selectedAbi, selectedBundle) = manifest.selectBundleForDevice(deviceInfo.supportedAbis)
                ?: return failInstall(
                    "Unsupported CPU ABI (${deviceInfo.primaryAbi}). Supported: ${manifest.supportedAbis.joinToString(", ")}."
                )

            // Stage 3: Resumable download + SHA-256 verification
            val archiveName = if (manifest.abiBundles.isNotEmpty()) {
                "${manifest.runtimeId}-${manifest.version}-$selectedAbi.${selectedBundle.archiveFormat.extensionHint}"
            } else {
                "${manifest.runtimeId}-${manifest.version}.${selectedBundle.archiveFormat.extensionHint}"
            }
            updateStage(
                stage = RuntimeInstallStage.DOWNLOADING,
                detail = "Downloading ${manifest.runtimeId} v${manifest.version} ($selectedAbi)…",
                componentId = "linux_runtime",
                componentStatus = ComponentInstallStatus.DOWNLOADING,
                progressPercent = 0,
            )

            val downloaded = downloader.downloadAndVerify(
                bundle = selectedBundle,
                downloadsDir = downloadsDir,
                targetArchiveName = archiveName,
                onProgress = { dl, total ->
                    val pct = if (total > 0L) ((dl * 100L) / total).toInt().coerceIn(0, 100) else null
                    val stage = if (pct != null && pct >= 100) {
                        RuntimeInstallStage.VERIFYING
                    } else {
                        RuntimeInstallStage.DOWNLOADING
                    }
                    _installState.update { state ->
                        state.copy(
                            stage = stage,
                            isInstalling = true,
                            downloadedBytes = dl,
                            totalBytes = total.coerceAtLeast(0L),
                            progressPercent = pct,
                            errorMessage = null,
                            statusDetail = if (stage == RuntimeInstallStage.VERIFYING) {
                                "Verifying SHA-256 digest…"
                            } else {
                                "Downloading ${formatBytes(dl)}${if (total > 0) " / ${formatBytes(total)}" else ""}…"
                            },
                            components = updateComponentStatus(
                                state.components,
                                "linux_runtime",
                                if (stage == RuntimeInstallStage.VERIFYING) {
                                    ComponentInstallStatus.VERIFYING
                                } else {
                                    ComponentInstallStatus.DOWNLOADING
                                },
                                pct,
                            ),
                        )
                    }
                },
            ).getOrElse { err ->
                return failInstall(err.message ?: "Runtime download failed.", err)
            }

            // Stage 4: Extract into isolated staging directory
            currentStaging = File(stagingDir, "${manifest.version}-${System.currentTimeMillis()}")
            updateStage(
                stage = RuntimeInstallStage.EXTRACTING,
                detail = "Extracting verified archive into isolated staging…",
                componentId = "linux_runtime",
                componentStatus = ComponentInstallStatus.EXTRACTING,
                progressPercent = null,
            )
            RuntimeArchiveExtractor.extractToStaging(
                archiveFile = downloaded.archiveFile,
                format = selectedBundle.archiveFormat,
                stagingRoot = currentStaging,
                stripRootPrefix = selectedBundle.extractionRoot,
            ).getOrElse { err ->
                return failInstall(err.message ?: "Archive extraction failed.", err)
            }

            // Stage 5: Validate required binaries, component dependencies, and file layout inside staging
            updateStage(
                stage = RuntimeInstallStage.VALIDATING,
                detail = "Validating required Linux binaries and directory layout…",
                componentId = "base_toolchains",
                componentStatus = ComponentInstallStatus.INSTALLING,
            )
            val missing = validateRequiredPaths(currentStaging, selectedBundle.requiredPaths)
            if (missing.isNotEmpty()) {
                currentStaging.deleteRecursively()
                return failInstall("Staged runtime missing required files: ${missing.joinToString(", ")}")
            }

            val verifiedComponents = validateAndResolveComponents(
                stagingRoot = currentStaging,
                manifest = manifest,
                bundleRequiredPaths = selectedBundle.requiredPaths,
            ).getOrElse { err ->
                currentStaging.deleteRecursively()
                return failInstall(err.message ?: "Runtime component verification failed.", err)
            }

            ensureBaseRootfsSystemFiles(currentStaging)
            ensureBinariesExecutable(currentStaging)
            writeComponentMarkers(currentStaging, manifest, verifiedComponents, downloaded.sha256Hex)

            // Stage 6: Atomic activation with backup rollback
            updateStage(
                stage = RuntimeInstallStage.ACTIVATING,
                detail = "Activating runtime atomically…",
                componentId = "linux_runtime",
                componentStatus = ComponentInstallStatus.INSTALLING,
            )
            activateStagingAtomically(
                staging = currentStaging,
                manifest = manifest,
                bundleSpec = selectedBundle,
                abi = selectedAbi,
                sha256 = downloaded.sha256Hex,
                verifiedComponents = verifiedComponents,
            ).getOrElse { err ->
                return failInstall(err.message ?: "Atomic runtime activation failed.", err)
            }

            // Stage 7: Run post-activation health check
            updateStage(
                stage = RuntimeInstallStage.HEALTH_CHECKING,
                detail = "Running post-activation runtime health checks…",
                componentId = "base_toolchains",
                componentStatus = ComponentInstallStatus.VERIFYING,
            )
            val health = runHealthCheckInternal(includeLiveProotProbe = true)
            if (!health.isHealthy) {
                rollbackFromBackupIfPresent()
                return failInstall(health.summary)
            }

            // Clean backup only after health check succeeds
            if (backupDir.exists()) {
                backupDir.deleteRecursively()
            }

            // Mark onboarding complete once Linux runtime is healthy
            preferencesRepository.setOnboardingCompleted(true)
            refreshStateFromDisk(health, checkedEnvironment = true)
            Result.success(health)
        } catch (ce: CancellationException) {
            currentStaging?.deleteRecursively()
            val hasPart = downloadsDir.listFiles()?.any { it.name.endsWith(".part") } == true
            val existingHealthy = isExistingRuntimeValidOnDisk()
            _installState.update { state ->
                state.copy(
                    stage = if (existingHealthy) RuntimeInstallStage.INSTALLED else RuntimeInstallStage.CANCELLED,
                    isInstalling = false,
                    isInstalled = existingHealthy,
                    hasCheckedEnvironment = true,
                    canResumeDownload = hasPart,
                    progressPercent = null,
                    errorMessage = null,
                    statusDetail = if (hasPart) {
                        "Installation cancelled. Partial download preserved for resume."
                    } else {
                        "Installation cancelled."
                    },
                    components = reconcileComponentsWithDisk(state.components),
                )
            }
            Result.failure(ce)
        }
    }

    private suspend fun installFromUpstreamBootstrapLocked(): Result<RuntimeHealthReport> {
        var currentStaging: File? = null
        val tempSubdirs = mutableListOf<File>()
        return try {
            updateStage(
                stage = RuntimeInstallStage.CHECKING_COMPATIBILITY,
                detail = "Checking CPU ABI, Android version, and free storage…",
                componentId = "linux_runtime",
                componentStatus = ComponentInstallStatus.WAITING,
            )
            val deviceInfo = DeviceEnvironment.inspect(appContext)
            val compat = RuntimeCompatibilityChecker.evaluate(deviceInfo, manifest = null)
            if (!compat.isCompatible) {
                return failInstall(compat.blockingReasons.joinToString(" "))
            }
            val selectedAbi = deviceInfo.supportedAbis.firstOrNull {
                it.equals("arm64-v8a", ignoreCase = true) || it.equals("x86_64", ignoreCase = true)
            }?.lowercase() ?: return failInstall(
                "Unsupported CPU ABI (${deviceInfo.primaryAbi}). Supported: arm64-v8a, x86_64."
            )

            updateStage(
                stage = RuntimeInstallStage.FETCHING_MANIFEST,
                detail = "Resolving official Ubuntu 24.04, Node.js, GitHub CLI & OpenCode releases…",
                componentId = "linux_runtime",
                componentStatus = ComponentInstallStatus.DOWNLOADING,
            )
            val plan = upstreamBootstrap.resolvePlan(selectedAbi).getOrElse { err ->
                val agt = (err as? RuntimeManifestException)?.agtError
                    ?: (err as? RuntimeDownloadException)?.agtError
                return failInstall(agt?.userMessage ?: err.message ?: "Failed to resolve official upstream releases.", err)
            }

            val ts = System.currentTimeMillis()
            currentStaging = File(stagingDir, "upstream-$ts")

            // 1. Ubuntu Base Rootfs (linux_runtime)
            val ubuntuPkg = plan.ubuntuRootfs
            val ubuntuArchive = downloadUpstreamPackage(ubuntuPkg, "linux_runtime")
                .getOrElse { err -> return failInstall(err.message ?: "Ubuntu rootfs download failed.", err) }

            updateStage(
                stage = RuntimeInstallStage.EXTRACTING,
                detail = "Extracting ${ubuntuPkg.displayName} rootfs into staging…",
                componentId = "linux_runtime",
                componentStatus = ComponentInstallStatus.EXTRACTING,
                progressPercent = null,
            )
            RuntimeArchiveExtractor.extractToStaging(
                archiveFile = ubuntuArchive.archiveFile,
                format = ubuntuPkg.bundle.archiveFormat,
                stagingRoot = currentStaging,
                stripRootPrefix = ubuntuPkg.bundle.extractionRoot,
            ).getOrElse { err -> return failInstall(err.message ?: "Ubuntu rootfs extraction failed.", err) }

            // 2. Node.js Toolchain (base_toolchains)
            val nodePkg = plan.nodeToolchain
            val nodeArchive = downloadUpstreamPackage(nodePkg, "base_toolchains")
                .getOrElse { err -> return failInstall(err.message ?: "Node.js toolchain download failed.", err) }
            val nodeTmp = File(stagingDir, "pkg-node-$ts").also { tempSubdirs += it }
            updateStage(
                stage = RuntimeInstallStage.EXTRACTING,
                detail = "Installing ${nodePkg.displayName} into /usr…",
                componentId = "base_toolchains",
                componentStatus = ComponentInstallStatus.EXTRACTING,
                progressPercent = null,
            )
            RuntimeArchiveExtractor.extractToStaging(
                archiveFile = nodeArchive.archiveFile,
                format = nodePkg.bundle.archiveFormat,
                stagingRoot = nodeTmp,
                stripRootPrefix = nodePkg.bundle.extractionRoot,
            ).getOrElse { err -> return failInstall(err.message ?: "Node.js extraction failed.", err) }
            RuntimeArchiveExtractor.mergeDirectoryInto(
                sourceDir = nodeTmp,
                targetDir = File(currentStaging, "usr"),
                confinementRoot = currentStaging,
            )
            nodeTmp.deleteRecursively()

            // 3. GitHub CLI (github_cli)
            val verifiedSpecs = mutableListOf(
                RuntimeComponentSpec(
                    id = "linux_runtime",
                    name = "Linux Runtime (PRoot / Ubuntu)",
                    description = ubuntuPkg.displayName,
                    version = ubuntuPkg.version,
                    requiredPaths = listOf("bin/sh", "etc/os-release"),
                ),
                RuntimeComponentSpec(
                    id = "base_toolchains",
                    name = "Base Toolchains",
                    description = nodePkg.displayName,
                    version = nodePkg.version,
                    requiredPaths = listOf("usr/bin/node", "usr/bin/npm"),
                    dependencies = listOf("linux_runtime"),
                ),
            )
            markComponentCompleted("linux_runtime", ubuntuPkg.version)
            markComponentCompleted("base_toolchains", nodePkg.version)

            val optionalWarnings = plan.warnings.toMutableList()
            val ghPkg = plan.githubCli
            if (ghPkg != null) {
                val ghRes = downloadUpstreamPackage(ghPkg, "github_cli")
                if (ghRes.isSuccess) {
                    val ghTmp = File(stagingDir, "pkg-gh-$ts").also { tempSubdirs += it }
                    updateStage(
                        stage = RuntimeInstallStage.EXTRACTING,
                        detail = "Installing ${ghPkg.displayName} into /usr…",
                        componentId = "github_cli",
                        componentStatus = ComponentInstallStatus.EXTRACTING,
                        progressPercent = null,
                    )
                    val ghExtract = RuntimeArchiveExtractor.extractToStaging(
                        archiveFile = ghRes.getOrThrow().archiveFile,
                        format = ghPkg.bundle.archiveFormat,
                        stagingRoot = ghTmp,
                        stripRootPrefix = ghPkg.bundle.extractionRoot,
                    )
                    if (ghExtract.isSuccess) {
                        RuntimeArchiveExtractor.mergeDirectoryInto(
                            sourceDir = ghTmp,
                            targetDir = File(currentStaging, "usr"),
                            confinementRoot = currentStaging,
                        )
                        ghTmp.deleteRecursively()
                        if (File(currentStaging, "usr/bin/gh").exists()) {
                            verifiedSpecs += RuntimeComponentSpec(
                                id = "github_cli",
                                name = "GitHub CLI (gh)",
                                description = ghPkg.displayName,
                                version = ghPkg.version,
                                requiredPaths = listOf("usr/bin/gh"),
                                dependencies = listOf("linux_runtime", "base_toolchains"),
                            )
                            markComponentCompleted("github_cli", ghPkg.version)
                        }
                    } else {
                        ghTmp.deleteRecursively()
                        val err = ghExtract.exceptionOrNull()
                        if (isVerificationFailure(err)) {
                            return failInstall(err?.message ?: "Failed to verify GitHub CLI.", err)
                        }
                        optionalWarnings += "GitHub CLI extraction skipped (${err?.message ?: "unavailable"})"
                    }
                } else {
                    val err = ghRes.exceptionOrNull()!!
                    if (isVerificationFailure(err)) {
                        return failInstall(err.message ?: "GitHub CLI SHA-256 verification failed.", err)
                    }
                    optionalWarnings += "GitHub CLI download skipped (${err.message ?: "unavailable"})"
                }
            }

            // 4. OpenCode Agent CLI (opencode_agent)
            val ocPkg = plan.openCodeAgent
            if (ocPkg != null) {
                val ocRes = downloadUpstreamPackage(ocPkg, "opencode_agent")
                if (ocRes.isSuccess) {
                    val ocTmp = File(stagingDir, "pkg-oc-$ts").also { tempSubdirs += it }
                    updateStage(
                        stage = RuntimeInstallStage.EXTRACTING,
                        detail = "Installing ${ocPkg.displayName} into /usr/local/bin…",
                        componentId = "opencode_agent",
                        componentStatus = ComponentInstallStatus.EXTRACTING,
                        progressPercent = null,
                    )
                    val ocExtract = RuntimeArchiveExtractor.extractToStaging(
                        archiveFile = ocRes.getOrThrow().archiveFile,
                        format = ocPkg.bundle.archiveFormat,
                        stagingRoot = ocTmp,
                        stripRootPrefix = ocPkg.bundle.extractionRoot,
                    )
                    if (ocExtract.isSuccess) {
                        val locatedBinary = findBinaryInTree(ocTmp, "opencode")
                        if (locatedBinary != null) {
                            val targetOc = File(currentStaging, "usr/local/bin/opencode")
                            targetOc.parentFile?.mkdirs()
                            locatedBinary.copyTo(targetOc, overwrite = true)
                            targetOc.setExecutable(true, false)
                            verifiedSpecs += RuntimeComponentSpec(
                                id = "opencode_agent",
                                name = "OpenCode Agent Runtime",
                                description = ocPkg.displayName,
                                version = ocPkg.version,
                                requiredPaths = listOf("usr/local/bin/opencode"),
                                dependencies = listOf("linux_runtime", "base_toolchains"),
                            )
                            markComponentCompleted("opencode_agent", ocPkg.version)
                        } else {
                            optionalWarnings += "OpenCode binary not found inside release archive; install from Settings -> Agent."
                        }
                        ocTmp.deleteRecursively()
                    } else {
                        ocTmp.deleteRecursively()
                        val err = ocExtract.exceptionOrNull()
                        if (isVerificationFailure(err)) {
                            return failInstall(err?.message ?: "OpenCode verification failed.", err)
                        }
                        optionalWarnings += "OpenCode extraction skipped (${err?.message ?: "unavailable"})"
                    }
                } else {
                    val err = ocRes.exceptionOrNull()!!
                    if (isVerificationFailure(err)) {
                        return failInstall(err.message ?: "OpenCode SHA-256 verification failed.", err)
                    }
                    optionalWarnings += "OpenCode download skipped (${err.message ?: "unavailable"})"
                }
            }

            // 5. Base directories, DNS resolv.conf, /etc/hosts, CA certs, and Skills/MCP registry
            ensureBaseRootfsSystemFiles(currentStaging)

            val skillsRegistry = File(currentStaging, "usr/local/share/agt/skills/registry.json")
            skillsRegistry.parentFile?.mkdirs()
            if (!skillsRegistry.exists()) {
                skillsRegistry.writeText(
                    """
                    {
                      "schema_version": 1,
                      "skills": [
                        {
                          "id": "repo_inspector",
                          "name": "Repository Inspector",
                          "version": "1.0.0",
                          "description": "Inspect project layout, manifests, and test runners."
                        }
                      ]
                    }
                    """.trimIndent() + "\n"
                )
            }
            verifiedSpecs += RuntimeComponentSpec(
                id = "base_skills_mcp",
                name = "Base Skills & MCP Registry",
                description = "Repository inspection skills and HTTP MCP registry.",
                version = "1.0.0",
                requiredPaths = listOf("usr/local/share/agt/skills/registry.json"),
                dependencies = listOf("linux_runtime"),
            )
            markComponentCompleted("base_skills_mcp", "1.0.0")

            // 6. Validate required files, chmod binaries, write markers, and activate atomically
            updateStage(
                stage = RuntimeInstallStage.VALIDATING,
                detail = "Validating Linux rootfs layout and SHA-256 verified binaries…",
                componentId = "base_skills_mcp",
                componentStatus = ComponentInstallStatus.INSTALLING,
            )
            val allReqPaths = verifiedSpecs.flatMap { it.requiredPaths }.distinct()
            val missing = validateRequiredPaths(currentStaging, allReqPaths)
            if (missing.isNotEmpty()) {
                currentStaging.deleteRecursively()
                return failInstall("Staged upstream runtime missing required files: ${missing.joinToString(", ")}")
            }

            ensureBinariesExecutable(currentStaging)
            val syntheticManifest = RuntimeManifest(
                runtimeId = "agt-ubuntu-upstream",
                version = ubuntuPkg.version,
                releaseChannel = "stable",
                supportedAbis = listOf("arm64-v8a", "x86_64"),
                minAndroidSdk = RuntimeCompatibilityChecker.DEFAULT_MIN_SDK,
                minRequiredFreeBytes = RuntimeCompatibilityChecker.DEFAULT_MIN_FREE_BYTES,
                bundle = ubuntuPkg.bundle.copy(requiredPaths = allReqPaths),
                components = verifiedSpecs,
                licenseNotices = defaultUpstreamLicenses(),
            )
            writeComponentMarkers(currentStaging, syntheticManifest, verifiedSpecs, ubuntuArchive.sha256Hex)

            updateStage(
                stage = RuntimeInstallStage.ACTIVATING,
                detail = "Activating Linux runtime atomically…",
                componentId = "linux_runtime",
                componentStatus = ComponentInstallStatus.INSTALLING,
            )
            activateStagingAtomically(
                staging = currentStaging,
                manifest = syntheticManifest,
                bundleSpec = syntheticManifest.bundle,
                abi = selectedAbi,
                sha256 = ubuntuArchive.sha256Hex,
                verifiedComponents = verifiedSpecs,
            ).getOrElse { err ->
                return failInstall(err.message ?: "Atomic runtime activation failed.", err)
            }

            updateStage(
                stage = RuntimeInstallStage.HEALTH_CHECKING,
                detail = "Running post-activation runtime health checks…",
                componentId = "base_toolchains",
                componentStatus = ComponentInstallStatus.VERIFYING,
            )
            var health = runHealthCheckInternal(includeLiveProotProbe = true)
            if (!health.isHealthy) {
                rollbackFromBackupIfPresent()
                return failInstall(health.summary)
            }

            if (processBridge.isIsolationActive(activeDir)) {
                updateStage(
                    stage = RuntimeInstallStage.HEALTH_CHECKING,
                    detail = "Checking Git CLI inside Linux runtime…",
                    componentId = "base_toolchains",
                    componentStatus = ComponentInstallStatus.INSTALLING,
                )
                val gitResult = ensureGitInstalledLocked(forceReinstall = false)
                if (gitResult.isSuccess) {
                    health = runHealthCheckInternal(includeLiveProotProbe = true)
                } else {
                    optionalWarnings += "Git CLI deferred (${gitResult.exceptionOrNull()?.message ?: "offline"}; auto-installs on first Git action or Repair)"
                }
            }

            if (backupDir.exists()) {
                backupDir.deleteRecursively()
            }

            preferencesRepository.setOnboardingCompleted(true)
            refreshStateFromDisk(health, checkedEnvironment = true)
            if (optionalWarnings.isNotEmpty()) {
                _installState.update { st ->
                    st.copy(
                        statusDetail = "${st.statusDetail} (${optionalWarnings.joinToString("; ")})"
                    )
                }
            }
            Result.success(health)
        } catch (ce: CancellationException) {
            currentStaging?.deleteRecursively()
            tempSubdirs.forEach { it.deleteRecursively() }
            val hasPart = downloadsDir.listFiles()?.any { it.name.endsWith(".part") } == true
            val existingHealthy = isExistingRuntimeValidOnDisk()
            _installState.update { state ->
                state.copy(
                    stage = if (existingHealthy) RuntimeInstallStage.INSTALLED else RuntimeInstallStage.CANCELLED,
                    isInstalling = false,
                    isInstalled = existingHealthy,
                    hasCheckedEnvironment = true,
                    canResumeDownload = hasPart,
                    progressPercent = null,
                    errorMessage = null,
                    statusDetail = if (hasPart) {
                        "Installation cancelled. Partial download preserved for resume."
                    } else {
                        "Installation cancelled."
                    },
                    components = reconcileComponentsWithDisk(state.components),
                )
            }
            Result.failure(ce)
        } catch (t: Throwable) {
            currentStaging?.deleteRecursively()
            tempSubdirs.forEach { it.deleteRecursively() }
            val agt = (t as? RuntimeExtractionException)?.agtError
                ?: (t as? RuntimeDownloadException)?.agtError
                ?: (t as? RuntimeManifestException)?.agtError
            failInstall(agt?.userMessage ?: t.message ?: "Linux environment installation failed.", t)
        }
    }

    private suspend fun downloadUpstreamPackage(
        pkg: UpstreamPackageSpec,
        componentId: String,
    ): Result<DownloadedRuntimeBundle> {
        updateStage(
            stage = RuntimeInstallStage.DOWNLOADING,
            detail = "Downloading ${pkg.displayName}…",
            componentId = componentId,
            componentStatus = ComponentInstallStatus.DOWNLOADING,
            progressPercent = 0,
        )
        return downloader.downloadAndVerify(
            bundle = pkg.bundle,
            downloadsDir = downloadsDir,
            targetArchiveName = pkg.archiveName,
            onProgress = { dl, total ->
                val pct = if (total > 0L) ((dl * 100L) / total).toInt().coerceIn(0, 100) else null
                val stage = if (pct != null && pct >= 100) {
                    RuntimeInstallStage.VERIFYING
                } else {
                    RuntimeInstallStage.DOWNLOADING
                }
                _installState.update { state ->
                    state.copy(
                        stage = stage,
                        isInstalling = true,
                        downloadedBytes = dl,
                        totalBytes = total.coerceAtLeast(0L),
                        progressPercent = pct,
                        errorMessage = null,
                        statusDetail = if (stage == RuntimeInstallStage.VERIFYING) {
                            "Verifying SHA-256 for ${pkg.displayName}…"
                        } else {
                            "Downloading ${pkg.displayName}: ${formatBytes(dl)}${if (total > 0) " / ${formatBytes(total)}" else ""}…"
                        },
                        components = updateComponentStatus(
                            state.components,
                            componentId,
                            if (stage == RuntimeInstallStage.VERIFYING) {
                                ComponentInstallStatus.VERIFYING
                            } else {
                                ComponentInstallStatus.DOWNLOADING
                            },
                            pct,
                        ),
                    )
                }
            },
        )
    }

    private fun markComponentCompleted(componentId: String, version: String) {
        _installState.update { state ->
            state.copy(
                components = state.components.map { item ->
                    if (item.id == componentId) {
                        item.copy(
                            status = ComponentInstallStatus.COMPLETED,
                            progressPercent = 100,
                            version = version,
                        )
                    } else {
                        item
                    }
                }
            )
        }
    }

    private fun findBinaryInTree(root: File, binaryName: String): File? {
        val preferred = listOf(
            "usr/local/bin/$binaryName",
            "usr/bin/$binaryName",
            "bin/$binaryName",
            binaryName,
        ).map { File(root, it) }.firstOrNull { it.exists() && it.isFile }
        if (preferred != null) return preferred
        return root.walkTopDown().firstOrNull { it.isFile && it.name == binaryName }
    }

    private fun defaultUpstreamLicenses(): List<com.example.domain.model.RuntimeLicenseNotice> =
        defaultRuntimeLicenses() + listOf(
            com.example.domain.model.RuntimeLicenseNotice(
                component = "Ubuntu Base 24.04",
                license = "Ubuntu / Canonical Open Source Packages",
                spdxId = "GPL-2.0-or-later",
                summary = "Official Ubuntu ARM64/AMD64 base userspace from cdimage.ubuntu.com.",
            ),
            com.example.domain.model.RuntimeLicenseNotice(
                component = "Node.js",
                license = "MIT License",
                spdxId = "MIT",
                summary = "Official Node.js Linux distribution from nodejs.org.",
            ),
            com.example.domain.model.RuntimeLicenseNotice(
                component = "GitHub CLI (gh)",
                license = "MIT License",
                spdxId = "MIT",
                summary = "Official GitHub CLI Linux release from cli/cli.",
            ),
        )

    suspend fun runHealthCheck(): RuntimeHealthReport = installMutex.withLock {
        withContext(Dispatchers.IO) {
            val report = runHealthCheckInternal(includeLiveProotProbe = true)
            refreshStateFromDisk(report)
            report
        }
    }

    suspend fun ensureGitToolchain(forceReinstall: Boolean = false): Result<String> = installMutex.withLock {
        withContext(Dispatchers.IO) {
            if (!activeDir.exists()) {
                return@withContext Result.failure(
                    IllegalStateException("Linux runtime is not installed. Complete Setup or Settings -> Runtime first.")
                )
            }
            ensureBaseRootfsSystemFiles(activeDir)
            ensureBinariesExecutable(activeDir)
            val result = ensureGitInstalledLocked(forceReinstall = forceReinstall)
            if (result.isSuccess) {
                val updatedHealth = runHealthCheckInternal(includeLiveProotProbe = true)
                refreshStateFromDisk(updatedHealth)
            }
            result
        }
    }

    suspend fun repairRuntime(): Result<RuntimeHealthReport> = installMutex.withLock {
        withContext(Dispatchers.IO) {
            // 1. Clean stale temp/staging/backup artifacts safely (never touch projects)
            if (stagingDir.exists()) {
                stagingDir.deleteRecursively()
            }
            if (backupDir.exists() && isExistingRuntimeValidOnDisk()) {
                backupDir.deleteRecursively()
            } else if (backupDir.exists() && !isExistingRuntimeValidOnDisk()) {
                rollbackFromBackupIfPresent()
            }

            // 2. If active runtime has valid files, re-verify permissions, system files, and markers
            val persisted = readPersistedState()
            if (persisted != null && activeDir.exists()) {
                val missing = validateRequiredPaths(activeDir, persisted.requiredPaths)
                if (missing.isEmpty()) {
                    ensureBaseRootfsSystemFiles(activeDir)
                    ensureBinariesExecutable(activeDir)
                    restoreMarkersFromPersisted(activeDir, persisted)
                    var health = runHealthCheckInternal(includeLiveProotProbe = true)
                    if (health.isHealthy && processBridge.isIsolationActive(activeDir)) {
                        ensureGitInstalledLocked(forceReinstall = false)
                        health = runHealthCheckInternal(includeLiveProotProbe = true)
                    }
                    refreshStateFromDisk(health)
                    return@withContext Result.success(health)
                }
            }

            if (persisted != null &&
                (persisted.runtimeId == "agt-ubuntu-upstream" || persisted.runtimeId == "agt-ubuntu-recovered")
            ) {
                return@withContext installFromUpstreamBootstrapLocked()
            }

            // 3. Otherwise, re-extract from a verified cached archive in downloads/ if available
            val verifiedArchive = persisted?.let { st ->
                downloadsDir.listFiles()?.firstOrNull {
                    it.isFile && !it.name.endsWith(".part") &&
                        downloader.computeSha256(it).equals(st.sha256, ignoreCase = true)
                }
            }
            if (persisted != null && verifiedArchive != null) {
                val staging = File(stagingDir, "repair-${System.currentTimeMillis()}")
                val format = RuntimeArchiveFormat.parse(persisted.archiveFormat) ?: return@withContext Result.failure(
                    RuntimeExtractionException(AgtError.RuntimeVerificationFailed("Unknown cached archive format."))
                )
                val extractRes = RuntimeArchiveExtractor.extractToStaging(
                    archiveFile = verifiedArchive,
                    format = format,
                    stagingRoot = staging,
                    stripRootPrefix = persisted.extractionRoot,
                )
                if (extractRes.isFailure) {
                    return@withContext Result.failure(extractRes.exceptionOrNull()!!)
                }
                val missing = validateRequiredPaths(staging, persisted.requiredPaths)
                if (missing.isNotEmpty()) {
                    staging.deleteRecursively()
                    return@withContext Result.failure(
                        RuntimeExtractionException(
                            AgtError.RuntimeVerificationFailed("Repair bundle missing: ${missing.joinToString(", ")}")
                        )
                    )
                }
                ensureBaseRootfsSystemFiles(staging)
                ensureBinariesExecutable(staging)
                restoreMarkersFromPersisted(staging, persisted)
                if (backupDir.exists()) backupDir.deleteRecursively()
                val hadActive = activeDir.exists()
                if (hadActive && !activeDir.renameTo(backupDir)) {
                    staging.deleteRecursively()
                    return@withContext Result.failure(
                        RuntimeExtractionException(AgtError.RuntimeVerificationFailed("Unable to back up active runtime before repair."))
                    )
                }
                if (!staging.renameTo(activeDir)) {
                    if (hadActive && backupDir.exists()) backupDir.renameTo(activeDir)
                    staging.deleteRecursively()
                    return@withContext Result.failure(
                        RuntimeExtractionException(AgtError.RuntimeVerificationFailed("Unable to activate repaired runtime."))
                    )
                }
                if (backupDir.exists()) backupDir.deleteRecursively()
                var health = runHealthCheckInternal(includeLiveProotProbe = true)
                if (health.isHealthy && processBridge.isIsolationActive(activeDir)) {
                    ensureGitInstalledLocked(forceReinstall = false)
                    health = runHealthCheckInternal(includeLiveProotProbe = true)
                }
                refreshStateFromDisk(health)
                return@withContext Result.success(health)
            }

            val msg = "No valid cached runtime archive found. Tap Install or Update to reinstall the Linux runtime."
            refreshStateFromDisk(
                RuntimeHealthReport(
                    isHealthy = false,
                    checkedAtEpochMs = System.currentTimeMillis(),
                    summary = msg,
                    error = AgtError.RuntimeNotInstalled(msg),
                )
            )
            Result.failure(RuntimeExtractionException(AgtError.RuntimeNotInstalled(msg)))
        }
    }

    suspend fun removeRuntime(): Unit = installMutex.withLock {
        withContext(Dispatchers.IO) {
            activeDir.deleteRecursively()
            stagingDir.deleteRecursively()
            backupDir.deleteRecursively()
            downloadsDir.deleteRecursively()
            stateDir.deleteRecursively()
            refreshStateFromDisk(overrideHealth = null, checkedEnvironment = false, resetCheckedFlag = true)
        }
    }

    fun calculateRuntimeDiskBytes(): Long = directorySize(runtimeRootDir)

    private fun activateStagingAtomically(
        staging: File,
        manifest: RuntimeManifest,
        bundleSpec: com.example.domain.model.RuntimeBundleSpec,
        abi: String,
        sha256: String,
        verifiedComponents: List<RuntimeComponentSpec>,
    ): Result<Unit> {
        if (backupDir.exists()) {
            backupDir.deleteRecursively()
        }
        val hadActive = activeDir.exists()
        if (hadActive) {
            if (!activeDir.renameTo(backupDir)) {
                return Result.failure(
                    RuntimeExtractionException(AgtError.RuntimeVerificationFailed("Unable to back up active runtime before activation."))
                )
            }
        }
        if (!staging.renameTo(activeDir)) {
            if (hadActive && backupDir.exists()) {
                backupDir.renameTo(activeDir)
            }
            return Result.failure(
                RuntimeExtractionException(AgtError.RuntimeVerificationFailed("Unable to move staged runtime into active directory."))
            )
        }
        val componentReqMap = verifiedComponents
            .filter { it.requiredPaths.isNotEmpty() }
            .associate { it.id to it.requiredPaths }
        val componentVersionMap = verifiedComponents.associate { it.id to it.version }
        val combinedRequired = (bundleSpec.requiredPaths + verifiedComponents.flatMap { it.requiredPaths }).distinct()

        writePersistedState(
            PersistedRuntimeState(
                runtimeId = manifest.runtimeId,
                version = manifest.version,
                releaseChannel = manifest.releaseChannel,
                abi = abi,
                supportedAbis = manifest.supportedAbis,
                sha256 = sha256,
                archiveFormat = bundleSpec.archiveFormat.extensionHint,
                extractionRoot = bundleSpec.extractionRoot,
                requiredPaths = combinedRequired,
                installedAtEpochMs = System.currentTimeMillis(),
                licenses = manifest.licenseNotices.map {
                    listOf(it.component, it.license, it.spdxId, it.summary)
                },
                componentIds = verifiedComponents.map { it.id }.distinct(),
                componentRequiredPaths = componentReqMap,
                componentVersions = componentVersionMap,
            )
        )
        return Result.success(Unit)
    }

    private fun rollbackFromBackupIfPresent() {
        if (backupDir.exists()) {
            if (activeDir.exists()) {
                activeDir.deleteRecursively()
            }
            backupDir.renameTo(activeDir)
        }
    }

    private fun runHealthCheckInternal(includeLiveProotProbe: Boolean = false): RuntimeHealthReport {
        val now = System.currentTimeMillis()
        val persisted = readPersistedState()
        if (persisted == null || !activeDir.exists()) {
            return RuntimeHealthReport(
                isHealthy = false,
                checkedAtEpochMs = now,
                summary = "Runtime not installed.",
                error = AgtError.RuntimeNotInstalled(),
            )
        }
        val deviceInfo = DeviceEnvironment.inspect(appContext)
        val abiOk = deviceInfo.supportedAbis.any { dev ->
            persisted.supportedAbis.any { allowed -> allowed.equals(dev, ignoreCase = true) }
        }
        val verified = mutableListOf<String>()
        val missing = mutableListOf<String>()
        val allPaths = (persisted.requiredPaths + persisted.componentRequiredPaths.values.flatten()).distinct()
        for (rel in allPaths) {
            val resolved = ProjectPathValidator.resolveWithinRoot(activeDir, rel).getOrNull()
            if (resolved != null && resolved.exists()) {
                verified += rel
            } else {
                missing += rel
            }
        }
        val markerDir = File(activeDir, ".agt-markers")
        val missingMarkers = persisted.componentIds.filter { compId ->
            !File(markerDir, "$compId.json").exists()
        }
        val markersPresent = markerDir.exists() && markerDir.isDirectory && missingMarkers.isEmpty()

        val baseFilesOk = missing.isEmpty() && markersPresent && abiOk
        if (!baseFilesOk) {
            val summary = when {
                !abiOk -> "ABI check failed for device ${deviceInfo.primaryAbi}. Expected: ${persisted.supportedAbis.joinToString(", ")}"
                missing.isNotEmpty() -> "Missing runtime files: ${missing.joinToString(", ")}"
                else -> "Runtime metadata markers missing (${missingMarkers.joinToString(", ").ifEmpty { "markers" }}). Run Repair."
            }
            return RuntimeHealthReport(
                isHealthy = false,
                checkedAtEpochMs = now,
                verifiedPaths = verified,
                missingPaths = missing,
                summary = summary,
                error = AgtError.RuntimeVerificationFailed(summary),
            )
        }

        if (includeLiveProotProbe && processBridge.isIsolationActive(activeDir)) {
            val probe = runLiveProotSmokeProbe()
            if (!probe.passed) {
                val summary = "PRoot execution check failed: ${probe.detail}"
                return RuntimeHealthReport(
                    isHealthy = false,
                    checkedAtEpochMs = now,
                    verifiedPaths = verified,
                    missingPaths = missing,
                    summary = summary,
                    error = AgtError.RuntimeVerificationFailed(summary),
                )
            }
            return RuntimeHealthReport(
                isHealthy = true,
                checkedAtEpochMs = now,
                verifiedPaths = verified,
                missingPaths = missing,
                summary = "Healthy · v${persisted.version} · ${probe.detail}",
                error = null,
            )
        }

        return RuntimeHealthReport(
            isHealthy = true,
            checkedAtEpochMs = now,
            verifiedPaths = verified,
            missingPaths = missing,
            summary = "Healthy · v${persisted.version} (${verified.size} required file(s) verified)",
            error = null,
        )
    }

    private data class ProotProbeResult(
        val passed: Boolean,
        val detail: String,
    )

    private data class InternalCommandOutput(
        val exitCode: Int?,
        val stdout: String,
        val stderr: String,
        val timedOut: Boolean = false,
        val spawnError: Throwable? = null,
    ) {
        val combined: String
            get() = listOf(stdout.trim(), stderr.trim()).filter { it.isNotEmpty() }.joinToString("\n")
    }

    private fun runLiveProotSmokeProbe(): ProotProbeResult {
        ensureBaseRootfsSystemFiles(activeDir)
        ensureBinariesExecutable(activeDir)
        val hasNode = File(activeDir, "usr/bin/node").exists() && File(activeDir, "usr/bin/npm").exists()
        val probeScript = buildString {
            append("echo __AGT_SH_OK__")
            if (hasNode) {
                append(" && printf '__AGT_NODE__=' && node --version && printf '__AGT_NPM__=' && npm --version")
            }
            append(" && if command -v git >/dev/null 2>&1; then printf '__AGT_GIT__=' && git --version; fi")
        }
        val out = runInternalProotCommand(probeScript, timeoutSeconds = 15L)
        if (out.spawnError != null) {
            return ProotProbeResult(
                passed = false,
                detail = out.spawnError.message ?: "Unable to spawn PRoot process.",
            )
        }
        if (out.timedOut) {
            return ProotProbeResult(passed = false, detail = "PRoot smoke check timed out after 15s.")
        }
        if (out.exitCode != 0 || !out.stdout.contains("__AGT_SH_OK__")) {
            return ProotProbeResult(
                passed = false,
                detail = out.combined.ifBlank { "exit code ${out.exitCode ?: -1}" }.take(280),
            )
        }
        val lines = out.stdout.lineSequence().map { it.trim() }.toList()
        val nodeVer = lines.firstOrNull { it.startsWith("__AGT_NODE__=") }?.removePrefix("__AGT_NODE__=")?.trim()
        val gitVer = lines.firstOrNull { it.startsWith("__AGT_GIT__=") }?.removePrefix("__AGT_GIT__=")
            ?.removePrefix("git version ")?.trim()
        val parts = buildList {
            add(if (processBridge.isSeccompDisabled()) "PRoot live (no-seccomp)" else "PRoot live")
            if (!nodeVer.isNullOrBlank()) add("Node $nodeVer")
            if (!gitVer.isNullOrBlank()) add("Git $gitVer") else add("Git pending")
        }
        return ProotProbeResult(passed = true, detail = parts.joinToString(" · "))
    }

    private fun ensureGitInstalledLocked(forceReinstall: Boolean): Result<String> {
        if (!forceReinstall) {
            val check = runInternalProotCommand("git --version", timeoutSeconds = 15L)
            val existingVer = check.stdout.lineSequence().firstOrNull { it.contains("git version", ignoreCase = true) }?.trim()
            if (check.spawnError == null && check.exitCode == 0 && !existingVer.isNullOrBlank()) {
                writeGitCliMarker(existingVer)
                return Result.success(existingVer)
            }
        }
        val hasApt = File(activeDir, "usr/bin/apt-get").exists() || File(activeDir, "bin/apt-get").exists()
        if (!hasApt) {
            return Result.failure(IllegalStateException("apt-get is unavailable inside this Linux rootfs."))
        }
        val installScript = """
            export DEBIAN_FRONTEND=noninteractive
            mkdir -p /var/lib/apt/lists/partial /var/cache/apt/archives/partial /var/log/apt /tmp
            apt-get update -qq -o APT::Sandbox::User=root && \
            apt-get install -y -o APT::Sandbox::User=root --no-install-recommends git ca-certificates && \
            git --version
        """.trimIndent()
        val installOut = runInternalProotCommand(installScript, timeoutSeconds = 180L)
        if (installOut.spawnError != null) {
            return Result.failure(installOut.spawnError)
        }
        if (installOut.timedOut) {
            return Result.failure(IllegalStateException("Timed out installing git inside Ubuntu rootfs."))
        }
        if (installOut.exitCode != 0) {
            return Result.failure(
                IllegalStateException(
                    installOut.combined.ifBlank { "apt-get install git failed (exit ${installOut.exitCode})" }.take(320)
                )
            )
        }
        ensureBinariesExecutable(activeDir)
        val versionLine = installOut.stdout.lineSequence()
            .lastOrNull { it.contains("git version", ignoreCase = true) }
            ?.trim()
            ?: "git installed"
        writeGitCliMarker(versionLine)
        return Result.success(versionLine)
    }

    private fun writeGitCliMarker(version: String) {
        runCatching {
            val markerDir = File(activeDir, ".agt-markers").apply { mkdirs() }
            val payload = JSONObject()
                .put("id", "git_cli")
                .put("name", "Git CLI")
                .put("version", version)
                .put("verified_at_ms", System.currentTimeMillis())
            File(markerDir, "git_cli.json").writeText(payload.toString(2))
        }
    }

    private fun runInternalProotCommand(
        shellCommand: String,
        timeoutSeconds: Long,
    ): InternalCommandOutput {
        val first = runInternalProotCommandOnce(
            shellCommand = shellCommand,
            timeoutSeconds = timeoutSeconds,
            disableSeccomp = processBridge.isSeccompDisabled(),
        )
        if (first.spawnError == null &&
            !first.timedOut &&
            first.exitCode != 0 &&
            processBridge.isIsolationActive(activeDir) &&
            !processBridge.isSeccompDisabled() &&
            AndroidProcessBridge.looksLikeSeccompFailure(first.exitCode, first.combined)
        ) {
            enableProotSeccompFallback()
            return runInternalProotCommandOnce(
                shellCommand = shellCommand,
                timeoutSeconds = timeoutSeconds,
                disableSeccomp = true,
            )
        }
        return first
    }

    private fun runInternalProotCommandOnce(
        shellCommand: String,
        timeoutSeconds: Long,
        disableSeccomp: Boolean,
    ): InternalCommandOutput {
        val argv = processBridge.buildCommand(
            runtimeActiveDir = activeDir,
            projectWorkingDir = activeDir,
            shellCommand = shellCommand,
        )
        val env = buildInternalProotEnv(disableSeccomp)
        val proc = try {
            processBridge.spawn(argv, activeDir, env)
        } catch (t: Throwable) {
            return InternalCommandOutput(exitCode = null, stdout = "", stderr = "", spawnError = t)
        }
        val stdoutFuture = CompletableFuture.supplyAsync {
            readBoundedStream(proc.stdout)
        }
        val stderrFuture = CompletableFuture.supplyAsync {
            readBoundedStream(proc.stderr)
        }
        return try {
            val code = CompletableFuture.supplyAsync { proc.waitFor() }
                .get(timeoutSeconds.coerceIn(2L, 300L), TimeUnit.SECONDS)
            val out = runCatching { stdoutFuture.get(2, TimeUnit.SECONDS) }.getOrDefault("")
            val err = runCatching { stderrFuture.get(2, TimeUnit.SECONDS) }.getOrDefault("")
            InternalCommandOutput(exitCode = code, stdout = out, stderr = err)
        } catch (_: java.util.concurrent.TimeoutException) {
            proc.terminateTree()
            val out = runCatching { stdoutFuture.get(1, TimeUnit.SECONDS) }.getOrDefault("")
            val err = runCatching { stderrFuture.get(1, TimeUnit.SECONDS) }.getOrDefault("")
            InternalCommandOutput(exitCode = null, stdout = out, stderr = err, timedOut = true)
        } catch (t: Throwable) {
            proc.terminateTree()
            InternalCommandOutput(exitCode = null, stdout = "", stderr = "", spawnError = t)
        }
    }

    private fun buildInternalProotEnv(disableSeccomp: Boolean): Map<String, String> {
        val runtimeTmp = File(activeDir, "tmp").apply { mkdirs() }
        val caCert = File(activeDir, "etc/ssl/certs/ca-certificates.crt")
        return if (processBridge.isIsolationActive(activeDir)) {
            File(activeDir, "root").mkdirs()
            linkedMapOf(
                "TERM" to "xterm-256color",
                "LANG" to "C.UTF-8",
                "HOME" to "/root",
                "PWD" to "/",
                "PATH" to "/usr/local/sbin:/usr/local/bin:/usr/sbin:/usr/bin:/sbin:/bin",
                "TMPDIR" to "/tmp",
                "AGT_RUNTIME_ROOT" to "/",
                "AGT_IS_PROOT" to "1",
            ).apply {
                if (caCert.exists()) {
                    put("SSL_CERT_FILE", "/etc/ssl/certs/ca-certificates.crt")
                }
                putAll(processBridge.buildProotEnvironment(activeDir, disableSeccomp = disableSeccomp))
            }
        } else {
            val runtimePath = listOf(
                File(activeDir, "usr/local/bin").absolutePath,
                File(activeDir, "usr/bin").absolutePath,
                File(activeDir, "bin").absolutePath,
                "/usr/local/bin",
                "/usr/bin",
                "/bin",
                "/system/bin",
            ).joinToString(":")
            linkedMapOf(
                "TERM" to "xterm-256color",
                "LANG" to "C.UTF-8",
                "HOME" to activeDir.absolutePath,
                "PWD" to activeDir.absolutePath,
                "PATH" to runtimePath,
                "TMPDIR" to runtimeTmp.absolutePath,
                "AGT_RUNTIME_ROOT" to activeDir.absolutePath,
            ).apply {
                if (caCert.exists()) {
                    put("SSL_CERT_FILE", caCert.absolutePath)
                }
            }
        }
    }

    private fun readBoundedStream(stream: java.io.InputStream, maxBytes: Int = 65_536): String {
        return runCatching {
            val buffer = ByteArray(4096)
            val out = java.io.ByteArrayOutputStream()
            var total = 0
            while (true) {
                val read = stream.read(buffer)
                if (read <= 0) break
                val remaining = maxBytes - total
                if (remaining > 0) {
                    val toWrite = minOf(read, remaining)
                    out.write(buffer, 0, toWrite)
                    total += toWrite
                }
            }
            out.toString(StandardCharsets.UTF_8.name())
        }.getOrDefault("")
    }

    private fun validateAndResolveComponents(
        stagingRoot: File,
        manifest: RuntimeManifest,
        bundleRequiredPaths: List<String>,
    ): Result<List<RuntimeComponentSpec>> {
        val byId = manifest.components.associateBy { it.id }
        val baseIds = listOf("linux_runtime", "base_toolchains")
        val resolvedSpecs = buildList {
            for (baseId in baseIds) {
                val explicit = byId[baseId]
                if (explicit != null) {
                    add(explicit)
                } else {
                    val defaults = defaultSetupComponents().first { it.id == baseId }
                    add(
                        RuntimeComponentSpec(
                            id = defaults.id,
                            name = defaults.name,
                            description = defaults.description,
                            version = manifest.version,
                            requiredPaths = bundleRequiredPaths,
                            dependencies = emptyList(),
                        )
                    )
                }
            }
            for (comp in manifest.components) {
                if (comp.id !in baseIds) {
                    add(comp)
                }
            }
        }

        val ordered = orderComponentsByDependencies(resolvedSpecs)
            .getOrElse { return Result.failure(it) }

        val verified = mutableListOf<RuntimeComponentSpec>()
        val verifiedIds = mutableSetOf<String>()
        for (comp in ordered) {
            updateStage(
                stage = RuntimeInstallStage.VALIDATING,
                detail = "Verifying component ${comp.name} (${comp.id})…",
                componentId = comp.id,
                componentStatus = ComponentInstallStatus.VERIFYING,
            )
            val missingDeps = comp.dependencies.filter { it !in verifiedIds }
            if (missingDeps.isNotEmpty()) {
                val msg = "Component '${comp.name}' (${comp.id}) has unsatisfied dependencies: ${missingDeps.joinToString(", ")}"
                updateStage(
                    stage = RuntimeInstallStage.FAILED,
                    detail = msg,
                    componentId = comp.id,
                    componentStatus = ComponentInstallStatus.FAILED,
                )
                return Result.failure(RuntimeExtractionException(AgtError.RuntimeVerificationFailed(msg)))
            }
            if (comp.requiredPaths.isNotEmpty()) {
                val missingPaths = validateRequiredPaths(stagingRoot, comp.requiredPaths)
                if (missingPaths.isNotEmpty()) {
                    val msg = "Component '${comp.name}' (${comp.id}) is missing required files: ${missingPaths.joinToString(", ")}"
                    updateStage(
                        stage = RuntimeInstallStage.FAILED,
                        detail = msg,
                        componentId = comp.id,
                        componentStatus = ComponentInstallStatus.FAILED,
                    )
                    return Result.failure(RuntimeExtractionException(AgtError.RuntimeVerificationFailed(msg)))
                }
            }
            verified += comp
            verifiedIds += comp.id
        }
        return Result.success(verified)
    }

    private fun orderComponentsByDependencies(
        components: List<RuntimeComponentSpec>,
    ): Result<List<RuntimeComponentSpec>> {
        val byId = components.associateBy { it.id }
        val visitState = mutableMapOf<String, Int>()
        val ordered = mutableListOf<RuntimeComponentSpec>()

        fun visit(comp: RuntimeComponentSpec) {
            when (visitState[comp.id] ?: 0) {
                1 -> throw RuntimeExtractionException(
                    AgtError.RuntimeManifestInvalid("Circular component dependency detected at '${comp.id}'.")
                )
                2 -> return
            }
            visitState[comp.id] = 1
            for (depId in comp.dependencies) {
                val dep = byId[depId] ?: throw RuntimeExtractionException(
                    AgtError.RuntimeManifestInvalid(
                        "Component '${comp.id}' requires missing dependency '$depId'."
                    )
                )
                visit(dep)
            }
            visitState[comp.id] = 2
            ordered += comp
        }

        return runCatching {
            components.forEach { visit(it) }
            ordered.toList()
        }
    }

    private fun validateRequiredPaths(root: File, requiredPaths: List<String>): List<String> {
        val missing = mutableListOf<String>()
        for (rel in requiredPaths) {
            val file = ProjectPathValidator.resolveWithinRoot(root, rel).getOrNull()
            if (file == null || !file.exists()) {
                missing += rel
            }
        }
        return missing
    }

    private fun ensureBinariesExecutable(root: File) {
        listOf("bin", "sbin", "usr/bin", "usr/sbin", "usr/local/bin").forEach { sub ->
            val dir = File(root, sub)
            if (dir.exists() && dir.isDirectory) {
                dir.listFiles()?.forEach { child ->
                    if (child.isFile) child.setExecutable(true, false)
                }
            }
        }
    }

    private fun ensureBaseRootfsSystemFiles(root: File) {
        File(root, "tmp").mkdirs()
        File(root, "root").mkdirs()
        File(root, "dev/shm").mkdirs()
        File(root, "var/lib/apt/lists/partial").mkdirs()
        File(root, "var/cache/apt/archives/partial").mkdirs()
        File(root, "var/log/apt").mkdirs()

        val resolvFile = File(root, "etc/resolv.conf")
        resolvFile.parentFile?.mkdirs()
        if (!resolvFile.exists() || resolvFile.length() == 0L) {
            resolvFile.writeText("nameserver 1.1.1.1\nnameserver 8.8.8.8\n")
        }

        val hostsFile = File(root, "etc/hosts")
        hostsFile.parentFile?.mkdirs()
        if (!hostsFile.exists() || hostsFile.length() == 0L) {
            hostsFile.writeText("127.0.0.1 localhost\n::1 localhost ip6-localhost ip6-loopback\n")
        }

        ensureCaCertificatesBundle(root)
    }

    private fun ensureCaCertificatesBundle(root: File) {
        val targetCert = File(root, "etc/ssl/certs/ca-certificates.crt")
        if (targetCert.exists() && targetCert.length() > 0L) return

        val systemCertDirs = listOf(
            File("/apex/com.android.conscrypt/cacerts"),
            File("/system/etc/security/cacerts"),
        ).filter { it.exists() && it.isDirectory }
        if (systemCertDirs.isEmpty()) return

        runCatching {
            val pemRegex = Regex("-----BEGIN CERTIFICATE-----[\\s\\S]+?-----END CERTIFICATE-----")
            val seenBlocks = LinkedHashSet<String>()
            systemCertDirs.forEach { dir ->
                dir.listFiles()?.forEach { certFile ->
                    if (certFile.isFile && certFile.length() in 1L..262_144L) {
                        val content = runCatching { certFile.readText() }.getOrNull().orEmpty()
                        pemRegex.findAll(content).forEach { m ->
                            seenBlocks += m.value.trim()
                        }
                    }
                }
            }
            if (seenBlocks.isNotEmpty()) {
                targetCert.parentFile?.mkdirs()
                targetCert.writeText(seenBlocks.joinToString("\n") + "\n")
            }
        }
    }

    private fun writeComponentMarkers(
        root: File,
        manifest: RuntimeManifest,
        verifiedComponents: List<RuntimeComponentSpec>,
        sha256: String,
    ) {
        val markerDir = File(root, ".agt-markers").apply { mkdirs() }
        val nowMs = System.currentTimeMillis()
        verifiedComponents.forEach { comp ->
            val obj = JSONObject()
                .put("id", comp.id)
                .put("name", comp.name)
                .put("version", comp.version)
                .put("runtime_id", manifest.runtimeId)
                .put("runtime_version", manifest.version)
                .put("sha256", sha256)
                .put("required_paths", JSONArray(comp.requiredPaths))
                .put("dependencies", JSONArray(comp.dependencies))
                .put("verified_at_ms", nowMs)
            File(markerDir, "${comp.id}.json").writeText(obj.toString(2))
        }
    }

    private fun restoreMarkersFromPersisted(root: File, persisted: PersistedRuntimeState) {
        val markerDir = File(root, ".agt-markers").apply { mkdirs() }
        val nowMs = System.currentTimeMillis()
        persisted.componentIds.ifEmpty { listOf("linux_runtime", "base_toolchains") }.forEach { compId ->
            val payload = JSONObject()
                .put("id", compId)
                .put("runtime_id", persisted.runtimeId)
                .put("version", persisted.componentVersions[compId] ?: persisted.version)
                .put("runtime_version", persisted.version)
                .put("sha256", persisted.sha256)
                .put("required_paths", JSONArray(persisted.componentRequiredPaths[compId].orEmpty()))
                .put("repaired_at_ms", nowMs)
            File(markerDir, "$compId.json").writeText(payload.toString(2))
        }
    }

    private fun recoverAndLoadStateSync() {
        if (prootNoSeccompMarker.exists()) {
            processBridge.setSeccompDisabled(true)
        }
        // Recover interrupted atomic swap or clean aborted staging without harming valid runtime or projects.
        if (stagingDir.exists()) {
            stagingDir.deleteRecursively()
        }
        if (backupDir.exists()) {
            if (isExistingRuntimeValidOnDisk()) {
                backupDir.deleteRecursively()
            } else {
                rollbackFromBackupIfPresent()
            }
        }
        val health = if (isExistingRuntimeValidOnDisk()) runHealthCheckInternal(includeLiveProotProbe = false) else null
        refreshStateFromDisk(health)
    }

    private fun isExistingRuntimeValidOnDisk(): Boolean {
        val persisted = readPersistedState() ?: return false
        if (!activeDir.exists()) return false
        val allPaths = (persisted.requiredPaths + persisted.componentRequiredPaths.values.flatten()).distinct()
        return validateRequiredPaths(activeDir, allPaths).isEmpty()
    }

    private suspend fun verifyExistingEnvironmentBeforeDownload(): RuntimeHealthReport? {
        _installState.update { state ->
            state.copy(
                stage = RuntimeInstallStage.VERIFYING_ENVIRONMENT,
                isInstalling = true,
                hasCheckedEnvironment = true,
                progressPercent = null,
                errorMessage = null,
                statusDetail = "Checking if Linux environment already exists on device…",
            )
        }

        val initialHealth = runHealthCheckInternal(includeLiveProotProbe = true)
        if (initialHealth.isHealthy) {
            ensureBaseRootfsSystemFiles(activeDir)
            ensureBinariesExecutable(activeDir)
            preferencesRepository.setOnboardingCompleted(true)
            refreshStateFromDisk(initialHealth, checkedEnvironment = true)
            return initialHealth
        }

        if (tryRecoverUnpersistedRootfsOnDisk()) {
            val recoveredHealth = runHealthCheckInternal(includeLiveProotProbe = true)
            if (recoveredHealth.isHealthy) {
                preferencesRepository.setOnboardingCompleted(true)
                refreshStateFromDisk(recoveredHealth, checkedEnvironment = true)
                return recoveredHealth
            }
        }

        // Not installed or incomplete: surface detected vs missing components before downloading.
        val persisted = readPersistedState()
        val reconciled = reconcileComponentsWithDisk(
            base = defaultSetupComponents(),
            installedIds = persisted?.componentIds.orEmpty(),
            version = persisted?.version,
            componentVersions = persisted?.componentVersions.orEmpty(),
            componentRequiredPaths = persisted?.componentRequiredPaths.orEmpty(),
        )
        _installState.update { state ->
            state.copy(
                hasCheckedEnvironment = true,
                components = reconciled,
                statusDetail = "Environment incomplete or not installed. Preparing download…",
            )
        }
        return null
    }

    private fun tryRecoverUnpersistedRootfsOnDisk(): Boolean {
        if (!activeDir.exists()) return false
        val hasShell = listOf("bin/sh", "usr/bin/sh", "bin/bash").any { File(activeDir, it).exists() }
        val hasOsRelease = File(activeDir, "etc/os-release").exists()
        if (!hasShell || !hasOsRelease) return false

        ensureBaseRootfsSystemFiles(activeDir)
        ensureBinariesExecutable(activeDir)

        val deviceInfo = DeviceEnvironment.inspect(appContext)
        val primaryAbi = deviceInfo.supportedAbis.firstOrNull {
            it.equals("arm64-v8a", ignoreCase = true) || it.equals("x86_64", ignoreCase = true)
        }?.lowercase() ?: deviceInfo.primaryAbi

        val specs = mutableListOf<RuntimeComponentSpec>()
        specs += RuntimeComponentSpec(
            id = "linux_runtime",
            name = "Linux Runtime (PRoot / Ubuntu)",
            description = "Recovered on-device Linux rootfs",
            version = "24.04",
            requiredPaths = listOf("bin/sh", "etc/os-release"),
        )
        if (File(activeDir, "usr/bin/node").exists() && File(activeDir, "usr/bin/npm").exists()) {
            specs += RuntimeComponentSpec(
                id = "base_toolchains",
                name = "Base Toolchains",
                description = "Node.js toolchain",
                version = "22.x",
                requiredPaths = listOf("usr/bin/node", "usr/bin/npm"),
                dependencies = listOf("linux_runtime"),
            )
        }
        if (File(activeDir, "usr/bin/gh").exists()) {
            specs += RuntimeComponentSpec(
                id = "github_cli",
                name = "GitHub CLI (gh)",
                description = "GitHub CLI",
                version = "detected",
                requiredPaths = listOf("usr/bin/gh"),
                dependencies = listOf("linux_runtime"),
            )
        }
        if (listOf("usr/local/bin/opencode", "usr/bin/opencode", "bin/opencode").any { File(activeDir, it).exists() }) {
            val foundRel = listOf("usr/local/bin/opencode", "usr/bin/opencode", "bin/opencode")
                .first { File(activeDir, it).exists() }
            specs += RuntimeComponentSpec(
                id = "opencode_agent",
                name = "OpenCode Agent Runtime",
                description = "OpenCode CLI",
                version = "detected",
                requiredPaths = listOf(foundRel),
                dependencies = listOf("linux_runtime"),
            )
        }
        if (File(activeDir, "usr/local/share/agt/skills/registry.json").exists()) {
            specs += RuntimeComponentSpec(
                id = "base_skills_mcp",
                name = "Base Skills & MCP Registry",
                description = "Repository inspection skills and HTTP MCP registry.",
                version = "1.0.0",
                requiredPaths = listOf("usr/local/share/agt/skills/registry.json"),
                dependencies = listOf("linux_runtime"),
            )
        }

        val combinedReq = specs.flatMap { it.requiredPaths }.distinct()
        val syntheticManifest = RuntimeManifest(
            runtimeId = "agt-ubuntu-recovered",
            version = "24.04",
            releaseChannel = "stable",
            supportedAbis = listOf("arm64-v8a", "x86_64"),
            minAndroidSdk = RuntimeCompatibilityChecker.DEFAULT_MIN_SDK,
            minRequiredFreeBytes = RuntimeCompatibilityChecker.DEFAULT_MIN_FREE_BYTES,
            bundle = com.example.domain.model.RuntimeBundleSpec(
                downloadUrl = "https://cdimage.ubuntu.com/ubuntu-base/releases/24.04/release",
                compressedSizeBytes = 0L,
                sha256 = "0".repeat(64),
                archiveFormat = RuntimeArchiveFormat.TAR_GZ,
                extractionRoot = "",
                requiredPaths = combinedReq,
            ),
            components = specs,
            licenseNotices = defaultUpstreamLicenses(),
        )
        writeComponentMarkers(activeDir, syntheticManifest, specs, "0".repeat(64))
        writePersistedState(
            PersistedRuntimeState(
                runtimeId = syntheticManifest.runtimeId,
                version = syntheticManifest.version,
                releaseChannel = syntheticManifest.releaseChannel,
                abi = primaryAbi,
                supportedAbis = syntheticManifest.supportedAbis,
                sha256 = "0".repeat(64),
                archiveFormat = "tar.gz",
                extractionRoot = "",
                requiredPaths = combinedReq,
                installedAtEpochMs = System.currentTimeMillis(),
                licenses = syntheticManifest.licenseNotices.map {
                    listOf(it.component, it.license, it.spdxId, it.summary)
                },
                componentIds = specs.map { it.id }.distinct(),
                componentRequiredPaths = specs.associate { it.id to it.requiredPaths },
                componentVersions = specs.associate { it.id to it.version },
            )
        )
        return true
    }

    private fun isVerificationFailure(err: Throwable?): Boolean {
        if (err == null) return false
        val downloadErr = err as? RuntimeDownloadException
        if (downloadErr?.agtError is AgtError.RuntimeVerificationFailed) return true
        val manifestErr = err as? RuntimeManifestException
        if (manifestErr?.agtError is AgtError.RuntimeVerificationFailed) return true
        return err.message?.contains("SHA-256", ignoreCase = true) == true
    }

    private fun refreshStateFromDisk(
        overrideHealth: RuntimeHealthReport?,
        checkedEnvironment: Boolean = false,
        resetCheckedFlag: Boolean = false,
    ) {
        val persisted = readPersistedState()
        val hasPart = downloadsDir.listFiles()?.any { it.name.endsWith(".part") } == true
        val diskBytes = calculateRuntimeDiskBytes()
        val health = overrideHealth ?: if (persisted != null) runHealthCheckInternal() else null
        val isInstalled = persisted != null && health?.isHealthy == true
        val hasChecked = if (resetCheckedFlag) {
            false
        } else {
            checkedEnvironment || isInstalled || hasPart || _installState.value.hasCheckedEnvironment
        }

        val components = reconcileComponentsWithDisk(
            base = defaultSetupComponents(),
            installedIds = if (isInstalled) persisted.componentIds else emptyList(),
            version = persisted?.version,
            componentVersions = persisted?.componentVersions.orEmpty(),
            componentRequiredPaths = persisted?.componentRequiredPaths.orEmpty(),
        )

        val notices = persisted?.licenses?.mapNotNull { parts ->
            if (parts.size >= 4) {
                com.example.domain.model.RuntimeLicenseNotice(
                    component = parts[0],
                    license = parts[1],
                    spdxId = parts[2],
                    summary = parts[3],
                )
            } else {
                null
            }
        }?.ifEmpty { defaultRuntimeLicenses() } ?: defaultRuntimeLicenses()

        _installState.value = RuntimeInstallState(
            stage = if (isInstalled) RuntimeInstallStage.INSTALLED else RuntimeInstallStage.IDLE,
            isInstalling = false,
            isInstalled = isInstalled,
            hasCheckedEnvironment = hasChecked,
            installedRuntimeId = if (isInstalled) persisted.runtimeId else null,
            installedVersion = if (isInstalled) persisted.version else null,
            installedAbi = if (isInstalled) persisted.abi else null,
            installedChannel = if (isInstalled) persisted.releaseChannel else null,
            installedAtEpochMs = if (isInstalled) persisted.installedAtEpochMs else null,
            lastHealthCheckEpochMs = health?.checkedAtEpochMs,
            healthCheckPassed = health?.isHealthy == true,
            healthMessage = health?.summary ?: "Runtime not installed",
            progressPercent = if (isInstalled) 100 else null,
            runtimeDiskBytes = diskBytes,
            canResumeDownload = hasPart,
            statusDetail = when {
                isInstalled -> "Linux runtime v${persisted.version} active and verified."
                hasPart -> "Partial download found. Tap Install Runtime to resume."
                else -> "Tap Install Runtime to verify existing environment or download Linux packages."
            },
            errorMessage = if (health != null && !health.isHealthy && persisted != null) health.summary else null,
            components = components,
            licenseNotices = notices,
        )
    }

    private fun reconcileComponentsWithDisk(
        base: List<SetupComponentItem>,
        installedIds: List<String> = emptyList(),
        version: String? = null,
        componentVersions: Map<String, String> = emptyMap(),
        componentRequiredPaths: Map<String, List<String>> = emptyMap(),
    ): List<SetupComponentItem> {
        val markerDir = File(activeDir, ".agt-markers")
        return base.map { item ->
            val markerExists = File(markerDir, "${item.id}.json").exists()
            val reqPaths = componentRequiredPaths[item.id].orEmpty()
            val filesVerified = reqPaths.isEmpty() || validateRequiredPaths(activeDir, reqPaths).isEmpty()
            val installed = item.id in installedIds && markerExists && filesVerified
            if (installed) {
                item.copy(
                    status = ComponentInstallStatus.COMPLETED,
                    progressPercent = 100,
                    version = componentVersions[item.id] ?: version,
                )
            } else {
                item.copy(
                    status = ComponentInstallStatus.NOT_INSTALLED,
                    progressPercent = null,
                    version = null,
                )
            }
        }
    }

    private fun updateStage(
        stage: RuntimeInstallStage,
        detail: String,
        componentId: String? = null,
        componentStatus: ComponentInstallStatus? = null,
        progressPercent: Int? = null,
    ) {
        _installState.update { state ->
            state.copy(
                stage = stage,
                isInstalling = true,
                hasCheckedEnvironment = true,
                statusDetail = detail,
                progressPercent = progressPercent,
                errorMessage = null,
                components = if (componentId != null && componentStatus != null) {
                    updateComponentStatus(state.components, componentId, componentStatus, progressPercent)
                } else {
                    state.components
                },
            )
        }
    }

    private fun updateComponentStatus(
        items: List<SetupComponentItem>,
        targetId: String,
        status: ComponentInstallStatus,
        progressPercent: Int?,
    ): List<SetupComponentItem> = items.map { item ->
        if (item.id == targetId) {
            item.copy(status = status, progressPercent = progressPercent)
        } else {
            item
        }
    }

    private fun failInstall(message: String, cause: Throwable? = null): Result<RuntimeHealthReport> {
        val hasPart = downloadsDir.listFiles()?.any { it.name.endsWith(".part") } == true
        val existingHealthy = isExistingRuntimeValidOnDisk()
        _installState.update { state ->
            state.copy(
                stage = RuntimeInstallStage.FAILED,
                isInstalling = false,
                isInstalled = existingHealthy,
                hasCheckedEnvironment = true,
                progressPercent = null,
                canResumeDownload = hasPart,
                statusDetail = message,
                errorMessage = message,
                components = state.components.map { item ->
                    when {
                        item.status == ComponentInstallStatus.FAILED -> item.copy(progressPercent = null)
                        item.status in setOf(
                            ComponentInstallStatus.DOWNLOADING,
                            ComponentInstallStatus.VERIFYING,
                            ComponentInstallStatus.EXTRACTING,
                            ComponentInstallStatus.INSTALLING,
                        ) -> item.copy(status = ComponentInstallStatus.FAILED, progressPercent = null)
                        item.status != ComponentInstallStatus.COMPLETED &&
                            item.id == "linux_runtime" &&
                            state.components.none { it.status == ComponentInstallStatus.FAILED } ->
                            item.copy(status = ComponentInstallStatus.FAILED, progressPercent = null)
                        else -> item
                    }
                },
            )
        }
        val err = cause ?: RuntimeExtractionException(AgtError.RuntimeVerificationFailed(message))
        return Result.failure(err)
    }

    private data class PersistedRuntimeState(
        val runtimeId: String,
        val version: String,
        val releaseChannel: String,
        val abi: String,
        val supportedAbis: List<String>,
        val sha256: String,
        val archiveFormat: String,
        val extractionRoot: String,
        val requiredPaths: List<String>,
        val installedAtEpochMs: Long,
        val licenses: List<List<String>>,
        val componentIds: List<String>,
        val componentRequiredPaths: Map<String, List<String>> = emptyMap(),
        val componentVersions: Map<String, String> = emptyMap(),
    )

    private fun writePersistedState(state: PersistedRuntimeState) {
        stateDir.mkdirs()
        val reqByCompJson = JSONObject().apply {
            state.componentRequiredPaths.forEach { (id, paths) ->
                put(id, JSONArray(paths))
            }
        }
        val verByCompJson = JSONObject().apply {
            state.componentVersions.forEach { (id, ver) ->
                put(id, ver)
            }
        }
        val obj = JSONObject()
            .put("runtime_id", state.runtimeId)
            .put("version", state.version)
            .put("release_channel", state.releaseChannel)
            .put("abi", state.abi)
            .put("supported_abis", JSONArray(state.supportedAbis))
            .put("sha256", state.sha256)
            .put("archive_format", state.archiveFormat)
            .put("extraction_root", state.extractionRoot)
            .put("required_paths", JSONArray(state.requiredPaths))
            .put("installed_at_epoch_ms", state.installedAtEpochMs)
            .put("component_ids", JSONArray(state.componentIds))
            .put("component_required_paths", reqByCompJson)
            .put("component_versions", verByCompJson)
            .put(
                "licenses",
                JSONArray().apply {
                    state.licenses.forEach { tuple -> put(JSONArray(tuple)) }
                }
            )
        val tmp = File(stateDir, "runtime_state.json.tmp")
        tmp.writeText(obj.toString(2))
        if (stateFile.exists()) stateFile.delete()
        tmp.renameTo(stateFile)
    }

    private fun readPersistedState(): PersistedRuntimeState? {
        if (!stateFile.exists() || !stateFile.isFile) return null
        return runCatching {
            val obj = JSONObject(stateFile.readText())
            val abiArr = obj.optJSONArray("supported_abis")
            val supportedAbis = buildList {
                if (abiArr != null) {
                    for (i in 0 until abiArr.length()) {
                        val v = abiArr.optString(i).trim()
                        if (v.isNotEmpty()) add(v)
                    }
                }
            }
            val reqArr = obj.optJSONArray("required_paths")
            val reqPaths = buildList {
                if (reqArr != null) {
                    for (i in 0 until reqArr.length()) {
                        val v = reqArr.optString(i).trim()
                        if (v.isNotEmpty()) add(v)
                    }
                }
            }
            val compArr = obj.optJSONArray("component_ids")
            val compIds = buildList {
                if (compArr != null) {
                    for (i in 0 until compArr.length()) {
                        val v = compArr.optString(i).trim()
                        if (v.isNotEmpty()) add(v)
                    }
                }
            }
            val compReqObj = obj.optJSONObject("component_required_paths")
            val compReqPaths = buildMap<String, List<String>> {
                if (compReqObj != null) {
                    val keys = compReqObj.keys()
                    while (keys.hasNext()) {
                        val k = keys.next()
                        val arr = compReqObj.optJSONArray(k) ?: continue
                        val list = buildList {
                            for (i in 0 until arr.length()) {
                                val p = arr.optString(i).trim()
                                if (p.isNotEmpty()) add(p)
                            }
                        }
                        put(k, list)
                    }
                }
            }
            val compVerObj = obj.optJSONObject("component_versions")
            val compVersions = buildMap<String, String> {
                if (compVerObj != null) {
                    val keys = compVerObj.keys()
                    while (keys.hasNext()) {
                        val k = keys.next()
                        val v = compVerObj.optString(k).trim()
                        if (v.isNotEmpty()) put(k, v)
                    }
                }
            }
            val licArr = obj.optJSONArray("licenses")
            val licenses = buildList {
                if (licArr != null) {
                    for (i in 0 until licArr.length()) {
                        val row = licArr.optJSONArray(i) ?: continue
                        val tuple = buildList {
                            for (j in 0 until row.length()) add(row.optString(j))
                        }
                        if (tuple.size >= 4) add(tuple)
                    }
                }
            }
            val primaryAbi = obj.optString("abi", "arm64-v8a")
            PersistedRuntimeState(
                runtimeId = obj.getString("runtime_id"),
                version = obj.getString("version"),
                releaseChannel = obj.optString("release_channel", "stable"),
                abi = primaryAbi,
                supportedAbis = supportedAbis.ifEmpty { listOf(primaryAbi) },
                sha256 = obj.getString("sha256"),
                archiveFormat = obj.optString("archive_format", "tar.gz"),
                extractionRoot = obj.optString("extraction_root", ""),
                requiredPaths = reqPaths.ifEmpty { listOf("bin/sh") },
                installedAtEpochMs = obj.optLong("installed_at_epoch_ms", 0L),
                licenses = licenses,
                componentIds = compIds,
                componentRequiredPaths = compReqPaths,
                componentVersions = compVersions,
            )
        }.getOrNull()
    }

    private fun directorySize(file: File): Long {
        if (!file.exists()) return 0L
        if (file.isFile) return file.length()
        return file.listFiles()?.sumOf { directorySize(it) } ?: 0L
    }

    private fun formatBytes(bytes: Long): String {
        val kb = bytes / 1024.0
        val mb = kb / 1024.0
        return if (mb >= 1.0) {
            String.format(java.util.Locale.US, "%.1f MB", mb)
        } else {
            String.format(java.util.Locale.US, "%.0f KB", kb)
        }
    }
}
