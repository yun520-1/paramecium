package com.heartflow.data

import android.content.Context
import org.json.JSONObject

data class UserProfile(
    val name: String = "",
    val nickname: String = "",
    val bio: String = "",
    val interests: List<String> = emptyList(),
    val preferences: Map<String, String> = emptyMap()
) {
    fun toJson(): JSONObject = JSONObject().apply {
        put("name", name)
        put("nickname", nickname)
        put("bio", bio)
        put("interests", org.json.JSONArray(interests))
        put("preferences", JSONObject(preferences))
    }

    fun toContextString(): String {
        val sb = StringBuilder()
        if (name.isNotBlank()) sb.appendLine("用户姓名: $name")
        if (nickname.isNotBlank()) sb.appendLine("用户昵称: $nickname")
        if (bio.isNotBlank()) sb.appendLine("用户简介: $bio")
        if (interests.isNotEmpty()) sb.appendLine("用户兴趣: ${interests.joinToString(", ")}")
        return sb.toString().trim()
    }

    companion object {
        fun fromJson(json: JSONObject) = UserProfile(
            name = json.optString("name", ""),
            nickname = json.optString("nickname", ""),
            bio = json.optString("bio", ""),
            interests = json.optJSONArray("interests")?.let { arr ->
                (0 until arr.length()).map { arr.getString(it) }
            } ?: emptyList()
        )

        fun load(context: Context): UserProfile {
            val prefs = context.getSharedPreferences("heartflow_profile", Context.MODE_PRIVATE)
            val json = prefs.getString("profile", null) ?: return UserProfile()
            return try { fromJson(JSONObject(json)) } catch (_: Exception) { UserProfile() }
        }

        fun save(context: Context, profile: UserProfile) {
            context.getSharedPreferences("heartflow_profile", Context.MODE_PRIVATE)
                .edit().putString("profile", profile.toJson().toString()).apply()
        }
    }
}
