package com.heartflow.protocol

import com.heartflow.model.*

/**
 * 协议工厂
 * 根据协议类型创建对应的协议实例
 */
object ModelProtocolFactory {
    /**
     * 创建协议实例
     */
    fun create(type: ModelProtocolType): ModelProtocol {
        return when (type) {
            ModelProtocolType.OPENAI_COMPATIBLE -> OpenAiCompatibleProtocol()
            ModelProtocolType.ANTHROPIC_MESSAGES -> AnthropicMessagesProtocol()
            ModelProtocolType.CODEX_RESPONSES -> CodexResponsesProtocol()
            ModelProtocolType.LOCAL_GGUF -> LocalGgufProtocol()
        }
    }

    /**
     * 根据配置自动选择协议
     */
    fun createAuto(config: ModelConfig): ModelProtocol {
        return create(config.protocolType)
    }

    /**
     * 根据baseUrl和modelId自动检测并创建协议
     */
    fun createAuto(baseUrl: String, modelId: String): ModelProtocol {
        val type = ModelProtocolType.autoDetect(baseUrl, modelId)
        return create(type)
    }
}
