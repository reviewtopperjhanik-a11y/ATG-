package com.example.data.runtime

import com.example.core.common.AgtError
import com.example.core.common.ProjectPathValidator
import com.example.core.security.GatewayUrlValidation
import com.example.core.security.GatewayUrlValidator
import com.example.domain.model.RuntimeArchiveFormat
import com.example.domain.model.RuntimeBundleSpec
import com.example.domain.model.RuntimeComponentSpec
import com.example.domain.model.RuntimeLicenseNotice
import com.example.domain.model.RuntimeManifest
import com.example.domain.model.defaultRuntimeLicenses
import org.json.JSONArray
import org.json.JSONObject

class RuntimeManifestException(val agtError: AgtError) : Exception(agtError.userMessage)

/**
 * Parses and strictly validates an AGT runtime manifest JSON payload.
 * Enforces HTTPS for remote bundle URLs (loopback HTTP allowed for local test servers),
 * 64-char SHA-256 hex digest, and path-traversal-free extraction paths.
 */
object RuntimeManifestParser {

    private val sha256HexRegex = Regex("^[0-9a-f]{64}$")

    fun parse(rawJson: String): Result<RuntimeManifest> = runCatching {
        val root = JSONObject(rawJson)
        val runtimeId = root.optString("runtime_id").trim()
        require(runtimeId.isNotEmpty()) { "Manifest missing runtime_id." }

        val version = root.optString("version").trim()
        require(version.isNotEmpty()) { "Manifest missing version." }
        require(!version.contains("..") && !version.contains('/') && !version.contains('\\')) {
            "Manifest version contains unsafe path characters."
        }

        val releaseChannel = root.optString("release_channel").ifBlank { "stable" }.trim()

        val bundlesObj = root.optJSONObject("bundles")
        val abiBundles = buildMap<String, RuntimeBundleSpec> {
            if (bundlesObj != null) {
                val keys = bundlesObj.keys()
                while (keys.hasNext()) {
                    val abiKey = keys.next().trim()
                    val obj = bundlesObj.optJSONObject(abiKey) ?: continue
                    require(abiKey.isNotEmpty()) { "Manifest bundles contains an empty ABI key." }
                    put(abiKey, parseBundleSpec(obj))
                }
            }
        }

        val bundleObj = root.optJSONObject("bundle")
        val primaryBundle = when {
            bundleObj != null -> parseBundleSpec(bundleObj)
            abiBundles.isNotEmpty() -> abiBundles["arm64-v8a"] ?: abiBundles.values.first()
            else -> throw IllegalArgumentException("Manifest missing bundle or bundles object.")
        }

        val explicitAbis = parseStringList(root.optJSONArray("supported_abis"))
        val supportedAbis = when {
            explicitAbis.isNotEmpty() -> explicitAbis
            abiBundles.isNotEmpty() -> abiBundles.keys.toList()
            else -> listOf("arm64-v8a")
        }

        val minAndroidSdk = root.optInt("min_android_sdk", 26)
        require(minAndroidSdk in 21..100) { "Manifest min_android_sdk out of valid range." }

        val minRequiredFreeBytes = root.optLong("min_required_free_bytes", 128L * 1024L * 1024L)
        require(minRequiredFreeBytes >= 0L) { "Manifest min_required_free_bytes must be non-negative." }

        val components = parseComponents(root.optJSONArray("components"))
        validateComponentGraph(components)
        val licenses = parseLicenses(root.optJSONArray("license_notices"))
            .ifEmpty { defaultRuntimeLicenses() }

        RuntimeManifest(
            runtimeId = runtimeId,
            version = version,
            releaseChannel = releaseChannel,
            supportedAbis = supportedAbis,
            minAndroidSdk = minAndroidSdk,
            minRequiredFreeBytes = minRequiredFreeBytes,
            bundle = primaryBundle,
            components = components,
            licenseNotices = licenses,
            abiBundles = abiBundles,
        )
    }.recoverCatching { err ->
        if (err is RuntimeManifestException) throw err
        throw RuntimeManifestException(
            AgtError.RuntimeManifestInvalid(
                userMessage = err.message ?: "Invalid runtime manifest JSON.",
                diagnosticDetail = err.message,
            )
        )
    }

    private fun parseBundleSpec(bundleObj: JSONObject): RuntimeBundleSpec {
        val downloadUrlRaw = bundleObj.optString("download_url").trim()
        val urlValidation = GatewayUrlValidator.validate(downloadUrlRaw, allowQueryAndFragment = true)
        if (urlValidation is GatewayUrlValidation.Invalid) {
            throw RuntimeManifestException(
                AgtError.RuntimeManifestInvalid(
                    userMessage = "Bundle URL rejected: ${urlValidation.reason}"
                )
            )
        }
        val normalizedUrl = (urlValidation as GatewayUrlValidation.Valid).normalizedBaseUrl

        val compressedSize = bundleObj.optLong("compressed_size_bytes", -1L)
        require(compressedSize >= 0L) { "Manifest bundle compressed_size_bytes must be >= 0." }

        val sha256 = bundleObj.optString("sha256").trim().lowercase()
        require(sha256HexRegex.matches(sha256)) {
            "Manifest bundle sha256 must be a valid 64-character hex digest."
        }

        val formatRaw = bundleObj.optString("archive_format").ifBlank { "tar.gz" }
        val archiveFormat = RuntimeArchiveFormat.parse(formatRaw)
            ?: throw IllegalArgumentException("Unsupported archive_format: $formatRaw")

        val extractionRoot = sanitizeRelativeSubpath(
            bundleObj.optString("extraction_root").trim(),
            allowEmpty = true,
        )
        val requiredPaths = parseStringList(bundleObj.optJSONArray("required_paths"))
            .map { sanitizeRelativeSubpath(it, allowEmpty = false) }
            .ifEmpty { listOf("bin/sh") }

        return RuntimeBundleSpec(
            downloadUrl = normalizedUrl,
            compressedSizeBytes = compressedSize,
            sha256 = sha256,
            archiveFormat = archiveFormat,
            extractionRoot = extractionRoot,
            requiredPaths = requiredPaths,
        )
    }

    private fun sanitizeRelativeSubpath(raw: String, allowEmpty: Boolean): String {
        val cleaned = raw.trim().trim('/')
        if (cleaned.isEmpty()) {
            require(allowEmpty) { "Relative path in runtime manifest cannot be empty." }
            return ""
        }
        require(!raw.startsWith('/') && !cleaned.contains('\\') && !cleaned.contains('\u0000')) {
            "Unsafe path in runtime manifest: $raw"
        }
        val segments = cleaned.split('/')
        require(segments.none { it.isEmpty() || it == "." || it == ".." }) {
            "Path traversal rejected in runtime manifest: $raw"
        }
        return cleaned
    }

    private fun parseStringList(arr: JSONArray?): List<String> {
        if (arr == null) return emptyList()
        return buildList {
            for (i in 0 until arr.length()) {
                val item = arr.optString(i).trim()
                if (item.isNotEmpty()) add(item)
            }
        }
    }

    private fun parseComponents(arr: JSONArray?): List<RuntimeComponentSpec> {
        if (arr == null) return emptyList()
        return buildList {
            for (i in 0 until arr.length()) {
                val obj = arr.optJSONObject(i) ?: continue
                val id = obj.optString("id").trim()
                val name = obj.optString("name").trim()
                if (id.isEmpty() || name.isEmpty()) continue
                require(!id.contains("..") && !id.contains('/') && !id.contains('\\')) {
                    "Component id contains unsafe path characters: $id"
                }
                val reqPaths = parseStringList(obj.optJSONArray("required_paths"))
                    .map { sanitizeRelativeSubpath(it, allowEmpty = false) }
                val dependencies = parseStringList(obj.optJSONArray("dependencies"))
                    .map { dep ->
                        require(!dep.contains("..") && !dep.contains('/') && !dep.contains('\\')) {
                            "Component dependency contains unsafe characters: $dep"
                        }
                        dep
                    }
                    .distinct()
                add(
                    RuntimeComponentSpec(
                        id = id,
                        name = name,
                        description = obj.optString("description").trim(),
                        version = obj.optString("version").ifBlank { "1.0.0" }.trim(),
                        requiredPaths = reqPaths,
                        dependencies = dependencies,
                    )
                )
            }
        }
    }

    private fun validateComponentGraph(components: List<RuntimeComponentSpec>) {
        if (components.isEmpty()) return
        val seenIds = mutableSetOf<String>()
        for (comp in components) {
            require(seenIds.add(comp.id)) {
                "Duplicate component id in runtime manifest: ${comp.id}"
            }
        }
        val implicitBaseIds = setOf("linux_runtime", "base_toolchains")
        val availableIds = seenIds + implicitBaseIds
        val byId = components.associateBy { it.id }

        for (comp in components) {
            for (dep in comp.dependencies) {
                require(dep != comp.id) {
                    "Component '${comp.id}' cannot depend on itself."
                }
                require(dep in availableIds) {
                    "Component '${comp.id}' declares unknown dependency '$dep'."
                }
            }
        }

        // Cycle detection via 3-color DFS (0=unvisited, 1=visiting, 2=visited)
        val visitState = mutableMapOf<String, Int>()
        fun dfs(id: String) {
            when (visitState[id] ?: 0) {
                1 -> throw IllegalArgumentException("Circular dependency detected involving component '$id'.")
                2 -> return
            }
            visitState[id] = 1
            byId[id]?.dependencies?.forEach { dep ->
                if (dep in byId) dfs(dep)
            }
            visitState[id] = 2
        }
        components.forEach { dfs(it.id) }
    }

    private fun parseLicenses(arr: JSONArray?): List<RuntimeLicenseNotice> {
        if (arr == null) return emptyList()
        return buildList {
            for (i in 0 until arr.length()) {
                val obj = arr.optJSONObject(i) ?: continue
                val component = obj.optString("component").trim()
                val license = obj.optString("license").trim()
                if (component.isEmpty() || license.isEmpty()) continue
                add(
                    RuntimeLicenseNotice(
                        component = component,
                        license = license,
                        spdxId = obj.optString("spdx_id").ifBlank { license }.trim(),
                        summary = obj.optString("summary").trim(),
                    )
                )
            }
        }
    }
}
