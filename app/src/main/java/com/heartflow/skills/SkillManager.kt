package com.heartflow.skills

import org.json.JSONObject
import java.io.BufferedReader
import java.io.File
import java.io.InputStreamReader
import java.net.HttpURLConnection
import java.net.URL
import java.text.SimpleDateFormat
import java.util.*

/**
 * SkillManager - 统一管理所有技能
 * 参考Claude的skills架构
 */
class SkillManager {
    private val skillsDir: File
    private val brainDir: File
    private val memoryDir: File
    
    init {
        val homeDir = System.getProperty("user.home")
        skillsDir = File(homeDir, ".heartflow/skills")
        brainDir = File(homeDir, ".heartflow/brain")
        memoryDir = File(homeDir, ".heartflow/memory")
        
        skillsDir.mkdirs()
        brainDir.mkdirs()
        memoryDir.mkdirs()
        
        // 创建brain子目录
        listOf("people", "places", "games", "tech", "events", "media", "ideas", "orgs").forEach {
            File(brainDir, it).mkdirs()
        }
    }
    
    // ========== Skill管理 ==========
    
    fun installSkill(url: String): String {
        return try {
            val rawUrl = when {
                url.contains("github.com") -> {
                    url.replace("github.com", "raw.githubusercontent.com")
                        .replace("/blob/", "/")
                        .let { if (!it.contains("/main/") && !it.contains("/master/")) "$it/main" else it }
                        .let { if (!it.endsWith(".md") && !it.endsWith(".json")) "$it/SKILL.md" else it }
                }
                else -> url
            }
            
            val conn = URL(rawUrl).openConnection() as HttpURLConnection
            conn.connectTimeout = 30000
            conn.readTimeout = 30000
            conn.setRequestProperty("User-Agent", "HeartFlow/2.2.2")
            
            if (conn.responseCode != 200) {
                return "下载失败: HTTP ${conn.responseCode}"
            }
            
            val content = BufferedReader(InputStreamReader(conn.inputStream, Charsets.UTF_8)).readText()
            conn.disconnect()
            
            val name = url.substringAfterLast("/").substringBeforeLast(".")
                .ifBlank { "skill_${System.currentTimeMillis()}" }
            val targetFile = File(skillsDir, "$name.skill")
            targetFile.writeText(content, Charsets.UTF_8)
            
            "✅ 技能安装成功!\n名称: $name\n位置: ${targetFile.absolutePath}"
        } catch (e: Exception) {
            "安装失败: ${e.message}"
        }
    }
    
    fun listSkills(): String {
        val files = skillsDir.listFiles()?.filter { it.name.endsWith(".skill") } ?: emptyList()
        return if (files.isEmpty()) {
            "暂未安装任何技能"
        } else {
            "已安装技能 (${files.size}个):\n" + files.joinToString("\n") { "📦 ${it.nameWithoutExtension}" }
        }
    }
    
    fun uninstallSkill(name: String): String {
        val file = File(skillsDir, "$name.skill")
        return if (file.exists()) {
            file.delete()
            "✅ 已卸载: $name"
        } else {
            "技能不存在: $name"
        }
    }
    
    fun getSkillContent(name: String): String {
        val file = File(skillsDir, "$name.skill")
        return if (file.exists()) {
            file.readText(Charsets.UTF_8).take(5000)
        } else {
            "技能不存在: $name"
        }
    }
    
    // ========== Brain知识库 ==========
    
    fun remember(category: String, name: String, content: String): String {
        val categoryDir = File(brainDir, category.lowercase())
        if (!categoryDir.exists()) categoryDir.mkdirs()
        
        val fileName = name.lowercase().replace(" ", "-").replace(Regex("[^a-z0-9-]"), "")
        val file = File(categoryDir, "$fileName.md")
        
        val timestamp = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.getDefault()).format(Date())
        
        val contentBuilder = StringBuilder()
        if (file.exists()) {
            contentBuilder.appendLine(file.readText())
            contentBuilder.appendLine()
            contentBuilder.appendLine("---")
            contentBuilder.appendLine()
        } else {
            contentBuilder.appendLine("# $name")
            contentBuilder.appendLine()
            contentBuilder.appendLine("Category: $category")
            contentBuilder.appendLine("Created: $timestamp")
            contentBuilder.appendLine()
        }
        
        contentBuilder.appendLine("## Update at $timestamp")
        contentBuilder.appendLine(content)
        
        file.writeText(contentBuilder.toString(), Charsets.UTF_8)
        return "✅ 已记住: $name (${category})"
    }
    
    fun searchBrain(query: String, category: String? = null): String {
        val searchDirs = if (category != null) {
            listOf(File(brainDir, category.lowercase()))
        } else {
            brainDir.listFiles()?.filter { it.isDirectory } ?: emptyList()
        }
        
        val results = mutableListOf<String>()
        val queryLower = query.lowercase()
        
        for (dir in searchDirs) {
            if (!dir.exists()) continue
            dir.listFiles()?.filter { it.extension == "md" }?.forEach { file ->
                val content = file.readText()
                if (content.lowercase().contains(queryLower)) {
                    val preview = content.lines().take(5).joinToString("\n")
                    results.add("📁 ${dir.name}/${file.nameWithoutExtension}:\n$preview")
                }
            }
        }
        
        return if (results.isEmpty()) {
            "未找到关于 '$query' 的信息"
        } else {
            "找到 ${results.size} 条结果:\n\n${results.joinToString("\n\n")}"
        }
    }
    
    // ========== 记忆系统 ==========
    
    fun storeMemory(key: String, value: String): String {
        val memFile = File(memoryDir, "$key.txt")
        val timestamp = SimpleDateFormat("MM-dd HH:mm", Locale.getDefault()).format(Date())
        memFile.appendText("[$timestamp] $value\n")
        return "✅ 已记住: $key"
    }
    
    fun recallMemory(key: String): String {
        val files = memoryDir.listFiles()?.filter { it.name.contains(key, ignoreCase = true) } ?: emptyList()
        return if (files.isEmpty()) {
            "未找到关于 '$key' 的记忆"
        } else {
            val result = StringBuilder("关于 '$key' 的记忆:\n\n")
            files.forEach { file ->
                result.appendLine("📁 ${file.nameWithoutExtension}:")
                result.appendLine(file.readText().take(500))
            }
            result.toString()
        }
    }
    
    // ========== GitHub工具 ==========
    
    fun getGitHubRepoInfo(owner: String, repo: String): String {
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
            
            val json = JSONObject(content)
            buildString {
                appendLine("📦 ${json.getString("full_name")}")
                appendLine("📝 ${json.optString("description", "无描述")}")
                appendLine("⭐ ${json.optInt("stargazers_count")} stars")
                appendLine("🍴 ${json.optInt("forks_count")} forks")
                appendLine("🔗 ${json.getString("html_url")}")
            }
        } catch (e: Exception) {
            "获取仓库信息失败: ${e.message}"
        }
    }
    
    fun searchGitHubRepos(query: String, limit: Int = 5): String {
        return try {
            val encoded = java.net.URLEncoder.encode(query, "UTF-8")
            val conn = URL("https://api.github.com/search/repositories?q=$encoded&sort=stars&per_page=$limit").openConnection() as HttpURLConnection
            conn.connectTimeout = 15000
            conn.readTimeout = 15000
            conn.setRequestProperty("Accept", "application/vnd.github.v3+json")
            conn.setRequestProperty("User-Agent", "HeartFlow/2.2.2")
            
            val reader = BufferedReader(InputStreamReader(conn.inputStream, Charsets.UTF_8))
            val content = reader.readText()
            reader.close()
            conn.disconnect()
            
            val json = JSONObject(content)
            val items = json.getJSONArray("items")
            
            buildString {
                appendLine("GitHub搜索结果: '$query' (${items.length()}个)")
                for (i in 0 until items.length()) {
                    val item = items.getJSONObject(i)
                    appendLine("📦 ${item.getString("full_name")} (⭐ ${item.optInt("stargazers_count")})")
                    appendLine("   ${item.optString("description", "无描述").take(100)}")
                }
            }
        } catch (e: Exception) {
            "搜索失败: ${e.message}"
        }
    }
    
    // ========== 网络搜索 ==========
    
    fun searchWeb(query: String, engine: String = "baidu"): String {
        return try {
            val encoded = java.net.URLEncoder.encode(query, "UTF-8")
            val searchUrl = when (engine.lowercase()) {
                "bing" -> "https://www.bing.com/search?q=$encoded&count=5"
                else -> "https://www.baidu.com/s?wd=$encoded&rn=5"
            }
            
            val conn = URL(searchUrl).openConnection() as HttpURLConnection
            conn.connectTimeout = 15000
            conn.readTimeout = 15000
            conn.setRequestProperty("User-Agent", "Mozilla/5.0")
            
            val reader = BufferedReader(InputStreamReader(conn.inputStream, Charsets.UTF_8))
            val content = reader.readText()
            reader.close()
            conn.disconnect()
            
            val text = content.replace(Regex("<[^>]+>"), " ").replace(Regex("\\s+"), " ").trim()
            "搜索结果 ($engine):\n${text.take(2000)}"
        } catch (e: Exception) {
            "搜索失败: ${e.message}"
        }
    }
    
    fun fetchUrl(url: String): String {
        return try {
            val conn = URL(url).openConnection() as HttpURLConnection
            conn.connectTimeout = 15000
            conn.readTimeout = 15000
            conn.setRequestProperty("User-Agent", "Mozilla/5.0")
            
            val reader = BufferedReader(InputStreamReader(conn.inputStream, Charsets.UTF_8))
            val content = reader.readText()
            reader.close()
            conn.disconnect()
            
            val text = content.replace(Regex("<script[^>]*>[\\s\\S]*?</script>"), "")
                .replace(Regex("<style[^>]*>[\\s\\S]*?</style>"), "")
                .replace(Regex("<[^>]+>"), " ")
                .replace(Regex("\\s+"), " ")
                .trim()
            
            "网页内容:\n${text.take(3000)}"
        } catch (e: Exception) {
            "获取失败: ${e.message}"
        }
    }
    
    // ========== 代码审查 ==========
    
    fun reviewCode(code: String, language: String = "kotlin"): String {
        val lines = code.lines()
        val issues = mutableListOf<String>()
        val suggestions = mutableListOf<String>()
        
        if (lines.size > 100) {
            suggestions.add("💡 代码片段较长，建议拆分")
        }
        
        val emptyLines = lines.count { it.isBlank() }
        if (emptyLines > lines.size * 0.3) {
            suggestions.add("💡 空行较多，建议清理")
        }
        
        return buildString {
            appendLine("📋 代码审查报告 ($language)")
            appendLine("═══════════════════════════════════════")
            appendLine()
            appendLine("📊 基本信息:")
            appendLine("  语言: $language")
            appendLine("  行数: ${lines.size}")
            appendLine()
            
            if (issues.isNotEmpty()) {
                appendLine("❌ 问题 (${issues.size}个):")
                issues.forEach { appendLine("  $it") }
                appendLine()
            }
            
            if (suggestions.isNotEmpty()) {
                appendLine("💡 建议 (${suggestions.size}个):")
                suggestions.forEach { appendLine("  $it") }
                appendLine()
            }
            
            if (issues.isEmpty() && suggestions.isEmpty()) {
                appendLine("✅ 代码质量良好")
            }
        }
    }
    
    // ========== 系统信息 ==========
    
    fun getSystemInfo(): String {
        val runtime = Runtime.getRuntime()
        return buildString {
            appendLine("系统信息:")
            appendLine("OS: ${System.getProperty("os.name")}")
            appendLine("Java: ${System.getProperty("java.version")}")
            appendLine("内存: ${runtime.maxMemory() / 1024 / 1024}MB")
            appendLine("CPU: ${runtime.availableProcessors()}核")
            appendLine("用户目录: ${System.getProperty("user.home")}")
        }
    }
    
    companion object {
        private var instance: SkillManager? = null
        
        fun getInstance(): SkillManager {
            if (instance == null) {
                instance = SkillManager()
            }
            return instance!!
        }
    }
}
