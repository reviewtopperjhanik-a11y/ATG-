package com.example.core.security

import android.content.Context
import android.util.Base64
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Encrypts per-extension MCP bearer tokens via Android Keystore AES-256-GCM.
 * Tokens are stored isolated from Room and configuration files.
 */
class ExtensionCredentialVault(
    context: Context,
    private val cipher: TokenCipher = AndroidKeystoreTokenCipher("agt_extension_mcp_v1"),
) {
    private val prefs = context.applicationContext.getSharedPreferences(
        "agt_extension_vault",
        Context.MODE_PRIVATE,
    )

    suspend fun saveToken(extensionId: String, rawToken: String): Result<Unit> = withContext(Dispatchers.IO) {
        runCatching {
            val sanitizedKey = sanitizeKey(extensionId)
            val trimmed = rawToken.trim()
            require(trimmed.isNotEmpty()) { "MCP bearer token cannot be empty." }
            val encrypted = cipher.encrypt(trimmed)
            val encoded = Base64.encodeToString(encrypted, Base64.NO_WRAP)
            prefs.edit().putString(sanitizedKey, encoded).apply()
        }
    }

    suspend fun readToken(extensionId: String): Result<String?> = withContext(Dispatchers.IO) {
        runCatching {
            val encoded = prefs.getString(sanitizeKey(extensionId), null) ?: return@runCatching null
            val bytes = Base64.decode(encoded, Base64.NO_WRAP)
            cipher.decrypt(bytes)
        }
    }

    fun hasToken(extensionId: String): Boolean = prefs.contains(sanitizeKey(extensionId))

    suspend fun removeToken(extensionId: String) = withContext(Dispatchers.IO) {
        prefs.edit().remove(sanitizeKey(extensionId)).apply()
    }

    suspend fun allStoredTokens(): List<String> = withContext(Dispatchers.IO) {
        prefs.all.keys.mapNotNull { key ->
            val encoded = prefs.getString(key, null) ?: return@mapNotNull null
            runCatching {
                val bytes = Base64.decode(encoded, Base64.NO_WRAP)
                cipher.decrypt(bytes)
            }.getOrNull()
        }
    }

    private fun sanitizeKey(extensionId: String): String =
        "mcp_tok_${extensionId.replace(Regex("[^A-Za-z0-9._-]"), "_")}"
}
