package com.example.agent.orchestrator

import com.example.domain.model.ExtractedSymbol
import com.example.domain.model.FileSymbolIndex
import com.example.domain.model.ProjectInspection
import com.example.domain.model.RankedRepoFile
import com.example.domain.model.RepoLanguage
import com.example.domain.model.RepoMapResult
import com.example.domain.model.SymbolKind
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.File
import java.util.concurrent.ConcurrentHashMap
import kotlin.math.abs

/**
 * Aider-style Kotlin Repo Map engine:
 * - Indexes multi-language symbols incrementally with mtime/size & dirty-path caching
 * - Builds a cross-file import + symbol reference graph and computes weighted PageRank scores
 * - Boosts entry points and recently edited files (git / checkpoint / task edits)
 * - Renders a compact symbol map strictly within a configurable token budget
 */
class RepoMapEngine(
    private val defaultTokenBudget: Int = DEFAULT_TOKEN_BUDGET,
) {
    private data class CachedProjectRepo(
        val tokenBudget: Int,
        val entryPoints: Set<String>,
        val recentlyEditedPaths: Set<String>,
        val objectiveHint: String,
        val fileIndices: MutableMap<String, FileSymbolIndex>,
        val dirtyPaths: MutableSet<String>,
        var invalidated: Boolean,
        var lastResult: RepoMapResult?,
    )

    private val projectLocks = ConcurrentHashMap<String, Mutex>()
    private val cacheByProject = ConcurrentHashMap<String, CachedProjectRepo>()

    fun invalidate(projectId: String, changedRelativePaths: Collection<String> = emptyList()) {
        val entry = cacheByProject[projectId] ?: return
        if (changedRelativePaths.isEmpty()) {
            entry.invalidated = true
        } else {
            changedRelativePaths.forEach { raw ->
                val norm = raw.replace('\\', '/').trimStart('/')
                if (norm.isNotEmpty()) entry.dirtyPaths += norm
            }
            entry.invalidated = true
        }
    }

    fun clearCache(projectId: String) {
        cacheByProject.remove(projectId)
    }

    suspend fun buildRepoMap(
        projectId: String,
        projectRootPath: String,
        candidateFiles: List<String>? = null,
        entryPoints: Collection<String> = emptyList(),
        recentlyEditedPaths: Collection<String> = emptyList(),
        objectiveHint: String = "",
        tokenBudget: Int = defaultTokenBudget,
        persistedCachePresent: Boolean = true,
    ): RepoMapResult = withContext(Dispatchers.IO) {
        val lock = projectLocks.getOrPut(projectId) { Mutex() }
        lock.withLock {
            val safeBudget = tokenBudget.coerceAtLeast(MIN_TOKEN_BUDGET)
            val root = File(projectRootPath).canonicalFile
            val files = (candidateFiles ?: scanSupportedFiles(root))
                .map { it.replace('\\', '/') }
                .filter { path ->
                    !RepositoryInspector.isSecretOrExcludedFile(path) &&
                        RepoLanguage.fromPath(path) != RepoLanguage.OTHER
                }
                .distinct()

            val normEntries = entryPoints.map { it.replace('\\', '/') }.toSet()
            val normEdited = recentlyEditedPaths.map { it.replace('\\', '/') }.toSet()
            val normHint = objectiveHint.trim()

            val existing = cacheByProject[projectId]
            val currentDiskMeta = files.associateWith { rel ->
                val f = File(root, rel)
                if (f.isFile) f.lastModified() to f.length() else -1L to -1L
            }

            if (existing != null &&
                !existing.invalidated &&
                persistedCachePresent &&
                existing.lastResult != null &&
                existing.tokenBudget == safeBudget &&
                existing.entryPoints == normEntries &&
                existing.recentlyEditedPaths == normEdited &&
                existing.objectiveHint == normHint &&
                existing.fileIndices.keys == files.toSet() &&
                files.all { rel ->
                    val cached = existing.fileIndices[rel] ?: return@all false
                    val meta = currentDiskMeta[rel]
                    meta != null && cached.lastModifiedMs == meta.first && cached.sizeBytes == meta.second
                }
            ) {
                return@withLock existing.lastResult!!.copy(cacheHit = true)
            }

            val workingIndices = existing?.fileIndices?.toMutableMap() ?: mutableMapOf()
            val dirtySet = existing?.dirtyPaths ?: mutableSetOf()

            // Remove deleted files
            val fileSet = files.toSet()
            workingIndices.keys.retainAll(fileSet)

            // Incrementally parse only new, dirty, or changed files
            for (rel in files) {
                val meta = currentDiskMeta[rel] ?: continue
                if (meta.first < 0L) continue
                val cached = workingIndices[rel]
                val needsParse = cached == null ||
                    rel in dirtySet ||
                    cached.lastModifiedMs != meta.first ||
                    cached.sizeBytes != meta.second
                if (needsParse) {
                    val extracted = SymbolExtractor.extractFile(File(root, rel), rel)
                    if (extracted != null) {
                        workingIndices[rel] = extracted
                    }
                }
            }

            val indexedList = files.mapNotNull { workingIndices[it] }
            val ranked = scoreAndRankFiles(
                files = indexedList,
                entryPoints = normEntries,
                recentlyEditedPaths = normEdited,
                objectiveHint = normHint,
            )

            val rendered = renderWithinTokenBudget(
                rankedFiles = ranked,
                tokenBudget = safeBudget,
            )

            val result = RepoMapResult(
                projectId = projectId,
                renderedMap = rendered.text,
                estimatedTokens = estimateTokens(rendered.text),
                tokenBudget = safeBudget,
                totalFilesIndexed = indexedList.size,
                includedFilesCount = rendered.includedFilesCount,
                totalSymbolsExtracted = indexedList.sumOf { it.symbols.size },
                rankedFiles = ranked,
                cacheHit = false,
                generatedAtEpochMs = System.currentTimeMillis(),
            )

            cacheByProject[projectId] = CachedProjectRepo(
                tokenBudget = safeBudget,
                entryPoints = normEntries,
                recentlyEditedPaths = normEdited,
                objectiveHint = normHint,
                fileIndices = workingIndices,
                dirtyPaths = mutableSetOf(),
                invalidated = false,
                lastResult = result,
            )
            result
        }
    }

    suspend fun buildFromInspection(
        inspection: ProjectInspection,
        recentlyEditedPaths: Collection<String> = emptyList(),
        objectiveHint: String = "",
        tokenBudget: Int = defaultTokenBudget,
        persistedCachePresent: Boolean = true,
    ): RepoMapResult = buildRepoMap(
        projectId = inspection.projectId,
        projectRootPath = inspection.projectRootPath,
        candidateFiles = inspection.directoryMap,
        entryPoints = inspection.entryPoints,
        recentlyEditedPaths = recentlyEditedPaths,
        objectiveHint = objectiveHint,
        tokenBudget = tokenBudget,
        persistedCachePresent = persistedCachePresent,
    )

    private fun scanSupportedFiles(root: File): List<String> {
        if (!root.exists() || !root.isDirectory) return emptyList()
        val out = mutableListOf<String>()
        root.walkTopDown()
            .onEnter { dir ->
                val rel = runCatching { dir.canonicalFile.relativeTo(root).path.replace('\\', '/') }.getOrNull()
                if (dir == root) return@onEnter true
                rel != null && !RepositoryInspector.isSecretOrExcludedFile(rel)
            }
            .forEach { f ->
                if (f.isFile && out.size < MAX_INDEXED_FILES) {
                    val rel = runCatching { f.canonicalFile.relativeTo(root).path.replace('\\', '/') }.getOrNull()
                    if (rel != null &&
                        !RepositoryInspector.isSecretOrExcludedFile(rel) &&
                        RepoLanguage.fromPath(rel) != RepoLanguage.OTHER
                    ) {
                        out += rel
                    }
                }
            }
        return out.sorted()
    }

    private fun scoreAndRankFiles(
        files: List<FileSymbolIndex>,
        entryPoints: Set<String>,
        recentlyEditedPaths: Set<String>,
        objectiveHint: String,
    ): List<RankedRepoFile> {
        if (files.isEmpty()) return emptyList()

        val symbolDefiners = HashMap<String, MutableList<Pair<String, ExtractedSymbol>>>()
        val moduleLookup = HashMap<String, MutableSet<String>>()

        for (file in files) {
            for (sym in file.symbols) {
                if (sym.name.length >= 3) {
                    symbolDefiners.getOrPut(sym.name) { mutableListOf() } += file.relativePath to sym
                }
            }
            moduleKeysForFile(file).forEach { key ->
                moduleLookup.getOrPut(key) { mutableSetOf() } += file.relativePath
            }
        }

        val outgoingWeights = HashMap<String, MutableMap<String, Double>>()
        val incomingRefCounts = HashMap<String, Int>()
        val incomingWeightSum = HashMap<String, Double>()

        for (source in files) {
            val targets = outgoingWeights.getOrPut(source.relativePath) { mutableMapOf() }

            // 1. Import edges
            for (imp in source.imports) {
                val matchedTargets = resolveImportTargets(imp, source.relativePath, moduleLookup)
                for (targetPath in matchedTargets) {
                    if (targetPath != source.relativePath) {
                        targets[targetPath] = (targets[targetPath] ?: 0.0) + IMPORT_EDGE_WEIGHT
                    }
                }
            }

            // 2. Cross-file symbol reference edges
            for (ident in source.referencedIdentifiers) {
                val defs = symbolDefiners[ident] ?: continue
                // Skip overly generic symbol names defined across too many files
                if (defs.size > 6) continue
                val multiplicityScale = 1.0 / defs.size.toDouble()
                for ((targetPath, sym) in defs) {
                    if (targetPath == source.relativePath) continue
                    val kindWeight = when (sym.kind) {
                        SymbolKind.CLASS,
                        SymbolKind.INTERFACE,
                        SymbolKind.OBJECT,
                        SymbolKind.STRUCT,
                        SymbolKind.TRAIT,
                        SymbolKind.ENUM,
                        -> TYPE_REF_WEIGHT
                        else -> FUNC_REF_WEIGHT
                    }
                    targets[targetPath] = (targets[targetPath] ?: 0.0) + (kindWeight * multiplicityScale)
                }
            }

            for ((targetPath, w) in targets) {
                incomingRefCounts[targetPath] = (incomingRefCounts[targetPath] ?: 0) + 1
                incomingWeightSum[targetPath] = (incomingWeightSum[targetPath] ?: 0.0) + w
            }
        }

        val hintTokens = objectiveHint
            .lowercase()
            .split(Regex("[^a-z0-9_]+"))
            .filter { it.length >= 3 }
            .toSet()

        val personalWeights = HashMap<String, Double>(files.size)
        for (file in files) {
            val isEntry = isEntryFile(file.relativePath, entryPoints)
            val isEdited = file.relativePath in recentlyEditedPaths
            val hintBoost = if (hintTokens.isEmpty()) 0.0 else {
                val lowerPath = file.relativePath.lowercase()
                val pathMatches = hintTokens.count { it in lowerPath }
                val symMatches = file.symbols.count { sym ->
                    val symLower = sym.name.lowercase()
                    hintTokens.any { tok -> tok in symLower }
                }
                (pathMatches * 1.5 + symMatches * 0.9).coerceAtMost(4.5)
            }
            val baseWeight = 1.0 +
                (if (isEntry) ENTRY_POINT_BOOST else 0.0) +
                (if (isEdited) RECENT_EDIT_BOOST else 0.0) +
                hintBoost
            personalWeights[file.relativePath] = baseWeight
        }

        val personalTotal = personalWeights.values.sum().coerceAtLeast(1.0)
        val normalizedPersonal = personalWeights.mapValues { it.value / personalTotal }

        // Weighted PageRank over dependency graph (damping = 0.85)
        var ranks = files.associate { it.relativePath to (1.0 / files.size.toDouble()) }.toMutableMap()
        repeat(PAGERANK_ITERATIONS) {
            val nextRanks = HashMap<String, Double>(files.size)
            var danglingMass = 0.0
            for (file in files) {
                val out = outgoingWeights[file.relativePath].orEmpty()
                if (out.isEmpty()) {
                    danglingMass += ranks[file.relativePath] ?: 0.0
                }
            }
            for (file in files) {
                nextRanks[file.relativePath] = (1.0 - PAGERANK_DAMPING) * (normalizedPersonal[file.relativePath] ?: 0.0) +
                    PAGERANK_DAMPING * danglingMass * (normalizedPersonal[file.relativePath] ?: 0.0)
            }
            for (source in files) {
                val srcRank = ranks[source.relativePath] ?: 0.0
                val out = outgoingWeights[source.relativePath].orEmpty()
                val outSum = out.values.sum()
                if (outSum > 0.0) {
                    for ((target, weight) in out) {
                        nextRanks[target] = (nextRanks[target] ?: 0.0) +
                            PAGERANK_DAMPING * srcRank * (weight / outSum)
                    }
                }
            }
            var delta = 0.0
            for (file in files) {
                delta += abs((nextRanks[file.relativePath] ?: 0.0) - (ranks[file.relativePath] ?: 0.0))
            }
            ranks = nextRanks
            if (delta < 1e-6) return@repeat
        }

        return files.map { file ->
            val isEntry = isEntryFile(file.relativePath, entryPoints)
            val isEdited = file.relativePath in recentlyEditedPaths
            val prScore = (ranks[file.relativePath] ?: 0.0) * 100.0
            val inWeight = (incomingWeightSum[file.relativePath] ?: 0.0) * 0.75
            val directBoost = (if (isEdited) RECENT_EDIT_BOOST else 0.0) +
                (if (isEntry) ENTRY_POINT_BOOST else 0.0) +
                (file.symbols.count { it.kind != SymbolKind.METHOD }.coerceAtMost(8) * 0.15)
            val totalScore = prScore + inWeight + directBoost

            RankedRepoFile(
                relativePath = file.relativePath,
                language = file.language,
                score = totalScore,
                incomingReferences = incomingRefCounts[file.relativePath] ?: 0,
                isEntryPoint = isEntry,
                isRecentlyEdited = isEdited,
                symbols = prioritizeSymbols(file.symbols),
            )
        }.sortedWith(
            compareByDescending<RankedRepoFile> { it.score }
                .thenByDescending { it.incomingReferences }
                .thenBy { it.relativePath }
        )
    }

    private fun prioritizeSymbols(symbols: List<ExtractedSymbol>): List<ExtractedSymbol> {
        if (symbols.size <= 1) return symbols
        return symbols.sortedWith(
            compareByDescending<ExtractedSymbol> { it.isPublicOrExported }
                .thenBy { symbolPriorityRank(it.kind) }
                .thenBy { it.lineNumber }
        )
    }

    private fun symbolPriorityRank(kind: SymbolKind): Int = when (kind) {
        SymbolKind.INTERFACE, SymbolKind.TRAIT -> 0
        SymbolKind.CLASS, SymbolKind.STRUCT, SymbolKind.OBJECT -> 1
        SymbolKind.ENUM, SymbolKind.TYPE_ALIAS -> 2
        SymbolKind.FUNCTION -> 3
        SymbolKind.METHOD -> 4
        SymbolKind.PROPERTY -> 5
    }

    private data class RenderedResult(
        val text: String,
        val includedFilesCount: Int,
    )

    private fun renderWithinTokenBudget(
        rankedFiles: List<RankedRepoFile>,
        tokenBudget: Int,
    ): RenderedResult {
        if (rankedFiles.isEmpty()) {
            val emptyText = "# Repo Map (0 files, ~4 tokens)"
            return RenderedResult(
                text = trimToTokenBudget(emptyText, tokenBudget),
                includedFilesCount = 0,
            )
        }

        // Reserve budget for summary header line
        val reservedHeaderBudget = 24
        val bodyBudget = (tokenBudget - reservedHeaderBudget).coerceAtLeast(32)
        val sections = mutableListOf<String>()
        var bodyTokensUsed = 0
        var includedFiles = 0

        for (file in rankedFiles) {
            val headerTag = buildList {
                if (file.isRecentlyEdited) add("edited")
                if (file.isEntryPoint) add("entry")
                if (file.incomingReferences > 0) add("refs=${file.incomingReferences}")
            }.joinToString(", ")
            val fileHeader = if (headerTag.isNotEmpty()) {
                "${file.relativePath} [$headerTag]"
            } else {
                file.relativePath
            }

            val symbolLines = file.symbols.map { sym ->
                val indent = if (sym.kind == SymbolKind.METHOD) "    " else "  "
                "${indent}L${sym.lineNumber}: ${sym.signature}"
            }

            val fullBlock = buildString {
                append(fileHeader)
                symbolLines.forEach {
                    append('\n')
                    append(it)
                }
            }
            val fullTokens = estimateTokens(fullBlock) + 1
            if (bodyTokensUsed + fullTokens <= bodyBudget) {
                sections += fullBlock
                bodyTokensUsed += fullTokens
                includedFiles++
                continue
            }

            // Try fitting a partial symbol preview for this high-priority file
            val remaining = bodyBudget - bodyTokensUsed
            val headerTokens = estimateTokens(fileHeader) + 2
            if (remaining <= headerTokens) {
                break
            }

            val partialLines = mutableListOf(fileHeader)
            var partialTokens = headerTokens
            var keptSymbols = 0
            for (line in symbolLines) {
                val lineTok = estimateTokens(line) + 1
                // Leave small room for ellipsis note if symbols remain
                val ellipsisReserve = if (keptSymbols + 1 < symbolLines.size) 7 else 0
                if (partialTokens + lineTok + ellipsisReserve <= remaining) {
                    partialLines += line
                    partialTokens += lineTok
                    keptSymbols++
                } else {
                    break
                }
            }
            if (keptSymbols < symbolLines.size && keptSymbols > 0) {
                val omittedSym = symbolLines.size - keptSymbols
                val omittedLine = "  … (+$omittedSym symbols)"
                if (partialTokens + estimateTokens(omittedLine) <= remaining) {
                    partialLines += omittedLine
                }
            }
            if (keptSymbols > 0 || symbolLines.isEmpty()) {
                val partialBlock = partialLines.joinToString("\n")
                sections += partialBlock
                bodyTokensUsed += estimateTokens(partialBlock) + 1
                includedFiles++
            }
            break
        }

        val omittedFiles = (rankedFiles.size - includedFiles).coerceAtLeast(0)
        val totalSymbols = rankedFiles.sumOf { it.symbols.size }
        val body = buildString {
            append(sections.joinToString("\n\n"))
            if (omittedFiles > 0) {
                val omittedNote = "\n\n… (+$omittedFiles lower-ranked file(s) omitted within $tokenBudget token budget)"
                if (estimateTokens(this.toString() + omittedNote) + reservedHeaderBudget <= tokenBudget) {
                    append(omittedNote)
                }
            }
        }

        val headerPrefix = "# Repo Map ($includedFiles/${rankedFiles.size} files, $totalSymbols symbols)"
        val combined = if (body.isNotEmpty()) "$headerPrefix\n$body" else headerPrefix
        return RenderedResult(
            text = trimToTokenBudget(combined, tokenBudget),
            includedFilesCount = includedFiles,
        )
    }

    private fun trimToTokenBudget(text: String, tokenBudget: Int): String {
        if (estimateTokens(text) <= tokenBudget) return text
        val maxChars = (tokenBudget * CHARS_PER_TOKEN).coerceAtLeast(16)
        return text.take(maxChars).substringBeforeLast('\n').ifBlank { text.take(maxChars) }
    }

    private fun isEntryFile(relativePath: String, entryPoints: Set<String>): Boolean {
        if (relativePath in entryPoints) return true
        val name = relativePath.substringAfterLast('/')
        return name in ENTRY_FILENAMES
    }

    private fun moduleKeysForFile(file: FileSymbolIndex): List<String> {
        val norm = file.relativePath.replace('\\', '/')
        val withoutExt = norm.substringBeforeLast('.')
        val fileStem = withoutExt.substringAfterLast('/')
        return buildList {
            add(withoutExt.lowercase())
            add(fileStem.lowercase())
            file.packageOrModule?.takeIf { it.isNotBlank() }?.let { pkg ->
                add(pkg.lowercase())
                add("$pkg.$fileStem".lowercase())
            }
            file.symbols.forEach { sym ->
                file.packageOrModule?.takeIf { it.isNotBlank() }?.let { pkg ->
                    add("$pkg.${sym.name}".lowercase())
                }
            }
        }.distinct()
    }

    private fun resolveImportTargets(
        rawImport: String,
        sourcePath: String,
        moduleLookup: Map<String, Set<String>>,
    ): Set<String> {
        val cleaned = rawImport.trim().removeSuffix(".*").removeSuffix("::*")
        if (cleaned.isEmpty()) return emptySet()

        val candidates = LinkedHashSet<String>()
        val lower = cleaned.lowercase()
        candidates += lower

        // Handle relative TS/JS imports: './foo/bar' or '../utils/helper'
        if (cleaned.startsWith(".")) {
            val parentDir = sourcePath.substringBeforeLast('/', "")
            val combined = if (parentDir.isEmpty()) cleaned else "$parentDir/$cleaned"
            val normalizedSegments = mutableListOf<String>()
            combined.split('/').forEach { seg ->
                when (seg) {
                    "", "." -> Unit
                    ".." -> if (normalizedSegments.isNotEmpty()) normalizedSegments.removeAt(normalizedSegments.lastIndex)
                    else -> normalizedSegments += seg
                }
            }
            val resolvedNoExt = normalizedSegments.joinToString("/")
                .replace(Regex("\\.(ts|tsx|js|jsx|mjs|cjs)$"), "")
                .lowercase()
            candidates += resolvedNoExt
            candidates += "$resolvedNoExt/index"
        }

        // Last segment & slash/colon conversions for Python/Rust/Go/Kotlin
        val slashToDot = cleaned.replace("::", ".").replace('/', '.').lowercase()
        val dotToSlash = cleaned.replace("::", "/").replace('.', '/').lowercase()
        candidates += slashToDot
        candidates += dotToSlash
        candidates += cleaned.substringAfterLast('.').substringAfterLast('/').substringAfterLast("::").lowercase()

        val matched = LinkedHashSet<String>()
        for (key in candidates) {
            moduleLookup[key]?.let { matched.addAll(it) }
        }
        return matched
    }

    companion object {
        const val DEFAULT_TOKEN_BUDGET = 2000
        const val MIN_TOKEN_BUDGET = 80
        private const val CHARS_PER_TOKEN = 4
        private const val MAX_INDEXED_FILES = 250
        private const val PAGERANK_ITERATIONS = 12
        private const val PAGERANK_DAMPING = 0.85
        private const val IMPORT_EDGE_WEIGHT = 3.0
        private const val TYPE_REF_WEIGHT = 1.5
        private const val FUNC_REF_WEIGHT = 0.8
        private const val ENTRY_POINT_BOOST = 2.5
        private const val RECENT_EDIT_BOOST = 4.0

        private val ENTRY_FILENAMES = setOf(
            "MainActivity.kt",
            "App.tsx",
            "App.kt",
            "main.py",
            "app.py",
            "index.ts",
            "index.js",
            "server.ts",
            "server.js",
            "main.go",
            "main.rs",
            "lib.rs",
        )

        fun estimateTokens(text: String): Int {
            if (text.isEmpty()) return 0
            return (text.length + CHARS_PER_TOKEN - 1) / CHARS_PER_TOKEN
        }
    }
}
