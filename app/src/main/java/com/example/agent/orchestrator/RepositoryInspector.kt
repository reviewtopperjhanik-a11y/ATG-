package com.example.agent.orchestrator

import com.example.core.common.ProjectPathValidator
import com.example.domain.model.ProjectInspection
import com.example.domain.model.ProjectStack
import com.example.domain.model.ValidationCommand
import com.example.domain.model.ValidationKind
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.File

/**
 * Inspects a workspace root cleanly to produce a compact project map, stack classification,
 * Git metadata, and real build/test/lint validation commands while excluding secrets and build caches.
 */
object RepositoryInspector {

    private val excludedDirNames = setOf(
        ".git",
        ".gradle",
        "build",
        "node_modules",
        "target",
        "__pycache__",
        ".venv",
        "venv",
        ".idea",
        ".agt-checkpoints",
        "dist",
    )

    private val secretFileNames = setOf(
        ".env",
        ".env.local",
        ".env.production",
        "local.properties",
        "secrets.properties",
        "google-services.json",
    )

    private val secretExtensions = setOf("pem", "key", "p12", "jks", "keystore", "crt")

    suspend fun inspect(
        projectId: String,
        projectRootPath: String,
        recentTaskSummaries: List<String> = emptyList(),
    ): Result<ProjectInspection> = withContext(Dispatchers.IO) {
        runCatching {
            val root = File(projectRootPath).canonicalFile
            require(root.exists() && root.isDirectory) {
                "Workspace directory does not exist: $projectRootPath"
            }

            val gitignoreRules = readGitignorePatterns(root)
            val directoryMap = mutableListOf<String>()
            val entryPoints = mutableListOf<String>()
            collectProjectFiles(
                root = root,
                dir = root,
                gitignoreRules = gitignoreRules,
                outFiles = directoryMap,
                maxFiles = 200,
                depth = 0,
            )

            val topLevelNames = root.listFiles()?.map { it.name }?.toSet().orEmpty()
            val stack = detectStack(topLevelNames, directoryMap)
            val manifests = collectManifestSummaries(root, topLevelNames)
            val validationCommands = detectValidationCommands(root, topLevelNames, directoryMap, stack)

            // Identify key entry points from indexed files
            directoryMap.forEach { rel ->
                val fileName = rel.substringAfterLast('/')
                if (fileName in ENTRY_POINT_NAMES || rel.endsWith("MainActivity.kt") || rel.endsWith("App.tsx")) {
                    entryPoints += rel
                }
            }

            val gitDir = File(root, ".git")
            val isGitRepo = gitDir.exists() && gitDir.isDirectory
            val gitHead = if (isGitRepo) readGitHead(gitDir) else null

            ProjectInspection(
                projectId = projectId,
                projectRootPath = root.absolutePath,
                stack = stack,
                stackLabel = stack.label,
                entryPoints = entryPoints.take(10),
                manifestSummaries = manifests,
                directoryMap = directoryMap,
                validationCommands = validationCommands,
                isGitRepo = isGitRepo,
                gitHead = gitHead,
                gitStatusSummary = if (isGitRepo) "${directoryMap.size} tracked/workspace file(s) indexed" else null,
                recentTaskSummaries = recentTaskSummaries,
            )
        }
    }

    fun isSecretOrExcludedFile(relativePath: String): Boolean {
        val normalized = relativePath.replace('\\', '/')
        val segments = normalized.split('/')
        if (segments.any { it in excludedDirNames }) return true
        val fileName = segments.lastOrNull().orEmpty()
        if (fileName in secretFileNames || fileName.startsWith(".env.")) return true
        val ext = fileName.substringAfterLast('.', "").lowercase()
        return ext in secretExtensions
    }

    private fun collectProjectFiles(
        root: File,
        dir: File,
        gitignoreRules: Set<String>,
        outFiles: MutableList<String>,
        maxFiles: Int,
        depth: Int,
    ) {
        if (depth > 8 || outFiles.size >= maxFiles) return
        val children = dir.listFiles()
            ?.sortedWith(compareBy<File> { !it.isDirectory }.thenBy { it.name.lowercase() })
            .orEmpty()
        for (child in children) {
            if (outFiles.size >= maxFiles) break
            val rel = runCatching {
                child.canonicalFile.relativeTo(root).path.replace('\\', '/')
            }.getOrNull() ?: continue
            // Ensure symlink doesn't escape workspace root
            if (ProjectPathValidator.resolveWithinRoot(root, rel).isFailure) continue
            if (child.name in excludedDirNames || child.name in gitignoreRules) continue

            if (child.isDirectory) {
                collectProjectFiles(root, child, gitignoreRules, outFiles, maxFiles, depth + 1)
            } else if (!isSecretOrExcludedFile(rel)) {
                outFiles += rel
            }
        }
    }

    private fun readGitignorePatterns(root: File): Set<String> {
        val gitignore = File(root, ".gitignore")
        if (!gitignore.isFile) return emptySet()
        return runCatching {
            gitignore.readLines()
                .map { it.trim() }
                .filter { it.isNotEmpty() && !it.startsWith("#") && !it.startsWith("!") }
                .map { it.trimEnd('/').removePrefix("./") }
                .filter { !it.contains('*') && !it.contains('/') }
                .toSet()
        }.getOrDefault(emptySet())
    }

    private fun detectStack(topLevelNames: Set<String>, files: List<String>): ProjectStack {
        return when {
            "build.gradle.kts" in topLevelNames || "build.gradle" in topLevelNames -> ProjectStack.GRADLE_JVM
            "package.json" in topLevelNames -> ProjectStack.NODE_JS
            "pyproject.toml" in topLevelNames || "requirements.txt" in topLevelNames || files.any { it.endsWith(".py") } ->
                ProjectStack.PYTHON
            "Cargo.toml" in topLevelNames -> ProjectStack.RUST
            "go.mod" in topLevelNames -> ProjectStack.GO
            "Makefile" in topLevelNames || files.any { it.endsWith(".sh") } -> ProjectStack.SHELL_MAKE
            "index.html" in topLevelNames || files.any { it.endsWith(".html") } -> ProjectStack.WEB_STATIC
            else -> ProjectStack.UNKNOWN
        }
    }

    private fun collectManifestSummaries(root: File, topLevelNames: Set<String>): List<String> {
        val summaries = mutableListOf<String>()
        if ("package.json" in topLevelNames) {
            runCatching {
                val obj = JSONObject(File(root, "package.json").readText())
                val name = obj.optString("name", "node-app")
                val scripts = obj.optJSONObject("scripts")?.keys()?.asSequence()?.toList().orEmpty()
                val depsCount = obj.optJSONObject("dependencies")?.length() ?: 0
                summaries += "package.json ($name, $depsCount deps, scripts: ${scripts.joinToString(", ").ifBlank { "none" }})"
            }
        }
        if ("pyproject.toml" in topLevelNames) {
            summaries += "pyproject.toml present"
        }
        if ("requirements.txt" in topLevelNames) {
            val count = runCatching {
                File(root, "requirements.txt").readLines().count { it.isNotBlank() && !it.trimStart().startsWith("#") }
            }.getOrDefault(0)
            summaries += "requirements.txt ($count requirement line(s))"
        }
        if ("build.gradle.kts" in topLevelNames || "build.gradle" in topLevelNames) {
            summaries += "Gradle build configuration present"
        }
        if ("Cargo.toml" in topLevelNames) {
            summaries += "Cargo.toml (Rust crate)"
        }
        if ("go.mod" in topLevelNames) {
            summaries += "go.mod (Go module)"
        }
        return summaries
    }

    private fun detectValidationCommands(
        root: File,
        topLevelNames: Set<String>,
        files: List<String>,
        stack: ProjectStack,
    ): List<ValidationCommand> {
        val commands = mutableListOf<ValidationCommand>()
        when (stack) {
            ProjectStack.NODE_JS -> {
                val pkgFile = File(root, "package.json")
                val scripts = runCatching { JSONObject(pkgFile.readText()).optJSONObject("scripts") }.getOrNull()
                if (scripts != null) {
                    if (scripts.has("typecheck")) {
                        commands += ValidationCommand("node-typecheck", "Type Check", "npm run typecheck", ValidationKind.TYPECHECK)
                    }
                    if (scripts.has("lint")) {
                        commands += ValidationCommand("node-lint", "Lint", "npm run lint", ValidationKind.LINT)
                    }
                    if (scripts.has("build")) {
                        commands += ValidationCommand("node-build", "Build", "npm run build", ValidationKind.BUILD)
                    }
                    val testCmd = scripts.optString("test")
                    if (scripts.has("test") && !testCmd.contains("no test specified", ignoreCase = true)) {
                        commands += ValidationCommand("node-test", "Unit Tests", "npm test", ValidationKind.TEST)
                    }
                }
                if (commands.isEmpty()) {
                    val jsFiles = files.filter { it.endsWith(".js") || it.endsWith(".mjs") }.take(8)
                    if (jsFiles.isNotEmpty()) {
                        val args = jsFiles.joinToString(" ") { posixQuote(it) }
                        commands += ValidationCommand(
                            id = "node-syntax",
                            label = "JS Syntax Check",
                            command = "for f in $args; do node --check \"\$f\" || exit 1; done",
                            kind = ValidationKind.LINT,
                        )
                    }
                }
            }
            ProjectStack.PYTHON -> {
                val pyFiles = files.filter { it.endsWith(".py") }
                val hasTests = pyFiles.any {
                    val name = it.substringAfterLast('/')
                    name.startsWith("test_") || name.endsWith("_test.py") || it.startsWith("tests/")
                }
                if ("pytest.ini" in topLevelNames || hasTests) {
                    commands += ValidationCommand(
                        id = "py-test",
                        label = "Python Tests",
                        command = "if command -v pytest >/dev/null 2>&1; then pytest -q; else python3 -m unittest discover -v; fi",
                        kind = ValidationKind.TEST,
                    )
                } else if (pyFiles.isNotEmpty()) {
                    val args = pyFiles.take(15).joinToString(" ") { posixQuote(it) }
                    commands += ValidationCommand(
                        id = "py-compile",
                        label = "Python Syntax Check",
                        command = "python3 -m py_compile $args",
                        kind = ValidationKind.LINT,
                    )
                }
            }
            ProjectStack.GRADLE_JVM -> {
                val hasWrapper = "gradlew" in topLevelNames
                val runner = if (hasWrapper) "if [ -x ./gradlew ]; then ./gradlew; else sh ./gradlew; fi" else "gradle"
                commands += ValidationCommand(
                    id = "gradle-test",
                    label = "Gradle Test",
                    command = if (hasWrapper) "sh ./gradlew test" else "gradle test",
                    kind = ValidationKind.TEST,
                )
            }
            ProjectStack.RUST -> {
                commands += ValidationCommand("cargo-check", "Cargo Check", "cargo check", ValidationKind.BUILD)
                commands += ValidationCommand("cargo-test", "Cargo Test", "cargo test", ValidationKind.TEST)
            }
            ProjectStack.GO -> {
                commands += ValidationCommand("go-build", "Go Build", "go build ./...", ValidationKind.BUILD)
                commands += ValidationCommand("go-test", "Go Test", "go test ./...", ValidationKind.TEST)
            }
            ProjectStack.SHELL_MAKE -> {
                val makefile = File(root, "Makefile")
                val testSh = files.firstOrNull { it == "test.sh" || it == "run_tests.sh" }
                if (makefile.isFile && makefile.readText().contains(Regex("^test:", RegexOption.MULTILINE))) {
                    commands += ValidationCommand("make-test", "Make Test", "make test", ValidationKind.TEST)
                } else if (testSh != null) {
                    commands += ValidationCommand("script-test", "Run $testSh", "sh ${posixQuote(testSh)}", ValidationKind.TEST)
                } else {
                    val shFiles = files.filter { it.endsWith(".sh") }.take(10)
                    if (shFiles.isNotEmpty()) {
                        val args = shFiles.joinToString(" ") { posixQuote(it) }
                        commands += ValidationCommand(
                            id = "sh-syntax",
                            label = "Shell Syntax Check",
                            command = "for f in $args; do sh -n \"\$f\" || exit 1; done",
                            kind = ValidationKind.LINT,
                        )
                    }
                }
            }
            ProjectStack.WEB_STATIC, ProjectStack.UNKNOWN -> {
                // Only add checks if executable scripts or tests exist
                val testSh = files.firstOrNull { it == "test.sh" || it == "run_tests.sh" }
                if (testSh != null) {
                    commands += ValidationCommand("script-test", "Run $testSh", "sh ${posixQuote(testSh)}", ValidationKind.TEST)
                }
            }
        }
        return commands
    }

    private fun readGitHead(gitDir: File): String? {
        val headFile = File(gitDir, "HEAD")
        if (!headFile.isFile) return null
        val content = runCatching { headFile.readText().trim() }.getOrNull() ?: return null
        return if (content.startsWith("ref: ")) {
            content.removePrefix("ref: ").removePrefix("refs/heads/")
        } else {
            content.take(12)
        }
    }

    private fun posixQuote(value: String): String =
        "'" + value.replace("'", "'\"'\"'") + "'"

    private val ENTRY_POINT_NAMES = setOf(
        "main.py", "app.py", "index.js", "index.ts", "server.js", "server.ts",
        "main.rs", "lib.rs", "main.go", "index.html", "README.md",
    )
}
