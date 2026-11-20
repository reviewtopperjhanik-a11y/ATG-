package com.example.core.security

import android.content.Context
import android.util.Base64
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext

/**
 * Stores GitHub OAuth/PAT credentials encrypted via Android Keystore AES-256-GCM.
 * Isolated from JH GetWay Pro credentials and never exposes raw tokens in UI state or logs.
 */
class GitHubCredentialVault(
    context: Context,
    private val cipher: TokenCipher = AndroidKeystoreTokenCipher("agt_github_oauth_token_v1"),
) {
    private val prefs = context.applicationContext.getSharedPreferences(
        "agt_github_vault",
        Context.MODE_PRIVATE,
    )

    private val _hasToken = MutableStateFlow(prefs.contains(KEY_CIPHERTEXT))
    val hasToken: StateFlow<Boolean> = _hasToken.asStateFlow()

    suspend fun saveToken(rawToken: String): Result<Unit> = withContext(Dispatchers.IO) {
        runCatching {
            val trimmed = rawToken.trim()
            require(trimmed.isNotEmpty()) { "GitHub access token cannot be empty." }
            val encrypted = cipher.encrypt(trimmed)
            val encoded = Base64.encodeToString(encrypted, Base64.NO_WRAP)
            prefs.edit().putString(KEY_CIPHERTEXT, encoded).apply()
            _hasToken.value = true
        }
    }

    suspend fun readToken(): Result<String?> = withContext(Dispatchers.IO) {
        runCatching {
            val encoded = prefs.getString(KEY_CIPHERTEXT, null) ?: return@runCatching null
            val bytes = Base64.decode(encoded, Base64.NO_WRAP)
            cipher.decrypt(bytes)
        }
    }

    suspend fun clearToken() = withContext(Dispatchers.IO) {
        prefs.edit().remove(KEY_CIPHERTEXT).apply()
        _hasToken.value = false
    }

    private companion object {
        const val KEY_CIPHERTEXT = "encrypted_github_token"
    }
}
