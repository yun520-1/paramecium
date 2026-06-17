package com.heartflow.client

import com.heartflow.model.*
import com.heartflow.protocol.ModelProtocol
import com.heartflow.protocol.ModelProtocolFactory
import com.heartflow.data.AgentTool

/**
 * 模型客户端工厂
 * 提供便捷的客户端创建方式
 */
object ModelClientFactory {

    /**
     * 根据ApiConfig创建客户端（支持 com.heartflow.app.ApiConfig）
     */
    fun create(apiConfig: com.heartflow.app.ApiConfig): ModelClient {
        val modelConfig = ModelConfig.fromAppApiConfig(apiConfig)
        return ModelClient(modelConfig)
    }

    /**
     * 根据ModelConfig创建客户端
     */
    fun create(modelConfig: ModelConfig): ModelClient {
        val protocol = ModelProtocolFactory.createAuto(modelConfig)
        return ModelClient(modelConfig, protocol)
    }

    /**
     * 根据baseUrl和modelId创建客户端
     */
    fun create(baseUrl: String, modelId: String, apiKey: String): ModelClient {
        val protocolType = ModelProtocolType.autoDetect(baseUrl, modelId)
        val modelConfig = ModelConfig(
            baseUrl = baseUrl,
            modelId = modelId,
            apiKey = apiKey,
            protocolType = protocolType
        )
        return ModelClient(modelConfig)
    }

    /**
     * 从预配置创建（使用默认协议）
     */
    fun createDefault(
        baseUrl: String,
        modelId: String,
        apiKey: String,
        protocolType: ModelProtocolType = ModelProtocolType.OPENAI_COMPATIBLE
    ): ModelClient {
        val modelConfig = ModelConfig(
            baseUrl = baseUrl,
            modelId = modelId,
            apiKey = apiKey,
            protocolType = protocolType
        )
        return ModelClient(modelConfig)
    }

    /**
     * 创建支持工具调用的客户端
     */
    fun createWithTools(
        apiConfig: com.heartflow.app.ApiConfig,
        tools: List<com.heartflow.data.AgentTool>
    ): ModelClient {
        val modelConfig = ModelConfig.fromAppApiConfig(apiConfig)
        val options = ModelRequestOptions(
            tools = tools.map { ToolAdapter(it) }
        )
        return ModelClient(modelConfig)
    }
}

/**
 * 客户端池管理
 * 用于管理多个客户端实例
 */
class ModelClientPool(
    private val maxPoolSize: Int = 5
) {
    private val pool = LinkedHashMap<String, ModelClient>()
    private val lock = Any()

    /**
     * 获取或创建客户端
     */
    fun getOrCreate(
        apiConfig: com.heartflow.app.ApiConfig,
        factory: (com.heartflow.app.ApiConfig) -> ModelClient = { ModelClientFactory.create(it) }
    ): ModelClient {
        val key = apiConfig.provider

        synchronized(lock) {
            // 尝试从池中获取
            pool[key]?.let { return it }

            // 如果池已满，移除最旧的
            if (pool.size >= maxPoolSize) {
                val oldestKey = pool.keys.firstOrNull()
                oldestKey?.let {
                    pool.remove(it)?.release()
                }
            }

            // 创建新客户端
            val client = factory(apiConfig)
            pool[key] = client
            return client
        }
    }

    /**
     * 移除并释放客户端
     */
    fun remove(apiConfigId: String) {
        synchronized(lock) {
            pool.remove(apiConfigId)?.release()
        }
    }

    /**
     * 清空并释放所有客户端
     */
    fun clear() {
        synchronized(lock) {
            pool.values.forEach { it.release() }
            pool.clear()
        }
    }

    /**
     * 获取池大小
     */
    fun size(): Int = synchronized(lock) { pool.size }
}
