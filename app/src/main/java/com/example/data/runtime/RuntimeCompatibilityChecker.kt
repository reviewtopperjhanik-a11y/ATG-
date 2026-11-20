package com.example.data.runtime

import com.example.core.common.DeviceCompatibilityInfo
import com.example.domain.model.RuntimeCompatibilityReport
import com.example.domain.model.RuntimeManifest

/**
 * Evaluates Android device compatibility (CPU ABI, Android API level, and free disk space)
 * before attempting download or extraction.
 */
object RuntimeCompatibilityChecker {

    const val DEFAULT_MIN_SDK = 26
    const val DEFAULT_MIN_FREE_BYTES = 256L * 1024L * 1024L

    fun evaluate(
        deviceInfo: DeviceCompatibilityInfo,
        manifest: RuntimeManifest? = null,
    ): RuntimeCompatibilityReport {
        val allowedAbis = if (manifest != null && manifest.abiBundles.isNotEmpty()) {
            manifest.abiBundles.keys.toList()
        } else {
            manifest?.supportedAbis ?: listOf("arm64-v8a", "x86_64")
        }
        val requiredSdk = manifest?.minAndroidSdk ?: DEFAULT_MIN_SDK
        val requiredFreeBytes = manifest?.minRequiredFreeBytes ?: DEFAULT_MIN_FREE_BYTES

        val abiSupported = if (manifest != null) {
            manifest.selectBundleForDevice(deviceInfo.supportedAbis) != null &&
                deviceInfo.supportedAbis.any { dev ->
                    manifest.supportedAbis.any { allowed -> allowed.equals(dev, ignoreCase = true) }
                }
        } else {
            deviceInfo.supportedAbis.any { deviceAbi ->
                allowedAbis.any { allowed -> allowed.equals(deviceAbi, ignoreCase = true) }
            }
        }
        val sdkSupported = deviceInfo.androidSdk >= requiredSdk
        val storageSupported = deviceInfo.availableStorageBytes >= requiredFreeBytes

        val reasons = buildList {
            if (!abiSupported) {
                add(
                    "Unsupported CPU ABI (${deviceInfo.primaryAbi}). Required: ${allowedAbis.joinToString(", ")}."
                )
            }
            if (!sdkSupported) {
                add("Android API ${deviceInfo.androidSdk} is below required minimum API $requiredSdk.")
            }
            if (!storageSupported) {
                val reqMb = requiredFreeBytes / (1024 * 1024)
                add("At least ${reqMb} MB free storage is required (available: ${deviceInfo.availableStorageGbFormatted}).")
            }
        }

        return RuntimeCompatibilityReport(
            isCompatible = abiSupported && sdkSupported && storageSupported,
            deviceInfo = deviceInfo,
            abiSupported = abiSupported,
            sdkSupported = sdkSupported,
            storageSupported = storageSupported,
            requiredFreeBytes = requiredFreeBytes,
            blockingReasons = reasons,
        )
    }
}
