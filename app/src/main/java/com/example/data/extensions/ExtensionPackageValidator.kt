package com.example.data.extensions

import com.example.core.common.AgtError
import com.example.core.common.ProjectPathValidator
import com.example.core.security.GatewayUrlValidation
import com.example.core.security.GatewayUrlValidator
import com.example.domain.model.ExtensionAgentTarget
import com.example.domain.model.ExtensionCategory
import com.example.domain.model.ExtensionManifest
import com.example.domain.model.ExtensionPackageFormat
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.nio.charset.StandardCharsets
import java.security.MessageDigest

/**
 * Validates extension manifests, SKILL.md documents, integrity hashes, GitHub/archive sources,
 * path confinement, MCP server endpoints & PRoot stdio commands, and JS/TS plugin source bundles
 * (strictly blocking native binaries / DCL payloads per Google Play policy).
 */
object ExtensionPackageValidator {

    private val idPattern = Regex("^[a-zA-Z0-9._-]{2,80}$")
    private val shaPattern = Regex("^(?:sha256:)?([a-fA-F0-9]{64})$")
    private val githubShortRepoRegex = Regex("^([a-zA-Z0-9._-]+)/([a-zA-Z0-9._-]+)(?:/(.+))?$")
    private val githubUrlRegex = Regex(
        "^https?://(?:www\\.)?github\\.com/([^/]+)/([^/]+?)(?:\\.git)?(?:/(tree|blob)/([^/]+)(?:/(.*))?)?/?$",
        RegexOption.IGNORE_CASE
    )
    private val allowedDocExtensions = setOf("md", "markdown", "txt", "json", "yaml", "yml", "toml", "license")
    private val allowedPluginCodeExtensions = setOf("ts", "mts", "cts", "js", "mjs", "cjs")
    private val allowedPluginExtensions = allowedDocExtensions + allowedPluginCodeExtensions + setOf("lock")
    private val forbiddenBinaryExtensions = setOf("so", "dex", "jar", "apk", "exe", "dll", "dylib", "wasm", "bin", "elf")

    val ALLOWED_LOCAL_MCP_EXECUTABLES = setOf("npx", "node", "uvx", "python3", "bunx")
    private val disallowedShellTokens = listOf("&&", "||", ";", "|", "`", "$(", ">", "<", "\n", "\r", "\u0000")
    private val disallowedSensitiveCommandPatterns = listOf(
        "agt_gateway_vault",
        "agt_github_vault",
        "agt_extension_vault",
        "agt-cred-",
        "agt-askpass-",
        "last_crash.json",
        "/proc/self/environ",
        "masterkey",
        "encryptedsharedpreferences",
        "keystore",
    )

    data class ResolvedSkillSource(
        val candidateUrls: List<String>,
        val archiveSubpath: String? = null,
        val suggestedId: String? = null,
        val formatHint: ExtensionPackageFormat? = null,
        val docsUrl: String? = null,
        val agentTargetHint: ExtensionAgentTarget? = null,
    )

    data class StagedSkillCopySummary(
        val manifest: ExtensionManifest,
        val copiedDocFiles: List<String>,
        val skippedScriptCount: Int,
        val packageDigestSha256: String,
    )

    data class StagedPluginCopySummary(
        val manifest: ExtensionManifest,
        val entrypointRelPath: String,
        val copiedFiles: List<String>,
        val packageDigestSha256: String,
    )

    fun validateExtensionId(rawId: String): Result<String> = runCatching {
        val trimmed = rawId.trim()
        require(trimmed.isNotEmpty()) { "Extension ID cannot be empty." }
        require(!trimmed.contains("..") && !trimmed.contains('/') && !trimmed.contains('\\')) {
            "Extension ID contains disallowed path characters."
        }
        require(idPattern.matches(trimmed)) {
            "Extension ID must be 2–80 characters (letters, numbers, dots, underscores, hyphens)."
        }
        trimmed
    }

    fun validateLocalMcpCommand(rawCommand: String): Result<List<String>> = runCatching {
        val trimmed = rawCommand.trim()
        require(trimmed.isNotEmpty()) { "Local MCP command cannot be empty." }
        disallowedShellTokens.forEach { bad ->
            if (trimmed.contains(bad)) {
                throw IllegalArgumentException(
                    "Local MCP command cannot contain shell operators or control characters ('$bad')."
                )
            }
        }
        val tokens = tokenizeCommandArgs(trimmed)
        validateLocalMcpCommandTokens(tokens).getOrThrow()
    }

    fun validateLocalMcpCommandTokens(tokens: List<String>): Result<List<String>> = runCatching {
        require(tokens.isNotEmpty()) { "Local MCP command requires an executable and arguments." }
        require(tokens.size <= 24) { "Local MCP command exceeds maximum argument count (24)." }
        val cleaned = tokens.map { it.trim() }.filter { it.isNotEmpty() }
        require(cleaned.isNotEmpty()) { "Local MCP command cannot be empty." }

        val joinedLower = cleaned.joinToString(" ").lowercase()
        disallowedShellTokens.forEach { bad ->
            if (cleaned.any { it.contains(bad) }) {
                throw IllegalArgumentException("Disallowed shell operator or character '$bad' in MCP command.")
            }
        }
        disallowedSensitiveCommandPatterns.forEach { pattern ->
            if (joinedLower.contains(pattern)) {
                throw IllegalArgumentException("Local MCP command references protected credential storage ($pattern).")
            }
        }

        val exe = cleaned.first()
        val exeBasename = exe.substringAfterLast('/')
        require(exeBasename in ALLOWED_LOCAL_MCP_EXECUTABLES) {
            "Executable '$exeBasename' is not allowed for sandboxed PRoot MCP. Allowed: ${ALLOWED_LOCAL_MCP_EXECUTABLES.sorted().joinToString(", ")}"
        }
        cleaned
    }

    private fun tokenizeCommandArgs(raw: String): List<String> {
        val tokens = mutableListOf<String>()
        val current = StringBuilder()
        var quoteChar: Char? = null
        var i = 0
        while (i < raw.length) {
            val ch = raw[i]
            when {
                quoteChar != null -> {
                    if (ch == quoteChar) {
                        quoteChar = null
                    } else if (ch == '\\' && quoteChar == '"' && i + 1 < raw.length) {
                        i++
                        current.append(raw[i])
                    } else {
                        current.append(ch)
                    }
                }
                ch == '\'' || ch == '"' -> {
                    quoteChar = ch
                }
                ch.isWhitespace() -> {
                    if (current.isNotEmpty()) {
                        tokens += current.toString()
                        current.clear()
                    }
                }
                else -> current.append(ch)
            }
            i++
        }
        require(quoteChar == null) { "Unclosed quote in local MCP command." }
        if (current.isNotEmpty()) {
            tokens += current.toString()
        }
        return tokens
    }

    fun sha256Hex(bytes: ByteArray): String {
        val digest = MessageDigest.getInstance("SHA-256").digest(bytes)
        return digest.joinToString("") { "%02x".format(it) }
    }

    fun sha256HexFile(file: File): String {
        val md = MessageDigest.getInstance("SHA-256")
        val buf = ByteArray(64 * 1024)
        file.inputStream().use { input ->
            while (true) {
                val n = input.read(buf)
                if (n <= 0) break
                md.update(buf, 0, n)
            }
        }
        return md.digest().joinToString("") { "%02x".format(it) }
    }

    fun verifySha256(bytes: ByteArray, expectedDigest: String?): Result<String> {
        val actual = sha256Hex(bytes)
        return compareDigest(actual, expectedDigest)
    }

    fun verifyFileSha256(file: File, expectedDigest: String?): Result<String> {
        val actual = sha256HexFile(file)
        return compareDigest(actual, expectedDigest)
    }

    private fun compareDigest(actual: String, expectedDigest: String?): Result<String> {
        val trimmed = expectedDigest?.trim().orEmpty()
        if (trimmed.isEmpty()) {
            return Result.success(actual)
        }
        val match = shaPattern.matchEntire(trimmed)
            ?: return Result.failure(
                IllegalArgumentException("Invalid SHA-256 format. Expected 64 hexadecimal characters.")
            )
        val normalizedExpected = match.groupValues[1].lowercase()
        if (actual != normalizedExpected) {
            return Result.failure(
                IllegalStateException(
                    "Extension integrity verification failed (expected ${normalizedExpected.take(12)}…, got ${actual.take(12)}…)."
                )
            )
        }
        return Result.success(actual)
    }

    fun resolveConfinedSkillFile(extensionsRoot: File, extensionId: String): Result<File> = runCatching {
        val cleanId = validateExtensionId(extensionId).getOrThrow()
        ProjectPathValidator.resolveWithinRoot(extensionsRoot, "$cleanId/SKILL.md").getOrThrow()
    }

    fun resolveConfinedPluginEntrypoint(
        extensionsRoot: File,
        extensionId: String,
        entrypointRelPath: String,
    ): Result<File> = runCatching {
        val cleanId = validateExtensionId(extensionId).getOrThrow()
        val cleanEntry = entrypointRelPath.trim().trimStart('/').replace('\\', '/')
        require(cleanEntry.isNotEmpty() && !cleanEntry.contains("..")) {
            "Invalid plugin entrypoint path '$entrypointRelPath'."
        }
        val ext = cleanEntry.substringAfterLast('.', "").lowercase()
        require(ext in allowedPluginCodeExtensions) {
            "Plugin entrypoint must be an interpreted JS/TS file (.ts/.js/.mjs/.cjs)."
        }
        ProjectPathValidator.resolveWithinRoot(extensionsRoot, "$cleanId/$cleanEntry").getOrThrow()
    }

    fun resolveConfinedExtensionDir(extensionsRoot: File, extensionId: String): Result<File> = runCatching {
        val cleanId = validateExtensionId(extensionId).getOrThrow()
        ProjectPathValidator.resolveWithinRoot(extensionsRoot, cleanId).getOrThrow()
    }

    fun validateMcpEndpoint(rawUrl: String): GatewayUrlValidation =
        GatewayUrlValidator.validate(rawUrl, allowQueryAndFragment = true)

    fun validateResourceUrl(rawUrl: String): GatewayUrlValidation =
        GatewayUrlValidator.validate(rawUrl, allowQueryAndFragment = true)

    /**
     * Resolves GitHub URLs, `owner/repo(/subpath)` shorthand, `.zip`/`.tar.gz` URLs,
     * and raw SKILL.md / `.ts` / `.js` URLs into safe downloadable HTTPS candidate URLs.
     */
    fun resolveRemoteSource(rawInput: String): ResolvedSkillSource? {
        val trimmed = rawInput.trim()
        if (trimmed.isEmpty() || trimmed.startsWith("{") || trimmed.startsWith("---")) return null
        if (trimmed.contains('\n') || trimmed.contains('\r') || trimmed.contains(' ')) return null

        val ghMatch = githubUrlRegex.matchEntire(trimmed)
        if (ghMatch != null) {
            val owner = ghMatch.groupValues[1]
            val repo = ghMatch.groupValues[2]
            val mode = ghMatch.groupValues[3].lowercase()
            val branch = ghMatch.groupValues[4].takeIf { it.isNotBlank() }
            val subpath = ghMatch.groupValues[5].trim('/').takeIf { it.isNotEmpty() }
            val agentHint = inferAgentTarget(owner, repo, subpath)

            if (mode == "blob" && branch != null && subpath != null) {
                val rawUrl = "https://raw.githubusercontent.com/$owner/$repo/$branch/$subpath"
                val fileExt = subpath.substringAfterLast('.', "").lowercase()
                val isCodeFile = fileExt in allowedPluginCodeExtensions
                val leaf = if (isCodeFile) {
                    subpath.substringAfterLast('/').substringBeforeLast('.')
                } else {
                    subpath.substringBeforeLast('/').substringAfterLast('/', repo)
                }
                return ResolvedSkillSource(
                    candidateUrls = listOf(rawUrl),
                    suggestedId = slugifyId("${if (isCodeFile) "plugin" else "skill"}.$owner.$leaf"),
                    formatHint = if (isCodeFile) ExtensionPackageFormat.JS_TS_PLUGIN else ExtensionPackageFormat.RAW_SKILL_MD,
                    docsUrl = trimmed,
                    agentTargetHint = agentHint,
                )
            }

            val urls = if (branch != null) {
                listOf("https://github.com/$owner/$repo/archive/refs/heads/$branch.zip")
            } else {
                listOf(
                    "https://github.com/$owner/$repo/archive/refs/heads/main.zip",
                    "https://github.com/$owner/$repo/archive/refs/heads/master.zip",
                )
            }
            val leafName = subpath?.substringAfterLast('/') ?: repo
            return ResolvedSkillSource(
                candidateUrls = urls,
                archiveSubpath = subpath,
                suggestedId = slugifyId("skill.$owner.$leafName"),
                formatHint = ExtensionPackageFormat.ZIP,
                docsUrl = trimmed,
                agentTargetHint = agentHint,
            )
        }

        val isHttpUrl = trimmed.startsWith("https://", ignoreCase = true) ||
            trimmed.startsWith("http://", ignoreCase = true)
        if (isHttpUrl) {
            val lower = trimmed.lowercase().substringBefore('?').substringBefore('#')
            val ext = lower.substringAfterLast('.', "")
            val hint = when {
                lower.endsWith(".tar.gz") || lower.endsWith(".tgz") -> ExtensionPackageFormat.TAR_GZ
                lower.endsWith(".zip") -> ExtensionPackageFormat.ZIP
                ext in allowedPluginCodeExtensions -> ExtensionPackageFormat.JS_TS_PLUGIN
                else -> null
            }
            return ResolvedSkillSource(
                candidateUrls = listOf(trimmed),
                formatHint = hint,
                docsUrl = trimmed,
            )
        }

        val shortMatch = githubShortRepoRegex.matchEntire(trimmed)
        if (shortMatch != null && !trimmed.contains(':')) {
            val owner = shortMatch.groupValues[1]
            val repo = shortMatch.groupValues[2]
            val subpath = shortMatch.groupValues[3].trim('/').takeIf { it.isNotEmpty() }
            val leafName = subpath?.substringAfterLast('/') ?: repo
            return ResolvedSkillSource(
                candidateUrls = listOf(
                    "https://github.com/$owner/$repo/archive/refs/heads/main.zip",
                    "https://github.com/$owner/$repo/archive/refs/heads/master.zip",
                ),
                archiveSubpath = subpath,
                suggestedId = slugifyId("skill.$owner.$leafName"),
                formatHint = ExtensionPackageFormat.ZIP,
                docsUrl = "https://github.com/$owner/$repo" + if (subpath != null) "/tree/main/$subpath" else "",
                agentTargetHint = inferAgentTarget(owner, repo, subpath),
            )
        }

        return null
    }

    fun inferAgentTarget(vararg hints: String?): ExtensionAgentTarget {
        val joined = hints.filterNotNull().joinToString(" ").lowercase()
        val hasPi = Regex("\\bpi\\b|pi-skills|pi-mono|pi-agent").containsMatchIn(joined)
        val hasOpenCode = joined.contains("opencode") || joined.contains("superpowers")
        return when {
            hasPi && !hasOpenCode -> ExtensionAgentTarget.PI
            hasOpenCode && !hasPi -> ExtensionAgentTarget.OPENCODE
            else -> ExtensionAgentTarget.UNIVERSAL
        }
    }

    fun detectFormatFromHeaderOrHint(
        firstBytes: ByteArray,
        hint: ExtensionPackageFormat?,
        sourceUrl: String,
    ): ExtensionPackageFormat {
        if (firstBytes.size >= 4 &&
            firstBytes[0] == 0x50.toByte() &&
            firstBytes[1] == 0x4B.toByte() &&
            firstBytes[2] == 0x03.toByte() &&
            firstBytes[3] == 0x04.toByte()
        ) {
            return ExtensionPackageFormat.ZIP
        }
        if (firstBytes.size >= 2 &&
            firstBytes[0] == 0x1F.toByte() &&
            firstBytes[1] == 0x8B.toByte()
        ) {
            return ExtensionPackageFormat.TAR_GZ
        }
        if (hint == ExtensionPackageFormat.ZIP ||
            hint == ExtensionPackageFormat.TAR_GZ ||
            hint == ExtensionPackageFormat.JS_TS_PLUGIN
        ) {
            return hint
        }
        val cleanUrl = sourceUrl.lowercase().substringBefore('?').substringBefore('#')
        val ext = cleanUrl.substringAfterLast('.', "")
        return when {
            cleanUrl.endsWith(".tar.gz") || cleanUrl.endsWith(".tgz") -> ExtensionPackageFormat.TAR_GZ
            cleanUrl.endsWith(".zip") -> ExtensionPackageFormat.ZIP
            ext in allowedPluginCodeExtensions -> ExtensionPackageFormat.JS_TS_PLUGIN
            else -> ExtensionPackageFormat.RAW_SKILL_MD
        }
    }

    /**
     * Locates the directory containing `SKILL.md` inside an extracted archive staging folder.
     * Unwraps single GitHub root directories (e.g. `repo-main/`) and resolves optional [archiveSubpath].
     */
    fun findSkillRootInExtractedDir(stagingRoot: File, archiveSubpath: String?): Result<File> = runCatching {
        val unwrapped = unwrapArchiveBase(stagingRoot, archiveSubpath)

        if (unwrapped.isFile && unwrapped.name.equals("SKILL.md", ignoreCase = true)) {
            return@runCatching unwrapped.parentFile ?: stagingRoot.canonicalFile
        }
        if (File(unwrapped, "SKILL.md").isFile) {
            return@runCatching unwrapped
        }
        val found = unwrapped.walkTopDown()
            .maxDepth(3)
            .firstOrNull { it.isFile && it.name.equals("SKILL.md", ignoreCase = true) }
            ?: throw IllegalStateException(
                valCleanSubMessage(archiveSubpath, "Downloaded archive does not contain a SKILL.md package.")
            )
        found.parentFile ?: unwrapped
    }

    /**
     * Locates the plugin root directory and JS/TS entrypoint (`index.ts`, `plugin.ts`,
     * `package.json` `main`/`opencode.plugin`/`pi.extensions`, or sole `.ts`/`.js` file)
     * inside an extracted plugin archive.
     */
    fun findPluginRootAndEntrypoint(
        stagingRoot: File,
        archiveSubpath: String?,
        preferredEntrypoint: String? = null,
    ): Result<Pair<File, String>> = runCatching {
        val candidate = unwrapArchiveBase(stagingRoot, archiveSubpath)
        if (candidate.isFile) {
            val ext = candidate.extension.lowercase()
            require(ext in allowedPluginCodeExtensions) {
                "Plugin file '${candidate.name}' must be an interpreted JS/TS module."
            }
            return@runCatching (candidate.parentFile ?: stagingRoot.canonicalFile) to candidate.name
        }

        val cleanPref = preferredEntrypoint?.trim()?.trimStart('/')?.replace('\\', '/')
        if (!cleanPref.isNullOrBlank()) {
            val resolved = ProjectPathValidator.resolveWithinRoot(candidate, cleanPref).getOrNull()
            if (resolved != null && resolved.isFile && resolved.extension.lowercase() in allowedPluginCodeExtensions) {
                return@runCatching candidate to cleanPref
            }
        }

        // Check package.json hints if present
        val pkgFile = File(candidate, "package.json")
        if (pkgFile.isFile) {
            val pkgObj = runCatching { JSONObject(pkgFile.readText(StandardCharsets.UTF_8)) }.getOrNull()
            if (pkgObj != null) {
                val pkgHints = buildList {
                    pkgObj.optJSONObject("pi")?.optJSONArray("extensions")?.let { arr ->
                        for (i in 0 until arr.length()) add(arr.optString(i))
                    }
                    pkgObj.optJSONObject("opencode")?.optString("plugin")?.let { add(it) }
                    add(pkgObj.optString("module"))
                    add(pkgObj.optString("main"))
                }.map { it.trim().removePrefix("./") }.filter { it.isNotBlank() }

                for (hintPath in pkgHints) {
                    val resolved = ProjectPathValidator.resolveWithinRoot(candidate, hintPath).getOrNull()
                    if (resolved != null && resolved.isFile && resolved.extension.lowercase() in allowedPluginCodeExtensions) {
                        return@runCatching candidate to hintPath
                    }
                }
            }
        }

        val standardCandidates = listOf(
            "index.ts", "index.mts", "index.js", "index.mjs", "index.cjs",
            "src/index.ts", "src/index.js", "plugin.ts", "plugin.js",
        )
        for (rel in standardCandidates) {
            val f = ProjectPathValidator.resolveWithinRoot(candidate, rel).getOrNull()
            if (f != null && f.isFile) {
                return@runCatching candidate to rel
            }
        }

        val topCodeFiles = candidate.listFiles()
            ?.filter { it.isFile && it.extension.lowercase() in allowedPluginCodeExtensions }
            ?.sortedBy { it.name }
            .orEmpty()
        if (topCodeFiles.isNotEmpty()) {
            return@runCatching candidate to topCodeFiles.first().name
        }

        val nestedCode = candidate.walkTopDown().maxDepth(3)
            .firstOrNull {
                it.isFile &&
                    !it.path.contains("/node_modules/") &&
                    it.extension.lowercase() in allowedPluginCodeExtensions
            }
            ?: throw IllegalStateException(
                valCleanSubMessage(archiveSubpath, "Downloaded plugin archive does not contain a JS/TS entrypoint (.ts/.js).")
            )
        val rootDir = nestedCode.parentFile ?: candidate
        rootDir to nestedCode.name
    }

    private fun unwrapArchiveBase(stagingRoot: File, archiveSubpath: String?): File {
        val stagingCanonical = stagingRoot.canonicalFile
        val rootChildren = stagingCanonical.listFiles()?.filter { it.name != "__MACOSX" }.orEmpty()
        val unwrappedRoot = if (
            rootChildren.size == 1 &&
            rootChildren[0].isDirectory &&
            !File(stagingCanonical, "SKILL.md").exists() &&
            !File(stagingCanonical, "package.json").exists()
        ) {
            rootChildren[0]
        } else {
            stagingCanonical
        }
        val cleanSub = archiveSubpath?.trim()?.trim('/')?.takeIf { it.isNotEmpty() }
        return if (cleanSub != null) {
            ProjectPathValidator.resolveWithinRoot(unwrappedRoot, cleanSub).getOrThrow()
        } else {
            unwrappedRoot
        }
    }

    private fun valCleanSubMessage(archiveSubpath: String?, fallback: String): String {
        val cleanSub = archiveSubpath?.trim()?.trim('/')?.takeIf { it.isNotEmpty() }
        return if (cleanSub != null) {
            "Requested subpath '$cleanSub' was not found in downloaded archive."
        } else {
            fallback
        }
    }

    /**
     * Copies validated non-executable skill docs (`SKILL.md`, markdown/json/yaml docs) from [sourceSkillDir]
     * into [destinationDir], strictly rejecting native binaries / DCL payloads (.so, .dex, .jar, .apk, .exe)
     * and omitting arbitrary executable scripts in Skill packages.
     */
    fun copyValidatedNonExecutableSkillDir(
        sourceSkillDir: File,
        destinationDir: File,
        sourceLabel: String,
        overrideManifestId: String? = null,
        overrideManifestName: String? = null,
        overrideDescription: String? = null,
        overridePublisher: String? = null,
        overrideVersion: String? = null,
        overrideLicense: String? = null,
    ): Result<StagedSkillCopySummary> = runCatching {
        val sourceCanon = sourceSkillDir.canonicalFile
        val rawSkillFile = sourceCanon.listFiles()
            ?.firstOrNull { it.isFile && it.name.equals("SKILL.md", ignoreCase = true) }
            ?: throw IllegalStateException("SKILL.md is missing from extracted package.")

        val rawSkillText = rawSkillFile.readText(StandardCharsets.UTF_8)
        val parsed = parseSkillInput(
            rawInput = rawSkillText,
            expectedSha256 = null,
            sourceLabel = sourceLabel,
        ).getOrThrow()

        val effectiveId = overrideManifestId?.let { validateExtensionId(it).getOrThrow() } ?: parsed.id
        val effectiveManifest = parsed.copy(
            id = effectiveId,
            name = overrideManifestName?.takeIf { it.isNotBlank() } ?: parsed.name,
            description = overrideDescription?.takeIf { it.isNotBlank() } ?: parsed.description,
            publisher = overridePublisher?.takeIf { it.isNotBlank() } ?: parsed.publisher,
            version = overrideVersion?.takeIf { it.isNotBlank() } ?: parsed.version,
            license = overrideLicense?.takeIf { it.isNotBlank() } ?: parsed.license,
        )

        val copiedFiles = mutableListOf<String>()
        var skippedScripts = 0
        var totalBytes = 0L

        if (destinationDir.exists()) {
            destinationDir.deleteRecursively()
        }
        destinationDir.mkdirs()

        sourceCanon.walkTopDown().maxDepth(4).forEach { file ->
            if (file == sourceCanon) return@forEach
            val relPath = file.relativeTo(sourceCanon).path.replace('\\', '/')
            if (relPath.startsWith(".git/") || relPath == ".git" || relPath.startsWith(".agt")) {
                return@forEach
            }
            if (file.isDirectory) return@forEach

            val ext = file.extension.lowercase()
            if (ext in forbiddenBinaryExtensions) {
                throw IllegalStateException(
                    "Extension package blocked: contains prohibited binary file '$relPath' (.$ext)."
                )
            }
            val isSkillMd = relPath.equals("SKILL.md", ignoreCase = true)
            if (isSkillMd) {
                val target = ProjectPathValidator.resolveWithinRoot(destinationDir, "SKILL.md").getOrThrow()
                target.parentFile?.mkdirs()
                target.writeText(effectiveManifest.skillMarkdown.orEmpty(), StandardCharsets.UTF_8)
                copiedFiles += "SKILL.md"
                totalBytes += file.length()
            } else if (ext in allowedDocExtensions || file.name.equals("README", ignoreCase = true)) {
                totalBytes += file.length()
                require(totalBytes <= 5L * 1024L * 1024L) {
                    "Skill documentation exceeds 5 MB limit."
                }
                val contentSample = runCatching { file.readText(StandardCharsets.UTF_8) }.getOrNull().orEmpty()
                if (!contentSample.contains('\u0000')) {
                    val target = ProjectPathValidator.resolveWithinRoot(destinationDir, relPath).getOrThrow()
                    target.parentFile?.mkdirs()
                    file.copyTo(target, overwrite = true)
                    copiedFiles += relPath
                }
            } else {
                skippedScripts++
            }
        }

        val canonicalSkillDest = File(destinationDir, "SKILL.md")
        require(canonicalSkillDest.isFile) { "Failed to stage SKILL.md cleanly." }
        val skillSha = sha256HexFile(canonicalSkillDest)

        StagedSkillCopySummary(
            manifest = effectiveManifest.copy(expectedSha256 = skillSha),
            copiedDocFiles = copiedFiles.sorted(),
            skippedScriptCount = skippedScripts,
            packageDigestSha256 = skillSha,
        )
    }

    /**
     * Stages an interpreted JS/TS OpenCode or Pi plugin bundle into [destinationDir].
     * Enforces Google Play System Integrity by blocking ALL native binaries / Dalvik code
     * (`.so`, `.dex`, `.jar`, `.apk`, `.exe`, `.wasm`, `.bin`, `.elf`) and permitting only
     * interpreted JS/TS source + docs executed inside PRoot Linux.
     */
    fun copyValidatedJsTsPluginDir(
        sourceDir: File,
        destinationDir: File,
        entrypointRelPath: String,
        sourceLabel: String,
        overrideManifestId: String? = null,
        overrideManifestName: String? = null,
        overrideDescription: String? = null,
        overridePublisher: String? = null,
        overrideVersion: String? = null,
        overrideLicense: String? = null,
    ): Result<StagedPluginCopySummary> = runCatching {
        val sourceCanon = sourceDir.canonicalFile
        val cleanEntry = entrypointRelPath.trim().trimStart('/').replace('\\', '/')
        val entryFile = ProjectPathValidator.resolveWithinRoot(sourceCanon, cleanEntry).getOrThrow()
        require(entryFile.isFile && entryFile.extension.lowercase() in allowedPluginCodeExtensions) {
            "Plugin entrypoint '$cleanEntry' missing or is not an interpreted JS/TS file."
        }

        val entryText = entryFile.readText(StandardCharsets.UTF_8)
        val parsedEntry = parsePluginInput(
            rawInput = entryText,
            expectedSha256 = null,
            sourceLabel = sourceLabel,
            defaultEntrypoint = entryFile.name,
        ).getOrThrow()

        // Also enrich from package.json if present
        val pkgJson = File(sourceCanon, "package.json").takeIf { it.isFile }?.let {
            runCatching { JSONObject(it.readText(StandardCharsets.UTF_8)) }.getOrNull()
        }
        val pkgName = pkgJson?.optString("name")?.trim()?.takeIf { it.isNotEmpty() }
        val pkgDesc = pkgJson?.optString("description")?.trim()?.takeIf { it.isNotEmpty() }
        val pkgVersion = pkgJson?.optString("version")?.trim()?.takeIf { it.isNotEmpty() }
        val pkgLicense = pkgJson?.optString("license")?.trim()?.takeIf { it.isNotEmpty() }

        val effectiveId = overrideManifestId?.let { validateExtensionId(it).getOrThrow() }
            ?: pkgName?.let { validateExtensionId(slugifyId("plugin.$it")).getOrNull() }
            ?: parsedEntry.id

        val effectiveManifest = parsedEntry.copy(
            id = effectiveId,
            name = overrideManifestName?.takeIf { it.isNotBlank() } ?: pkgName ?: parsedEntry.name,
            description = overrideDescription?.takeIf { it.isNotBlank() } ?: pkgDesc ?: parsedEntry.description,
            publisher = overridePublisher?.takeIf { it.isNotBlank() } ?: parsedEntry.publisher,
            version = overrideVersion?.takeIf { it.isNotBlank() } ?: pkgVersion ?: parsedEntry.version,
            license = overrideLicense?.takeIf { it.isNotBlank() } ?: pkgLicense ?: parsedEntry.license,
            category = ExtensionCategory.PLUGINS,
        )

        if (destinationDir.exists()) {
            destinationDir.deleteRecursively()
        }
        destinationDir.mkdirs()

        val copiedFiles = mutableListOf<String>()
        var totalBytes = 0L
        var fileCount = 0

        sourceCanon.walkTopDown().maxDepth(5).forEach { file ->
            if (file == sourceCanon) return@forEach
            val relPath = file.relativeTo(sourceCanon).path.replace('\\', '/')
            if (relPath.startsWith(".git/") || relPath == ".git" ||
                relPath.startsWith("node_modules/") || relPath == "node_modules" ||
                relPath.startsWith(".agt")
            ) {
                return@forEach
            }
            if (file.isDirectory) return@forEach

            val ext = file.extension.lowercase()
            if (ext in forbiddenBinaryExtensions) {
                throw IllegalStateException(
                    "Plugin package blocked (Play Policy): contains prohibited binary/DCL file '$relPath' (.$ext)."
                )
            }
            if (ext in allowedPluginExtensions || file.name.equals("README", ignoreCase = true)) {
                fileCount++
                require(fileCount <= 2_000) { "Plugin package exceeds 2,000 file limit." }
                totalBytes += file.length()
                require(totalBytes <= 15L * 1024L * 1024L) { "Plugin source exceeds 15 MB limit." }
                val rawBytes = file.readBytes()
                require(!rawBytes.contains(0.toByte())) {
                    "Plugin file '$relPath' contains binary null bytes and was rejected."
                }
                val target = ProjectPathValidator.resolveWithinRoot(destinationDir, relPath).getOrThrow()
                target.parentFile?.mkdirs()
                target.writeBytes(rawBytes)
                copiedFiles += relPath
            }
        }

        val stagedEntry = ProjectPathValidator.resolveWithinRoot(destinationDir, cleanEntry).getOrThrow()
        require(stagedEntry.isFile) { "Failed to stage plugin entrypoint '$cleanEntry'." }
        val entrySha = sha256HexFile(stagedEntry)

        StagedPluginCopySummary(
            manifest = effectiveManifest.copy(expectedSha256 = entrySha),
            entrypointRelPath = cleanEntry,
            copiedFiles = copiedFiles.sorted(),
            packageDigestSha256 = entrySha,
        )
    }

    /**
     * Parses a user-imported JS/TS Plugin (raw `.ts`/`.js` code or JSON plugin manifest)
     * strictly for Phase 6.2 PRoot Node/Bun execution (zero native DCL).
     */
    fun parsePluginInput(
        rawInput: String,
        expectedSha256: String? = null,
        sourceLabel: String = "manual://plugin-import",
        defaultEntrypoint: String = "index.ts",
    ): Result<ExtensionManifest> = runCatching {
        val trimmed = rawInput.trim()
        require(trimmed.isNotEmpty()) { "Paste JS/TS plugin code, a Plugin JSON manifest, or a GitHub/HTTPS URL." }
        require(!trimmed.contains('\u0000')) { "Plugin content contains invalid null bytes." }

        val manifest = if (trimmed.startsWith("{")) {
            parseJsonPluginManifest(trimmed, sourceLabel, defaultEntrypoint)
        } else {
            parseRawJsTsPlugin(trimmed, sourceLabel, defaultEntrypoint)
        }

        val effectiveExpectedSha = expectedSha256?.trim()?.takeIf { it.isNotEmpty() }
            ?: manifest.expectedSha256?.trim()?.takeIf { it.isNotEmpty() }
        val codeText = manifest.skillMarkdown.orEmpty()
        val verifiedSha = verifySha256(
            bytes = codeText.toByteArray(StandardCharsets.UTF_8),
            expectedDigest = effectiveExpectedSha,
        ).getOrThrow()

        manifest.copy(
            expectedSha256 = verifiedSha,
            category = ExtensionCategory.PLUGINS,
        )
    }

    private fun parseJsonPluginManifest(
        rawJson: String,
        sourceLabel: String,
        defaultEntrypoint: String,
    ): ExtensionManifest {
        val obj = JSONObject(rawJson)
        if (obj.has("nativeBinary") || obj.has("dexPath") || obj.has("soPath") || obj.has("wasmModule")) {
            throw IllegalStateException(
                "Native binaries, .dex, .so, and .wasm modules are strictly prohibited under Google Play integrity policy."
            )
        }
        val rawName = obj.optString("name").trim()
        require(rawName.isNotEmpty()) { "Plugin manifest is missing 'name'." }
        val rawId = obj.optString("id").trim().ifBlank { slugifyId("plugin.$rawName") }
        val cleanId = validateExtensionId(rawId).getOrThrow()
        val entry = obj.optString("entrypoint").trim().ifBlank { defaultEntrypoint }
        val entryExt = entry.substringAfterLast('.', "").lowercase()
        require(entryExt in allowedPluginCodeExtensions) {
            "Plugin entrypoint '$entry' must be .ts, .mts, .cts, .js, .mjs, or .cjs."
        }
        val code = obj.optString("code")
            .ifBlank { obj.optString("pluginCode") }
            .ifBlank { obj.optString("sourceCode") }
            .trim()
        require(code.isNotEmpty()) { "Plugin JSON manifest must include 'code' (JS/TS module source)." }

        val integrityObj = obj.optJSONObject("integrity")
        val manifestSha = integrityObj?.optString("sha256")?.trim()?.takeIf { it.isNotEmpty() }
            ?: obj.optString("sha256").trim().takeIf { it.isNotEmpty() }

        return ExtensionManifest(
            id = cleanId,
            name = rawName,
            description = obj.optString("description").trim().ifBlank { "Sandboxed JS/TS PRoot plugin ($entry)." },
            category = ExtensionCategory.PLUGINS,
            publisher = obj.optString("publisher").trim().ifBlank { "Community Plugin" },
            version = obj.optString("version").trim().ifBlank { "1.0.0" },
            license = obj.optString("license").trim().ifBlank { "MIT" },
            compatibility = obj.optString("compatibility").trim().ifBlank { "OpenCode & Pi JS/TS · PRoot Sandbox" },
            permissions = listOf("PROOT_SANDBOX_EXEC", "AGENT_TOOL_HOOK"),
            expectedSha256 = manifestSha,
            endpointUrl = entry,
            skillMarkdown = code,
            sourceUri = obj.optString("source").trim().ifBlank { sourceLabel },
        )
    }

    private fun parseRawJsTsPlugin(
        code: String,
        sourceLabel: String,
        defaultEntrypoint: String,
    ): ExtensionManifest {
        val headerMeta = linkedMapOf<String, String>()
        code.lines().take(30).forEach { line ->
            val match = Regex("^//\\s*@([a-zA-Z0-9_-]+)\\s+(.+)$").find(line.trim())
            if (match != null) {
                headerMeta[match.groupValues[1].lowercase()] = match.groupValues[2].trim()
            }
        }
        val cleanEntry = headerMeta["entrypoint"]?.takeIf { it.isNotBlank() } ?: defaultEntrypoint
        val rawName = headerMeta["name"]?.takeIf { it.isNotBlank() }
            ?: sourceLabel.substringAfterLast('/').substringBeforeLast('.').takeIf {
                it.isNotBlank() && !sourceLabel.startsWith("manual://")
            }
            ?: "Custom Sandboxed Plugin"
        val rawId = headerMeta["id"]?.takeIf { it.isNotBlank() } ?: slugifyId("plugin.$rawName")
        val cleanId = validateExtensionId(rawId).getOrThrow()

        return ExtensionManifest(
            id = cleanId,
            name = rawName,
            description = headerMeta["description"]?.takeIf { it.isNotBlank() }
                ?: "Interpreted JS/TS plugin executed strictly inside PRoot Linux sandbox.",
            category = ExtensionCategory.PLUGINS,
            publisher = headerMeta["publisher"]?.takeIf { it.isNotBlank() } ?: "Imported JS/TS Plugin",
            version = headerMeta["version"]?.takeIf { it.isNotBlank() } ?: "1.0.0",
            license = headerMeta["license"]?.takeIf { it.isNotBlank() } ?: "MIT",
            compatibility = headerMeta["compatibility"]?.takeIf { it.isNotBlank() }
                ?: "OpenCode & Pi JS/TS · PRoot Sandbox",
            permissions = listOf("PROOT_SANDBOX_EXEC", "AGENT_TOOL_HOOK"),
            expectedSha256 = headerMeta["sha256"]?.takeIf { it.isNotBlank() },
            endpointUrl = cleanEntry,
            skillMarkdown = code,
            sourceUri = sourceLabel,
        )
    }

    fun parseSkillInput(
        rawInput: String,
        expectedSha256: String? = null,
        sourceLabel: String = "manual://import",
    ): Result<ExtensionManifest> = runCatching {
        val trimmed = rawInput.trim()
        require(trimmed.isNotEmpty()) { "Paste a SKILL.md document or Skill JSON manifest." }

        val manifest = if (trimmed.startsWith("{")) {
            parseJsonSkillManifest(trimmed, sourceLabel)
        } else {
            parseRawSkillMarkdown(trimmed, sourceLabel)
        }

        if (manifest.category == ExtensionCategory.PLUGINS) {
            throw IllegalStateException(
                "Third-party executable plugins cannot be imported as Skills. Use the Plugins tab importer instead."
            )
        }

        val skillBody = manifest.skillMarkdown?.trim().orEmpty()
        require(skillBody.isNotEmpty()) { "SKILL.md content cannot be empty." }
        validateNonExecutableSkillContent(skillBody)

        val effectiveExpectedSha = expectedSha256?.trim()?.takeIf { it.isNotEmpty() }
            ?: manifest.expectedSha256?.trim()?.takeIf { it.isNotEmpty() }
        val verifiedSha = verifySha256(
            bytes = skillBody.toByteArray(StandardCharsets.UTF_8),
            expectedDigest = effectiveExpectedSha,
        ).getOrThrow()

        manifest.copy(
            expectedSha256 = verifiedSha,
            skillMarkdown = skillBody,
        )
    }

    private fun parseJsonSkillManifest(rawJson: String, sourceLabel: String): ExtensionManifest {
        val obj = JSONObject(rawJson)
        val rawType = obj.optString("type").ifBlank { obj.optString("category") }.uppercase()
        if (rawType.contains("PLUGIN")) {
            throw IllegalStateException(
                "Third-party executable plugins are blocked from Skills import."
            )
        }
        if (obj.has("entrypoint") || obj.has("nativeBinary") || obj.has("wasmModule")) {
            throw IllegalArgumentException(
                "Executable entrypoints are prohibited inside Skills. Skills must contain non-executable SKILL.md instructions only."
            )
        }

        val rawName = obj.optString("name").trim()
        require(rawName.isNotEmpty()) { "Manifest is missing 'name'." }
        val rawId = obj.optString("id").trim().ifBlank { slugifyId("skill.$rawName") }
        val cleanId = validateExtensionId(rawId).getOrThrow()

        val description = obj.optString("description").trim()
            .ifBlank { "Custom SKILL.md guidance for $rawName." }
        val publisher = obj.optString("publisher").trim().ifBlank { "Community" }
        val version = obj.optString("version").trim().ifBlank { "1.0.0" }
        val license = obj.optString("license").trim().ifBlank { "MIT" }
        val compatibility = obj.optString("compatibility").trim().ifBlank { "AGT API >= 1.0" }

        val permissions = mutableListOf<String>()
        val permsJson = obj.optJSONArray("permissions") ?: JSONArray()
        for (i in 0 until permsJson.length()) {
            val p = permsJson.optString(i).trim()
            if (p.isNotEmpty()) permissions += p
        }
        if (permissions.isEmpty()) {
            permissions += "PROJECT_READ"
        }

        val integrityObj = obj.optJSONObject("integrity")
        val manifestSha = integrityObj?.optString("sha256")?.trim()
            ?.takeIf { it.isNotEmpty() }
            ?: obj.optString("sha256").trim().takeIf { it.isNotEmpty() }

        val skillMarkdown = obj.optString("skillMarkdown")
            .ifBlank { obj.optString("skill_markdown") }
            .ifBlank { obj.optString("instructions") }

        return ExtensionManifest(
            id = cleanId,
            name = rawName,
            description = description,
            category = ExtensionCategory.SKILLS,
            publisher = publisher,
            version = version,
            license = license,
            compatibility = compatibility,
            permissions = permissions.distinct(),
            expectedSha256 = manifestSha,
            skillMarkdown = skillMarkdown,
            sourceUri = obj.optString("source").trim().ifBlank { sourceLabel },
        )
    }

    private fun parseRawSkillMarkdown(markdown: String, sourceLabel: String): ExtensionManifest {
        val frontmatter = linkedMapOf<String, String>()
        var body = markdown
        if (markdown.startsWith("---\n") || markdown.startsWith("---\r\n")) {
            val lines = markdown.lines()
            val endIdx = lines.drop(1).indexOfFirst { it.trim() == "---" }
            if (endIdx >= 0) {
                val fmLines = lines.subList(1, endIdx + 1)
                fmLines.forEach { line ->
                    val colon = line.indexOf(':')
                    if (colon > 0) {
                        val k = line.substring(0, colon).trim().lowercase()
                        val v = line.substring(colon + 1).trim().trim('"', '\'')
                        frontmatter[k] = v
                    }
                }
                body = lines.drop(endIdx + 2).joinToString("\n").trim()
            }
        }

        val headingTitle = body.lines()
            .firstOrNull { it.trim().startsWith("#") }
            ?.trim()
            ?.trimStart('#')
            ?.trim()

        val name = frontmatter["name"]?.takeIf { it.isNotBlank() }
            ?: headingTitle?.takeIf { it.isNotBlank() }
            ?: "Custom Skill"
        val description = frontmatter["description"]?.takeIf { it.isNotBlank() }
            ?: body.lines().firstOrNull { it.isNotBlank() && !it.trim().startsWith("#") }?.take(140)
            ?: "Custom non-executable SKILL.md instructions."
        val id = frontmatter["id"]?.takeIf { it.isNotBlank() }
            ?.let { validateExtensionId(it).getOrThrow() }
            ?: validateExtensionId(slugifyId("skill.$name")).getOrThrow()

        val permissions = frontmatter["permissions"]
            ?.split(',')
            ?.map { it.trim().trim('[', ']', '"', '\'') }
            ?.filter { it.isNotEmpty() }
            ?.ifEmpty { listOf("PROJECT_READ") }
            ?: listOf("PROJECT_READ")

        return ExtensionManifest(
            id = id,
            name = name,
            description = description,
            category = ExtensionCategory.SKILLS,
            publisher = frontmatter["publisher"]?.ifBlank { null } ?: "Imported SKILL.md",
            version = frontmatter["version"]?.ifBlank { null } ?: "1.0.0",
            license = frontmatter["license"]?.ifBlank { null } ?: "Unspecified",
            compatibility = frontmatter["compatibility"]?.ifBlank { null } ?: "AGT API >= 1.0",
            permissions = permissions,
            expectedSha256 = frontmatter["sha256"]?.ifBlank { null },
            skillMarkdown = markdown,
            sourceUri = sourceLabel,
        )
    }

    private fun validateNonExecutableSkillContent(skillBody: String) {
        require(!skillBody.contains('\u0000')) {
            "Skill content contains invalid null bytes."
        }
        val firstLine = skillBody.lines().firstOrNull()?.trim().orEmpty()
        if (firstLine.startsWith("#!/bin/") || firstLine.startsWith("#!/usr/bin/")) {
            throw IllegalArgumentException(
                "Executable shell scripts cannot be imported as Skills. Wrap guidance in SKILL.md markdown instructions."
            )
        }
    }

    fun slugifyId(raw: String): String {
        val cleaned = raw.lowercase()
            .replace(Regex("[^a-z0-9._-]+"), "-")
            .trim('-', '.')
        return cleaned.ifBlank { "custom-ext" }.take(64)
    }
}

