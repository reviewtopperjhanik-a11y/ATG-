package com.example.domain.model

enum class RepoLanguage(val label: String, val extensions: Set<String>) {
    KOTLIN("Kotlin", setOf("kt", "kts")),
    JAVA("Java", setOf("java")),
    TYPESCRIPT("TypeScript", setOf("ts", "tsx", "mts", "cts")),
    JAVASCRIPT("JavaScript", setOf("js", "jsx", "mjs", "cjs")),
    PYTHON("Python", setOf("py")),
    GO("Go", setOf("go")),
    RUST("Rust", setOf("rs")),
    OTHER("Other", emptySet());

    companion object {
        fun fromPath(relativePath: String): RepoLanguage {
            val ext = relativePath.substringAfterLast('.', "").lowercase()
            return entries.firstOrNull { ext in it.extensions } ?: OTHER
        }
    }
}

enum class SymbolKind(val shortLabel: String) {
    CLASS("class"),
    INTERFACE("interface"),
    OBJECT("object"),
    ENUM("enum"),
    STRUCT("struct"),
    TRAIT("trait"),
    TYPE_ALIAS("type"),
    FUNCTION("fun"),
    METHOD("method"),
    PROPERTY("val"),
}

data class ExtractedSymbol(
    val name: String,
    val kind: SymbolKind,
    val signature: String,
    val lineNumber: Int,
    val isPublicOrExported: Boolean = true,
)

data class FileSymbolIndex(
    val relativePath: String,
    val language: RepoLanguage,
    val lastModifiedMs: Long,
    val sizeBytes: Long,
    val packageOrModule: String? = null,
    val imports: List<String> = emptyList(),
    val symbols: List<ExtractedSymbol> = emptyList(),
    val referencedIdentifiers: Set<String> = emptySet(),
)

data class RankedRepoFile(
    val relativePath: String,
    val language: RepoLanguage,
    val score: Double,
    val incomingReferences: Int,
    val isEntryPoint: Boolean,
    val isRecentlyEdited: Boolean,
    val symbols: List<ExtractedSymbol>,
)

data class RepoMapResult(
    val projectId: String,
    val renderedMap: String,
    val estimatedTokens: Int,
    val tokenBudget: Int,
    val totalFilesIndexed: Int,
    val includedFilesCount: Int,
    val totalSymbolsExtracted: Int,
    val rankedFiles: List<RankedRepoFile>,
    val cacheHit: Boolean,
    val generatedAtEpochMs: Long,
)
