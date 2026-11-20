package com.example.core.config

import com.example.BuildConfig
import com.example.core.security.GatewayUrlValidation
import com.example.core.security.GatewayUrlValidator

enum class ConfigSource {
    BUILD_CONFIG,
    LEGACY_PREFERENCE,
}

sealed interface ConfigResolution {
    data class Configured(
        val value: String,
        val source: ConfigSource,
    ) : ConfigResolution

    data class MissingOrInvalid(
        val userMessage: String,
        val diagnosticDetail: String,
    ) : ConfigResolution
}

data class ProductionConfigValidationReport(
    val isValid: Boolean,
    val errors: List<String>,
)

/**
 * Centralized application configuration for public runtime and GitHub OAuth endpoints.
 *
 * Separates build-time configuration ([BuildConfig.AGT_RUNTIME_MANIFEST_URL],
 * [BuildConfig.AGT_GITHUB_OAUTH_CLIENT_ID]) from user preferences and private credentials.
 * Never holds private OAuth client secrets.
 */
data class AgtAppConfig(
    val runtimeManifestUrl: String = BuildConfig.AGT_RUNTIME_MANIFEST_URL,
    val githubOAuthClientId: String = BuildConfig.AGT_GITHUB_OAUTH_CLIENT_ID,
    val isDebugBuild: Boolean = BuildConfig.DEBUG,
    val allowUpstreamBootstrap: Boolean = true,
) {
    fun isUpstreamBootstrapActive(legacySavedUrl: String = ""): Boolean =
        allowUpstreamBootstrap && resolveRuntimeManifestUrl(legacySavedUrl) is ConfigResolution.MissingOrInvalid

    fun resolveRuntimeManifestUrl(legacySavedUrl: String = ""): ConfigResolution {
        val buildValue = runtimeManifestUrl.trim()
        if (buildValue.isNotEmpty()) {
            return validateManifestEndpoint(
                rawUrl = buildValue,
                source = ConfigSource.BUILD_CONFIG,
                allowLoopbackHttp = isDebugBuild,
            )
        }

        val legacyValue = legacySavedUrl.trim()
        if (legacyValue.isNotEmpty()) {
            val legacyChecked = validateManifestEndpoint(
                rawUrl = legacyValue,
                source = ConfigSource.LEGACY_PREFERENCE,
                allowLoopbackHttp = isDebugBuild,
            )
            if (legacyChecked is ConfigResolution.Configured) {
                return legacyChecked
            }
        }

        return ConfigResolution.MissingOrInvalid(
            userMessage = "Runtime manifest endpoint is not configured for this build (AGT_RUNTIME_MANIFEST_URL). Supply an official HTTPS runtime manifest URL before production release.",
            diagnosticDetail = "Missing BuildConfig.AGT_RUNTIME_MANIFEST_URL (debug=$isDebugBuild)",
        )
    }

    fun resolveGitHubOAuthClientId(legacySavedClientId: String = ""): ConfigResolution {
        val buildValue = githubOAuthClientId.trim()
        if (buildValue.isNotEmpty()) {
            return validateClientId(
                rawClientId = buildValue,
                source = ConfigSource.BUILD_CONFIG,
            )
        }

        val legacyValue = legacySavedClientId.trim()
        if (legacyValue.isNotEmpty()) {
            val legacyChecked = validateClientId(
                rawClientId = legacyValue,
                source = ConfigSource.LEGACY_PREFERENCE,
            )
            if (legacyChecked is ConfigResolution.Configured) {
                return legacyChecked
            }
        }

        return ConfigResolution.MissingOrInvalid(
            userMessage = "GitHub OAuth is not configured for this build (AGT_GITHUB_OAUTH_CLIENT_ID). Supply the official GitHub OAuth App Client ID before production release.",
            diagnosticDetail = "Missing BuildConfig.AGT_GITHUB_OAUTH_CLIENT_ID (debug=$isDebugBuild)",
        )
    }

    fun validateClientIdFormat(rawClientId: String): ConfigResolution =
        validateClientId(rawClientId = rawClientId, source = ConfigSource.LEGACY_PREFERENCE)

    fun shouldMigrateLegacyRuntimeUrl(legacySavedUrl: String): Boolean {
        if (legacySavedUrl.isBlank()) return false
        val resolved = resolveRuntimeManifestUrl(legacySavedUrl)
        return resolved is ConfigResolution.Configured && resolved.source == ConfigSource.BUILD_CONFIG
    }

    fun shouldMigrateLegacyGitHubClientId(legacySavedClientId: String): Boolean {
        if (legacySavedClientId.isBlank()) return false
        val resolved = resolveGitHubOAuthClientId(legacySavedClientId)
        return resolved is ConfigResolution.Configured && resolved.source == ConfigSource.BUILD_CONFIG
    }

    fun validateProductionConfiguration(): ProductionConfigValidationReport {
        val errors = mutableListOf<String>()
        val manifestClean = runtimeManifestUrl.trim()
        if (manifestClean.isEmpty()) {
            errors += "AGT_RUNTIME_MANIFEST_URL must be supplied before production release."
        } else {
            val check = validateManifestEndpoint(
                rawUrl = manifestClean,
                source = ConfigSource.BUILD_CONFIG,
                allowLoopbackHttp = false,
            )
            if (check is ConfigResolution.MissingOrInvalid) {
                errors += check.userMessage
            }
        }

        val clientIdClean = githubOAuthClientId.trim()
        if (clientIdClean.isEmpty()) {
            errors += "AGT_GITHUB_OAUTH_CLIENT_ID must be supplied before production release."
        } else {
            val check = validateClientId(
                rawClientId = clientIdClean,
                source = ConfigSource.BUILD_CONFIG,
            )
            if (check is ConfigResolution.MissingOrInvalid) {
                errors += check.userMessage
            }
        }
        return ProductionConfigValidationReport(
            isValid = errors.isEmpty(),
            errors = errors,
        )
    }

    private fun validateManifestEndpoint(
        rawUrl: String,
        source: ConfigSource,
        allowLoopbackHttp: Boolean,
    ): ConfigResolution {
        if (isPlaceholderUrl(rawUrl)) {
            return ConfigResolution.MissingOrInvalid(
                userMessage = "Runtime manifest endpoint uses a placeholder URL and must be replaced with an official HTTPS manifest URL before release.",
                diagnosticDetail = "Placeholder AGT_RUNTIME_MANIFEST_URL rejected",
            )
        }
        return when (val v = GatewayUrlValidator.validate(rawUrl)) {
            is GatewayUrlValidation.Invalid -> ConfigResolution.MissingOrInvalid(
                userMessage = "Configured runtime manifest URL is invalid: ${v.reason}",
                diagnosticDetail = v.reason,
            )
            is GatewayUrlValidation.Valid -> {
                if (v.isLoopback && !allowLoopbackHttp && v.normalizedBaseUrl.startsWith("http://")) {
                    ConfigResolution.MissingOrInvalid(
                        userMessage = "Production runtime manifest URL must use HTTPS rather than loopback HTTP (AGT_RUNTIME_MANIFEST_URL).",
                        diagnosticDetail = "Loopback HTTP rejected for production config",
                    )
                } else {
                    ConfigResolution.Configured(
                        value = v.normalizedBaseUrl,
                        source = source,
                    )
                }
            }
        }
    }

    private fun validateClientId(
        rawClientId: String,
        source: ConfigSource,
    ): ConfigResolution {
        val clean = rawClientId.trim()
        if (clean.isEmpty() || isPlaceholderClientId(clean)) {
            return ConfigResolution.MissingOrInvalid(
                userMessage = "Configured GitHub OAuth Client ID is invalid or a placeholder. Supply the official AGT_GITHUB_OAUTH_CLIENT_ID before release.",
                diagnosticDetail = "Invalid or placeholder AGT_GITHUB_OAUTH_CLIENT_ID",
            )
        }
        if (looksLikePrivateSecret(clean)) {
            return ConfigResolution.MissingOrInvalid(
                userMessage = "Invalid GitHub OAuth configuration: private tokens or secrets must never be placed in AGT_GITHUB_OAUTH_CLIENT_ID.",
                diagnosticDetail = "Secret-like prefix rejected in AGT_GITHUB_OAUTH_CLIENT_ID",
            )
        }
        if (clean.any(Char::isWhitespace) || clean.length < 6) {
            return ConfigResolution.MissingOrInvalid(
                userMessage = "Configured GitHub OAuth Client ID has an invalid format.",
                diagnosticDetail = "Malformed AGT_GITHUB_OAUTH_CLIENT_ID",
            )
        }
        return ConfigResolution.Configured(
            value = clean,
            source = source,
        )
    }

    companion object {
        const val KEY_RUNTIME_MANIFEST_URL = "AGT_RUNTIME_MANIFEST_URL"
        const val KEY_GITHUB_OAUTH_CLIENT_ID = "AGT_GITHUB_OAUTH_CLIENT_ID"

        fun fromBuildConfig(): AgtAppConfig = AgtAppConfig(
            runtimeManifestUrl = BuildConfig.AGT_RUNTIME_MANIFEST_URL,
            githubOAuthClientId = BuildConfig.AGT_GITHUB_OAUTH_CLIENT_ID,
            isDebugBuild = BuildConfig.DEBUG,
        )

        private fun isPlaceholderUrl(url: String): Boolean {
            val lower = url.lowercase()
            return lower.contains("example.com") ||
                lower.contains("example.org") ||
                lower.contains("example.net") ||
                lower.contains("your_") ||
                lower.contains("placeholder")
        }

        private fun isPlaceholderClientId(id: String): Boolean {
            val lower = id.lowercase()
            return lower.contains("...") ||
                lower.contains("placeholder") ||
                lower.startsWith("your_") ||
                lower == "changeme"
        }

        private fun looksLikePrivateSecret(value: String): Boolean {
            val lower = value.lowercase()
            return lower.startsWith("ghp_") ||
                lower.startsWith("ghs_") ||
                lower.startsWith("ghr_") ||
                lower.startsWith("github_pat_") ||
                lower.contains("client_secret")
        }
    }
}
