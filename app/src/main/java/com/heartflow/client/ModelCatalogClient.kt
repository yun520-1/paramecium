package com.heartflow.client

import android.util.Log
import com.heartflow.model.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.*
import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.TimeUnit

/**
 * 模型目录客户端
 * 用于获取支持的模型列表
 */
class ModelCatalogClient(
    private val config: ModelConfig
) {
    companion object {
        private const val TAG = "ModelCatalogClient"

        /** 模型列表端点 */
        private const val ENDPOINT_MODELS = "/v1/models"
    }

    private val client = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .build()

    /**
     * 获取模型列表
     */
    suspend fun listModels(): ModelCatalogResult = withContext(Dispatchers.IO) {
        try {
            val url = buildModelsUrl()
            val request = buildRequest(url)

            val response = client.newCall(request).execute()
            val body = response.body?.string() ?: ""

            if (!response.isSuccessful) {
                return@withContext ModelCatalogResult.Error(
                    "获取模型列表失败: HTTP ${response.code}",
                    response.code
                )
            }

            val models = parseModelsResponse(body)
            ModelCatalogResult.Success(models)
        } catch (e: Exception) {
            Log.e(TAG, "获取模型列表失败", e)
            ModelCatalogResult.Error(
                "获取模型列表失败: ${e.message}",
                null
            )
        }
    }

    /**
     * 获取模型信息
     */
    suspend fun getModelInfo(modelId: String): ModelInfoResult = withContext(Dispatchers.IO) {
        try {
            val url = buildModelInfoUrl(modelId)
            val request = buildRequest(url)

            val response = client.newCall(request).execute()
            val body = response.body?.string() ?: ""

            if (!response.isSuccessful) {
                return@withContext ModelInfoResult.Error(
                    "获取模型信息失败: HTTP ${response.code}",
                    response.code
                )
            }

            val modelInfo = parseModelInfo(body)
            ModelInfoResult.Success(modelInfo)
        } catch (e: Exception) {
            Log.e(TAG, "获取模型信息失败", e)
            ModelInfoResult.Error(
                "获取模型信息失败: ${e.message}",
                null
            )
        }
    }

    private fun buildModelsUrl(): String {
        val baseUrl = config.baseUrl.trimEnd('/')
        return "$baseUrl$ENDPOINT_MODELS"
    }

    private fun buildModelInfoUrl(modelId: String): String {
        val baseUrl = config.baseUrl.trimEnd('/')
        return "$baseUrl$ENDPOINT_MODELS/$modelId"
    }

    private fun buildRequest(url: String): Request {
        return Request.Builder()
            .url(url)
            .apply {
                // 添加认证头
                if (config.apiKey.isNotEmpty()) {
                    header("Authorization", "Bearer ${config.apiKey}")
                }
            }
            .get()
            .build()
    }

    private fun parseModelsResponse(body: String): List<ModelInfo> {
        return try {
            val json = JSONObject(body)
            val data = json.optJSONArray("data") ?: JSONArray()

            val models = mutableListOf<ModelInfo>()
            for (i in 0 until data.length()) {
                val obj = data.getJSONObject(i)
                models.add(parseSingleModel(obj))
            }
            models
        } catch (_: Exception) {
            emptyList()
        }
    }

    private fun parseSingleModel(obj: JSONObject): ModelInfo {
        val id = obj.optString("id", "")
        val created = obj.optLong("created", 0)
        val ownedBy = obj.optString("owned_by", "unknown")

        // 尝试获取上下文长度
        val contextLength = obj.optJSONObject("limits")
            ?.optJSONObject("context_window")
            ?.optInt("max_tokens", 0) ?: 0

        return ModelInfo(
            id = id,
            name = extractModelName(id),
            created = created,
            ownedBy = ownedBy,
            contextLength = contextLength,
            description = "",
            capabilities = detectCapabilities(id)
        )
    }

    private fun parseModelInfo(body: String): ModelInfo {
        return try {
            val obj = JSONObject(body)
            parseSingleModel(obj)
        } catch (_: Exception) {
            ModelInfo(
                id = "",
                name = "",
                created = 0,
                ownedBy = "unknown",
                contextLength = 0,
                description = "",
                capabilities = emptyList()
            )
        }
    }

    private fun extractModelName(modelId: String): String {
        // 从完整ID中提取简化的模型名称
        return modelId.substringAfterLast("/").substringAfterLast("-").ifEmpty { modelId }
    }

    private fun detectCapabilities(modelId: String): List<String> {
        val capabilities = mutableListOf<String>()
        val lower = modelId.lowercase()

        if (lower.contains("gpt-4") || lower.contains("claude")) {
            capabilities.add("function_calling")
            capabilities.add("vision")
        }

        if (lower.contains("qwen")) {
            capabilities.add("function_calling")
            capabilities.add("thinking")
        }

        if (lower.contains("deepseek")) {
            capabilities.add("function_calling")
            capabilities.add("reasoning")
        }

        if (lower.contains("o1") || lower.contains("o3")) {
            capabilities.add("reasoning")
            capabilities.add("no_system_prompt")
        }

        return capabilities
    }
}

/**
 * 模型信息
 */
data class ModelInfo(
    val id: String,
    val name: String,
    val created: Long,
    val ownedBy: String,
    val contextLength: Int,
    val description: String,
    val capabilities: List<String>
) {
    fun hasCapability(capability: String): Boolean {
        return capabilities.contains(capability)
    }

    fun getContextDisplay(): String {
        return when {
            contextLength >= 1_000_000 -> "${contextLength / 1_000_000}m"
            contextLength >= 1000 -> "${contextLength / 1000}k"
            contextLength > 0 -> contextLength.toString()
            else -> "未知"
        }
    }
}

/**
 * 模型目录结果
 */
sealed class ModelCatalogResult {
    data class Success(val models: List<ModelInfo>) : ModelCatalogResult()
    data class Error(val message: String, val httpCode: Int?) : ModelCatalogResult()
}

/**
 * 模型信息结果
 */
sealed class ModelInfoResult {
    data class Success(val model: ModelInfo) : ModelInfoResult()
    data class Error(val message: String, val httpCode: Int?) : ModelInfoResult()
}

/**
 * 常用模型目录
 * 当API不可用时提供备用模型列表
 */
object ModelCatalog {
    private const val TAG = "ModelCatalog"

    /**
     * 获取常用模型列表
     */
    fun getCommonModels(): List<ModelInfo> {
        return listOf(
            // OpenAI
            createModelInfo("gpt-4o", "OpenAI GPT-4o", 128000, listOf("vision", "function_calling")),
            createModelInfo("gpt-4-turbo", "OpenAI GPT-4 Turbo", 128000, listOf("vision", "function_calling")),
            createModelInfo("gpt-4", "OpenAI GPT-4", 8192, listOf("function_calling")),
            createModelInfo("gpt-3.5-turbo", "OpenAI GPT-3.5 Turbo", 16385, listOf("function_calling")),

            // Claude
            createModelInfo("claude-3-5-sonnet-20241022", "Claude 3.5 Sonnet", 200000, listOf("vision", "function_calling")),
            createModelInfo("claude-3-opus-20240229", "Claude 3 Opus", 200000, listOf("vision", "function_calling")),
            createModelInfo("claude-3-sonnet-20240229", "Claude 3 Sonnet", 200000, listOf("vision", "function_calling")),
            createModelInfo("claude-3-haiku-20240307", "Claude 3 Haiku", 200000, listOf("vision")),

            // 通义千问
            createModelInfo("qwen-plus", "通义千问 Plus", 131072, listOf("function_calling", "thinking")),
            createModelInfo("qwen-turbo", "通义千问 Turbo", 131072, listOf("function_calling")),
            createModelInfo("qwen-max", "通义千问 Max", 8192, listOf("function_calling")),

            // DeepSeek
            createModelInfo("deepseek-chat", "DeepSeek Chat", 64000, listOf("function_calling", "reasoning")),
            createModelInfo("deepseek-coder", "DeepSeek Coder", 64000, listOf("function_calling")),

            // Moonshot
            createModelInfo("moonshot-v1-8k", "Moonshot 8K", 8192, listOf("function_calling", "thinking")),
            createModelInfo("moonshot-v1-32k", "Moonshot 32K", 32768, listOf("function_calling", "thinking")),
            createModelInfo("moonshot-v1-128k", "Moonshot 128K", 131072, listOf("function_calling", "thinking")),

            // 智谱
            createModelInfo("glm-4", "GLM-4", 128000, listOf("function_calling", "thinking")),
            createModelInfo("glm-4-flash", "GLM-4 Flash", 128000, listOf("function_calling")),
            createModelInfo("glm-3-turbo", "GLM-3 Turbo", 128000, listOf("function_calling")),

            // MiniMax
            createModelInfo("abab6-chat", "MiniMax ABAB6", 245760, listOf("function_calling")),
            createModelInfo("abab5.5-chat", "MiniMax ABAB5.5", 163840, listOf("function_calling")),

            // OpenAI o系列
            createModelInfo("o1-preview", "OpenAI o1 Preview", 128000, listOf("reasoning", "no_system_prompt")),
            createModelInfo("o1-mini", "OpenAI o1 Mini", 128000, listOf("reasoning", "no_system_prompt")),
            createModelInfo("o3-mini", "OpenAI o3 Mini", 128000, listOf("reasoning", "no_system_prompt"))
        )
    }

    private fun createModelInfo(
        id: String,
        name: String,
        contextLength: Int,
        capabilities: List<String>
    ): ModelInfo {
        return ModelInfo(
            id = id,
            name = name,
            created = 0,
            ownedBy = "",
            contextLength = contextLength,
            description = "",
            capabilities = capabilities
        )
    }

    /**
     * 根据搜索关键词过滤模型
     */
    fun filterModels(query: String): List<ModelInfo> {
        if (query.isBlank()) return getCommonModels()

        val lowerQuery = query.lowercase()
        return getCommonModels().filter { model ->
            model.id.lowercase().contains(lowerQuery) ||
            model.name.lowercase().contains(lowerQuery) ||
            model.capabilities.any { it.lowercase().contains(lowerQuery) }
        }
    }

    /**
     * 获取支持特定能力的模型
     */
    fun getModelsWithCapability(capability: String): List<ModelInfo> {
        return getCommonModels().filter { it.hasCapability(capability) }
    }
}
