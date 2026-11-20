package com.example.agent.orchestrator

import com.example.domain.model.ExtractedSymbol
import com.example.domain.model.FileSymbolIndex
import com.example.domain.model.RepoLanguage
import com.example.domain.model.SymbolKind
import java.io.File

/**
 * Multi-language lightweight symbol & dependency extractor (Kotlin, Java, TypeScript/JavaScript,
 * Python, Go, Rust). Runs purely in Kotlin with no native tree-sitter binaries required.
 */
object SymbolExtractor {

    private const val MAX_FILE_BYTES = 512 * 1024L
    private const val MAX_SIGNATURE_LEN = 96

    fun extractFile(
        file: File,
        relativePath: String,
    ): FileSymbolIndex? {
        if (!file.isFile || file.length() > MAX_FILE_BYTES) return null
        val language = RepoLanguage.fromPath(relativePath)
        if (language == RepoLanguage.OTHER) return null
        val content = runCatching { file.readText() }.getOrNull() ?: return null
        return extractSource(
            relativePath = relativePath,
            content = content,
            language = language,
            lastModifiedMs = file.lastModified(),
            sizeBytes = file.length(),
        )
    }

    fun extractSource(
        relativePath: String,
        content: String,
        language: RepoLanguage = RepoLanguage.fromPath(relativePath),
        lastModifiedMs: Long = 0L,
        sizeBytes: Long = content.length.toLong(),
    ): FileSymbolIndex {
        if (language == RepoLanguage.OTHER) {
            return FileSymbolIndex(
                relativePath = relativePath,
                language = language,
                lastModifiedMs = lastModifiedMs,
                sizeBytes = sizeBytes,
            )
        }

        val cleaned = stripCommentsPreservingLines(content, isPython = (language == RepoLanguage.PYTHON))
        val lines = cleaned.lines()

        var packageOrModule: String? = null
        val imports = mutableListOf<String>()
        val symbols = mutableListOf<ExtractedSymbol>()

        when (language) {
            RepoLanguage.KOTLIN -> parseKotlin(lines, imports, symbols) { packageOrModule = it }
            RepoLanguage.JAVA -> parseJava(lines, imports, symbols) { packageOrModule = it }
            RepoLanguage.TYPESCRIPT, RepoLanguage.JAVASCRIPT -> parseTsJs(lines, imports, symbols)
            RepoLanguage.PYTHON -> parsePython(lines, imports, symbols)
            RepoLanguage.GO -> parseGo(lines, imports, symbols) { packageOrModule = it }
            RepoLanguage.RUST -> parseRust(lines, imports, symbols)
            RepoLanguage.OTHER -> Unit
        }

        val declaredNames = symbols.map { it.name }.toSet()
        val refs = extractIdentifierTokens(cleaned)
            .asSequence()
            .filter { it.length >= 3 && it !in COMMON_NOISE_IDENTIFIERS && it !in declaredNames }
            .toSet()

        return FileSymbolIndex(
            relativePath = relativePath,
            language = language,
            lastModifiedMs = lastModifiedMs,
            sizeBytes = sizeBytes,
            packageOrModule = packageOrModule,
            imports = imports.distinct(),
            symbols = symbols.distinctBy { "${it.kind}:${it.name}:${it.lineNumber}" },
            referencedIdentifiers = refs,
        )
    }

    private fun parseKotlin(
        lines: List<String>,
        imports: MutableList<String>,
        symbols: MutableList<ExtractedSymbol>,
        onPackage: (String) -> Unit,
    ) {
        lines.forEachIndexed { idx, rawLine ->
            val lineNo = idx + 1
            val trimmed = rawLine.trim()
            if (trimmed.isEmpty()) return@forEachIndexed

            KT_PACKAGE_REGEX.find(trimmed)?.let {
                onPackage(it.groupValues[1])
                return@forEachIndexed
            }
            KT_IMPORT_REGEX.find(trimmed)?.let {
                imports += it.groupValues[1]
                return@forEachIndexed
            }
            KT_TYPE_REGEX.find(trimmed)?.let { m ->
                val rawKind = m.groupValues[1].lowercase()
                val name = m.groupValues[2]
                val kind = when {
                    rawKind.contains("interface") -> SymbolKind.INTERFACE
                    rawKind.contains("enum") -> SymbolKind.ENUM
                    rawKind.contains("object") -> SymbolKind.OBJECT
                    rawKind.contains("typealias") -> SymbolKind.TYPE_ALIAS
                    else -> SymbolKind.CLASS
                }
                symbols += ExtractedSymbol(
                    name = name,
                    kind = kind,
                    signature = compactSignature(trimmed),
                    lineNumber = lineNo,
                    isPublicOrExported = !trimmed.startsWith("private ") && !trimmed.startsWith("internal "),
                )
                return@forEachIndexed
            }
            KT_FUN_REGEX.find(trimmed)?.let { m ->
                val fullName = m.groupValues[1]
                val simpleName = fullName.substringAfterLast('.')
                val isIndented = rawLine.firstOrNull()?.isWhitespace() == true
                symbols += ExtractedSymbol(
                    name = simpleName,
                    kind = if (isIndented) SymbolKind.METHOD else SymbolKind.FUNCTION,
                    signature = compactSignature(trimmed),
                    lineNumber = lineNo,
                    isPublicOrExported = !trimmed.startsWith("private "),
                )
            }
        }
    }

    private fun parseJava(
        lines: List<String>,
        imports: MutableList<String>,
        symbols: MutableList<ExtractedSymbol>,
        onPackage: (String) -> Unit,
    ) {
        lines.forEachIndexed { idx, rawLine ->
            val lineNo = idx + 1
            val trimmed = rawLine.trim()
            if (trimmed.isEmpty() || trimmed.startsWith("@")) return@forEachIndexed

            JAVA_PACKAGE_REGEX.find(trimmed)?.let {
                onPackage(it.groupValues[1])
                return@forEachIndexed
            }
            JAVA_IMPORT_REGEX.find(trimmed)?.let {
                imports += it.groupValues[1]
                return@forEachIndexed
            }
            JAVA_TYPE_REGEX.find(trimmed)?.let { m ->
                val rawKind = m.groupValues[1].lowercase()
                val name = m.groupValues[2]
                val kind = when {
                    rawKind.contains("interface") -> SymbolKind.INTERFACE
                    rawKind.contains("enum") -> SymbolKind.ENUM
                    else -> SymbolKind.CLASS
                }
                symbols += ExtractedSymbol(
                    name = name,
                    kind = kind,
                    signature = compactSignature(trimmed),
                    lineNumber = lineNo,
                    isPublicOrExported = trimmed.contains("public ") || !trimmed.startsWith("private "),
                )
                return@forEachIndexed
            }
            JAVA_METHOD_REGEX.find(trimmed)?.let { m ->
                val methodName = m.groupValues[1]
                if (methodName !in CONTROL_FLOW_KEYWORDS) {
                    symbols += ExtractedSymbol(
                        name = methodName,
                        kind = SymbolKind.METHOD,
                        signature = compactSignature(trimmed),
                        lineNumber = lineNo,
                        isPublicOrExported = !trimmed.startsWith("private "),
                    )
                }
            }
        }
    }

    private fun parseTsJs(
        lines: List<String>,
        imports: MutableList<String>,
        symbols: MutableList<ExtractedSymbol>,
    ) {
        lines.forEachIndexed { idx, rawLine ->
            val lineNo = idx + 1
            val trimmed = rawLine.trim()
            if (trimmed.isEmpty()) return@forEachIndexed

            TS_IMPORT_FROM_REGEX.find(trimmed)?.let {
                imports += it.groupValues[1]
                return@forEachIndexed
            }
            TS_REQUIRE_REGEX.find(trimmed)?.let { m ->
                imports += m.groupValues[1]
            }
            TS_TYPE_REGEX.find(trimmed)?.let { m ->
                val rawKind = m.groupValues[1].lowercase()
                val name = m.groupValues[2]
                val kind = when (rawKind) {
                    "interface" -> SymbolKind.INTERFACE
                    "enum" -> SymbolKind.ENUM
                    "type" -> SymbolKind.TYPE_ALIAS
                    else -> SymbolKind.CLASS
                }
                symbols += ExtractedSymbol(
                    name = name,
                    kind = kind,
                    signature = compactSignature(trimmed),
                    lineNumber = lineNo,
                    isPublicOrExported = trimmed.startsWith("export "),
                )
                return@forEachIndexed
            }
            TS_FUNCTION_REGEX.find(trimmed)?.let { m ->
                val name = m.groupValues[1]
                symbols += ExtractedSymbol(
                    name = name,
                    kind = SymbolKind.FUNCTION,
                    signature = compactSignature(trimmed),
                    lineNumber = lineNo,
                    isPublicOrExported = trimmed.startsWith("export "),
                )
                return@forEachIndexed
            }
            TS_ARROW_VAR_REGEX.find(trimmed)?.let { m ->
                val name = m.groupValues[1]
                symbols += ExtractedSymbol(
                    name = name,
                    kind = SymbolKind.FUNCTION,
                    signature = compactSignature(trimmed),
                    lineNumber = lineNo,
                    isPublicOrExported = trimmed.startsWith("export "),
                )
                return@forEachIndexed
            }
            if (rawLine.firstOrNull()?.isWhitespace() == true) {
                TS_METHOD_REGEX.find(trimmed)?.let { m ->
                    val name = m.groupValues[1]
                    if (name !in CONTROL_FLOW_KEYWORDS) {
                        symbols += ExtractedSymbol(
                            name = name,
                            kind = SymbolKind.METHOD,
                            signature = compactSignature(trimmed),
                            lineNumber = lineNo,
                            isPublicOrExported = !trimmed.startsWith("private "),
                        )
                    }
                }
            }
        }
    }

    private fun parsePython(
        lines: List<String>,
        imports: MutableList<String>,
        symbols: MutableList<ExtractedSymbol>,
    ) {
        lines.forEachIndexed { idx, rawLine ->
            val lineNo = idx + 1
            val trimmed = rawLine.trim()
            if (trimmed.isEmpty()) return@forEachIndexed

            PY_FROM_IMPORT_REGEX.find(trimmed)?.let { m ->
                val module = m.groupValues[1].trim()
                val names = m.groupValues[2].split(',').mapNotNull {
                    it.trim().substringBefore(" as ").trim().takeIf(String::isNotBlank)
                }
                imports += module
                imports += names
                return@forEachIndexed
            }
            PY_IMPORT_REGEX.find(trimmed)?.let { m ->
                m.groupValues[1].split(',').forEach { part ->
                    val mod = part.trim().substringBefore(" as ").trim()
                    if (mod.isNotBlank()) imports += mod
                }
                return@forEachIndexed
            }
            PY_CLASS_REGEX.find(trimmed)?.let { m ->
                val name = m.groupValues[1]
                symbols += ExtractedSymbol(
                    name = name,
                    kind = SymbolKind.CLASS,
                    signature = compactSignature(trimmed),
                    lineNumber = lineNo,
                    isPublicOrExported = !name.startsWith("_"),
                )
                return@forEachIndexed
            }
            PY_DEF_REGEX.find(trimmed)?.let { m ->
                val name = m.groupValues[1]
                val isIndented = rawLine.firstOrNull()?.isWhitespace() == true
                symbols += ExtractedSymbol(
                    name = name,
                    kind = if (isIndented) SymbolKind.METHOD else SymbolKind.FUNCTION,
                    signature = compactSignature(trimmed),
                    lineNumber = lineNo,
                    isPublicOrExported = !name.startsWith("_") || name == "__init__",
                )
            }
        }
    }

    private fun parseGo(
        lines: List<String>,
        imports: MutableList<String>,
        symbols: MutableList<ExtractedSymbol>,
        onPackage: (String) -> Unit,
    ) {
        var inImportBlock = false
        lines.forEachIndexed { idx, rawLine ->
            val lineNo = idx + 1
            val trimmed = rawLine.trim()
            if (trimmed.isEmpty()) return@forEachIndexed

            if (inImportBlock) {
                if (trimmed.startsWith(")")) {
                    inImportBlock = false
                } else {
                    GO_QUOTED_IMPORT_REGEX.find(trimmed)?.let { imports += it.groupValues[1] }
                }
                return@forEachIndexed
            }
            if (trimmed.startsWith("import (")) {
                inImportBlock = true
                return@forEachIndexed
            }
            GO_PACKAGE_REGEX.find(trimmed)?.let {
                onPackage(it.groupValues[1])
                return@forEachIndexed
            }
            GO_SINGLE_IMPORT_REGEX.find(trimmed)?.let {
                imports += it.groupValues[1]
                return@forEachIndexed
            }
            GO_TYPE_REGEX.find(trimmed)?.let { m ->
                val name = m.groupValues[1]
                val rawKind = m.groupValues[2].lowercase()
                val kind = when (rawKind) {
                    "interface" -> SymbolKind.INTERFACE
                    "struct" -> SymbolKind.STRUCT
                    else -> SymbolKind.TYPE_ALIAS
                }
                symbols += ExtractedSymbol(
                    name = name,
                    kind = kind,
                    signature = compactSignature(trimmed),
                    lineNumber = lineNo,
                    isPublicOrExported = name.firstOrNull()?.isUpperCase() == true,
                )
                return@forEachIndexed
            }
            GO_METHOD_REGEX.find(trimmed)?.let { m ->
                val name = m.groupValues[1]
                symbols += ExtractedSymbol(
                    name = name,
                    kind = SymbolKind.METHOD,
                    signature = compactSignature(trimmed),
                    lineNumber = lineNo,
                    isPublicOrExported = name.firstOrNull()?.isUpperCase() == true,
                )
                return@forEachIndexed
            }
            GO_FUNC_REGEX.find(trimmed)?.let { m ->
                val name = m.groupValues[1]
                symbols += ExtractedSymbol(
                    name = name,
                    kind = SymbolKind.FUNCTION,
                    signature = compactSignature(trimmed),
                    lineNumber = lineNo,
                    isPublicOrExported = name.firstOrNull()?.isUpperCase() == true,
                )
            }
        }
    }

    private fun parseRust(
        lines: List<String>,
        imports: MutableList<String>,
        symbols: MutableList<ExtractedSymbol>,
    ) {
        var implScopeDepth = 0
        lines.forEachIndexed { idx, rawLine ->
            val lineNo = idx + 1
            val trimmed = rawLine.trim()
            if (trimmed.isEmpty()) return@forEachIndexed

            RUST_USE_REGEX.find(trimmed)?.let {
                imports += it.groupValues[1]
                return@forEachIndexed
            }
            RUST_MOD_REGEX.find(trimmed)?.let {
                imports += it.groupValues[1]
            }
            RUST_TYPE_REGEX.find(trimmed)?.let { m ->
                val rawKind = m.groupValues[1].lowercase()
                val name = m.groupValues[2]
                val kind = when (rawKind) {
                    "struct" -> SymbolKind.STRUCT
                    "enum" -> SymbolKind.ENUM
                    "trait" -> SymbolKind.TRAIT
                    else -> SymbolKind.TYPE_ALIAS
                }
                symbols += ExtractedSymbol(
                    name = name,
                    kind = kind,
                    signature = compactSignature(trimmed),
                    lineNumber = lineNo,
                    isPublicOrExported = trimmed.startsWith("pub"),
                )
            }
            if (RUST_IMPL_REGEX.containsMatchIn(trimmed)) {
                implScopeDepth += trimmed.count { it == '{' } - trimmed.count { it == '}' }
                if (implScopeDepth < 0) implScopeDepth = 0
                return@forEachIndexed
            }
            RUST_FN_REGEX.find(trimmed)?.let { m ->
                val name = m.groupValues[1]
                val isMethod = implScopeDepth > 0 || rawLine.firstOrNull()?.isWhitespace() == true
                symbols += ExtractedSymbol(
                    name = name,
                    kind = if (isMethod) SymbolKind.METHOD else SymbolKind.FUNCTION,
                    signature = compactSignature(trimmed),
                    lineNumber = lineNo,
                    isPublicOrExported = trimmed.startsWith("pub"),
                )
            }
            if (implScopeDepth > 0) {
                implScopeDepth += trimmed.count { it == '{' } - trimmed.count { it == '}' }
                if (implScopeDepth < 0) implScopeDepth = 0
            }
        }
    }

    /**
     * Strips line/block comments while preserving line breaks so 1-based line numbers remain accurate.
     */
    private fun stripCommentsPreservingLines(source: String, isPython: Boolean): String {
        val sb = StringBuilder(source.length)
        var i = 0
        var inSingleQuote = false
        var inDoubleQuote = false
        var inBlockComment = false
        while (i < source.length) {
            val ch = source[i]
            val next = source.getOrNull(i + 1)

            if (inBlockComment) {
                if (ch == '*' && next == '/') {
                    inBlockComment = false
                    i += 2
                } else {
                    if (ch == '\n') sb.append('\n')
                    i++
                }
                continue
            }

            if (inSingleQuote) {
                sb.append(ch)
                if (ch == '\\' && next != null) {
                    sb.append(next)
                    i += 2
                    continue
                }
                if (ch == '\'') inSingleQuote = false
                i++
                continue
            }

            if (inDoubleQuote) {
                sb.append(ch)
                if (ch == '\\' && next != null) {
                    sb.append(next)
                    i += 2
                    continue
                }
                if (ch == '"') inDoubleQuote = false
                i++
                continue
            }

            if (!isPython && ch == '/' && next == '*') {
                inBlockComment = true
                i += 2
                continue
            }
            if (!isPython && ch == '/' && next == '/') {
                while (i < source.length && source[i] != '\n') i++
                continue
            }
            if (isPython && ch == '#') {
                while (i < source.length && source[i] != '\n') i++
                continue
            }
            if (ch == '\'') inSingleQuote = true
            if (ch == '"') inDoubleQuote = true
            sb.append(ch)
            i++
        }
        return sb.toString()
    }

    private fun extractIdentifierTokens(source: String): Set<String> {
        val tokens = LinkedHashSet<String>()
        IDENT_REGEX.findAll(source).forEach { match ->
            tokens += match.value
        }
        return tokens
    }

    private fun compactSignature(rawLine: String): String {
        val cleaned = rawLine
            .replace(Regex("\\s+"), " ")
            .removeSuffix("{")
            .removeSuffix(":")
            .trim()
        return if (cleaned.length <= MAX_SIGNATURE_LEN) {
            cleaned
        } else {
            cleaned.take(MAX_SIGNATURE_LEN - 1).trimEnd() + "…"
        }
    }

    private val KT_PACKAGE_REGEX = Regex("""^package\s+([A-Za-z0-9_.]+)""")
    private val KT_IMPORT_REGEX = Regex("""^import\s+([A-Za-z0-9_.*]+)""")
    private val KT_TYPE_REGEX = Regex(
        """^(?:(?:public|private|internal|protected|open|abstract|final|sealed|data|value|inline|inner|enum|annotation|expect|actual)\s+)*(class|interface|enum\s+class|object|typealias)\s+([A-Za-z_][A-Za-z0-9_]*)"""
    )
    private val KT_FUN_REGEX = Regex(
        """^(?:(?:public|private|internal|protected|override|open|final|abstract|suspend|inline|tailrec|operator|infix|external|expect|actual)\s+)*fun\s+(?:<[^>]+>\s+)?([A-Za-z_][A-Za-z0-9_.]*)\s*\("""
    )

    private val JAVA_PACKAGE_REGEX = Regex("""^package\s+([A-Za-z0-9_.]+)\s*;""")
    private val JAVA_IMPORT_REGEX = Regex("""^import\s+(?:static\s+)?([A-Za-z0-9_.*]+)\s*;""")
    private val JAVA_TYPE_REGEX = Regex(
        """^(?:(?:public|private|protected|static|final|abstract|sealed|non-sealed)\s+)*(class|interface|enum|record|@interface)\s+([A-Za-z_][A-Za-z0-9_]*)"""
    )
    private val JAVA_METHOD_REGEX = Regex(
        """^(?:(?:public|private|protected|static|final|abstract|synchronized|native|default)\s+)+(?:<[^>]+>\s+)?(?:[A-Za-z_][\w$.<>\[\],\s?]*\s+)?([A-Za-z_][A-Za-z0-9_]*)\s*\("""
    )

    private val TS_IMPORT_FROM_REGEX = Regex("""^import\s+.*?\s+from\s+['"]([^'"]+)['"]""")
    private val TS_REQUIRE_REGEX = Regex("""require\(\s*['"]([^'"]+)['"]\s*\)""")
    private val TS_TYPE_REGEX = Regex(
        """^(?:export\s+(?:default\s+)?)?(?:declare\s+)?(?:abstract\s+)?(class|interface|type|enum)\s+([A-Za-z_$][A-Za-z0-9_$]*)"""
    )
    private val TS_FUNCTION_REGEX = Regex(
        """^(?:export\s+(?:default\s+)?)?(?:declare\s+)?(?:async\s+)?function\*?\s+([A-Za-z_$][A-Za-z0-9_$]*)\s*(?:<[^>]+>)?\s*\("""
    )
    private val TS_ARROW_VAR_REGEX = Regex(
        """^(?:export\s+)?(?:const|let|var)\s+([A-Za-z_$][A-Za-z0-9_$]*)\s*(?::[^=]+)?=\s*(?:async\s+)?(?:\([^)]*\)|[A-Za-z_$][\w$]*)\s*=>"""
    )
    private val TS_METHOD_REGEX = Regex(
        """^(?:(?:public|private|protected|static|async|override|readonly|get|set)\s+)*([A-Za-z_$][A-Za-z0-9_$]*)\s*(?:<[^>]+>)?\s*\([^)]*\)\s*(?::\s*[^\{=]+)?\s*\{"""
    )

    private val PY_FROM_IMPORT_REGEX = Regex("""^from\s+([A-Za-z0-9_.]+)\s+import\s+(.+)$""")
    private val PY_IMPORT_REGEX = Regex("""^import\s+(.+)$""")
    private val PY_CLASS_REGEX = Regex("""^class\s+([A-Za-z_][A-Za-z0-9_]*)\b""")
    private val PY_DEF_REGEX = Regex("""^(?:async\s+)?def\s+([A-Za-z_][A-Za-z0-9_]*)\s*\(""")

    private val GO_PACKAGE_REGEX = Regex("""^package\s+([A-Za-z_][A-Za-z0-9_]*)""")
    private val GO_SINGLE_IMPORT_REGEX = Regex("""^import\s+(?:[A-Za-z_.]\w*\s+)?["']([^"']+)["']""")
    private val GO_QUOTED_IMPORT_REGEX = Regex("""["']([^"']+)["']""")
    private val GO_TYPE_REGEX = Regex("""^type\s+([A-Za-z_][A-Za-z0-9_]*)\s+(struct|interface|\S+)""")
    private val GO_METHOD_REGEX = Regex("""^func\s*\([^)]+\)\s*([A-Za-z_][A-Za-z0-9_]*)\s*\(""")
    private val GO_FUNC_REGEX = Regex("""^func\s+([A-Za-z_][A-Za-z0-9_]*)\s*(?:\[[^\]]+\])?\s*\(""")

    private val RUST_USE_REGEX = Regex("""^(?:pub\s+)?use\s+([^;]+);""")
    private val RUST_MOD_REGEX = Regex("""^(?:pub\s+)?mod\s+([A-Za-z_][A-Za-z0-9_]*)""")
    private val RUST_TYPE_REGEX = Regex(
        """^(?:pub(?:\([^)]*\))?\s+)?(struct|enum|trait|type)\s+([A-Za-z_][A-Za-z0-9_]*)"""
    )
    private val RUST_IMPL_REGEX = Regex("""^impl\b""")
    private val RUST_FN_REGEX = Regex(
        """^(?:pub(?:\([^)]*\))?\s+)?(?:async\s+)?(?:unsafe\s+)?(?:const\s+)?fn\s+([A-Za-z_][A-Za-z0-9_]*)\s*(?:<[^>]+>)?\s*\("""
    )

    private val IDENT_REGEX = Regex("""\b[A-Za-z_][A-Za-z0-9_]*\b""")

    private val CONTROL_FLOW_KEYWORDS = setOf(
        "if", "for", "while", "switch", "catch", "return", "new", "throw", "else", "try", "super", "this",
    )

    private val COMMON_NOISE_IDENTIFIERS = setOf(
        "val", "var", "fun", "class", "interface", "object", "enum", "struct", "trait", "type", "impl",
        "import", "package", "export", "default", "const", "let", "function", "async", "await", "return",
        "true", "false", "null", "nil", "None", "self", "this", "super", "public", "private", "internal",
        "protected", "override", "suspend", "String", "Int", "Long", "Boolean", "Double", "Float", "Unit",
        "List", "Map", "Set", "Result", "Error", "Exception", "run", "main", "init", "get", "set", "name",
        "value", "data", "item", "items", "index", "count", "size", "length", "toString", "equals", "hashCode",
    )
}
