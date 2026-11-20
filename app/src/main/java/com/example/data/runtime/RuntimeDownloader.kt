package com.example.data.runtime

import com.example.core.common.AgtError
import com.example.core.security.GatewayUrlValidation
import com.example.core.security.GatewayUrlValidator
import com.example.core.security.SecretRedactor
import com.example.domain.model.RuntimeBundleSpec
import com.example.domain.model.RuntimeManifest
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.IOException
import java.security.MessageDigest
import java.util.concurrent.TimeUnit
import kotlin.coroutines.coroutineContext

class RuntimeDownloadException(val agtError: AgtError) : Exception(agtError.userMessage)

data class DownloadedRuntimeBundle(
    val archiveFile: File,
    val sha256Hex: String,
    val byteCount: Long,
)

/**
 * Fetches runtime manifests and downloads runtime bundles with HTTP Range resume support
 * and strict SHA-256 verification.
 */
class RuntimeDownloader(
    private val okHttpClient: OkHttpClient = defaultClient(),
) {
    suspend fun fetchManifest(manifestUrl: String): Result<RuntimeManifest> = withContext(Dispatchers.IO) {
        val validation = GatewayUrlValidator.validate(manifestUrl, allowQueryAndFragment = true)
        if (validation is GatewayUrlValidation.Invalid) {
            return@withContext Result.failure(
                RuntimeManifestException(AgtError.RuntimeManifestInvalid(userMessage = validation.reason))
            )
        }
        val normalizedUrl = (validation as GatewayUrlValidation.Valid).normalizedBaseUrl
        val req = Request.Builder()
            .url(normalizedUrl)
            .header("User-Agent", USER_AGENT)
            .get()
            .build()
        val call = okHttpClient.newCall(req)
        try {
            call.execute().use { resp ->
                val body = resp.body?.string().orEmpty()
                if (!resp.isSuccessful) {
                    val safe = SecretRedactor.redact("HTTP ${resp.code}: ${body.take(200)}")
                    return@withContext Result.failure(
                        RuntimeDownloadException(
                            AgtError.NetworkUnavailable(
                                userMessage = "Manifest server returned HTTP ${resp.code}.",
                                diagnosticDetail = safe,
                            )
                        )
                    )
                }
                RuntimeManifestParser.parse(body)
            }
        } catch (ce: CancellationException) {
            call.cancel()
            throw ce
        } catch (io: IOException) {
            val safe = SecretRedactor.redact(io.message ?: "Failed to fetch runtime manifest.")
            Result.failure(
                RuntimeDownloadException(
                    AgtError.NetworkUnavailable(
                        userMessage = safe,
                        diagnosticDetail = safe,
                    )
                )
            )
        }
    }

    suspend fun downloadAndVerify(
        bundle: RuntimeBundleSpec,
        downloadsDir: File,
        targetArchiveName: String,
        onProgress: suspend (downloadedBytes: Long, totalBytes: Long) -> Unit,
    ): Result<DownloadedRuntimeBundle> = withContext(Dispatchers.IO) {
        val urlValidation = GatewayUrlValidator.validate(bundle.downloadUrl, allowQueryAndFragment = true)
        if (urlValidation is GatewayUrlValidation.Invalid) {
            return@withContext Result.failure(
                RuntimeManifestException(
                    AgtError.RuntimeManifestInvalid(
                        userMessage = "Bundle URL rejected: ${urlValidation.reason}"
                    )
                )
            )
        }
        val safeDownloadUrl = (urlValidation as GatewayUrlValidation.Valid).normalizedBaseUrl

        if (!downloadsDir.exists() && !downloadsDir.mkdirs()) {
            return@withContext Result.failure(
                RuntimeDownloadException(
                    AgtError.RuntimeVerificationFailed("Unable to create runtime downloads directory.")
                )
            )
        }

        val partFile = File(downloadsDir, "$targetArchiveName.part")
        val finalFile = File(downloadsDir, targetArchiveName)

        // If the final archive already exists and passes SHA-256, reuse it cleanly.
        if (finalFile.exists() && finalFile.isFile) {
            val existingHash = computeSha256(finalFile)
            if (existingHash.equals(bundle.sha256, ignoreCase = true)) {
                val len = finalFile.length()
                onProgress(len, len)
                return@withContext Result.success(DownloadedRuntimeBundle(finalFile, existingHash, len))
            } else {
                finalFile.delete()
            }
        }

        val existingBytes = if (partFile.exists()) partFile.length() else 0L
        val expectedBytes = bundle.compressedSizeBytes

        if (expectedBytes > 0 && existingBytes > expectedBytes) {
            partFile.delete()
        }

        // If part file already exists and matches SHA-256 (whether expectedBytes was known or 0), promote it.
        if (partFile.exists() && partFile.length() > 0L && (expectedBytes == 0L || partFile.length() == expectedBytes)) {
            val partHash = computeSha256(partFile)
            if (partHash.equals(bundle.sha256, ignoreCase = true)) {
                if (finalFile.exists()) finalFile.delete()
                if (!partFile.renameTo(finalFile)) {
                    partFile.copyTo(finalFile, overwrite = true)
                    partFile.delete()
                }
                val len = finalFile.length()
                onProgress(len, len)
                return@withContext Result.success(DownloadedRuntimeBundle(finalFile, partHash, len))
            } else if (expectedBytes > 0 && partFile.length() == expectedBytes) {
                partFile.delete()
            }
        }

        var attempt = 0
        while (attempt < 2) {
            attempt++
            val currentPartLen = if (partFile.exists()) partFile.length() else 0L
            val resumeOffset = when {
                expectedBytes > 0 && currentPartLen in 1 until expectedBytes -> currentPartLen
                expectedBytes <= 0 && currentPartLen > 0L -> currentPartLen
                else -> 0L
            }

            val reqBuilder = Request.Builder()
                .url(safeDownloadUrl)
                .header("User-Agent", USER_AGENT)
                .get()
            if (resumeOffset > 0L) {
                reqBuilder.header("Range", "bytes=$resumeOffset-")
            }

            val call = okHttpClient.newCall(reqBuilder.build())
            var needsFreshRetry = false
            try {
                call.execute().use { resp ->
                    coroutineContext.ensureActive()
                    if (resp.code == 416 && partFile.exists()) {
                        partFile.delete()
                        if (attempt == 1) {
                            needsFreshRetry = true
                            return@use
                        }
                        return@withContext Result.failure(
                            RuntimeDownloadException(
                                AgtError.NetworkUnavailable("Server rejected byte range (416). Retry download cleanly.")
                            )
                        )
                    }
                    if (!resp.isSuccessful) {
                        return@withContext Result.failure(
                            RuntimeDownloadException(
                                AgtError.NetworkUnavailable("Runtime download failed with HTTP ${resp.code}.")
                            )
                        )
                    }

                    val isResumed = resp.code == 206 && resumeOffset > 0L
                    val startOffset = if (isResumed) resumeOffset else 0L
                    if (!isResumed && partFile.exists()) {
                        partFile.delete()
                    }

                    val body = resp.body ?: return@withContext Result.failure(
                        RuntimeDownloadException(AgtError.NetworkUnavailable("Runtime download returned empty body."))
                    )
                    val contentLength = body.contentLength()
                    val totalBytes = when {
                        expectedBytes > 0L -> expectedBytes
                        contentLength > 0L -> startOffset + contentLength
                        else -> -1L
                    }

                    var written = startOffset
                    onProgress(written, totalBytes)

                    val maxAllowedBytes = if (expectedBytes > 0L) expectedBytes + 64L * 1024L else MAX_RUNTIME_BUNDLE_BYTES
                    val buffer = ByteArray(64 * 1024)
                    var lastEmitMs = System.currentTimeMillis()
                    var lastEmitPct = if (totalBytes > 0L) ((written * 100L) / totalBytes).toInt() else -1

                    body.byteStream().use { input ->
                        FileOutputStream(partFile, isResumed).use { output ->
                            while (true) {
                                coroutineContext.ensureActive()
                                val read = input.read(buffer)
                                if (read < 0) break
                                if (written + read > maxAllowedBytes) {
                                    throw IOException("Runtime download exceeded maximum allowed size ($maxAllowedBytes bytes).")
                                }
                                output.write(buffer, 0, read)
                                written += read

                                val nowMs = System.currentTimeMillis()
                                val pct = if (totalBytes > 0L) ((written * 100L) / totalBytes).toInt().coerceIn(0, 100) else -1
                                if (nowMs - lastEmitMs >= PROGRESS_THROTTLE_MS || (pct >= 0 && pct != lastEmitPct)) {
                                    lastEmitMs = nowMs
                                    lastEmitPct = pct
                                    onProgress(written, totalBytes)
                                }
                            }
                            output.fd.sync()
                        }
                    }
                    onProgress(written, if (totalBytes > 0L) totalBytes else written)
                }
            } catch (ce: CancellationException) {
                call.cancel()
                throw ce
            } catch (io: IOException) {
                val safe = SecretRedactor.redact(io.message ?: "Runtime download interrupted.")
                return@withContext Result.failure(
                    RuntimeDownloadException(
                        AgtError.NetworkUnavailable(
                            userMessage = "$safe Partial download kept for resume.",
                            diagnosticDetail = safe,
                        )
                    )
                )
            }

            if (!needsFreshRetry) break
        }

        // Verify SHA-256 before moving .part to final verified archive.
        val actualHash = computeSha256(partFile)
        if (!actualHash.equals(bundle.sha256, ignoreCase = true)) {
            partFile.delete()
            return@withContext Result.failure(
                RuntimeDownloadException(
                    AgtError.RuntimeVerificationFailed(
                        userMessage = "SHA-256 verification failed. Corrupt runtime bundle was rejected and deleted.",
                        diagnosticDetail = "expected=${bundle.sha256}, actual=$actualHash",
                    )
                )
            )
        }

        if (finalFile.exists()) finalFile.delete()
        if (!partFile.renameTo(finalFile)) {
            partFile.copyTo(finalFile, overwrite = true)
            partFile.delete()
        }
        Result.success(
            DownloadedRuntimeBundle(
                archiveFile = finalFile,
                sha256Hex = actualHash,
                byteCount = finalFile.length(),
            )
        )
    }

    fun computeSha256(file: File): String {
        val digest = MessageDigest.getInstance("SHA-256")
        val buf = ByteArray(64 * 1024)
        FileInputStream(file).use { fis ->
            while (true) {
                val read = fis.read(buf)
                if (read < 0) break
                digest.update(buf, 0, read)
            }
        }
        return digest.digest().joinToString("") { "%02x".format(it) }
    }

    companion object {
        private const val USER_AGENT = "AGT-Code-Android/1.0"
        private const val PROGRESS_THROTTLE_MS = 150L
        private const val MAX_RUNTIME_BUNDLE_BYTES = 2L * 1024L * 1024L * 1024L

        fun defaultClient(): OkHttpClient = OkHttpClient.Builder()
            .dns(GatewayUrlValidator.ssrfGuardDns())
            .followRedirects(true)
            .followSslRedirects(true)
            .connectTimeout(25, TimeUnit.SECONDS)
            .readTimeout(120, TimeUnit.SECONDS)
            .writeTimeout(30, TimeUnit.SECONDS)
            .build()
    }
}
