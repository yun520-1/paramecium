package com.heartflow.engine

import kotlinx.serialization.*
import kotlinx.serialization.json.*
import org.json.JSONObject
import org.json.JSONArray
import java.time.Instant
import java.util.UUID

/**
 * 任务状态枚举
 */
@Serializable
enum class TodoStatus {
    /** 待处理 */
    PENDING,
    /** 进行中 */
    IN_PROGRESS,
    /** 已完成 */
    COMPLETED,
    /** 已取消 */
    CANCELLED
}

/**
 * 任务数据类
 * 用于复杂任务分解和进度跟踪
 */
@Serializable
data class TodoItem(
    /** 任务唯一标识 */
    val id: String = UUID.randomUUID().toString(),
    /** 任务内容描述 */
    val content: String,
    /** 任务状态 */
    val status: TodoStatus = TodoStatus.PENDING,
    /** 创建时间（Unix时间戳） */
    val createdAt: Long = Instant.now().epochSecond,
    /** 更新时间（Unix时间戳） */
    val updatedAt: Long = Instant.now().epochSecond,
    /** 父任务ID（用于子任务） */
    val parentId: String? = null,
    /** 优先级（1-5，1最高） */
    val priority: Int = 3,
    /** 标签列表 */
    val tags: List<String> = emptyList(),
    /** 备注信息 */
    val notes: String? = null
)

/**
 * 任务统计信息
 */
@Serializable
data class TodoSummary(
    /** 总任务数 */
    val total: Int,
    /** 待处理任务数 */
    val pending: Int,
    /** 进行中任务数 */
    val inProgress: Int,
    /** 已完成任务数 */
    val completed: Int,
    /** 已取消任务数 */
    val cancelled: Int
)

/**
 * 任务管理器
 * 会话级内存任务列表，用于复杂任务分解和进度跟踪
 * 参考hermes-agent的todo_tool.py设计
 */
class TodoManager {
    // 任务存储（会话级内存）
    private val todos = mutableListOf<TodoItem>()
    
    // JSON序列化配置
    private val json = Json {
        prettyPrint = true
        ignoreUnknownKeys = true
        encodeDefaults = true
    }
    
    /**
     * 添加任务
     * @param content 任务内容
     * @param parentId 父任务ID（可选）
     * @param priority 优先级（1-5）
     * @param tags 标签列表
     * @return 创建的任务对象
     */
    fun add(
        content: String,
        parentId: String? = null,
        priority: Int = 3,
        tags: List<String> = emptyList()
    ): TodoItem {
        val item = TodoItem(
            content = content,
            parentId = parentId,
            priority = priority.coerceIn(1, 5),
            tags = tags
        )
        todos.add(item)
        return item
    }
    
    /**
     * 更新任务
     * @param id 任务ID
     * @param content 新内容（可选）
     * @param status 新状态（可选）
     * @param priority 新优先级（可选）
     * @param tags 新标签（可选）
     * @param notes 新备注（可选）
     * @return 更新后的任务对象，如果任务不存在返回null
     */
    fun update(
        id: String,
        content: String? = null,
        status: TodoStatus? = null,
        priority: Int? = null,
        tags: List<String>? = null,
        notes: String? = null
    ): TodoItem? {
        val index = todos.indexOfFirst { it.id == id }
        if (index == -1) return null
        
        val existing = todos[index]
        val updated = existing.copy(
            content = content ?: existing.content,
            status = status ?: existing.status,
            priority = priority?.coerceIn(1, 5) ?: existing.priority,
            tags = tags ?: existing.tags,
            notes = notes ?: existing.notes,
            updatedAt = Instant.now().epochSecond
        )
        
        todos[index] = updated
        return updated
    }
    
    /**
     * 移除任务
     * @param id 任务ID
     * @return 是否成功移除
     */
    fun remove(id: String): Boolean {
        return todos.removeAll { it.id == id }
    }
    
    /**
     * 获取所有任务
     * @param includeCompleted 是否包含已完成任务（默认false）
     * @return 任务列表
     */
    fun list(includeCompleted: Boolean = false): List<TodoItem> {
        return if (includeCompleted) {
            todos.toList()
        } else {
            todos.filter { it.status != TodoStatus.COMPLETED }
        }
    }
    
    /**
     * 搜索任务
     * @param query 搜索关键词
     * @return 匹配的任务列表
     */
    fun search(query: String): List<TodoItem> {
        val lowerQuery = query.lowercase()
        return todos.filter { item ->
            item.content.lowercase().contains(lowerQuery) ||
            item.tags.any { it.lowercase().contains(lowerQuery) } ||
            item.notes?.lowercase()?.contains(lowerQuery) == true
        }
    }
    
    /**
     * 获取任务摘要统计
     * @return 任务统计信息
     */
    fun getSummary(): TodoSummary {
        return TodoSummary(
            total = todos.size,
            pending = todos.count { it.status == TodoStatus.PENDING },
            inProgress = todos.count { it.status == TodoStatus.IN_PROGRESS },
            completed = todos.count { it.status == TodoStatus.COMPLETED },
            cancelled = todos.count { it.status == TodoStatus.CANCELLED }
        )
    }
    
    /**
     * 合并任务列表
     * @param newTodos 新任务列表
     * @param merge 是否增量合并（true=合并，false=全量替换）
     * @return 合并后的任务总数
     */
    fun mergeTodos(newTodos: List<TodoItem>, merge: Boolean = true): Int {
        if (merge) {
            // 增量更新：基于ID合并
            for (newItem in newTodos) {
                val existingIndex = todos.indexOfFirst { it.id == newItem.id }
                if (existingIndex >= 0) {
                    // 更新现有任务
                    todos[existingIndex] = newItem.copy(
                        updatedAt = Instant.now().epochSecond
                    )
                } else {
                    // 添加新任务
                    todos.add(newItem)
                }
            }
        } else {
            // 全量替换
            todos.clear()
            todos.addAll(newTodos)
        }
        
        return todos.size
    }
    
    /**
     * 格式化为系统提示注入格式
     * 用于上下文压缩后重建活跃任务
     * @return 格式化的任务字符串
     */
    fun formatForInjection(): String {
        val activeTasks = todos.filter { 
            it.status in listOf(TodoStatus.PENDING, TodoStatus.IN_PROGRESS) 
        }
        
        if (activeTasks.isEmpty()) {
            return "当前没有活跃任务。"
        }
        
        val summary = getSummary()
        val sb = StringBuilder()
        
        sb.appendLine("## 当前任务列表")
        sb.appendLine("总计: ${summary.total} | 待处理: ${summary.pending} | 进行中: ${summary.inProgress} | 已完成: ${summary.completed} | 已取消: ${summary.cancelled}")
        sb.appendLine()
        
        // 按优先级排序
        val sortedTasks = activeTasks.sortedBy { it.priority }
        
        for (task in sortedTasks) {
            val statusIcon = when (task.status) {
                TodoStatus.PENDING -> "○"
                TodoStatus.IN_PROGRESS -> "◉"
                TodoStatus.COMPLETED -> "●"
                TodoStatus.CANCELLED -> "✗"
            }
            
            val priorityLabel = when (task.priority) {
                1 -> "🔴 紧急"
                2 -> "🟠 高"
                3 -> "🟡 中"
                4 -> "🟢 低"
                5 -> "⚪ 最低"
                else -> "🟡 中"
            }
            
            sb.appendLine("$statusIcon [${task.id.take(8)}] ${task.content}")
            sb.appendLine("   优先级: $priorityLabel | 状态: ${task.status}")
            
            if (task.tags.isNotEmpty()) {
                sb.appendLine("   标签: ${task.tags.joinToString(", ")}")
            }
            
            if (task.notes != null) {
                sb.appendLine("   备注: ${task.notes}")
            }
            
            // 显示子任务
            val subTasks = todos.filter { it.parentId == task.id }
            if (subTasks.isNotEmpty()) {
                sb.appendLine("   子任务:")
                for (sub in subTasks) {
                    val subIcon = when (sub.status) {
                        TodoStatus.PENDING -> "  ○"
                        TodoStatus.IN_PROGRESS -> "  ◉"
                        TodoStatus.COMPLETED -> "  ●"
                        TodoStatus.CANCELLED -> "  ✗"
                    }
                    sb.appendLine("   $subIcon ${sub.content}")
                }
            }
            
            sb.appendLine()
        }
        
        return sb.toString()
    }
    
    /**
     * 序列化为JSON
     * @return JSON字符串
     */
    fun toJson(): String {
        return json.encodeToString(todos)
    }
    
    /**
     * 从JSON反序列化
     * @param jsonString JSON字符串
     * @return 反序列化后的任务列表
     */
    fun fromJson(jsonString: String): List<TodoItem> {
        return try {
            json.decodeFromString<List<TodoItem>>(jsonString)
        } catch (e: Exception) {
            emptyList()
        }
    }
    
    /**
     * 获取所有任务（包括已完成）
     * @return 所有任务列表
     */
    fun getAll(): List<TodoItem> {
        return todos.toList()
    }
    
    /**
     * 清空所有任务
     */
    fun clear() {
        todos.clear()
    }
    
    /**
     * 获取任务数量
     * @return 任务总数
     */
    fun size(): Int {
        return todos.size
    }
    
    /**
     * 检查任务是否存在
     * @param id 任务ID
     * @return 是否存在
     */
    fun exists(id: String): Boolean {
        return todos.any { it.id == id }
    }
    
    /**
     * 获取指定任务
     * @param id 任务ID
     * @return 任务对象，如果不存在返回null
     */
    fun get(id: String): TodoItem? {
        return todos.find { it.id == id }
    }
    
    /**
     * 获取指定状态的任务
     * @param status 任务状态
     * @return 匹配的任务列表
     */
    fun getByStatus(status: TodoStatus): List<TodoItem> {
        return todos.filter { it.status == status }
    }
    
    /**
     * 获取指定标签的任务
     * @param tag 标签名称
     * @return 匹配的任务列表
     */
    fun getByTag(tag: String): List<TodoItem> {
        return todos.filter { it.tags.contains(tag) }
    }
    
    /**
     * 获取指定父任务的子任务
     * @param parentId 父任务ID
     * @return 子任务列表
     */
    fun getSubTasks(parentId: String): List<TodoItem> {
        return todos.filter { it.parentId == parentId }
    }
    
    /**
     * 批量更新任务状态
     * @param ids 任务ID列表
     * @param status 新状态
     * @return 更新的任务数量
     */
    fun batchUpdateStatus(ids: List<String>, status: TodoStatus): Int {
        var count = 0
        for (id in ids) {
            val index = todos.indexOfFirst { it.id == id }
            if (index >= 0) {
                todos[index] = todos[index].copy(
                    status = status,
                    updatedAt = Instant.now().epochSecond
                )
                count++
            }
        }
        return count
    }
    
    /**
     * 获取任务树结构
     * @return 任务树（根任务 -> 子任务映射）
     */
    fun getTaskTree(): Map<TodoItem, List<TodoItem>> {
        val rootTasks = todos.filter { it.parentId == null }
        return rootTasks.associateWith { root ->
            todos.filter { it.parentId == root.id }
        }
    }
}

/**
 * TodoManager扩展函数：创建工具定义
 * 用于注册到ToolRegistry
 */
fun TodoManager.toToolDefinitions(): List<ToolDefinition> {
    return listOf(
        ToolDefinition(
            name = "todo_add",
            description = "添加新任务",
            parameters = JSONObject().apply {
                put("type", "object")
                put("properties", JSONObject().apply {
                    put("content", JSONObject().apply {
                        put("type", "string")
                        put("description", "任务内容描述")
                    })
                    put("priority", JSONObject().apply {
                        put("type", "integer")
                        put("description", "优先级(1-5，1最高)")
                    })
                    put("tags", JSONObject().apply {
                        put("type", "array")
                        put("items", JSONObject().apply {
                            put("type", "string")
                        })
                        put("description", "标签列表")
                    })
                    put("parent_id", JSONObject().apply {
                        put("type", "string")
                        put("description", "父任务ID（用于子任务）")
                    })
                })
                put("required", JSONArray().apply { put("content") })
            },
            category = ToolCategory.MEMORY,
            tags = listOf("todo", "task", "add")
        ),
        
        ToolDefinition(
            name = "todo_update",
            description = "更新任务状态或内容",
            parameters = JSONObject().apply {
                put("type", "object")
                put("properties", JSONObject().apply {
                    put("id", JSONObject().apply {
                        put("type", "string")
                        put("description", "任务ID")
                    })
                    put("content", JSONObject().apply {
                        put("type", "string")
                        put("description", "新内容")
                    })
                    put("status", JSONObject().apply {
                        put("type", "string")
                        put("description", "新状态: PENDING/IN_PROGRESS/COMPLETED/CANCELLED")
                    })
                    put("priority", JSONObject().apply {
                        put("type", "integer")
                        put("description", "新优先级(1-5)")
                    })
                    put("notes", JSONObject().apply {
                        put("type", "string")
                        put("description", "备注信息")
                    })
                })
                put("required", JSONArray().apply { put("id") })
            },
            category = ToolCategory.MEMORY,
            tags = listOf("todo", "task", "update")
        ),
        
        ToolDefinition(
            name = "todo_list",
            description = "列出所有任务",
            parameters = JSONObject().apply {
                put("type", "object")
                put("properties", JSONObject().apply {
                    put("include_completed", JSONObject().apply {
                        put("type", "boolean")
                        put("description", "是否包含已完成任务")
                    })
                })
            },
            category = ToolCategory.MEMORY,
            tags = listOf("todo", "task", "list")
        ),
        
        ToolDefinition(
            name = "todo_search",
            description = "搜索任务",
            parameters = JSONObject().apply {
                put("type", "object")
                put("properties", JSONObject().apply {
                    put("query", JSONObject().apply {
                        put("type", "string")
                        put("description", "搜索关键词")
                    })
                })
                put("required", JSONArray().apply { put("query") })
            },
            category = ToolCategory.MEMORY,
            tags = listOf("todo", "task", "search")
        ),
        
        ToolDefinition(
            name = "todo_remove",
            description = "删除任务",
            parameters = JSONObject().apply {
                put("type", "object")
                put("properties", JSONObject().apply {
                    put("id", JSONObject().apply {
                        put("type", "string")
                        put("description", "任务ID")
                    })
                })
                put("required", JSONArray().apply { put("id") })
            },
            category = ToolCategory.MEMORY,
            tags = listOf("todo", "task", "remove")
        ),
        
        ToolDefinition(
            name = "todo_summary",
            description = "获取任务统计摘要",
            parameters = JSONObject().apply {
                put("type", "object")
                put("properties", JSONObject())
            },
            category = ToolCategory.MEMORY,
            tags = listOf("todo", "task", "summary")
        )
    )
}