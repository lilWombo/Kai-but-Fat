package com.inspiredandroid.kai.tools

import com.inspiredandroid.kai.models.S25ModelCatalog
import com.inspiredandroid.kai.network.tools.ParameterSchema
import com.inspiredandroid.kai.network.tools.Tool
import com.inspiredandroid.kai.network.tools.ToolInfo
import com.inspiredandroid.kai.network.tools.ToolSchema
import com.inspiredandroid.kai.sandbox.OllamaServerManager
import org.koin.java.KoinJavaComponent.inject
import kotlin.time.Duration.Companion.minutes

/**
 * Controls the on-device Ollama LLM server and provides full model lifecycle
 * management: install, start, stop, pull models, and run inference.
 * Exposes the S25 Ultra curated model catalog for guided model selection.
 */
object OllamaTool : Tool {

    private val ollamaManager: OllamaServerManager by inject(OllamaServerManager::class.java)

    override val timeout = 5.minutes

    override val schema = ToolSchema(
        name = "ollama",
        description = """Control the on-device Ollama LLM server. Runs local LLMs with zero internet and zero API cost.
Operations:
- list_s25_models: Show all models certified for Samsung Galaxy S25 Ultra (with sizes + RAM requirements)
- start: Start the Ollama server (starts automatically on first use)
- stop: Stop the server
- status: Check server status and list loaded models
- pull: Download a model by tag (e.g. gemma3:4b, qwen2.5-coder:7b)
- list: List already-downloaded models
- generate: Run inference — generate a response from a loaded model
Recommended starters for S25 Ultra: gemma3:4b (general), qwen2.5-coder:7b (coding), deepseek-r1:8b (reasoning)""",
        parameters = mapOf(
            "operation" to ParameterSchema(
                "string",
                "Operation: list_s25_models | start | stop | status | pull | list | generate",
                true,
            ),
            "model" to ParameterSchema("string", "Model tag (e.g. gemma3:4b). Required for pull and generate.", false),
            "prompt" to ParameterSchema("string", "Prompt text. Required for generate.", false),
        ),
    )

    override suspend fun execute(args: Map<String, Any>): Any {
        val operation = args["operation"]?.toString()
            ?: return mapOf("success" to false, "error" to "operation is required")

        return when (operation) {
            "list_s25_models" -> mapOf(
                "success" to true,
                "recommended" to S25ModelCatalog.recommendedStarter.ollamaTag,
                "models" to S25ModelCatalog.models.map {
                    mapOf(
                        "ollama_tag" to it.ollamaTag,
                        "name" to it.displayName,
                        "params" to it.paramCount,
                        "size_gb" to it.sizeGb,
                        "ram_required_gb" to it.ramRequiredGb,
                        "category" to it.category.name,
                        "context_window" to it.contextWindow,
                        "description" to it.description,
                    )
                },
            )

            "start" -> {
                ollamaManager.start()
                mapOf("success" to true, "message" to "Ollama server starting at ${ollamaManager.baseUrl}")
            }

            "stop" -> {
                ollamaManager.stop()
                mapOf("success" to true, "message" to "Ollama server stopped")
            }

            "status" -> {
                val models = ollamaManager.listModels()
                mapOf(
                    "success" to true,
                    "base_url" to ollamaManager.baseUrl,
                    "models" to models.map { mapOf("name" to it.name, "size_bytes" to it.size) },
                )
            }

            "list" -> {
                val models = ollamaManager.listModels()
                mapOf("success" to true, "models" to models.map { it.name })
            }

            "pull" -> {
                val model = args["model"]?.toString()
                    ?: return mapOf(
                        "success" to false,
                        "error" to "model tag required. Use operation='list_s25_models' to see available options.",
                    )
                val log = StringBuilder()
                val ok = ollamaManager.pullModel(model) { log.append(it).append('\n') }
                mapOf(
                    "success" to ok,
                    "model" to model,
                    "output" to log.toString().takeLast(1000),
                )
            }

            "generate" -> {
                val model = args["model"]?.toString()
                    ?: return mapOf("success" to false, "error" to "model is required for generate")
                val prompt = args["prompt"]?.toString()
                    ?: return mapOf("success" to false, "error" to "prompt is required for generate")
                val response = ollamaManager.generate(model, prompt)
                mapOf("success" to true, "model" to model, "response" to response)
            }

            else -> mapOf("success" to false, "error" to "Unknown operation: $operation")
        }
    }

    val toolInfo = ToolInfo(
        id = "ollama",
        name = "On-Device Ollama",
        description = "Download and run local LLMs on the S25 Ultra with zero API cost",
    )
}
