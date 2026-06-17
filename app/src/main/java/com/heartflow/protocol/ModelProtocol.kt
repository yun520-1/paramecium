package com.heartflow.protocol

import com.heartflow.model.*
import com.heartflow.tool.ToolCall

/**
 * 模型协议接口
 * 定义不同API协议的实现规范
 */
interface ModelProtocol {
    /**
     * 非流式完成调用
     */
    fun complete(config: ModelConfig, messages: List<ModelMessage>): ModelCompletionResponse

    /**
     * 流式完成调用
     */
    fun stream(
        config: ModelConfig,
        messages: List<ModelMessage>,
        callback: ModelStreamCallback,
        cancellationToken: ModelCancellationToken?
    ): ModelCompletionResponse

    /**
     * 流式完成调用（带选项）
     */
    fun stream(
        config: ModelConfig,
        messages: List<ModelMessage>,
        callback: ModelStreamCallback,
        cancellationToken: ModelCancellationToken?,
        options: ModelRequestOptions?
    ): ModelCompletionResponse

    /**
     * 构建API端点URL
     */
    fun buildEndpoint(baseUrl: String, suffix: String): String {
        val base = baseUrl.trimEnd('/')
        return if (base.endsWith(suffix)) base else "$base$suffix"
    }

    /**
     * 获取协议类型
     */
    fun getProtocolType(): ModelProtocolType
}
