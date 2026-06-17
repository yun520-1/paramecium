package com.heartflow.web

import android.content.Context
import android.graphics.Bitmap
import android.os.Handler
import android.os.Looper
import android.webkit.WebChromeClient
import android.webkit.WebResourceRequest
import android.webkit.WebView
import android.webkit.WebViewClient
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference

/**
 * WebView浏览器引擎 — Android原生无头浏览器
 * 使用系统WebView渲染JavaScript页面，提取完整内容
 */
class WebViewEngine(private val context: Context) {

    companion object {
        private const val CONTENT_LIMIT = 8000
        private const val USER_AGENT = "Mozilla/5.0 (Linux; Android 14; Pixel 8) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/125.0.0.0 Mobile Safari/537.36"
    }

    /**
     * 同步方式加载页面并提取内容（在IO线程调用）
     */
    fun fetchPageSync(url: String, timeoutMs: Long = 15000L): String {
        val resultRef = AtomicReference<String>("")
        val latch = CountDownLatch(1)

        // WebView必须在主线程创建和操作
        Handler(Looper.getMainLooper()).post {
            try {
                val webView = WebView(context.applicationContext).apply {
                    settings.apply {
                        javaScriptEnabled = true
                        domStorageEnabled = true
                        loadWithOverviewMode = true
                        userAgentString = USER_AGENT
                        mixedContentMode = android.webkit.WebSettings.MIXED_CONTENT_ALWAYS_ALLOW
                    }
                    setLayerType(android.view.View.LAYER_TYPE_SOFTWARE, null)
                }

                val pageLoaded = CountDownLatch(1)
                var finalUrl = url

                webView.webViewClient = object : WebViewClient() {
                    override fun onPageStarted(view: WebView?, url: String?, favicon: Bitmap?) {
                        finalUrl = url ?: finalUrl
                    }

                    override fun onPageFinished(view: WebView?, url: String?) {
                        finalUrl = url ?: finalUrl
                        pageLoaded.countDown()
                    }

                    override fun shouldOverrideUrlLoading(view: WebView?, request: WebResourceRequest?): Boolean = false

                    override fun onReceivedError(view: WebView?, errorCode: Int, description: String?, failingUrl: String?) {
                        pageLoaded.countDown()
                    }
                }

                webView.webChromeClient = WebChromeClient()
                webView.loadUrl(url)

                // 等待页面加载（最多timeoutMs的70%）
                pageLoaded.await((timeoutMs * 7 / 10), TimeUnit.MILLISECONDS)

                // 额外等待JS渲染 — 使用 Handler.postDelay 避免阻塞主线程
                Handler(Looper.getMainLooper()).postDelayed({
                    // 提取内容的JS
                    val jsCode = """
                        (function() {
                            document.querySelectorAll('script,style,noscript,iframe,svg').forEach(function(e){e.remove();});
                            var el = document.querySelector('article')||document.querySelector('main')||document.querySelector('.content')||document.body;
                            var text = el ? (el.innerText||el.textContent||'') : '';
                            var links = [];
                            document.querySelectorAll('a[href]').forEach(function(a){
                                var h=a.href, t=(a.innerText||'').trim();
                                if(h&&t&&t.length>5&&t.length<200&&h.startsWith('http')) links.push({text:t,url:h});
                            });
                            return JSON.stringify({title:document.title||'',url:window.location.href||'',text:text.substring(0,8000),links:links.slice(0,15)});
                        })()
                    """.trimIndent()

                    webView.evaluateJavascript(jsCode) { value ->
                        val cleaned = value?.removeSurrounding("\"")
                            ?.replace("\\\"", "\"")
                            ?.replace("\\n", "\n")
                            ?.replace("\\\\", "\\")
                            ?.replace("\\/", "/") ?: ""

                        if (cleaned.isBlank()) {
                            resultRef.set("❌ 页面内容为空: $url")
                        } else {
                            val title = extractField(cleaned, "title")
                            val pageText = extractField(cleaned, "text")
                            val links = extractLinks(cleaned)

                            val sb = StringBuilder()
                            if (title.isNotBlank()) sb.appendLine("📄 标题: $title")
                            sb.appendLine("🔗 URL: $finalUrl")
                            sb.appendLine()
                            if (pageText.isNotBlank()) {
                                sb.appendLine(pageText.take(CONTENT_LIMIT))
                            }
                            if (links.isNotBlank()) {
                                sb.appendLine()
                                sb.appendLine("📎 相关链接:")
                                sb.appendLine(links)
                            }
                            resultRef.set(sb.toString())
                        }

                        webView.stopLoading()
                        webView.destroy()
                        latch.countDown()
                    }
                }, 1500)

            } catch (e: Exception) {
                resultRef.set("❌ 浏览器错误: ${e.message}")
                latch.countDown()
            }
        }

        latch.await(timeoutMs, TimeUnit.MILLISECONDS)
        val result = resultRef.get()
        return if (result.isNotBlank()) result else "❌ 页面加载超时: $url"
    }

    private fun extractField(json: String, field: String): String {
        val key = "\"$field\""
        val start = json.indexOf(key)
        if (start == -1) return ""
        val colonIdx = json.indexOf(":", start + key.length)
        if (colonIdx == -1) return ""
        val strStart = json.indexOf("\"", colonIdx + 1)
        if (strStart == -1) return ""
        var i = strStart + 1
        val sb = StringBuilder()
        while (i < json.length && json[i] != '"') {
            if (json[i] == '\\' && i + 1 < json.length) {
                when (json[i + 1]) {
                    'n' -> sb.append('\n')
                    '"' -> sb.append('"')
                    '\\' -> sb.append('\\')
                    '/' -> sb.append('/')
                    else -> { sb.append(json[i]); sb.append(json[i + 1]) }
                }
                i += 2
            } else {
                sb.append(json[i])
                i++
            }
        }
        return sb.toString()
    }

    private fun extractLinks(json: String): String {
        val linksIdx = json.indexOf("\"links\"")
        if (linksIdx == -1) return ""
        val arrStart = json.indexOf("[", linksIdx)
        if (arrStart == -1) return ""

        val sb = StringBuilder()
        val textPattern = Regex("\"text\"\\s*:\\s*\"([^\"]+)\"")
        val urlPattern = Regex("\"url\"\\s*:\\s*\"([^\"]+)\"")

        val sub = json.substring(arrStart, (arrStart + 3000).coerceAtMost(json.length))
        val texts = textPattern.findAll(sub).map { it.groupValues[1].replace("\\/", "/") }.toList()
        val urls = urlPattern.findAll(sub).map { it.groupValues[1].replace("\\/", "/") }.toList()

        for (j in texts.indices.take(urls.size).take(10)) {
            sb.appendLine("• ${texts[j]}")
            sb.appendLine("  ${urls[j]}")
        }
        return sb.toString()
    }
}
