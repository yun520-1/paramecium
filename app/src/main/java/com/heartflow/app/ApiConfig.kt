package com.heartflow.app

import android.content.Context
import android.content.SharedPreferences
import org.json.JSONObject

/**
 * API 配置数据类
 *
 * @param provider 提供商标识（moonshot/openai/deepseek/custom等）
 * @param apiKey API密钥
 * @param baseUrl 自定义API地址（可选）
 * @param model 模型名称
 * @param temperature 温度参数
 * @param maxTokens 最大输出token（null=自动推荐）
 */
data class ApiConfig(
    val provider: String = "moonshot",
    val apiKey: String = "",
    val baseUrl: String? = null,
    val model: String = "default",
    val temperature: Double = 0.7,
    val maxTokens: Int? = null
) {
    /** 解析后的有效模型名 */
    val effectiveModel: String
        get() = when (model) {
            "moonshot" -> "moonshot-v1-8k"
            "deepseek" -> "deepseek-chat"
            "qwen" -> "qwen-turbo"
            "zhipu" -> "glm-4-flash"
            else -> model
        }

    /** 解析后的有效BaseUrl */
    val effectiveBaseUrl: String
        get() = baseUrl?.trimEnd('/')
            ?: PROVIDER_BASE_URLS[provider]
            ?: "https://api.moonshot.cn/v1"

    /** 获取实际使用的 max_tokens（null=自动推荐） */
    fun getEffectiveMaxTokens(): Int = maxTokens ?: getRecommendedMaxTokens(effectiveModel)

    fun toJson(): JSONObject = JSONObject().apply {
        put("provider", provider)
        put("apiKey", apiKey)
        put("baseUrl", baseUrl ?: "")
        put("model", model)
        put("temperature", temperature)
        put("maxTokens", maxTokens ?: -1) // -1 表示自动
    }

    companion object {
        fun fromJson(json: JSONObject) = ApiConfig(
            provider = json.optString("provider", "moonshot"),
            apiKey = json.optString("apiKey", ""),
            baseUrl = json.optString("baseUrl", "").ifBlank { null },
            model = json.optString("model", "default").ifBlank { "default" },
            temperature = json.optDouble("temperature", 0.7),
            maxTokens = json.optInt("maxTokens", -1).let { if (it <= 0) null else it }
        )

        private const val PREFS_NAME = "heartflow_config"
        private const val KEY_CONFIG = "api_config"

        private val PROVIDER_BASE_URLS = mapOf(
            "moonshot" to "https://api.moonshot.cn/v1",
            "openai" to "https://api.openai.com/v1",
            "deepseek" to "https://api.deepseek.com/v1",
            "zhipu" to "https://open.bigmodel.cn/api/paas/v4",
            "gemini" to "https://generativelanguage.googleapis.com/v1beta",
            "custom" to ""
        )

        fun load(context: Context): ApiConfig {
            val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            val json = prefs.getString(KEY_CONFIG, null)
            return if (json != null) {
                try {
                    fromJson(JSONObject(json))
                } catch (_: Exception) {
                    ApiConfig()
                }
            } else ApiConfig()
        }

        fun save(context: Context, config: ApiConfig) {
            val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            prefs.edit().putString(KEY_CONFIG, config.toJson().toString()).apply()
        }

        /**
         * 根据模型名推荐 max_tokens 值。
         *
         * 优先查 [MAX_OUTPUT_TOKENS] 表（各模型实际输出上限），
         * 找不到时回退到保守值 8192。
         *
         * 注意：此值是输出预算，不是最大限制。
         * 实际请求时取 min(此值, 模型最大输出限制)。
         */
        fun getRecommendedMaxTokens(model: String): Int {
            // 1) 查已知 max_output_tokens
            val maxOut = lookupMaxOutput(model)
            if (maxOut != null) return maxOut

            // 2) 回退：保守值（大多数模型至少支持 8k 输出）
            return 8192
        }

        /**
         * 获取模型上下文窗口上限（用于UI显示"推荐256K"等）
         */
        fun getRecommendedContextWindow(model: String): Int {
            val key = MODEL_WINDOWS.keys
                .sortedByDescending { it.length }
                .firstOrNull { model.startsWith(it) }
            return MODEL_WINDOWS[key] ?: 262144  // 默认为256K
        }

        /**
         * 格式化tokens数量为易读字符串（如"256K"、"1M"）
         */
        fun formatTokens(tokens: Int): String = when {
            tokens >= 1048576 -> "${tokens / 1048576}M"
            tokens >= 1024 -> "${tokens / 1024}K"
            else -> tokens.toString()
        }

        /** 按模型前缀查找已知的 max_output_tokens 上限 */
        private fun lookupMaxOutput(model: String): Int? {
            if (model.isBlank()) return null
            val exact = MAX_OUTPUT_TOKENS[model]
            if (exact != null) return exact
            val key = MAX_OUTPUT_TOKENS.keys
                .sortedByDescending { it.length }
                .firstOrNull { model.startsWith(it) }
            return if (key != null) MAX_OUTPUT_TOKENS[key] else null
        }

        /**
         * 各模型的真实 max_output_tokens 上限。
         * key 是模型前缀（越长越精确优先匹配），value 是官方推荐的上限值。
         *
         * 注意：这是模型允许的最大输出上限，不是推荐预算。
         * 实际预算由 [getRecommendedMaxTokens] 根据上下文窗口动态计算。
         */
        private val MAX_OUTPUT_TOKENS: Map<String, Int> = mapOf(
            // DeepSeek 实际最大输出（支持1M上下文）
            "deepseek" to 16384,
            // Moonshot 实际最大输出（支持256K上下文）
            "moonshot-v1-8k" to 8192,
            "moonshot-v1-32k" to 16384,
            "moonshot-v1-128k" to 16384,
            // GPT 系列
            "gpt-4o" to 16384,
            "gpt-4o-mini" to 16384,
            "gpt-4" to 16384,
            "gpt-4-32k" to 16384,
            "gpt-3.5-turbo" to 4096,
            // Claude 系列
            "claude-3-5" to 8192,
            "claude-3" to 8192,
            "claude" to 8192,
            // Gemini
            "gemini" to 8192,
            // Qwen
            "qwen" to 8192,
            "qwen-turbo" to 8192,
            "qwen-plus" to 8192,
            "qwen-max" to 16384,
            // GLM
            "glm-4" to 8192,
            "glm" to 8192,
        )

        /**
         * 模型上下文窗口上限（用于显示"推荐256K"等）
         * 实际输出tokens由MAX_OUTPUT_TOKENS限制
         */
        private val MODEL_WINDOWS: Map<String, Int> = mapOf(
            // DeepSeek 1M 上下文
            "deepseek" to 1048576,
            // Moonshot 全部提升到 256K
            "moonshot-v1-8k" to 262144,
            "moonshot-v1-32k" to 262144,
            "moonshot-v1-128k" to 262144,
            // GPT 全部提升到 256K
            "gpt-4o" to 262144, "gpt-4o-mini" to 262144,
            "gpt-4" to 262144, "gpt-4-32k" to 262144,
            "gpt-3.5-turbo" to 262144,
            // Claude 160k → 256k
            "claude-3-5" to 262144, "claude-3" to 262144, "claude" to 262144,
            // Gemini 1M → 256k
            "gemini-2" to 262144, "gemini" to 262144,
            // Qwen 全部 256k
            "qwen" to 262144, "qwen-turbo" to 262144, "qwen-plus" to 262144, "qwen-max" to 262144,
            // GLM 全部 256k
            "glm-4" to 262144, "glm" to 262144,
        )
    }
}
