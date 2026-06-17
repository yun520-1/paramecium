package com.heartflow.data

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.text.SimpleDateFormat
import java.util.*

data class MediaAttachment(
    val uri: String,
    val type: String,       // "image", "audio", "file"
    val mimeType: String,
    val fileName: String,
    val fileSize: Long = 0,
    val base64Data: String? = null
) {
    fun toJson(): JSONObject = JSONObject().apply {
        put("uri", uri)
        put("type", type)
        put("mimeType", mimeType)
        put("fileName", fileName)
        put("fileSize", fileSize)
        put("base64Data", base64Data)
    }

    companion object {
        fun fromJson(json: JSONObject): MediaAttachment = MediaAttachment(
            uri = json.getString("uri"),
            type = json.getString("type"),
            mimeType = json.getString("mimeType"),
            fileName = json.getString("fileName"),
            fileSize = json.optLong("fileSize", 0),
            base64Data = json.optString("base64Data", null)
        )
    }
}

data class ChatMessage(
    val id: String = UUID.randomUUID().toString(),
    val role: String,
    val content: String,
    val timestamp: Long = System.currentTimeMillis(),
    val emotion: String? = null,
    val isCode: Boolean = false,
    val isVoice: Boolean = false,
    val attachment: MediaAttachment? = null
)

data class Conversation(
    val id: String = UUID.randomUUID().toString(),
    val title: String,
    val messages: MutableList<ChatMessage> = mutableListOf(),
    val createdAt: Long = System.currentTimeMillis(),
    val updatedAt: Long = System.currentTimeMillis(),
    val personalityId: String = "default"
) {
    val preview: String
        get() = messages.lastOrNull { it.role == "user" }?.content?.take(50) ?: title

    val messageCount: Int get() = messages.size

    fun toJson(): JSONObject = JSONObject().apply {
        put("id", id)
        put("title", title)
        put("createdAt", createdAt)
        put("updatedAt", updatedAt)
        put("personalityId", personalityId)
        put("messages", JSONArray(messages.map { msg ->
            JSONObject().apply {
                put("id", msg.id)
                put("role", msg.role)
                put("content", msg.content)
                put("timestamp", msg.timestamp)
                put("emotion", msg.emotion)
                put("isCode", msg.isCode)
                put("isVoice", msg.isVoice)
                put("attachment", msg.attachment?.toJson())
            }
        }))
    }

    companion object {
        fun fromJson(json: JSONObject): Conversation {
            val conv = Conversation(
                id = json.getString("id"),
                title = json.getString("title"),
                createdAt = json.optLong("createdAt", System.currentTimeMillis()),
                updatedAt = json.optLong("updatedAt", System.currentTimeMillis()),
                personalityId = json.optString("personalityId", "default")
            )
            val msgs = json.optJSONArray("messages")
            if (msgs != null) {
                for (i in 0 until msgs.length()) {
                    val m = msgs.getJSONObject(i)
                    conv.messages.add(ChatMessage(
                        id = m.getString("id"),
                        role = m.getString("role"),
                        content = m.getString("content"),
                        timestamp = m.optLong("timestamp", 0),
                        emotion = m.optString("emotion", "").ifEmpty { null },
                        isCode = m.optBoolean("isCode", false),
                        isVoice = m.optBoolean("isVoice", false),
                        attachment = m.optJSONObject("attachment")?.let { MediaAttachment.fromJson(it) }
                    ))
                }
            }
            return conv
        }
    }
}

class ChatHistoryManager(private val context: Context) {
    private val dir: File get() = File(context.filesDir, "conversations").also { it.mkdirs() }

    fun save(conversation: Conversation) {
        File(dir, "${conversation.id}.json").writeText(conversation.toJson().toString())
    }

    fun loadAll(): List<Conversation> {
        return dir.listFiles()?.filter { it.extension == "json" }?.mapNotNull {
            try { Conversation.fromJson(JSONObject(it.readText())) } catch (_: Exception) { null }
        }?.sortedByDescending { it.updatedAt } ?: emptyList()
    }

    fun load(id: String): Conversation? {
        val file = File(dir, "$id.json")
        return if (file.exists()) {
            try { Conversation.fromJson(JSONObject(file.readText())) } catch (_: Exception) { null }
        } else null
    }

    fun delete(id: String) {
        File(dir, "$id.json").delete()
    }

    fun generateTitle(firstMessage: String): String {
        return if (firstMessage.length > 20) firstMessage.take(20) + "..." else firstMessage
    }
}
