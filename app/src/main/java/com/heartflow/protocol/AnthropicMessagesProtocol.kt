package com.heartflow.protocol

import android.util.Log
import com.heartflow.model.*
import com.heartflow.tool.ToolCall
import com.heartflow.engine.ToolRegistry
import okhttp3.*
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.io.BufferedReader
import java.io.InputStreamReader
import java.nio.charset.StandardCharsets
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/**
 * Anthropic Messages API协议实现
 * 支持Claude系列模型的API调用
 */
class AnthropicMessagesProtocol : ModelProtocol {
    companion object {
        private const val TAG = "AnthropicMessagesProtocol"
        private const val DEFAULT_BASE_URL = "https://api.anthropic.com"
        private const val API_VERSION = "2023-06-01"
    }

    private val client = OkHttpClient.Builder()
        .connectTimeout(30, TimeUnit.SECONDS)
        .readTimeout(180, TimeUnit.SECONDS)
        .writeTimeout(30, TimeUnit.SECONDS)
        .retryOnConnectionFailure(true)
        .build()

    override fun getProtocolType(): ModelProtocolType = ModelProtocolType.ANTHROPIC_MESSAGES

    override fun complete(config: ModelConfig, messages: List<ModelMessage>): ModelCompletionResponse {
        try {
            val body = buildRequestBody(config, messages, stream = false)
            val headers = buildHeaders(config.apiKey)
            val url = buildEndpoint(config.baseUrl, "/v1/messages")
            val response = makeRequest(url, body, headers)
            return parseNonStreamResponse(response)
        } catch (e: Exception) {
            Log.e(TAG, "Complete failed", e)
            throw ModelCompletionException("Anthropic协议请求失败: ${e.message}", e)
        }
    }

    override fun stream(
        config: ModelConfig,
        messages: List<ModelMessage>,
        callback: ModelStreamCallback,
        cancellationToken: ModelCancellationToken?
    ): ModelCompletionResponse {
        return stream(config, messages, callback, cancellationToken, ModelRequestOptions.defaults())
    }

    override fun stream(
        config: ModelConfig,
        messages: List<ModelMessage>,
        callback: ModelStreamCallback,
        cancellationToken: ModelCancellationToken?,
        options: ModelRequestOptions?
    ): ModelCompletionResponse {
        try {
            val requestOptions = options ?: ModelRequestOptions.defaults()
            val body = buildRequestBody(config, messages, stream = true, options = requestOptions)
            val headers = buildHeaders(config.apiKey)
            val url = buildEndpoint(config.baseUrl, "/v1/messages")
            return makeStreamRequest(url, body, headers, cancellationToken, callback, requestOptions)
        } catch (e: Exception) {
            Log.e(TAG, "Stream failed", e)
            if (e is ModelCompletionException) throw e
            throw ModelCompletionException("Anthropic协议流式请求失败: ${e.message}", e)
        }
    }

    private fun buildRequestBody(
        config: ModelConfig,
        messages: List<ModelMessage>,
        stream: Boolean,
        options: ModelRequestOptions? = null
    ): JSONObject {
        val modelId = ModelContextInfo.parse(config.modelId).apiModelId

        return JSONObject().apply {
            put("model", modelId)
            put("messages", messagesToAnthropicFormat(messages))
            put("max_tokens", 4096)
            put("stream", stream)

            // Anthropic特有的beta参数
            if (options?.preserveReasoning == true) {
                put("extra_headers", JSONObject().put("anthropic-beta", "interleaved-thinking-2025-05-14"))
            }

            // 工具调用支持
            options?.tools?.takeIf { tools -> tools.isNotEmpty() }?.let { tools ->
                put("tools", ToolRegistry.toJsonArray(tools))
            }
        }
    }

    private fun buildHeaders(apiKey: String): Headers {
        return Headers.Builder().apply {
            add("x-api-key", apiKey)
            add("anthropic-version", API_VERSION)
            add("Content-Type", "application/json")
        }.build()
    }

    private fun messagesToAnthropicFormat(messages: List<ModelMessage>): JSONArray {
        val array = JSONArray()
        for (message in messages) {
            when (message.role) {
                "user" -> {
                    array.put(JSONObject().apply {
                        put("role", "user")
                        put("content", message.content)
                    })
                }
                "assistant" -> {
                    val contentArray = JSONArray()

                    // 添加推理内容（如果存在）
                    if (message.reasoningContent.isNotEmpty()) {
                        contentArray.put(JSONObject().apply {
                            put("type", "thinking")
                            put("thinking", message.reasoningContent)
                        })
                    }

                    // 添加文本内容
                    if (message.content.isNotEmpty()) {
                        contentArray.put(JSONObject().apply {
                            put("type", "text")
                            put("text", message.content)
                        })
                    }

                    // 添加工具调用
                    message.getToolCalls().forEach { toolCall ->
                        contentArray.put(JSONObject().apply {
                            put("type", "tool_use")
                            put("id", toolCall.getId())
                            put("name", toolCall.getName())
                            put("input", JSONObject(toolCall.getArguments()))
                        })
                    }

                    array.put(JSONObject().apply {
                        put("role", "assistant")
                        put("content", contentArray)
                    })
                }
                "tool" -> {
                    array.put(JSONObject().apply {
                        put("role", "user")
                        put("content", JSONArray().apply {
                            put(JSONObject().apply {
                                put("type", "tool_result")
                                put("tool_use_id", message.getToolCallId())
                                put("content", message.content)
                            })
                        })
                    })
                }
            }
        }
        return array
    }

    private fun parseNonStreamResponse(response: String): ModelCompletionResponse {
        val json = JSONObject(response)
        val content = json.getJSONArray("content")
        if (content.length() == 0) return ModelCompletionResponse.empty()

        val firstBlock = content.getJSONObject(0)
        val type = firstBlock.optString("type", "")

        return when (type) {
            "text" -> {
                val text = firstBlock.optString("text", "")
                ModelCompletionResponse(text)
            }
            else -> ModelCompletionResponse(firstBlock.optString("text", ""))
        }
    }

    private fun makeRequest(url: String, body: JSONObject, headers: Headers): String {
        val request = Request.Builder()
            .url(url)
            .headers(headers)
            .post(body.toString().toRequestBody("application/json".toMediaType()))
            .build()

        return client.newCall(request).execute().use { response ->
            if (!response.isSuccessful) {
                val errorBody = response.body?.string() ?: ""
                throw ModelCompletionException("Anthropic HTTP ${response.code}: $errorBody")
            }
            response.body?.string() ?: throw ModelCompletionException("响应体为空")
        }
    }

    private fun makeStreamRequest(
        url: String,
        body: JSONObject,
        headers: Headers,
        cancellationToken: ModelCancellationToken?,
        callback: ModelStreamCallback,
        options: ModelRequestOptions
    ): ModelCompletionResponse {
        val request = Request.Builder()
            .url(url)
            .headers(headers)
            .post(body.toString().toRequestBody("application/json".toMediaType()))
            .build()

        val textBuilder = StringBuilder()
        val reasoningBuilder = StringBuilder()
        val toolCallBuilders = mutableMapOf<Int, ToolCallBuilder>()
        val latch = CountDownLatch(1)
        val latchReleased = java.util.concurrent.atomic.AtomicBoolean(false)
        var errorMessage: String? = null

        val call = client.newCall(request)
        cancellationToken?.onCancel { call.cancel() }

        call.enqueue(object : Callback {
            override fun onFailure(call: Call, e: java.io.IOException) {
                if (latchReleased.compareAndSet(false, true)) {
                    errorMessage = e.message
                    latch.countDown()
                }
            }

            override fun onResponse(call: Call, response: Response) {
                try {
                    if (!response.isSuccessful) {
                        errorMessage = "Anthropic HTTP ${response.code}"
                        return
                    }

                    response.body?.byteStream()?.use { stream ->
                        val reader = BufferedReader(InputStreamReader(stream, StandardCharsets.UTF_8))
                        var line: String?

                        while (reader.readLine().also { line = it } != null) {
                            val l = line ?: continue
                            if (l.isEmpty()) continue
                            if (!l.startsWith("data: ")) continue

                            val data = l.removePrefix("data: ").trim()
                            if (data.isEmpty()) continue

                            try {
                                val event = JSONObject(data)
                                val type = event.optString("type", "")

                                when (type) {
                                    "content_block_start" -> {
                                        val contentBlock = event.optJSONObject("content_block") ?: continue
                                        val blockType = contentBlock.optString("type", "")
                                        if (blockType == "tool_use") {
                                            val index = event.optInt("index", 0)
                                            val toolName = contentBlock.optString("name", "")
                                            val toolId = contentBlock.optString("id", "")
                                            val builder = toolCallBuilders.getOrPut(index) { ToolCallBuilder() }
                                            builder.name = toolName
                                            builder.id = toolId
                                            callback.onToolCallStart(index, toolId, toolName)
                                        }
                                    }
                                    "content_block_delta" -> {
                                        val delta = event.optJSONObject("delta") ?: continue
                                        val deltaType = delta.optString("type", "")

                                        when (deltaType) {
                                            "text_delta" -> {
                                                val text = delta.optString("text", "")
                                                if (text.isNotEmpty()) {
                                                    textBuilder.append(text)
                                                    callback.onTextDelta(text)
                                                }
                                            }
                                            "thinking_delta" -> {
                                                val thinking = delta.optString("thinking", "")
                                                if (thinking.isNotEmpty()) {
                                                    reasoningBuilder.append(thinking)
                                                    callback.onReasoningDelta(thinking)
                                                }
                                            }
                                            "input_json_delta" -> {
                                                val index = event.optInt("index", 0)
                                                val partialJson = delta.optString("partial_json", "")
                                                val builder = toolCallBuilders.getOrPut(index) { ToolCallBuilder() }
                                                builder.arguments.append(partialJson)
                                                callback.onToolCallArgument(partialJson, index)
                                            }
                                        }
                                    }
                                    "message_delta" -> {
                                        // 可以在这里处理usage信息
                                        val usage = event.optJSONObject("usage")
                                        // 暂不处理
                                    }
                                    "error" -> {
                                        errorMessage = "Anthropic流式错误: ${event.opt("error")}"
                                        break
                                    }
                                }
                            } catch (_: Exception) { }
                        }
                    }
                } catch (e: Exception) {
                    errorMessage = e.message
                } finally {
                    if (latchReleased.compareAndSet(false, true)) {
                        callback.onComplete?.invoke(textBuilder.toString())
                        latch.countDown()
                    }
                }
            }
        })

        try {
            if (!latch.await(180, TimeUnit.SECONDS)) {
                call.cancel()
                throw ModelCompletionException("Anthropic流式请求超时")
            }
        } catch (e: InterruptedException) {
            call.cancel()
            throw ModelCompletionException("请求被中断")
        }

        errorMessage?.let { throw ModelCompletionException(it) }

        val toolCalls = buildToolCalls(toolCallBuilders)
        return ModelCompletionResponse(textBuilder.toString(), reasoningBuilder.toString(), toolCalls)
    }

    private fun buildToolCalls(builders: Map<Int, ToolCallBuilder>): List<ToolCall> {
        return builders.entries.sortedBy { it.key }.mapNotNull { (_, builder) ->
            if (builder.name.isEmpty()) null
            else {
                val id = builder.id.ifEmpty { "toolu_${System.currentTimeMillis()}" }
                val args = builder.arguments.toString().ifEmpty { "{}" }
                ToolCall(id, builder.name, args)
            }
        }
    }

    private class ToolCallBuilder {
        var id: String = ""
        var name: String = ""
        val arguments = StringBuilder()
    }
}
