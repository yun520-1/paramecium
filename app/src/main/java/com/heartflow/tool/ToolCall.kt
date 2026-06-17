package com.heartflow.tool

/**
 * 工具调用数据类
 */
data class ToolCall(
    private val id: String,
    private val name: String,
    private val arguments: String
) {
    init {
        // 确保非空字段有默认值
    }

    fun getId(): String = id
    fun getName(): String = name
    fun getArguments(): String = arguments

    companion object {
        /**
         * 创建工具调用，自动生成 ID
         */
        fun create(name: String, arguments: String = "{}"): ToolCall {
            return ToolCall(
                id = if (name.isNotEmpty()) "tool_call_${System.currentTimeMillis()}" else "",
                name = name,
                arguments = arguments
            )
        }

        /**
         * 从参数字符串创建工具调用
         */
        fun fromArguments(name: String, argsMap: Map<String, Any>): ToolCall {
            val jsonArgs = org.json.JSONObject(argsMap).toString()
            return create(name, jsonArgs)
        }
    }
}
