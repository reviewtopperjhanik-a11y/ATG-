package com.example.core.common

import java.io.File

sealed interface ProjectNameValidation {
    data class Valid(val sanitizedName: String, val folderSlug: String) : ProjectNameValidation
    data class Invalid(val reason: String) : ProjectNameValidation
}

object ProjectPathValidator {
    private val allowedNamePattern = Regex("^[A-Za-z0-9][A-Za-z0-9._ -]{0,47}$")

    fun validateProjectName(rawName: String): ProjectNameValidation {
        val trimmed = rawName.trim()
        if (trimmed.isEmpty()) {
            return ProjectNameValidation.Invalid("Project name cannot be empty.")
        }
        if (trimmed.contains("..") || trimmed.contains('/') || trimmed.contains('\\') || trimmed.contains('\u0000')) {
            return ProjectNameValidation.Invalid("Project name cannot contain path separators or '..'.")
        }
        if (!allowedNamePattern.matches(trimmed)) {
            return ProjectNameValidation.Invalid(
                "Use 1–48 letters, numbers, spaces, dots, underscores, or hyphens."
            )
        }
        val slug = trimmed
            .lowercase()
            .replace(Regex("[^a-z0-9._-]+"), "-")
            .trim('-')
            .ifBlank { "project" }
        return ProjectNameValidation.Valid(sanitizedName = trimmed, folderSlug = slug)
    }

    /**
     * Validates a relative entry name or relative path inside a workspace, rejecting empty,
     * absolute paths, '..' segments, backslashes, and null bytes.
     */
    fun validateRelativePath(relativePath: String, allowEmpty: Boolean = false): Result<String> {
        return runCatching {
            if (relativePath.contains('\u0000')) {
                throw IllegalArgumentException("Path contains null byte.")
            }
            if (relativePath.startsWith("/") || relativePath.startsWith("\\")) {
                throw IllegalArgumentException("Absolute paths are not allowed: $relativePath")
            }
            if (relativePath.contains('\\')) {
                throw IllegalArgumentException("Backslashes are not allowed in relative paths: $relativePath")
            }
            val trimmed = relativePath.trimEnd('/')
            if (trimmed.isEmpty()) {
                if (allowEmpty) return@runCatching ""
                throw IllegalArgumentException("Path cannot be empty.")
            }
            val segments = trimmed.split('/')
            if (segments.any { it.isEmpty() || it == "." || it == ".." }) {
                throw IllegalArgumentException("Invalid path segment in: $relativePath")
            }
            trimmed
        }
    }

    /**
     * Canonicalizes [relativePath] under [projectRoot] and rejects any path escaping the root
     * (including absolute paths, '..' traversal, or symlinks pointing outside [projectRoot]).
     */
    fun resolveWithinRoot(projectRoot: File, relativePath: String): Result<File> {
        return runCatching {
            val cleanRel = validateRelativePath(relativePath, allowEmpty = true).getOrThrow()
            val canonicalRoot = projectRoot.canonicalFile
            val candidate = if (cleanRel.isEmpty()) {
                canonicalRoot
            } else {
                File(canonicalRoot, cleanRel).canonicalFile
            }
            val rootPath = canonicalRoot.path
            val candidatePath = candidate.path
            val isInside = candidatePath == rootPath ||
                candidatePath.startsWith(rootPath + File.separator)
            if (!isInside) {
                throw IllegalArgumentException("Path escapes project root: $relativePath")
            }
            candidate
        }
    }
}

object WorkspacePathMapper {
    const val RUNTIME_WORKSPACES_BASE = "/workspace"

    fun runtimeRootForProject(projectId: String): String {
        val cleanId = ProjectPathValidator.validateRelativePath(projectId).getOrThrow()
        return "$RUNTIME_WORKSPACES_BASE/$cleanId"
    }

    fun toRuntimePath(projectId: String, relativePath: String): Result<String> {
        return runCatching {
            val runtimeRoot = runtimeRootForProject(projectId)
            val cleanRel = ProjectPathValidator.validateRelativePath(relativePath, allowEmpty = true).getOrThrow()
            if (cleanRel.isEmpty()) runtimeRoot else "$runtimeRoot/$cleanRel"
        }
    }
}
