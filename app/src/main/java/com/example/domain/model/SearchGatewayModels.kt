package com.example.domain.model

import com.example.core.common.AgtError

enum class SearchServiceKind(val label: String) {
    WEB_SEARCH("Web Search API"),
    GITHUB_SEARCH("GitHub Search API"),
}

data class SearchServiceHealth(
    val service: SearchServiceKind,
    val status: GatewayHealthStatus = GatewayHealthStatus.UNCONFIGURED,
    val latencyMs: Long? = null,
    val lastCheckedEpochMs: Long? = null,
    val lastSuccessfulEpochMs: Long? = null,
    val statusMessage: String = "Not configured",
    val activeProviders: List<String> = emptyList(),
    val availableKeysCount: Int? = null,
    val cacheEnabled: Boolean? = null,
    val error: AgtError? = null,
)

data class WebSearchRequest(
    val query: String,
    val maxResults: Int = 5,
    val searchDepth: String = "basic",
    val includeContent: Boolean = true,
    val freshnessDays: Int = 365,
)

data class WebSearchResultItem(
    val title: String,
    val url: String,
    val snippet: String,
    val content: String = "",
    val publishedDate: String? = null,
    val score: Double? = null,
)

data class WebSearchResponse(
    val query: String,
    val providerUsed: String,
    val cached: Boolean = false,
    val latencyMs: Long = 0L,
    val results: List<WebSearchResultItem> = emptyList(),
)

enum class GitHubSearchMode(val wireValue: String) {
    REPOSITORIES("repositories"),
    CODE("code"),
}

data class GitHubSearchRequest(
    val query: String,
    val mode: GitHubSearchMode = GitHubSearchMode.REPOSITORIES,
    val language: String? = null,
    val sort: String = "stars",
    val maxResults: Int = 5,
    val includeReadme: Boolean = true,
)

data class GitHubSearchResultItem(
    val fullName: String,
    val htmlUrl: String,
    val description: String,
    val stars: Int = 0,
    val forks: Int = 0,
    val language: String? = null,
    val license: String? = null,
    val updatedAt: String? = null,
    val topics: List<String> = emptyList(),
    val readmeExcerpt: String = "",
)

data class GitHubSearchResponse(
    val query: String,
    val mode: GitHubSearchMode = GitHubSearchMode.REPOSITORIES,
    val cached: Boolean = false,
    val latencyMs: Long = 0L,
    val totalCount: Int = 0,
    val results: List<GitHubSearchResultItem> = emptyList(),
)

sealed interface SearchOutcome<out T> {
    data class Success<T>(
        val data: T,
        val fallbackUsed: Boolean = false,
        val notice: String? = null,
    ) : SearchOutcome<T>

    data class Degraded(
        val reason: String,
        val error: AgtError? = null,
    ) : SearchOutcome<Nothing>
}
