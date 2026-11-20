package com.example.domain.contracts

import com.example.domain.model.GitHubSearchRequest
import com.example.domain.model.GitHubSearchResponse
import com.example.domain.model.SearchServiceHealth
import com.example.domain.model.WebSearchRequest
import com.example.domain.model.WebSearchResponse

interface WebSearchClient {
    suspend fun health(): SearchServiceHealth
    suspend fun search(request: WebSearchRequest): Result<WebSearchResponse>
}

interface GitHubSearchClient {
    suspend fun health(): SearchServiceHealth
    suspend fun search(request: GitHubSearchRequest): Result<GitHubSearchResponse>
}
