package com.example.data.runtime

import com.example.core.common.AgtError
import com.example.core.security.GatewayUrlValidation
import com.example.core.security.GatewayUrlValidator
import com.example.core.security.SecretRedactor
import com.example.domain.model.RuntimeArchiveFormat
import com.example.domain.model.RuntimeBundleSpec
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONArray
import org.json.JSONObject
import java.io.IOException

data class UpstreamBootstrapEndpoints(
    val ubuntuSha256SumsUrl: String = "https://cdimage.ubuntu.com/ubuntu-base/releases/24.04/release/SHA256SUMS",
    val ubuntuBaseDirUrl: String = "https://cdimage.ubuntu.com/ubuntu-base/releases/24.04/release",
    val nodeSha256SumsUrl: String = "https://nodejs.org/dist/latest-v22.x/SHASUMS256.txt",
    val nodeBaseDirUrl: String = "https://nodejs.org/dist/latest-v22.x",
    val ghReleaseApiUrl: String = "https://api.github.com/repos/cli/cli/releases/latest",
    val openCodeReleaseApiUrls: List<String> = listOf(
        "https://api.github.com/repos/anomalyco/opencode/releases/latest",
        "https://api.github.com/repos/sst/opencode/releases/latest",
    ),
    val githubWebBaseUrl: String = "https://github.com",
)

data class UpstreamPackageSpec(
    val componentId: String,
    val displayName: String,
    val version: String,
    val archiveName: String,
    val bundle: RuntimeBundleSpec,
)

data class UpstreamBootstrapPlan(
    val abi: String,
    val ubuntuRootfs: UpstreamPackageSpec,
    val nodeToolchain: UpstreamPackageSpec,
    val githubCli: UpstreamPackageSpec?,
    val openCodeAgent: UpstreamPackageSpec?,
    val warnings: List<String> = emptyList(),
)

/**
 * Resolves official upstream Linux userspace and developer toolchain archives directly from
 * Canonical Ubuntu (`cdimage.ubuntu.com`), Node.js (`nodejs.org`), and GitHub Releases (`cli/cli`,
 * `opencode`) along with authoritative SHA-256 checksums.
 */
class UpstreamRuntimeBootstrap(
    private val okHttpClient: OkHttpClient = RuntimeDownloader.defaultClient(),
    private val endpoints: UpstreamBootstrapEndpoints = UpstreamBootstrapEndpoints(),
) {

    suspend fun resolvePlan(abi: String): Result<UpstreamBootstrapPlan> = withContext(Dispatchers.IO) {
        val normalizedAbi = abi.trim().lowercase()
        if (normalizedAbi != "arm64-v8a" && normalizedAbi != "x86_64") {
            return@withContext Result.failure(
                RuntimeManifestException(
                    AgtError.RuntimeManifestInvalid(
                        "Unsupported CPU ABI ($abi) for upstream Linux bootstrap. Supported: arm64-v8a, x86_64."
                    )
                )
            )
        }

        val warnings = mutableListOf<String>()

        val ubuntuSpec = resolveUbuntuRootfs(normalizedAbi).getOrElse {
            return@withContext Result.failure(it)
        }
        val nodeSpec = resolveNodeToolchain(normalizedAbi).getOrElse {
            return@withContext Result.failure(it)
        }

        val ghSpec = resolveGitHubCli(normalizedAbi).getOrElse { err ->
            warnings += "GitHub CLI (gh): ${err.message ?: "unavailable"}"
            null
        }
        val openCodeSpec = resolveOpenCode(normalizedAbi).getOrElse { err ->
            warnings += "OpenCode CLI: ${err.message ?: "unavailable"}"
            null
        }

        Result.success(
            UpstreamBootstrapPlan(
                abi = normalizedAbi,
                ubuntuRootfs = ubuntuSpec,
                nodeToolchain = nodeSpec,
                githubCli = ghSpec,
                openCodeAgent = openCodeSpec,
                warnings = warnings,
            )
        )
    }

    suspend fun resolveUbuntuRootfs(abi: String): Result<UpstreamPackageSpec> = withContext(Dispatchers.IO) {
        val ubuntuArch = when (abi.lowercase()) {
            "arm64-v8a" -> "arm64"
            "x86_64" -> "amd64"
            else -> return@withContext Result.failure(
                IllegalArgumentException("Unsupported ABI for Ubuntu rootfs: $abi")
            )
        }
        val sumsText = fetchText(endpoints.ubuntuSha256SumsUrl).getOrElse {
            return@withContext Result.failure(it)
        }
        val pattern = Regex("""^([0-9a-fA-F]{64})\s+\*?(ubuntu-base-(24\.04(?:\.\d+)?)-base-$ubuntuArch\.tar\.gz)$""")
        val matched = sumsText.lineSequence()
            .mapNotNull { line -> pattern.matchEntire(line.trim()) }
            .firstOrNull()
            ?: return@withContext Result.failure(
                RuntimeManifestException(
                    AgtError.RuntimeManifestInvalid(
                        "Could not locate Ubuntu 24.04 ($ubuntuArch) rootfs entry in official SHA256SUMS."
                    )
                )
            )

        val sha256 = matched.groupValues[1].lowercase()
        val filename = matched.groupValues[2]
        val version = matched.groupValues[3]
        val baseDir = endpoints.ubuntuBaseDirUrl.trimEnd('/')

        Result.success(
            UpstreamPackageSpec(
                componentId = "linux_runtime",
                displayName = "Ubuntu $version ($ubuntuArch)",
                version = version,
                archiveName = filename,
                bundle = RuntimeBundleSpec(
                    downloadUrl = "$baseDir/$filename",
                    compressedSizeBytes = 0L,
                    sha256 = sha256,
                    archiveFormat = RuntimeArchiveFormat.TAR_GZ,
                    extractionRoot = "",
                    requiredPaths = listOf("bin/sh", "etc/os-release"),
                ),
            )
        )
    }

    suspend fun resolveNodeToolchain(abi: String): Result<UpstreamPackageSpec> = withContext(Dispatchers.IO) {
        val nodeArch = when (abi.lowercase()) {
            "arm64-v8a" -> "arm64"
            "x86_64" -> "x64"
            else -> return@withContext Result.failure(
                IllegalArgumentException("Unsupported ABI for Node.js: $abi")
            )
        }
        val sumsText = fetchText(endpoints.nodeSha256SumsUrl).getOrElse {
            return@withContext Result.failure(it)
        }
        val pattern = Regex("""^([0-9a-fA-F]{64})\s+\*?(node-v(\d+\.\d+\.\d+)-linux-$nodeArch\.tar\.gz)$""")
        val matched = sumsText.lineSequence()
            .mapNotNull { line -> pattern.matchEntire(line.trim()) }
            .firstOrNull()
            ?: return@withContext Result.failure(
                RuntimeManifestException(
                    AgtError.RuntimeManifestInvalid(
                        "Could not locate Node.js Linux ($nodeArch) tarball in official SHASUMS256.txt."
                    )
                )
            )

        val sha256 = matched.groupValues[1].lowercase()
        val filename = matched.groupValues[2]
        val version = matched.groupValues[3]
        val baseDir = endpoints.nodeBaseDirUrl.trimEnd('/')
        val rootFolder = filename.removeSuffix(".tar.gz")

        Result.success(
            UpstreamPackageSpec(
                componentId = "base_toolchains",
                displayName = "Node.js v$version ($nodeArch)",
                version = version,
                archiveName = filename,
                bundle = RuntimeBundleSpec(
                    downloadUrl = "$baseDir/$filename",
                    compressedSizeBytes = 0L,
                    sha256 = sha256,
                    archiveFormat = RuntimeArchiveFormat.TAR_GZ,
                    extractionRoot = rootFolder,
                    requiredPaths = listOf("bin/node", "bin/npm"),
                ),
            )
        )
    }

    suspend fun resolveGitHubCli(abi: String): Result<UpstreamPackageSpec> = withContext(Dispatchers.IO) {
        val ghArch = when (abi.lowercase()) {
            "arm64-v8a" -> "arm64"
            "x86_64" -> "amd64"
            else -> return@withContext Result.failure(
                IllegalArgumentException("Unsupported ABI for GitHub CLI: $abi")
            )
        }
        val releaseJson = resolveGitHubReleaseJson(endpoints.ghReleaseApiUrl).getOrElse {
            return@withContext Result.failure(it)
        }
        val tag = releaseJson.optString("tag_name").trim().ifBlank { "latest" }
        val version = tag.removePrefix("v")
        val assets = releaseJson.optJSONArray("assets") ?: JSONArray()

        val assetRegex = Regex("""^gh_.*_linux_${ghArch}\.tar\.gz$""", RegexOption.IGNORE_CASE)
        val matchedAsset = findAsset(assets) { name -> assetRegex.matches(name) }
            ?: return@withContext Result.failure(
                RuntimeManifestException(
                    AgtError.RuntimeManifestInvalid("GitHub CLI release missing Linux $ghArch archive.")
                )
            )

        val assetName = matchedAsset.optString("name").trim()
        val downloadUrl = matchedAsset.optString("browser_download_url").trim()
        val sizeBytes = matchedAsset.optLong("size", 0L).coerceAtLeast(0L)
        val sha256 = resolveSha256ForGitHubAsset(matchedAsset, assets).getOrElse {
            return@withContext Result.failure(it)
        }

        Result.success(
            UpstreamPackageSpec(
                componentId = "github_cli",
                displayName = "GitHub CLI v$version ($ghArch)",
                version = version,
                archiveName = assetName,
                bundle = RuntimeBundleSpec(
                    downloadUrl = downloadUrl,
                    compressedSizeBytes = sizeBytes,
                    sha256 = sha256,
                    archiveFormat = RuntimeArchiveFormat.TAR_GZ,
                    extractionRoot = assetName.removeSuffix(".tar.gz"),
                    requiredPaths = listOf("bin/gh"),
                ),
            )
        )
    }

    suspend fun resolveOpenCode(abi: String): Result<UpstreamPackageSpec> = withContext(Dispatchers.IO) {
        val archTokens = when (abi.lowercase()) {
            "arm64-v8a" -> listOf("arm64", "aarch64")
            "x86_64" -> listOf("x64", "amd64", "x86_64")
            else -> return@withContext Result.failure(
                IllegalArgumentException("Unsupported ABI for OpenCode: $abi")
            )
        }

        var lastError: Throwable? = null
        for (apiUrl in endpoints.openCodeReleaseApiUrls) {
            val releaseJson = resolveGitHubReleaseJson(apiUrl).getOrElse { err ->
                lastError = err
                continue
            }
            val tag = releaseJson.optString("tag_name").trim().ifBlank { "latest" }
            val version = tag.removePrefix("v")
            val assets = releaseJson.optJSONArray("assets") ?: JSONArray()

            val matchedAsset = selectBestOpenCodeAsset(assets, archTokens) ?: continue

            val assetName = matchedAsset.optString("name").trim()
            val downloadUrl = matchedAsset.optString("browser_download_url").trim()
            val sizeBytes = matchedAsset.optLong("size", 0L).coerceAtLeast(0L)
            val format = when {
                assetName.endsWith(".zip", ignoreCase = true) -> RuntimeArchiveFormat.ZIP
                else -> RuntimeArchiveFormat.TAR_GZ
            }
            val sha256 = resolveSha256ForGitHubAsset(matchedAsset, assets).getOrElse { err ->
                lastError = err
                continue
            }

            return@withContext Result.success(
                UpstreamPackageSpec(
                    componentId = "opencode_agent",
                    displayName = "OpenCode v$version",
                    version = version,
                    archiveName = assetName,
                    bundle = RuntimeBundleSpec(
                        downloadUrl = downloadUrl,
                        compressedSizeBytes = sizeBytes,
                        sha256 = sha256,
                        archiveFormat = format,
                        extractionRoot = "",
                        requiredPaths = listOf("opencode"),
                    ),
                )
            )
        }

        Result.failure(
            lastError ?: RuntimeManifestException(
                AgtError.RuntimeManifestInvalid(
                    "No verified Linux ($abi) OpenCode release archive with SHA-256 digest found."
                )
            )
        )
    }

    private fun selectBestOpenCodeAsset(assets: JSONArray, archTokens: List<String>): JSONObject? {
        val candidates = mutableListOf<Pair<Int, JSONObject>>()
        for (i in 0 until assets.length()) {
            val obj = assets.optJSONObject(i) ?: continue
            val name = obj.optString("name").trim()
            val lower = name.lowercase()
            if (name.isEmpty()) continue
            val excluded = lower.contains("desktop") ||
                lower.contains("appimage") ||
                lower.contains("checksum") ||
                lower.endsWith(".deb") ||
                lower.endsWith(".rpm") ||
                lower.endsWith(".dmg") ||
                lower.endsWith(".exe") ||
                lower.endsWith(".msi") ||
                lower.endsWith(".sig") ||
                lower.endsWith(".asc") ||
                lower.endsWith(".sha256") ||
                lower.endsWith(".sha256sum") ||
                lower.endsWith(".txt") ||
                lower.endsWith(".json")
            if (excluded) continue

            val isLinux = lower.contains("linux")
            val isArch = archTokens.any { lower.contains(it) }
            val isTarGz = lower.endsWith(".tar.gz") || lower.endsWith(".tgz")
            val isZip = lower.endsWith(".zip")
            if (!isLinux || !isArch || (!isTarGz && !isZip)) continue

            var score = 0
            if (isTarGz) score += 20
            if (!lower.contains("musl")) score += 10
            if (!lower.contains("baseline")) score += 4
            if (obj.optString("digest").startsWith("sha256:", ignoreCase = true)) score += 2
            candidates += score to obj
        }
        return candidates.maxByOrNull { it.first }?.second
    }

    private suspend fun resolveGitHubReleaseJson(apiUrl: String): Result<JSONObject> {
        val apiResult = fetchJsonObject(apiUrl)
        if (apiResult.isSuccess) return apiResult

        val ownerRepo = GITHUB_API_LATEST_REGEX.find(apiUrl)?.groupValues?.get(1)
            ?: return apiResult
        return fetchGitHubReleaseViaWeb(ownerRepo).recoverCatching { webErr ->
            throw (apiResult.exceptionOrNull() ?: webErr)
        }
    }

    private suspend fun fetchGitHubReleaseViaWeb(ownerRepo: String): Result<JSONObject> = withContext(Dispatchers.IO) {
        val webBase = endpoints.githubWebBaseUrl.trimEnd('/')
        val latestUrl = "$webBase/$ownerRepo/releases/latest"
        val validation = GatewayUrlValidator.validate(latestUrl, allowQueryAndFragment = true)
        if (validation is GatewayUrlValidation.Invalid) {
            return@withContext Result.failure(
                RuntimeManifestException(AgtError.RuntimeManifestInvalid(validation.reason))
            )
        }
        val normalizedLatest = (validation as GatewayUrlValidation.Valid).normalizedBaseUrl
        val req = Request.Builder()
            .url(normalizedLatest)
            .header("User-Agent", "AGT-Code-Android/1.0")
            .header("Accept", "text/html,application/xhtml+xml")
            .get()
            .build()
        val call = okHttpClient.newCall(req)
        val tag = try {
            call.execute().use { resp ->
                if (!resp.isSuccessful) {
                    return@withContext Result.failure(
                        RuntimeDownloadException(
                            AgtError.NetworkUnavailable("GitHub releases page returned HTTP ${resp.code}.")
                        )
                    )
                }
                val finalUrl = resp.request.url.toString()
                val tagFromUrl = Regex("""/releases/tag/([^/?#]+)""").find(finalUrl)?.groupValues?.get(1)
                if (!tagFromUrl.isNullOrBlank()) {
                    tagFromUrl
                } else {
                    val html = resp.body?.string().orEmpty()
                    Regex("""/$ownerRepo/releases/tag/([^"'?#<\s]+)""")
                        .find(html)
                        ?.groupValues
                        ?.get(1)
                        ?.takeIf { it.isNotBlank() }
                        ?: return@withContext Result.failure(
                            RuntimeManifestException(
                                AgtError.RuntimeManifestInvalid("Unable to determine latest release tag for $ownerRepo.")
                            )
                        )
                }
            }
        } catch (ce: CancellationException) {
            call.cancel()
            throw ce
        } catch (io: IOException) {
            return@withContext Result.failure(
                RuntimeDownloadException(
                    AgtError.NetworkUnavailable(SecretRedactor.redact(io.message ?: "GitHub web request failed."))
                )
            )
        }

        val expandedUrl = "$webBase/$ownerRepo/releases/expanded_assets/$tag"
        val assetsHtml = fetchText(expandedUrl, acceptHeader = "text/html").getOrElse {
            return@withContext Result.failure(it)
        }
        val escapedRepo = Regex.escape(ownerRepo)
        val escapedTag = Regex.escape(tag)
        val linkRegex = Regex("""href="(/$escapedRepo/releases/download/$escapedTag/([^"?#]+))"""")
        val shaRegex = Regex("""sha256:([0-9a-fA-F]{64})""")

        val matches = linkRegex.findAll(assetsHtml).toList()
        if (matches.isEmpty()) {
            return@withContext Result.failure(
                RuntimeManifestException(
                    AgtError.RuntimeManifestInvalid("No downloadable assets listed on GitHub release $ownerRepo@$tag.")
                )
            )
        }

        val assetsArray = JSONArray()
        matches.forEachIndexed { idx, match ->
            val relHref = match.groupValues[1]
            val assetName = match.groupValues[2].substringAfterLast('/')
            val blockEnd = if (idx + 1 < matches.size) matches[idx + 1].range.first else minOf(assetsHtml.length, match.range.last + 1600)
            val snippet = assetsHtml.substring(match.range.first, blockEnd)
            val digestHex = shaRegex.find(snippet)?.groupValues?.get(1)?.lowercase()

            val obj = JSONObject()
                .put("name", assetName)
                .put("browser_download_url", "$webBase$relHref")
                .put("size", 0L)
            if (!digestHex.isNullOrBlank()) {
                obj.put("digest", "sha256:$digestHex")
            }
            assetsArray.put(obj)
        }

        Result.success(
            JSONObject()
                .put("tag_name", tag)
                .put("assets", assetsArray)
        )
    }

    private suspend fun resolveSha256ForGitHubAsset(
        asset: JSONObject,
        allAssets: JSONArray,
    ): Result<String> {
        val assetName = asset.optString("name").trim()
        val digestField = asset.optString("digest").trim()
        if (digestField.startsWith("sha256:", ignoreCase = true)) {
            val hex = digestField.substringAfter(':').trim().lowercase()
            if (SHA256_HEX.matches(hex)) {
                return Result.success(hex)
            }
        }

        // Fallback to checksum asset in the same GitHub release (e.g. *_checksums.txt, SHA256SUMS, <asset>.sha256)
        val sidecar = findAsset(allAssets) { name ->
            name.equals("$assetName.sha256", ignoreCase = true) ||
                name.equals("$assetName.sha256sum", ignoreCase = true)
        }
        val checksumsAsset = sidecar ?: findAsset(allAssets) { name ->
            val lower = name.lowercase()
            lower.contains("checksum") || lower == "sha256sums" || lower == "sha256sums.txt"
        }
        if (checksumsAsset == null) {
            return Result.failure(
                RuntimeManifestException(
                    AgtError.RuntimeVerificationFailed("Release asset '$assetName' has no SHA-256 digest or checksums file.")
                )
            )
        }
        val checksumsUrl = checksumsAsset.optString("browser_download_url").trim()
        val text = fetchText(checksumsUrl).getOrElse { return Result.failure(it) }
        val parsed = parseSha256FromChecksumsText(text, assetName)
            ?: return Result.failure(
                RuntimeManifestException(
                    AgtError.RuntimeVerificationFailed("SHA-256 entry for '$assetName' not found in ${checksumsAsset.optString("name")}.")
                )
            )
        return Result.success(parsed)
    }

    private fun parseSha256FromChecksumsText(text: String, targetFilename: String): String? {
        val lines = text.lineSequence().map { it.trim() }.filter { it.isNotEmpty() }.toList()
        // Format 1: "<64-hex>  <filename>" or "<64-hex> *<filename>"
        val pairRegex = Regex("""^([0-9a-fA-F]{64})\s+\*?(.+)$""")
        for (line in lines) {
            val m = pairRegex.matchEntire(line) ?: continue
            val sha = m.groupValues[1].lowercase()
            val fileToken = m.groupValues[2].trim().substringAfterLast('/')
            if (fileToken.equals(targetFilename, ignoreCase = true)) {
                return sha
            }
        }
        // Format 2: single-hash .sha256 sidecar containing only "<64-hex>"
        if (lines.size == 1 && SHA256_HEX.matches(lines[0].lowercase())) {
            return lines[0].lowercase()
        }
        return null
    }

    private fun findAsset(assets: JSONArray, predicate: (String) -> Boolean): JSONObject? {
        for (i in 0 until assets.length()) {
            val obj = assets.optJSONObject(i) ?: continue
            val name = obj.optString("name").trim()
            if (name.isNotEmpty() && predicate(name)) {
                return obj
            }
        }
        return null
    }

    private suspend fun fetchJsonObject(url: String): Result<JSONObject> {
        val text = fetchText(
            url = url,
            acceptHeader = "application/vnd.github+json",
        ).getOrElse { return Result.failure(it) }
        return runCatching { JSONObject(text) }
    }

    private suspend fun fetchText(
        url: String,
        acceptHeader: String? = null,
    ): Result<String> = withContext(Dispatchers.IO) {
        val validation = GatewayUrlValidator.validate(url, allowQueryAndFragment = true)
        if (validation is GatewayUrlValidation.Invalid) {
            return@withContext Result.failure(
                RuntimeManifestException(AgtError.RuntimeManifestInvalid(validation.reason))
            )
        }
        val normalized = (validation as GatewayUrlValidation.Valid).normalizedBaseUrl
        val reqBuilder = Request.Builder()
            .url(normalized)
            .header("User-Agent", "AGT-Code-Android/1.0")
            .get()
        if (!acceptHeader.isNullOrBlank()) {
            reqBuilder.header("Accept", acceptHeader)
        }
        val call = okHttpClient.newCall(reqBuilder.build())
        try {
            call.execute().use { resp ->
                val body = resp.body?.string().orEmpty()
                if (!resp.isSuccessful) {
                    val safe = SecretRedactor.redact("HTTP ${resp.code}: ${body.take(200)}")
                    return@withContext Result.failure(
                        RuntimeDownloadException(
                            AgtError.NetworkUnavailable(
                                userMessage = "Upstream server returned HTTP ${resp.code} for $normalized.",
                                diagnosticDetail = safe,
                            )
                        )
                    )
                }
                Result.success(body)
            }
        } catch (ce: CancellationException) {
            call.cancel()
            throw ce
        } catch (io: IOException) {
            val safe = SecretRedactor.redact(io.message ?: "Upstream network request failed.")
            Result.failure(
                RuntimeDownloadException(
                    AgtError.NetworkUnavailable(
                        userMessage = safe,
                        diagnosticDetail = safe,
                    )
                )
            )
        }
    }

    private companion object {
        val SHA256_HEX = Regex("^[0-9a-f]{64}$")
        val GITHUB_API_LATEST_REGEX = Regex("""/repos/([^/]+/[^/]+)/releases/latest/?$""")
    }
}
