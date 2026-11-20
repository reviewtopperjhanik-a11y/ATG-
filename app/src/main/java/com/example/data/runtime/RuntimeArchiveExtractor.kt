package com.example.data.runtime

import android.os.Build
import android.system.Os
import com.example.core.common.AgtError
import com.example.domain.model.RuntimeArchiveFormat
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import java.io.BufferedInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.InputStream
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.util.zip.GZIPInputStream
import java.util.zip.ZipInputStream
import kotlin.coroutines.coroutineContext

class RuntimeExtractionException(val agtError: AgtError) : Exception(agtError.userMessage)

data class ExtractionSummary(
    val fileCount: Int,
    val extractedBytes: Long,
)

/**
 * Extracts verified runtime archives (.tar, .tar.gz, .zip) into an isolated staging directory.
 * Rejects path traversal, backslash paths, null bytes, and symlink escapes outside stagingRoot.
 */
object RuntimeArchiveExtractor {

    private const val DEFAULT_MAX_EXTRACTED_BYTES = 2L * 1024L * 1024L * 1024L
    private const val DEFAULT_MAX_ENTRIES = 100_000

    suspend fun extractToStaging(
        archiveFile: File,
        format: RuntimeArchiveFormat,
        stagingRoot: File,
        stripRootPrefix: String = "",
        maxExtractedBytes: Long = DEFAULT_MAX_EXTRACTED_BYTES,
        maxEntries: Int = DEFAULT_MAX_ENTRIES,
        onEntryExtracted: suspend (count: Int) -> Unit = {},
    ): Result<ExtractionSummary> = withContext(Dispatchers.IO) {
        if (!archiveFile.exists() || !archiveFile.isFile) {
            return@withContext Result.failure(
                RuntimeExtractionException(AgtError.RuntimeVerificationFailed("Verified archive file not found."))
            )
        }
        if (stagingRoot.exists()) {
            stagingRoot.deleteRecursively()
        }
        if (!stagingRoot.mkdirs()) {
            return@withContext Result.failure(
                RuntimeExtractionException(AgtError.RuntimeVerificationFailed("Failed to prepare staging directory."))
            )
        }

        val normalizedStrip = stripRootPrefix.trim().trim('/')
        try {
            val summary = when (format) {
                RuntimeArchiveFormat.TAR -> BufferedInputStream(FileInputStream(archiveFile)).use {
                    extractTarStream(it, stagingRoot, normalizedStrip, maxExtractedBytes, maxEntries, onEntryExtracted)
                }
                RuntimeArchiveFormat.TAR_GZ -> BufferedInputStream(FileInputStream(archiveFile)).use { raw ->
                    GZIPInputStream(raw).use { gz ->
                        extractTarStream(gz, stagingRoot, normalizedStrip, maxExtractedBytes, maxEntries, onEntryExtracted)
                    }
                }
                RuntimeArchiveFormat.ZIP -> BufferedInputStream(FileInputStream(archiveFile)).use { raw ->
                    extractZipStream(raw, stagingRoot, normalizedStrip, maxExtractedBytes, maxEntries, onEntryExtracted)
                }
            }
            Result.success(summary)
        } catch (ce: CancellationException) {
            stagingRoot.deleteRecursively()
            throw ce
        } catch (re: RuntimeExtractionException) {
            stagingRoot.deleteRecursively()
            Result.failure(re)
        } catch (t: Throwable) {
            stagingRoot.deleteRecursively()
            Result.failure(
                RuntimeExtractionException(
                    AgtError.RuntimeVerificationFailed(
                        userMessage = t.message ?: "Failed to extract runtime archive.",
                        diagnosticDetail = t.message,
                    )
                )
            )
        }
    }

    private suspend fun extractZipStream(
        raw: InputStream,
        stagingRoot: File,
        stripPrefix: String,
        maxExtractedBytes: Long,
        maxEntries: Int,
        onEntryExtracted: suspend (Int) -> Unit,
    ): ExtractionSummary {
        var count = 0
        var totalBytes = 0L
        val buffer = ByteArray(64 * 1024)
        val stagingCanonical = stagingRoot.canonicalFile
        ZipInputStream(raw).use { zis ->
            while (true) {
                coroutineContext.ensureActive()
                val entry = zis.nextEntry ?: break
                val relative = sanitizeAndStripEntryPath(entry.name, stripPrefix)
                if (relative == null) {
                    zis.closeEntry()
                    continue
                }
                val dest = resolveConfinedTarget(stagingCanonical, relative)
                if (entry.isDirectory) {
                    ensureDirectoryTarget(stagingCanonical, dest)
                } else {
                    if (count >= maxEntries) {
                        throw RuntimeExtractionException(
                            AgtError.RuntimeVerificationFailed("Archive exceeds maximum allowed entry count ($maxEntries).")
                        )
                    }
                    ensureParentDirectory(stagingCanonical, dest)
                    deleteIfExistsOrSymlink(dest)
                    FileOutputStream(dest).use { out ->
                        while (true) {
                            coroutineContext.ensureActive()
                            val n = zis.read(buffer)
                            if (n <= 0) break
                            if (totalBytes + n > maxExtractedBytes) {
                                throw RuntimeExtractionException(
                                    AgtError.RuntimeVerificationFailed("Archive exceeds maximum extracted size ($maxExtractedBytes bytes).")
                                )
                            }
                            out.write(buffer, 0, n)
                            totalBytes += n
                        }
                    }
                    maybeMarkExecutable(relative, dest, ownerExec = false)
                    count++
                    onEntryExtracted(count)
                }
                zis.closeEntry()
            }
        }
        return ExtractionSummary(fileCount = count, extractedBytes = totalBytes)
    }

    private suspend fun extractTarStream(
        input: InputStream,
        stagingRoot: File,
        stripPrefix: String,
        maxExtractedBytes: Long,
        maxEntries: Int,
        onEntryExtracted: suspend (Int) -> Unit,
    ): ExtractionSummary {
        val header = ByteArray(512)
        var pendingLongName: String? = null
        var pendingLongLink: String? = null
        var paxOverrides: Map<String, String> = emptyMap()
        var count = 0
        var totalBytes = 0L
        val stagingCanonical = stagingRoot.canonicalFile

        while (true) {
            coroutineContext.ensureActive()
            val headerBytes = readFully(input, header)
            if (headerBytes == 0) break
            if (headerBytes < 512) {
                throw RuntimeExtractionException(AgtError.RuntimeVerificationFailed("Truncated tar header block."))
            }
            if (header.all { it.toInt() == 0 }) {
                break
            }

            val typeFlag = header[156].toInt().toChar()
            val rawName = readTarName(header)
            val mode = parseOctal(header, 100, 8)
            val size = parseTarSize(header)
            val linkRaw = readCString(header, 157, 100)

            val effectiveName = paxOverrides["path"] ?: pendingLongName ?: rawName
            val effectiveLink = paxOverrides["linkpath"] ?: pendingLongLink ?: linkRaw
            pendingLongName = null
            pendingLongLink = null
            paxOverrides = emptyMap()

            when (typeFlag) {
                'L' -> {
                    val data = readExactBytesAndPad(input, size)
                    pendingLongName = data.toString(StandardCharsets.UTF_8).trimEnd('\u0000')
                    continue
                }
                'K' -> {
                    val data = readExactBytesAndPad(input, size)
                    pendingLongLink = data.toString(StandardCharsets.UTF_8).trimEnd('\u0000')
                    continue
                }
                'x' -> {
                    val data = readExactBytesAndPad(input, size)
                    paxOverrides = parsePaxRecords(data.toString(StandardCharsets.UTF_8))
                    continue
                }
                'g' -> {
                    skipBytesAndPad(input, size)
                    continue
                }
            }

            val relative = sanitizeAndStripEntryPath(effectiveName, stripPrefix)
            if (relative == null) {
                skipBytesAndPad(input, size)
                continue
            }

            val dest = resolveConfinedTarget(stagingCanonical, relative)
            when (typeFlag) {
                '5' -> {
                    ensureParentDirectory(stagingCanonical, dest)
                    ensureDirectoryTarget(stagingCanonical, dest)
                    skipBytesAndPad(input, size)
                }
                '1' -> {
                    if (count >= maxEntries) {
                        throw RuntimeExtractionException(
                            AgtError.RuntimeVerificationFailed("Archive exceeds maximum allowed entry count ($maxEntries).")
                        )
                    }
                    skipBytesAndPad(input, size)
                    val linkRelative = sanitizeAndStripEntryPath(effectiveLink, stripPrefix)
                    if (linkRelative != null) {
                        val targetFile = resolveConfinedTarget(stagingCanonical, linkRelative)
                        ensureParentDirectory(stagingCanonical, dest)
                        deleteIfExistsOrSymlink(dest)
                        createHardlinkOrCopy(targetFile, dest)
                        count++
                        onEntryExtracted(count)
                    }
                }
                '2' -> {
                    if (count >= maxEntries) {
                        throw RuntimeExtractionException(
                            AgtError.RuntimeVerificationFailed("Archive exceeds maximum allowed entry count ($maxEntries).")
                        )
                    }
                    skipBytesAndPad(input, size)
                    validateSymlinkTarget(stagingCanonical, dest, effectiveLink)
                    ensureParentDirectory(stagingCanonical, dest)
                    deleteIfExistsOrSymlink(dest)
                    createSymlink(effectiveLink, dest)
                    count++
                    onEntryExtracted(count)
                }
                '0', '\u0000' -> {
                    if (count >= maxEntries) {
                        throw RuntimeExtractionException(
                            AgtError.RuntimeVerificationFailed("Archive exceeds maximum allowed entry count ($maxEntries).")
                        )
                    }
                    if (size < 0L || totalBytes + size > maxExtractedBytes) {
                        throw RuntimeExtractionException(
                            AgtError.RuntimeVerificationFailed("Archive exceeds maximum extracted size ($maxExtractedBytes bytes).")
                        )
                    }
                    ensureParentDirectory(stagingCanonical, dest)
                    deleteIfExistsOrSymlink(dest)
                    copyExactBytesAndPad(input, dest, size)
                    totalBytes += size
                    val ownerExec = (mode and 0b001_000_000L) != 0L
                    maybeMarkExecutable(relative, dest, ownerExec)
                    count++
                    onEntryExtracted(count)
                }
                else -> {
                    skipBytesAndPad(input, size)
                }
            }
        }
        return ExtractionSummary(fileCount = count, extractedBytes = totalBytes)
    }

    private fun sanitizeAndStripEntryPath(rawEntryName: String, stripPrefix: String): String? {
        if (rawEntryName.contains('\u0000') || rawEntryName.contains('\\')) {
            throw RuntimeExtractionException(
                AgtError.RuntimeVerificationFailed("Archive entry contains illegal characters: $rawEntryName")
            )
        }
        var cleaned = rawEntryName.trim()
        if (cleaned.startsWith("/")) {
            throw RuntimeExtractionException(
                AgtError.RuntimeVerificationFailed("Archive entry uses forbidden absolute path: $rawEntryName")
            )
        }
        while (cleaned.startsWith("./")) {
            cleaned = cleaned.removePrefix("./")
        }
        cleaned = cleaned.trimEnd('/')
        if (cleaned.isEmpty() || cleaned == ".") return null

        val parts = cleaned.split('/')
        if (parts.any { it == ".." || it.isEmpty() }) {
            throw RuntimeExtractionException(
                AgtError.RuntimeVerificationFailed("Archive path traversal rejected: $rawEntryName")
            )
        }

        if (stripPrefix.isNotEmpty()) {
            cleaned = when {
                cleaned == stripPrefix -> return null
                cleaned.startsWith("$stripPrefix/") -> cleaned.removePrefix("$stripPrefix/")
                else -> cleaned
            }
        }
        return cleaned.ifEmpty { null }
    }

    /**
     * Resolves [relativePath] inside [stagingRoot] without letting pre-existing symlinks
     * (such as rootfs `var/run -> /run` or `bin -> usr/bin`) escape onto the host filesystem.
     */
    private fun resolveConfinedTarget(stagingRoot: File, relativePath: String): File {
        val rootCanonical = stagingRoot.canonicalFile
        val segments = relativePath.split('/').filter { it.isNotEmpty() && it != "." }
        val resolvedSegments = mutableListOf<String>()
        var symlinkHops = 0

        for (idx in segments.indices) {
            val seg = segments[idx]
            if (seg == "..") {
                if (resolvedSegments.isEmpty()) {
                    throw RuntimeExtractionException(
                        AgtError.RuntimeVerificationFailed("Archive path escapes staging root: $relativePath")
                    )
                }
                resolvedSegments.removeAt(resolvedSegments.lastIndex)
                continue
            }
            resolvedSegments += seg
            val isLast = idx == segments.lastIndex
            if (!isLast) {
                val cur = File(rootCanonical, resolvedSegments.joinToString(File.separator))
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O && Files.isSymbolicLink(cur.toPath())) {
                    symlinkHops++
                    if (symlinkHops > 32) {
                        throw RuntimeExtractionException(
                            AgtError.RuntimeVerificationFailed("Archive symlink loop detected at: $relativePath")
                        )
                    }
                    val linkTarget = Files.readSymbolicLink(cur.toPath()).toString()
                    val remapped = remapLinkIntoRootSegments(resolvedSegments.dropLast(1), linkTarget, relativePath)
                    resolvedSegments.clear()
                    resolvedSegments.addAll(remapped)
                }
            }
        }

        val target = if (resolvedSegments.isEmpty()) {
            rootCanonical
        } else {
            File(rootCanonical, resolvedSegments.joinToString(File.separator))
        }
        val rootNorm = rootCanonical.toPath().toAbsolutePath().normalize()
        val targetNorm = target.toPath().toAbsolutePath().normalize()
        if (!targetNorm.startsWith(rootNorm)) {
            throw RuntimeExtractionException(
                AgtError.RuntimeVerificationFailed("Archive path escapes staging root: $relativePath")
            )
        }
        return target
    }

    private fun remapLinkIntoRootSegments(
        parentSegments: List<String>,
        linkTarget: String,
        contextPath: String,
    ): List<String> {
        if (linkTarget.contains('\u0000') || linkTarget.contains('\\')) {
            throw RuntimeExtractionException(
                AgtError.RuntimeVerificationFailed("Archive symlink target contains illegal characters: $linkTarget")
            )
        }
        val base = if (linkTarget.startsWith('/')) {
            mutableListOf()
        } else {
            parentSegments.toMutableList()
        }
        for (seg in linkTarget.split('/').filter { it.isNotEmpty() && it != "." }) {
            if (seg == "..") {
                if (base.isEmpty()) {
                    throw RuntimeExtractionException(
                        AgtError.RuntimeVerificationFailed("Archive symlink escapes staging root: $contextPath -> $linkTarget")
                    )
                }
                base.removeAt(base.lastIndex)
            } else {
                base += seg
            }
        }
        return base
    }

    private fun validateSymlinkTarget(stagingCanonical: File, linkFile: File, target: String) {
        if (target.contains('\u0000') || target.contains('\\')) {
            throw RuntimeExtractionException(
                AgtError.RuntimeVerificationFailed("Archive symlink target contains illegal characters: $target")
            )
        }
        if (target.startsWith('/')) {
            val cleanTarget = target.trimStart('/')
            if (cleanTarget == "proc" || cleanTarget.startsWith("proc/") ||
                cleanTarget == "dev" || cleanTarget.startsWith("dev/") ||
                cleanTarget == "sys" || cleanTarget.startsWith("sys/")
            ) {
                return
            }
            val segments = cleanTarget.split('/').filter { it.isNotEmpty() && it != "." }
            var depth = 0
            for (seg in segments) {
                if (seg == "..") {
                    depth--
                    if (depth < 0) {
                        throw RuntimeExtractionException(
                            AgtError.RuntimeVerificationFailed("Archive symlink target escapes rootfs: $target")
                        )
                    }
                } else {
                    depth++
                }
            }
            return
        }
        val rootNorm = stagingCanonical.toPath().toAbsolutePath().normalize()
        val parentNorm = (linkFile.parentFile ?: stagingCanonical).toPath().toAbsolutePath().normalize()
        val resolvedNorm = parentNorm.resolve(target).normalize()
        if (!resolvedNorm.startsWith(rootNorm)) {
            throw RuntimeExtractionException(
                AgtError.RuntimeVerificationFailed("Archive symlink escape rejected: $target")
            )
        }
    }

    private fun ensureDirectoryTarget(stagingRoot: File, dir: File) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O && Files.isSymbolicLink(dir.toPath())) {
            val linkTarget = Files.readSymbolicLink(dir.toPath()).toString()
            val rootCanon = stagingRoot.canonicalFile
            val parentRel = runCatching {
                (dir.parentFile ?: rootCanon).toPath().toAbsolutePath().normalize()
                    .let { p ->
                        val r = rootCanon.toPath().toAbsolutePath().normalize()
                        if (p.startsWith(r)) r.relativize(p).toString().replace(File.separatorChar, '/') else ""
                    }
            }.getOrDefault("")
            val parentSegs = parentRel.split('/').filter { it.isNotEmpty() }
            val remapped = remapLinkIntoRootSegments(parentSegs, linkTarget, dir.name)
            val targetDir = if (remapped.isEmpty()) {
                rootCanon
            } else {
                File(rootCanon, remapped.joinToString(File.separator))
            }
            targetDir.mkdirs()
            return
        }
        if (dir.exists() && !dir.isDirectory) {
            dir.delete()
        }
        dir.mkdirs()
    }

    private fun ensureParentDirectory(stagingRoot: File, dest: File) {
        val parent = dest.parentFile ?: return
        ensureDirectoryTarget(stagingRoot, parent)
    }

    private fun deleteIfExistsOrSymlink(dest: File) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O && Files.isSymbolicLink(dest.toPath())) {
            Files.deleteIfExists(dest.toPath())
        } else if (dest.isDirectory) {
            dest.deleteRecursively()
        } else if (dest.exists()) {
            dest.delete()
        }
    }

    private fun createHardlinkOrCopy(targetFile: File, linkDest: File) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val linked = runCatching {
                if (targetFile.exists()) {
                    Files.createLink(linkDest.toPath(), targetFile.toPath())
                    true
                } else {
                    false
                }
            }.getOrDefault(false)
            if (linked) return
        }
        if (targetFile.exists() && targetFile.isFile) {
            targetFile.copyTo(linkDest, overwrite = true)
            if (targetFile.canExecute()) {
                linkDest.setExecutable(true, false)
            }
        }
    }

    private fun createSymlink(target: String, linkFile: File) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            Files.createSymbolicLink(linkFile.toPath(), File(target).toPath())
        } else {
            Os.symlink(target, linkFile.absolutePath)
        }
    }

    fun mergeDirectoryInto(
        sourceDir: File,
        targetDir: File,
        confinementRoot: File = targetDir,
    ) {
        if (!sourceDir.exists() || !sourceDir.isDirectory) return
        val sourceCanon = sourceDir.canonicalFile
        val targetCanon = targetDir.apply { mkdirs() }.canonicalFile
        val confinementCanon = confinementRoot.apply { mkdirs() }.canonicalFile
        fun copyRecursive(src: File, dst: File) {
            val isSymlink = Build.VERSION.SDK_INT >= Build.VERSION_CODES.O && Files.isSymbolicLink(src.toPath())
            if (isSymlink) {
                val linkTarget = Files.readSymbolicLink(src.toPath()).toString()
                validateSymlinkTarget(sourceCanon, src, linkTarget)
                ensureParentDirectory(confinementCanon, dst)
                deleteIfExistsOrSymlink(dst)
                createSymlink(linkTarget, dst)
                return
            }
            if (src.isDirectory) {
                ensureDirectoryTarget(confinementCanon, dst)
                src.listFiles()?.forEach { child ->
                    copyRecursive(child, File(dst, child.name))
                }
                return
            }
            if (src.isFile) {
                ensureParentDirectory(confinementCanon, dst)
                deleteIfExistsOrSymlink(dst)
                src.copyTo(dst, overwrite = true)
                if (src.canExecute()) {
                    dst.setExecutable(true, false)
                }
            }
        }
        sourceCanon.listFiles()?.forEach { child ->
            copyRecursive(child, File(targetCanon, child.name))
        }
    }

    private fun maybeMarkExecutable(relativePath: String, file: File, ownerExec: Boolean) {
        if (ownerExec ||
            relativePath.startsWith("bin/") ||
            relativePath.startsWith("sbin/") ||
            relativePath.startsWith("usr/bin/") ||
            relativePath.startsWith("usr/sbin/") ||
            relativePath.startsWith("usr/local/bin/") ||
            relativePath.endsWith(".sh")
        ) {
            file.setExecutable(true, false)
        }
    }

    private fun readTarName(header: ByteArray): String {
        val name = readCString(header, 0, 100)
        val prefix = readCString(header, 345, 155)
        return if (prefix.isNotEmpty()) "$prefix/$name" else name
    }

    private fun readCString(buf: ByteArray, offset: Int, maxLen: Int): String {
        var end = offset
        val limit = (offset + maxLen).coerceAtMost(buf.size)
        while (end < limit && buf[end].toInt() != 0) end++
        return String(buf, offset, end - offset, StandardCharsets.UTF_8)
    }

    private fun parseOctal(buf: ByteArray, offset: Int, len: Int): Long {
        var value = 0L
        val end = offset + len
        for (i in offset until end) {
            val b = buf[i].toInt() and 0xFF
            if (b == 0 || b == ' '.code) {
                if (value > 0) break else continue
            }
            if (b in '0'.code..'7'.code) {
                value = (value shl 3) + (b - '0'.code)
            }
        }
        return value
    }

    private fun parseTarSize(header: ByteArray): Long {
        val first = header[124].toInt() and 0xFF
        if ((first and 0x80) != 0) {
            var result = 0L
            for (i in 125 until 136) {
                result = (result shl 8) or (header[i].toLong() and 0xFFL)
            }
            return result
        }
        return parseOctal(header, 124, 12)
    }

    private fun parsePaxRecords(text: String): Map<String, String> {
        val map = mutableMapOf<String, String>()
        text.lineSequence().forEach { line ->
            val spaceIdx = line.indexOf(' ')
            if (spaceIdx > 0) {
                val kv = line.substring(spaceIdx + 1)
                val eqIdx = kv.indexOf('=')
                if (eqIdx > 0) {
                    map[kv.substring(0, eqIdx)] = kv.substring(eqIdx + 1)
                }
            }
        }
        return map
    }

    private fun readFully(input: InputStream, dest: ByteArray): Int {
        var total = 0
        while (total < dest.size) {
            val n = input.read(dest, total, dest.size - total)
            if (n < 0) break
            total += n
        }
        return total
    }

    private fun readExactBytesAndPad(input: InputStream, size: Long): ByteArray {
        val out = ByteArrayOutputStream()
        val buf = ByteArray(4096)
        var rem = size
        while (rem > 0) {
            val toRead = minOf(buf.size.toLong(), rem).toInt()
            val n = input.read(buf, 0, toRead)
            if (n < 0) throw RuntimeExtractionException(AgtError.RuntimeVerificationFailed("Unexpected EOF in tar entry."))
            out.write(buf, 0, n)
            rem -= n
        }
        skipTarPadding(input, size)
        return out.toByteArray()
    }

    private suspend fun copyExactBytesAndPad(input: InputStream, dest: File, size: Long) {
        val buf = ByteArray(16 * 1024)
        var rem = size
        FileOutputStream(dest).use { out ->
            while (rem > 0) {
                coroutineContext.ensureActive()
                val toRead = minOf(buf.size.toLong(), rem).toInt()
                val n = input.read(buf, 0, toRead)
                if (n < 0) {
                    throw RuntimeExtractionException(AgtError.RuntimeVerificationFailed("Truncated file entry in tar archive."))
                }
                out.write(buf, 0, n)
                rem -= n
            }
        }
        skipTarPadding(input, size)
    }

    private fun skipBytesAndPad(input: InputStream, size: Long) {
        if (size <= 0L) return
        val buf = ByteArray(8192)
        var rem = size
        while (rem > 0) {
            val toRead = minOf(buf.size.toLong(), rem).toInt()
            val n = input.read(buf, 0, toRead)
            if (n < 0) break
            rem -= n
        }
        skipTarPadding(input, size)
    }

    private fun skipTarPadding(input: InputStream, size: Long) {
        val mod = (size % 512L).toInt()
        if (mod == 0) return
        val pad = 512 - mod
        val buf = ByteArray(pad)
        readFully(input, buf)
    }
}
