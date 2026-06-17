package com.heartflow.skills

import org.json.JSONObject
import java.io.BufferedReader
import java.io.InputStreamReader
import java.net.HttpURLConnection
import java.net.URL
import java.text.SimpleDateFormat
import java.util.*

/**
 * HermesSkillIntegrator - 集成所有Hermes核心技能
 */
class HermesSkillIntegrator {
    
    /**
     * 获取所有集成的skills
     */
    fun getAllIntegratedSkills(): Map<String, List<SkillInfo>> {
        return mapOf(
            "搜索与研究" to listOf(
                SkillInfo("baidu-search", "百度AI搜索", "支持网页搜索、百度百科、秒懂百科、AI智能生成"),
                SkillInfo("web-search", "网络搜索", "使用DuckDuckGo搜索网页、新闻、图片、视频"),
                SkillInfo("deep-research-pro", "深度研究", "多源深度研究，生成引用报告"),
                SkillInfo("cn-web-search", "中文网页搜索", "中文搜索引擎集成"),
                SkillInfo("ask-search", "问答搜索", "智能问答搜索")
            ),
            "代码与开发" to listOf(
                SkillInfo("code-review-and-quality", "代码审查", "五维度代码审查：正确性、可读性、架构、安全、性能"),
                SkillInfo("software-development", "软件开发", "软件开发最佳实践"),
                SkillInfo("debugging", "调试", "调试技巧和方法"),
                SkillInfo("devops", "DevOps", "DevOps实践和工具"),
                SkillInfo("git", "Git", "Git版本控制"),
                SkillInfo("github", "GitHub", "GitHub操作和API")
            ),
            "文档处理" to listOf(
                SkillInfo("document-pro", "文档处理", "PDF、DOCX、PPT、Excel文档处理"),
                SkillInfo("note-taking", "笔记", "笔记管理和组织"),
                SkillInfo("pdf", "PDF处理", "PDF创建、编辑、转换")
            ),
            "AI与创意" to listOf(
                SkillInfo("ai", "AI工具", "AI相关工具和集成"),
                SkillInfo("ai-image-generation", "AI图像生成", "AI图像生成工具"),
                SkillInfo("ai-video-generation", "AI视频生成", "AI视频生成工具"),
                SkillInfo("creative", "创意", "创意写作和设计"),
                SkillInfo("diagramming", "图表", "图表和流程图创建")
            ),
            "数据与分析" to listOf(
                SkillInfo("data", "数据", "数据处理和分析"),
                SkillInfo("data-science", "数据科学", "数据科学工具和方法"),
                SkillInfo("cognitive", "认知", "认知科学和心理学"),
                SkillInfo("cognitive-biases", "认知偏见", "行为经济学原理")
            ),
            "记忆与知识" to listOf(
                SkillInfo("memory", "记忆", "记忆管理系统"),
                SkillInfo("research", "研究", "研究方法和工具"),
                SkillInfo("academic-deep-research", "学术研究", "学术论文搜索和分析")
            ),
            "工具与集成" to listOf(
                SkillInfo("email", "邮件", "邮件处理"),
                SkillInfo("media", "媒体", "媒体处理工具"),
                SkillInfo("productivity", "生产力", "生产力工具"),
                SkillInfo("social-media", "社交媒体", "社交媒体集成"),
                SkillInfo("feeds", "信息流", "RSS和信息流管理")
            ),
            "平台与服务" to listOf(
                SkillInfo("alipay-aipay-product-intro", "支付宝介绍", "支付宝AI支付产品介绍"),
                SkillInfo("alipay-authenticate-wallet", "支付宝认证", "支付宝钱包开通和授权"),
                SkillInfo("alipay-pay-for-service", "支付宝支付", "支付宝支付处理"),
                SkillInfo("baoyu-url-to-markdown", "URL转Markdown", "将网页转换为Markdown格式")
            ),
            "安全与合规" to listOf(
                SkillInfo("security-auditor", "安全审计", "安全审计工具"),
                SkillInfo("compliance-review", "合规审查", "合规性审查"),
                SkillInfo("red-teaming", "红队", "渗透测试和安全评估")
            )
        )
    }
    
    /**
     * 搜索skills
     */
    fun searchSkills(query: String): List<SkillInfo> {
        val allSkills = getAllIntegratedSkills().values.flatten()
        return allSkills.filter { 
            it.name.contains(query, ignoreCase = true) || 
            it.description.contains(query, ignoreCase = true) 
        }
    }
    
    /**
     * 获取skill详情
     */
    fun getSkillDetail(skillName: String): String {
        val allSkills = getAllIntegratedSkills().values.flatten()
        val skill = allSkills.find { it.name == skillName }
        
        return if (skill != null) {
            buildString {
                appendLine("📦 ${skill.name}")
                appendLine("═══════════════════════════════════════")
                appendLine("名称: ${skill.name}")
                appendLine("描述: ${skill.description}")
                appendLine()
                appendLine("安装命令:")
                appendLine("npx skills add https://github.com/Yee-h/agent_config --skill ${skill.name}")
                appendLine()
                appendLine("使用方式:")
                appendLine("在对话中直接使用相关功能即可")
            }
        } else {
            "❌ 未找到技能: $skillName"
        }
    }
    
    /**
     * 获取所有skills列表
     */
    fun listAllSkills(): String {
        val categories = getAllIntegratedSkills()
        val totalCount = categories.values.sumOf { it.size }
        
        return buildString {
            appendLine("📋 Hermes技能库 ($totalCount 个技能)")
            appendLine("═══════════════════════════════════════")
            categories.forEach { (category, skills) ->
                appendLine()
                appendLine("📁 $category (${skills.size}个)")
                skills.forEach { skill ->
                    appendLine("  • ${skill.name}: ${skill.description.take(50)}")
                }
            }
        }
    }
    
    /**
     * 获取推荐的skills
     */
    fun getRecommendedSkills(taskType: String): List<SkillInfo> {
        val allSkills = getAllIntegratedSkills()
        
        return when (taskType.lowercase()) {
            "搜索", "search" -> allSkills["搜索与研究"] ?: emptyList()
            "代码", "code", "开发" -> allSkills["代码与开发"] ?: emptyList()
            "文档", "document" -> allSkills["文档处理"] ?: emptyList()
            "ai", "创意" -> allSkills["AI与创意"] ?: emptyList()
            "数据", "data" -> allSkills["数据与分析"] ?: emptyList()
            "记忆", "memory" -> allSkills["记忆与知识"] ?: emptyList()
            "工具", "tool" -> allSkills["工具与集成"] ?: emptyList()
            "安全", "security" -> allSkills["安全与合规"] ?: emptyList()
            else -> allSkills.values.flatten().take(10)
        }
    }
    
    data class SkillInfo(
        val name: String,
        val displayName: String,
        val description: String
    )
}

// 单例实例
val hermesSkillIntegrator = HermesSkillIntegrator()
