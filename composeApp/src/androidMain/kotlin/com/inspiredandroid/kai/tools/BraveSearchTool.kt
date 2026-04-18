// composeApp/src/androidMain/kotlin/com/inspiredandroid/kai/tools/BraveSearchTool.kt
package com.inspiredandroid.kai.tools

import com.inspiredandroid.kai.data.AppSettings
import com.inspiredandroid.kai.httpClient
import com.inspiredandroid.kai.network.tools.ParameterSchema
import com.inspiredandroid.kai.network.tools.Tool
import com.inspiredandroid.kai.network.tools.ToolInfo
import com.inspiredandroid.kai.network.tools.ToolSchema
import io.ktor.client.plugins.HttpTimeout
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.statement.bodyAsText
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import org.koin.java.KoinJavaComponent.inject
import java.net.URLEncoder

@Serializable
private data class BraveWebResult(
    val title: String = "",
    val url: String = "",
    val description: String = "",
)

@Serializable
private data class BraveThumbnail(val src: String = "")

@Serializable
private data class BraveImageResult(
    val title: String = "",
    val url: String = "",
    val thumbnail: BraveThumbnail = BraveThumbnail(),
    val source: String = "",
)

@Serializable
private data class BraveWebResults(val results: List<BraveWebResult> = emptyList())

@Serializable
private data class BraveImageResults(val results: List<BraveImageResult> = emptyList())

@Serializable
private data class BraveSearchResponse(
    val web: BraveWebResults = BraveWebResults(),
    val images: BraveImageResults = BraveImageResults(),
)

/**
 * Web and image search via the Brave Search API.
 * Requires a free Brave Search API key (api.search.brave.com).
 */
object BraveSearchTool : Tool {

    private val appSettings: AppSettings by inject(AppSettings::class.java)
    private val json = Json { ignoreUnknownKeys = true }
    private val client = httpClient {
        install(HttpTimeout) { requestTimeoutMillis = 20_000 }
    }

    override val schema = ToolSchema(
        name = "brave_search",
        description = """Search the web or find images using Brave Search.
Use type='web' (default) for news, facts, documentation, current events.
Use type='image' to find images by description — returns direct image URLs and thumbnails.
Requires a Brave Search API key set in Settings > Tools.""",
        parameters = mapOf(
            "query" to ParameterSchema("string", "The search query", true),
            "type" to ParameterSchema("string", "Search type: 'web' (default) or 'image'", false),
            "count" to ParameterSchema("integer", "Number of results (1-20, default 5)", false),
        ),
    )

    override suspend fun execute(args: Map<String, Any>): Any {
        val apiKey = appSettings.getBraveApiKey()
        if (apiKey.isBlank()) {
            return mapOf(
                "success" to false,
                "error" to "Brave Search API key not configured. Add it in Settings > Tools. Get a free key at https://api.search.brave.com",
            )
        }
        val query = args["query"]?.toString()
            ?: return mapOf("success" to false, "error" to "query is required")
        val type = args["type"]?.toString()?.lowercase() ?: "web"
        val count = ((args["count"] as? Number)?.toInt() ?: 5).coerceIn(1, 20)
        val encoded = URLEncoder.encode(query, "UTF-8")

        return try {
            if (type == "image") {
                val url = "https://api.search.brave.com/res/v1/images/search?q=$encoded&count=$count&safesearch=moderate"
                val response = client.get(url) {
                    header("Accept", "application/json")
                    header("Accept-Encoding", "gzip")
                    header("X-Subscription-Token", apiKey)
                }
                val body = json.decodeFromString(BraveImageResults.serializer(), response.bodyAsText())
                mapOf(
                    "success" to true,
                    "type" to "image",
                    "results" to body.results.map {
                        mapOf(
                            "title" to it.title,
                            "image_url" to it.url,
                            "thumbnail_url" to it.thumbnail.src,
                            "source" to it.source,
                        )
                    },
                )
            } else {
                val url = "https://api.search.brave.com/res/v1/web/search?q=$encoded&count=$count&safesearch=moderate"
                val response = client.get(url) {
                    header("Accept", "application/json")
                    header("Accept-Encoding", "gzip")
                    header("X-Subscription-Token", apiKey)
                }
                val body = json.decodeFromString(BraveSearchResponse.serializer(), response.bodyAsText())
                mapOf(
                    "success" to true,
                    "type" to "web",
                    "results" to body.web.results.map {
                        mapOf("title" to it.title, "url" to it.url, "snippet" to it.description)
                    },
                )
            }
        } catch (e: Exception) {
            mapOf("success" to false, "error" to "Brave Search failed: ${e.message}")
        }
    }

    val toolInfo = ToolInfo(
        id = "brave_search",
        name = "Brave Search",
        description = "Web and image search via Brave Search API — privacy-focused, no tracking",
    )
}
