// composeApp/src/androidMain/kotlin/com/inspiredandroid/kai/tools/FetchTool.kt
package com.inspiredandroid.kai.tools

import com.inspiredandroid.kai.httpClient
import com.inspiredandroid.kai.network.tools.ParameterSchema
import com.inspiredandroid.kai.network.tools.Tool
import com.inspiredandroid.kai.network.tools.ToolInfo
import com.inspiredandroid.kai.network.tools.ToolSchema
import io.ktor.client.plugins.HttpTimeout
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.statement.bodyAsText

/**
 * Fetches any URL and returns clean readable text, stripping HTML tags.
 * Lightweight alternative to the Playwright MCP for simple URL reading.
 */
object FetchTool : Tool {

    private val client = httpClient {
        install(HttpTimeout) { requestTimeoutMillis = 30_000 }
    }

    override val schema = ToolSchema(
        name = "fetch_url",
        description = """Fetch the content of any public URL and return it as clean readable text.
Use for reading articles, documentation, API responses, or any web page.
For complex JS-rendered pages use the Playwright MCP instead.""",
        parameters = mapOf(
            "url" to ParameterSchema("string", "The URL to fetch", true),
            "max_chars" to ParameterSchema("integer", "Max characters to return (default 8000)", false),
        ),
    )

    override suspend fun execute(args: Map<String, Any>): Any {
        val url = args["url"]?.toString()
            ?: return mapOf("success" to false, "error" to "url is required")
        val maxChars = ((args["max_chars"] as? Number)?.toInt() ?: 8_000).coerceIn(100, 50_000)

        return try {
            val response = client.get(url) {
                header("User-Agent", "Mozilla/5.0 (compatible; Kai/1.0)")
                header("Accept", "text/html,application/xhtml+xml,text/plain;q=0.9,*/*;q=0.8")
            }
            val raw = response.bodyAsText()
            val text = stripHtml(raw).trim()
            val truncated = text.length > maxChars
            mapOf(
                "success" to true,
                "url" to url,
                "content" to text.take(maxChars),
                "truncated" to truncated,
                "total_chars" to text.length,
            )
        } catch (e: Exception) {
            mapOf("success" to false, "error" to "Fetch failed: ${e.message}")
        }
    }

    private fun stripHtml(html: String): String {
        var s = html
        s = s.replace(Regex("<script[^>]*>[\s\S]*?</script>", RegexOption.IGNORE_CASE), " ")
        s = s.replace(Regex("<style[^>]*>[\s\S]*?</style>", RegexOption.IGNORE_CASE), " ")
        s = s.replace(Regex("<[^>]+>"), " ")
        s = s.replace(Regex("&nbsp;"), " ")
        s = s.replace(Regex("&amp;"), "&")
        s = s.replace(Regex("&lt;"), "<")
        s = s.replace(Regex("&gt;"), ">")
        s = s.replace(Regex("&quot;"), "\"")
        s = s.replace(Regex("&#39;"), "'")
        s = s.replace(Regex("[ \t]+"), " ")
        s = s.replace(Regex("\n{3,}"), "\n\n")
        return s
    }

    val toolInfo = ToolInfo(
        id = "fetch_url",
        name = "Fetch URL",
        description = "Fetch any public URL and return clean readable text",
    )
}
