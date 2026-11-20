package com.example.core.security

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext
import java.nio.charset.StandardCharsets
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

interface TokenCipher {
    fun encrypt(plaintext: String): ByteArray
    fun decrypt(payload: ByteArray): String
}

/**
 * Production Android Keystore AES-256-GCM cipher. Never downgrades to plaintext.
 */
class AndroidKeystoreTokenCipher(
    private val keyAlias: String = "agt_gateway_device_token_v1",
) : TokenCipher {

    private fun getOrCreateKey(): SecretKey {
        val keyStore = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        val existing = keyStore.getKey(keyAlias, null) as? SecretKey
        if (existing != null) return existing

        val keyGenerator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore")
        val spec = KeyGenParameterSpec.Builder(
            keyAlias,
            KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT,
        )
            .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
            .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
            .setKeySize(256)
            .build()
        keyGenerator.init(spec)
        return keyGenerator.generateKey()
    }

    override fun encrypt(plaintext: String): ByteArray {
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, getOrCreateKey())
        val iv = cipher.iv
        val ciphertext = cipher.doFinal(plaintext.toByteArray(StandardCharsets.UTF_8))
        return byteArrayOf(iv.size.toByte()) + iv + ciphertext
    }

    override fun decrypt(payload: ByteArray): String {
        require(payload.size > 2) { "Encrypted token payload is corrupt." }
        val ivLength = payload[0].toInt() and 0xFF
        require(payload.size > 1 + ivLength) { "Encrypted token IV is corrupt." }
        val iv = payload.copyOfRange(1, 1 + ivLength)
        val ciphertext = payload.copyOfRange(1 + ivLength, payload.size)
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.DECRYPT_MODE, getOrCreateKey(), GCMParameterSpec(128, iv))
        val plainBytes = cipher.doFinal(ciphertext)
        return String(plainBytes, StandardCharsets.UTF_8)
    }
}

/**
 * Stores the JH GetWay Pro device token encrypted via Android Keystore AES-256-GCM.
 * Never exposes the raw token in UI state flows after saving.
 */
class GatewayCredentialVault(
    context: Context,
    private val cipher: TokenCipher = AndroidKeystoreTokenCipher(),
) {
    private val prefs = context.applicationContext.getSharedPreferences(
        "agt_gateway_vault",
        Context.MODE_PRIVATE,
    )

    private val _hasToken = MutableStateFlow(prefs.contains(KEY_CIPHERTEXT))
    val hasToken: StateFlow<Boolean> = _hasToken.asStateFlow()

    suspend fun saveToken(rawToken: String): Result<Unit> = withContext(Dispatchers.IO) {
        runCatching {
            val trimmed = rawToken.trim()
            require(trimmed.isNotEmpty()) { "Device token cannot be empty." }
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
        const val KEY_CIPHERTEXT = "encrypted_gateway_token"
    }
}
