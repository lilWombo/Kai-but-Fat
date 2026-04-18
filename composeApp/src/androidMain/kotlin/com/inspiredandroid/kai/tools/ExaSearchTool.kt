// composeApp/src/androidMain/kotlin/com/inspiredandroid/kai/tools/ExaSearchTool.kt
package com.inspiredandroid.kai.tools

import com.inspiredandroid.kai.data.AppSettings
import com.inspiredandroid.kai.httpClient
import com.inspiredandroid.kai.network.tools.ParameterSchema
import com.inspiredandroid.kai.network.tools.Tool
import com.inspiredandroid.kai.network.tools.ToolInfo
import com.inspiredandroid.kai.network.tools.ToolSchema
import io.ktor.client.plugins.HttpTimeout
import io.ktor.client.request.header
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.contentType
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import org.koin.java.KoinJavaComponent.inject

@Serializable
private data class ExaResult(
    val id: String = "",
    val title: String = "",
    val url: String = "",
    val score: Double = 0.0,
    val publishedDate: String = "",
    val text: String = "",
    val summary: String = "",
)

@Serializable
private data class ExaSearchResponse(
    val results: List<ExaResult> = emptyList(),
)

@Serializable
private data class ExaSearchRequest(
    val query: String,
    val type: String = "neural",
    val numResults: Int = 5,
    val contents: ExaContents = ExaContents(),
)

@Serializable
private data class ExaContents(
    val text: ExaTextOption = ExaTextOption(),
    val summary: ExaSummaryOption = ExaSummaryOption(),
)

@Serializable
private data class ExaTextOption(val maxCharacters: Int = 1000)

@Serializable
private data class ExaSummaryOption(val query: String = "")

/**
 * Neural + keyword web search via Exa AI (api.exa.ai).
 * Neural search understands meaning, not just keywords — better for research questions.
 * Requires a free Exa API key from https://exa.ai
 */
object ExaSearchTool : Tool {

    private val appSettings: AppSettings by inject(AppSettings::class.java)
    private val json = Json { ignoreUnknownKeys = true }
    private val client = httpClient {
        install(HttpTimeout) { requestTimeoutMillis = 20_000 }
    }

    override val schema = ToolSchema(
        name = "exa_search",
        description = """Neural and keyword web search via Exa AI.
type='neural' (default) understands meaning and context — ideal for research questions.
type='keyword' behaves like a traditional search engine.
type='auto' lets Exa decide.
Returns titles, URLs, publication dates, and text snippets.
Requires an Exa API key in Settings > Tools.""",
        parameters = mapOf(
            "query" to ParameterSchema("string", "The search query", true),
            "type" to ParameterSchema("string", "Search type: neural (default) | keyword | auto", false),
            "count" to ParameterSchema("integer", "Number of results (1-10, default 5)", false),
        ),
    )

    override suspend fun execute(args: Map<String, Any>): Any {
        val apiKey = appSettings.getExaApiKey()
        if (apiKey.isBlank()) {
            return mapOf(
                "success" to false,
                "error" to "Exa API key not configured. Add it in Settings > Tools. Get a free key at https://exa.ai",
            )
        }
        val query = args["query"]?.toString()
            ?: return mapOf("success" to false, "error" to "query is required")
        val type  = args["type"]?.toString() ?: "neural"
        val count = ((args["count"] as? Number)?.toInt() ?: 5).coerceIn(1, 10)

        return try {
            val body = json.encodeToString(
                ExaSearchRequest.serializer(),
                ExaSearchRequest(query = query, type = type, numResults = count),
            )
            val response = client.post("https://api.exa.ai/search") {
                contentType(ContentType.Application.Json)
                header("x-api-key", apiKey)
                setBody(body)
            }
            val parsed = json.decodeFromString(ExaSearchResponse.serializer(), response.bodyAsText())
            mapOf(
                "success" to true,
                "type" to type,
                "results" to parsed.results.map {
                    mapOf(
                        "title" to it.title,
                        "url" to it.url,
                        "score" to it.score,
                        "published" to it.publishedDate,
                        "text" to it.text.take(500),
                    )
                },
            )
        } catch (e: Exception) {
            mapOf("success" to false, "error" to "Exa search failed: ${e.message}")
        }
    }

    val toolInfo = ToolInfo(
        id = "exa_search",
        name = "Exa Neural Search",
        description = "Semantic neural web search — understands meaning, not just keywords",
    )
}
