package com.heartflow.skills

import java.io.File

/**
 * CodeReview Skill - 代码审查
 * 参考Claude的code-review skill
 */
class CodeReviewSkill {
    
    /**
     * 审查代码文件
     */
    fun reviewFile(filePath: String): String {
        val file = File(filePath)
        if (!file.exists()) return "文件不存在: $filePath"
        
        val content = file.readText(Charsets.UTF_8)
        val lines = content.lines()
        
        val issues = mutableListOf<String>()
        val suggestions = mutableListOf<String>()
        
        // 检查行数
        if (lines.size > 500) {
            issues.add("⚠️ 文件过长 (${lines.size}行)，建议拆分")
        }
        
        // 检查函数长度
        var currentFunction = ""
        var functionLines = 0
        for ((index, line) in lines.withIndex()) {
            if (line.contains("fun ") || line.contains("function ") || line.contains("def ")) {
                if (functionLines > 50) {
                    issues.add("⚠️ 函数 '$currentFunction' 过长 (${functionLines}行)")
                }
                currentFunction = line.trim().take(50)
                functionLines = 0
            }
            functionLines++
        }
        
        // 检查注释
        val commentLines = lines.filter { it.trim().startsWith("//") || it.trim().startsWith("#") || it.trim().startsWith("/*") }
        if (commentLines.size < lines.size * 0.1) {
            suggestions.add("💡 注释较少，建议增加注释")
        }
        
        // 检查TODO
        val todoLines = lines.filter { it.contains("TODO") || it.contains("FIXME") || it.contains("HACK") }
        if (todoLines.isNotEmpty()) {
            suggestions.add("📝 发现 ${todoLines.size} 个TODO/FIXME")
        }
        
        // 检查硬编码
        val hardcodedStrings = lines.filter { it.contains("\"http://") || it.contains("\"https://") }
        if (hardcodedStrings.isNotEmpty()) {
            issues.add("⚠️ 发现硬编码URL，建议使用配置")
        }
        
        // 检查魔法数字
        val magicNumbers = lines.filter { line ->
            line.contains(Regex("\\b\\d{3,}\\b")) && !line.contains("//") && !line.contains("val ") && !line.contains("const ")
        }
        if (magicNumbers.isNotEmpty()) {
            suggestions.add("💡 发现魔法数字，建议定义常量")
        }
        
        // 生成报告
        return buildString {
            appendLine("📋 代码审查报告: ${file.name}")
            appendLine("═══════════════════════════════════════")
            appendLine()
            appendLine("📊 基本信息:")
            appendLine("  文件: $filePath")
            appendLine("  行数: ${lines.size}")
            appendLine("  大小: ${file.length()} bytes")
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
                appendLine("✅ 代码质量良好，未发现问题")
            }
        }
    }
    
    /**
     * 审查代码片段
     */
    fun reviewCode(code: String, language: String = "kotlin"): String {
        val lines = code.lines()
        val issues = mutableListOf<String>()
        val suggestions = mutableListOf<String>()
        
        // 通用检查
        if (lines.size > 100) {
            suggestions.add("💡 代码片段较长，建议拆分")
        }
        
        // 检查空行
        val emptyLines = lines.count { it.isBlank() }
        if (emptyLines > lines.size * 0.3) {
            suggestions.add("💡 空行较多，建议清理")
        }
        
        // 检查缩进
        val inconsistentIndent = lines.any { line ->
            line.isNotBlank() && (line.startsWith("\t  ") || line.startsWith("  \t"))
        }
        if (inconsistentIndent) {
            issues.add("⚠️ 缩进不一致")
        }
        
        // 生成报告
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
    
    companion object {
        private var instance: CodeReviewSkill? = null
        
        fun getInstance(): CodeReviewSkill {
            if (instance == null) {
                instance = CodeReviewSkill()
            }
            return instance!!
        }
    }
}
