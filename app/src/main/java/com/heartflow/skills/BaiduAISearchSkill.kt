package com.heartflow.skills

import org.json.JSONObject
import java.io.BufferedReader
import java.io.InputStreamReader
import java.net.HttpURLConnection
import java.net.URL
import java.text.SimpleDateFormat
import java.util.*

/**
 * BaiduAISearchSkill - 百度AI搜索技能
 * 支持网页搜索、百度百科、秒懂百科、AI智能生成四种模式
 */
class BaiduAISearchSkill {
    companion object {
        private const val API_KEY = "bce-v3/ALTAK-VCbLrLdVXHix6aLrf3Fv7/6a3f5b76ac21ee1b3b1d3b4f442f558324e3cdb6"
        private const val BASE_URL = "https://qianfan.baidubce.com/v2/ai_search"
    }
    
    /**
     * 网页搜索
     */
    fun webSearch(query: String, limit: Int = 5): String {
        return try {
            val encoded = java.net.URLEncoder.encode(query, "UTF-8")
            val url = "$BASE_URL?query=$encoded&limit=$limit&type=web_search"
            
            val conn = URL(url).openConnection() as HttpURLConnection
            conn.connectTimeout = 15000
            conn.readTimeout = 15000
            conn.setRequestProperty("Authorization", "Bearer $API_KEY")
            conn.setRequestProperty("Content-Type", "application/json")
            conn.requestMethod = "GET"
            
            val reader = BufferedReader(InputStreamReader(conn.inputStream, Charsets.UTF_8))
            val content = reader.readText()
            reader.close()
            conn.disconnect()
            
            val json = JSONObject(content)
            val results = json.optJSONArray("results") ?: return "未找到结果"
            
            buildString {
                appendLine("🔍 百度AI搜索: $query")
                appendLine("═══════════════════════════════════════")
                for (i in 0 until minOf(results.length(), limit)) {
                    val item = results.getJSONObject(i)
                    appendLine("${i + 1}. ${item.optString("title", "无标题")}")
                    appendLine("   ${item.optString("content", "无摘要").take(100)}")
                    appendLine("   🔗 ${item.optString("url", "")}")
                    appendLine()
                }
            }
        } catch (e: Exception) {
            "❌ 搜索失败: ${e.message}"
        }
    }
    
    /**
     * 百度百科
     */
    fun baikeSearch(query: String): String {
        return try {
            val encoded = java.net.URLEncoder.encode(query, "UTF-8")
            val url = "$BASE_URL?query=$encoded&type=baike"
            
            val conn = URL(url).openConnection() as HttpURLConnection
            conn.connectTimeout = 15000
            conn.readTimeout = 15000
            conn.setRequestProperty("Authorization", "Bearer $API_KEY")
            conn.setRequestProperty("Content-Type", "application/json")
            conn.requestMethod = "GET"
            
            val reader = BufferedReader(InputStreamReader(conn.inputStream, Charsets.UTF_8))
            val content = reader.readText()
            reader.close()
            conn.disconnect()
            
            val json = JSONObject(content)
            val results = json.optJSONArray("results") ?: return "未找到百科内容"
            
            buildString {
                appendLine("📚 百度百科: $query")
                appendLine("═══════════════════════════════════════")
                for (i in 0 until minOf(results.length(), 3)) {
                    val item = results.getJSONObject(i)
                    appendLine("📖 ${item.optString("title", "无标题")}")
                    appendLine("${item.optString("content", "无内容").take(500)}")
                    appendLine()
                }
            }
        } catch (e: Exception) {
            "❌ 百科搜索失败: ${e.message}"
        }
    }
    
    /**
     * 秒懂百科（视频）
     */
    fun miaodongBaike(query: String): String {
        return try {
            val encoded = java.net.URLEncoder.encode(query, "UTF-8")
            val url = "$BASE_URL?query=$encoded&type=miaodong_baike"
            
            val conn = URL(url).openConnection() as HttpURLConnection
            conn.connectTimeout = 15000
            conn.readTimeout = 15000
            conn.setRequestProperty("Authorization", "Bearer $API_KEY")
            conn.setRequestProperty("Content-Type", "application/json")
            conn.requestMethod = "GET"
            
            val reader = BufferedReader(InputStreamReader(conn.inputStream, Charsets.UTF_8))
            val content = reader.readText()
            reader.close()
            conn.disconnect()
            
            val json = JSONObject(content)
            val results = json.optJSONArray("results") ?: return "未找到秒懂百科内容"
            
            buildString {
                appendLine("🎬 秒懂百科: $query")
                appendLine("═══════════════════════════════════════")
                for (i in 0 until minOf(results.length(), 3)) {
                    val item = results.getJSONObject(i)
                    appendLine("📹 ${item.optString("title", "无标题")}")
                    appendLine("   ${item.optString("description", "无描述").take(100)}")
                    appendLine("   🔗 ${item.optString("url", "")}")
                    appendLine()
                }
            }
        } catch (e: Exception) {
            "❌ 秒懂百科搜索失败: ${e.message}"
        }
    }
    
    /**
     * AI智能生成
     */
    fun aiChat(query: String): String {
        return try {
            val encoded = java.net.URLEncoder.encode(query, "UTF-8")
            val url = "$BASE_URL?query=$encoded&type=ai_chat"
            
            val conn = URL(url).openConnection() as HttpURLConnection
            conn.connectTimeout = 30000
            conn.readTimeout = 30000
            conn.setRequestProperty("Authorization", "Bearer $API_KEY")
            conn.setRequestProperty("Content-Type", "application/json")
            conn.requestMethod = "GET"
            
            val reader = BufferedReader(InputStreamReader(conn.inputStream, Charsets.UTF_8))
            val content = reader.readText()
            reader.close()
            conn.disconnect()
            
            val json = JSONObject(content)
            val answer = json.optString("answer", "未生成答案")
            val sources = json.optJSONArray("references")
            
            buildString {
                appendLine("🤖 AI智能回答: $query")
                appendLine("═══════════════════════════════════════")
                appendLine(answer)
                if (sources != null && sources.length() > 0) {
                    appendLine()
                    appendLine("📚 参考来源:")
                    for (i in 0 until minOf(sources.length(), 5)) {
                        val source = sources.getJSONObject(i)
                        appendLine("   ${i + 1}. ${source.optString("title", "")} - ${source.optString("url", "")}")
                    }
                }
            }
        } catch (e: Exception) {
            "❌ AI搜索失败: ${e.message}"
        }
    }
    
    /**
     * 智能搜索（自动选择最佳模式）
     */
    fun smartSearch(query: String): String {
        // 根据查询内容自动选择搜索模式
        val lowerQuery = query.lowercase()
        return when {
            lowerQuery.contains("百科") || lowerQuery.contains("是什么") -> baikeSearch(query)
            lowerQuery.contains("视频") || lowerQuery.contains("秒懂") -> miaodongBaike(query)
            lowerQuery.contains("怎么") || lowerQuery.contains("如何") || lowerQuery.contains("为什么") -> aiChat(query)
            else -> webSearch(query)
        }
    }
    
    private fun minOf(a: Int, b: Int): Int = if (a < b) a else b
}

// 单例实例
val baiduAISearch = BaiduAISearchSkill()
