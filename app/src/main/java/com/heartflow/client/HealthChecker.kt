package com.heartflow.client

import android.util.Log
import com.heartflow.model.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.*
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import java.util.concurrent.TimeUnit

/**
 * API健康检查器
 * 用于检测API连接状态
 */
class HealthChecker(
    private val config: ModelConfig
) {
    companion object {
        private const val TAG = "HealthChecker"

        /** 默认超时时间 */
        private const val DEFAULT_TIMEOUT = 10L
    }

    private val client = OkHttpClient.Builder()
        .connectTimeout(DEFAULT_TIMEOUT, TimeUnit.SECONDS)
        .readTimeout(DEFAULT_TIMEOUT, TimeUnit.SECONDS)
        .writeTimeout(5, TimeUnit.SECONDS)
        .retryOnConnectionFailure(false)
        .build()

    /**
     * 执行健康检查
     */
    suspend fun check(): HealthResult = withContext(Dispatchers.IO) {
        try {
            val startTime = System.currentTimeMillis()

            when (config.protocolType) {
                ModelProtocolType.ANTHROPIC_MESSAGES -> checkAnthropic()
                ModelProtocolType.CODEX_RESPONSES -> checkCodex()
                ModelProtocolType.LOCAL_GGUF -> checkLocalModel()
                ModelProtocolType.OPENAI_COMPATIBLE -> checkOpenAiCompatible()
                else -> HealthResult.Error("不支持的协议类型: ${config.protocolType}", null)
            }.also { result ->
                val latency = System.currentTimeMillis() - startTime
                Log.d(TAG, "健康检查完成: $result, 延迟: ${latency}ms")
            }
        } catch (e: Exception) {
            Log.e(TAG, "健康检查异常", e)
            HealthResult.Error(e.message ?: "未知错误", null)
        }
    }

    /**
     * 检查OpenAI兼容API
     */
    private suspend fun checkOpenAiCompatible(): HealthResult {
        return try {
            // 尝试获取模型列表
            val url = buildUrl("/v1/models")
            val request = Request.Builder()
                .url(url)
                .header("Authorization", "Bearer ${config.apiKey}")
                .get()
                .build()

            val response = client.newCall(request).execute()

            if (response.isSuccessful) {
                HealthResult.Healthy(response.code, "连接正常")
            } else {
                when (response.code) {
                    401 -> HealthResult.Unauthorized
                    403 -> HealthResult.Forbidden
                    404 -> HealthResult.NotFound
                    else -> HealthResult.Error(
                        "HTTP ${response.code}",
                        response.code
                    )
                }
            }
        } catch (e: Exception) {
            handleConnectionError(e)
        }
    }

    /**
     * 检查Anthropic API
     */
    private suspend fun checkAnthropic(): HealthResult {
        return try {
            // Anthropic健康检查端点
            val url = "https://api.anthropic.com/health"
            val request = Request.Builder()
                .url(url)
                .header("x-api-key", config.apiKey)
                .header("anthropic-version", "2023-06-01")
                .get()
                .build()

            val response = client.newCall(request).execute()

            if (response.isSuccessful) {
                HealthResult.Healthy(response.code, "Claude API连接正常")
            } else {
                when (response.code) {
                    401 -> HealthResult.Unauthorized
                    else -> HealthResult.Error(
                        "HTTP ${response.code}",
                        response.code
                    )
                }
            }
        } catch (e: Exception) {
            handleConnectionError(e)
        }
    }

    /**
     * 检查Codex API
     */
    private suspend fun checkCodex(): HealthResult {
        // Codex使用Responses API，与OpenAI兼容
        return checkOpenAiCompatible()
    }

    /**
     * 检查本地模型（Ollama等）
     */
    private suspend fun checkLocalModel(): HealthResult {
        return try {
            val baseUrl = config.baseUrl.ifEmpty { "http://localhost:11434" }
            val url = "$baseUrl/api/tags" // Ollama模型列表端点
            val request = Request.Builder()
                .url(url)
                .get()
                .build()

            val response = client.newCall(request).execute()

            if (response.isSuccessful) {
                HealthResult.Healthy(response.code, "本地模型服务正常")
            } else {
                when (response.code) {
                    404 -> {
                        // 尝试/api/chat端点
                        val chatUrl = "$baseUrl/api/chat"
                        val chatRequest = Request.Builder()
                            .url(chatUrl)
                            .post("""{"model": "", "messages": [], "stream": false}""".toRequestBody("application/json".toMediaType()))
                            .build()

                        val chatResponse = client.newCall(chatRequest).execute()
                        if (chatResponse.isSuccessful) {
                            HealthResult.Healthy(200, "本地模型服务正常")
                        } else {
                            HealthResult.Error("本地模型服务响应异常", chatResponse.code)
                        }
                    }
                    else -> HealthResult.Error(
                        "HTTP ${response.code}",
                        response.code
                    )
                }
            }
        } catch (e: Exception) {
            handleConnectionError(e)
        }
    }

    private fun buildUrl(path: String): String {
        val baseUrl = config.baseUrl.trimEnd('/')
        return "$baseUrl$path"
    }

    private fun handleConnectionError(e: Exception): HealthResult {
        val message = e.message ?: "连接失败"

        return when {
            message.contains("timeout", ignoreCase = true) -> {
                HealthResult.Timeout
            }
            message.contains("refused", ignoreCase = true) ||
            message.contains("Unable to resolve host", ignoreCase = true) -> {
                HealthResult.Unreachable
            }
            message.contains("network", ignoreCase = true) -> {
                HealthResult.NetworkError(message)
            }
            else -> HealthResult.Error(message, null)
        }
    }
}

/**
 * 健康检查结果
 */
sealed class HealthResult {
    /** 服务正常 */
    data class Healthy(val code: Int, val message: String) : HealthResult()

    /** 认证失败 */
    data object Unauthorized : HealthResult()

    /** 权限被禁止 */
    data object Forbidden : HealthResult()

    /** 资源未找到 */
    data object NotFound : HealthResult()

    /** 连接超时 */
    data object Timeout : HealthResult()

    /** 无法到达 */
    data object Unreachable : HealthResult()

    /** 网络错误 */
    data class NetworkError(val message: String) : HealthResult()

    /** 其他错误 */
    data class Error(val message: String, val code: Int?) : HealthResult()

    fun isHealthy(): Boolean = this is Healthy
    fun isAuthError(): Boolean = this is Unauthorized || this is Forbidden

    fun getDisplayMessage(): String {
        return when (this) {
            is Healthy -> "✓ $message"
            is Unauthorized -> "✗ API密钥无效"
            is Forbidden -> "✗ 权限被拒绝"
            is NotFound -> "✗ 端点未找到"
            is Timeout -> "✗ 连接超时"
            is Unreachable -> "✗ 服务不可达"
            is NetworkError -> "✗ 网络错误: $message"
            is Error -> "✗ 错误: $message"
        }
    }
}

/**
 * 批量健康检查器
 */
class BatchHealthChecker(
    private val configs: List<ModelConfig>
) {
    private val results = mutableMapOf<String, HealthResult>()

    /**
     * 执行所有检查
     */
    suspend fun checkAll(): Map<String, HealthResult> {
        results.clear()

        for (config in configs) {
            val checker = HealthChecker(config)
            val result = checker.check()
            results[config.modelId] = result
        }

        return results.toMap()
    }

    /**
     * 获取健康的服务
     */
    fun getHealthyServices(): List<ModelConfig> {
        return configs.filter { results[it.modelId] is HealthResult.Healthy }
    }

    /**
     * 获取失败的服务
     */
    fun getUnhealthyServices(): List<Pair<ModelConfig, HealthResult>> {
        return configs.mapNotNull { config ->
            val result = results[config.modelId]
            if (result != null && !result.isHealthy()) {
                config to result
            } else null
        }
    }
}
