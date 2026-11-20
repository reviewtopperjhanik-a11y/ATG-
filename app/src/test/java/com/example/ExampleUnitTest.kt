package com.example

import com.example.core.common.ProjectNameValidation
import com.example.core.common.ProjectPathValidator
import com.example.core.common.WorkspacePathMapper
import com.example.domain.model.SettingsCategory
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.nio.file.Files

class ExampleUnitTest {

    @get:Rule
    val tempFolder = TemporaryFolder()

    @Test
    fun `valid project name produces sanitized slug`() {
        val result = ProjectPathValidator.validateProjectName("  My API Service  ")
        assertTrue(result is ProjectNameValidation.Valid)
        val valid = result as ProjectNameValidation.Valid
        assertEquals("My API Service", valid.sanitizedName)
        assertEquals("my-api-service", valid.folderSlug)
    }

    @Test
    fun `project name rejects traversal and separators`() {
        assertTrue(ProjectPathValidator.validateProjectName("") is ProjectNameValidation.Invalid)
        assertTrue(ProjectPathValidator.validateProjectName("../escape") is ProjectNameValidation.Invalid)
        assertTrue(ProjectPathValidator.validateProjectName("foo/bar") is ProjectNameValidation.Invalid)
        assertTrue(ProjectPathValidator.validateProjectName("foo\\bar") is ProjectNameValidation.Invalid)
        assertTrue(ProjectPathValidator.validateProjectName("bad\u0000name") is ProjectNameValidation.Invalid)
    }

    @Test
    fun `resolveWithinRoot rejects parent traversal, absolute paths, and symlink escapes`() {
        val root = tempFolder.newFolder("workspace")
        val outside = tempFolder.newFolder("outside")
        val secretFile = File(outside, "secret.txt").apply { writeText("top-secret") }

        val inside = ProjectPathValidator.resolveWithinRoot(root, "src/Main.kt")
        assertTrue(inside.isSuccess)
        assertEquals(File(root, "src/Main.kt").canonicalPath, inside.getOrThrow().canonicalPath)

        assertTrue(ProjectPathValidator.resolveWithinRoot(root, "../../etc/passwd").isFailure)
        assertTrue(ProjectPathValidator.resolveWithinRoot(root, "/etc/passwd").isFailure)
        assertTrue(ProjectPathValidator.resolveWithinRoot(root, "a\\b").isFailure)
        assertTrue(ProjectPathValidator.resolveWithinRoot(root, "a\u0000b").isFailure)

        val linkPath = File(root, "escape-link").toPath()
        val symlinkCreated = runCatching {
            Files.createSymbolicLink(linkPath, outside.toPath())
        }.isSuccess
        if (symlinkCreated) {
            assertTrue(ProjectPathValidator.resolveWithinRoot(root, "escape-link/secret.txt").isFailure)
            assertTrue(secretFile.exists())
        }
    }

    @Test
    fun `workspace path mapper maps confined runtime paths`() {
        val mapped = WorkspacePathMapper.toRuntimePath("demo-123", "src/App.kt")
        assertTrue(mapped.isSuccess)
        assertEquals("/workspace/demo-123/src/App.kt", mapped.getOrThrow())

        assertTrue(WorkspacePathMapper.toRuntimePath("demo-123", "../outside").isFailure)
        assertTrue(WorkspacePathMapper.toRuntimePath("demo-123", "/etc/passwd").isFailure)
    }

    @Test
    fun `settings categories match approved 10 item order`() {
        val expected = listOf(
            "Gateway", "GitHub", "Agent", "Runtime", "Models",
            "Security", "Appearance", "Notifications", "Storage", "About",
        )
        assertEquals(expected, SettingsCategory.entries.map { it.title })
    }
}
