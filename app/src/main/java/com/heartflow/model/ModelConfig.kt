package com.heartflow.model

/**
 * 模型配置
 * 定义协议层的完整配置
 */
data class ModelConfig(
    val id: String = "",
    val name: String = "",
    val protocolType: ModelProtocolType = ModelProtocolType.OPENAI_COMPATIBLE,
    val providerLabel: String = "",
    val baseUrl: String = "",
    val apiKey: String = "",
    val modelId: String = "",
    val toolCallLimit: Int = DEFAULT_TOOL_CALL_LIMIT,
    val compressionModelEnabled: Boolean = false,
    val compressionModelAuto: Boolean = true,
    val compressionModelId: String = ""
) {
    companion object {
        const val DEFAULT_TOOL_CALL_LIMIT = 200
        const val UNLIMITED_TOOL_CALLS = -1
        const val DEFAULT_COMPRESSION_MODEL_AUTO = true

        /**
         * 检查是否支持专用压缩模型
         */
        fun supportsDedicatedCompression(type: ModelProtocolType): Boolean {
            return type == ModelProtocolType.OPENAI_COMPATIBLE || type == ModelProtocolType.CODEX_RESPONSES
        }

        /**
         * 从 com.heartflow.app.ApiConfig 创建
         */
        fun fromAppApiConfig(apiConfig: com.heartflow.app.ApiConfig): ModelConfig {
            return ModelConfig(
                id = apiConfig.provider,
                name = apiConfig.effectiveModel,
                protocolType = ModelProtocolType.autoDetect(apiConfig.effectiveBaseUrl, apiConfig.effectiveModel),
                providerLabel = apiConfig.provider,
                baseUrl = apiConfig.effectiveBaseUrl,
                apiKey = apiConfig.apiKey,
                modelId = apiConfig.effectiveModel,
                toolCallLimit = ModelConfig.DEFAULT_TOOL_CALL_LIMIT
            )
        }
    }

    /**
     * 是否启用压缩模型
     */
    fun isCompressionModelEnabled(): Boolean = compressionModelEnabled && supportsDedicatedCompression(protocolType)

    /**
     * 是否自动选择压缩模型
     */
    fun isCompressionModelAuto(): Boolean = compressionModelAuto

    /**
     * 获取有效的压缩模型ID
     */
    fun getEffectiveCompressionModelId(): String {
        return if (!compressionModelEnabled || compressionModelAuto || compressionModelId.isEmpty()) {
            modelId
        } else {
            compressionModelId
        }
    }

    /**
     * 创建带新ID的配置
     */
    fun withId(nextId: String): ModelConfig = copy(id = nextId)

    /**
     * 创建带新模型ID的配置
     */
    fun withModelId(nextModelId: String): ModelConfig = copy(modelId = nextModelId)
}
