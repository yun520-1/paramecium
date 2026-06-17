package com.heartflow.client

import android.util.Log
import com.heartflow.model.*
import com.heartflow.protocol.ModelProtocol
import com.heartflow.protocol.ModelProtocolFactory
import com.heartflow.tool.ToolCall
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.util.concurrent.atomic.AtomicInteger

/**
 * 模型客户端核心类
 * 负责API调用的完整流程管理
 */
class ModelClient(
    private val config: ModelConfig,
    private val protocol: ModelProtocol = ModelProtocolFactory.createAuto(config)
) {
    companion object {
        private const val TAG = "ModelClient"

        /** 默认最大工具调用次数 */
        private const val DEFAULT_MAX_TOOL_CALLS = 10

        /** 默认推理努力级别 */
        private const val DEFAULT_REASONING_EFFORT = AiBehaviorSettings.REASONING_MEDIUM
    }

    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private val toolCallCount = AtomicInteger(0)
    private var currentCancellationToken: ModelCancellationToken? = null

    /** 流式状态 */
    private val _streamState = MutableStateFlow<StreamState>(StreamState.Idle)
    val streamState: StateFlow<StreamState> = _streamState.asStateFlow()

    /**
     * 发送完整消息并获取响应（非流式）
     */
    suspend fun complete(
        messages: List<ModelMessage>,
        options: ModelRequestOptions? = null
    ): ModelCompletionResponse = withContext(Dispatchers.IO) {
        try {
            _streamState.value = StreamState.Loading
            val response = protocol.complete(config, messages)
            _streamState.value = StreamState.Success(response)
            response
        } catch (e: Exception) {
            _streamState.value = StreamState.Error(e.message ?: "未知错误")
            throw e
        }
    }

    /**
     * 发送流式消息并处理响应（使用内部配置）
     */
    suspend fun stream(
        messages: List<ModelMessage>,
        callback: ModelStreamCallback,
        options: ModelRequestOptions? = null
    ): ModelCompletionResponse = withContext(Dispatchers.IO) {
        val token = ModelCancellationToken()
        currentCancellationToken = token

        try {
            _streamState.value = StreamState.Streaming

            val extendedCallback = createExtendedCallback(callback)
            val response = protocol.stream(config, messages, extendedCallback, token, options)

            _streamState.value = StreamState.Success(response)
            response
        } catch (e: Exception) {
            _streamState.value = StreamState.Error(e.message ?: "未知错误")
            throw e
        } finally {
            currentCancellationToken = null
        }
    }

    /**
     * 发送流式消息并处理响应（使用外部配置）
     */
    suspend fun stream(
        config: ModelConfig,
        messages: List<ModelMessage>,
        callback: ModelStreamCallback,
        options: ModelRequestOptions? = null
    ): ModelCompletionResponse = withContext(Dispatchers.IO) {
        val token = ModelCancellationToken()
        currentCancellationToken = token

        try {
            _streamState.value = StreamState.Streaming

            val extendedCallback = createExtendedCallback(callback)
            val response = protocol.stream(config, messages, extendedCallback, token, options)

            _streamState.value = StreamState.Success(response)
            response
        } catch (e: Exception) {
            _streamState.value = StreamState.Error(e.message ?: "未知错误")
            throw e
        } finally {
            currentCancellationToken = null
        }
    }

    /**
     * 带工具调用的流式消息
     * 自动处理工具调用循环
     */
    suspend fun streamWithTools(
        messages: List<ModelMessage>,
        callback: ModelStreamCallback,
        options: ModelRequestOptions? = null,
        maxToolCalls: Int = DEFAULT_MAX_TOOL_CALLS,
        toolExecutor: suspend (ToolCall) -> String
    ): ModelCompletionResponse = withContext(Dispatchers.IO) {
        var currentMessages = messages.toMutableList()
        val fullTextBuilder = StringBuilder()
        val fullReasoningBuilder = StringBuilder()
        toolCallCount.set(0)

        while (toolCallCount.get() < maxToolCalls) {
            val response = stream(currentMessages, callback, options)

            // 累积文本和推理内容
            fullTextBuilder.append(response.text)
            fullReasoningBuilder.append(response.reasoningContent)

            // 检查是否有工具调用
            val toolCalls = response.toolCalls
            if (toolCalls.isEmpty()) {
                // 没有更多工具调用，返回累积结果
                break
            }

            // 增加工具调用计数
            toolCallCount.incrementAndGet()

            // 执行工具调用并添加工具结果消息
            for (toolCall in toolCalls) {
                val toolResult = try {
                    toolExecutor(toolCall)
                } catch (e: Exception) {
                    "{\"error\": \"${e.message}\"}"
                }

                val toolResultMessage = ToolModelMessage(
                    content = toolResult,
                    toolCallId = toolCall.getId()
                )
                currentMessages = currentMessages.toMutableList()
                currentMessages.add(toolResultMessage)

                callback.onTextDelta("") // 保持流式连接
            }

            // 如果达到最大工具调用次数，停止
            if (toolCallCount.get() >= maxToolCalls) {
                Log.w(TAG, "达到最大工具调用次数: $maxToolCalls")
                break
            }
        }

        val finalResponse = ModelCompletionResponse(
            text = fullTextBuilder.toString(),
            reasoningContent = fullReasoningBuilder.toString(),
            toolCalls = emptyList() // 工具调用已在循环中处理
        )

        _streamState.value = StreamState.Success(finalResponse)
        finalResponse
    }

    /**
     * 取消当前请求
     */
    fun cancel() {
        currentCancellationToken?.cancel()
    }

    /**
     * 重置流状态
     */
    fun resetState() {
        _streamState.value = StreamState.Idle
    }

    /**
     * 释放资源
     */
    fun release() {
        cancel()
        scope.cancel()
    }

    /**
     * 创建扩展回调，添加完成处理
     */
    private fun createExtendedCallback(callback: ModelStreamCallback): ModelStreamCallback {
        return object : ModelStreamCallback {
            override fun onTextDelta(delta: String) {
                callback.onTextDelta(delta)
            }

            override fun onReasoningDelta(delta: String) {
                callback.onReasoningDelta(delta)
            }

            override fun onToolCallStart(index: Int, id: String, name: String) {
                callback.onToolCallStart(index, id, name)
            }

            override fun onToolCallArgument(delta: String, index: Int) {
                callback.onToolCallArgument(delta, index)
            }

            override var onComplete: ((String) -> Unit)?
                get() = callback.onComplete
                set(value) { callback.onComplete = value }

            override var onError: ((String) -> Unit)?
                get() = callback.onError
                set(value) { callback.onError = value }
        }
    }

    /**
     * 获取工具调用计数
     */
    fun getToolCallCount(): Int = toolCallCount.get()
}

/**
 * 流式状态
 */
sealed class StreamState {
    data object Idle : StreamState()
    data object Loading : StreamState()
    data object Streaming : StreamState()
    data class Success(val response: ModelCompletionResponse) : StreamState()
    data class Error(val message: String) : StreamState()
}
