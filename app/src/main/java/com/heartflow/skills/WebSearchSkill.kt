package com.heartflow.skills

import java.io.BufferedReader
import java.io.InputStreamReader
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder

/**
 * WebSearch Skill - 网络搜索
 * 参考Claude的web-search skill
 */
class WebSearchSkill {
    
    /**
     * 百度搜索
     */
    fun searchBaidu(query: String, limit: Int = 5): String {
        return try {
            val encoded = URLEncoder.encode(query, "UTF-8")
            val conn = URL("https://www.baidu.com/s?wd=$encoded&rn=$limit").openConnection() as HttpURLConnection
            conn.connectTimeout = 15000
            conn.readTimeout = 15000
            conn.setRequestProperty("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36")
            
            val reader = BufferedReader(InputStreamReader(conn.inputStream, Charsets.UTF_8))
            val content = reader.readText()
            reader.close()
            conn.disconnect()
            
            val text = content.replace(Regex("<script[^>]*>[\\s\\S]*?</script>"), "")
                .replace(Regex("<style[^>]*>[\\s\\S]*?</style>"), "")
                .replace(Regex("<[^>]+>"), " ")
                .replace(Regex("\\s+"), " ")
                .trim()
            
            "🔍 百度搜索 '$query':\n${text.take(2000)}"
        } catch (e: Exception) {
            "搜索失败: ${e.message}"
        }
    }
    
    /**
     * 必应搜索
     */
    fun searchBing(query: String, limit: Int = 5): String {
        return try {
            val encoded = URLEncoder.encode(query, "UTF-8")
            val conn = URL("https://www.bing.com/search?q=$encoded&count=$limit").openConnection() as HttpURLConnection
            conn.connectTimeout = 15000
            conn.readTimeout = 15000
            conn.setRequestProperty("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36")
            
            val reader = BufferedReader(InputStreamReader(conn.inputStream, Charsets.UTF_8))
            val content = reader.readText()
            reader.close()
            conn.disconnect()
            
            val text = content.replace(Regex("<script[^>]*>[\\s\\S]*?</script>"), "")
                .replace(Regex("<style[^>]*>[\\s\\S]*?</style>"), "")
                .replace(Regex("<[^>]+>"), " ")
                .replace(Regex("\\s+"), " ")
                .trim()
            
            "🔍 必应搜索 '$query':\n${text.take(2000)}"
        } catch (e: Exception) {
            "搜索失败: ${e.message}"
        }
    }
    
    /**
     * 谷歌搜索
     */
    fun searchGoogle(query: String, limit: Int = 5): String {
        return try {
            val encoded = URLEncoder.encode(query, "UTF-8")
            val conn = URL("https://www.google.com/search?q=$encoded&num=$limit").openConnection() as HttpURLConnection
            conn.connectTimeout = 15000
            conn.readTimeout = 15000
            conn.setRequestProperty("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36")
            
            val reader = BufferedReader(InputStreamReader(conn.inputStream, Charsets.UTF_8))
            val content = reader.readText()
            reader.close()
            conn.disconnect()
            
            val text = content.replace(Regex("<script[^>]*>[\\s\\S]*?</script>"), "")
                .replace(Regex("<style[^>]*>[\\s\\S]*?</style>"), "")
                .replace(Regex("<[^>]+>"), " ")
                .replace(Regex("\\s+"), " ")
                .trim()
            
            "🔍 谷歌搜索 '$query':\n${text.take(2000)}"
        } catch (e: Exception) {
            "搜索失败: ${e.message}"
        }
    }
    
    /**
     * 获取网页内容
     */
    fun fetchUrl(url: String): String {
        return try {
            val conn = URL(url).openConnection() as HttpURLConnection
            conn.connectTimeout = 15000
            conn.readTimeout = 15000
            conn.setRequestProperty("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36")
            conn.instanceFollowRedirects = true
            
            val reader = BufferedReader(InputStreamReader(conn.inputStream, Charsets.UTF_8))
            val content = reader.readText()
            reader.close()
            conn.disconnect()
            
            val text = content.replace(Regex("<script[^>]*>[\\s\\S]*?</script>"), "")
                .replace(Regex("<style[^>]*>[\\s\\S]*?</style>"), "")
                .replace(Regex("<[^>]+>"), " ")
                .replace(Regex("\\s+"), " ")
                .trim()
            
            "🌐 网页内容 ($url):\n${text.take(5000)}"
        } catch (e: Exception) {
            "获取网页失败: ${e.message}"
        }
    }
    
    /**
     * 综合搜索
     */
    fun search(query: String, engines: List<String> = listOf("baidu", "bing")): String {
        val results = mutableListOf<String>()
        
        for (engine in engines) {
            val result = when (engine.lowercase()) {
                "baidu" -> searchBaidu(query, 3)
                "bing" -> searchBing(query, 3)
                "google" -> searchGoogle(query, 3)
                else -> continue
            }
            results.add(result)
        }
        
        return results.joinToString("\n\n---\n\n")
    }
    
    companion object {
        private var instance: WebSearchSkill? = null
        
        fun getInstance(): WebSearchSkill {
            if (instance == null) {
                instance = WebSearchSkill()
            }
            return instance!!
        }
    }
}
