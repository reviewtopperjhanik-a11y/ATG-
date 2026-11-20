package com.example.data.project

import android.content.Context
import android.net.Uri
import android.provider.DocumentsContract
import com.example.core.common.ProjectNameValidation
import com.example.core.common.ProjectPathValidator
import com.example.core.common.WorkspacePathMapper
import com.example.data.database.CheckpointDao
import com.example.data.database.ProjectDao
import com.example.data.database.ProjectEntity
import com.example.data.database.TaskDao
import com.example.data.database.TaskEntity
import com.example.domain.contracts.ProjectFileStore
import com.example.domain.model.FileTreeEntry
import com.example.domain.model.ProjectSummary
import com.example.domain.model.TaskState
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.withContext
import java.io.File
import java.util.UUID

class ProjectRepository(
    private val context: Context,
    private val projectDao: ProjectDao,
    private val taskDao: TaskDao,
    private val checkpointDao: CheckpointDao,
) : ProjectFileStore {

    private val workspacesRoot: File
        get() = File(context.filesDir, "workspaces").apply { mkdirs() }

    fun observeRecentProjects(): Flow<List<ProjectSummary>> =
        projectDao.observeRecentProjects().map { list -> list.map { it.toSummary() } }

    fun observeProject(projectId: String): Flow<ProjectSummary?> =
        projectDao.observeProjectById(projectId).map { it?.toSummary() }

    fun observeProjectCount(): Flow<Int> = projectDao.observeProjectCount()

    fun observeTaskCount(): Flow<Int> = taskDao.observeTaskCount()

    fun observeCheckpointCount(): Flow<Int> = checkpointDao.observeCheckpointCount()

    fun observeTasks(projectId: String): Flow<List<TaskEntity>> =
        taskDao.observeTasksForProject(projectId)

    suspend fun createProject(rawName: String): Result<ProjectSummary> = withContext(Dispatchers.IO) {
        when (val validation = ProjectPathValidator.validateProjectName(rawName)) {
            is ProjectNameValidation.Invalid ->
                Result.failure(IllegalArgumentException(validation.reason))

            is ProjectNameValidation.Valid -> {
                val id = "${validation.folderSlug}-${UUID.randomUUID().toString().take(6)}"
                val projectDir = ProjectPathValidator.resolveWithinRoot(workspacesRoot, id)
                    .getOrElse { return@withContext Result.failure(it) }
                if (!projectDir.exists() && !projectDir.mkdirs()) {
                    return@withContext Result.failure(
                        IllegalStateException("Could not create workspace directory.")
                    )
                }
                val runtimeMount = WorkspacePathMapper.runtimeRootForProject(id)
                val now = System.currentTimeMillis()
                val entity = ProjectEntity(
                    id = id,
                    name = validation.sanitizedName,
                    workspacePath = projectDir.absolutePath,
                    runtimeMountPath = runtimeMount,
                    stackLabel = detectProjectStack(projectDir),
                    createdAtEpochMs = now,
                    lastOpenedEpochMs = now,
                )
                projectDao.upsertProject(entity)
                Result.success(entity.toSummary())
            }
        }
    }

    suspend fun importLocalDirectory(
        rawName: String,
        treeUri: Uri,
    ): Result<ProjectSummary> = withContext(Dispatchers.IO) {
        val validation = ProjectPathValidator.validateProjectName(rawName)
        if (validation is ProjectNameValidation.Invalid) {
            return@withContext Result.failure(IllegalArgumentException(validation.reason))
        }
        val valid = validation as ProjectNameValidation.Valid
        val id = "${valid.folderSlug}-${UUID.randomUUID().toString().take(6)}"
        val projectDir = ProjectPathValidator.resolveWithinRoot(workspacesRoot, id)
            .getOrElse { return@withContext Result.failure(it) }
        if (!projectDir.exists() && !projectDir.mkdirs()) {
            return@withContext Result.failure(
                IllegalStateException("Could not allocate imported workspace directory.")
            )
        }

        val copyResult = runCatching {
            val rootDocId = DocumentsContract.getTreeDocumentId(treeUri)
            copyDocumentTreeBounded(
                treeUri = treeUri,
                documentId = rootDocId,
                targetRoot = projectDir,
                relativeTarget = "",
                state = ImportCopyBudget(maxFiles = 500, maxTotalBytes = 50L * 1024 * 1024),
            )
        }
        if (copyResult.isFailure) {
            projectDir.deleteRecursively()
            return@withContext Result.failure(
                copyResult.exceptionOrNull() ?: IllegalStateException("Failed to import folder.")
            )
        }

        val now = System.currentTimeMillis()
        val entity = ProjectEntity(
            id = id,
            name = valid.sanitizedName,
            workspacePath = projectDir.absolutePath,
            runtimeMountPath = WorkspacePathMapper.runtimeRootForProject(id),
            stackLabel = detectProjectStack(projectDir),
            createdAtEpochMs = now,
            lastOpenedEpochMs = now,
        )
        projectDao.upsertProject(entity)
        Result.success(entity.toSummary())
    }

    suspend fun allocateCloneWorkspace(rawName: String): Result<Pair<String, File>> =
        withContext(Dispatchers.IO) {
            when (val validation = ProjectPathValidator.validateProjectName(rawName)) {
                is ProjectNameValidation.Invalid ->
                    Result.failure(IllegalArgumentException(validation.reason))
                is ProjectNameValidation.Valid -> {
                    val id = "${validation.folderSlug}-${UUID.randomUUID().toString().take(6)}"
                    val projectDir = ProjectPathValidator.resolveWithinRoot(workspacesRoot, id)
                        .getOrElse { return@withContext Result.failure(it) }
                    if (!projectDir.exists() && !projectDir.mkdirs()) {
                        return@withContext Result.failure(
                            IllegalStateException("Could not allocate clone workspace directory.")
                        )
                    }
                    Result.success(id to projectDir)
                }
            }
        }

    suspend fun finalizeClonedProject(
        projectId: String,
        rawName: String,
        workspaceDir: File,
        remoteUrl: String,
        branchName: String?,
    ): Result<ProjectSummary> = withContext(Dispatchers.IO) {
        val validation = ProjectPathValidator.validateProjectName(rawName)
        val displayName = (validation as? ProjectNameValidation.Valid)?.sanitizedName ?: rawName.trim()
        val now = System.currentTimeMillis()
        val entity = ProjectEntity(
            id = projectId,
            name = displayName,
            workspacePath = workspaceDir.canonicalPath,
            runtimeMountPath = WorkspacePathMapper.runtimeRootForProject(projectId),
            remoteUrl = remoteUrl,
            branchName = branchName,
            stackLabel = detectProjectStack(workspaceDir),
            createdAtEpochMs = now,
            lastOpenedEpochMs = now,
        )
        projectDao.upsertProject(entity)
        Result.success(entity.toSummary())
    }

    suspend fun cleanupFailedCloneWorkspace(workspaceDir: File) = withContext(Dispatchers.IO) {
        runCatching {
            val canonicalRoot = workspacesRoot.canonicalFile
            val target = workspaceDir.canonicalFile
            if (target != canonicalRoot && target.path.startsWith(canonicalRoot.path + File.separator)) {
                target.deleteRecursively()
            }
        }
    }

    suspend fun updateProjectGitMetadata(
        projectId: String,
        remoteUrl: String?,
        branchName: String?,
    ) = withContext(Dispatchers.IO) {
        projectDao.updateGitMetadata(projectId, remoteUrl, branchName)
    }

    suspend fun markOpened(projectId: String) = withContext(Dispatchers.IO) {
        val existing = projectDao.getProjectById(projectId) ?: return@withContext
        val dir = File(existing.workspacePath)
        if (!dir.exists()) {
            dir.mkdirs()
        }
        val detectedStack = detectProjectStack(dir)
        projectDao.updateOpenedMetadata(
            projectId = projectId,
            timestampMs = System.currentTimeMillis(),
            stackLabel = detectedStack,
        )
    }

    suspend fun recordSubmittedTask(
        projectId: String,
        prompt: String,
        modelId: String?,
        state: TaskState,
        taskId: String = UUID.randomUUID().toString(),
    ): String = withContext(Dispatchers.IO) {
        val now = System.currentTimeMillis()
        taskDao.upsertTask(
            TaskEntity(
                id = taskId,
                projectId = projectId,
                prompt = prompt,
                state = state.name,
                modelId = modelId,
                createdAtEpochMs = now,
                updatedAtEpochMs = now,
            )
        )
        taskId
    }

    suspend fun updateTaskState(taskId: String, state: TaskState) =
        withContext(Dispatchers.IO) {
            taskDao.updateTaskState(
                taskId = taskId,
                state = state.name,
                updatedAtEpochMs = System.currentTimeMillis(),
            )
        }

    suspend fun completeTaskWithSummary(
        taskId: String,
        summary: com.example.domain.model.TaskExecutionSummary,
    ) = withContext(Dispatchers.IO) {
        val json = encodeTaskSummary(summary)
        taskDao.updateTaskCompletion(
            taskId = taskId,
            state = summary.finalState.name,
            summaryJson = json,
            updatedAtEpochMs = summary.completedAtEpochMs,
        )
    }

    suspend fun getTaskById(taskId: String): TaskEntity? = withContext(Dispatchers.IO) {
        taskDao.getTaskById(taskId)
    }

    suspend fun recoverInterruptedTasks(projectId: String): Int = withContext(Dispatchers.IO) {
        taskDao.markActiveTasksPaused(
            projectId = projectId,
            updatedAtEpochMs = System.currentTimeMillis(),
        )
    }

    fun decodeTaskSummary(summaryJson: String?): com.example.domain.model.TaskExecutionSummary? {
        if (summaryJson.isNullOrBlank()) return null
        return runCatching {
            val obj = org.json.JSONObject(summaryJson)
            val filesArr = obj.optJSONArray("filesChanged") ?: org.json.JSONArray()
            val files = (0 until filesArr.length()).mapNotNull { filesArr.optString(it).takeIf(String::isNotBlank) }
            val checksArr = obj.optJSONArray("checksExecuted") ?: org.json.JSONArray()
            val checks = (0 until checksArr.length()).mapNotNull { idx ->
                val c = checksArr.optJSONObject(idx) ?: return@mapNotNull null
                com.example.domain.model.ValidationCheckResult(
                    commandId = c.optString("commandId"),
                    label = c.optString("label"),
                    command = c.optString("command"),
                    status = runCatching {
                        com.example.domain.model.ValidationOutcome.valueOf(c.optString("status"))
                    }.getOrDefault(com.example.domain.model.ValidationOutcome.SKIPPED),
                    exitCode = if (c.has("exitCode") && !c.isNull("exitCode")) c.optInt("exitCode") else null,
                    durationMs = c.optLong("durationMs", 0L),
                    outputExcerpt = c.optString("outputExcerpt"),
                )
            }
            val issuesArr = obj.optJSONArray("unresolvedIssues") ?: org.json.JSONArray()
            val issues = (0 until issuesArr.length()).mapNotNull { issuesArr.optString(it).takeIf(String::isNotBlank) }
            com.example.domain.model.TaskExecutionSummary(
                taskId = obj.optString("taskId"),
                objective = obj.optString("objective"),
                finalState = runCatching { TaskState.valueOf(obj.optString("finalState")) }
                    .getOrDefault(TaskState.COMPLETED),
                filesChanged = files,
                checksExecuted = checks,
                fixAttempts = obj.optInt("fixAttempts", 0),
                checkpointId = obj.optString("checkpointId").ifBlank { null },
                gitStatusNote = obj.optString("gitStatusNote", "Local workspace"),
                unresolvedIssues = issues,
                completedAtEpochMs = obj.optLong("completedAtEpochMs", 0L),
            )
        }.getOrNull()
    }

    private fun encodeTaskSummary(summary: com.example.domain.model.TaskExecutionSummary): String {
        val obj = org.json.JSONObject()
            .put("taskId", summary.taskId)
            .put("objective", summary.objective)
            .put("finalState", summary.finalState.name)
            .put("fixAttempts", summary.fixAttempts)
            .put("checkpointId", summary.checkpointId ?: "")
            .put("gitStatusNote", summary.gitStatusNote)
            .put("completedAtEpochMs", summary.completedAtEpochMs)
        val filesArr = org.json.JSONArray()
        summary.filesChanged.forEach { filesArr.put(it) }
        obj.put("filesChanged", filesArr)
        val checksArr = org.json.JSONArray()
        summary.checksExecuted.forEach { c ->
            checksArr.put(
                org.json.JSONObject()
                    .put("commandId", c.commandId)
                    .put("label", c.label)
                    .put("command", c.command)
                    .put("status", c.status.name)
                    .put("exitCode", c.exitCode)
                    .put("durationMs", c.durationMs)
                    .put("outputExcerpt", c.outputExcerpt)
            )
        }
        obj.put("checksExecuted", checksArr)
        val issuesArr = org.json.JSONArray()
        summary.unresolvedIssues.forEach { issuesArr.put(it) }
        obj.put("unresolvedIssues", issuesArr)
        return obj.toString()
    }

    suspend fun deleteProject(projectId: String): Result<Unit> = withContext(Dispatchers.IO) {
        runCatching {
            val existing = projectDao.getProjectById(projectId)
            projectDao.deleteProjectById(projectId)
            if (existing != null) {
                val targetDir = ProjectPathValidator.resolveWithinRoot(
                    workspacesRoot,
                    File(existing.workspacePath).name,
                ).getOrNull()
                if (targetDir != null && targetDir.exists() && targetDir != workspacesRoot.canonicalFile) {
                    targetDir.deleteRecursively()
                }
            }
            Unit
        }
    }

    suspend fun calculateWorkspaceStorageBytes(): Long = withContext(Dispatchers.IO) {
        fun folderSize(file: File): Long {
            if (!file.exists()) return 0L
            if (file.isFile) return file.length()
            return file.listFiles()?.sumOf { folderSize(it) } ?: 0L
        }
        folderSize(workspacesRoot)
    }

    suspend fun createDirectory(projectRootPath: String, relativePath: String): Result<Unit> =
        withContext(Dispatchers.IO) {
            val root = File(projectRootPath)
            ProjectPathValidator.resolveWithinRoot(root, relativePath).mapCatching { dir ->
                if (!dir.exists() && !dir.mkdirs()) {
                    error("Unable to create directory: $relativePath")
                }
            }
        }

    override suspend fun list(projectRootPath: String, relativePath: String): Result<List<FileTreeEntry>> =
        withContext(Dispatchers.IO) {
            val root = File(projectRootPath)
            ProjectPathValidator.resolveWithinRoot(root, relativePath).map { targetDir ->
                if (!targetDir.exists() || !targetDir.isDirectory) {
                    emptyList()
                } else {
                    val canonicalRoot = root.canonicalFile
                    targetDir.listFiles()
                        .orEmpty()
                        .sortedWith(compareByDescending<File> { it.isDirectory }.thenBy { it.name.lowercase() })
                        .map { child ->
                            val rel = child.canonicalFile.relativeTo(canonicalRoot).path
                            FileTreeEntry(
                                name = child.name,
                                relativePath = rel,
                                isDirectory = child.isDirectory,
                                sizeBytes = if (child.isFile) child.length() else 0L,
                            )
                        }
                }
            }
        }

    override suspend fun readText(projectRootPath: String, relativePath: String): Result<String> =
        withContext(Dispatchers.IO) {
            val root = File(projectRootPath)
            ProjectPathValidator.resolveWithinRoot(root, relativePath).mapCatching { file ->
                if (!file.isFile) error("Not a readable file: $relativePath")
                file.readText()
            }
        }

    override suspend fun writeText(projectRootPath: String, relativePath: String, content: String): Result<Unit> =
        withContext(Dispatchers.IO) {
            val root = File(projectRootPath)
            ProjectPathValidator.resolveWithinRoot(root, relativePath).mapCatching { file ->
                file.parentFile?.mkdirs()
                file.writeText(content)
            }
        }

    override suspend fun delete(projectRootPath: String, relativePath: String): Result<Unit> =
        withContext(Dispatchers.IO) {
            val root = File(projectRootPath)
            ProjectPathValidator.resolveWithinRoot(root, relativePath).mapCatching { file ->
                if (file == root.canonicalFile) error("Cannot delete project root.")
                if (!file.deleteRecursively()) error("Failed to delete $relativePath")
            }
        }

    private fun detectProjectStack(projectDir: File): String {
        val names = projectDir.listFiles()?.map { it.name }?.toSet().orEmpty()
        return when {
            "build.gradle.kts" in names || "build.gradle" in names -> "Gradle / JVM"
            "package.json" in names -> "Node.js"
            "pyproject.toml" in names || "requirements.txt" in names -> "Python"
            "Cargo.toml" in names -> "Rust"
            "go.mod" in names -> "Go"
            "index.html" in names -> "Web"
            else -> "Local Workspace"
        }
    }

    private data class ImportCopyBudget(
        val maxFiles: Int,
        val maxTotalBytes: Long,
        var copiedFiles: Int = 0,
        var copiedBytes: Long = 0L,
    )

    private fun copyDocumentTreeBounded(
        treeUri: Uri,
        documentId: String,
        targetRoot: File,
        relativeTarget: String,
        state: ImportCopyBudget,
        depth: Int = 0,
    ) {
        if (depth > 12) return
        val childrenUri = DocumentsContract.buildChildDocumentsUriUsingTree(treeUri, documentId)
        val projection = arrayOf(
            DocumentsContract.Document.COLUMN_DOCUMENT_ID,
            DocumentsContract.Document.COLUMN_DISPLAY_NAME,
            DocumentsContract.Document.COLUMN_MIME_TYPE,
            DocumentsContract.Document.COLUMN_SIZE,
        )
        context.contentResolver.query(childrenUri, projection, null, null, null)?.use { cursor ->
            val idIdx = cursor.getColumnIndexOrThrow(DocumentsContract.Document.COLUMN_DOCUMENT_ID)
            val nameIdx = cursor.getColumnIndexOrThrow(DocumentsContract.Document.COLUMN_DISPLAY_NAME)
            val mimeIdx = cursor.getColumnIndexOrThrow(DocumentsContract.Document.COLUMN_MIME_TYPE)
            while (cursor.moveToNext()) {
                val childId = cursor.getString(idIdx)
                val childName = cursor.getString(nameIdx) ?: continue
                val mime = cursor.getString(mimeIdx) ?: ""
                if (childName == "." || childName == ".." || childName.contains('/') || childName.contains('\\')) {
                    continue
                }
                if (childName == ".git" || childName == "node_modules" || childName == "build") {
                    continue
                }
                val childRel = if (relativeTarget.isEmpty()) childName else "$relativeTarget/$childName"
                val destFile = ProjectPathValidator.resolveWithinRoot(targetRoot, childRel).getOrNull()
                    ?: continue

                if (mime == DocumentsContract.Document.MIME_TYPE_DIR) {
                    destFile.mkdirs()
                    copyDocumentTreeBounded(
                        treeUri = treeUri,
                        documentId = childId,
                        targetRoot = targetRoot,
                        relativeTarget = childRel,
                        state = state,
                        depth = depth + 1,
                    )
                } else {
                    if (state.copiedFiles >= state.maxFiles) {
                        error("Import limit exceeded (max ${state.maxFiles} files).")
                    }
                    val docUri = DocumentsContract.buildDocumentUriUsingTree(treeUri, childId)
                    context.contentResolver.openInputStream(docUri)?.use { input ->
                        destFile.parentFile?.mkdirs()
                        destFile.outputStream().use { output ->
                            val buffer = ByteArray(8192)
                            var read = input.read(buffer)
                            while (read >= 0) {
                                state.copiedBytes += read
                                if (state.copiedBytes > state.maxTotalBytes) {
                                    error("Import size limit exceeded (50 MB max).")
                                }
                                output.write(buffer, 0, read)
                                read = input.read(buffer)
                            }
                        }
                    }
                    state.copiedFiles += 1
                }
            }
        }
    }

    private fun ProjectEntity.toSummary(): ProjectSummary = ProjectSummary(
        id = id,
        name = name,
        workspacePath = workspacePath,
        runtimeMountPath = runtimeMountPath,
        remoteUrl = remoteUrl,
        branchName = branchName,
        lastOpenedEpochMs = lastOpenedEpochMs,
        stackLabel = stackLabel,
    )
}
