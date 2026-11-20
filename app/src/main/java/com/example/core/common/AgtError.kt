package com.example.core.common

/**
 * Typed failure hierarchy across gateway, runtime, project filesystem, and extension boundaries.
 * Never expose raw credentials or secrets inside [diagnosticDetail].
 */
sealed class AgtError(
    open val userMessage: String,
    open val diagnosticDetail: String? = null,
) {
    data class NetworkUnavailable(
        override val userMessage: String = "Network is unavailable.",
        override val diagnosticDetail: String? = null,
    ) : AgtError(userMessage, diagnosticDetail)

    data class GatewayUnauthorized(
        override val userMessage: String = "Gateway rejected credentials or token is missing.",
        override val diagnosticDetail: String? = null,
    ) : AgtError(userMessage, diagnosticDetail)

    data class GatewayModelUnavailable(
        override val userMessage: String = "Requested model is unavailable on the gateway.",
        override val diagnosticDetail: String? = null,
    ) : AgtError(userMessage, diagnosticDetail)

    data class UnsupportedCapability(
        override val userMessage: String = "Selected model or gateway does not support this capability.",
        override val diagnosticDetail: String? = null,
    ) : AgtError(userMessage, diagnosticDetail)

    data class RuntimeNotInstalled(
        override val userMessage: String = "Linux runtime is not installed yet.",
        override val diagnosticDetail: String? = null,
    ) : AgtError(userMessage, diagnosticDetail)

    data class RuntimeIncompatible(
        override val userMessage: String = "Device architecture or OS version is incompatible.",
        override val diagnosticDetail: String? = null,
    ) : AgtError(userMessage, diagnosticDetail)

    data class RuntimeManifestInvalid(
        override val userMessage: String = "Runtime manifest URL or JSON manifest is invalid.",
        override val diagnosticDetail: String? = null,
    ) : AgtError(userMessage, diagnosticDetail)

    data class RuntimeInsufficientStorage(
        override val userMessage: String = "Insufficient free storage to install Linux runtime.",
        override val diagnosticDetail: String? = null,
    ) : AgtError(userMessage, diagnosticDetail)

    data class RuntimeVerificationFailed(
        override val userMessage: String = "Runtime bundle integrity check or staging verification failed.",
        override val diagnosticDetail: String? = null,
    ) : AgtError(userMessage, diagnosticDetail)

    data class ProcessStartFailed(
        override val userMessage: String = "Failed to start process in runtime.",
        override val diagnosticDetail: String? = null,
    ) : AgtError(userMessage, diagnosticDetail)

    data class ProcessTimedOut(
        override val userMessage: String = "Process timed out before completion.",
        override val diagnosticDetail: String? = null,
    ) : AgtError(userMessage, diagnosticDetail)

    data class ProcessCancelled(
        override val userMessage: String = "Process was cancelled.",
        override val diagnosticDetail: String? = null,
    ) : AgtError(userMessage, diagnosticDetail)

    data class PermissionDenied(
        override val userMessage: String = "Operation blocked by security policy.",
        override val diagnosticDetail: String? = null,
    ) : AgtError(userMessage, diagnosticDetail)

    data class ApprovalRequired(
        override val userMessage: String = "User approval is required to continue.",
        override val diagnosticDetail: String? = null,
    ) : AgtError(userMessage, diagnosticDetail)

    data class ProjectPathRejected(
        override val userMessage: String = "Path is invalid or escapes the project workspace root.",
        override val diagnosticDetail: String? = null,
    ) : AgtError(userMessage, diagnosticDetail)

    data class CheckpointFailed(
        override val userMessage: String = "Failed to create or restore checkpoint.",
        override val diagnosticDetail: String? = null,
    ) : AgtError(userMessage, diagnosticDetail)

    data class ExtensionSignatureOrIntegrityFailure(
        override val userMessage: String = "Extension integrity or manifest verification failed.",
        override val diagnosticDetail: String? = null,
    ) : AgtError(userMessage, diagnosticDetail)
}

sealed interface UiState<out T> {
    data object Loading : UiState<Nothing>
    data class Success<T>(val data: T) : UiState<T>
    data class Error(val error: AgtError) : UiState<Nothing>
}
