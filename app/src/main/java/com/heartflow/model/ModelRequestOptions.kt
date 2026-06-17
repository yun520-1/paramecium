package com.heartflow.model

import org.json.JSONObject

/**
 * 基础工具接口
 * 所有工具类型都需要实现此接口
 */
interface BaseTool {
    /**
     * 获取工具名称
     */
    fun getName(): String

    /**
     * 获取工具描述
     */
    fun getDescription(): String

    /**
     * 获取参数的JSON Schema
     */
    fun getParametersSchema(): JSONObject

    /**
     * 转换为OpenAI Function Calling格式
     */
    fun toFunctionCallFormat(): JSONObject {
        return JSONObject().apply {
            put("type", "function")
            put("function", JSONObject().apply {
                put("name", getName())
                put("description", getDescription())
                put("parameters", getParametersSchema())
            })
        }
    }
}

/**
 * 推理强度配置
 */
object AiBehaviorSettings {
    const val REASONING_OFF = "off"
    const val REASONING_LOW = "low"
    const val REASONING_MEDIUM = "medium"
    const val REASONING_HIGH = "high"
    const val REASONING_MAX = "max"

    /**
     * 规范化推理强度值
     */
    fun normalizeReasoningEffort(effort: String?): String {
        return when (effort?.lowercase()) {
            "off", "low", "medium", "high", "max" -> effort.lowercase()
            else -> REASONING_MEDIUM
        }
    }
}

/**
 * 模型请求选项
 * 定义API调用的配置参数
 */
data class ModelRequestOptions(
    val reasoningEffort: String = AiBehaviorSettings.REASONING_MEDIUM,
    val preserveReasoning: Boolean = false,
    val tools: List<BaseTool> = emptyList()
) {
    companion object {
        /**
         * 默认请求选项
         */
        fun defaults(): ModelRequestOptions = ModelRequestOptions()
    }

    /**
     * 创建带工具的选项
     */
    fun withTools(tools: List<BaseTool>): ModelRequestOptions =
        copy(tools = tools)

    /**
     * 创建带推理强度的选项
     */
    fun withReasoningEffort(effort: String): ModelRequestOptions =
        copy(reasoningEffort = AiBehaviorSettings.normalizeReasoningEffort(effort))
}
