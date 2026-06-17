package com.heartflow.skills

import java.io.BufferedReader
import java.io.InputStreamReader
import java.net.HttpURLConnection
import java.net.URL

/**
 * GitHub Skill - GitHub交互
 * 参考Claude的github skill
 */
class GitHubSkill {
    
    /**
     * 获取仓库信息
     */
    fun getRepoInfo(owner: String, repo: String): String {
        return try {
            val conn = URL("https://api.github.com/repos/$owner/$repo").openConnection() as HttpURLConnection
            conn.connectTimeout = 15000
            conn.readTimeout = 15000
            conn.setRequestProperty("Accept", "application/vnd.github.v3+json")
            conn.setRequestProperty("User-Agent", "HeartFlow/2.2.2")
            
            val reader = BufferedReader(InputStreamReader(conn.inputStream, Charsets.UTF_8))
            val content = reader.readText()
            reader.close()
            conn.disconnect()
            
            val json = org.json.JSONObject(content)
            buildString {
                appendLine("📦 ${json.getString("full_name")}")
                appendLine("📝 ${json.optString("description", "无描述")}")
                appendLine("⭐ ${json.optInt("stargazers_count")} stars")
                appendLine("🍴 ${json.optInt("forks_count")} forks")
                appendLine("🔗 ${json.getString("html_url")}")
                appendLine("📅 创建: ${json.optString("created_at", "")}")
                appendLine("🔄 更新: ${json.optString("updated_at", "")}")
            }
        } catch (e: Exception) {
            "获取仓库信息失败: ${e.message}"
        }
    }
    
    /**
     * 搜索仓库
     */
    fun searchRepos(query: String, sort: String = "stars", limit: Int = 5): String {
        return try {
            val encoded = java.net.URLEncoder.encode(query, "UTF-8")
            val conn = URL("https://api.github.com/search/repositories?q=$encoded&sort=$sort&per_page=$limit").openConnection() as HttpURLConnection
            conn.connectTimeout = 15000
            conn.readTimeout = 15000
            conn.setRequestProperty("Accept", "application/vnd.github.v3+json")
            conn.setRequestProperty("User-Agent", "HeartFlow/2.2.2")
            
            val reader = BufferedReader(InputStreamReader(conn.inputStream, Charsets.UTF_8))
            val content = reader.readText()
            reader.close()
            conn.disconnect()
            
            val json = org.json.JSONObject(content)
            val items = json.getJSONArray("items")
            
            buildString {
                appendLine("GitHub搜索结果: '$query' (${items.length()}个)")
                appendLine()
                for (i in 0 until items.length()) {
                    val item = items.getJSONObject(i)
                    appendLine("📦 ${item.getString("full_name")} (⭐ ${item.optInt("stargazers_count")})")
                    appendLine("   ${item.optString("description", "无描述").take(100)}")
                    appendLine("   ${item.getString("html_url")}")
                    appendLine()
                }
            }
        } catch (e: Exception) {
            "搜索失败: ${e.message}"
        }
    }
    
    /**
     * 获取仓库文件列表
     */
    fun listFiles(owner: String, repo: String, path: String = ""): String {
        return try {
            val url = if (path.isBlank()) {
                "https://api.github.com/repos/$owner/$repo/contents"
            } else {
                "https://api.github.com/repos/$owner/$repo/contents/$path"
            }
            
            val conn = URL(url).openConnection() as HttpURLConnection
            conn.connectTimeout = 15000
            conn.readTimeout = 15000
            conn.setRequestProperty("Accept", "application/vnd.github.v3+json")
            conn.setRequestProperty("User-Agent", "HeartFlow/2.2.2")
            
            val reader = BufferedReader(InputStreamReader(conn.inputStream, Charsets.UTF_8))
            val content = reader.readText()
            reader.close()
            conn.disconnect()
            
            val items = org.json.JSONArray(content)
            
            buildString {
                appendLine("📁 $owner/$repo/${path.ifBlank { "/" }} (${items.length()}项)")
                appendLine()
                for (i in 0 until items.length()) {
                    val item = items.getJSONObject(i)
                    val type = if (item.getString("type") == "dir") "📁" else "📄"
                    appendLine("$type ${item.getString("name")}")
                }
            }
        } catch (e: Exception) {
            "获取文件列表失败: ${e.message}"
        }
    }
    
    /**
     * 获取文件内容
     */
    fun getFileContent(owner: String, repo: String, path: String): String {
        return try {
            val conn = URL("https://raw.githubusercontent.com/$owner/$repo/main/$path").openConnection() as HttpURLConnection
            conn.connectTimeout = 15000
            conn.readTimeout = 15000
            conn.setRequestProperty("User-Agent", "HeartFlow/2.2.2")
            
            val reader = BufferedReader(InputStreamReader(conn.inputStream, Charsets.UTF_8))
            val content = reader.readText()
            reader.close()
            conn.disconnect()
            
            "📄 $path:\n\n${content.take(5000)}"
        } catch (e: Exception) {
            "获取文件内容失败: ${e.message}"
        }
    }
    
    /**
     * 获取用户信息
     */
    fun getUserInfo(username: String): String {
        return try {
            val conn = URL("https://api.github.com/users/$username").openConnection() as HttpURLConnection
            conn.connectTimeout = 15000
            conn.readTimeout = 15000
            conn.setRequestProperty("Accept", "application/vnd.github.v3+json")
            conn.setRequestProperty("User-Agent", "HeartFlow/2.2.2")
            
            val reader = BufferedReader(InputStreamReader(conn.inputStream, Charsets.UTF_8))
            val content = reader.readText()
            reader.close()
            conn.disconnect()
            
            val json = org.json.JSONObject(content)
            buildString {
                appendLine("👤 ${json.getString("login")}")
                appendLine("📝 ${json.optString("bio", "无简介")}")
                appendLine("📍 ${json.optString("location", "未知")}")
                appendLine("👥 ${json.optInt("followers")} followers")
                appendLine("🔗 ${json.getString("html_url")}")
            }
        } catch (e: Exception) {
            "获取用户信息失败: ${e.message}"
        }
    }
    
    companion object {
        private var instance: GitHubSkill? = null
        
        fun getInstance(): GitHubSkill {
            if (instance == null) {
                instance = GitHubSkill()
            }
            return instance!!
        }
    }
}
