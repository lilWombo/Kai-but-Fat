package com.inspiredandroid.kai.tools

import com.inspiredandroid.kai.data.AppSettings
import com.inspiredandroid.kai.httpClient
import com.inspiredandroid.kai.network.tools.ParameterSchema
import com.inspiredandroid.kai.network.tools.Tool
import com.inspiredandroid.kai.network.tools.ToolInfo
import com.inspiredandroid.kai.network.tools.ToolSchema
import io.ktor.client.plugins.HttpTimeout
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.contentType
import io.ktor.serialization.kotlinx.json.json
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import org.koin.java.KoinJavaComponent.inject

@Serializable
private data class TavilyRequest(
    val query: String,
    @SerialName("api_key") val apiKey: String,
    @SerialName("search_depth") val searchDepth: String = "basic",
    @SerialName("max_results") val maxResults: Int = 5,
    @SerialName("include_answer") val includeAnswer: Boolean = true,
)

@Serializable
private data class TavilyResult(
    val title: String = "",
    val url: String = "",
    val content: String = "",
    val score: Double = 0.0,
)

@Serializable
private data class TavilyResponse(
    val answer: String? = null,
    val results: List<TavilyResult> = emptyList(),
)

/**
 * AI-powered web search using Tavily. Returns curated results with direct
 * answers and source citations. Requires a free Tavily API key (tavily.com).
 */
object TavilySearchTool : Tool {

    private val appSettings: AppSettings by inject(AppSettings::class.java)
    private val json = Json { ignoreUnknownKeys = true }

    private val client = httpClient {
        install(ContentNegotiation) { json(json) }
        install(HttpTimeout) { requestTimeoutMillis = 20_000 }
    }

    override val schema = ToolSchema(
        name = "tavily_search",
        description = """Search the web with Tavily AI — returns high-quality curated results with direct answers.
Use for: current events, news, documentation lookups, price checks, factual queries needing verified sources.
Prefer this over web_search when you need a direct answer rather than a list of links.
Requires a Tavily API key set in Settings > Tools.""",
        parameters = mapOf(
            "query" to ParameterSchema("string", "The search query", true),
            "depth" to ParameterSchema(
                "string",
                "Search depth: 'basic' (fast) or 'advanced' (thorough). Default: basic",
                false,
            ),
        ),
    )

    override suspend fun execute(args: Map<String, Any>): Any {
        val apiKey = appSettings.getTavilyApiKey()
        if (apiKey.isBlank()) {
            return mapOf(
                "success" to false,
                "error" to "Tavily API key not configured. Add it in Settings > Tools. Get a free key at https://tavily.com",
            )
        }

        val query = args["query"]?.toString()
            ?: return mapOf("success" to false, "error" to "query is required")
        val depth = if (args["depth"]?.toString() == "advanced") "advanced" else "basic"

        return try {
            val response = client.post("https://api.tavily.com/search") {
                contentType(ContentType.Application.Json)
                setBody(TavilyRequest(query = query, apiKey = apiKey, searchDepth = depth))
            }
            val body = json.decodeFromString(TavilyResponse.serializer(), response.bodyAsText())
            buildMap<String, Any> {
                put("success", true)
                body.answer?.let { put("answer", it) }
                put(
                    "results",
                    body.results.map {
                        mapOf("title" to it.title, "url" to it.url, "content" to it.content)
                    },
                )
            }
        } catch (e: Exception) {
            mapOf("success" to false, "error" to "Tavily search failed: ${e.message}")
        }
    }

    val toolInfo = ToolInfo(
        id = "tavily_search",
        name = "Tavily AI Search",
        description = "AI-powered web search with direct answers and cited sources",
    )
}
