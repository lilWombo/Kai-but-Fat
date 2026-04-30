package com.inspiredandroid.kai.tools

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.webkit.JavascriptInterface
import android.webkit.WebResourceError
import android.webkit.WebResourceRequest
import android.webkit.WebView
import android.webkit.WebViewClient
import com.inspiredandroid.kai.network.tools.ParameterSchema
import com.inspiredandroid.kai.network.tools.Tool
import com.inspiredandroid.kai.network.tools.ToolInfo
import com.inspiredandroid.kai.network.tools.ToolSchema
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import org.koin.java.KoinJavaComponent.inject
import kotlin.time.Duration.Companion.seconds

/**
 * Host-side headless browser backed by a background Android WebView.
 * Runs inside the Android app process so all networking uses the
 * standard Android network stack instead of proot.
 */
object HeadlessBrowserTool : Tool {

    val toolInfo = ToolInfo(
        id = "headless_browser",
        name = "Headless Browser",
        description = "Navigate URLs, extract page content, and run JavaScript from a headless WebView on Android.",
    )

    override val schema = ToolSchema(
        name = "headless_browser",
        description = """Navigate a URL with a real JavaScript-capable browser engine.
Operations:
- navigate: load URL, return raw HTML and final URL
- extract_text: load URL, return visible text content (no HTML tags)
- run_js: load URL, then execute custom JavaScript and return its result""",
        parameters = mapOf(
            "operation" to ParameterSchema(
                "string",
                "navigate | extract_text | run_js",
                true,
            ),
            "url" to ParameterSchema("string", "Full URL to load (https://…)", true),
            "javascript" to ParameterSchema(
                "string",
                "JavaScript expression to evaluate. Required for run_js.",
                false,
            ),
        ),
    )

    override suspend fun execute(args: Map<String, Any>): Any {
        val operation = args["operation"]?.toString()
            ?: return mapOf("success" to false, "error" to "operation is required")
        val url = args["url"]?.toString()
            ?: return mapOf("success" to false, "error" to "url is required")

        return when (operation) {
            "navigate" -> navigatePage(url, extractJs = "document.documentElement.outerHTML")
            "extract_text" -> navigatePage(
                url,
                extractJs = "document.body ? document.body.innerText : ''",
                key = "text",
            )
            "run_js" -> {
                val js = args["javascript"]?.toString()
                    ?: return mapOf("success" to false, "error" to "javascript is required for run_js")
                navigatePage(url, extractJs = "JSON.stringify($js)", key = "result")
            }
            else -> mapOf("success" to false, "error" to "Unknown operation: $operation")
        }
    }

    private suspend fun navigatePage(
        url: String,
        extractJs: String,
        key: String = "html",
    ): Map<String, Any> = withContext(Dispatchers.Main) {
        val context: Context by inject(Context::class.java)
        val deferred = CompletableDeferred<String>()

        val webView = WebView(context).apply {
            settings.javaScriptEnabled = true
            settings.domStorageEnabled = true
            settings.userAgentString =
                "Mozilla/5.0 (Linux; Android 14) AppleWebKit/537.36 Chrome/124.0 Safari/537.36 KaiBrowser/1.0"
        }

        val jsInterface = object {
            @JavascriptInterface
            fun onResult(value: String) {
                if (!deferred.isCompleted) deferred.complete(value)
            }
        }
        webView.addJavascriptInterface(jsInterface, "_KaiBridge")

        webView.webViewClient = object : WebViewClient() {
            override fun onPageFinished(view: WebView, finishedUrl: String) {
                view.loadUrl("javascript:(function(){ _KaiBridge.onResult($extractJs); })();")
            }

            override fun onReceivedError(
                view: WebView,
                request: WebResourceRequest,
                error: WebResourceError,
            ) {
                if (request.isForMainFrame && !deferred.isCompleted) {
                    deferred.complete("ERROR:" + error.description)
                }
            }
        }
        webView.loadUrl(url)

        val result = withTimeoutOrNull(30.seconds) { deferred.await() }

        Handler(Looper.getMainLooper()).post { webView.destroy() }

        return@withContext when {
            result == null -> mapOf("success" to false, "error" to "Timed out loading $url")
            result.startsWith("ERROR:") -> mapOf(
                "success" to false,
                "error" to result.removePrefix("ERROR:"),
            )
            else -> mapOf("success" to true, "url" to url, key to result)
        }
    }
}
