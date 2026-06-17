package com.heartflow.client

import android.util.Log
import com.heartflow.app.ApiConfig
import com.heartflow.app.StreamCallback
import com.heartflow.app.ToolCallData
import com.heartflow.model.ModelMessage
import com.heartflow.model.ModelRequestOptions
import com.heartflow.model.ModelStreamCallback
import com.heartflow.model.ModelCancellationToken
import com.heartflow.model.ModelCompletionResponse
import com.heartflow.model.ToolAdapter
import com.heartflow.model.SystemModelMessage
import com.heartflow.model.AssistantModelMessage
import com.heartflow.model.ToolModelMessage
import com.heartflow.model.UserModelMessage
import com.heartflow.data.AgentTool
import kotlinx.coroutines.*
import org.json.JSONObject
import kotlin.coroutines.coroutineContext

/**
 * 模型客户端桥接器
 * 将新协议层（ModelClient）与现有 ApiService 桥接
 *
 * 职责：
 * 1. 适配现有 StreamCallback 接口到新协议层
 * 2. 转换 ToolCall 格式
 * 3. 提供统一的 API 调用接口
 */
class ModelClientBridge {
    companion object {
        private const val TAG = "ModelClientBridge"
    }

    private var modelClient: ModelClient? = null
    private var currentConfig: ApiConfig? = null

    /**
     * 初始化客户端
     */
    fun initialize(config: ApiConfig) {
        currentConfig = config
        modelClient = ModelClientFactory.create(config)
        Log.d(TAG, "初始化完成: ${config.effectiveModel} @ ${config.effectiveBaseUrl}")
    }

    /**
     * 发送消息（使用新协议层）
     * @return true 表示成功，false 表示失败
     */
    suspend fun sendMessage(
        userMessage: String?,
        systemPrompt: String,
        tools: List<AgentTool>?,
        callback: StreamCallback,
        extraMessages: List<Map<String, Any>>? = null
    ): Boolean = withContext(Dispatchers.IO) {
        val client = modelClient ?: run {
            callback.onError("客户端未初始化")
            return@withContext false
        }

        try {
            // 转换消息格式
            val messages = buildMessages(userMessage, systemPrompt, extraMessages)

            // 转换工具格式
            val options = if (tools != null && tools.isNotEmpty()) {
                ModelRequestOptions(
                    tools = tools.map { ToolAdapter(it) }
                )
            } else {
                ModelRequestOptions.defaults()
            }

            // 创建流式回调适配器
            val streamCallbackAdapter = object : ModelStreamCallback {
                private val fullText = StringBuilder()
                private val toolCallBuilder = mutableMapOf<Int, ToolCallDataBuilder>()

                override fun onTextDelta(delta: String) {
                    fullText.append(delta)
                    callback.onToken(delta)
                }

                override fun onReasoningDelta(delta: String) {
                    // 推理内容暂不在UI显示
                }

                override fun onToolCallStart(index: Int, id: String, name: String) {
                    toolCallBuilder.getOrPut(index) { ToolCallDataBuilder() }.apply {
                        this.id = id
                        this.name = name
                    }
                }

                override fun onToolCallArgument(delta: String, index: Int) {
                    toolCallBuilder.getOrPut(index) { ToolCallDataBuilder() }
                        .arguments.append(delta)
                }

                override var onComplete: ((String) -> Unit)? = { fullResponse ->
                    // 检查是否有工具调用
                    if (toolCallBuilder.isNotEmpty()) {
                        val toolCalls = toolCallBuilder.entries.sortedBy { it.key }.map { (_, b) ->
                            ToolCallData(id = b.id, name = b.name, arguments = b.arguments.toString())
                        }
                        callback.onToolCalls(toolCalls)
                    } else {
                        callback.onComplete(fullResponse)
                    }
                }

                override var onError: ((String) -> Unit)? = { error ->
                    callback.onError(error)
                }
            }

            // 调用协议层（使用 ModelClient 内部创建 cancellationToken）
            client.stream(
                messages = messages,
                callback = streamCallbackAdapter,
                options = options
            )
            // 如果没有抛出异常，认为调用成功
            true

        } catch (e: CancellationException) {
            callback.onError("请求已取消")
            false
        } catch (e: Exception) {
            Log.e(TAG, "API调用失败", e)
            callback.onError(e.message ?: "API调用失败")
            false
        }
    }

    /**
     * 取消当前请求
     */
    fun cancel() {
        modelClient?.cancel()
    }

    /**
     * 构建消息列表
     */
    private fun buildMessages(
        userMessage: String?,
        systemPrompt: String,
        extraMessages: List<Map<String, Any>>?
    ): List<ModelMessage> {
        val messages = mutableListOf<ModelMessage>()

        // 系统提示
        messages.add(SystemModelMessage(systemPrompt))

        // 额外消息（历史对话 + 工具调用）
        extraMessages?.forEach { msg ->
            val role = msg["role"]?.toString() ?: return@forEach
            val rawContent = msg["content"]
            val content = when {
                rawContent == null || rawContent == JSONObject.NULL -> ""
                else -> rawContent.toString()
            }

            when (role) {
                "assistant" -> {
                    val toolCalls = msg["tool_calls"] as? List<*>
                    if (toolCalls != null) {
                        val convertedToolCalls = convertToolCalls(toolCalls).map { tc ->
                            com.heartflow.tool.ToolCall(tc["id"] ?: "", tc["name"] ?: "", tc["arguments"] ?: "{}")
                        }
                        messages.add(AssistantModelMessage(content, toolCalls = convertedToolCalls))
                    } else {
                        messages.add(AssistantModelMessage(content))
                    }
                }
                "tool" -> {
                    val toolCallId = msg["tool_call_id"]?.toString() ?: ""
                    messages.add(ToolModelMessage(content, toolCallId))
                }
                "user" -> {
                    messages.add(UserModelMessage(content))
                }
            }
        }

        // 当前用户消息（首次请求）
        if (!userMessage.isNullOrBlank()) {
            messages.add(UserModelMessage(userMessage))
        }

        return messages
    }

    /**
     * 转换工具调用格式
     */
    @Suppress("UNCHECKED_CAST")
    private fun convertToolCalls(toolCalls: List<*>): List<Map<String, String>> {
        return toolCalls.mapNotNull { tc ->
            when (tc) {
                is Map<*, *> -> {
                    val id = tc["id"]?.toString() ?: return@mapNotNull null
                    val func = tc["function"] as? Map<*, *>
                    val name = func?.get("name")?.toString() ?: return@mapNotNull null
                    val arguments = func["arguments"]?.toString() ?: "{}"
                    mapOf("id" to id, "name" to name, "arguments" to arguments)
                }
                else -> null
            }
        }
    }

    /**
     * 工具调用数据构建器
     */
    private class ToolCallDataBuilder {
        var id: String = ""
        var name: String = ""
        val arguments = StringBuilder()
    }
}
