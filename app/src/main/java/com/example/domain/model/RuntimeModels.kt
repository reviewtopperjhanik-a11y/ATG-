package com.example.domain.model

import com.example.core.common.AgtError
import com.example.core.common.DeviceCompatibilityInfo

enum class RuntimeArchiveFormat(val extensionHint: String) {
    TAR("tar"),
    TAR_GZ("tar.gz"),
    ZIP("zip");

    companion object {
        fun parse(raw: String): RuntimeArchiveFormat? = when (raw.trim().lowercase()) {
            "tar" -> TAR
            "tar.gz", "tgz", "gzip" -> TAR_GZ
            "zip" -> ZIP
            else -> null
        }
    }
}

data class RuntimeBundleSpec(
    val downloadUrl: String,
    val compressedSizeBytes: Long,
    val sha256: String,
    val archiveFormat: RuntimeArchiveFormat,
    val extractionRoot: String = "",
    val requiredPaths: List<String> = emptyList(),
)

data class RuntimeComponentSpec(
    val id: String,
    val name: String,
    val description: String,
    val version: String,
    val requiredPaths: List<String> = emptyList(),
    val dependencies: List<String> = emptyList(),
)

data class RuntimeLicenseNotice(
    val component: String,
    val license: String,
    val spdxId: String,
    val summary: String,
)

data class RuntimeManifest(
    val runtimeId: String,
    val version: String,
    val releaseChannel: String,
    val supportedAbis: List<String>,
    val minAndroidSdk: Int,
    val minRequiredFreeBytes: Long,
    val bundle: RuntimeBundleSpec,
    val components: List<RuntimeComponentSpec>,
    val licenseNotices: List<RuntimeLicenseNotice>,
    val abiBundles: Map<String, RuntimeBundleSpec> = emptyMap(),
) {
    fun selectBundleForDevice(deviceAbis: List<String>): Pair<String, RuntimeBundleSpec>? {
        if (abiBundles.isNotEmpty()) {
            for (deviceAbi in deviceAbis) {
                val match = abiBundles.entries.firstOrNull { (abiKey, _) ->
                    abiKey.equals(deviceAbi, ignoreCase = true)
                }
                if (match != null) {
                    return deviceAbi to match.value
                }
            }
            return null
        }
        val matchedAbi = deviceAbis.firstOrNull { dev ->
            supportedAbis.any { allowed -> allowed.equals(dev, ignoreCase = true) }
        } ?: return null
        return matchedAbi to bundle
    }
}

data class RuntimeCompatibilityReport(
    val isCompatible: Boolean,
    val deviceInfo: DeviceCompatibilityInfo,
    val abiSupported: Boolean,
    val sdkSupported: Boolean,
    val storageSupported: Boolean,
    val requiredFreeBytes: Long,
    val blockingReasons: List<String>,
)

enum class RuntimeInstallStage(val label: String) {
    IDLE("Not installed"),
    VERIFYING_ENVIRONMENT("Verifying environment"),
    CHECKING_COMPATIBILITY("Checking device compatibility"),
    FETCHING_MANIFEST("Fetching runtime manifest"),
    DOWNLOADING("Downloading runtime bundle"),
    VERIFYING("Verifying SHA-256 integrity"),
    EXTRACTING("Extracting into staging"),
    VALIDATING("Validating binaries and layout"),
    ACTIVATING("Activating runtime atomically"),
    HEALTH_CHECKING("Running runtime health checks"),
    INSTALLED("Installed"),
    FAILED("Installation failed"),
    CANCELLED("Cancelled"),
}

data class RuntimeHealthReport(
    val isHealthy: Boolean,
    val checkedAtEpochMs: Long,
    val verifiedPaths: List<String> = emptyList(),
    val missingPaths: List<String> = emptyList(),
    val summary: String,
    val error: AgtError? = null,
)

data class RuntimeInstallState(
    val stage: RuntimeInstallStage = RuntimeInstallStage.IDLE,
    val isInstalling: Boolean = false,
    val isInstalled: Boolean = false,
    val hasCheckedEnvironment: Boolean = false,
    val installedRuntimeId: String? = null,
    val installedVersion: String? = null,
    val installedAbi: String? = null,
    val installedChannel: String? = null,
    val installedAtEpochMs: Long? = null,
    val lastHealthCheckEpochMs: Long? = null,
    val healthCheckPassed: Boolean = false,
    val healthMessage: String = "Runtime not installed",
    val progressPercent: Int? = null,
    val downloadedBytes: Long = 0L,
    val totalBytes: Long = 0L,
    val runtimeDiskBytes: Long = 0L,
    val canResumeDownload: Boolean = false,
    val statusDetail: String = "Tap Install Runtime to verify existing environment or download Linux packages.",
    val errorMessage: String? = null,
    val components: List<SetupComponentItem> = defaultSetupComponents(),
    val licenseNotices: List<RuntimeLicenseNotice> = defaultRuntimeLicenses(),
)

data class OpenCodeRuntimeState(
    val isInstalled: Boolean = false,
    val isInstalling: Boolean = false,
    val version: String? = null,
    val binaryPath: String? = null,
    val statusDetail: String = "OpenCode runtime is not installed in the Linux rootfs.",
    val errorMessage: String? = null,
)

data class PiRuntimeState(
    val isInstalled: Boolean = false,
    val isInstalling: Boolean = false,
    val version: String? = null,
    val binaryPath: String? = null,
    val nodeAvailable: Boolean = false,
    val statusDetail: String = "Pi Coding Agent is not installed in the Linux rootfs.",
    val errorMessage: String? = null,
)

fun defaultSetupComponents(): List<SetupComponentItem> = listOf(
    SetupComponentItem(
        id = "linux_runtime",
        name = "Linux Runtime (PRoot / Ubuntu)",
        description = "ARM64 rootfs userspace for shells, compilers, and dev servers.",
        status = ComponentInstallStatus.NOT_INSTALLED,
    ),
    SetupComponentItem(
        id = "base_toolchains",
        name = "Base Toolchains",
        description = "Shell utilities, coreutils, Git, Node.js, and essential build utilities.",
        status = ComponentInstallStatus.NOT_INSTALLED,
    ),
    SetupComponentItem(
        id = "opencode_agent",
        name = "OpenCode Agent Runtime",
        description = "Primary autonomous coding runtime connected to JH GetWay Pro.",
        status = ComponentInstallStatus.NOT_INSTALLED,
    ),
    SetupComponentItem(
        id = "github_cli",
        name = "GitHub CLI (gh)",
        description = "Repository discovery, clone, commit, and pull request tooling.",
        status = ComponentInstallStatus.NOT_INSTALLED,
    ),
    SetupComponentItem(
        id = "base_skills_mcp",
        name = "Base Skills & MCP Registry",
        description = "Repository inspection skills and Streamable HTTP / SSE MCP client support.",
        status = ComponentInstallStatus.NOT_INSTALLED,
    ),
)

fun defaultRuntimeLicenses(): List<RuntimeLicenseNotice> = listOf(
    RuntimeLicenseNotice(
        component = "PRoot",
        license = "GNU General Public License v2.0",
        spdxId = "GPL-2.0-only",
        summary = "Userspace chroot/mount-bind layer (not a hardened VM boundary).",
    ),
    RuntimeLicenseNotice(
        component = "GNU Talloc",
        license = "GNU Lesser General Public License v3.0 or later",
        spdxId = "LGPL-3.0-or-later",
        summary = "Hierarchical pool memory allocator required by PRoot.",
    ),
)
