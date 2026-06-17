package com.heartflow.tool.builtin

/**
 * TODO 项目数据类
 * - 用于 todo_update 工具的状态管理
 */
data class TodoItem(
    val content: String,
    val status: String
) {
    companion object {
        const val STATUS_PENDING = "pending"
        const val STATUS_IN_PROGRESS = "in_progress"
        const val STATUS_COMPLETED = "completed"

        fun normalizeStatus(raw: String?): String {
            if (raw == null) return STATUS_PENDING
            val value = raw.trim().lowercase()
            return when {
                value == STATUS_IN_PROGRESS || value == "inprogress" || value == "in-progress" -> STATUS_IN_PROGRESS
                value == STATUS_COMPLETED || value == "done" || value == "complete" || value == "finished" -> STATUS_COMPLETED
                else -> STATUS_PENDING
            }
        }

        fun fromJson(obj: org.json.JSONObject?): TodoItem? {
            if (obj == null) return null
            val content = obj.optString("content", "").trim()
            if (content.isEmpty()) return null
            return TodoItem(content, obj.optString("status", STATUS_PENDING))
        }
    }

    fun isCompleted(): Boolean = status == STATUS_COMPLETED

    fun isInProgress(): Boolean = status == STATUS_IN_PROGRESS

    fun toJson(): org.json.JSONObject = org.json.JSONObject()
        .put("content", content)
        .put("status", status)
}
