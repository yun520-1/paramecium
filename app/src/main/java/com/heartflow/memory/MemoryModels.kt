package com.heartflow.memory

import org.json.JSONArray
import org.json.JSONObject

enum class MemoryLayer { WORKING, EPISODIC, CORE }

data class MemoryItem(
    val id: String = java.util.UUID.randomUUID().toString(),
    val content: String,
    val layer: MemoryLayer,
    val timestamp: Long = System.currentTimeMillis(),
    val tags: List<String> = emptyList(),
    val importance: Int = 1,
    val emotion: String? = null,
    val accessCount: Int = 0,
    val source: String? = null
) {
    fun toJson(): JSONObject = JSONObject().apply {
        put("id", id)
        put("content", content)
        put("layer", layer.name)
        put("timestamp", timestamp)
        put("tags", JSONArray(tags))
        put("importance", importance)
        if (emotion != null) put("emotion", emotion)
        put("accessCount", accessCount)
        if (source != null) put("source", source)
    }

    companion object {
        fun fromJson(json: JSONObject): MemoryItem = MemoryItem(
            id = json.getString("id"),
            content = json.getString("content"),
            layer = MemoryLayer.valueOf(json.getString("layer")),
            timestamp = json.optLong("timestamp", System.currentTimeMillis()),
            tags = json.optJSONArray("tags").let { arr ->
                (0 until (arr?.length() ?: 0)).map { arr?.getString(it) ?: "" }
            },
            importance = json.optInt("importance", 1),
            emotion = json.optString("emotion", "").ifEmpty { null },
            accessCount = json.optInt("accessCount", 0),
            source = json.optString("source", "").ifEmpty { null }
        )
    }
}

data class MemoryStats(
    val workingCount: Int = 0,
    val episodicCount: Int = 0,
    val coreCount: Int = 0,
    val lastAccessTime: Long = 0,
    val totalMemories: Int = 0
)

data class UserFact(
    val key: String,
    val value: String,
    val confidence: Float = 1.0f,
    val source: String? = null
)