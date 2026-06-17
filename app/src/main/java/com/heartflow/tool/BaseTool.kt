package com.heartflow.tool

import org.json.JSONObject

/**
 * 工具基类，所有工具必须继承此类
 */
abstract class BaseTool {
    abstract fun getName(): String

    abstract fun getDescription(): String

    abstract fun getCategory(): ToolCategory

    open fun requiresConfirmation(): Boolean = false

    abstract fun getParameters(): JSONObject

    abstract fun execute(input: JSONObject, context: ToolContext): ToolResult

    fun toJson(): JSONObject {
        val function = JSONObject()
        function.put("name", getName())
        function.put("description", getDescription())
        function.put("parameters", getParameters())
        val wrapper = JSONObject()
        wrapper.put("type", "function")
        wrapper.put("function", function)
        return wrapper
    }
}
