package com.heartflow.engine

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.*
import okhttp3.OkHttpClient
import okhttp3.Request
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit

/**
 * AuthType - 认证类型枚举
 */
@Serializable
enum class AuthType {
    API_KEY,
    OAUTH,
    NONE
}

/**
 * ProviderProfile - 提供者配置数据类
 * 参考hermes providers设计，支持多提供商LLM
 */
data class ProviderProfile(
    val name: String,
    val aliases: List<String> = emptyList(),
    val authType: AuthType = AuthType.API_KEY,
    val baseUrl: String,
    val modelsUrl: String? = null,
    val visualSupport: Boolean = false,
    val models: List<String> = emptyList(),
    val apiKeyEnv: String? = null,
    val headerFormat: String = "Bearer {key}",
    val prepareMessages: ((List<Map<String, Any>>) -> List<Map<String, Any>>)? = null,
    val buildExtraBody: ((Map<String, Any>) -> Map<String, Any>)? = null,
)

/**
 * ProviderRegistry - 提供者注册表
 * 管理所有LLM提供者，支持延迟发现机制
 */
object ProviderRegistry {
    private val providers = ConcurrentHashMap<String, ProviderProfile>()
    private val aliases = ConcurrentHashMap<String, String>()
    private val httpClient = OkHttpClient.Builder()
        .connectTimeout(30, TimeUnit.SECONDS)
        .readTimeout(60, TimeUnit.SECONDS)
        .build()

    private val json = Json { ignoreUnknownKeys = true }

    init {
        registerBuiltinProviders()
    }

    /**
     * 注册提供者
     */
    fun register(profile: ProviderProfile) {
        providers[profile.name] = profile
        profile.aliases.forEach { alias ->
            aliases[alias] = profile.name
        }
    }

    /**
     * 获取提供者，支持别名
     */
    fun get(name: String): ProviderProfile? {
        val normalizedName = name.lowercase()
        return providers[normalizedName] 
            ?: aliases[normalizedName]?.let { providers[it] }
    }

    /**
     * 列出所有提供者
     */
    fun list(): List<ProviderProfile> = providers.values.toList()

    /**
     * 获取所有提供者名称
     */
    fun listNames(): List<String> = providers.keys.toList()

    /**
     * 自动检测可用提供者（从环境变量读取）
     */
    suspend fun detectAvailable(): List<ProviderProfile> = withContext(Dispatchers.IO) {
        providers.values.filter { profile ->
            when (profile.authType) {
                AuthType.API_KEY -> {
                    val envVar = profile.apiKeyEnv ?: "${profile.name.uppercase()}_API_KEY"
                    !System.getenv(envVar).isNullOrBlank()
                }
                AuthType.OAUTH -> false // OAuth需要手动登录
                AuthType.NONE -> true
            }
        }
    }

    /**
     * 获取模型列表（REST调用）
     */
    suspend fun fetchModels(profile: ProviderProfile, apiKey: String?): List<String> = withContext(Dispatchers.IO) {
        if (profile.modelsUrl == null) return@withContext profile.models
        
        try {
            val requestBuilder = Request.Builder()
                .url(profile.modelsUrl)
                .get()

            if (apiKey != null) {
                val headerValue = profile.headerFormat.replace("{key}", apiKey)
                requestBuilder.addHeader("Authorization", headerValue)
            }

            val response = httpClient.newCall(requestBuilder.build()).execute()
            if (!response.isSuccessful) return@withContext profile.models

            val body = response.body?.string() ?: return@withContext profile.models
            val jsonResponse = json.parseToJsonElement(body).jsonObject
            
            // 尝试解析OpenAI格式的模型列表
            val data = jsonResponse["data"]?.jsonArray ?: return@withContext profile.models
            data.mapNotNull { element ->
                element.jsonObject["id"]?.jsonPrimitive?.content
            }
        } catch (e: Exception) {
            profile.models
        }
    }

    /**
     * 注册内置提供者
     */
    private fun registerBuiltinProviders() {
        // OpenAI兼容提供者
        register(ProviderProfile(
            name = "openai",
            aliases = listOf("openai", "gpt", "chatgpt"),
            baseUrl = "https://api.openai.com/v1",
            modelsUrl = "https://api.openai.com/v1/models",
            visualSupport = true,
            models = listOf("gpt-4o", "gpt-4o-mini", "gpt-4-turbo", "gpt-4", "gpt-3.5-turbo"),
            apiKeyEnv = "OPENAI_API_KEY",
        ))

        // Anthropic提供者
        register(ProviderProfile(
            name = "anthropic",
            aliases = listOf("anthropic", "claude", "sonnet"),
            baseUrl = "https://api.anthropic.com/v1",
            modelsUrl = "https://api.anthropic.com/v1/models",
            visualSupport = true,
            models = listOf("claude-sonnet-4-20250514", "claude-3-5-sonnet-20241022", "claude-3-haiku-20240307"),
            apiKeyEnv = "ANTHROPIC_API_KEY",
            headerFormat = "{key}",
            prepareMessages = ::prepareAnthropicMessages,
            buildExtraBody = ::buildAnthropicExtraBody,
        ))

        // OpenRouter提供者
        register(ProviderProfile(
            name = "openrouter",
            aliases = listOf("openrouter"),
            baseUrl = "https://openrouter.ai/api/v1",
            modelsUrl = "https://openrouter.ai/api/v1/models",
            visualSupport = true,
            models = listOf(
                "anthropic/claude-sonnet-4-20250514",
                "openai/gpt-4o",
                "google/gemini-2.0-flash-001",
            ),
            apiKeyEnv = "OPENROUTER_API_KEY",
        ))

        // DeepSeek提供者
        register(ProviderProfile(
            name = "deepseek",
            aliases = listOf("deepseek"),
            baseUrl = "https://api.deepseek.com/v1",
            modelsUrl = "https://api.deepseek.com/v1/models",
            visualSupport = false,
            models = listOf("deepseek-chat", "deepseek-coder", "deepseek-reasoner"),
            apiKeyEnv = "DEEPSEEK_API_KEY",
        ))

        // Google Gemini提供者
        register(ProviderProfile(
            name = "gemini",
            aliases = listOf("gemini", "google", "googleai"),
            baseUrl = "https://generativelanguage.googleapis.com/v1beta",
            modelsUrl = "https://generativelanguage.googleapis.com/v1beta/models",
            visualSupport = true,
            models = listOf("gemini-2.0-flash", "gemini-1.5-pro", "gemini-1.5-flash"),
            apiKeyEnv = "GOOGLE_API_KEY",
        ))

        // 通义千问提供者
        register(ProviderProfile(
            name = "qwen",
            aliases = listOf("qwen", "tongyi", "dashscope"),
            baseUrl = "https://dashscope.aliyuncs.com/compatible-mode/v1",
            modelsUrl = "https://dashscope.aliyuncs.com/compatible-mode/v1/models",
            visualSupport = true,
            models = listOf("qwen-max", "qwen-plus", "qwen-turbo"),
            apiKeyEnv = "DASHSCOPE_API_KEY",
        ))

        // Moonshot (Kimi)提供者
        register(ProviderProfile(
            name = "moonshot",
            aliases = listOf("moonshot", "kimi"),
            baseUrl = "https://api.moonshot.cn/v1",
            modelsUrl = "https://api.moonshot.cn/v1/models",
            visualSupport = false,
            models = listOf("moonshot-v1-8k", "moonshot-v1-32k", "moonshot-v1-128k"),
            apiKeyEnv = "MOONSHOT_API_KEY",
        ))

        // 智谱GLM提供者
        register(ProviderProfile(
            name = "zhipu",
            aliases = listOf("zhipu", "glm"),
            baseUrl = "https://open.bigmodel.cn/api/paas/v4",
            modelsUrl = "https://open.bigmodel.cn/api/paas/v4/models",
            visualSupport = true,
            models = listOf("glm-4", "glm-4-flash", "glm-4v"),
            apiKeyEnv = "ZHIPU_API_KEY",
        ))
    }
}

/**
 * Anthropic消息预处理 - 转换为Anthropic格式
 */
private fun prepareAnthropicMessages(messages: List<Map<String, Any>>): List<Map<String, Any>> {
    return messages.map { msg ->
        when (msg["role"]) {
            "system" -> mapOf(
                "role" to "user",
                "content" to listOf(
                    mapOf("type" to "text", "text" to msg["content"])
                )
            )
            else -> msg
        }
    }
}

/**
 * Anthropic额外请求体构建
 */
private fun buildAnthropicExtraBody(body: Map<String, Any>): Map<String, Any> {
    return body.toMutableMap().apply {
        put("anthropic_version", "bedrock-2023-05-31")
    }
}
