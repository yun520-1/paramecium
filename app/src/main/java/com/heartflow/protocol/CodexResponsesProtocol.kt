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
 * Codex Responses API协议实现
 * 支持OpenAI Codex模型的responses API
 */
class CodexResponsesProtocol : ModelProtocol {
    companion object {
        private const val TAG = "CodexResponsesProtocol"
    }

    private val client = OkHttpClient.Builder()
        .connectTimeout(30, TimeUnit.SECONDS)
        .readTimeout(300, TimeUnit.SECONDS)
        .writeTimeout(30, TimeUnit.SECONDS)
        .retryOnConnectionFailure(true)
        .build()

    override fun getProtocolType(): ModelProtocolType = ModelProtocolType.CODEX_RESPONSES

    override fun complete(config: ModelConfig, messages: List<ModelMessage>): ModelCompletionResponse {
        try {
            val body = buildRequestBody(config, messages, stream = false)
            val headers = buildHeaders(config.apiKey)
            val url = buildEndpoint(config.baseUrl, "/v1/responses")
            val response = makeRequest(url, body, headers)
            return parseNonStreamResponse(response)
        } catch (e: Exception) {
            Log.e(TAG, "Complete failed", e)
            throw ModelCompletionException("Codex协议请求失败: ${e.message}", e)
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
            val url = buildEndpoint(config.baseUrl, "/v1/responses")
            return makeStreamRequest(url, body, headers, cancellationToken, callback)
        } catch (e: Exception) {
            Log.e(TAG, "Stream failed", e)
            if (e is ModelCompletionException) throw e
            throw ModelCompletionException("Codex协议流式请求失败: ${e.message}", e)
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
            put("input", messagesToCodexFormat(messages))
            put("stream", stream)

            // temperature设置
            put("temperature", 0.2)

            // 工具调用
            options?.tools?.takeIf { tools -> tools.isNotEmpty() }?.let { tools ->
                put("tools", ToolRegistry.toJsonArray(tools))
            }

            // reasoning相关
            options?.reasoningEffort?.takeIf { it != AiBehaviorSettings.REASONING_OFF }?.let {
                put("thinking", JSONObject().put("type", "enabled"))
            }
        }
    }

    private fun buildHeaders(apiKey: String): Headers {
        return Headers.Builder().apply {
            add("Authorization", "Bearer $apiKey")
            add("Content-Type", "application/json")
            add("OpenAI-Beta", "responses-2025-05-01")
        }.build()
    }

    private fun messagesToCodexFormat(messages: List<ModelMessage>): JSONArray {
        val array = JSONArray()
        for (message in messages) {
            when (message.role) {
                "system" -> {
                    array.put(JSONObject().apply {
                        put("role", "system")
                        put("content", message.content)
                    })
                }
                "user" -> {
                    array.put(JSONObject().apply {
                        put("role", "user")
                        put("content", message.content)
                    })
                }
                "assistant" -> {
                    val contentArray = JSONArray()

                    // 推理内容
                    if (message.reasoningContent.isNotEmpty()) {
                        contentArray.put(JSONObject().apply {
                            put("type", "reasoning")
                            put("reasoning", message.reasoningContent)
                        })
                    }

                    // 文本内容
                    if (message.content.isNotEmpty()) {
                        contentArray.put(JSONObject().apply {
                            put("type", "output_text")
                            put("text", message.content)
                        })
                    }

                    // 工具调用
                    message.getToolCalls().forEach { toolCall ->
                        contentArray.put(JSONObject().apply {
                            put("type", "function_call")
                            put("id", toolCall.getId())
                            put("name", toolCall.getName())
                            put("arguments", toolCall.getArguments())
                        })
                    }

                    array.put(JSONObject().apply {
                        put("role", "assistant")
                        put("content", contentArray)
                    })
                }
                "tool" -> {
                    array.put(JSONObject().apply {
                        put("role", "tool")
                        put("content", message.content)
                        put("tool_call_id", message.getToolCallId())
                    })
                }
            }
        }
        return array
    }

    private fun parseNonStreamResponse(response: String): ModelCompletionResponse {
        val json = JSONObject(response)
        val outputs = json.optJSONArray("output")
        if (outputs == null || outputs.length() == 0) return ModelCompletionResponse.empty()

        val textBuilder = StringBuilder()
        val reasoningBuilder = StringBuilder()
        val toolCalls = mutableListOf<ToolCall>()

        for (i in 0 until outputs.length()) {
            val output = outputs.getJSONObject(i)
            when (output.optString("type")) {
                "message" -> {
                    val content = output.optJSONArray("content")
                    if (content != null) {
                        for (j in 0 until content.length()) {
                            val item = content.getJSONObject(j)
                            when (item.optString("type")) {
                                "output_text" -> textBuilder.append(item.optString("text", ""))
                                "reasoning" -> reasoningBuilder.append(item.optString("reasoning", ""))
                            }
                        }
                    }
                }
                "function_call" -> {
                    val id = output.optString("id", "")
                    val name = output.optString("name", "")
                    val args = output.optString("arguments", "{}")
                    toolCalls.add(ToolCall(id, name, args))
                }
            }
        }

        return ModelCompletionResponse(textBuilder.toString(), reasoningBuilder.toString(), toolCalls)
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
                throw ModelCompletionException("Codex HTTP ${response.code}: $errorBody")
            }
            response.body?.string() ?: throw ModelCompletionException("响应体为空")
        }
    }

    private fun makeStreamRequest(
        url: String,
        body: JSONObject,
        headers: Headers,
        cancellationToken: ModelCancellationToken?,
        callback: ModelStreamCallback
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
        var errorMessage: String? = null

        val call = client.newCall(request)
        cancellationToken?.onCancel { call.cancel() }

        call.enqueue(object : Callback {
            override fun onFailure(req: Call, e: java.io.IOException) {
                errorMessage = e.message
                latch.countDown()
            }

            override fun onResponse(req: Call, response: Response) {
                try {
                    if (!response.isSuccessful) {
                        errorMessage = "Codex HTTP ${response.code}"
                        latch.countDown()
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
                            if (data == "[DONE]") break

                            try {
                                val event = JSONObject(data)
                                val item = event.optJSONObject("item") ?: continue
                                val type = item.optString("type", "")

                                when (type) {
                                    "message" -> {
                                        val content = item.optJSONArray("content")
                                        if (content != null) {
                                            for (i in 0 until content.length()) {
                                                val contentItem = content.getJSONObject(i)
                                                when (contentItem.optString("type")) {
                                                    "output_text" -> {
                                                        val text = contentItem.optString("text", "")
                                                        if (text.isNotEmpty()) {
                                                            textBuilder.append(text)
                                                            callback.onTextDelta(text)
                                                        }
                                                    }
                                                    "reasoning" -> {
                                                        val reasoning = contentItem.optString("reasoning", "")
                                                        if (reasoning.isNotEmpty()) {
                                                            reasoningBuilder.append(reasoning)
                                                            callback.onReasoningDelta(reasoning)
                                                        }
                                                    }
                                                }
                                            }
                                        }
                                    }
                                    "function_call" -> {
                                        val index = event.optInt("index", toolCallBuilders.size)
                                        val builder = toolCallBuilders.getOrPut(index) { ToolCallBuilder() }
                                        builder.id = item.optString("id", builder.id)
                                        builder.name = item.optString("name", builder.name)
                                        val argsDelta = item.optString("arguments", "")
                                        if (argsDelta.isNotEmpty()) builder.arguments.append(argsDelta)
                                    }
                                    "error" -> {
                                        errorMessage = "Codex流式错误: ${item.opt("error")}"
                                        break
                                    }
                                }
                            } catch (_: Exception) { }
                        }
                    }

                    callback.onComplete?.invoke(textBuilder.toString())
                    latch.countDown()
                } catch (e: Exception) {
                    errorMessage = e.message
                    latch.countDown()
                }
            }
        })

        try {
            if (!latch.await(300, TimeUnit.SECONDS)) {
                call.cancel()
                throw ModelCompletionException("Codex流式请求超时")
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
                val id = builder.id.ifEmpty { "fc_${System.currentTimeMillis()}" }
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
