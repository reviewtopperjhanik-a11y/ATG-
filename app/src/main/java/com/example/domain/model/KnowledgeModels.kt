package com.example.domain.model

enum class KnowledgeCategory(val label: String) {
    ARCHITECTURAL_DECISION("Architecture"),
    KNOWN_FIX("Known Fix"),
    CONVENTIONS("Convention"),
    VALIDATION_INSIGHT("Validation"),
    FAILURE_PATTERN("Failure Pattern"),
}

/**
 * Temporal, fact-keyed long-term project knowledge entry (Mem0 / Graphiti-inspired).
 *
 * When a newer fact shares the same normalized [factKey] for a project and updates or replaces
 * prior knowledge, the previous entry is closed out via [validUntilEpochMs] and [supersededById]
 * rather than overwritten destructively, preserving historical auditability while keeping active
 * retrieval clean and non-redundant.
 */
data class ProjectKnowledgeEntry(
    val id: String,
    val projectId: String,
    val factKey: String,
    val category: KnowledgeCategory,
    val title: String,
    val content: String,
    val tags: String = "",
    val sourceTaskId: String? = null,
    val confidence: Float = 0.85f,
    val validFromEpochMs: Long,
    val validUntilEpochMs: Long? = null,
    val supersededById: String? = null,
    val createdAtEpochMs: Long,
    val updatedAtEpochMs: Long,
) {
    val isCurrentlyValid: Boolean
        get() = supersededById == null && validUntilEpochMs == null

    fun isValidAt(epochMs: Long): Boolean =
        validFromEpochMs <= epochMs && (validUntilEpochMs == null || validUntilEpochMs > epochMs)
}

data class KnowledgeRetrievalHit(
    val entry: ProjectKnowledgeEntry,
    val score: Double,
    val matchedViaFts5: Boolean,
)

data class KnowledgeRetrievalResult(
    val query: String,
    val hits: List<KnowledgeRetrievalHit>,
    val totalActiveEntries: Int,
    val usedFts5: Boolean,
    val estimatedTokens: Int,
) {
    fun toPromptContext(maxTokens: Int = DEFAULT_KNOWLEDGE_TOKEN_BUDGET): String {
        if (hits.isEmpty()) return ""
        val sb = StringBuilder()
        sb.appendLine("[RECALLED LONG-TERM PROJECT KNOWLEDGE]")
        for (hit in hits) {
            val e = hit.entry
            val line = "- [${e.category.label}] ${e.title}: ${e.content}"
            val candidate = sb.toString() + line + "\n"
            if (estimateTokens(candidate) > maxTokens) {
                break
            }
            sb.appendLine(line)
        }
        return sb.toString().trim().takeIf { it != "[RECALLED LONG-TERM PROJECT KNOWLEDGE]" }.orEmpty()
    }

    companion object {
        const val DEFAULT_KNOWLEDGE_TOKEN_BUDGET = 450

        fun estimateTokens(text: String): Int =
            if (text.isBlank()) 0 else ((text.length + 3) / 4).coerceAtLeast(1)
    }
}
