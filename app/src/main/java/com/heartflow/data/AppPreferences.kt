package com.heartflow.data

import android.content.Context

enum class ThemeMode { LIGHT, DARK, SYSTEM }
enum class ThemeVariant(val displayName: String) {
    AURORA_PURPLE("极光紫"),
    OCEAN_BLUE("海洋蓝"),
    FOREST_GREEN("森林绿"),
    SUNSET_ORANGE("落日橙"),
    DARK_NIGHT("暗夜黑")
}
enum class Language(val code: String, val displayName: String) {
    CHINESE("zh", "中文"),
    ENGLISH("en", "English"),
    JAPANESE("ja", "日本語")
}

class AppPreferences(context: Context) {
    private val prefs = context.getSharedPreferences("heartflow_prefs", Context.MODE_PRIVATE)

    var themeMode: ThemeMode
        get() = ThemeMode.valueOf(prefs.getString("theme_mode", "SYSTEM") ?: "SYSTEM")
        set(value) = prefs.edit().putString("theme_mode", value.name).apply()

    var themeVariant: ThemeVariant
        get() = ThemeVariant.valueOf(prefs.getString("theme_variant", "AURORA_PURPLE") ?: "AURORA_PURPLE")
        set(value) = prefs.edit().putString("theme_variant", value.name).apply()

    var language: Language
        get() = Language.entries.firstOrNull { it.code == prefs.getString("language", "zh") } ?: Language.CHINESE
        set(value) = prefs.edit().putString("language", value.code).apply()

    var fontSize: Float
        get() = prefs.getFloat("font_size", 14f)
        set(value) = prefs.edit().putFloat("font_size", value).apply()

    var personalityId: String
        get() = prefs.getString("personality_id", "default") ?: "default"
        set(value) = prefs.edit().putString("personality_id", value).apply()

    var enableTools: Boolean
        get() = prefs.getBoolean("enable_tools", true)
        set(value) = prefs.edit().putBoolean("enable_tools", value).apply()

    var enableStreaming: Boolean
        get() = prefs.getBoolean("enable_streaming", true)
        set(value) = prefs.edit().putBoolean("enable_streaming", value).apply()

var systemPrompt: String
        get() = prefs.getString("system_prompt", "") ?: ""
        set(value) = prefs.edit().putString("system_prompt", value).apply()

    var userDefinedTools: String
        get() = prefs.getString("user_tools", "[]") ?: "[]"
        set(value) = prefs.edit().putString("user_tools", value).apply()

    var maxToolLoops: Int
        get() = prefs.getInt("max_tool_loops", 3)
        set(value) = prefs.edit().putInt("max_tool_loops", value).apply()

    var toolTimeoutMs: Long
        get() = prefs.getLong("tool_timeout_ms", 30000L)
        set(value) = prefs.edit().putLong("tool_timeout_ms", value).apply()

    /** 已禁用的工具名列表，以 JSON 数组持久化 */
    var disabledTools: Set<String>
        get() {
            val json = prefs.getString("disabled_tools", "[]") ?: "[]"
            return try {
                org.json.JSONArray(json).let { arr ->
                    (0 until arr.length()).map { arr.getString(it) }.toSet()
                }
            } catch (_: Exception) { emptySet() }
        }
        set(value) {
            val json = org.json.JSONArray(value.toList()).toString()
            prefs.edit().putString("disabled_tools", json).apply()
        }

    var sttEngine: String
        get() = prefs.getString("stt_engine", "system") ?: "system"
        set(value) = prefs.edit().putString("stt_engine", value).apply()

    var mediaQuality: String
        get() = prefs.getString("media_quality", "compressed") ?: "compressed"
        set(value) = prefs.edit().putString("media_quality", value).apply()

    var autoDownloadMedia: Boolean
        get() = prefs.getBoolean("auto_download_media", true)
        set(value) = prefs.edit().putBoolean("auto_download_media", value).apply()

    var visionModel: String
        get() = prefs.getString("vision_model", "") ?: ""
        set(value) = prefs.edit().putString("vision_model", value).apply()

    var ttsSpeed: Float
        get() = prefs.getFloat("tts_speed", 1.0f)
        set(value) = prefs.edit().putFloat("tts_speed", value).apply()

    var ttsPitch: Float
        get() = prefs.getFloat("tts_pitch", 1.0f)
        set(value) = prefs.edit().putFloat("tts_pitch", value).apply()

    var browserHomeUrl: String
        get() = prefs.getString("browser_home_url", "https://wm.m.sm.cn/?from=wm828952") ?: "https://wm.m.sm.cn/?from=wm828952"
        set(value) = prefs.edit().putString("browser_home_url", value).apply()
}
