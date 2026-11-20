package com.example.domain.model

data class GitHubRepoSummary(
    val id: Long,
    val name: String,
    val fullName: String,
    val ownerLogin: String,
    val description: String?,
    val cloneUrl: String,
    val htmlUrl: String,
    val defaultBranch: String,
    val isPrivate: Boolean,
    val updatedAtIso: String,
)

data class GitHubPullRequestResult(
    val number: Int,
    val title: String,
    val htmlUrl: String,
    val state: String,
    val headBranch: String,
    val baseBranch: String,
)

data class GitHubOAuthRequest(
    val authorizationUrl: String,
    val state: String,
    val redirectUri: String,
    val scopes: List<String>,
)

data class GitHubDeviceCodePrompt(
    val deviceCode: String,
    val userCode: String,
    val verificationUri: String,
    val verificationUriComplete: String? = null,
    val expiresInSeconds: Int = 900,
    val intervalSeconds: Int = 5,
    val scopes: List<String> = emptyList(),
)

data class GitChangedFile(
    val relativePath: String,
    val statusCode: String,
    val isStaged: Boolean,
    val isUntracked: Boolean,
    val isSensitive: Boolean = false,
)

data class GitWorkspaceStatus(
    val gitAvailable: Boolean = true,
    val isGitRepo: Boolean = false,
    val branchName: String? = null,
    val remoteUrl: String? = null,
    val aheadCount: Int = 0,
    val behindCount: Int = 0,
    val changedFiles: List<GitChangedFile> = emptyList(),
    val diffText: String = "",
    val statusError: String? = null,
) {
    val hasChanges: Boolean get() = changedFiles.isNotEmpty()
}

data class GitCommitResult(
    val commitHash: String,
    val branchName: String,
    val message: String,
    val filesCommittedCount: Int,
)

data class GitPushResult(
    val remoteName: String,
    val branchName: String,
    val outputSummary: String,
)

data class GitCloneOutcome(
    val workspacePath: String,
    val remoteUrl: String,
    val branchName: String,
)
