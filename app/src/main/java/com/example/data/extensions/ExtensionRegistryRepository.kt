package com.example.data.extensions

import android.content.Context
import com.example.core.common.ProjectPathValidator
import com.example.core.security.ExtensionCredentialVault
import com.example.core.security.GatewayUrlValidation
import com.example.data.database.ExtensionDao
import com.example.data.database.ExtensionEntity
import com.example.data.gateway.JhGatewayHttpClient
import com.example.data.preferences.AppPreferencesRepository
import com.example.data.runtime.LocalRuntimeExecutor
import com.example.data.runtime.RuntimeArchiveExtractor
import com.example.domain.model.ActiveExtensionPolicy
import com.example.domain.model.ExtensionAgentTarget
import com.example.domain.model.ExtensionCatalogEntry
import com.example.domain.model.ExtensionCategory
import com.example.domain.model.ExtensionInstallProgress
import com.example.domain.model.ExtensionInstallStage
import com.example.domain.model.ExtensionInstallState
import com.example.domain.model.ExtensionPackageFormat
import com.example.domain.model.ExtensionRecord
import com.example.domain.model.McpServerRuntimeConfig
import com.example.domain.model.McpToolDescriptor
import com.example.domain.model.McpTransportType
import com.example.domain.model.PluginRuntimeConfig
import com.example.domain.model.RuntimeArchiveFormat
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.FileOutputStream
import java.nio.charset.StandardCharsets
import java.util.UUID

/**
 * Coordinates the Universal Extension Hub (Built-in, OpenCode, Pi Agent Skills,
 * remote HTTP/SSE & PRoot local stdio MCP Servers, opt-in sandboxed JS/TS Plugins,
 * archive/GitHub downloads, SHA-256 verification, native dual-agent syncing, and
 * Android Keystore credential isolation).
 */
class ExtensionRegistryRepository(
    context: Context,
    private val extensionDao: ExtensionDao,
    private val credentialVault: ExtensionCredentialVault,
    private val mcpHttpClient: McpHttpClient = McpHttpClient(),
    private val okHttpClient: OkHttpClient = JhGatewayHttpClient.defaultOkHttpClient(),
    private val runtimeExecutor: LocalRuntimeExecutor? = null,
    private val preferencesRepository: AppPreferencesRepository? = null,
) {
    private val appContext = context.applicationContext
    private val extensionsRoot = File(appContext.filesDir, "extensions").apply { mkdirs() }
    private val catalogCacheFile = File(extensionsRoot, ExtensionCatalog.OFFICIAL_CATALOG_CACHE_FILE)
    private val mcpStdioClient: McpStdioClient? by lazy { runtimeExecutor?.let(::McpStdioClient) }

    private val _installProgress = MutableStateFlow<ExtensionInstallProgress?>(null)
    val installProgress: StateFlow<ExtensionInstallProgress?> = _installProgress.asStateFlow()

    private val _remoteCatalogEntries = MutableStateFlow(loadCachedCatalogEntries())

    suspend fun isSandboxExtensionsAllowed(): Boolean =
        preferencesRepository?.preferences?.first()?.allowProotSandboxExtensions ?: false

    fun observeExtensions(): Flow<List<ExtensionRecord>> =
        combine(
            extensionDao.observeAll(),
            _remoteCatalogEntries,
        ) { entities, remoteEntries ->
            mergeCatalogWithInstalled(entities, remoteEntries)
        }

    suspend fun getAllExtensions(): List<ExtensionRecord> =
        mergeCatalogWithInstalled(extensionDao.getAll(), _remoteCatalogEntries.value)

    suspend fun getEnabledSkills(): List<ExtensionRecord> =
        getAllExtensions().filter {
            it.category == ExtensionCategory.SKILLS && it.installState == ExtensionInstallState.INSTALLED_ENABLED
        }

    suspend fun getEnabledMcpServers(): List<ExtensionRecord> {
        val sandboxAllowed = isSandboxExtensionsAllowed()
        return getAllExtensions().filter {
            it.category == ExtensionCategory.MCP_SERVERS &&
                it.installState == ExtensionInstallState.INSTALLED_ENABLED &&
                (it.mcpTransportType != McpTransportType.LOCAL_STDIO || sandboxAllowed)
        }
    }

    suspend fun getEnabledPlugins(): List<ExtensionRecord> {
        if (!isSandboxExtensionsAllowed()) return emptyList()
        return getAllExtensions().filter {
            it.category == ExtensionCategory.PLUGINS && it.installState == ExtensionInstallState.INSTALLED_ENABLED
        }
    }

    fun getCatalogEntry(id: String): ExtensionCatalogEntry? =
        mergedCatalogEntries(_remoteCatalogEntries.value).firstOrNull { it.record.id == id }

    /**
     * Fetches a remote `extensions-catalog.json` index, validates entries, and caches it locally.
     */
    suspend fun syncRemoteCatalog(catalogUrl: String): Result<Int> = withContext(Dispatchers.IO) {
        runCatching {
            val validUrl = when (val v = ExtensionPackageValidator.validateResourceUrl(catalogUrl.trim())) {
                is GatewayUrlValidation.Valid -> v.normalizedBaseUrl
                is GatewayUrlValidation.Invalid -> throw IllegalArgumentException(v.reason)
            }
            val payload = fetchUrlText(validUrl, maxChars = MAX_CATALOG_CHARS)
            val parsed = ExtensionCatalog.parseRemoteCatalogJson(payload).getOrThrow()
            catalogCacheFile.writeText(
                ExtensionCatalog.serializeCatalogEntries(parsed),
                StandardCharsets.UTF_8,
            )
            _remoteCatalogEntries.value = parsed
            parsed.size
        }
    }

    /**
     * Installs or updates a curated built-in non-executable SKILL.md extension.
     */
    suspend fun installBuiltInSkill(id: String): Result<ExtensionRecord> = withContext(Dispatchers.IO) {
        runCatching {
            val builtIn = ExtensionCatalog.BUILT_IN_SKILLS.firstOrNull { it.record.id == id }
                ?: throw IllegalArgumentException("Unknown built-in Skill: $id")
            val skillMarkdown = builtIn.inlineSkillMarkdown
                ?: throw IllegalStateException("Built-in Skill '${builtIn.record.id}' is missing inline instructions.")

            val skillFile = ExtensionPackageValidator.resolveConfinedSkillFile(extensionsRoot, builtIn.record.id)
                .getOrThrow()
            skillFile.parentFile?.mkdirs()
            val skillBytes = skillMarkdown.toByteArray(StandardCharsets.UTF_8)
            val digest = ExtensionPackageValidator.sha256Hex(skillBytes)
            skillFile.writeText(skillMarkdown, StandardCharsets.UTF_8)
            writeSidecarMeta(
                extensionId = builtIn.record.id,
                agentTarget = builtIn.record.agentTarget,
                tags = builtIn.record.tags,
                docsUrl = builtIn.record.docsUrl,
                packageFormat = ExtensionPackageFormat.INLINE_SKILL,
            )

            val now = System.currentTimeMillis()
            val entity = ExtensionEntity(
                id = builtIn.record.id,
                name = builtIn.record.name,
                description = builtIn.record.description,
                category = ExtensionCategory.SKILLS.name,
                publisher = builtIn.record.publisher,
                version = builtIn.record.version,
                availableVersion = builtIn.record.version,
                license = builtIn.record.license,
                compatibility = builtIn.record.compatibility,
                sourceUri = builtIn.record.sourceUri,
                sha256 = digest,
                permissionsCsv = builtIn.record.permissions.joinToString(","),
                installState = ExtensionInstallState.INSTALLED_ENABLED.name,
                isBuiltIn = true,
                endpointUrl = null,
                skillContentPath = "${builtIn.record.id}/SKILL.md",
                discoveredToolsJson = null,
                healthMessage = "Non-executable SKILL.md verified ($digest)",
                installedAtEpochMs = now,
                updatedAtEpochMs = now,
            )
            extensionDao.upsert(entity)
            toRecord(entity, availableVersion = builtIn.record.version, catalogEntry = builtIn)
        }
    }

    /**
     * Installs a catalog extension by running the full Download -> Verify SHA-256 -> Extract -> Install
     * pipeline with live progress reporting.
     */
    suspend fun installCatalogExtension(id: String): Result<ExtensionRecord> = withContext(Dispatchers.IO) {
        val entry = getCatalogEntry(id)
            ?: return@withContext Result.failure(IllegalArgumentException("Unknown catalog extension: $id"))

        if (entry.record.category == ExtensionCategory.PLUGINS) {
            if (!isSandboxExtensionsAllowed()) {
                return@withContext Result.failure(
                    IllegalStateException(SANDBOX_GATE_ERROR)
                )
            }
            if (!entry.inlineSkillMarkdown.isNullOrBlank() && entry.downloadUrl.isNullOrBlank()) {
                return@withContext installInlinePlugin(entry)
            }
            val pluginUrl = entry.downloadUrl?.takeIf { it.isNotBlank() }
                ?: return@withContext Result.failure(
                    IllegalArgumentException("Catalog plugin '${entry.record.name}' has no download URL.")
                )
            return@withContext runCatching {
                try {
                    downloadAndInstallPluginPackage(
                        candidateUrls = listOf(pluginUrl),
                        archiveSubpath = entry.archiveSubpath,
                        expectedSha256 = entry.expectedSha256,
                        preferredEntrypoint = entry.pluginEntrypoint,
                        progressId = entry.record.id,
                        progressName = entry.record.name,
                        overrideId = entry.record.id,
                        overrideName = entry.record.name,
                        overrideDescription = entry.record.description,
                        overridePublisher = entry.record.publisher,
                        overrideVersion = entry.record.version,
                        overrideLicense = entry.record.license,
                        overrideCompatibility = entry.record.compatibility,
                        overridePermissions = entry.record.permissions,
                        agentTarget = entry.record.agentTarget,
                        tags = entry.record.tags,
                        docsUrl = entry.record.docsUrl ?: entry.record.sourceUri,
                        catalogEntry = entry,
                    )
                } finally {
                    _installProgress.value = null
                }
            }
        }
        if (entry.format == ExtensionPackageFormat.INLINE_SKILL || entry.record.isBuiltIn) {
            return@withContext installBuiltInSkill(entry.record.id)
        }
        if (entry.format == ExtensionPackageFormat.REMOTE_MCP_TEMPLATE) {
            return@withContext Result.failure(
                IllegalStateException("Remote MCP presets must be connected through the MCP endpoint confirmation dialog.")
            )
        }
        if (entry.format == ExtensionPackageFormat.LOCAL_STDIO_MCP_TEMPLATE) {
            return@withContext Result.failure(
                IllegalStateException("Local PRoot MCP presets must be connected through the MCP command confirmation dialog.")
            )
        }

        val url = entry.downloadUrl?.takeIf { it.isNotBlank() }
            ?: return@withContext Result.failure(
                IllegalArgumentException("Catalog item '${entry.record.name}' has no download URL.")
            )

        runCatching {
            try {
                downloadAndInstallSkillArchive(
                    candidateUrls = listOf(url),
                    archiveSubpath = entry.archiveSubpath,
                    expectedSha256 = entry.expectedSha256,
                    formatHint = entry.format,
                    progressId = entry.record.id,
                    progressName = entry.record.name,
                    overrideId = entry.record.id,
                    overrideName = entry.record.name,
                    overrideDescription = entry.record.description,
                    overridePublisher = entry.record.publisher,
                    overrideVersion = entry.record.version,
                    overrideLicense = entry.record.license,
                    overrideCompatibility = entry.record.compatibility,
                    overridePermissions = entry.record.permissions,
                    agentTarget = entry.record.agentTarget,
                    tags = entry.record.tags,
                    docsUrl = entry.record.docsUrl ?: entry.record.sourceUri,
                    catalogEntry = entry,
                )
            } finally {
                _installProgress.value = null
            }
        }
    }

    private suspend fun installInlinePlugin(entry: ExtensionCatalogEntry): Result<ExtensionRecord> = runCatching {
        if (!isSandboxExtensionsAllowed()) {
            throw IllegalStateException(SANDBOX_GATE_ERROR)
        }
        val code = entry.inlineSkillMarkdown?.takeIf { it.isNotBlank() }
            ?: throw IllegalStateException("Plugin '${entry.record.name}' is missing source code.")
        val entrypoint = entry.pluginEntrypoint ?: "index.ts"
        val destFile = ExtensionPackageValidator.resolveConfinedPluginEntrypoint(
            extensionsRoot = extensionsRoot,
            extensionId = entry.record.id,
            entrypointRelPath = entrypoint,
        ).getOrThrow()
        destFile.parentFile?.mkdirs()
        destFile.writeText(code, StandardCharsets.UTF_8)
        val digest = ExtensionPackageValidator.sha256HexFile(destFile)
        writeSidecarMeta(
            extensionId = entry.record.id,
            agentTarget = entry.record.agentTarget,
            tags = entry.record.tags,
            docsUrl = entry.record.docsUrl,
            packageFormat = ExtensionPackageFormat.JS_TS_PLUGIN,
            pluginEntrypoint = entrypoint,
        )
        val now = System.currentTimeMillis()
        val entity = ExtensionEntity(
            id = entry.record.id,
            name = entry.record.name,
            description = entry.record.description,
            category = ExtensionCategory.PLUGINS.name,
            publisher = entry.record.publisher,
            version = entry.record.version,
            availableVersion = entry.record.version,
            license = entry.record.license,
            compatibility = entry.record.compatibility,
            sourceUri = entry.record.sourceUri,
            sha256 = digest,
            permissionsCsv = entry.record.permissions.joinToString(","),
            installState = ExtensionInstallState.INSTALLED_ENABLED.name,
            isBuiltIn = false,
            endpointUrl = null,
            skillContentPath = "${entry.record.id}/$entrypoint",
            discoveredToolsJson = null,
            healthMessage = "Sandboxed JS/TS plugin ready ($entrypoint · SHA-256 ${digest.take(12)}…)",
            installedAtEpochMs = now,
            updatedAtEpochMs = now,
        )
        extensionDao.upsert(entity)
        toRecord(entity, availableVersion = entry.record.version, catalogEntry = entry)
    }

    /**
     * Imports a sandboxed JS/TS OpenCode or Pi plugin from raw JS/TS code, Plugin JSON manifest,
     * GitHub repo/tree/blob URL, `owner/repo(/subpath)`, or direct HTTPS archive / `.ts`/`.js` URL.
     * Requires explicit user opt-in ([isSandboxExtensionsAllowed]) and rejects native binaries.
     */
    suspend fun importPluginPackage(
        rawSourceOrContent: String,
        expectedSha256: String? = null,
    ): Result<ExtensionRecord> = withContext(Dispatchers.IO) {
        runCatching {
            if (!isSandboxExtensionsAllowed()) {
                throw IllegalStateException(SANDBOX_GATE_ERROR)
            }
            val trimmed = rawSourceOrContent.trim()
            require(trimmed.isNotEmpty()) {
                "Enter JS/TS plugin code, Plugin JSON, GitHub repo/URL, or .ts/.js/.zip/.tar.gz URL."
            }

            val resolvedSource = ExtensionPackageValidator.resolveRemoteSource(trimmed)
            if (resolvedSource != null) {
                try {
                    return@runCatching downloadAndInstallPluginPackage(
                        candidateUrls = resolvedSource.candidateUrls,
                        archiveSubpath = resolvedSource.archiveSubpath,
                        expectedSha256 = expectedSha256,
                        preferredEntrypoint = null,
                        progressId = resolvedSource.suggestedId ?: "plugin.custom",
                        progressName = resolvedSource.suggestedId ?: "Sandboxed Plugin",
                        overrideId = resolvedSource.suggestedId?.replaceFirst("skill.", "plugin."),
                        agentTarget = resolvedSource.agentTargetHint ?: ExtensionAgentTarget.UNIVERSAL,
                        tags = listOf("plugin", "imported"),
                        docsUrl = resolvedSource.docsUrl,
                    )
                } finally {
                    _installProgress.value = null
                }
            }

            val manifest = ExtensionPackageValidator.parsePluginInput(
                rawInput = trimmed,
                expectedSha256 = expectedSha256,
                sourceLabel = "manual://plugin-import",
            ).getOrThrow()

            val entrypoint = manifest.endpointUrl ?: "index.ts"
            val entryFile = ExtensionPackageValidator.resolveConfinedPluginEntrypoint(
                extensionsRoot = extensionsRoot,
                extensionId = manifest.id,
                entrypointRelPath = entrypoint,
            ).getOrThrow()
            entryFile.parentFile?.mkdirs()
            val codeBody = manifest.skillMarkdown.orEmpty()
            entryFile.writeText(codeBody, StandardCharsets.UTF_8)

            val inferredTarget = ExtensionPackageValidator.inferAgentTarget(
                manifest.name,
                manifest.description,
                manifest.compatibility,
                codeBody.take(600),
            )
            writeSidecarMeta(
                extensionId = manifest.id,
                agentTarget = inferredTarget,
                tags = listOf("plugin", "manual"),
                docsUrl = null,
                packageFormat = ExtensionPackageFormat.JS_TS_PLUGIN,
                pluginEntrypoint = entrypoint,
            )

            val now = System.currentTimeMillis()
            val entity = ExtensionEntity(
                id = manifest.id,
                name = manifest.name,
                description = manifest.description,
                category = ExtensionCategory.PLUGINS.name,
                publisher = manifest.publisher,
                version = manifest.version,
                availableVersion = manifest.version,
                license = manifest.license,
                compatibility = manifest.compatibility,
                sourceUri = manifest.sourceUri,
                sha256 = manifest.expectedSha256.orEmpty(),
                permissionsCsv = manifest.permissions.joinToString(","),
                installState = ExtensionInstallState.INSTALLED_ENABLED.name,
                isBuiltIn = false,
                endpointUrl = null,
                skillContentPath = "${manifest.id}/$entrypoint",
                discoveredToolsJson = null,
                healthMessage = "Sandboxed JS/TS plugin verified ($entrypoint · ${manifest.expectedSha256.orEmpty().take(12)}…)",
                installedAtEpochMs = now,
                updatedAtEpochMs = now,
            )
            extensionDao.upsert(entity)
            toRecord(entity)
        }
    }

    /**
     * Universal Skill Importer: accepts raw SKILL.md, Skill JSON manifest, `owner/repo(/subpath)`,
     * GitHub repo/tree/blob URLs, `.zip` / `.tar.gz` URLs, or direct raw SKILL.md HTTPS URLs.
     * Enforces SHA-256 verification, archive confinement, and non-executable Skill policies.
     */
    suspend fun importSkillPackage(
        rawSourceOrContent: String,
        expectedSha256: String? = null,
    ): Result<ExtensionRecord> = withContext(Dispatchers.IO) {
        runCatching {
            val trimmed = rawSourceOrContent.trim()
            require(trimmed.isNotEmpty()) {
                "Enter SKILL.md text, Skill JSON, GitHub repo/URL, or .zip/.tar.gz URL."
            }

            val resolvedSource = ExtensionPackageValidator.resolveRemoteSource(trimmed)
            if (resolvedSource != null) {
                try {
                    return@runCatching downloadAndInstallSkillArchive(
                        candidateUrls = resolvedSource.candidateUrls,
                        archiveSubpath = resolvedSource.archiveSubpath,
                        expectedSha256 = expectedSha256,
                        formatHint = resolvedSource.formatHint,
                        progressId = resolvedSource.suggestedId ?: "import.custom",
                        progressName = resolvedSource.suggestedId ?: "Custom Skill Package",
                        overrideId = resolvedSource.suggestedId,
                        agentTarget = resolvedSource.agentTargetHint ?: ExtensionAgentTarget.UNIVERSAL,
                        tags = listOf("imported"),
                        docsUrl = resolvedSource.docsUrl,
                    )
                } finally {
                    _installProgress.value = null
                }
            }

            // Local raw SKILL.md or JSON manifest text
            val manifest = ExtensionPackageValidator.parseSkillInput(
                rawInput = trimmed,
                expectedSha256 = expectedSha256,
                sourceLabel = "manual://skill-import",
            ).getOrThrow()

            val skillFile = ExtensionPackageValidator.resolveConfinedSkillFile(extensionsRoot, manifest.id)
                .getOrThrow()
            skillFile.parentFile?.mkdirs()
            val skillBody = manifest.skillMarkdown.orEmpty()
            skillFile.writeText(skillBody, StandardCharsets.UTF_8)
            val inferredTarget = ExtensionPackageValidator.inferAgentTarget(
                manifest.name,
                manifest.description,
                manifest.compatibility,
            )
            writeSidecarMeta(
                extensionId = manifest.id,
                agentTarget = inferredTarget,
                tags = listOf("manual"),
                docsUrl = null,
                packageFormat = ExtensionPackageFormat.RAW_SKILL_MD,
            )

            val now = System.currentTimeMillis()
            val entity = ExtensionEntity(
                id = manifest.id,
                name = manifest.name,
                description = manifest.description,
                category = ExtensionCategory.SKILLS.name,
                publisher = manifest.publisher,
                version = manifest.version,
                availableVersion = manifest.version,
                license = manifest.license,
                compatibility = manifest.compatibility,
                sourceUri = manifest.sourceUri,
                sha256 = manifest.expectedSha256.orEmpty(),
                permissionsCsv = manifest.permissions.joinToString(","),
                installState = ExtensionInstallState.INSTALLED_ENABLED.name,
                isBuiltIn = false,
                endpointUrl = null,
                skillContentPath = "${manifest.id}/SKILL.md",
                discoveredToolsJson = null,
                healthMessage = "Verified SHA-256 ${manifest.expectedSha256.orEmpty().take(12)}…",
                installedAtEpochMs = now,
                updatedAtEpochMs = now,
            )
            extensionDao.upsert(entity)
            toRecord(entity)
        }
    }

    private suspend fun downloadAndInstallSkillArchive(
        candidateUrls: List<String>,
        archiveSubpath: String?,
        expectedSha256: String?,
        formatHint: ExtensionPackageFormat?,
        progressId: String,
        progressName: String,
        overrideId: String? = null,
        overrideName: String? = null,
        overrideDescription: String? = null,
        overridePublisher: String? = null,
        overrideVersion: String? = null,
        overrideLicense: String? = null,
        overrideCompatibility: String? = null,
        overridePermissions: List<String>? = null,
        agentTarget: ExtensionAgentTarget = ExtensionAgentTarget.UNIVERSAL,
        tags: List<String> = emptyList(),
        docsUrl: String? = null,
        catalogEntry: ExtensionCatalogEntry? = null,
    ): ExtensionRecord {
        val normalizedUrls = candidateUrls.map { raw ->
            when (val valid = ExtensionPackageValidator.validateResourceUrl(raw)) {
                is GatewayUrlValidation.Valid -> valid.normalizedBaseUrl
                is GatewayUrlValidation.Invalid -> throw IllegalArgumentException(valid.reason)
            }
        }

        val tmpRoot = File(extensionsRoot, ".tmp-${UUID.randomUUID().toString().take(8)}")
        tmpRoot.mkdirs()
        try {
            val downloadedFile = File(tmpRoot, "download.pkg")
            var winningUrl: String? = null
            var lastError: Throwable? = null

            for (url in normalizedUrls) {
                _installProgress.value = ExtensionInstallProgress(
                    extensionId = progressId,
                    extensionName = progressName,
                    stage = ExtensionInstallStage.DOWNLOADING,
                    progressPercent = 0,
                    detail = "Downloading package…",
                )
                val attempt = runCatching {
                    downloadToFileWithProgress(url, downloadedFile, progressId, progressName)
                }
                if (attempt.isSuccess) {
                    winningUrl = url
                    break
                } else {
                    lastError = attempt.exceptionOrNull()
                }
            }

            val finalUrl = winningUrl ?: throw (lastError ?: IllegalStateException("Download failed."))

            // 1. Inspect magic bytes and format
            val header = ByteArray(16)
            val headerLen = downloadedFile.inputStream().use { it.read(header) }.coerceAtLeast(0)
            val detectedFormat = ExtensionPackageValidator.detectFormatFromHeaderOrHint(
                firstBytes = header.copyOf(headerLen),
                hint = formatHint,
                sourceUrl = finalUrl,
            )

            // 2. Verify SHA-256 on downloaded archive or content
            _installProgress.value = ExtensionInstallProgress(
                extensionId = progressId,
                extensionName = progressName,
                stage = ExtensionInstallStage.VERIFYING,
                progressPercent = 100,
                detail = "Verifying SHA-256 integrity…",
            )
            val fileDigest = ExtensionPackageValidator.verifyFileSha256(downloadedFile, expectedSha256).getOrThrow()

            if (detectedFormat == ExtensionPackageFormat.RAW_SKILL_MD) {
                require(downloadedFile.length() <= MAX_MANIFEST_CHARS) {
                    "Extension resource exceeds maximum allowed size."
                }
                val text = downloadedFile.readText(StandardCharsets.UTF_8)
                val manifest = ExtensionPackageValidator.parseSkillInput(
                    rawInput = text,
                    expectedSha256 = expectedSha256,
                    sourceLabel = finalUrl,
                ).getOrThrow()

                val effectiveId = overrideId?.let { ExtensionPackageValidator.validateExtensionId(it).getOrThrow() }
                    ?: manifest.id

                _installProgress.value = ExtensionInstallProgress(
                    extensionId = effectiveId,
                    extensionName = overrideName ?: manifest.name,
                    stage = ExtensionInstallStage.INSTALLING,
                    progressPercent = 100,
                    detail = "Installing SKILL.md…",
                )

                val skillFile = ExtensionPackageValidator.resolveConfinedSkillFile(extensionsRoot, effectiveId)
                    .getOrThrow()
                skillFile.parentFile?.mkdirs()
                skillFile.writeText(manifest.skillMarkdown.orEmpty(), StandardCharsets.UTF_8)

                val effectiveAgentTarget = if (agentTarget != ExtensionAgentTarget.UNIVERSAL) {
                    agentTarget
                } else {
                    ExtensionPackageValidator.inferAgentTarget(finalUrl, manifest.name, manifest.description)
                }
                writeSidecarMeta(
                    extensionId = effectiveId,
                    agentTarget = effectiveAgentTarget,
                    tags = tags,
                    docsUrl = docsUrl ?: finalUrl,
                    packageFormat = ExtensionPackageFormat.RAW_SKILL_MD,
                )

                val now = System.currentTimeMillis()
                val entity = ExtensionEntity(
                    id = effectiveId,
                    name = overrideName ?: manifest.name,
                    description = overrideDescription ?: manifest.description,
                    category = ExtensionCategory.SKILLS.name,
                    publisher = overridePublisher ?: manifest.publisher,
                    version = overrideVersion ?: manifest.version,
                    availableVersion = overrideVersion ?: manifest.version,
                    license = overrideLicense ?: manifest.license,
                    compatibility = overrideCompatibility ?: manifest.compatibility,
                    sourceUri = finalUrl,
                    sha256 = manifest.expectedSha256 ?: fileDigest,
                    permissionsCsv = (overridePermissions ?: manifest.permissions).joinToString(","),
                    installState = ExtensionInstallState.INSTALLED_ENABLED.name,
                    isBuiltIn = false,
                    endpointUrl = null,
                    skillContentPath = "$effectiveId/SKILL.md",
                    discoveredToolsJson = null,
                    healthMessage = "Verified SHA-256 ${(manifest.expectedSha256 ?: fileDigest).take(12)}…",
                    installedAtEpochMs = now,
                    updatedAtEpochMs = now,
                )
                extensionDao.upsert(entity)
                return toRecord(entity, availableVersion = catalogEntry?.record?.version, catalogEntry = catalogEntry)
            }

            // 3. Extract .zip / .tar.gz via confined RuntimeArchiveExtractor
            _installProgress.value = ExtensionInstallProgress(
                extensionId = progressId,
                extensionName = progressName,
                stage = ExtensionInstallStage.EXTRACTING,
                progressPercent = null,
                detail = "Extracting archive safely…",
            )
            val stagingDir = File(tmpRoot, "staged")
            val archiveFormat = when (detectedFormat) {
                ExtensionPackageFormat.TAR_GZ -> RuntimeArchiveFormat.TAR_GZ
                else -> RuntimeArchiveFormat.ZIP
            }
            RuntimeArchiveExtractor.extractToStaging(
                archiveFile = downloadedFile,
                format = archiveFormat,
                stagingRoot = stagingDir,
                maxExtractedBytes = MAX_EXTRACTED_EXTENSION_BYTES,
                maxEntries = MAX_EXTRACTED_EXTENSION_ENTRIES,
            ).getOrThrow()

            // 4. Locate SKILL.md directory + validate non-executable docs
            val skillSourceDir = ExtensionPackageValidator.findSkillRootInExtractedDir(stagingDir, archiveSubpath)
                .getOrThrow()

            _installProgress.value = ExtensionInstallProgress(
                extensionId = progressId,
                extensionName = progressName,
                stage = ExtensionInstallStage.INSTALLING,
                progressPercent = 100,
                detail = "Installing Skill files…",
            )

            val stagedProbe = File(tmpRoot, "validated-pkg")
            val copySummary = ExtensionPackageValidator.copyValidatedNonExecutableSkillDir(
                sourceSkillDir = skillSourceDir,
                destinationDir = stagedProbe,
                sourceLabel = finalUrl,
                overrideManifestId = overrideId,
                overrideManifestName = overrideName,
                overrideDescription = overrideDescription,
                overridePublisher = overridePublisher,
                overrideVersion = overrideVersion,
                overrideLicense = overrideLicense,
            ).getOrThrow()

            val manifest = copySummary.manifest
            val destDir = ExtensionPackageValidator.resolveConfinedExtensionDir(extensionsRoot, manifest.id)
                .getOrThrow()
            if (destDir.exists()) {
                destDir.deleteRecursively()
            }
            RuntimeArchiveExtractor.mergeDirectoryInto(stagedProbe, destDir, extensionsRoot)

            val effectiveTarget = if (agentTarget != ExtensionAgentTarget.UNIVERSAL) {
                agentTarget
            } else {
                ExtensionPackageValidator.inferAgentTarget(finalUrl, archiveSubpath, manifest.name, manifest.description)
            }
            writeSidecarMeta(
                extensionId = manifest.id,
                agentTarget = effectiveTarget,
                tags = tags,
                docsUrl = docsUrl ?: finalUrl,
                packageFormat = detectedFormat,
            )

            val skippedNote = if (copySummary.skippedScriptCount > 0) {
                " · ${copySummary.skippedScriptCount} script(s) skipped (Step 2 sandbox required)"
            } else {
                ""
            }
            val now = System.currentTimeMillis()
            val entity = ExtensionEntity(
                id = manifest.id,
                name = manifest.name,
                description = manifest.description,
                category = ExtensionCategory.SKILLS.name,
                publisher = manifest.publisher,
                version = manifest.version,
                availableVersion = catalogEntry?.record?.version ?: manifest.version,
                license = manifest.license,
                compatibility = overrideCompatibility ?: manifest.compatibility,
                sourceUri = finalUrl,
                sha256 = fileDigest,
                permissionsCsv = (overridePermissions ?: manifest.permissions).joinToString(","),
                installState = ExtensionInstallState.INSTALLED_ENABLED.name,
                isBuiltIn = false,
                endpointUrl = null,
                skillContentPath = "${manifest.id}/SKILL.md",
                discoveredToolsJson = null,
                healthMessage = "Extracted ${copySummary.copiedDocFiles.size} file(s) · SHA-256 ${fileDigest.take(12)}…$skippedNote",
                installedAtEpochMs = now,
                updatedAtEpochMs = now,
            )
            extensionDao.upsert(entity)
            return toRecord(entity, availableVersion = catalogEntry?.record?.version, catalogEntry = catalogEntry)
        } finally {
            tmpRoot.deleteRecursively()
        }
    }

    private suspend fun downloadAndInstallPluginPackage(
        candidateUrls: List<String>,
        archiveSubpath: String?,
        expectedSha256: String?,
        preferredEntrypoint: String?,
        progressId: String,
        progressName: String,
        overrideId: String? = null,
        overrideName: String? = null,
        overrideDescription: String? = null,
        overridePublisher: String? = null,
        overrideVersion: String? = null,
        overrideLicense: String? = null,
        overrideCompatibility: String? = null,
        overridePermissions: List<String>? = null,
        agentTarget: ExtensionAgentTarget = ExtensionAgentTarget.UNIVERSAL,
        tags: List<String> = emptyList(),
        docsUrl: String? = null,
        catalogEntry: ExtensionCatalogEntry? = null,
    ): ExtensionRecord {
        val normalizedUrls = candidateUrls.map { raw ->
            when (val valid = ExtensionPackageValidator.validateResourceUrl(raw)) {
                is GatewayUrlValidation.Valid -> valid.normalizedBaseUrl
                is GatewayUrlValidation.Invalid -> throw IllegalArgumentException(valid.reason)
            }
        }

        val tmpRoot = File(extensionsRoot, ".tmp-plugin-${UUID.randomUUID().toString().take(8)}")
        tmpRoot.mkdirs()
        try {
            val downloadedFile = File(tmpRoot, "plugin.pkg")
            var winningUrl: String? = null
            var lastError: Throwable? = null

            for (url in normalizedUrls) {
                _installProgress.value = ExtensionInstallProgress(
                    extensionId = progressId,
                    extensionName = progressName,
                    stage = ExtensionInstallStage.DOWNLOADING,
                    progressPercent = 0,
                    detail = "Downloading plugin…",
                )
                val attempt = runCatching {
                    downloadToFileWithProgress(url, downloadedFile, progressId, progressName)
                }
                if (attempt.isSuccess) {
                    winningUrl = url
                    break
                } else {
                    lastError = attempt.exceptionOrNull()
                }
            }

            val finalUrl = winningUrl ?: throw (lastError ?: IllegalStateException("Download failed."))

            val header = ByteArray(16)
            val headerLen = downloadedFile.inputStream().use { it.read(header) }.coerceAtLeast(0)
            val detectedFormat = ExtensionPackageValidator.detectFormatFromHeaderOrHint(
                firstBytes = header.copyOf(headerLen),
                hint = null,
                sourceUrl = finalUrl,
            )

            _installProgress.value = ExtensionInstallProgress(
                extensionId = progressId,
                extensionName = progressName,
                stage = ExtensionInstallStage.VERIFYING,
                progressPercent = 100,
                detail = "Verifying SHA-256 & Play Integrity policy…",
            )
            val fileDigest = ExtensionPackageValidator.verifyFileSha256(downloadedFile, expectedSha256).getOrThrow()

            val isArchive = detectedFormat == ExtensionPackageFormat.ZIP || detectedFormat == ExtensionPackageFormat.TAR_GZ
            if (!isArchive) {
                require(downloadedFile.length() <= MAX_MANIFEST_CHARS) {
                    "Plugin file exceeds maximum allowed size."
                }
                val rawBytes = downloadedFile.readBytes()
                require(!rawBytes.contains(0.toByte())) {
                    "Plugin download contains binary null bytes and was blocked."
                }
                val codeText = rawBytes.toString(StandardCharsets.UTF_8)
                val urlLeaf = finalUrl.substringBefore('?').substringBefore('#').substringAfterLast('/')
                val defaultEntry = preferredEntrypoint
                    ?: urlLeaf.takeIf { it.endsWith(".ts") || it.endsWith(".js") || it.endsWith(".mjs") || it.endsWith(".mts") }
                    ?: "index.ts"
                val manifest = ExtensionPackageValidator.parsePluginInput(
                    rawInput = codeText,
                    expectedSha256 = expectedSha256,
                    sourceLabel = finalUrl,
                    defaultEntrypoint = defaultEntry,
                ).getOrThrow()

                val effectiveId = overrideId?.let { ExtensionPackageValidator.validateExtensionId(it).getOrThrow() }
                    ?: manifest.id
                val entrypoint = preferredEntrypoint ?: manifest.endpointUrl ?: defaultEntry

                _installProgress.value = ExtensionInstallProgress(
                    extensionId = effectiveId,
                    extensionName = overrideName ?: manifest.name,
                    stage = ExtensionInstallStage.INSTALLING,
                    progressPercent = 100,
                    detail = "Staging sandboxed JS/TS plugin…",
                )

                val destDir = ExtensionPackageValidator.resolveConfinedExtensionDir(extensionsRoot, effectiveId).getOrThrow()
                if (destDir.exists()) destDir.deleteRecursively()
                val destEntry = ExtensionPackageValidator.resolveConfinedPluginEntrypoint(
                    extensionsRoot = extensionsRoot,
                    extensionId = effectiveId,
                    entrypointRelPath = entrypoint,
                ).getOrThrow()
                destEntry.parentFile?.mkdirs()
                destEntry.writeText(manifest.skillMarkdown.orEmpty(), StandardCharsets.UTF_8)

                val effectiveTarget = if (agentTarget != ExtensionAgentTarget.UNIVERSAL) {
                    agentTarget
                } else {
                    ExtensionPackageValidator.inferAgentTarget(finalUrl, manifest.name, manifest.description, codeText.take(600))
                }
                writeSidecarMeta(
                    extensionId = effectiveId,
                    agentTarget = effectiveTarget,
                    tags = tags.ifEmpty { listOf("plugin", "proot") },
                    docsUrl = docsUrl ?: finalUrl,
                    packageFormat = ExtensionPackageFormat.JS_TS_PLUGIN,
                    pluginEntrypoint = entrypoint,
                )

                val now = System.currentTimeMillis()
                val entity = ExtensionEntity(
                    id = effectiveId,
                    name = overrideName ?: manifest.name,
                    description = overrideDescription ?: manifest.description,
                    category = ExtensionCategory.PLUGINS.name,
                    publisher = overridePublisher ?: manifest.publisher,
                    version = overrideVersion ?: manifest.version,
                    availableVersion = overrideVersion ?: manifest.version,
                    license = overrideLicense ?: manifest.license,
                    compatibility = overrideCompatibility ?: manifest.compatibility,
                    sourceUri = finalUrl,
                    sha256 = fileDigest,
                    permissionsCsv = (overridePermissions ?: manifest.permissions).joinToString(","),
                    installState = ExtensionInstallState.INSTALLED_ENABLED.name,
                    isBuiltIn = false,
                    endpointUrl = null,
                    skillContentPath = "$effectiveId/$entrypoint",
                    discoveredToolsJson = null,
                    healthMessage = "Sandboxed JS/TS plugin staged ($entrypoint · SHA-256 ${fileDigest.take(12)}…)",
                    installedAtEpochMs = now,
                    updatedAtEpochMs = now,
                )
                extensionDao.upsert(entity)
                return toRecord(entity, availableVersion = catalogEntry?.record?.version, catalogEntry = catalogEntry)
            }

            // Extract archive safely and validate JS/TS bundle (rejecting native binaries)
            _installProgress.value = ExtensionInstallProgress(
                extensionId = progressId,
                extensionName = progressName,
                stage = ExtensionInstallStage.EXTRACTING,
                progressPercent = null,
                detail = "Extracting plugin archive safely…",
            )
            val stagingDir = File(tmpRoot, "staged-plugin")
            val archiveFormat = if (detectedFormat == ExtensionPackageFormat.TAR_GZ) {
                RuntimeArchiveFormat.TAR_GZ
            } else {
                RuntimeArchiveFormat.ZIP
            }
            RuntimeArchiveExtractor.extractToStaging(
                archiveFile = downloadedFile,
                format = archiveFormat,
                stagingRoot = stagingDir,
                maxExtractedBytes = MAX_EXTRACTED_EXTENSION_BYTES,
                maxEntries = MAX_EXTRACTED_EXTENSION_ENTRIES,
            ).getOrThrow()

            val (pluginSourceDir, discoveredEntrypoint) = ExtensionPackageValidator.findPluginRootAndEntrypoint(
                stagingRoot = stagingDir,
                archiveSubpath = archiveSubpath,
                preferredEntrypoint = preferredEntrypoint,
            ).getOrThrow()

            _installProgress.value = ExtensionInstallProgress(
                extensionId = progressId,
                extensionName = progressName,
                stage = ExtensionInstallStage.INSTALLING,
                progressPercent = 100,
                detail = "Validating & staging JS/TS plugin bundle…",
            )

            val stagedProbe = File(tmpRoot, "validated-plugin")
            val copySummary = ExtensionPackageValidator.copyValidatedJsTsPluginDir(
                sourceDir = pluginSourceDir,
                destinationDir = stagedProbe,
                entrypointRelPath = discoveredEntrypoint,
                sourceLabel = finalUrl,
                overrideManifestId = overrideId,
                overrideManifestName = overrideName,
                overrideDescription = overrideDescription,
                overridePublisher = overridePublisher,
                overrideVersion = overrideVersion,
                overrideLicense = overrideLicense,
            ).getOrThrow()

            val manifest = copySummary.manifest
            val destDir = ExtensionPackageValidator.resolveConfinedExtensionDir(extensionsRoot, manifest.id).getOrThrow()
            if (destDir.exists()) destDir.deleteRecursively()
            RuntimeArchiveExtractor.mergeDirectoryInto(stagedProbe, destDir, extensionsRoot)

            val effectiveTarget = if (agentTarget != ExtensionAgentTarget.UNIVERSAL) {
                agentTarget
            } else {
                ExtensionPackageValidator.inferAgentTarget(finalUrl, archiveSubpath, manifest.name, manifest.description)
            }
            writeSidecarMeta(
                extensionId = manifest.id,
                agentTarget = effectiveTarget,
                tags = tags.ifEmpty { listOf("plugin", "proot") },
                docsUrl = docsUrl ?: finalUrl,
                packageFormat = ExtensionPackageFormat.JS_TS_PLUGIN,
                pluginEntrypoint = copySummary.entrypointRelPath,
            )

            val now = System.currentTimeMillis()
            val entity = ExtensionEntity(
                id = manifest.id,
                name = manifest.name,
                description = manifest.description,
                category = ExtensionCategory.PLUGINS.name,
                publisher = manifest.publisher,
                version = manifest.version,
                availableVersion = catalogEntry?.record?.version ?: manifest.version,
                license = manifest.license,
                compatibility = overrideCompatibility ?: manifest.compatibility,
                sourceUri = finalUrl,
                sha256 = fileDigest,
                permissionsCsv = (overridePermissions ?: manifest.permissions).joinToString(","),
                installState = ExtensionInstallState.INSTALLED_ENABLED.name,
                isBuiltIn = false,
                endpointUrl = null,
                skillContentPath = "${manifest.id}/${copySummary.entrypointRelPath}",
                discoveredToolsJson = null,
                healthMessage = "Staged ${copySummary.copiedFiles.size} file(s) · entry ${copySummary.entrypointRelPath} · SHA-256 ${fileDigest.take(12)}…",
                installedAtEpochMs = now,
                updatedAtEpochMs = now,
            )
            extensionDao.upsert(entity)
            return toRecord(entity, availableVersion = catalogEntry?.record?.version, catalogEntry = catalogEntry)
        } finally {
            tmpRoot.deleteRecursively()
        }
    }

    /**
     * Registers and optionally probes a local stdio / `npx` MCP server inside PRoot Linux.
     * Requires explicit sandbox opt-in ([isSandboxExtensionsAllowed]).
     */
    suspend fun registerLocalMcpServer(
        name: String,
        commandLine: String,
        description: String = "",
        publisher: String = "PRoot Stdio MCP",
        license: String = "Open-Source",
        envToken: String? = null,
        verifyConnection: Boolean = true,
        explicitId: String? = null,
        agentTarget: ExtensionAgentTarget = ExtensionAgentTarget.UNIVERSAL,
    ): Result<ExtensionRecord> = withContext(Dispatchers.IO) {
        runCatching {
            if (!isSandboxExtensionsAllowed()) {
                throw IllegalStateException(SANDBOX_GATE_ERROR)
            }
            val cleanName = name.trim()
            require(cleanName.isNotEmpty()) { "MCP server name is required." }

            val tokens = ExtensionPackageValidator.validateLocalMcpCommand(commandLine).getOrThrow()
            val id = ExtensionPackageValidator.validateExtensionId(
                explicitId ?: ExtensionPackageValidator.slugifyId("mcp.local.$cleanName")
            ).getOrThrow()
            val alias = mcpAliasForId(id)

            val cleanToken = envToken?.trim()?.takeIf { it.isNotEmpty() }
            if (cleanToken != null) {
                credentialVault.saveToken(id, cleanToken).getOrThrow()
            }
            val tokenEnvName = if (cleanToken != null) "AGT_MCP_TOKEN_${alias.uppercase()}" else null

            val stdioUri = "stdio://${tokens.joinToString(" ")}"
            var discoveredTools: List<McpToolDescriptor> = emptyList()
            var healthMessage = "Registered local PRoot MCP (${tokens.joinToString(" ")})"
            var serverVersion = "1.0.0"

            if (verifyConnection) {
                val exec = runtimeExecutor
                    ?: throw IllegalStateException("Local PRoot runtime executor is unavailable.")
                val availability = exec.ensureReady()
                if (!availability.isInstalled) {
                    throw IllegalStateException(
                        "Linux PRoot runtime must be installed before probing local stdio MCP (${availability.detail})."
                    )
                }
                val client = mcpStdioClient ?: McpStdioClient(exec)
                val probeEnv = buildMap {
                    if (tokenEnvName != null && cleanToken != null) {
                        put(tokenEnvName, cleanToken)
                    }
                }
                val probeDir = File(appContext.filesDir, "extensions").apply { mkdirs() }
                val (initRes, tools) = client.probeServer(
                    commandTokens = tokens,
                    workingDirectory = probeDir,
                    environment = probeEnv,
                    secretsToRedact = listOfNotNull(cleanToken),
                ).getOrElse { err ->
                    if (cleanToken != null && extensionDao.getById(id) == null) {
                        credentialVault.removeToken(id)
                    }
                    throw err
                }
                serverVersion = initRes.serverVersion ?: "1.0.0"
                discoveredTools = tools
                val label = initRes.serverName ?: cleanName
                healthMessage = "PRoot stdio connected: $label (${tools.size} tool(s) discovered)"
            }

            val perms = buildList {
                add("PROOT_SANDBOX_EXEC")
                add("MCP_TOOL_CALL")
                if (discoveredTools.any { it.isDestructive }) {
                    add("MCP_DESTRUCTIVE_APPROVAL")
                }
            }
            val matchedCatalog = getCatalogEntry(id)
            val effectiveTarget = matchedCatalog?.record?.agentTarget ?: agentTarget
            writeSidecarMeta(
                extensionId = id,
                agentTarget = effectiveTarget,
                tags = matchedCatalog?.record?.tags?.takeIf { it.isNotEmpty() } ?: listOf("stdio", "proot", tokens.first()),
                docsUrl = matchedCatalog?.record?.docsUrl,
                packageFormat = ExtensionPackageFormat.LOCAL_STDIO_MCP_TEMPLATE,
                mcpTransportType = McpTransportType.LOCAL_STDIO,
                localMcpCommand = tokens,
            )

            val now = System.currentTimeMillis()
            val entity = ExtensionEntity(
                id = id,
                name = cleanName,
                description = description.trim().ifBlank { "PRoot stdio MCP: ${tokens.joinToString(" ")}" },
                category = ExtensionCategory.MCP_SERVERS.name,
                publisher = publisher.trim().ifBlank { "PRoot Stdio MCP" },
                version = serverVersion,
                availableVersion = serverVersion,
                license = license.trim().ifBlank { "Open-Source" },
                compatibility = "PRoot Local Stdio MCP · JSON-RPC 2024-11-05",
                sourceUri = stdioUri,
                sha256 = ExtensionPackageValidator.sha256Hex(stdioUri.toByteArray(StandardCharsets.UTF_8)),
                permissionsCsv = perms.joinToString(","),
                installState = ExtensionInstallState.INSTALLED_ENABLED.name,
                isBuiltIn = false,
                endpointUrl = stdioUri,
                skillContentPath = null,
                discoveredToolsJson = encodeTools(discoveredTools),
                healthMessage = healthMessage,
                installedAtEpochMs = now,
                updatedAtEpochMs = now,
            )
            extensionDao.upsert(entity)
            toRecord(entity, catalogEntry = matchedCatalog).copy(agentTarget = effectiveTarget)
        }
    }

    /**
     * Registers and optionally handshakes with an HTTP/SSE MCP server (`initialize` + `tools/list`).
     * Bearer tokens are isolated inside Android Keystore ([ExtensionCredentialVault]).
     */
    suspend fun registerMcpServer(
        name: String,
        endpointUrl: String,
        description: String = "",
        publisher: String = "Remote MCP",
        license: String = "Server-Provided",
        bearerToken: String? = null,
        verifyConnection: Boolean = true,
        explicitId: String? = null,
        agentTarget: ExtensionAgentTarget = ExtensionAgentTarget.UNIVERSAL,
    ): Result<ExtensionRecord> = withContext(Dispatchers.IO) {
        runCatching {
            val cleanName = name.trim()
            require(cleanName.isNotEmpty()) { "MCP server name is required." }

            val validUrl = when (val v = ExtensionPackageValidator.validateMcpEndpoint(endpointUrl)) {
                is GatewayUrlValidation.Valid -> v.normalizedBaseUrl
                is GatewayUrlValidation.Invalid -> throw IllegalArgumentException(v.reason)
            }

            val id = ExtensionPackageValidator.validateExtensionId(
                explicitId ?: ExtensionPackageValidator.slugifyId("mcp.$cleanName")
            ).getOrThrow()

            val cleanToken = bearerToken?.trim()?.takeIf { it.isNotEmpty() }
            if (cleanToken != null) {
                credentialVault.saveToken(id, cleanToken).getOrThrow()
            }

            var discoveredTools: List<McpToolDescriptor> = emptyList()
            var healthMessage = "Registered ($validUrl)"
            var serverVersion = "1.0.0"

            if (verifyConnection) {
                val initRes = mcpHttpClient.initialize(validUrl, cleanToken).getOrElse { err ->
                    if (cleanToken != null && extensionDao.getById(id) == null) {
                        credentialVault.removeToken(id)
                    }
                    throw err
                }
                serverVersion = initRes.serverVersion ?: "1.0.0"
                val tools = mcpHttpClient.listTools(
                    endpointUrl = validUrl,
                    bearerToken = cleanToken,
                    sessionId = initRes.sessionId,
                ).getOrElse { err ->
                    if (cleanToken != null && extensionDao.getById(id) == null) {
                        credentialVault.removeToken(id)
                    }
                    throw err
                }
                discoveredTools = tools
                val label = initRes.serverName ?: cleanName
                healthMessage = "Connected to $label (${tools.size} tool(s) discovered)"
            }

            val perms = buildList {
                add("NETWORK_ACCESS")
                add("MCP_TOOL_CALL")
                if (discoveredTools.any { it.isDestructive }) {
                    add("MCP_DESTRUCTIVE_APPROVAL")
                }
            }

            val matchedCatalog = getCatalogEntry(id)
            val effectiveTarget = matchedCatalog?.record?.agentTarget ?: agentTarget
            writeSidecarMeta(
                extensionId = id,
                agentTarget = effectiveTarget,
                tags = matchedCatalog?.record?.tags.orEmpty(),
                docsUrl = matchedCatalog?.record?.docsUrl,
                packageFormat = ExtensionPackageFormat.REMOTE_MCP_TEMPLATE,
                mcpTransportType = McpTransportType.HTTP_SSE,
            )

            val now = System.currentTimeMillis()
            val entity = ExtensionEntity(
                id = id,
                name = cleanName,
                description = description.trim().ifBlank { "HTTP/SSE MCP server at $validUrl" },
                category = ExtensionCategory.MCP_SERVERS.name,
                publisher = publisher.trim().ifBlank { "Remote MCP" },
                version = serverVersion,
                availableVersion = serverVersion,
                license = license.trim().ifBlank { "Server-Provided" },
                compatibility = "MCP JSON-RPC 2024-11-05",
                sourceUri = validUrl,
                sha256 = ExtensionPackageValidator.sha256Hex(validUrl.toByteArray(StandardCharsets.UTF_8)),
                permissionsCsv = perms.joinToString(","),
                installState = ExtensionInstallState.INSTALLED_ENABLED.name,
                isBuiltIn = false,
                endpointUrl = validUrl,
                skillContentPath = null,
                discoveredToolsJson = encodeTools(discoveredTools),
                healthMessage = healthMessage,
                installedAtEpochMs = now,
                updatedAtEpochMs = now,
            )
            extensionDao.upsert(entity)
            toRecord(entity, catalogEntry = matchedCatalog).copy(agentTarget = effectiveTarget)
        }
    }

    suspend fun testMcpServerConnection(id: String): Result<ExtensionRecord> = withContext(Dispatchers.IO) {
        runCatching {
            val entity = extensionDao.getById(id)
                ?: throw IllegalArgumentException("MCP server '$id' is not installed.")
            val record = toRecord(entity, catalogEntry = getCatalogEntry(id))
            val token = credentialVault.readToken(id).getOrNull()

            if (record.mcpTransportType == McpTransportType.LOCAL_STDIO) {
                if (!isSandboxExtensionsAllowed()) {
                    throw IllegalStateException(SANDBOX_GATE_ERROR)
                }
                val exec = runtimeExecutor
                    ?: throw IllegalStateException("Local PRoot runtime executor is unavailable.")
                if (!exec.ensureReady().isInstalled) {
                    throw IllegalStateException("Linux PRoot runtime must be installed to test local stdio MCP.")
                }
                val cmdTokens = ExtensionPackageValidator.validateLocalMcpCommandTokens(record.localMcpCommand).getOrThrow()
                val alias = mcpAliasForId(id)
                val probeEnv = buildMap {
                    if (!token.isNullOrBlank()) {
                        put("AGT_MCP_TOKEN_${alias.uppercase()}", token)
                    }
                }
                val (initRes, tools) = (mcpStdioClient ?: McpStdioClient(exec)).probeServer(
                    commandTokens = cmdTokens,
                    workingDirectory = extensionsRoot,
                    environment = probeEnv,
                    secretsToRedact = listOfNotNull(token),
                ).getOrThrow()
                val healthMsg = "PRoot stdio connected (${initRes.serverName ?: record.name} · ${tools.size} tool(s) verified)"
                val now = System.currentTimeMillis()
                val toolsJson = encodeTools(tools)
                extensionDao.updateHealthAndTools(
                    id = id,
                    discoveredToolsJson = toolsJson,
                    healthMessage = healthMsg,
                    updatedAtEpochMs = now,
                )
                val updated = extensionDao.getById(id) ?: entity.copy(
                    discoveredToolsJson = toolsJson,
                    healthMessage = healthMsg,
                    updatedAtEpochMs = now,
                )
                return@runCatching toRecord(updated, catalogEntry = getCatalogEntry(id))
            }

            val endpoint = entity.endpointUrl
                ?: throw IllegalStateException("Extension '$id' has no MCP endpoint URL.")
            val initRes = mcpHttpClient.initialize(endpoint, token).getOrThrow()
            val tools = mcpHttpClient.listTools(endpoint, token, initRes.sessionId).getOrThrow()
            val healthMsg = "Connected (${tools.size} tool(s) verified)"
            val now = System.currentTimeMillis()
            val toolsJson = encodeTools(tools)
            extensionDao.updateHealthAndTools(
                id = id,
                discoveredToolsJson = toolsJson,
                healthMessage = healthMsg,
                updatedAtEpochMs = now,
            )
            val updated = extensionDao.getById(id) ?: entity.copy(
                discoveredToolsJson = toolsJson,
                healthMessage = healthMsg,
                updatedAtEpochMs = now,
            )
            toRecord(updated, catalogEntry = getCatalogEntry(id))
        }
    }

    suspend fun setEnabled(id: String, enabled: Boolean): Result<Unit> = withContext(Dispatchers.IO) {
        runCatching {
            val entity = extensionDao.getById(id)
                ?: throw IllegalArgumentException("Extension '$id' is not installed.")
            if (enabled) {
                val rec = toRecord(entity, catalogEntry = getCatalogEntry(id))
                val requiresSandbox = rec.category == ExtensionCategory.PLUGINS ||
                    (rec.category == ExtensionCategory.MCP_SERVERS && rec.mcpTransportType == McpTransportType.LOCAL_STDIO)
                if (requiresSandbox && !isSandboxExtensionsAllowed()) {
                    throw IllegalStateException(SANDBOX_GATE_ERROR)
                }
            }
            val targetState = if (enabled) {
                ExtensionInstallState.INSTALLED_ENABLED.name
            } else {
                ExtensionInstallState.INSTALLED_DISABLED.name
            }
            extensionDao.updateState(id, targetState, System.currentTimeMillis())
            Unit
        }
    }

    suspend fun updateExtension(id: String): Result<ExtensionRecord> = withContext(Dispatchers.IO) {
        val catalogEntry = getCatalogEntry(id)
        val existing = extensionDao.getById(id)
            ?: return@withContext if (catalogEntry != null) {
                installCatalogExtension(id)
            } else {
                Result.failure(IllegalArgumentException("Extension '$id' is not installed."))
            }

        if (existing.isBuiltIn && existing.category == ExtensionCategory.SKILLS.name) {
            return@withContext installBuiltInSkill(id)
        }
        if (existing.category == ExtensionCategory.MCP_SERVERS.name) {
            return@withContext testMcpServerConnection(id)
        }
        if (catalogEntry != null &&
            catalogEntry.format != ExtensionPackageFormat.REMOTE_MCP_TEMPLATE &&
            catalogEntry.format != ExtensionPackageFormat.LOCAL_STDIO_MCP_TEMPLATE
        ) {
            return@withContext installCatalogExtension(id)
        }
        if (existing.sourceUri.startsWith("https://", ignoreCase = true) ||
            existing.sourceUri.startsWith("http://", ignoreCase = true)
        ) {
            return@withContext if (existing.category == ExtensionCategory.PLUGINS.name) {
                importPluginPackage(existing.sourceUri)
            } else {
                importSkillPackage(existing.sourceUri)
            }
        }

        runCatching {
            extensionDao.updateState(id, ExtensionInstallState.INSTALLED_ENABLED.name, System.currentTimeMillis())
            toRecord(extensionDao.getById(id) ?: existing, catalogEntry = catalogEntry)
        }
    }

    /**
     * Uninstalls extension, deletes any extracted SKILL.md directory, removes Keystore token,
     * and revokes active permissions/tool registrations immediately.
     */
    suspend fun uninstall(id: String): Result<Unit> = withContext(Dispatchers.IO) {
        runCatching {
            val entity = extensionDao.getById(id)
            if (entity?.skillContentPath != null) {
                runCatching {
                    val file = ProjectPathValidator
                        .resolveWithinRoot(extensionsRoot, entity.skillContentPath)
                        .getOrThrow()
                    val parent = file.parentFile?.canonicalFile
                    val rootCanonical = extensionsRoot.canonicalFile
                    if (parent != null && parent != rootCanonical && parent.path.startsWith("${rootCanonical.path}${File.separator}")) {
                        parent.deleteRecursively()
                    }
                }
            }
            credentialVault.removeToken(id)
            extensionDao.deleteById(id)
            Unit
        }
    }

    /**
     * Returns only INSTALLED_ENABLED Skills and their non-executable SKILL.md markdown instructions.
     * Disabled or uninstalled Skills are strictly excluded.
     */
    suspend fun getActiveSkillPrompts(selectedSkillIds: Set<String>? = null): List<Pair<ExtensionRecord, String>> =
        withContext(Dispatchers.IO) {
            val entities = extensionDao.getAll().filter {
                it.category == ExtensionCategory.SKILLS.name &&
                    it.installState == ExtensionInstallState.INSTALLED_ENABLED.name &&
                    (selectedSkillIds == null || it.id in selectedSkillIds)
            }
            entities.mapNotNull { entity ->
                val relPath = entity.skillContentPath ?: return@mapNotNull null
                val file = runCatching {
                    ProjectPathValidator.resolveWithinRoot(extensionsRoot, relPath).getOrThrow()
                }.getOrNull() ?: return@mapNotNull null
                if (!file.exists() || !file.isFile) return@mapNotNull null
                val content = runCatching { file.readText() }.getOrNull()?.takeIf { it.isNotBlank() }
                    ?: return@mapNotNull null
                toRecord(entity, catalogEntry = getCatalogEntry(entity.id)) to content
            }
        }

    /**
     * Copies every INSTALLED_ENABLED non-executable Skill package folder into [skillsTargetDir]
     * (`<skillsTargetDir>/<slug>/SKILL.md`) so OpenCode (`$OPENCODE_CONFIG_DIR/skills/`)
     * and Pi Coding Agent (`$PI_CODING_AGENT_DIR/skills/`) discover installed Skills natively.
     */
    suspend fun syncEnabledSkillsToDirectory(
        skillsTargetDir: File,
        selectedSkillIds: Set<String>? = null,
    ): Int = withContext(Dispatchers.IO) {
        val enabledSkills = extensionDao.getAll().filter {
            it.category == ExtensionCategory.SKILLS.name &&
                it.installState == ExtensionInstallState.INSTALLED_ENABLED.name &&
                (selectedSkillIds == null || it.id in selectedSkillIds)
        }
        skillsTargetDir.mkdirs()
        var syncedCount = 0
        enabledSkills.forEach { entity ->
            val relPath = entity.skillContentPath ?: return@forEach
            val skillFile = runCatching {
                ProjectPathValidator.resolveWithinRoot(extensionsRoot, relPath).getOrThrow()
            }.getOrNull() ?: return@forEach
            val sourceDir = skillFile.parentFile ?: return@forEach
            if (!sourceDir.isDirectory || !skillFile.isFile) return@forEach

            val cleanSlug = ExtensionPackageValidator.slugifyId(entity.id)
            val destDir = File(skillsTargetDir, cleanSlug).apply {
                if (exists()) deleteRecursively()
                mkdirs()
            }
            sourceDir.listFiles()?.forEach { child ->
                if (child.name == META_FILE_NAME) return@forEach
                if (child.isFile) {
                    child.copyTo(File(destDir, child.name), overwrite = true)
                } else if (child.isDirectory) {
                    RuntimeArchiveExtractor.mergeDirectoryInto(
                        sourceDir = child,
                        targetDir = File(destDir, child.name),
                        confinementRoot = destDir,
                    )
                }
            }
            syncedCount++
        }
        syncedCount
    }

    /**
     * Returns runtime configs for INSTALLED_ENABLED sandboxed JS/TS plugins when sandbox gate is open.
     */
    suspend fun getEnabledPluginRuntimeConfigs(
        selectedPluginIds: Set<String>? = null,
    ): List<PluginRuntimeConfig> = withContext(Dispatchers.IO) {
        if (!isSandboxExtensionsAllowed()) return@withContext emptyList()
        extensionDao.getAll()
            .filter {
                it.category == ExtensionCategory.PLUGINS.name &&
                    it.installState == ExtensionInstallState.INSTALLED_ENABLED.name &&
                    (selectedPluginIds == null || it.id in selectedPluginIds)
            }
            .mapNotNull { entity ->
                val record = toRecord(entity, catalogEntry = getCatalogEntry(entity.id))
                val entrypoint = record.pluginEntrypoint ?: entity.skillContentPath?.substringAfter('/') ?: "index.ts"
                val entryFile = ExtensionPackageValidator.resolveConfinedPluginEntrypoint(
                    extensionsRoot = extensionsRoot,
                    extensionId = entity.id,
                    entrypointRelPath = entrypoint,
                ).getOrNull() ?: return@mapNotNull null
                if (!entryFile.isFile) return@mapNotNull null
                PluginRuntimeConfig(
                    id = entity.id,
                    slug = ExtensionPackageValidator.slugifyId(entity.id),
                    entrypointRelPath = entrypoint,
                    agentTarget = record.agentTarget,
                )
            }
    }

    /**
     * Copies active sandboxed JS/TS plugins into [pluginsTargetDir] (`<pluginsTargetDir>/<slug>/...`)
     * for OpenCode (`$OPENCODE_CONFIG_DIR/plugins/`) or Pi (`$PI_CODING_AGENT_DIR/extensions/`).
     */
    suspend fun syncEnabledPluginsToDirectory(
        pluginsTargetDir: File,
        selectedPluginIds: Set<String>? = null,
    ): Int = withContext(Dispatchers.IO) {
        if (pluginsTargetDir.exists()) {
            pluginsTargetDir.listFiles()?.forEach { it.deleteRecursively() }
        }
        if (!isSandboxExtensionsAllowed()) return@withContext 0

        val configs = getEnabledPluginRuntimeConfigs(selectedPluginIds)
        if (configs.isEmpty()) return@withContext 0
        pluginsTargetDir.mkdirs()

        var synced = 0
        configs.forEach { cfg ->
            val srcDir = ExtensionPackageValidator.resolveConfinedExtensionDir(extensionsRoot, cfg.id)
                .getOrNull() ?: return@forEach
            if (!srcDir.isDirectory) return@forEach
            val destDir = File(pluginsTargetDir, cfg.slug).apply {
                if (exists()) deleteRecursively()
                mkdirs()
            }
            srcDir.listFiles()?.forEach { child ->
                if (child.name == META_FILE_NAME) return@forEach
                if (child.isFile) {
                    child.copyTo(File(destDir, child.name), overwrite = true)
                } else if (child.isDirectory) {
                    RuntimeArchiveExtractor.mergeDirectoryInto(
                        sourceDir = child,
                        targetDir = File(destDir, child.name),
                        confinementRoot = destDir,
                    )
                }
            }
            synced++
        }
        synced
    }

    /**
     * Returns runtime configs (with tokens held strictly in memory) for INSTALLED_ENABLED MCP servers.
     * Local stdio MCP servers are excluded if the sandbox toggle is disabled.
     */
    suspend fun getEnabledMcpRuntimeConfigs(selectedMcpIds: Set<String>? = null): List<McpServerRuntimeConfig> =
        withContext(Dispatchers.IO) {
            val sandboxAllowed = isSandboxExtensionsAllowed()
            extensionDao.getAll()
                .filter {
                    it.category == ExtensionCategory.MCP_SERVERS.name &&
                        it.installState == ExtensionInstallState.INSTALLED_ENABLED.name &&
                        !it.endpointUrl.isNullOrBlank() &&
                        (selectedMcpIds == null || it.id in selectedMcpIds)
                }
                .mapNotNull { entity ->
                    val record = toRecord(entity, catalogEntry = getCatalogEntry(entity.id))
                    if (record.mcpTransportType == McpTransportType.LOCAL_STDIO && !sandboxAllowed) {
                        return@mapNotNull null
                    }
                    val alias = mcpAliasForId(entity.id)
                    val token = credentialVault.readToken(entity.id).getOrNull()
                    val envVar = if (!token.isNullOrBlank()) {
                        "AGT_MCP_TOKEN_${alias.uppercase()}"
                    } else {
                        null
                    }
                    McpServerRuntimeConfig(
                        id = entity.id,
                        alias = alias,
                        endpointUrl = entity.endpointUrl!!,
                        envVarTokenName = envVar,
                        bearerToken = token,
                        transportType = record.mcpTransportType,
                        localCommand = record.localMcpCommand,
                    )
                }
        }

    /**
     * Computes active MCP + sandboxed plugin authorization policy for [com.example.agent.tools.ToolBroker].
     */
    suspend fun buildActiveExtensionPolicy(
        selectedMcpIds: Set<String>? = null,
        selectedPluginIds: Set<String>? = null,
    ): ActiveExtensionPolicy =
        withContext(Dispatchers.IO) {
            val sandboxAllowed = isSandboxExtensionsAllowed()
            val allEntities = extensionDao.getAll()
            val enabledMcps = allEntities.filter { entity ->
                if (entity.category != ExtensionCategory.MCP_SERVERS.name ||
                    entity.installState != ExtensionInstallState.INSTALLED_ENABLED.name ||
                    (selectedMcpIds != null && entity.id !in selectedMcpIds)
                ) {
                    return@filter false
                }
                val rec = toRecord(entity, catalogEntry = getCatalogEntry(entity.id))
                rec.mcpTransportType != McpTransportType.LOCAL_STDIO || sandboxAllowed
            }
            val prefixes = mutableSetOf<String>()
            val allowedTools = mutableSetOf<String>()
            val destructiveTools = mutableSetOf<String>()

            enabledMcps.forEach { entity ->
                val alias = mcpAliasForId(entity.id).lowercase()
                val prefix = "mcp_${alias}_"
                prefixes += prefix
                val tools = decodeTools(entity.discoveredToolsJson)
                tools.forEach { t ->
                    val normalizedTool = t.name.trim().lowercase()
                    val fullNamespaced = "$prefix$normalizedTool"
                    allowedTools += fullNamespaced
                    allowedTools += normalizedTool
                    if (t.isDestructive) {
                        destructiveTools += fullNamespaced
                        destructiveTools += normalizedTool
                    }
                }
            }

            val enabledPluginIds = if (sandboxAllowed) {
                allEntities.filter {
                    it.category == ExtensionCategory.PLUGINS.name &&
                        it.installState == ExtensionInstallState.INSTALLED_ENABLED.name &&
                        (selectedPluginIds == null || it.id in selectedPluginIds)
                }.map { it.id }.toSet()
            } else {
                emptySet()
            }

            ActiveExtensionPolicy(
                enforceMcpAllowlist = true,
                enabledMcpPrefixes = prefixes,
                enabledMcpToolNames = allowedTools,
                destructiveMcpTools = destructiveTools,
                enabledPluginIds = enabledPluginIds,
            )
        }

    suspend fun allSecretTokens(): List<String> = credentialVault.allStoredTokens()

    private fun mergedCatalogEntries(remoteEntries: List<ExtensionCatalogEntry>): List<ExtensionCatalogEntry> {
        val byId = LinkedHashMap<String, ExtensionCatalogEntry>()
        ExtensionCatalog.DEFAULT_CATALOG.forEach { byId[it.record.id] = it }
        remoteEntries.forEach { entry ->
            // Built-in IDs cannot be overwritten with non-builtin remote entries
            if (byId[entry.record.id]?.record?.isBuiltIn != true) {
                byId[entry.record.id] = entry
            }
        }
        return byId.values.toList()
    }

    private fun mergeCatalogWithInstalled(
        entities: List<ExtensionEntity>,
        remoteEntries: List<ExtensionCatalogEntry>,
    ): List<ExtensionRecord> {
        val catalog = mergedCatalogEntries(remoteEntries)
        val installedById = entities.associateBy { it.id }
        val result = mutableListOf<ExtensionRecord>()

        catalog.forEach { catalogItem ->
            val installed = installedById[catalogItem.record.id]
            if (installed == null) {
                result += catalogItem.record.copy(
                    skillMarkdownPreview = catalogItem.inlineSkillMarkdown ?: catalogItem.record.skillMarkdownPreview,
                )
            } else {
                val hasUpdate = installed.version != catalogItem.record.version &&
                    installed.installState != ExtensionInstallState.INSTALLED_DISABLED.name &&
                    catalogItem.format != ExtensionPackageFormat.REMOTE_MCP_TEMPLATE &&
                    catalogItem.format != ExtensionPackageFormat.LOCAL_STDIO_MCP_TEMPLATE
                result += toRecord(
                    entity = installed,
                    availableVersion = catalogItem.record.version,
                    overrideState = if (hasUpdate) ExtensionInstallState.UPDATE_AVAILABLE else null,
                    catalogEntry = catalogItem,
                )
            }
        }

        val catalogIds = catalog.map { it.record.id }.toSet()
        entities.filterNot { it.id in catalogIds }
            .forEach { ent ->
                result += toRecord(ent)
            }

        return result
    }

    private fun toRecord(
        entity: ExtensionEntity,
        availableVersion: String? = entity.availableVersion,
        overrideState: ExtensionInstallState? = null,
        catalogEntry: ExtensionCatalogEntry? = null,
    ): ExtensionRecord {
        val category = runCatching { ExtensionCategory.valueOf(entity.category) }
            .getOrDefault(ExtensionCategory.SKILLS)
        val state = overrideState ?: runCatching { ExtensionInstallState.valueOf(entity.installState) }
            .getOrDefault(ExtensionInstallState.INSTALLED_DISABLED)
        val preview = entity.skillContentPath?.let { rel ->
            runCatching {
                val file = ProjectPathValidator.resolveWithinRoot(extensionsRoot, rel).getOrThrow()
                if (file.exists()) file.readText().take(2_000) else null
            }.getOrNull()
        }
        val sidecar = readSidecarMeta(entity.id)
        val agentTarget = sidecar?.agentTarget
            ?: catalogEntry?.record?.agentTarget
            ?: ExtensionPackageValidator.inferAgentTarget(entity.id, entity.sourceUri, entity.compatibility)
        val tags = sidecar?.tags?.takeIf { it.isNotEmpty() }
            ?: catalogEntry?.record?.tags.orEmpty()
        val docsUrl = sidecar?.docsUrl ?: catalogEntry?.record?.docsUrl
        val mcpTransport = sidecar?.mcpTransportType
            ?: catalogEntry?.record?.mcpTransportType
            ?: if (entity.endpointUrl?.startsWith("stdio://") == true) {
                McpTransportType.LOCAL_STDIO
            } else {
                McpTransportType.HTTP_SSE
            }
        val localCmd = sidecar?.localMcpCommand?.takeIf { it.isNotEmpty() }
            ?: catalogEntry?.localCommand?.takeIf { it.isNotEmpty() }
            ?: if (mcpTransport == McpTransportType.LOCAL_STDIO && entity.endpointUrl != null) {
                ExtensionPackageValidator.validateLocalMcpCommand(
                    entity.endpointUrl.removePrefix("stdio://")
                ).getOrDefault(emptyList())
            } else {
                emptyList()
            }
        val pluginEntry = sidecar?.pluginEntrypoint
            ?: catalogEntry?.pluginEntrypoint
            ?: if (category == ExtensionCategory.PLUGINS) {
                entity.skillContentPath?.substringAfter('/')?.takeIf { it.isNotBlank() }
            } else {
                null
            }
        val pkgFormat = sidecar?.packageFormat
            ?: catalogEntry?.format
            ?: when {
                category == ExtensionCategory.PLUGINS -> ExtensionPackageFormat.JS_TS_PLUGIN
                category == ExtensionCategory.MCP_SERVERS && mcpTransport == McpTransportType.LOCAL_STDIO ->
                    ExtensionPackageFormat.LOCAL_STDIO_MCP_TEMPLATE
                category == ExtensionCategory.MCP_SERVERS -> ExtensionPackageFormat.REMOTE_MCP_TEMPLATE
                else -> ExtensionPackageFormat.RAW_SKILL_MD
            }

        return ExtensionRecord(
            id = entity.id,
            name = entity.name,
            description = entity.description,
            category = category,
            publisher = entity.publisher,
            version = entity.version,
            license = entity.license,
            compatibility = entity.compatibility,
            permissions = entity.permissionsCsv.split(',').map { it.trim() }.filter { it.isNotEmpty() },
            installState = state,
            isBuiltIn = entity.isBuiltIn,
            sourceUri = entity.sourceUri,
            sha256 = entity.sha256,
            endpointUrl = entity.endpointUrl,
            skillMarkdownPreview = preview,
            discoveredTools = decodeTools(entity.discoveredToolsJson),
            hasStoredToken = credentialVault.hasToken(entity.id),
            healthMessage = entity.healthMessage,
            availableVersion = availableVersion,
            agentTarget = agentTarget,
            tags = tags,
            docsUrl = docsUrl,
            packageFormat = pkgFormat,
            requiresBearerToken = catalogEntry?.record?.requiresBearerToken ?: false,
            mcpTransportType = mcpTransport,
            localMcpCommand = localCmd,
            pluginEntrypoint = pluginEntry,
        )
    }

    private data class SidecarMeta(
        val agentTarget: ExtensionAgentTarget,
        val tags: List<String>,
        val docsUrl: String?,
        val packageFormat: ExtensionPackageFormat?,
        val mcpTransportType: McpTransportType? = null,
        val localMcpCommand: List<String> = emptyList(),
        val pluginEntrypoint: String? = null,
    )

    private fun writeSidecarMeta(
        extensionId: String,
        agentTarget: ExtensionAgentTarget,
        tags: List<String>,
        docsUrl: String?,
        packageFormat: ExtensionPackageFormat,
        mcpTransportType: McpTransportType? = null,
        localMcpCommand: List<String> = emptyList(),
        pluginEntrypoint: String? = null,
    ) {
        runCatching {
            val extDir = ExtensionPackageValidator.resolveConfinedExtensionDir(extensionsRoot, extensionId).getOrThrow()
            extDir.mkdirs()
            val json = JSONObject()
                .put("agentTarget", agentTarget.name)
                .put("packageFormat", packageFormat.name)
                .put("tags", JSONArray(tags))
            if (!docsUrl.isNullOrBlank()) json.put("docsUrl", docsUrl)
            if (mcpTransportType != null) json.put("mcpTransportType", mcpTransportType.name)
            if (localMcpCommand.isNotEmpty()) json.put("localMcpCommand", JSONArray(localMcpCommand))
            if (!pluginEntrypoint.isNullOrBlank()) json.put("pluginEntrypoint", pluginEntrypoint)
            File(extDir, META_FILE_NAME).writeText(json.toString(), StandardCharsets.UTF_8)
        }
    }

    private fun readSidecarMeta(extensionId: String): SidecarMeta? = runCatching {
        val extDir = ExtensionPackageValidator.resolveConfinedExtensionDir(extensionsRoot, extensionId).getOrThrow()
        val metaFile = File(extDir, META_FILE_NAME)
        if (!metaFile.isFile) return@runCatching null
        val obj = JSONObject(metaFile.readText(StandardCharsets.UTF_8))
        val target = runCatching { ExtensionAgentTarget.valueOf(obj.optString("agentTarget")) }
            .getOrDefault(ExtensionAgentTarget.UNIVERSAL)
        val format = runCatching { ExtensionPackageFormat.valueOf(obj.optString("packageFormat")) }.getOrNull()
        val transport = runCatching { McpTransportType.valueOf(obj.optString("mcpTransportType")) }.getOrNull()
        val cmdArr = obj.optJSONArray("localMcpCommand") ?: JSONArray()
        val cmdTokens = buildList {
            for (i in 0 until cmdArr.length()) {
                val tok = cmdArr.optString(i).trim()
                if (tok.isNotEmpty()) add(tok)
            }
        }
        val pluginEntry = obj.optString("pluginEntrypoint").trim().takeIf { it.isNotEmpty() }
        val tagsArr = obj.optJSONArray("tags") ?: JSONArray()
        val tags = buildList {
            for (i in 0 until tagsArr.length()) {
                val t = tagsArr.optString(i).trim()
                if (t.isNotEmpty()) add(t)
            }
        }
        val docsUrl = obj.optString("docsUrl").trim().takeIf { it.isNotEmpty() }
        SidecarMeta(
            agentTarget = target,
            tags = tags,
            docsUrl = docsUrl,
            packageFormat = format,
            mcpTransportType = transport,
            localMcpCommand = cmdTokens,
            pluginEntrypoint = pluginEntry,
        )
    }.getOrNull()

    private fun loadCachedCatalogEntries(): List<ExtensionCatalogEntry> = runCatching {
        if (!catalogCacheFile.isFile) return@runCatching emptyList()
        ExtensionCatalog.parseRemoteCatalogJson(catalogCacheFile.readText(StandardCharsets.UTF_8))
            .getOrDefault(emptyList())
    }.getOrDefault(emptyList())

    private fun downloadToFileWithProgress(
        url: String,
        destFile: File,
        progressId: String,
        progressName: String,
    ) {
        val request = Request.Builder()
            .url(url)
            .header("User-Agent", "AGT-Code-Android/1.0")
            .get()
            .build()
        okHttpClient.newCall(request).execute().use { resp ->
            if (!resp.isSuccessful) {
                throw IllegalStateException("Download failed (HTTP ${resp.code}) from $url")
            }
            val body = resp.body ?: throw IllegalStateException("Empty HTTP response body.")
            val totalBytes = body.contentLength()
            var downloaded = 0L
            var lastReportedPct = -1
            val buf = ByteArray(16 * 1024)
            body.byteStream().use { input ->
                FileOutputStream(destFile).use { output ->
                    while (true) {
                        val n = input.read(buf)
                        if (n < 0) break
                        downloaded += n
                        if (downloaded > MAX_DOWNLOAD_BYTES) {
                            throw IllegalStateException("Downloaded package exceeds 25 MB safety limit.")
                        }
                        output.write(buf, 0, n)
                        val pct = if (totalBytes > 0L) {
                            ((downloaded * 100L) / totalBytes).toInt().coerceIn(0, 100)
                        } else {
                            null
                        }
                        if (pct != null && pct != lastReportedPct) {
                            lastReportedPct = pct
                            _installProgress.value = ExtensionInstallProgress(
                                extensionId = progressId,
                                extensionName = progressName,
                                stage = ExtensionInstallStage.DOWNLOADING,
                                progressPercent = pct,
                                detail = "Downloading package ($pct%)…",
                            )
                        }
                    }
                }
            }
            require(downloaded > 0L) { "Downloaded extension package was empty." }
        }
    }

    private fun fetchUrlText(url: String, maxChars: Int = MAX_MANIFEST_CHARS): String {
        val request = Request.Builder()
            .url(url)
            .header("User-Agent", "AGT-Code-Android/1.0")
            .get()
            .build()
        okHttpClient.newCall(request).execute().use { resp ->
            if (!resp.isSuccessful) {
                throw IllegalStateException("Failed to fetch extension resource (HTTP ${resp.code})")
            }
            val body = resp.body?.string().orEmpty()
            require(body.isNotBlank()) { "Downloaded extension resource was empty." }
            require(body.length <= maxChars) { "Extension resource exceeds maximum allowed size." }
            return body
        }
    }

    private fun encodeTools(tools: List<McpToolDescriptor>): String? {
        if (tools.isEmpty()) return null
        val arr = JSONArray()
        tools.forEach { t ->
            arr.put(
                JSONObject()
                    .put("name", t.name)
                    .put("description", t.description)
                    .put("isDestructive", t.isDestructive)
            )
        }
        return arr.toString()
    }

    private fun decodeTools(rawJson: String?): List<McpToolDescriptor> {
        if (rawJson.isNullOrBlank()) return emptyList()
        return runCatching {
            val arr = JSONArray(rawJson)
            buildList {
                for (i in 0 until arr.length()) {
                    val obj = arr.optJSONObject(i) ?: continue
                    val name = obj.optString("name").trim()
                    if (name.isEmpty()) continue
                    add(
                        McpToolDescriptor(
                            name = name,
                            description = obj.optString("description"),
                            isDestructive = obj.optBoolean("isDestructive", false),
                        )
                    )
                }
            }
        }.getOrDefault(emptyList())
    }

    companion object {
        private const val MAX_MANIFEST_CHARS = 512 * 1024
        private const val MAX_CATALOG_CHARS = 2 * 1024 * 1024
        private const val MAX_DOWNLOAD_BYTES = 25L * 1024L * 1024L
        private const val MAX_EXTRACTED_EXTENSION_BYTES = 25L * 1024L * 1024L
        private const val MAX_EXTRACTED_EXTENSION_ENTRIES = 1_500
        private const val META_FILE_NAME = ".agt_meta.json"
        const val SANDBOX_GATE_ERROR =
            "Enable the Sandboxed PRoot Extension Host opt-in toggle on the Plugins tab before installing or running plugins or local stdio MCP servers."

        fun mcpAliasForId(extensionId: String): String =
            extensionId.lowercase().replace(Regex("[^a-z0-9]+"), "_").trim('_').ifBlank { "server" }

        val BUILT_IN_SKILLS: List<ExtensionCatalogEntry>
            get() = ExtensionCatalog.BUILT_IN_SKILLS
    }
}
