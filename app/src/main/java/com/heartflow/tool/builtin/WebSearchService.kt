package com.heartflow.tool.builtin

import com.heartflow.tool.BaseTool
import com.heartflow.tool.ToolCategory
import com.heartflow.tool.ToolContext
import com.heartflow.tool.ToolResult
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import java.nio.charset.StandardCharsets
import java.util.LinkedHashMap

/**
 * 网络搜索服务
 * - 支持多种搜索提供商（Tavily, SerpAPI, Bing, Brave 等）
 * - 支持网页内容抓取
 */
class WebSearchService {
    companion object {
        private const val CONNECT_TIMEOUT_MS = 10000
        private const val READ_TIMEOUT_MS = 20000
    }

    data class SearchResultItem(
        val title: String,
        val url: String,
        val snippet: String,
        val publishedDate: String
    )

    fun search(config: WebSearchConfig, query: String, limit: Int): List<SearchResultItem> {
        val value = config.takeIf { it.baseUrl.isNotEmpty() && it.apiKey.isNotEmpty() }
            ?: throw IllegalStateException("网页搜索未配置。请在设置中填写搜索 API、模型/搜索源和密钥。")
        val maxResults = maxOf(1, minOf(if (limit <= 0) 5 else limit, 10))
        val request = buildSearchRequest(value, query, maxResults)
        val response = httpRequest(request)
        if (response.statusCode < 200 || response.statusCode >= 300) {
            throw IllegalStateException("搜索 API ${response.statusCode}: ${extractErrorText(response.body)}")
        }
        val json = JSONObject(response.body)
        val results = normalizeResults(json, value.provider)
        return if (results.size > maxResults) results.subList(0, maxResults) else results
    }

    fun fetchPage(url: String, maxChars: Int): String {
        val safeUrl = url.trim()
        val limit = maxOf(1000, minOf(if (maxChars <= 0) 12000 else maxChars, 30000))
        val request = HttpRequestInfo(safeUrl, "GET", null).apply {
            headers["Accept"] = "text/html,application/xhtml+xml,text/plain;q=0.9,*/*;q=0.6"
            headers["User-Agent"] = "HeartFlow/1.0"
        }
        val response = httpRequest(request)
        if (response.statusCode < 200 || response.statusCode >= 300) {
            throw IllegalStateException("网页请求失败 ${response.statusCode}: ${response.message}")
        }
        val normalized = if (response.contentType.lowercase().contains("html")) htmlToText(response.body) else response.body
        val compact = normalized.replace(Regex("\\n{3,}"), "\n\n").trim()
        return when {
            compact.isEmpty() -> "网页内容为空或无法提取正文。"
            compact.length > limit -> compact.substring(0, limit) + "\n\n[内容已截断，原始长度约 ${compact.length} 字符]"
            else -> compact
        }
    }

    private fun buildSearchRequest(config: WebSearchConfig, query: String, limit: Int): HttpRequestInfo {
        val provider = WebSearchConfig.normalizeProvider(config.provider)
        if (provider == "tavily") {
            val body = JSONObject()
                .put("query", query)
                .put("max_results", limit)
                .put("search_depth", if (config.model.isEmpty()) "basic" else config.model)
                .put("include_answer", false)
            return HttpRequestInfo(config.baseUrl, "POST", body.toString()).apply {
                headers["Content-Type"] = "application/json"
                headers["Authorization"] = "Bearer ${config.apiKey}"
            }
        }

        val params = LinkedHashMap<String, String>()
        params[config.queryParam] = query
        when (provider) {
            "serpapi" -> {
                params["engine"] = if (config.model.isEmpty()) "google" else config.model
                params[if (config.apiKeyParam.isEmpty()) "api_key" else config.apiKeyParam] = config.apiKey
                params["num"] = limit.toString()
            }
            "bing", "brave" -> params["count"] = limit.toString()
            else -> {
                if (config.model.isNotEmpty()) params["model"] = config.model
                if (config.apiKeyParam.isNotEmpty()) params[config.apiKeyParam] = config.apiKey
                params["limit"] = limit.toString()
            }
        }
        return HttpRequestInfo(appendQuery(config.baseUrl, params), "GET", null).apply {
            headers["Accept"] = "application/json"
            if (config.apiKeyHeader.isNotEmpty()) {
                headers[config.apiKeyHeader] = if (config.apiKeyHeader.equals("authorization", ignoreCase = true))
                    "Bearer ${config.apiKey}" else config.apiKey
            }
        }
    }

    private fun normalizeResults(json: JSONObject, provider: String): List<SearchResultItem> {
        return when (WebSearchConfig.normalizeProvider(provider)) {
            "tavily" -> arrayToResults(json.optJSONArray("results"), "title", "url", "content", "published_date")
            "brave" -> {
                val web = json.optJSONObject("web")
                arrayToResults(web?.optJSONArray("results"), "title", "url", "description", "age")
            }
            "serpapi" -> arrayToResults(json.optJSONArray("organic_results"), "title", "link", "snippet", "date")
            "bing" -> {
                val webPages = json.optJSONObject("webPages")
                arrayToResults(webPages?.optJSONArray("value"), "name", "url", "snippet", "dateLastCrawled")
            }
            else -> {
                var candidates = json.optJSONArray("results")
                if (candidates == null) candidates = json.optJSONArray("items")
                if (candidates == null) candidates = json.optJSONArray("data")
                if (candidates == null) {
                    val webObj = json.optJSONObject("web")
                    if (webObj != null) {
                        candidates = webObj.optJSONArray("results")
                    }
                }
                if (candidates == null) candidates = json.optJSONArray("organic_results")
                arrayToResults(candidates, "title", "url", "snippet", "publishedDate")
            }
        }
    }

    private fun arrayToResults(
        array: org.json.JSONArray?,
        titleKey: String,
        urlKey: String,
        snippetKey: String,
        dateKey: String
    ): List<SearchResultItem> {
        if (array == null) return emptyList()
        val results = mutableListOf<SearchResultItem>()
        for (i in 0 until array.length()) {
            val item = array.optJSONObject(i) ?: continue
            val url = firstOf(item, urlKey, "link", "href")
            if (url.isEmpty()) continue
            results.add(SearchResultItem(
                firstOf(item, titleKey, "name", "title", url).ifEmpty { "Untitled" },
                url,
                firstOf(item, snippetKey, "description", "content"),
                firstOf(item, dateKey)
            ))
        }
        return results
    }

    private fun firstOf(item: JSONObject, vararg keys: String): String {
        for (key in keys) {
            if (key.isEmpty()) continue
            val value = item.optString(key, "").trim()
            if (value.isNotEmpty()) return value
        }
        return ""
    }

    private fun httpRequest(request: HttpRequestInfo): HttpResponseInfo {
        var connection: HttpURLConnection? = null
        try {
            connection = URL(request.url).openConnection() as HttpURLConnection
            connection.connectTimeout = CONNECT_TIMEOUT_MS
            connection.readTimeout = READ_TIMEOUT_MS
            connection.instanceFollowRedirects = true
            connection.requestMethod = request.method
            request.headers.forEach { (k, v) -> connection.setRequestProperty(k, v) }
            if (request.body != null) {
                connection.doOutput = true
                val bytes = request.body.toByteArray(StandardCharsets.UTF_8)
                connection.setFixedLengthStreamingMode(bytes.size)
                val outStream = connection.outputStream
                outStream.write(bytes)
                outStream.close()
            }
            val code = connection.responseCode
            val stream: InputStream? = if (code >= 400) connection.errorStream else connection.inputStream
            return HttpResponseInfo(code, connection.responseMessage, connection.contentType, read(stream))
        } finally {
            connection?.disconnect()
        }
    }

    private fun read(input: InputStream?): String {
        if (input == null) return ""
        return input.use {
            val output = ByteArrayOutputStream()
            val buffer = ByteArray(8192)
            var read: Int
            while (it.read(buffer).also { read = it } != -1) {
                output.write(buffer, 0, read)
            }
            output.toString(StandardCharsets.UTF_8.name())
        }
    }

    private fun appendQuery(baseUrl: String, params: LinkedHashMap<String, String>): String {
        val query = StringBuilder()
        params.entries.joinTo(query, "&") { (k, v) ->
            "${URLEncoder.encode(k, "UTF-8")}=${URLEncoder.encode(v, "UTF-8")}"
        }
        return if (query.isEmpty()) baseUrl else "$baseUrl${if (baseUrl.contains("?")) "&" else "?"}$query"
    }

    private fun htmlToText(html: String): String {
        var text = html ?: ""
        text = text.replace(Regex("(?is)<script[\\s\\S]*?</script>"), " ")
        text = text.replace(Regex("(?is)<style[\\s\\S]*?</style>"), " ")
        text = text.replace(Regex("(?is)<noscript[\\s\\S]*?</noscript>"), " ")
        text = text.replace(Regex("(?is)</(p|div|section|article|header|footer|li|h[1-6]|tr)\\s*>"), "\n")
        text = text.replace(Regex("(?is)<br\\s*/?>"), "\n")
        text = text.replace(Regex("(?is)<[^>]+>"), " ")
        text = text.replace("&nbsp;", " ").replace("&amp;", "&")
            .replace("&lt;", "<").replace("&gt;", ">")
            .replace("&quot;", "\"").replace("&#39;", "'")
        return text.replace(Regex("[ \\t]{2,}"), " ").replace(Regex("\\n[ \\t]+"), "\n").trim()
    }

    private fun extractErrorText(text: String?): String {
        if (text.isNullOrEmpty()) return "请求失败"
        return try {
            val obj = JSONObject(text)
            val error = obj.optJSONObject("error")
            if (error?.optString("message")?.isNotEmpty() == true) error.optString("message")
            else if (obj.optString("message").isNotEmpty()) obj.optString("message")
            else text
        } catch (e: Exception) {
            text
        }
    }

    private data class HttpRequestInfo(
        val url: String,
        val method: String,
        val body: String?,
        val headers: MutableMap<String, String> = mutableMapOf()
    )

    private data class HttpResponseInfo(
        val statusCode: Int,
        val message: String,
        val contentType: String,
        val body: String
    )
}

/**
 * 搜索配置数据类
 */
data class WebSearchConfig(
    val baseUrl: String,
    val apiKey: String,
    val provider: String,
    val model: String,
    val queryParam: String,
    val apiKeyParam: String,
    val apiKeyHeader: String
) {
    companion object {
        const val PROVIDER_TAVILY = "tavily"
        const val PROVIDER_SERPAPI = "serpapi"
        const val PROVIDER_BING = "bing"
        const val PROVIDER_BRAVE = "brave"

        fun defaultConfig(): WebSearchConfig = WebSearchConfig("", "", "", "", "q", "api_key", "")

        fun normalizeProvider(provider: String?): String {
            return when (provider?.trim()?.lowercase()) {
                "tavily" -> PROVIDER_TAVILY
                "serpapi" -> PROVIDER_SERPAPI
                "bing", "msft", "microsoft" -> PROVIDER_BING
                "brave" -> PROVIDER_BRAVE
                else -> provider?.trim() ?: ""
            }
        }
    }
}
