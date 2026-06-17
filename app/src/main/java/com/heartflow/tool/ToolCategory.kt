package com.heartflow.tool

/**
 * 工具分类枚举
 */
enum class ToolCategory(val value: String) {
    READ("read"),
    GENERATE("generate"),
    WRITE("write"),
    SYSTEM("system"),
    BROWSER("browser"),
    BASIC("basic"),
    DOCUMENT("document"),
    HEARTBUG("heartbug"),
    MEMORY("memory"),
    KNOWLEDGE("knowledge"),
    NETWORK("network"),
    BAIDU("baidu"),
    GITHUB("github"),
    SKILL("skill"),
    DEV("dev"),
    APK("apk"),
    HERMES("hermes"),
    CUSTOM("custom");

    companion object {
        /**
         * 根据字符串值获取枚举
         */
        fun fromValue(value: String): ToolCategory? {
            return entries.find { it.value.equals(value, ignoreCase = true) }
        }

        /**
         * 根据中文分类名获取枚举（兼容旧分类）
         */
        fun fromString(category: String): ToolCategory {
            return when (category.lowercase()) {
                "浏览器" -> BROWSER
                "基础" -> BASIC
                "文档" -> DOCUMENT
                "心虫" -> HEARTBUG
                "记忆" -> MEMORY
                "知识库" -> KNOWLEDGE
                "网络" -> NETWORK
                "百度ai" -> BAIDU
                "github" -> GITHUB
                "技能" -> SKILL
                "开发" -> DEV
                "apk审计" -> APK
                "hermes" -> HERMES
                "通用" -> BASIC
                else -> fromValue(category) ?: CUSTOM
            }
        }
    }
}
