package com.example.feature.home

import android.content.Context
import android.net.Uri
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.example.core.common.AgtCrashHandler
import com.example.core.common.CrashReportSummary
import com.example.data.github.GitHubRepository
import com.example.data.github.GitRepositoryService
import com.example.data.preferences.AppPreferencesRepository
import com.example.data.project.ProjectRepository
import com.example.domain.model.GitHubAccountState
import com.example.domain.model.GitHubAuthStatus
import com.example.domain.model.GitHubRepoSummary
import com.example.domain.model.ProjectSummary
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

data class HomeUiState(
    val projects: List<ProjectSummary> = emptyList(),
    val showCreateDialog: Boolean = false,
    val showImportChooserDialog: Boolean = false,
    val showGitHubCloneDialog: Boolean = false,
    val pendingImportUri: Uri? = null,
    val projectNameDraft: String = "",
    val projectNameError: String? = null,
    val projectPendingDeletion: ProjectSummary? = null,
    val gitHubAccount: GitHubAccountState = GitHubAccountState(),
    val gitHubRepositories: List<GitHubRepoSummary> = emptyList(),
    val isLoadingGitHubRepos: Boolean = false,
    val gitHubRepoError: String? = null,
    val cloneUrlDraft: String = "",
    val cloneBranchDraft: String = "",
    val cloneProjectNameDraft: String = "",
    val cloneSearchQuery: String = "",
    val cloneError: String? = null,
    val isCloning: Boolean = false,
    val isBusy: Boolean = false,
    val infoBanner: String? = null,
    val errorBanner: String? = null,
    val lastCrashReport: CrashReportSummary? = null,
)

class HomeViewModel(
    private val appContext: Context,
    private val projectRepository: ProjectRepository,
    private val preferencesRepository: AppPreferencesRepository,
    private val gitHubRepository: GitHubRepository,
    private val gitRepositoryService: GitRepositoryService,
) : ViewModel() {

    private data class GitHubRepoFeed(
        val account: GitHubAccountState,
        val repos: List<GitHubRepoSummary>,
        val isLoading: Boolean,
        val error: String?,
    )

    private val transientState = MutableStateFlow(HomeUiState())

    private val gitHubFeedFlow = combine(
        gitHubRepository.accountState,
        gitHubRepository.repositories,
        gitHubRepository.isLoadingRepos,
        gitHubRepository.repoError,
    ) { account, repos, loading, error ->
        GitHubRepoFeed(account, repos, loading, error)
    }

    val uiState: StateFlow<HomeUiState> = combine(
        projectRepository.observeRecentProjects(),
        gitHubFeedFlow,
        transientState,
    ) { projects, gh, ui ->
        ui.copy(
            projects = projects,
            gitHubAccount = gh.account,
            gitHubRepositories = gh.repos,
            isLoadingGitHubRepos = gh.isLoading,
            gitHubRepoError = gh.error,
        )
    }.stateIn(
        scope = viewModelScope,
        started = SharingStarted.WhileSubscribed(5_000),
        initialValue = HomeUiState(gitHubAccount = gitHubRepository.accountState.value),
    )

    init {
        viewModelScope.launch {
            preferencesRepository.setOnboardingCompleted(true)
            val crash = AgtCrashHandler.readLastCrash(appContext)
            if (crash != null) {
                transientState.update { it.copy(lastCrashReport = crash) }
            }
        }
    }

    fun dismissLastCrashReport() {
        AgtCrashHandler.clearLastCrash(appContext)
        transientState.update { it.copy(lastCrashReport = null) }
    }

    fun openCreateDialog() {
        transientState.update {
            it.copy(
                showCreateDialog = true,
                pendingImportUri = null,
                projectNameDraft = "",
                projectNameError = null,
                errorBanner = null,
            )
        }
    }

    fun dismissCreateDialog() {
        transientState.update {
            it.copy(
                showCreateDialog = false,
                pendingImportUri = null,
                projectNameError = null,
            )
        }
    }

    fun updateProjectNameDraft(value: String) {
        transientState.update { it.copy(projectNameDraft = value, projectNameError = null) }
    }

    fun confirmCreateOrImportProject(onProjectReady: (String) -> Unit) {
        val draft = transientState.value.projectNameDraft
        val importUri = transientState.value.pendingImportUri
        viewModelScope.launch {
            transientState.update { it.copy(isBusy = true) }
            val result = if (importUri != null) {
                projectRepository.importLocalDirectory(draft, importUri)
            } else {
                projectRepository.createProject(draft)
            }
            result
                .onSuccess { created ->
                    transientState.update {
                        it.copy(
                            isBusy = false,
                            showCreateDialog = false,
                            pendingImportUri = null,
                            projectNameError = null,
                            errorBanner = null,
                        )
                    }
                    onProjectReady(created.id)
                }
                .onFailure { err ->
                    transientState.update {
                        it.copy(
                            isBusy = false,
                            projectNameError = err.message ?: "Invalid project name.",
                        )
                    }
                }
        }
    }

    fun openImportChooser() {
        transientState.update { it.copy(showImportChooserDialog = true, errorBanner = null) }
    }

    fun dismissImportChooser() {
        transientState.update { it.copy(showImportChooserDialog = false) }
    }

    fun onLocalDirectoryPicked(uri: Uri?, suggestedName: String) {
        transientState.update { it.copy(showImportChooserDialog = false) }
        if (uri == null) return
        transientState.update {
            it.copy(
                showCreateDialog = true,
                pendingImportUri = uri,
                projectNameDraft = suggestedName,
                projectNameError = null,
            )
        }
    }

    fun onGitHubCloneRequested() {
        transientState.update {
            it.copy(
                showImportChooserDialog = false,
                showGitHubCloneDialog = true,
                cloneError = null,
                infoBanner = null,
            )
        }
        if (gitHubRepository.accountState.value.status == GitHubAuthStatus.CONNECTED) {
            refreshGitHubRepositories()
        }
    }

    fun dismissGitHubCloneDialog() {
        if (transientState.value.isCloning) return
        transientState.update {
            it.copy(
                showGitHubCloneDialog = false,
                cloneError = null,
            )
        }
    }

    fun refreshGitHubRepositories() {
        viewModelScope.launch {
            gitHubRepository.refreshRepositories()
        }
    }

    fun updateCloneSearchQuery(query: String) {
        transientState.update { it.copy(cloneSearchQuery = query) }
    }

    fun updateCloneUrlDraft(url: String) {
        transientState.update { state ->
            val inferred = if (state.cloneProjectNameDraft.isBlank()) {
                deriveNameFromRemoteUrl(url)
            } else {
                state.cloneProjectNameDraft
            }
            state.copy(
                cloneUrlDraft = url,
                cloneProjectNameDraft = inferred,
                cloneError = null,
            )
        }
    }

    fun updateCloneBranchDraft(branch: String) {
        transientState.update { it.copy(cloneBranchDraft = branch, cloneError = null) }
    }

    fun updateCloneProjectNameDraft(name: String) {
        transientState.update { it.copy(cloneProjectNameDraft = name, cloneError = null) }
    }

    fun selectGitHubRepoToClone(repo: GitHubRepoSummary) {
        transientState.update {
            it.copy(
                cloneUrlDraft = repo.cloneUrl,
                cloneBranchDraft = repo.defaultBranch,
                cloneProjectNameDraft = repo.name,
                cloneError = null,
            )
        }
    }

    fun confirmCloneRepository(onProjectReady: (String) -> Unit) {
        val state = transientState.value
        val remoteUrl = state.cloneUrlDraft.trim()
        val projectName = state.cloneProjectNameDraft.trim()
            .ifEmpty { deriveNameFromRemoteUrl(remoteUrl) }
        val branch = state.cloneBranchDraft.trim().takeIf { it.isNotEmpty() }

        if (remoteUrl.isEmpty()) {
            transientState.update { it.copy(cloneError = "Enter a GitHub repository HTTPS URL or pick one above.") }
            return
        }
        if (projectName.isEmpty()) {
            transientState.update { it.copy(cloneError = "Enter a local project name for the cloned repository.") }
            return
        }

        viewModelScope.launch {
            transientState.update { it.copy(isCloning = true, cloneError = null) }
            gitRepositoryService.cloneRepository(
                rawProjectName = projectName,
                remoteUrl = remoteUrl,
                branchName = branch,
            ).onSuccess { project ->
                transientState.update {
                    it.copy(
                        isCloning = false,
                        showGitHubCloneDialog = false,
                        cloneUrlDraft = "",
                        cloneBranchDraft = "",
                        cloneProjectNameDraft = "",
                        cloneError = null,
                        infoBanner = "Cloned ${project.name} (${project.branchName ?: "HEAD"}).",
                    )
                }
                onProjectReady(project.id)
            }.onFailure { err ->
                transientState.update {
                    it.copy(
                        isCloning = false,
                        cloneError = err.message ?: "Failed to clone repository.",
                    )
                }
            }
        }
    }

    fun requestDeleteProject(project: ProjectSummary) {
        transientState.update { it.copy(projectPendingDeletion = project) }
    }

    fun dismissDeleteProject() {
        transientState.update { it.copy(projectPendingDeletion = null) }
    }

    fun confirmDeleteProject() {
        val target = transientState.value.projectPendingDeletion ?: return
        viewModelScope.launch {
            projectRepository.deleteProject(target.id)
                .onSuccess {
                    transientState.update { it.copy(projectPendingDeletion = null) }
                }
                .onFailure { err ->
                    transientState.update {
                        it.copy(
                            projectPendingDeletion = null,
                            errorBanner = err.message ?: "Failed to delete project.",
                        )
                    }
                }
        }
    }

    private fun deriveNameFromRemoteUrl(url: String): String {
        val trimmed = url.trim().trimEnd('/')
        val segment = trimmed.substringAfterLast('/', "")
            .removeSuffix(".git")
            .trim()
        return segment.take(40)
    }

    class Factory(
        private val appContext: Context,
        private val projectRepository: ProjectRepository,
        private val preferencesRepository: AppPreferencesRepository,
        private val gitHubRepository: GitHubRepository,
        private val gitRepositoryService: GitRepositoryService,
    ) : ViewModelProvider.Factory {
        @Suppress("UNCHECKED_CAST")
        override fun <T : ViewModel> create(modelClass: Class<T>): T {
            return HomeViewModel(
                appContext = appContext,
                projectRepository = projectRepository,
                preferencesRepository = preferencesRepository,
                gitHubRepository = gitHubRepository,
                gitRepositoryService = gitRepositoryService,
            ) as T
        }
    }
}
