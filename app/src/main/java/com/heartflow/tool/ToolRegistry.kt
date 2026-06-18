package com.heartflow.tool

import com.heartflow.tool.builtin.*

/**
 * 工具注册表
 * 管理所有可用工具的注册和获取
 */
class ToolRegistry {

    private val tools = mutableMapOf<String, BaseTool>()

    init {
        registerBuiltinTools()
    }

    /**
     * 注册所有内置工具
     */
    private fun registerBuiltinTools() {
        // READ 工具
        register(FileReadTool())
        register(ListDirectoryTool())
        register(GlobTool())
        register(WebSearchToolImpl())
        register(WebFetchToolImpl())
        register(BrowserTool())        // 完整浏览器引擎（GeckoView）
        register(ImageUnderstandingToolImpl())

        // WRITE 工具
        register(FileWriteTool())
        register(FileEditTool())
        register(FileDeleteTool())

        // GENERATE 工具
        register(ImageGenerationToolImpl())

        // SYSTEM 工具
        register(AgentToolImpl())
        register(TodoUpdateToolImpl())
        register(ShellExecuteToolImpl())
    }

    /**
     * 初始化（供外部调用）
     */
    fun init(context: android.content.Context) {
        // 已在 init 中注册内置工具
    }

    companion object {
        private const val CUSTOM_AGENT_PREFIX = "agentx_"
        private const val CUSTOM_MCP_PREFIX = "mcpx_"

        @Volatile
        private var instance: ToolRegistry? = null

        fun getInstance(): ToolRegistry {
            return instance ?: synchronized(this) {
                instance ?: ToolRegistry().also { instance = it }
            }
        }

        /**
         * 获取所有已注册的工具
         */
        fun getAll(): List<BaseTool> = getInstance().getAllTools()

        /**
         * 根据名称获取工具
         */
        fun getByName(name: String): BaseTool? = getInstance().getTool(name)

        /**
         * 获取工具名称列表
         */
        fun getToolNames(): List<String> = getInstance().getAllTools().map { it.getName() }

        /**
         * 获取函数调用格式
         */
        fun getFunctionCallFormat(): String = "function"

        /**
         * 安装技能
         */
        fun installSkill(url: String): Boolean {
            // 占位实现
            return false
        }

        /**
         * 获取已安装技能名称
         */
        fun getInstalledSkillNames(): List<String> = emptyList()
    }

    /**
     * 注册工具
     */
    fun register(tool: BaseTool?) {
        if (tool != null) {
            tools[tool.getName()] = tool
        }
    }

    /**
     * 根据名称获取工具
     */
    fun getTool(name: String): BaseTool? {
        return tools[name]
    }

    /**
     * 根据名称获取工具（get别名）
     */
    fun get(name: String): BaseTool? = getTool(name)

    /**
     * 获取所有已注册的工具
     */
    fun getAllTools(): List<BaseTool> {
        return tools.values.toList()
    }

    /**
     * 根据名称集合获取工具列表
     */
    fun getByNameSet(names: Set<String>?): List<BaseTool> {
        if (names == null || names.isEmpty()) return emptyList()
        return tools.values.filter { names.contains(it.getName()) }
    }

    /**
     * 获取工具数量
     */
    fun size(): Int = tools.size

    /**
     * 移除指定名称的工具
     */
    fun unregister(name: String): BaseTool? {
        return tools.remove(name)
    }

    /**
     * 清空所有工具
     */
    fun clear() {
        tools.clear()
    }

    /**
     * 检查工具是否存在
     */
    fun has(name: String): Boolean {
        return tools.containsKey(name)
    }

    /**
     * 检查是否为扩展工具名称
     */
    fun isExtensionToolName(name: String?): Boolean {
        return name != null && (name.startsWith(CUSTOM_AGENT_PREFIX) || name.startsWith(CUSTOM_MCP_PREFIX))
    }

    /**
     * 检查是否为自定义 Agent 工具名称
     */
    fun isCustomAgentToolName(name: String?): Boolean {
        return name != null && name.startsWith(CUSTOM_AGENT_PREFIX)
    }

    /**
     * 检查是否为自定义 MCP 工具名称
     */
    fun isCustomMcpToolName(name: String?): Boolean {
        return name != null && name.startsWith(CUSTOM_MCP_PREFIX)
    }

    /**
     * 生成安全的工具名称部分
     */
    fun safeToolNamePart(value: String?, fallback: String, maxLength: Int): String {
        val raw = value?.trim() ?: ""
        val builder = StringBuilder()
        for (i in raw.indices) {
            if (builder.length >= maxLength) break
            val ch = raw[i]
            if ((ch in 'a'..'z') || (ch in 'A'..'Z') || (ch in '0'..'9') || ch == '_' || ch == '-') {
                builder.append(ch)
            } else {
                builder.append('_')
            }
        }
        var clean = trimUnderscore(builder.toString())
        if (clean.isEmpty()) {
            clean = fallback
        }
        val first = clean[0]
        if (!((first in 'a'..'z') || (first in 'A'..'Z'))) {
            clean = fallback + "_" + clean
        }
        return clean
    }

    /**
     * 去除下划线
     */
    private fun trimUnderscore(value: String): String {
        var text = value
        while (text.contains("__")) {
            text = text.replace("__", "_")
        }
        var start = 0
        var end = text.length
        while (start < end && text[start] == '_') {
            start++
        }
        while (end > start && text[end - 1] == '_') {
            end--
        }
        return text.substring(start, end)
    }

    /**
     * 生成短哈希值
     */
    fun shortHash(value: String?): String {
        var hash = 5381L
        val text = value ?: ""
        for (i in text.indices) {
            hash = (hash * 33L + text[i].code) % 2147483647L
        }
        return kotlin.math.abs(hash).toString(36)
    }
}
