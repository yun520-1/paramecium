package com.heartflow.tool

import android.util.Log
import org.json.JSONArray
import org.json.JSONObject

/**
 * 工具调用文本解析器
 * 用于解析和验证工具调用
 */
class ToolCallTextParser {
    companion object {
        private const val TAG = "ToolCallTextParser"

        // 工具调用正则模式
        private val JSON_TOOL_CALL_PATTERN = Regex(
            """\{[\s\S]*?"tool_call"[\s\S]*?\}|\{[\s\S]*?"name"[\s\S]*?"arguments"[\s\S]*?\}"""
        )

        private val NAME_PATTERN = Regex("""\"name\"\s*:\s*\"([^\"]+)\"""")
        private val ID_PATTERN = Regex("""\"id\"\s*:\s*\"([^\"]+)\"""")
        private val ARGUMENTS_PATTERN = Regex("""\"arguments\"\s*:\s*(\{[\s\S]*?\}|\"[^\"]*\")""")
    }

    /**
     * 解析工具调用文本
     */
    fun parse(text: String): List<ToolCall> {
        val toolCalls = mutableListOf<ToolCall>()

        // 方法1：尝试JSON解析
        tryParseJson(text, toolCalls)

        // 方法2：尝试正则匹配
        if (toolCalls.isEmpty()) {
            tryParseRegex(text, toolCalls)
        }

        // 方法3：尝试从markdown代码块解析
        if (toolCalls.isEmpty()) {
            tryParseMarkdown(text, toolCalls)
        }

        return toolCalls
    }

    /**
     * 验证工具调用格式
     */
    fun validate(toolCall: ToolCall): ValidationResult {
        val errors = mutableListOf<String>()

        if (toolCall.getId().isEmpty()) {
            errors.add("工具调用ID不能为空")
        }

        if (toolCall.getName().isEmpty()) {
            errors.add("工具名称不能为空")
        }

        // 验证参数是否为有效JSON
        try {
            val args = toolCall.getArguments()
            if (args.isNotEmpty() && args !in listOf("{}", "[]", "")) {
                JSONObject(args)
            }
        } catch (e: Exception) {
            errors.add("工具参数不是有效的JSON: ${e.message}")
        }

        return if (errors.isEmpty()) {
            ValidationResult.Valid
        } else {
            ValidationResult.Invalid(errors)
        }
    }

    /**
     * 修复常见的工具调用格式问题
     */
    fun fixCommonIssues(text: String): String {
        var fixed = text

        // 修复1：去除markdown代码块标记
        fixed = fixed.replace(Regex("""```(?:json)?\s*"""), "")
        fixed = fixed.replace(Regex("""```\s*$"""), "")

        // 修复2：去除首尾空白
        fixed = fixed.trim()

        // 修复3：确保参数是对象格式
        val nameMatch = NAME_PATTERN.find(fixed)
        val argsMatch = ARGUMENTS_PATTERN.find(fixed)
        if (nameMatch != null && argsMatch != null) {
            val rawArgs = argsMatch.groupValues[1]
            if (!rawArgs.startsWith("{")) {
                // 如果参数不是对象格式，尝试包裹为对象
                val value = rawArgs.trim('"')
                fixed = fixed.replace(
                    "\"arguments\"$rawArgs",
                    "\"arguments\":{\"value\":\"$value\"}"
                )
            }
        }

        return fixed
    }

    /**
     * 尝试从JSON解析工具调用
     */
    private fun tryParseJson(text: String, toolCalls: MutableList<ToolCall>) {
        try {
            // 尝试解析为完整数组
            val json = JSONArray(text)
            for (i in 0 until json.length()) {
                val obj = json.getJSONObject(i)
                parseSingleObject(obj)?.let { toolCalls.add(it) }
            }
            return
        } catch (_: Exception) {}

        try {
            // 尝试解析为单个对象
            val json = JSONObject(text)
            parseSingleObject(json)?.let { toolCalls.add(it) }
        } catch (_: Exception) {}
    }

    /**
     * 解析单个JSON对象
     */
    private fun parseSingleObject(obj: JSONObject): ToolCall? {
        return try {
            val id = obj.optString("id", "")
            val name = obj.optString("name", "")
            val arguments = obj.optString("arguments", "{}")

            if (name.isEmpty()) {
                // 检查tool_call格式
                val toolCall = obj.optJSONObject("tool_call") ?: return null
                val tcId = toolCall.optString("id", id)
                val function = toolCall.optJSONObject("function") ?: return null
                val tcName = function.optString("name", "")
                val tcArgs = function.optString("arguments", "{}")
                if (tcName.isEmpty()) return null
                ToolCall(tcId, tcName, tcArgs)
            } else {
                ToolCall(id, name, arguments)
            }
        } catch (_: Exception) {
            null
        }
    }

    /**
     * 尝试从正则匹配解析
     */
    private fun tryParseRegex(text: String, toolCalls: MutableList<ToolCall>) {
        val matches = JSON_TOOL_CALL_PATTERN.findAll(text)
        for (match in matches) {
            try {
                val json = JSONObject(match.value)
                parseSingleObject(json)?.let { toolCalls.add(it) }
            } catch (_: Exception) {
                // 尝试修复并重新解析
                val fixed = fixCommonIssues(match.value)
                try {
                    val json = JSONObject(fixed)
                    parseSingleObject(json)?.let { toolCalls.add(it) }
                } catch (_: Exception) {}
            }
        }
    }

    /**
     * 尝试从markdown代码块解析
     */
    private fun tryParseMarkdown(text: String, toolCalls: MutableList<ToolCall>) {
        val codeBlockPattern = Regex("""```(?:json)?\s*([\s\S]*?)```""")
        val matches = codeBlockPattern.findAll(text)

        for (match in matches) {
            val codeContent = match.groupValues[1]
            tryParseJson(codeContent, toolCalls)
        }

        // 如果代码块解析失败，尝试在整段文本中查找JSON对象
        if (toolCalls.isEmpty()) {
            val jsonObjectPattern = Regex("""\{[^{}]*"name"[^{}]*"arguments"[^{}]*\}""")
            val objMatches = jsonObjectPattern.findAll(text)
            for (objMatch in objMatches) {
                try {
                    val json = JSONObject(objMatch.value)
                    parseSingleObject(json)?.let { toolCalls.add(it) }
                } catch (_: Exception) {}
            }
        }
    }
}

/**
 * 验证结果
 */
sealed class ValidationResult {
    data object Valid : ValidationResult()
    data class Invalid(val errors: List<String>) : ValidationResult()

    fun isValid(): Boolean = this is Valid
}
