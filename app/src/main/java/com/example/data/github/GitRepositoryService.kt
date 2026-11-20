package com.example.data.github

import com.example.agent.orchestrator.RepositoryInspector
import com.example.core.common.AgtError
import com.example.core.common.ProjectPathValidator
import com.example.core.security.GitHubCredentialVault
import com.example.core.security.SecretRedactor
import com.example.data.preferences.AppPreferencesRepository
import com.example.data.project.ProjectRepository
import com.example.data.runtime.RuntimeInstallerRepository
import com.example.domain.contracts.ProcessEvent
import com.example.domain.contracts.RuntimeCommand
import com.example.domain.contracts.RuntimeExecutor
import com.example.domain.model.GitChangedFile
import com.example.domain.model.GitCommitResult
import com.example.domain.model.GitPushResult
import com.example.domain.model.GitWorkspaceStatus
import com.example.domain.model.ProjectSummary
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext
import java.io.File
import java.util.UUID

/**
 * Executes real local Git and remote GitHub Git operations through [RuntimeExecutor]
 * inside confined workspace directories, keeping credentials out of `.git/config`,
 * shell history, and logs.
 */
class GitRepositoryService(
    private val runtimeExecutor: RuntimeExecutor,
    private val runtimeInstallerRepository: RuntimeInstallerRepository,
    private val projectRepository: ProjectRepository,
    private val credentialVault: GitHubCredentialVault,
    private val preferencesRepository: AppPreferencesRepository,
) {
    private data class CommandResult(
        val exitCode: Int?,
        val stdout: String,
        val stderr: String,
        val failureError: AgtError? = null,
    ) {
        val isSuccess: Boolean get() = failureError == null && exitCode == 0
        val combinedOutput: String
            get() = listOf(stdout.trim(), stderr.trim()).filter { it.isNotEmpty() }.joinToString("\n")
    }

    suspend fun cloneRepository(
        rawProjectName: String,
        remoteUrl: String,
        branchName: String? = null,
        allowFileSchemeForTesting: Boolean = false,
    ): Result<ProjectSummary> = withContext(Dispatchers.IO) {
        val readiness = runtimeExecutor.ensureReady()
        if (!readiness.isInstalled) {
            return@withContext Result.failure(
                IllegalStateException("Linux runtime is not installed. Complete Setup or Settings → Runtime before cloning.")
            )
        }

        val cleanRemoteUrl = if (allowFileSchemeForTesting && remoteUrl.trim().startsWith("file://")) {
            remoteUrl.trim()
        } else {
            when (val urlCheck = GitHubApiClient.validateGitRemoteUrl(remoteUrl)) {
                is GitRemoteUrlValidation.Invalid ->
                    return@withContext Result.failure(IllegalArgumentException(urlCheck.reason))
                is GitRemoteUrlValidation.Valid -> urlCheck.cleanCloneUrl
            }
        }

        val cleanBranch = branchName?.trim()?.takeIf { it.isNotEmpty() }
        if (cleanBranch != null && !isValidBranchName(cleanBranch)) {
            return@withContext Result.failure(IllegalArgumentException("Invalid branch name: $cleanBranch"))
        }

        val (projectId, workspaceDir) = projectRepository.allocateCloneWorkspace(rawProjectName)
            .getOrElse { return@withContext Result.failure(it) }

        val token = credentialVault.readToken().getOrNull()
        val secrets = listOfNotNull(token)

        var gitCheck = runGitCommand(
            workingDirectory = workspaceDir.absolutePath,
            shellCommand = "git --version",
            secrets = secrets,
        )
        if (!gitCheck.isSuccess) {
            val installRes = runtimeInstallerRepository.ensureGitToolchain()
            if (installRes.isSuccess) {
                gitCheck = runGitCommand(
                    workingDirectory = workspaceDir.absolutePath,
                    shellCommand = "git --version",
                    secrets = secrets,
                )
            }
        }
        if (!gitCheck.isSuccess) {
            projectRepository.cleanupFailedCloneWorkspace(workspaceDir)
            return@withContext Result.failure(
                IllegalStateException(
                    "Git CLI is not available in the Linux runtime (${gitCheck.combinedOutput.ifBlank { gitCheck.failureError?.userMessage ?: "not found" }})."
                )
            )
        }

        val branchFlag = if (cleanBranch != null) "--branch ${posixQuote(cleanBranch)} " else ""
        val cloneRes = runAuthenticatedGitCommand(
            workingDirectory = workspaceDir.absolutePath,
            gitCommand = "git clone --progress $branchFlag-- ${posixQuote(cleanRemoteUrl)} .",
            token = token,
            timeoutSeconds = 180,
        )

        if (!cloneRes.isSuccess) {
            projectRepository.cleanupFailedCloneWorkspace(workspaceDir)
            val errMsg = SecretRedactor.redact(
                cloneRes.combinedOutput.ifBlank { cloneRes.failureError?.userMessage ?: "git clone failed." },
                secrets,
            )
            return@withContext Result.failure(IllegalStateException(errMsg))
        }

        val headBranchRes = runGitCommand(
            workingDirectory = workspaceDir.absolutePath,
            shellCommand = "git rev-parse --abbrev-ref HEAD",
            secrets = secrets,
        )
        val actualBranch = headBranchRes.stdout.trim().takeIf { it.isNotEmpty() && it != "HEAD" }
            ?: cleanBranch
            ?: preferencesRepository.preferences.first().gitDefaultBranch

        projectRepository.finalizeClonedProject(
            projectId = projectId,
            rawName = rawProjectName,
            workspaceDir = workspaceDir,
            remoteUrl = GitHubApiClient.stripUrlCredentials(cleanRemoteUrl),
            branchName = actualBranch,
        )
    }

    suspend fun initRepository(
        projectId: String,
        projectRootPath: String,
    ): Result<String> = withContext(Dispatchers.IO) {
        val prefs = preferencesRepository.preferences.first()
        val branch = prefs.gitDefaultBranch.takeIf(::isValidBranchName) ?: "main"
        val gitCheck = runGitCommand(
            workingDirectory = projectRootPath,
            shellCommand = "git --version",
        )
        if (!gitCheck.isSuccess) {
            runtimeInstallerRepository.ensureGitToolchain()
        }
        val res = runGitCommand(
            workingDirectory = projectRootPath,
            shellCommand = "git init && git checkout -B ${posixQuote(branch)}",
        )
        if (!res.isSuccess) {
            return@withContext Result.failure(
                IllegalStateException(res.combinedOutput.ifBlank { res.failureError?.userMessage ?: "git init failed." })
            )
        }
        projectRepository.updateProjectGitMetadata(projectId, remoteUrl = null, branchName = branch)
        Result.success(branch)
    }

    suspend fun inspectStatus(projectRootPath: String): GitWorkspaceStatus =
        withContext(Dispatchers.IO) {
            val readiness = runtimeExecutor.ensureReady()
            if (!readiness.isInstalled) {
                return@withContext GitWorkspaceStatus(
                    gitAvailable = false,
                    statusError = "Linux runtime is not installed. Install runtime in Settings → Runtime to use Git.",
                )
            }

            val rootDir = File(projectRootPath)
            if (!rootDir.exists() || !rootDir.isDirectory) {
                return@withContext GitWorkspaceStatus(
                    gitAvailable = false,
                    statusError = "Project directory not found.",
                )
            }

            val token = credentialVault.readToken().getOrNull()
            val secrets = listOfNotNull(token)

            val versionRes = runGitCommand(projectRootPath, "git --version", secrets = secrets)
            if (!versionRes.isSuccess) {
                return@withContext GitWorkspaceStatus(
                    gitAvailable = false,
                    statusError = "Git CLI is unavailable inside the Linux runtime.",
                )
            }

            val insideRes = runGitCommand(projectRootPath, "git rev-parse --is-inside-work-tree", secrets = secrets)
            if (!insideRes.isSuccess || insideRes.stdout.trim() != "true") {
                return@withContext GitWorkspaceStatus(
                    gitAvailable = true,
                    isGitRepo = false,
                )
            }

            val branchRes = runGitCommand(projectRootPath, "git rev-parse --abbrev-ref HEAD", secrets = secrets)
            val branch = branchRes.stdout.trim().takeIf { it.isNotEmpty() }

            val remoteRes = runGitCommand(projectRootPath, "git remote get-url origin", secrets = secrets)
            val remoteUrl = if (remoteRes.isSuccess) {
                GitHubApiClient.stripUrlCredentials(remoteRes.stdout.trim()).takeIf { it.isNotEmpty() }
            } else {
                null
            }

            var ahead = 0
            var behind = 0
            val countRes = runGitCommand(
                projectRootPath,
                "git rev-list --left-right --count HEAD...@{upstream} 2>/dev/null",
                secrets = secrets,
            )
            if (countRes.isSuccess) {
                val parts = countRes.stdout.trim().split(Regex("\\s+"))
                if (parts.size == 2) {
                    ahead = parts[0].toIntOrNull() ?: 0
                    behind = parts[1].toIntOrNull() ?: 0
                }
            }

            val porcelainRes = runGitCommand(
                projectRootPath,
                "git status --porcelain=v1 -uall",
                secrets = secrets,
            )
            val changedFiles = parsePorcelainStatus(porcelainRes.stdout)

            val stagedDiffRes = runGitCommand(projectRootPath, "git diff --cached --no-ext-diff", secrets = secrets)
            val workDiffRes = runGitCommand(projectRootPath, "git diff --no-ext-diff", secrets = secrets)
            val combinedDiff = buildString {
                if (stagedDiffRes.stdout.isNotBlank()) {
                    appendLine("# Staged changes")
                    appendLine(stagedDiffRes.stdout.trimEnd())
                }
                if (workDiffRes.stdout.isNotBlank()) {
                    if (isNotEmpty()) appendLine()
                    appendLine("# Unstaged changes")
                    appendLine(workDiffRes.stdout.trimEnd())
                }
            }.let { SecretRedactor.redact(it, secrets).take(MAX_DIFF_CHARS) }

            GitWorkspaceStatus(
                gitAvailable = true,
                isGitRepo = true,
                branchName = branch,
                remoteUrl = remoteUrl,
                aheadCount = ahead,
                behindCount = behind,
                changedFiles = changedFiles,
                diffText = combinedDiff,
            )
        }

    suspend fun commitChanges(
        projectId: String,
        projectRootPath: String,
        commitMessage: String,
        excludeSensitiveFiles: Boolean = true,
    ): Result<GitCommitResult> = withContext(Dispatchers.IO) {
        val cleanMsg = commitMessage.trim()
        if (cleanMsg.isEmpty()) {
            return@withContext Result.failure(IllegalArgumentException("Commit message cannot be empty."))
        }
        val prefs = preferencesRepository.preferences.first()
        val authorName = prefs.gitAuthorName.trim()
        val authorEmail = prefs.gitAuthorEmail.trim()
        if (authorName.isEmpty() || authorEmail.isEmpty()) {
            return@withContext Result.failure(
                IllegalStateException("Configure Git Author Name and Email in Settings → GitHub before committing.")
            )
        }

        val status = inspectStatus(projectRootPath)
        if (!status.gitAvailable || !status.isGitRepo) {
            return@withContext Result.failure(
                IllegalStateException(status.statusError ?: "Workspace is not a Git repository.")
            )
        }
        if (status.changedFiles.isEmpty()) {
            return@withContext Result.failure(IllegalStateException("No local changes to commit."))
        }

        val filesToStage = if (excludeSensitiveFiles) {
            status.changedFiles.filterNot { it.isSensitive }
        } else {
            status.changedFiles
        }
        if (filesToStage.isEmpty()) {
            return@withContext Result.failure(
                IllegalStateException("Only sensitive credential/secret files are modified; staging blocked by policy.")
            )
        }

        val rootFile = File(projectRootPath).canonicalFile
        val safePaths = filesToStage.mapNotNull { entry ->
            if (ProjectPathValidator.resolveWithinRoot(rootFile, entry.relativePath).isSuccess) {
                entry.relativePath
            } else {
                null
            }
        }
        if (safePaths.isEmpty()) {
            return@withContext Result.failure(IllegalStateException("No valid confined file paths to stage."))
        }

        val quotedPaths = safePaths.joinToString(" ") { posixQuote(it) }
        val addRes = runGitCommand(projectRootPath, "git add -- $quotedPaths")
        if (!addRes.isSuccess) {
            return@withContext Result.failure(
                IllegalStateException(addRes.combinedOutput.ifBlank { "git add failed." })
            )
        }

        val tmpDir = resolveRuntimeTmpDir()
        val msgFileName = "agt-commit-${UUID.randomUUID().toString().take(8)}.txt"
        val hostMsgFile = File(tmpDir, msgFileName)
        hostMsgFile.writeText("$cleanMsg\n")
        val chrootMsgPath = "/tmp/$msgFileName"

        val commitRes = try {
            val cmd = "MSG_FILE=${posixQuote(chrootMsgPath)}; " +
                "if [ ! -f \"\$MSG_FILE\" ]; then MSG_FILE=\"\$AGT_RUNTIME_ROOT/tmp/$msgFileName\"; fi; " +
                "git -c user.name=${posixQuote(authorName)} -c user.email=${posixQuote(authorEmail)} commit -F \"\$MSG_FILE\""
            runGitCommand(
                workingDirectory = projectRootPath,
                shellCommand = cmd,
                environment = mapOf(
                    "GIT_AUTHOR_NAME" to authorName,
                    "GIT_AUTHOR_EMAIL" to authorEmail,
                    "GIT_COMMITTER_NAME" to authorName,
                    "GIT_COMMITTER_EMAIL" to authorEmail,
                ),
            )
        } finally {
            hostMsgFile.delete()
        }

        if (!commitRes.isSuccess) {
            return@withContext Result.failure(
                IllegalStateException(commitRes.combinedOutput.ifBlank { "git commit failed." })
            )
        }

        val hashRes = runGitCommand(projectRootPath, "git rev-parse --short HEAD")
        val hash = hashRes.stdout.trim().ifBlank { "HEAD" }
        val branch = status.branchName ?: prefs.gitDefaultBranch
        projectRepository.updateProjectGitMetadata(projectId, status.remoteUrl, branch)

        Result.success(
            GitCommitResult(
                commitHash = hash,
                branchName = branch,
                message = cleanMsg,
                filesCommittedCount = safePaths.size,
            )
        )
    }

    suspend fun pushBranch(
        projectId: String,
        projectRootPath: String,
        remoteName: String = "origin",
        branchName: String? = null,
        userApproved: Boolean,
    ): Result<GitPushResult> = withContext(Dispatchers.IO) {
        val prefs = preferencesRepository.preferences.first()
        if (prefs.requirePushApproval && !userApproved) {
            return@withContext Result.failure(
                IllegalStateException("Push requires explicit user approval per security policy.")
            )
        }

        val status = inspectStatus(projectRootPath)
        if (!status.gitAvailable || !status.isGitRepo) {
            return@withContext Result.failure(
                IllegalStateException(status.statusError ?: "Workspace is not a Git repository.")
            )
        }
        val targetBranch = branchName?.trim()?.takeIf { it.isNotEmpty() }
            ?: status.branchName
            ?: prefs.gitDefaultBranch
        if (!isValidBranchName(targetBranch) || !isValidBranchName(remoteName)) {
            return@withContext Result.failure(IllegalArgumentException("Invalid remote or branch name."))
        }
        if (status.remoteUrl.isNullOrBlank()) {
            return@withContext Result.failure(
                IllegalStateException("No 'origin' remote configured for this repository.")
            )
        }

        val token = credentialVault.readToken().getOrNull()
        val secrets = listOfNotNull(token)
        val pushRes = runAuthenticatedGitCommand(
            workingDirectory = projectRootPath,
            gitCommand = "git push -u ${posixQuote(remoteName)} ${posixQuote(targetBranch)}",
            token = token,
            timeoutSeconds = 120,
        )
        if (!pushRes.isSuccess) {
            val safeError = SecretRedactor.redact(
                pushRes.combinedOutput.ifBlank { pushRes.failureError?.userMessage ?: "git push failed." },
                secrets,
            )
            return@withContext Result.failure(IllegalStateException(safeError))
        }

        projectRepository.updateProjectGitMetadata(projectId, status.remoteUrl, targetBranch)
        val safeSummary = SecretRedactor.redact(
            pushRes.combinedOutput.ifBlank { "Pushed $targetBranch to $remoteName." },
            secrets,
        )
        Result.success(
            GitPushResult(
                remoteName = remoteName,
                branchName = targetBranch,
                outputSummary = safeSummary,
            )
        )
    }

    private suspend fun runAuthenticatedGitCommand(
        workingDirectory: String,
        gitCommand: String,
        token: String?,
        timeoutSeconds: Int,
    ): CommandResult {
        val secrets = listOfNotNull(token)
        if (token.isNullOrBlank()) {
            return runGitCommand(
                workingDirectory = workingDirectory,
                shellCommand = gitCommand,
                environment = mapOf("GIT_TERMINAL_PROMPT" to "0"),
                timeoutSeconds = timeoutSeconds,
                secrets = secrets,
            )
        }

        val tmpDir = resolveRuntimeTmpDir()
        val suffix = UUID.randomUUID().toString().take(8)
        val scriptName = "agt-askpass-$suffix.sh"
        val secretFileName = "agt-cred-$suffix.dat"
        val hostScript = File(tmpDir, scriptName)
        val hostSecret = File(tmpDir, secretFileName)
        return try {
            hostSecret.writeText(token)
            hostSecret.setReadable(false, false)
            hostSecret.setReadable(true, true)

            val chrootSecret = "/tmp/$secretFileName"
            val chrootScript = "/tmp/$scriptName"
            val askpassBody = """
                #!/bin/sh
                case "${'$'}1" in
                  *Username*|*username*)
                    echo "x-access-token"
                    ;;
                  *)
                    if [ -f "$chrootSecret" ]; then
                      cat "$chrootSecret"
                    else
                      cat "${'$'}AGT_RUNTIME_ROOT/tmp/$secretFileName"
                    fi
                    ;;
                esac
            """.trimIndent() + "\n"
            hostScript.writeText(askpassBody)
            hostScript.setReadable(false, false)
            hostScript.setReadable(true, true)
            hostScript.setExecutable(false, false)
            hostScript.setExecutable(true, true)

            val wrapped = "if [ -x ${posixQuote(chrootScript)} ]; then " +
                "export GIT_ASKPASS=${posixQuote(chrootScript)}; " +
                "else export GIT_ASKPASS=\"\$AGT_RUNTIME_ROOT/tmp/$scriptName\"; fi; " +
                "export GIT_TERMINAL_PROMPT=0; $gitCommand"

            runGitCommand(
                workingDirectory = workingDirectory,
                shellCommand = wrapped,
                environment = mapOf("GIT_TERMINAL_PROMPT" to "0"),
                timeoutSeconds = timeoutSeconds,
                secrets = secrets,
            )
        } finally {
            hostScript.delete()
            hostSecret.delete()
        }
    }

    private suspend fun runGitCommand(
        workingDirectory: String,
        shellCommand: String,
        environment: Map<String, String> = emptyMap(),
        timeoutSeconds: Int = 60,
        secrets: Collection<String> = emptyList(),
    ): CommandResult {
        val stdout = StringBuilder()
        val stderr = StringBuilder()
        var exitCode: Int? = null
        var failure: AgtError? = null

        runtimeExecutor.execute(
            RuntimeCommand(
                command = shellCommand,
                workingDirectory = workingDirectory,
                timeoutSeconds = timeoutSeconds,
                processId = "git-${UUID.randomUUID().toString().take(8)}",
                environment = environment + ("GIT_TERMINAL_PROMPT" to "0"),
                maxOutputLines = 2000,
            )
        ).collect { ev ->
            when (ev) {
                is ProcessEvent.Started -> Unit
                is ProcessEvent.Stdout -> stdout.appendLine(SecretRedactor.redact(ev.line, secrets))
                is ProcessEvent.Stderr -> stderr.appendLine(SecretRedactor.redact(ev.line, secrets))
                is ProcessEvent.Exited -> exitCode = ev.exitCode
                is ProcessEvent.Failed -> failure = ev.error
            }
        }
        return CommandResult(
            exitCode = exitCode,
            stdout = stdout.toString(),
            stderr = stderr.toString(),
            failureError = failure,
        )
    }

    private fun resolveRuntimeTmpDir(): File {
        val activeDir = runtimeInstallerRepository.activeDir
        val tmpDir = File(activeDir, "tmp")
        if (!tmpDir.exists()) tmpDir.mkdirs()
        return tmpDir
    }

    private fun parsePorcelainStatus(rawOutput: String): List<GitChangedFile> {
        return rawOutput.lineSequence()
            .filter { it.length >= 4 }
            .mapNotNull { line ->
                val x = line[0]
                val y = line[1]
                val pathPart = line.substring(3).trim()
                    .let { if (" -> " in it) it.substringAfterLast(" -> ") else it }
                    .removeSurrounding("\"")
                if (pathPart.isBlank()) return@mapNotNull null
                val untracked = x == '?' && y == '?'
                val staged = x != ' ' && x != '?'
                GitChangedFile(
                    relativePath = pathPart,
                    statusCode = "$x$y".trim().ifEmpty { "M" },
                    isStaged = staged,
                    isUntracked = untracked,
                    isSensitive = RepositoryInspector.isSecretOrExcludedFile(pathPart),
                )
            }
            .toList()
    }

    private fun isValidBranchName(name: String): Boolean {
        if (name.isBlank() || name.startsWith("-")) return false
        if (".." in name || " " in name || "\\" in name || ";" in name || "&" in name || "|" in name) {
            return false
        }
        return BRANCH_REGEX.matches(name)
    }

    private fun posixQuote(value: String): String =
        "'" + value.replace("'", "'\"'\"'") + "'"

    private companion object {
        const val MAX_DIFF_CHARS = 32_000
        val BRANCH_REGEX = Regex("^[A-Za-z0-9._/-]{1,120}$")
    }
}
