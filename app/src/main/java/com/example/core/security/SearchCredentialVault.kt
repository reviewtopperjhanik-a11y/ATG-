package com.example.core.security

import android.content.Context
import android.util.Base64
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext

/**
 * Stores scoped Bearer tokens for API 2 (Web Search Gateway) and API 3 (GitHub Search Gateway)
 * encrypted via Android Keystore AES-256-GCM. Never exposes raw tokens in UI state flows.
 */
class SearchCredentialVault(
    context: Context,
    private val cipher: TokenCipher = AndroidKeystoreTokenCipher(keyAlias = "agt_search_gateway_tokens_v1"),
) {
    private val prefs = context.applicationContext.getSharedPreferences(
        "agt_search_gateway_vault",
        Context.MODE_PRIVATE,
    )

    private val _hasWebSearchToken = MutableStateFlow(prefs.contains(KEY_WEB_SEARCH_CIPHERTEXT))
    val hasWebSearchToken: StateFlow<Boolean> = _hasWebSearchToken.asStateFlow()

    private val _hasGitHubSearchToken = MutableStateFlow(prefs.contains(KEY_GITHUB_SEARCH_CIPHERTEXT))
    val hasGitHubSearchToken: StateFlow<Boolean> = _hasGitHubSearchToken.asStateFlow()

    suspend fun saveWebSearchToken(rawToken: String): Result<Unit> =
        saveTokenInternal(KEY_WEB_SEARCH_CIPHERTEXT, _hasWebSearchToken, rawToken, "Web Search token")

    suspend fun readWebSearchToken(): Result<String?> =
        readTokenInternal(KEY_WEB_SEARCH_CIPHERTEXT)

    suspend fun clearWebSearchToken() =
        clearTokenInternal(KEY_WEB_SEARCH_CIPHERTEXT, _hasWebSearchToken)

    suspend fun saveGitHubSearchToken(rawToken: String): Result<Unit> =
        saveTokenInternal(KEY_GITHUB_SEARCH_CIPHERTEXT, _hasGitHubSearchToken, rawToken, "GitHub Search token")

    suspend fun readGitHubSearchToken(): Result<String?> =
        readTokenInternal(KEY_GITHUB_SEARCH_CIPHERTEXT)

    suspend fun clearGitHubSearchToken() =
        clearTokenInternal(KEY_GITHUB_SEARCH_CIPHERTEXT, _hasGitHubSearchToken)

    suspend fun allStoredTokens(): List<String> = withContext(Dispatchers.IO) {
        buildList {
            readTokenInternal(KEY_WEB_SEARCH_CIPHERTEXT).getOrNull()?.takeIf { it.isNotBlank() }?.let(::add)
            readTokenInternal(KEY_GITHUB_SEARCH_CIPHERTEXT).getOrNull()?.takeIf { it.isNotBlank() }?.let(::add)
        }
    }

    private suspend fun saveTokenInternal(
        key: String,
        stateFlow: MutableStateFlow<Boolean>,
        rawToken: String,
        label: String,
    ): Result<Unit> = withContext(Dispatchers.IO) {
        runCatching {
            val trimmed = rawToken.trim()
            require(trimmed.isNotEmpty()) { "$label cannot be empty." }
            val encrypted = cipher.encrypt(trimmed)
            val encoded = Base64.encodeToString(encrypted, Base64.NO_WRAP)
            prefs.edit().putString(key, encoded).apply()
            stateFlow.value = true
        }
    }

    private suspend fun readTokenInternal(key: String): Result<String?> = withContext(Dispatchers.IO) {
        runCatching {
            val encoded = prefs.getString(key, null) ?: return@runCatching null
            val bytes = Base64.decode(encoded, Base64.NO_WRAP)
            cipher.decrypt(bytes)
        }
    }

    private suspend fun clearTokenInternal(
        key: String,
        stateFlow: MutableStateFlow<Boolean>,
    ) = withContext(Dispatchers.IO) {
        prefs.edit().remove(key).apply()
        stateFlow.value = false
    }

    private companion object {
        const val KEY_WEB_SEARCH_CIPHERTEXT = "encrypted_web_search_token"
        const val KEY_GITHUB_SEARCH_CIPHERTEXT = "encrypted_github_search_token"
    }
}
