package com.example.core.security

/**
 * Redacts Bearer tokens, Authorization headers, embedded URL credentials, and common API
 * secret prefixes from logs, diagnostics, crash reports, and error strings.
 */
object SecretRedactor {
    private val bearerRegex = Regex("(?i)(bearer\\s+)[A-Za-z0-9._~+/-]+=*")
    private val authHeaderRegex = Regex("(?i)(authorization\\s*[:=]\\s*)[^\\r\\n]+")
    private val urlCredsRegex = Regex("(?i)(https?://)([^/\\s:@]+):([^@\\s/]+)@")
    private val githubPatRegex = Regex("\\bgithub_pat_[A-Za-z0-9_]{12,}\\b")
    private val keyTokenRegex = Regex("\\b(?:sk|pk|ghp|gho|ghu|ghs|ghr|jh|agt)_[A-Za-z0-9_-]{8,}\\b")
    private val skDashRegex = Regex("\\bsk-[A-Za-z0-9_-]{10,}\\b")

    fun redact(input: String, knownSecrets: Collection<String> = emptyList()): String {
        var result = input
        for (secret in knownSecrets) {
            val trimmed = secret.trim()
            if (trimmed.length >= 4) {
                result = result.replace(trimmed, "[REDACTED]")
            }
        }
        result = urlCredsRegex.replace(result, "$1[REDACTED]@")
        result = bearerRegex.replace(result, "$1[REDACTED]")
        result = authHeaderRegex.replace(result, "$1[REDACTED]")
        result = githubPatRegex.replace(result, "[REDACTED]")
        result = keyTokenRegex.replace(result, "[REDACTED]")
        result = skDashRegex.replace(result, "[REDACTED]")
        return result
    }
}
