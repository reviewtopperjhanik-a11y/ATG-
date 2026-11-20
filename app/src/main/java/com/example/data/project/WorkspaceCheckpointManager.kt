package com.example.data.project

import android.content.Context
import com.example.agent.orchestrator.RepositoryInspector
import com.example.core.common.ProjectPathValidator
import com.example.data.database.CheckpointDao
import com.example.data.database.CheckpointEntity
import com.example.data.database.ProjectDao
import com.example.domain.contracts.Checkpoint
import com.example.domain.contracts.CheckpointManager
import com.example.domain.model.CheckpointSummary
import com.example.domain.model.FileChangeType
import com.example.domain.model.FileDiffEntry
import com.example.domain.model.ProjectDiff
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.security.MessageDigest
import java.util.UUID

/**
 * Real filesystem snapshot, diff, and recovery manager confined to project roots.
 *
 * Captures file manifests + SHA-256 hashes + snapshot copies, computes diffs against the
 * live workspace, enforces bounded retention, and restores safely with automatic pre-restore
 * safety backups.
 */
class WorkspaceCheckpointManager(
    context: Context,
    private val projectDao: ProjectDao,
    private val checkpointDao: CheckpointDao,
    private val maxCheckpointsPerProject: Int = 15,
) : CheckpointManager {

    private val appContext = context.applicationContext
    private val mutex = Mutex()

    private val checkpointsRoot: File
        get() = File(appContext.filesDir, "checkpoints").apply { mkdirs() }

    override fun observeForProject(projectId: String): Flow<List<CheckpointSummary>> =
        checkpointDao.observeCheckpointsForProject(projectId).map { list ->
            list.map { entity ->
                CheckpointSummary(
                    id = entity.id,
                    projectId = entity.projectId,
                    reason = entity.reason,
                    gitHead = entity.gitHead,
                    fileCount = entity.fileCount,
                    createdAtEpochMs = entity.createdAtEpochMs,
                )
            }
        }

    override suspend fun create(projectId: String, reason: String): Result<Checkpoint> =
        mutex.withLock {
            createInternal(projectId = projectId, reason = reason)
        }

    private suspend fun createInternal(projectId: String, reason: String): Result<Checkpoint> =
        withContext(Dispatchers.IO) {
            runCatching {
                val project = projectDao.getProjectById(projectId)
                    ?: error("Project not found: $projectId")
                val workspaceRoot = File(project.workspacePath).canonicalFile
                require(workspaceRoot.exists() && workspaceRoot.isDirectory) {
                    "Workspace directory missing: ${project.workspacePath}"
                }

                val checkpointId = "chk-${System.currentTimeMillis()}-${UUID.randomUUID().toString().take(6)}"
                val snapshotDir = File(File(checkpointsRoot, projectId), checkpointId).apply { mkdirs() }
                val filesDir = File(snapshotDir, "files").apply { mkdirs() }

                val currentFiles = collectWorkspaceFiles(workspaceRoot)
                var totalBytes = 0L
                val manifestFiles = JSONArray()

                for ((relPath, srcFile) in currentFiles) {
                    val fileSize = srcFile.length()
                    totalBytes += fileSize
                    if (totalBytes > MAX_SNAPSHOT_BYTES) {
                        break
                    }
                    val destFile = ProjectPathValidator.resolveWithinRoot(filesDir, relPath).getOrThrow()
                    destFile.parentFile?.mkdirs()
                    srcFile.copyTo(destFile, overwrite = true)

                    val sha = sha256File(srcFile)
                    manifestFiles.put(
                        JSONObject()
                            .put("path", relPath)
                            .put("sha256", sha)
                            .put("size", fileSize)
                    )
                }

                val gitHead = readGitHead(workspaceRoot)
                val now = System.currentTimeMillis()
                val manifestJson = JSONObject()
                    .put("checkpointId", checkpointId)
                    .put("projectId", projectId)
                    .put("reason", reason)
                    .put("gitHead", gitHead ?: "")
                    .put("createdAtEpochMs", now)
                    .put("files", manifestFiles)
                File(snapshotDir, "manifest.json").writeText(manifestJson.toString(2))

                val entity = CheckpointEntity(
                    id = checkpointId,
                    projectId = projectId,
                    reason = reason,
                    snapshotPath = snapshotDir.absolutePath,
                    gitHead = gitHead,
                    createdAtEpochMs = now,
                    fileCount = manifestFiles.length(),
                )
                checkpointDao.insertCheckpoint(entity)
                pruneOldCheckpoints(projectId)

                Checkpoint(
                    id = checkpointId,
                    projectId = projectId,
                    reason = reason,
                    createdAtEpochMs = now,
                )
            }
        }

    override suspend fun diff(checkpointId: String): Result<ProjectDiff> = withContext(Dispatchers.IO) {
        runCatching {
            val entity = checkpointDao.getCheckpointById(checkpointId)
                ?: error("Checkpoint not found: $checkpointId")
            val project = projectDao.getProjectById(entity.projectId)
                ?: error("Project not found: ${entity.projectId}")
            val workspaceRoot = File(project.workspacePath).canonicalFile
            val snapshotDir = File(entity.snapshotPath)
            val snapshotFilesDir = File(snapshotDir, "files")
            val manifestFile = File(snapshotDir, "manifest.json")
            require(manifestFile.isFile) { "Checkpoint manifest missing for $checkpointId" }

            val manifestMap = readManifestEntries(manifestFile)
            val liveFiles = collectWorkspaceFiles(workspaceRoot)
            val allPaths = (manifestMap.keys + liveFiles.keys).toSortedSet()

            val entries = mutableListOf<FileDiffEntry>()
            for (rel in allPaths) {
                val snapEntry = manifestMap[rel]
                val liveFile = liveFiles[rel]
                val snapFile = if (snapEntry != null) {
                    ProjectPathValidator.resolveWithinRoot(snapshotFilesDir, rel).getOrNull()
                } else {
                    null
                }

                when {
                    snapEntry == null && liveFile != null -> {
                        val preview = renderSingleFileDiff(rel, liveFile, isAddition = true)
                        entries += FileDiffEntry(
                            relativePath = rel,
                            changeType = FileChangeType.ADDED,
                            beforeSizeBytes = 0L,
                            afterSizeBytes = liveFile.length(),
                            unifiedDiff = preview,
                        )
                    }
                    snapEntry != null && liveFile == null -> {
                        val preview = if (snapFile != null && snapFile.isFile) {
                            renderSingleFileDiff(rel, snapFile, isAddition = false)
                        } else {
                            "--- a/$rel\n+++ /dev/null\n@@ Deleted @@"
                        }
                        entries += FileDiffEntry(
                            relativePath = rel,
                            changeType = FileChangeType.DELETED,
                            beforeSizeBytes = snapEntry.sizeBytes,
                            afterSizeBytes = 0L,
                            unifiedDiff = preview,
                        )
                    }
                    snapEntry != null && liveFile != null -> {
                        val liveSha = sha256File(liveFile)
                        if (liveSha != snapEntry.sha256) {
                            val diffText = renderModifiedDiff(rel, snapFile, liveFile)
                            entries += FileDiffEntry(
                                relativePath = rel,
                                changeType = FileChangeType.MODIFIED,
                                beforeSizeBytes = snapEntry.sizeBytes,
                                afterSizeBytes = liveFile.length(),
                                unifiedDiff = diffText,
                            )
                        }
                    }
                }
            }

            ProjectDiff(
                checkpointId = entity.id,
                checkpointReason = entity.reason,
                createdAtEpochMs = entity.createdAtEpochMs,
                entries = entries,
            )
        }
    }

    override suspend fun restore(checkpointId: String): Result<Unit> = mutex.withLock {
        withContext(Dispatchers.IO) {
            runCatching {
                val entity = checkpointDao.getCheckpointById(checkpointId)
                    ?: error("Checkpoint not found: $checkpointId")
                val project = projectDao.getProjectById(entity.projectId)
                    ?: error("Project not found: ${entity.projectId}")
                val workspaceRoot = File(project.workspacePath).canonicalFile
                val snapshotDir = File(entity.snapshotPath)
                val snapshotFilesDir = File(snapshotDir, "files")
                val manifestFile = File(snapshotDir, "manifest.json")
                require(manifestFile.isFile) { "Checkpoint manifest missing for $checkpointId" }

                // Create safety backup before modifying workspace
                createInternal(
                    projectId = project.id,
                    reason = "Pre-restore backup (before ${checkpointId.take(14)})",
                )

                val manifestMap = readManifestEntries(manifestFile)
                val liveFiles = collectWorkspaceFiles(workspaceRoot)

                // Delete files created after the checkpoint
                for ((rel, liveFile) in liveFiles) {
                    if (rel !in manifestMap) {
                        val target = ProjectPathValidator.resolveWithinRoot(workspaceRoot, rel).getOrNull()
                        if (target != null && target.exists()) {
                            target.delete()
                            cleanEmptyParents(target.parentFile, workspaceRoot)
                        }
                    }
                }

                // Restore files from snapshot
                for (rel in manifestMap.keys) {
                    val snapFile = ProjectPathValidator.resolveWithinRoot(snapshotFilesDir, rel).getOrThrow()
                    val destFile = ProjectPathValidator.resolveWithinRoot(workspaceRoot, rel).getOrThrow()
                    if (snapFile.isFile) {
                        destFile.parentFile?.mkdirs()
                        snapFile.copyTo(destFile, overwrite = true)
                    }
                }
                Unit
            }
        }
    }

    private suspend fun pruneOldCheckpoints(projectId: String) {
        val all = checkpointDao.getCheckpointsForProject(projectId)
        if (all.size <= maxCheckpointsPerProject) return
        val toRemove = all.drop(maxCheckpointsPerProject)
        toRemove.forEach { old ->
            checkpointDao.deleteCheckpointById(old.id)
            runCatching { File(old.snapshotPath).deleteRecursively() }
        }
    }

    private fun collectWorkspaceFiles(root: File): Map<String, File> {
        val map = linkedMapOf<String, File>()
        fun walk(dir: File, depth: Int) {
            if (depth > 8 || map.size >= MAX_SNAPSHOT_FILES) return
            val children = dir.listFiles()?.sortedBy { it.name.lowercase() }.orEmpty()
            for (child in children) {
                if (map.size >= MAX_SNAPSHOT_FILES) break
                val rel = runCatching {
                    child.canonicalFile.relativeTo(root).path.replace('\\', '/')
                }.getOrNull() ?: continue
                if (ProjectPathValidator.resolveWithinRoot(root, rel).isFailure) continue
                if (child.name in EXCLUDED_DIRS) continue
                if (child.isDirectory) {
                    walk(child, depth + 1)
                } else if (child.isFile) {
                    map[rel] = child
                }
            }
        }
        walk(root, 0)
        return map
    }

    private data class ManifestFileEntry(val path: String, val sha256: String, val sizeBytes: Long)

    private fun readManifestEntries(manifestFile: File): Map<String, ManifestFileEntry> {
        val obj = JSONObject(manifestFile.readText())
        val arr = obj.optJSONArray("files") ?: return emptyMap()
        val result = linkedMapOf<String, ManifestFileEntry>()
        for (i in 0 until arr.length()) {
            val item = arr.optJSONObject(i) ?: continue
            val path = item.optString("path").ifBlank { continue }
            result[path] = ManifestFileEntry(
                path = path,
                sha256 = item.optString("sha256"),
                sizeBytes = item.optLong("size", 0L),
            )
        }
        return result
    }

    private fun renderSingleFileDiff(relPath: String, file: File, isAddition: Boolean): String {
        if (RepositoryInspector.isSecretOrExcludedFile(relPath)) {
            return if (isAddition) {
                "--- /dev/null\n+++ b/$relPath\n[REDACTED SECRET FILE]"
            } else {
                "--- a/$relPath\n+++ /dev/null\n[REDACTED SECRET FILE]"
            }
        }
        if (file.length() > MAX_DIFF_FILE_BYTES || isLikelyBinary(file)) {
            return if (isAddition) {
                "--- /dev/null\n+++ b/$relPath\n(Binary or large file, ${file.length()} bytes)"
            } else {
                "--- a/$relPath\n+++ /dev/null\n(Binary or large file, ${file.length()} bytes)"
            }
        }
        val lines = runCatching { file.readLines() }.getOrDefault(emptyList()).take(MAX_DIFF_LINES)
        val prefix = if (isAddition) "+" else "-"
        return buildString {
            if (isAddition) {
                appendLine("--- /dev/null")
                appendLine("+++ b/$relPath")
            } else {
                appendLine("--- a/$relPath")
                appendLine("+++ /dev/null")
            }
            lines.forEach { appendLine("$prefix$it") }
        }.trim()
    }

    private fun renderModifiedDiff(relPath: String, beforeFile: File?, afterFile: File): String {
        if (RepositoryInspector.isSecretOrExcludedFile(relPath)) {
            return "--- a/$relPath\n+++ b/$relPath\n[REDACTED SECRET FILE]"
        }
        if ((beforeFile?.length() ?: 0L) > MAX_DIFF_FILE_BYTES ||
            afterFile.length() > MAX_DIFF_FILE_BYTES ||
            (beforeFile != null && isLikelyBinary(beforeFile)) ||
            isLikelyBinary(afterFile)
        ) {
            return "--- a/$relPath\n+++ b/$relPath\n(Binary or large file modified: ${beforeFile?.length() ?: 0} B -> ${afterFile.length()} B)"
        }
        val beforeLines = beforeFile?.let { runCatching { it.readLines() }.getOrNull() }.orEmpty()
        val afterLines = runCatching { afterFile.readLines() }.getOrDefault(emptyList())
        return computeCompactLineDiff(relPath, beforeLines, afterLines)
    }

    private fun computeCompactLineDiff(
        relPath: String,
        before: List<String>,
        after: List<String>,
    ): String {
        val sb = StringBuilder()
        sb.appendLine("--- a/$relPath")
        sb.appendLine("+++ b/$relPath")

        var prefix = 0
        while (prefix < before.size && prefix < after.size && before[prefix] == after[prefix]) {
            prefix++
        }
        var suffix = 0
        while (suffix < (before.size - prefix) &&
            suffix < (after.size - prefix) &&
            before[before.size - 1 - suffix] == after[after.size - 1 - suffix]
        ) {
            suffix++
        }

        val removed = before.subList(prefix, before.size - suffix)
        val added = after.subList(prefix, after.size - suffix)
        sb.appendLine("@@ -${prefix + 1},${removed.size} +${prefix + 1},${added.size} @@")
        var emitted = 0
        for (line in removed) {
            if (emitted++ >= MAX_DIFF_LINES) {
                sb.appendLine("... [diff truncated]")
                return sb.toString().trim()
            }
            sb.appendLine("-$line")
        }
        for (line in added) {
            if (emitted++ >= MAX_DIFF_LINES) {
                sb.appendLine("... [diff truncated]")
                return sb.toString().trim()
            }
            sb.appendLine("+$line")
        }
        return sb.toString().trim()
    }

    private fun isLikelyBinary(file: File): Boolean {
        return runCatching {
            file.inputStream().use { input ->
                val buf = ByteArray(512)
                val read = input.read(buf)
                for (i in 0 until read) {
                    if (buf[i] == 0.toByte()) return@use true
                }
                false
            }
        }.getOrDefault(false)
    }

    private fun cleanEmptyParents(dir: File?, stopRoot: File) {
        var current = dir
        while (current != null && current != stopRoot && current.exists() && current.isDirectory) {
            val children = current.listFiles()
            if (children != null && children.isEmpty()) {
                current.delete()
                current = current.parentFile
            } else {
                break
            }
        }
    }

    private fun readGitHead(workspaceRoot: File): String? {
        val head = File(workspaceRoot, ".git/HEAD")
        if (!head.isFile) return null
        return runCatching { head.readText().trim().take(40) }.getOrNull()
    }

    private fun sha256File(file: File): String {
        val digest = MessageDigest.getInstance("SHA-256")
        file.inputStream().use { input ->
            val buf = ByteArray(8192)
            while (true) {
                val read = input.read(buf)
                if (read < 0) break
                digest.update(buf, 0, read)
            }
        }
        return digest.digest().joinToString("") { "%02x".format(it) }
    }

    private companion object {
        const val MAX_SNAPSHOT_FILES = 500
        const val MAX_SNAPSHOT_BYTES = 25L * 1024 * 1024
        const val MAX_DIFF_FILE_BYTES = 128L * 1024
        const val MAX_DIFF_LINES = 60
        val EXCLUDED_DIRS = setOf(".git", ".gradle", "build", "node_modules", "target", "__pycache__", ".venv")
    }
}
