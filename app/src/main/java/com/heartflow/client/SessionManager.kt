package com.heartflow.client

import android.content.Context
import android.content.SharedPreferences
import android.util.Log
import com.heartflow.model.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject

/**
 * 会话管理器
 * 负责管理对话会话的存储和恢复
 */
class SessionManager(context: Context) {
    companion object {
        private const val TAG = "SessionManager"
        private const val PREF_NAME = "heartflow_sessions"

        private const val KEY_SESSION_LIST = "session_list"
        private const val KEY_SESSION_PREFIX = "session_"
        private const val KEY_MESSAGE_PREFIX = "session_messages_"

        private const val MAX_STORED_SESSIONS = 50
    }

    private val prefs: SharedPreferences = context.getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE)

    /**
     * 创建新会话
     */
    fun createSession(apiConfig: ApiConfig, title: String? = null): Session {
        val session = Session(
            id = generateSessionId(),
            apiConfigId = apiConfig.id,
            title = title ?: "新对话",
            createdAt = System.currentTimeMillis(),
            updatedAt = System.currentTimeMillis(),
            messages = emptyList()
        )

        saveSession(session)
        addToSessionList(session.id)
        Log.d(TAG, "创建会话: ${session.id}")
        return session
    }

    /**
     * 获取会话
     */
    fun getSession(sessionId: String): Session? {
        val json = prefs.getString(KEY_SESSION_PREFIX + sessionId, null) ?: return null
        return try {
            Session.fromJson(json)
        } catch (_: Exception) {
            null
        }
    }

    /**
     * 更新会话
     */
    fun updateSession(session: Session): Boolean {
        val updated = session.copy(updatedAt = System.currentTimeMillis())
        saveSession(updated)
        return true
    }

    /**
     * 删除会话
     */
    fun deleteSession(sessionId: String): Boolean {
        prefs.edit()
            .remove(KEY_SESSION_PREFIX + sessionId)
            .remove(KEY_MESSAGE_PREFIX + sessionId)
            .apply()

        removeFromSessionList(sessionId)
        Log.d(TAG, "删除会话: $sessionId")
        return true
    }

    /**
     * 获取所有会话列表
     */
    fun getAllSessions(): List<Session> {
        val sessionIds = getSessionIdList()
        return sessionIds.mapNotNull { getSession(it) }
            .sortedByDescending { it.updatedAt }
    }

    /**
     * 添加消息到会话
     */
    fun addMessage(sessionId: String, message: ModelMessage): Boolean {
        val session = getSession(sessionId) ?: return false
        val updatedMessages = session.messages + message
        val updated = session.copy(
            messages = updatedMessages,
            updatedAt = System.currentTimeMillis()
        )
        saveSession(updated)
        return true
    }

    /**
     * 获取会话消息
     */
    fun getMessages(sessionId: String): List<ModelMessage> {
        return getSession(sessionId)?.messages ?: emptyList()
    }

    /**
     * 清除会话消息
     */
    fun clearMessages(sessionId: String): Boolean {
        val session = getSession(sessionId) ?: return false
        val updated = session.copy(
            messages = emptyList(),
            updatedAt = System.currentTimeMillis()
        )
        saveSession(updated)
        return true
    }

    /**
     * 删除旧会话（保留最近N个）
     */
    fun pruneOldSessions(keepCount: Int = MAX_STORED_SESSIONS) {
        val sessions = getAllSessions()
        if (sessions.size > keepCount) {
            val toDelete = sessions.drop(keepCount)
            toDelete.forEach { deleteSession(it.id) }
            Log.d(TAG, "清理旧会话: ${toDelete.size}个")
        }
    }

    private fun saveSession(session: Session) {
        prefs.edit()
            .putString(KEY_SESSION_PREFIX + session.id, session.toJson())
            .apply()
    }

    private fun getSessionIdList(): List<String> {
        val listStr = prefs.getString(KEY_SESSION_LIST, "") ?: ""
        if (listStr.isEmpty()) return emptyList()
        return listStr.split(",").filter { it.isNotEmpty() }
    }

    private fun addToSessionList(sessionId: String) {
        val list = getSessionIdList().toMutableList()
        if (!list.contains(sessionId)) {
            list.add(0, sessionId) // 新会话添加到前面
            prefs.edit().putString(KEY_SESSION_LIST, list.joinToString(",")).apply()
        }
    }

    private fun removeFromSessionList(sessionId: String) {
        val list = getSessionIdList().toMutableList()
        list.remove(sessionId)
        prefs.edit().putString(KEY_SESSION_LIST, list.joinToString(",")).apply()
    }

    private fun generateSessionId(): String {
        return "sess_${System.currentTimeMillis()}_${(1000..9999).random()}"
    }
}

/**
 * 会话数据模型
 */
data class Session(
    val id: String,
    val apiConfigId: String,
    val title: String,
    val createdAt: Long,
    val updatedAt: Long,
    val messages: List<ModelMessage>
) {
    fun toJson(): String {
        return JSONObject().apply {
            put("id", id)
            put("apiConfigId", apiConfigId)
            put("title", title)
            put("createdAt", createdAt)
            put("updatedAt", updatedAt)
            put("messageCount", messages.size)
        }.toString()
    }

    companion object {
        fun fromJson(json: String): Session {
            val obj = org.json.JSONObject(json)
            return Session(
                id = obj.getString("id"),
                apiConfigId = obj.getString("apiConfigId"),
                title = obj.getString("title"),
                createdAt = obj.getLong("createdAt"),
                updatedAt = obj.getLong("updatedAt"),
                messages = emptyList() // 消息单独存储
            )
        }
    }
}

/**
 * 消息序列化工具
 */
object MessageSerializer {

    /**
     * 将消息列表序列化为JSON字符串
     */
    fun serialize(messages: List<ModelMessage>): String {
        val array = org.json.JSONArray()
        for (msg in messages) {
            array.put(serializeMessage(msg))
        }
        return array.toString()
    }

    /**
     * 从JSON字符串反序列化消息列表
     */
    fun deserialize(json: String): List<ModelMessage> {
        if (json.isBlank()) return emptyList()

        return try {
            val array = org.json.JSONArray(json)
            (0 until array.length()).mapNotNull { i ->
                try {
                    deserializeMessage(array.getJSONObject(i))
                } catch (_: Exception) {
                    null
                }
            }
        } catch (_: Exception) {
            emptyList()
        }
    }

    private fun serializeMessage(msg: ModelMessage): org.json.JSONObject {
        return org.json.JSONObject().apply {
            put("role", msg.role)
            put("content", msg.content)
            if (msg.reasoningContent.isNotEmpty()) {
                put("reasoningContent", msg.reasoningContent)
            }

            // 工具调用
            val toolCalls = msg.getToolCalls()
            if (toolCalls.isNotEmpty()) {
                val toolCallsArray = org.json.JSONArray()
                for (tc in toolCalls) {
                    toolCallsArray.put(org.json.JSONObject().apply {
                        put("id", tc.getId())
                        put("name", tc.getName())
                        put("arguments", tc.getArguments())
                    })
                }
                put("toolCalls", toolCallsArray)
            }

            // 工具结果消息
            if (msg is ToolModelMessage) {
                put("toolCallId", msg.getToolCallId())
            }
        }
    }

    private fun deserializeMessage(obj: org.json.JSONObject): ModelMessage? {
        val role = obj.optString("role", "")
        val content = obj.optString("content", "")
        val reasoningContent = obj.optString("reasoningContent", "")

        // 工具调用
        val toolCallsArray = obj.optJSONArray("toolCalls")
        val toolCalls = if (toolCallsArray != null) {
            (0 until toolCallsArray.length()).map { i ->
                val tc = toolCallsArray.getJSONObject(i)
                com.heartflow.tool.ToolCall(
                    tc.optString("id", ""),
                    tc.optString("name", ""),
                    tc.optString("arguments", "{}")
                )
            }
        } else {
            emptyList()
        }

        return when (role) {
            "user" -> UserModelMessage(content)
            "assistant" -> {
                if (toolCalls.isNotEmpty()) {
                    AssistantModelMessage(content, reasoningContent, toolCalls)
                } else {
                    AssistantModelMessage(content, reasoningContent)
                }
            }
            "system" -> SystemModelMessage(content)
            "tool" -> ToolModelMessage(content, obj.optString("toolCallId", ""))
            else -> null
        }
    }
}
