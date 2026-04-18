// composeApp/src/androidMain/kotlin/com/inspiredandroid/kai/tools/FirecrawlTool.kt
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
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.koin.java.KoinJavaComponent.inject

/**
 * Agentic web scraping and full-site crawling via Firecrawl (api.firecrawl.dev).
 * Scrape returns clean markdown from a single URL.
 * Crawl returns markdown from multiple pages of a site.
 * Requires a free Firecrawl API key from https://firecrawl.dev
 */
object FirecrawlTool : Tool {

    private val appSettings: AppSettings by inject(AppSettings::class.java)
    private val json = Json { ignoreUnknownKeys = true }
    private val client = httpClient {
        install(HttpTimeout) { requestTimeoutMillis = 60_000 }
    }

    override val schema = ToolSchema(
        name = "firecrawl",
        description = """Scrape or crawl websites and return clean markdown content.
action='scrape' — Extract clean markdown from a single URL (JS-rendered pages supported).
action='crawl'  — Crawl an entire site (up to limit pages) and return all content as markdown.
action='extract' — Extract structured data from a URL using a natural language prompt.
Better than fetch_url for JS-heavy pages. Requires a Firecrawl API key in Settings > Tools.""",
        parameters = mapOf(
            "action" to ParameterSchema("string", "scrape | crawl | extract", true),
            "url" to ParameterSchema("string", "The URL to scrape or crawl", true),
            "limit" to ParameterSchema("integer", "Max pages to crawl (crawl only, default 5, max 20)", false),
            "prompt" to ParameterSchema("string", "Extraction prompt for action=extract", false),
        ),
    )

    override suspend fun execute(args: Map<String, Any>): Any {
        val apiKey = appSettings.getFirecrawlApiKey()
        if (apiKey.isBlank()) {
            return mapOf(
                "success" to false,
                "error" to "Firecrawl API key not configured. Add it in Settings > Tools. Get a free key at https://firecrawl.dev",
            )
        }
        val url = args["url"]?.toString()
            ?: return mapOf("success" to false, "error" to "url is required")

        return try {
            when (val action = args["action"]?.toString() ?: "scrape") {
                "scrape" -> {
                    val body = """{"url":"$url","formats":["markdown"]}"""
                    val resp = client.post("https://api.firecrawl.dev/v1/scrape") {
                        contentType(ContentType.Application.Json)
                        header("Authorization", "Bearer $apiKey")
                        setBody(body)
                    }
                    val obj = json.parseToJsonElement(resp.bodyAsText()).jsonObject
                    val success = obj["success"]?.jsonPrimitive?.booleanOrNull ?: false
                    val md = obj["data"]?.jsonObject?.get("markdown")?.jsonPrimitive?.content ?: ""
                    mapOf("success" to success, "url" to url, "markdown" to md.take(10_000))
                }

                "crawl" -> {
                    val limit = ((args["limit"] as? Number)?.toInt() ?: 5).coerceIn(1, 20)
                    val body = """{"url":"$url","limit":$limit,"scrapeOptions":{"formats":["markdown"]}}"""
                    val resp = client.post("https://api.firecrawl.dev/v1/crawl") {
                        contentType(ContentType.Application.Json)
                        header("Authorization", "Bearer $apiKey")
                        setBody(body)
                    }
                    val obj = json.parseToJsonElement(resp.bodyAsText()).jsonObject
                    val jobId = obj["id"]?.jsonPrimitive?.content ?: ""
                    mapOf(
                        "success" to true,
                        "message" to "Crawl started",
                        "job_id" to jobId,
                        "note" to "Crawl runs asynchronously. Poll GET https://api.firecrawl.dev/v1/crawl/$jobId for results.",
                    )
                }

                "extract" -> {
                    val prompt = args["prompt"]?.toString() ?: "Extract all key information"
                    val body = """{"url":"$url","prompt":"$prompt"}"""
                    val resp = client.post("https://api.firecrawl.dev/v1/extract") {
                        contentType(ContentType.Application.Json)
                        header("Authorization", "Bearer $apiKey")
                        setBody(body)
                    }
                    val obj = json.parseToJsonElement(resp.bodyAsText()).jsonObject
                    mapOf("success" to true, "url" to url, "data" to obj["data"].toString())
                }

                else -> mapOf("success" to false, "error" to "Unknown action: $action. Use scrape | crawl | extract")
            }
        } catch (e: Exception) {
            mapOf("success" to false, "error" to "Firecrawl failed: ${e.message}")
        }
    }

    val toolInfo = ToolInfo(
        id = "firecrawl",
        name = "Firecrawl",
        description = "Scrape and crawl websites with JS support — returns clean markdown",
    )
}
