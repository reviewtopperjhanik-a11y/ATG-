package com.example.core.common

import android.content.Context
import android.os.Build
import android.os.StatFs

data class DeviceCompatibilityInfo(
    val primaryAbi: String,
    val supportedAbis: List<String>,
    val androidSdk: Int,
    val androidRelease: String,
    val availableStorageBytes: Long,
    val isArm64: Boolean,
) {
    val availableStorageGbFormatted: String
        get() {
            val gb = availableStorageBytes.toDouble() / (1024.0 * 1024.0 * 1024.0)
            return String.format(java.util.Locale.US, "%.1f GB", gb)
        }
}

object DeviceEnvironment {
    fun inspect(context: Context): DeviceCompatibilityInfo {
        val abis = Build.SUPPORTED_ABIS?.toList().orEmpty()
        val primary = abis.firstOrNull().orEmpty().ifBlank { "unknown" }
        val statFs = StatFs(context.filesDir.absolutePath)
        val availableBytes = statFs.availableBytes.takeIf { it > 0L } ?: context.filesDir.usableSpace
        return DeviceCompatibilityInfo(
            primaryAbi = primary,
            supportedAbis = abis,
            androidSdk = Build.VERSION.SDK_INT,
            androidRelease = Build.VERSION.RELEASE ?: "unknown",
            availableStorageBytes = availableBytes,
            isArm64 = abis.any { it.equals("arm64-v8a", ignoreCase = true) },
        )
    }
}
