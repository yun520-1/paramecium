package com.heartflow.model

/**
 * API配置数据类
 * 定义API调用的完整配置
 */
data class ApiConfig(
    val id: String = "",
    val name: String = "",
    val protocolType: ModelProtocolType = ModelProtocolType.OPENAI_COMPATIBLE,
    val providerLabel: String = "",
    val baseUrl: String = "",
    val apiKey: String = "",
    val modelId: String = "",
    val temperature: Double = 0.7,
    val maxTokens: Int = 4096,
    val toolCallLimit: Int = 200,
    val reasoningEffort: String = AiBehaviorSettings.REASONING_MEDIUM
) {
    /**
     * 解析模型ID获取API模型ID（去除上下文后缀）
     */
    fun getEffectiveModel(): String = ModelContextInfo.parse(modelId).apiModelId

    /**
     * 获取上下文窗口大小
     */
    fun getContextWindow(): Int = ModelContextInfo.parse(modelId).contextTokens

    /**
     * 获取有效的基础URL
     */
    fun getEffectiveBaseUrl(): String = baseUrl.trimEnd('/')

    /**
     * 计算有效模型名称
     */
    val effectiveModelName: String by lazy {
        when {
            name.isNotBlank() -> name
            modelId.isNotBlank() -> ModelContextInfo.parse(modelId).apiModelId
            else -> "API"
        }
    }

    /**
     * 创建ModelConfig用于协议层
     */
    fun toModelConfig(): ModelConfig {
        return ModelConfig(
            id = id,
            name = effectiveModelName,
            protocolType = protocolType,
            providerLabel = providerLabel.ifEmpty { protocolType.label },
            baseUrl = getEffectiveBaseUrl(),
            apiKey = apiKey,
            modelId = modelId,
            toolCallLimit = toolCallLimit
        )
    }

    companion object {
        /**
         * 创建默认配置
         */
        fun defaults(): ApiConfig = ApiConfig()
    }
}
