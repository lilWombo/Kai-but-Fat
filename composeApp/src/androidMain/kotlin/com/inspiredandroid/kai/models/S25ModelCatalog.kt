package com.inspiredandroid.kai.models

/**
 * Curated list of Ollama-compatible models verified to run on the Samsung Galaxy S25 Ultra.
 *
 * Hardware constraints:
 *   SoC : Snapdragon 8 Elite (Exynos 2500 in some regions)
 *   RAM : 12 GB physical — ~8–9 GB available to apps after Android overhead
 *   NPU : Hexagon NPU for on-device acceleration (accessed via Ollama's llama.cpp backend)
 *
 * All sizes are Q4_K_M GGUF unless noted. Models above 8 GB RAM requirement will
 * likely trigger OOM on S25 Ultra and are excluded from this catalog.
 */
data class S25Model(
    val id: String,
    val displayName: String,
    val ollamaTag: String,
    val paramCount: String,
    val sizeGb: Float,
    val quantization: String = "Q4_K_M",
    val contextWindow: Int,
    val description: String,
    val category: S25ModelCategory,
    val ramRequiredGb: Float = sizeGb + 1.5f,
)

enum class S25ModelCategory {
    GENERAL,    // Best all-round assistant
    CODING,     // Code generation and editing
    FAST,       // Low latency, minimal RAM footprint
    REASONING,  // Extended chain-of-thought / math
}

object S25ModelCatalog {

    val models: List<S25Model> = listOf(

        // ── Fast / Small — under 2 GB ────────────────────────────────────────
        S25Model(
            id = "gemma3-1b",
            displayName = "Gemma 3 1B",
            ollamaTag = "gemma3:1b",
            paramCount = "1B",
            sizeGb = 0.8f,
            contextWindow = 32_768,
            description = "Google's smallest Gemma 3. Extremely fast for quick queries and real-time chat.",
            category = S25ModelCategory.FAST,
            ramRequiredGb = 2.0f,
        ),
        S25Model(
            id = "llama3.2-3b",
            displayName = "Llama 3.2 3B",
            ollamaTag = "llama3.2:3b",
            paramCount = "3B",
            sizeGb = 1.9f,
            contextWindow = 131_072,
            description = "Meta's 3B model with 128K context. Fast and surprisingly capable.",
            category = S25ModelCategory.FAST,
            ramRequiredGb = 3.5f,
        ),
        S25Model(
            id = "phi4-mini",
            displayName = "Phi-4 Mini",
            ollamaTag = "phi4-mini",
            paramCount = "3.8B",
            sizeGb = 2.5f,
            contextWindow = 16_384,
            description = "Microsoft Phi-4 Mini — strong math and coding at a tiny footprint.",
            category = S25ModelCategory.CODING,
            ramRequiredGb = 4.0f,
        ),

        // ── Mid-range — 3–6 GB ───────────────────────────────────────────────
        S25Model(
            id = "gemma3-4b",
            displayName = "Gemma 3 4B",
            ollamaTag = "gemma3:4b",
            paramCount = "4B",
            sizeGb = 3.1f,
            contextWindow = 128_000,
            description = "Best all-round model for S25 Ultra. 128K context, strong coding + reasoning, multimodal-capable.",
            category = S25ModelCategory.GENERAL,
            ramRequiredGb = 4.8f,
        ),
        S25Model(
            id = "qwen2.5-coder-7b",
            displayName = "Qwen 2.5 Coder 7B",
            ollamaTag = "qwen2.5-coder:7b",
            paramCount = "7B",
            sizeGb = 4.7f,
            contextWindow = 131_072,
            description = "Alibaba's dedicated coding model. Best code completion and refactoring at 7B scale.",
            category = S25ModelCategory.CODING,
            ramRequiredGb = 6.3f,
        ),
        S25Model(
            id = "mistral-7b",
            displayName = "Mistral 7B v0.3",
            ollamaTag = "mistral:7b",
            paramCount = "7B",
            sizeGb = 4.1f,
            contextWindow = 32_768,
            description = "The reliable 7B workhorse. Fast inference, strong instruction following.",
            category = S25ModelCategory.GENERAL,
            ramRequiredGb = 5.8f,
        ),
        S25Model(
            id = "llama3.1-8b",
            displayName = "Llama 3.1 8B",
            ollamaTag = "llama3.1:8b",
            paramCount = "8B",
            sizeGb = 4.7f,
            contextWindow = 131_072,
            description = "Meta's flagship 8B instruction model. Excellent all-round assistant with 128K context.",
            category = S25ModelCategory.GENERAL,
            ramRequiredGb = 6.3f,
        ),
        S25Model(
            id = "deepseek-r1-8b",
            displayName = "DeepSeek-R1 8B",
            ollamaTag = "deepseek-r1:8b",
            paramCount = "8B",
            sizeGb = 4.9f,
            contextWindow = 131_072,
            description = "Chain-of-thought reasoning model. Excellent for debugging logic and math problems.",
            category = S25ModelCategory.REASONING,
            ramRequiredGb = 6.5f,
        ),
        S25Model(
            id = "qwen3-8b",
            displayName = "Qwen 3 8B",
            ollamaTag = "qwen3:8b",
            paramCount = "8B",
            sizeGb = 5.2f,
            contextWindow = 131_072,
            description = "Alibaba Qwen 3 with thinking mode. Strong multilingual reasoning.",
            category = S25ModelCategory.REASONING,
            ramRequiredGb = 6.8f,
        ),

        // ── Maximum — up to 8 GB RAM ─────────────────────────────────────────
        S25Model(
            id = "gemma3-12b",
            displayName = "Gemma 3 12B",
            ollamaTag = "gemma3:12b",
            paramCount = "12B",
            sizeGb = 7.6f,
            contextWindow = 128_000,
            description = "Largest model fitting on S25 Ultra. Best on-device reasoning and coding quality available.",
            category = S25ModelCategory.REASONING,
            ramRequiredGb = 9.2f,
        ),
    )

    /** Models with comfortable RAM headroom on S25 Ultra (≤ 8 GB required) */
    fun safeModels() = models.filter { it.ramRequiredGb <= 8.0f }

    fun forCategory(category: S25ModelCategory) = models.filter { it.category == category }

    fun byId(id: String) = models.find { it.id == id }

    fun byOllamaTag(tag: String) = models.find { it.ollamaTag == tag }

    /** Best quality-to-speed balance for the S25 Ultra */
    val recommendedStarter: S25Model get() = byId("gemma3-4b") ?: models.first()
}
