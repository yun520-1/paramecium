package com.heartflow.skills

import org.json.JSONObject
import java.io.File
import java.text.SimpleDateFormat
import java.util.*

/**
 * Brain Skill - 个人知识库
 * 参考Claude的2nd-brain skill
 */
class BrainSkill {
    private val brainDir: File
    
    init {
        val homeDir = System.getProperty("user.home")
        brainDir = File(homeDir, ".heartflow/brain")
        brainDir.mkdirs()
        
        // 创建子目录
        listOf("people", "places", "games", "tech", "events", "media", "ideas", "orgs").forEach {
            File(brainDir, it).mkdirs()
        }
    }
    
    /**
     * 记住信息
     */
    fun remember(category: String, name: String, content: String, tags: List<String> = emptyList()): String {
        val categoryDir = File(brainDir, category.lowercase())
        if (!categoryDir.exists()) categoryDir.mkdirs()
        
        val fileName = name.lowercase().replace(" ", "-").replace(Regex("[^a-z0-9-]"), "")
        val file = File(categoryDir, "$fileName.md")
        
        val timestamp = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.getDefault()).format(Date())
        val tagStr = if (tags.isNotEmpty()) "\nTags: ${tags.joinToString(", ")}" else ""
        
        val content_builder = StringBuilder()
        if (file.exists()) {
            content_builder.appendLine(file.readText())
            content_builder.appendLine()
            content_builder.appendLine("---")
            content_builder.appendLine()
        } else {
            content_builder.appendLine("# $name")
            content_builder.appendLine()
            content_builder.appendLine("Category: $category")
            content_builder.appendLine("Created: $timestamp")
            content_builder.appendLine(tagStr)
            content_builder.appendLine()
        }
        
        content_builder.appendLine("## Update at $timestamp")
        content_builder.appendLine(content)
        
        file.writeText(content_builder.toString(), Charsets.UTF_8)
        return "✅ 已记住: $name (${category})"
    }
    
    /**
     * 搜索知识库
     */
    fun search(query: String, category: String? = null): String {
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
    
    /**
     * 获取分类列表
     */
    fun listCategories(): String {
        val categories = brainDir.listFiles()?.filter { it.isDirectory }?.map { it.name } ?: emptyList()
        return "知识库分类 (${categories.size}个):\n${categories.joinToString("\n") { "- $it" }}"
    }
    
    /**
     * 获取分类下的条目
     */
    fun listEntries(category: String): String {
        val categoryDir = File(brainDir, category.lowercase())
        if (!categoryDir.exists()) return "分类不存在: $category"
        
        val files = categoryDir.listFiles()?.filter { it.extension == "md" } ?: emptyList()
        return if (files.isEmpty()) {
            "$category 分类下没有条目"
        } else {
            "$category 分类 (${files.size}条):\n${files.joinToString("\n") { "- ${it.nameWithoutExtension}" }}"
        }
    }
    
    /**
     * 删除条目
     */
    fun delete(category: String, name: String): String {
        val fileName = name.lowercase().replace(" ", "-").replace(Regex("[^a-z0-9-]"), "")
        val file = File(brainDir, "${category.lowercase()}/$fileName.md")
        
        return if (file.exists()) {
            file.delete()
            "✅ 已删除: $name"
        } else {
            "条目不存在: $name"
        }
    }
    
    companion object {
        private var instance: BrainSkill? = null
        
        fun getInstance(): BrainSkill {
            if (instance == null) {
                instance = BrainSkill()
            }
            return instance!!
        }
    }
}
