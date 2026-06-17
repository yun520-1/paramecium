package com.heartflow.model

import org.json.JSONArray
import org.json.JSONObject
import com.heartflow.data.AgentTool
import com.heartflow.data.ToolParameter

/**
 * 工具适配器
 * 将 AgentTool 适配到 BaseTool 接口
 */
class ToolAdapter(
    private val agentTool: AgentTool
) : BaseTool {

    override fun getName(): String = agentTool.name

    override fun getDescription(): String = agentTool.description

    /**
     * 获取参数的JSON Schema
     * 注意：AgentTool 没有 category 字段，返回默认分类
     */
    override fun getParametersSchema(): JSONObject {
        val properties = JSONObject()
        val required = JSONArray()

        agentTool.parameters.forEach { param: ToolParameter ->
            properties.put(param.name, JSONObject().apply {
                put("type", param.type)
                put("description", param.description)
            })
            if (param.required) {
                required.put(param.name)
            }
        }

        return JSONObject().apply {
            put("type", "object")
            put("properties", properties)
            if (required.length() > 0) {
                put("required", required)
            }
        }
    }

    override fun toFunctionCallFormat(): JSONObject {
        return JSONObject().apply {
            put("type", "function")
            put("function", JSONObject().apply {
                put("name", agentTool.name)
                put("description", agentTool.description)
                put("parameters", getParametersSchema())
            })
        }
    }
}
