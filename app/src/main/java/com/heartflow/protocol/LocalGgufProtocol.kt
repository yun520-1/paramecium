package com.heartflow.protocol

import android.util.Log
import com.heartflow.model.*
import com.heartflow.tool.ToolCall
import com.heartflow.engine.ToolRegistry
import okhttp3.*
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.MediaType.Companion.toMediaType
import org.json.JSONArray
import org.json.JSONObject
import java.io.BufferedReader
import java.io.InputStreamReader
import java.nio.charset.StandardCharsets
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/**
 * 本地GGUF模型协议实现
 * 支持Ollama等本地模型API
 */
class LocalGgufProtocol : ModelProtocol {
    companion object {
        private const val TAG = "LocalGgufProtocol"
        private const val DEFAULT_BASE_URL = "http://localhost:11434"
    }

    private val client = OkHttpClient.Builder()
        .connectTimeout(10, TimeUnit.SECONDS)
        .readTimeout(300, TimeUnit.SECONDS)
        .writeTimeout(30, TimeUnit.SECONDS)
        .retryOnConnectionFailure(false)
        .build()

    override fun getProtocolType(): ModelProtocolType = ModelProtocolType.LOCAL_GGUF

    override fun complete(config: ModelConfig, messages: List<ModelMessage>): ModelCompletionResponse {
        try {
            val body = buildRequestBody(config, messages, stream = false)
            val headers = buildHeaders()
            val url = buildEndpoint(config.baseUrl.ifEmpty { DEFAULT_BASE_URL }, "/api/chat")
            val response = makeRequest(url, body, headers)
            return parseNonStreamResponse(response)
        } catch (e: Exception) {
            Log.e(TAG, "Complete failed", e)
            throw ModelCompletionException("本地模型协议请求失败: ${e.message}", e)
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
            val headers = buildHeaders()
            val url = buildEndpoint(config.baseUrl.ifEmpty { DEFAULT_BASE_URL }, "/api/chat")
            return makeStreamRequest(url, body, headers, cancellationToken, callback)
        } catch (e: Exception) {
            Log.e(TAG, "Stream failed", e)
            if (e is ModelCompletionException) throw e
            throw ModelCompletionException("本地模型流式请求失败: ${e.message}", e)
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
            put("messages", messagesToOllamaFormat(messages))
            put("stream", stream)

            // 本地模型特有参数
            options?.reasoningEffort?.takeIf { it != AiBehaviorSettings.REASONING_OFF }?.let {
                // Ollama支持keep_alive等参数
                put("keep_alive", "5m")
            }

            // 工具调用支持（如果Ollama版本支持）
            options?.tools?.takeIf { tools -> tools.isNotEmpty() }?.let { tools ->
                put("tools", ToolRegistry.toJsonArray(tools))
            }
        }
    }

    private fun buildHeaders(): Headers {
        return Headers.Builder().apply {
            add("Content-Type", "application/json")
        }.build()
    }

    private fun messagesToOllamaFormat(messages: List<ModelMessage>): JSONArray {
        val array = JSONArray()
        for (message in messages) {
            val obj = JSONObject()
            obj.put("role", message.role)
            obj.put("content", message.content)
            array.put(obj)
        }
        return array
    }

    private fun parseNonStreamResponse(response: String): ModelCompletionResponse {
        val json = JSONObject(response)
        val message = json.optJSONObject("message") ?: return ModelCompletionResponse.empty()

        val content = message.optString("content", "")
        return ModelCompletionResponse(content)
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
                throw ModelCompletionException("本地模型 HTTP ${response.code}: $errorBody")
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
        val toolCallBuilders = mutableMapOf<Int, ToolCallBuilder>()
        val latch = CountDownLatch(1)
        var errorMessage: String? = null

        val call = client.newCall(request)
        cancellationToken?.onCancel { call.cancel() }

        call.enqueue(object : Callback {
            override fun onFailure(call: Call, e: java.io.IOException) {
                errorMessage = e.message
                latch.countDown()
            }

            override fun onResponse(call: Call, response: Response) {
                try {
                    if (!response.isSuccessful) {
                        errorMessage = "本地模型 HTTP ${response.code}"
                        latch.countDown()
                        return
                    }

                    response.body?.byteStream()?.use { stream ->
                        val reader = BufferedReader(InputStreamReader(stream, StandardCharsets.UTF_8))
                        var line: String?

                        while (reader.readLine().also { line = it } != null) {
                            val l = line ?: continue
                            if (l.isEmpty()) continue

                            try {
                                val event = JSONObject(l)
                                val message = event.optJSONObject("message") ?: continue

                                // 提取文本内容
                                val content = message.optString("content", "")
                                if (content.isNotEmpty()) {
                                    textBuilder.append(content)
                                    callback.onTextDelta(content)
                                }

                                // 提取工具调用
                                message.optJSONArray("tool_calls")?.let { toolCalls ->
                                    appendToolCallDeltas(toolCallBuilders, toolCalls)
                                }

                                // 检查结束标志
                                if (event.optBoolean("done", false)) {
                                    break
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
                throw ModelCompletionException("本地模型流式请求超时")
            }
        } catch (e: InterruptedException) {
            call.cancel()
            throw ModelCompletionException("请求被中断")
        }

        errorMessage?.let { throw ModelCompletionException(it) }

        val toolCalls = buildToolCalls(toolCallBuilders)
        return ModelCompletionResponse(textBuilder.toString(), "", toolCalls)
    }

    private fun appendToolCallDeltas(builders: MutableMap<Int, ToolCallBuilder>, toolCalls: JSONArray) {
        for (i in 0 until toolCalls.length()) {
            val item = toolCalls.optJSONObject(i) ?: continue
            val index = i
            val builder = builders.getOrPut(index) { ToolCallBuilder() }

            item.optString("name").takeIf { it.isNotEmpty() }?.let { builder.name = it }
            item.optString("id").takeIf { it.isNotEmpty() }?.let { builder.id = it }
            item.optJSONObject("function")?.let { function ->
                function.optString("arguments").takeIf { it.isNotEmpty() }?.let { builder.arguments.append(it) }
            }
        }
    }

    private fun buildToolCalls(builders: Map<Int, ToolCallBuilder>): List<ToolCall> {
        return builders.entries.sortedBy { it.key }.mapNotNull { (_, builder) ->
            if (builder.name.isEmpty()) null
            else {
                val id = builder.id.ifEmpty { "local_${System.currentTimeMillis()}" }
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
