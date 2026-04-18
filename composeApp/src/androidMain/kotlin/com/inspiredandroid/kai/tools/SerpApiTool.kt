// composeApp/src/androidMain/kotlin/com/inspiredandroid/kai/tools/SerpApiTool.kt
package com.inspiredandroid.kai.tools

import com.inspiredandroid.kai.data.AppSettings
import com.inspiredandroid.kai.httpClient
import com.inspiredandroid.kai.network.tools.ParameterSchema
import com.inspiredandroid.kai.network.tools.Tool
import com.inspiredandroid.kai.network.tools.ToolInfo
import com.inspiredandroid.kai.network.tools.ToolSchema
import io.ktor.client.plugins.HttpTimeout
import io.ktor.client.request.get
import io.ktor.client.statement.bodyAsText
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import org.koin.java.KoinJavaComponent.inject
import java.net.URLEncoder

@Serializable
private data class SerpApiOrganicResult(
    val title: String = "",
    val link: String = "",
    val snippet: String = "",
)

@Serializable
private data class SerpApiImageResult(
    val title: String = "",
    @SerialName("original") val imageUrl: String = "",
    @SerialName("thumbnail") val thumbnailUrl: String = "",
    @SerialName("source") val source: String = "",
    @SerialName("link") val pageUrl: String = "",
)

@Serializable
private data class SerpApiResponse(
    @SerialName("organic_results") val organicResults: List<SerpApiOrganicResult> = emptyList(),
    @SerialName("images_results") val imagesResults: List<SerpApiImageResult> = emptyList(),
)

/**
 * Google Search (web + images) via SerpApi.
 * Requires a SerpApi key — free tier: 100 searches/month (serpapi.com).
 */
object SerpApiTool : Tool {

    private val appSettings: AppSettings by inject(AppSettings::class.java)
    private val json = Json { ignoreUnknownKeys = true }
    private val client = httpClient {
        install(HttpTimeout) { requestTimeoutMillis = 20_000 }
    }

    override val schema = ToolSchema(
        name = "serp_search",
        description = """Search Google via SerpApi for web results or images.
Use type='web' (default) for standard Google search results.
Use type='image' to search Google Images — best for specific photos, products, people, or places.
Requires a SerpApi key set in Settings > Tools (free at https://serpapi.com).""",
        parameters = mapOf(
            "query" to ParameterSchema("string", "The search query", true),
            "type" to ParameterSchema("string", "Search type: 'web' (default) or 'image'", false),
            "count" to ParameterSchema("integer", "Number of results (1-10, default 5)", false),
        ),
    )

    override suspend fun execute(args: Map<String, Any>): Any {
        val apiKey = appSettings.getSerpApiKey()
        if (apiKey.isBlank()) {
            return mapOf(
                "success" to false,
                "error" to "SerpApi key not configured. Add it in Settings > Tools. Get a free key at https://serpapi.com",
            )
        }
        val query = args["query"]?.toString()
            ?: return mapOf("success" to false, "error" to "query is required")
        val type = args["type"]?.toString()?.lowercase() ?: "web"
        val count = ((args["count"] as? Number)?.toInt() ?: 5).coerceIn(1, 10)
        val engine = if (type == "image") "google_images" else "google"
        val encoded = URLEncoder.encode(query, "UTF-8")
        val url = "https://serpapi.com/search?q=$encoded&engine=$engine&api_key=$apiKey&num=$count&hl=en&gl=us"

        return try {
            val response = client.get(url)
            val body = json.decodeFromString(SerpApiResponse.serializer(), response.bodyAsText())
            if (type == "image") {
                mapOf(
                    "success" to true,
                    "type" to "image",
                    "results" to body.imagesResults.take(count).map {
                        mapOf(
                            "title" to it.title,
                            "image_url" to it.imageUrl,
                            "thumbnail_url" to it.thumbnailUrl,
                            "source" to it.source,
                            "page_url" to it.pageUrl,
                        )
                    },
                )
            } else {
                mapOf(
                    "success" to true,
                    "type" to "web",
                    "results" to body.organicResults.take(count).map {
                        mapOf("title" to it.title, "url" to it.link, "snippet" to it.snippet)
                    },
                )
            }
        } catch (e: Exception) {
            mapOf("success" to false, "error" to "SerpApi search failed: ${e.message}")
        }
    }

    val toolInfo = ToolInfo(
        id = "serp_search",
        name = "Google Search (SerpApi)",
        description = "Search Google for web results or images. Finds photos, products, people, places",
    )
}
