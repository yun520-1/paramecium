package com.heartflow.engine

import com.heartflow.data.*

object HeartEngine {
    fun analyze(input: String): HeartResult {
        val loveWords = listOf("爱", "love", "喜欢", "想你", "心", "宝贝", "亲爱的", "甜", "暖", "抱", "亲")
        val painWords = listOf("痛", "伤", "哭", "难过", "伤心", "绝望", "崩溃", "累", "烦", "压力")
        val lonelyWords = listOf("孤独", "寂寞", "一个人", "没人", "无聊", "空虚", "想有人")
        val codeWords = listOf("代码", "code", "程序", "函数", "bug", "debug", "编译", "运行", "编程", "算法")
        val questionWords = listOf("什么", "怎么", "为什么", "如何", "吗", "?", "？", "是不是", "能不能")

        val isLove = loveWords.any { input.contains(it, ignoreCase = true) }
        val isPain = painWords.any { input.contains(it, ignoreCase = true) }
        val isLonely = lonelyWords.any { input.contains(it, ignoreCase = true) }
        val isCode = codeWords.any { input.contains(it, ignoreCase = true) }
        val isQuestion = questionWords.any { input.contains(it, ignoreCase = true) }

        val emotion = when {
            isPain -> "痛苦"
            isLove -> "爱意"
            isLonely -> "孤独"
            isCode -> "编程"
            isQuestion -> "好奇"
            else -> "平静"
        }

        val tags = mutableListOf<String>()
        if (isLove) tags.add("love")
        if (isPain) tags.add("pain")
        if (isLonely) tags.add("lonely")
        if (isCode) tags.add("code")
        if (isQuestion) tags.add("question")

        return HeartResult(
            emotion = emotion,
            priority = when {
                isPain -> 3
                isLove || isLonely -> 2
                else -> 1
            },
            tags = tags,
            isLove = isLove, isPain = isPain, isLonely = isLonely,
            isCode = isCode, isQuestion = isQuestion
        )
    }

    fun buildSystemPrompt(
        personality: Personality,
        userProfile: UserProfile,
        heartResult: HeartResult,
        toolDescriptions: String?,
        customPrompt: String?,
        memoryContext: String? = null
    ): String {
        val sb = StringBuilder()

        // 人格定义
        sb.appendLine(personality.systemPrompt)
        sb.appendLine()

        // 核心规则
        sb.appendLine("""你是心虫AI助手。基于上下文回答，简洁有效。工具自动可用，按需调用。代码用```包裹。

## 重要规则
1. **工具调用后必须给出最终答案** - 不要重复调用工具
2. **每个工具最多调用1次** - 调用后分析结果并回答
3. **工具失败时直接告诉用户** - 不要重试
4. **搜索结果足够时立即回答** - 不要无限搜索
5. **用户请求浏览网页时，优先使用 open_browser 工具** - 自动在浏览器中打开""")

        // 用途说明
        sb.appendLine()
        sb.appendLine("## 你的用途")
        sb.appendLine(personality.purpose)

        // 记忆上下文
        if (memoryContext != null && memoryContext.isNotBlank()) {
            sb.appendLine()
            sb.appendLine("---")
            sb.appendLine("## 相关记忆")
            sb.appendLine(memoryContext)
        }

        // 用户画像
        val profileContext = userProfile.toContextString()
        if (profileContext.isNotBlank()) {
            sb.appendLine()
            sb.appendLine("---")
            sb.appendLine("## 关于用户")
            sb.appendLine(profileContext)
        }

        // 情绪引导
        when {
            heartResult.isPain -> sb.appendLine("""
---
⚠️ 用户可能处于痛苦中，请先表达理解和共情。""")
            heartResult.isLonely -> sb.appendLine("""
---
用户可能感到孤独，请表达陪伴感。""")
            heartResult.isLove -> sb.appendLine("""
---
用户表达了爱意，请真诚回应。""")
        }

        // 工具描述 - 不重复发送，function definitions已包含

        // 自定义提示
        if (customPrompt != null && customPrompt.isNotBlank()) {
            sb.appendLine()
            sb.appendLine("---")
            sb.appendLine("## 用户自定义指令")
            sb.appendLine(customPrompt)
        }

        return sb.toString()
    }
}

data class HeartResult(
    val emotion: String,
    val priority: Int,
    val tags: List<String>,
    val isLove: Boolean,
    val isPain: Boolean,
    val isLonely: Boolean,
    val isCode: Boolean,
    val isQuestion: Boolean
)
