package com.heartflow.engine

import android.util.Log
import org.json.JSONObject
import org.json.JSONArray
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CopyOnWriteArrayList

/**
 * 工具权限级别
 */
enum class PermissionLevel {
    /** 自动执行 - 无需用户确认 */
    ALLOWED,
    /** 需要用户确认后执行 */
    REQUIRES_APPROVAL,
    /** 拒绝执行 */
    DENIED
}

/**
 * 工具分类
 */
enum class ToolCategory(val displayName: String) {
    CORE("核心"),
    FILE("文件"),
    WEB("网络"),
    MEMORY("记忆"),
    SKILL("技能"),
    MEDIA("媒体")
}

/**
 * 工具定义数据类
 * 包含工具的完整元数据：名称、描述、参数Schema、所需权限、分类等
 */
data class ToolDefinition(
    val name: String,
    val description: String,
    val parameters: JSONObject = JSONObject(),
    val permissions: Set<PermissionLevel> = setOf(PermissionLevel.ALLOWED),
    val category: ToolCategory = ToolCategory.CORE,
    val version: String = "1.0.0",
    val author: String = "HeartFlow",
    val tags: List<String> = emptyList(),
    val isEnabled: Boolean = true,
    val metadata: Map<String, Any> = emptyMap(),
    /** 可选的执行逻辑 — 桥接自 data.ToolRegistry */
    val executor: ((Map<String, Any>) -> String)? = null
) {
    /**
     * 执行工具逻辑（如果有 executor 的话）
     */
    fun execute(args: Map<String, Any>): String {
        return executor?.invoke(args) ?: "错误: 工具「${name}」没有绑定执行逻辑，仅作为定义元数据存在"
    }

    // 其余方法不变...
    fun validateParameters(args: Map<String, Any>): ValidationResult {
        val errors = mutableListOf<String>()

        // 检查必填参数
        val required = parameters.optJSONArray("required") ?: JSONArray()
        for (i in 0 until required.length()) {
            val paramName = required.getString(i)
            if (!args.containsKey(paramName)) {
                errors.add("缺少必填参数: $paramName")
            }
        }

        // 检查参数类型
        val properties = parameters.optJSONObject("properties") ?: JSONObject()
        for ((paramName, paramValue) in args) {
            val paramSchema = properties.optJSONObject(paramName)
            if (paramSchema != null) {
                val expectedType = paramSchema.optString("type")
                val actualType = getJsonType(paramValue)
                if (expectedType != actualType && expectedType != "any") {
                    errors.add("参数 $paramName 类型错误: 期望 $expectedType, 实际 $actualType")
                }
            }
        }

        return if (errors.isEmpty()) {
            ValidationResult(true, emptyList())
        } else {
            ValidationResult(false, errors)
        }
    }

    /**
     * 获取参数的JSON Schema描述
     */
    fun getParameterSchema(): JSONObject {
        return parameters
    }

    /**
     * 转换为OpenAI Function Calling格式
     */
    fun toFunctionCallFormat(): Map<String, Any> {
        return mapOf(
            "type" to "function",
            "function" to mapOf(
                "name" to name,
                "description" to description,
                "parameters" to parameters.toString()
            )
        )
    }

    private fun getJsonType(value: Any): String {
        return when (value) {
            is String -> "string"
            is Number -> {
                if (value is Double || value is Float) "number" else "integer"
            }
            is Boolean -> "boolean"
            is JSONObject -> "object"
            is JSONArray -> "array"
            is List<*> -> "array"
            is Map<*, *> -> "object"
            else -> "unknown"
        }
    }
}

/**
 * 参数验证结果
 */
data class ValidationResult(
    val isValid: Boolean,
    val errors: List<String>
)

/**
 * 工具执行上下文
 */
data class ToolExecutionContext(
    val toolName: String,
    val args: Map<String, Any>,
    val timestamp: Long = System.currentTimeMillis(),
    val requestId: String = java.util.UUID.randomUUID().toString()
)

/**
 * 工具注册中心
 * 管理所有工具的注册、查询、执行权限控制
 */
object ToolRegistry {
    private const val TAG = "ToolRegistry"

    // 工具存储
    private val tools = ConcurrentHashMap<String, ToolDefinition>()
    private val toolCategories = ConcurrentHashMap<ToolCategory, MutableList<String>>()

    // 权限配置
    private val permissionConfig = ConcurrentHashMap<String, PermissionLevel>()

    // 执行历史
    private val executionHistory = CopyOnWriteArrayList<ToolExecutionContext>()

    // 监听器
    private val listeners = mutableListOf<ToolRegistryListener>()

    /**
     * 初始化注册中心 — 工具注册由 data.ToolRegistry 通过 syncToEngine() 完成
     */
    fun init() {
        Log.d(TAG, "工具注册中心已就绪（工具由 data.ToolRegistry 同步注入）")
    }

    /**
     * 注册工具
     */
    fun register(tool: ToolDefinition): Boolean {
        if (tools.containsKey(tool.name)) {
            Log.w(TAG, "工具已存在: ${tool.name}")
            return false
        }

        tools[tool.name] = tool

        // 更新分类索引
        toolCategories.getOrPut(tool.category) { mutableListOf() }.add(tool.name)

        // 设置默认权限
        if (!permissionConfig.containsKey(tool.name)) {
            permissionConfig[tool.name] = tool.permissions.firstOrNull() ?: PermissionLevel.ALLOWED
        }

        Log.d(TAG, "注册工具: ${tool.name} (${tool.category.displayName})")
        notifyListeners { it.onToolRegistered(tool) }
        return true
    }

    /**
     * 注销工具
     */
    fun unregister(toolName: String): Boolean {
        val tool = tools.remove(toolName) ?: return false

        // 从分类索引中移除
        toolCategories[tool.category]?.remove(toolName)

        // 移除权限配置
        permissionConfig.remove(toolName)

        Log.d(TAG, "注销工具: $toolName")
        notifyListeners { it.onToolUnregistered(tool) }
        return true
    }

    /**
     * 获取工具定义
     */
    fun get(toolName: String): ToolDefinition? {
        return tools[toolName]
    }

    /** get 的别名，与 data.ToolRegistry 接口一致 */
    fun getByName(toolName: String): ToolDefinition? = get(toolName)

    /**
     * 获取所有已注册工具
     */
    fun getAll(): List<ToolDefinition> {
        return tools.values.toList()
    }

    /**
     * 获取指定分类的工具
     */
    fun getByCategory(category: ToolCategory): List<ToolDefinition> {
        val toolNames = toolCategories[category] ?: emptyList()
        return toolNames.mapNotNull { tools[it] }
    }

    /**
     * 搜索工具
     */
    fun search(query: String): List<ToolDefinition> {
        val lowerQuery = query.lowercase()
        return tools.values.filter { tool ->
            tool.name.lowercase().contains(lowerQuery) ||
            tool.description.lowercase().contains(lowerQuery) ||
            tool.tags.any { it.lowercase().contains(lowerQuery) }
        }
    }

    /**
     * 获取所有已启用的工具
     */
    fun getEnabled(): List<ToolDefinition> {
        return tools.values.filter { it.isEnabled }
    }

    /**
     * 设置工具启用状态
     */
    fun setEnabled(toolName: String, enabled: Boolean): Boolean {
        val tool = tools[toolName] ?: return false
        tools[toolName] = tool.copy(isEnabled = enabled)
        return true
    }

    /**
     * 检查工具权限
     */
    fun checkPermission(toolName: String): PermissionLevel {
        return permissionConfig[toolName] ?: PermissionLevel.ALLOWED
    }

    /**
     * 设置工具权限
     */
    fun setPermission(toolName: String, permission: PermissionLevel): Boolean {
        if (!tools.containsKey(toolName)) return false
        permissionConfig[toolName] = permission
        Log.d(TAG, "设置工具权限: $toolName -> $permission")
        return true
    }

    /**
     * 请求工具执行批准
     * 返回true表示已批准，false表示被拒绝
     */
    fun requestApproval(toolName: String, args: Map<String, Any>): Boolean {
        val permission = checkPermission(toolName)
        return when (permission) {
            PermissionLevel.ALLOWED -> true
            PermissionLevel.REQUIRES_APPROVAL -> {
                // 在实际应用中，这里应该显示确认对话框
                // 目前默认返回true
                Log.d(TAG, "请求批准工具执行: $toolName")
                true
            }
            PermissionLevel.DENIED -> {
                Log.w(TAG, "工具执行被拒绝: $toolName")
                false
            }
        }
    }

    /**
     * 验证工具参数
     */
    fun validateToolParameters(toolName: String, args: Map<String, Any>): ValidationResult {
        val tool = tools[toolName] ?: return ValidationResult(false, listOf("工具不存在: $toolName"))
        return tool.validateParameters(args)
    }

    /**
     * 获取工具的函数调用格式（兼容OpenAI）
     */
    fun getFunctionCallFormat(): List<Map<String, Any>> {
        return getEnabled().map { it.toFunctionCallFormat() }
    }

    /**
     * 获取工具的JSON数组格式（用于API请求）
     */
    fun toJsonArray(tools: List<com.heartflow.model.BaseTool>): JSONArray {
        return JSONArray().apply {
            for (tool in tools) {
                // BaseTool.toFunctionCallFormat() 返回 JSONObject
                put(tool.toFunctionCallFormat())
            }
        }
    }

    /**
     * 获取工具描述（用于系统提示）
     */
    fun getToolDescriptions(): String {
        return getEnabled().joinToString("\n") { tool ->
            "- ${tool.name}: ${tool.description}"
        }
    }

    /**
     * 记录工具执行
     */
    fun recordExecution(context: ToolExecutionContext) {
        executionHistory.add(context)
        // 保持历史记录在合理范围内
        if (executionHistory.size > 1000) {
            executionHistory.subList(0, 200).clear()
        }
    }

    /**
     * 获取执行历史
     */
    fun getExecutionHistory(limit: Int = 50): List<ToolExecutionContext> {
        return executionHistory.takeLast(limit)
    }

    /**
     * 添加监听器
     */
    fun addListener(listener: ToolRegistryListener) {
        listeners.add(listener)
    }

    /**
     * 移除监听器
     */
    fun removeListener(listener: ToolRegistryListener) {
        listeners.remove(listener)
    }

    private fun notifyListeners(action: (ToolRegistryListener) -> Unit) {
        listeners.forEach { listener ->
            try {
                action(listener)
            } catch (e: Exception) {
                Log.e(TAG, "通知监听器失败", e)
            }
        }
    }

}

/**
 * 工具注册中心监听器
 */
interface ToolRegistryListener {
    fun onToolRegistered(tool: ToolDefinition) {}
    fun onToolUnregistered(tool: ToolDefinition) {}
    fun onToolEnabled(toolName: String, enabled: Boolean) {}
    fun onPermissionChanged(toolName: String, permission: PermissionLevel) {}
}