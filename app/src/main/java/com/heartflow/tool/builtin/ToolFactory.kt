package com.heartflow.tool.builtin

import com.heartflow.tool.BaseTool

/**
 * 工具工厂类
 * 提供创建各种工具实例的便捷方法
 */
object ToolFactory {
    /**
     * 创建所有内置工具
     */
    fun createAllTools(): List<BaseTool> = listOf(
        // READ 工具
        FileReadTool(),
        ListDirectoryTool(),
        GlobTool(),
        WebSearchToolImpl(),
        WebFetchToolImpl(),
        ImageUnderstandingToolImpl(),
        // WRITE 工具
        FileWriteTool(),
        FileEditTool(),
        FileDeleteTool(),
        // GENERATE 工具
        ImageGenerationToolImpl(),
        // SYSTEM 工具
        AgentToolImpl(),
        TodoUpdateToolImpl(),
        ShellExecuteToolImpl()
    )

    /**
     * 创建文件相关工具
     */
    fun createFileTools(): List<BaseTool> = listOf(
        FileReadTool(),
        FileWriteTool(),
        FileEditTool(),
        FileDeleteTool(),
        ListDirectoryTool(),
        GlobTool()
    )

    /**
     * 创建网络相关工具
     */
    fun createNetworkTools(): List<BaseTool> = listOf(
        WebSearchToolImpl(),
        WebFetchToolImpl()
    )

    /**
     * 创建系统工具
     */
    fun createSystemTools(): List<BaseTool> = listOf(
        AgentToolImpl(),
        TodoUpdateToolImpl(),
        ShellExecuteToolImpl()
    )

    /**
     * 创建多媒体工具
     */
    fun createMediaTools(): List<BaseTool> = listOf(
        ImageUnderstandingToolImpl(),
        ImageGenerationToolImpl()
    )

    /**
     * 按名称获取工具实例
     */
    fun getTool(name: String): BaseTool? = when (name) {
        "file_read" -> FileReadTool()
        "file_write" -> FileWriteTool()
        "file_edit" -> FileEditTool()
        "file_delete" -> FileDeleteTool()
        "list_dir" -> ListDirectoryTool()
        "glob" -> GlobTool()
        "agent" -> AgentToolImpl()
        "todo_update" -> TodoUpdateToolImpl()
        "web_search" -> WebSearchToolImpl()
        "web_fetch" -> WebFetchToolImpl()
        "shell_execute" -> ShellExecuteToolImpl()
        "image_generation" -> ImageGenerationToolImpl()
        "image_understanding" -> ImageUnderstandingToolImpl()
        else -> null
    }
}
