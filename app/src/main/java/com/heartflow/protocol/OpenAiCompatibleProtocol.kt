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
 * OpenAI兼容协议实现
 * 支持OpenAI格式的API端点
 */
class OpenAiCompatibleProtocol : ModelProtocol {
    companion object {
        private const val TAG = "OpenAiCompatibleProtocol"
    }

    private val client = OkHttpClient.Builder()
        .connectTimeout(20, TimeUnit.SECONDS)
        .readTimeout(120, TimeUnit.SECONDS)
        .writeTimeout(30, TimeUnit.SECONDS)
        .retryOnConnectionFailure(true)
        .build()

    override fun getProtocolType(): ModelProtocolType = ModelProtocolType.OPENAI_COMPATIBLE

    override fun complete(config: ModelConfig, messages: List<ModelMessage>): ModelCompletionResponse {
        try {
            val body = JSONObject().apply {
                put("model", ModelContextInfo.parse(config.modelId).apiModelId)
                put("messages", messagesToJson(messages))
                put("temperature", 0.2)
            }

            val headers = buildHeaders(config.apiKey)
            val url = buildEndpoint(config.baseUrl, "/chat/completions")
            val response = makeRequest(url, body, headers)
            return parseNonStreamResponse(response)
        } catch (e: Exception) {
            Log.e(TAG, "Complete failed", e)
            throw ModelCompletionException("OpenAI兼容协议请求失败: ${e.message}", e)
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

            val body = JSONObject().apply {
                put("model", ModelContextInfo.parse(config.modelId).apiModelId)
                put("messages", messagesToJson(messages, requestOptions.preserveReasoning))
                put("temperature", 0.2)
                put("stream", true)

                if (requestOptions.tools.isNotEmpty()) {
                    put("tools", ToolRegistry.toJsonArray(requestOptions.tools))
                    put("tool_choice", "auto")
                }

                applyReasoningRequest(config, this, requestOptions)
            }

            val headers = buildHeaders(config.apiKey)
            val url = buildEndpoint(config.baseUrl, "/chat/completions")
            return makeStreamRequest(url, body, headers, cancellationToken, callback)
        } catch (e: Exception) {
            Log.e(TAG, "Stream failed", e)
            if (e is ModelCompletionException) throw e
            throw ModelCompletionException("OpenAI兼容协议流式请求失败: ${e.message}", e)
        }
    }

    private fun buildHeaders(apiKey: String): Headers {
        return Headers.Builder().apply {
            add("Authorization", "Bearer $apiKey")
            add("Content-Type", "application/json")
            add("HTTP-Referer", "https://github.com/heartflow-app")
            add("X-Title", "HeartFlow")
        }.build()
    }

    private fun applyReasoningRequest(config: ModelConfig, body: JSONObject, options: ModelRequestOptions) {
        if (!OpenAiCapabilities.supportsReasoningRequestParameters(config)) return

        val base = config.baseUrl.lowercase()
        val model = config.modelId.lowercase()
        val enabled = options.reasoningEffort != AiBehaviorSettings.REASONING_OFF

        when {
            // 通义千问
            base.contains("dashscope") || base.contains("aliyuncs") || model.contains("qwen") -> {
                body.put("enable_thinking", enabled)
                if (enabled) body.put("thinking_budget", thinkingBudget(options.reasoningEffort))
                if (options.preserveReasoning) body.put("preserve_thinking", true)
            }
            // DeepSeek
            base.contains("deepseek") || model.contains("deepseek") -> {
                body.put("thinking", JSONObject().put("type", if (enabled) "enabled" else "disabled"))
                if (enabled) {
                    val effort = if (options.reasoningEffort == AiBehaviorSettings.REASONING_MAX) "max" else "high"
                    body.put("reasoning_effort", effort)
                }
            }
            // 月之暗面/Moonshot
            base.contains("moonshot") || base.contains("kimi") || model.contains("kimi") -> {
                body.put("thinking", JSONObject().put("type", if (enabled) "enabled" else "disabled"))
            }
            // 智谱/GLM
            base.contains("zhipu") || model.contains("glm") -> {
                body.put("thinking", JSONObject().put("type", if (enabled) "enabled" else "disabled"))
                if (options.preserveReasoning) body.put("clear_thinking", false)
            }
            // 默认
            enabled -> body.put("reasoning", JSONObject().put("effort", options.reasoningEffort))
        }
    }

    private fun thinkingBudget(effort: String): Int {
        return when (effort) {
            AiBehaviorSettings.REASONING_LOW -> 1024
            AiBehaviorSettings.REASONING_HIGH -> 8192
            AiBehaviorSettings.REASONING_MAX -> 16000
            else -> 4096
        }
    }

    private fun messagesToJson(messages: List<ModelMessage>, preserveReasoning: Boolean = false): JSONArray {
        val array = JSONArray()
        for (message in messages) {
            val obj = JSONObject()
            obj.put("role", message.role)

            when {
                message.role == "tool" -> {
                    obj.put("tool_call_id", message.getToolCallId())
                    obj.put("content", message.content)
                }
                message.role == "assistant" && message.getToolCalls().isNotEmpty() -> {
                    obj.put("content", if (message.content.isEmpty()) JSONObject.NULL else message.content)
                    val toolCalls = JSONArray()
                    for (call in message.getToolCalls()) {
                        toolCalls.put(JSONObject().apply {
                            put("id", call.getId())
                            put("type", "function")
                            put("function", JSONObject().apply {
                                put("name", call.getName())
                                put("arguments", call.getArguments())
                            })
                        })
                    }
                    obj.put("tool_calls", toolCalls)
                }
                else -> obj.put("content", message.content)
            }

            if (preserveReasoning && message.role == "assistant" && message.reasoningContent.isNotEmpty()) {
                obj.put("reasoning_content", message.reasoningContent)
            }

            array.put(obj)
        }
        return array
    }

    private fun parseNonStreamResponse(response: String): ModelCompletionResponse {
        val json = JSONObject(response)
        val choices = json.getJSONArray("choices")
        if (choices.length() == 0) return ModelCompletionResponse.empty()

        val message = choices.getJSONObject(0).getJSONObject("message")
        val text = message.optString("content", "")
        return ModelCompletionResponse(text)
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
                throw ModelCompletionException("HTTP ${response.code}: $errorBody")
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
        val startedIndices = mutableSetOf<Int>()   // 跟踪已通知 onToolCallStart 的索引
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
                        errorMessage = "HTTP ${response.code}"
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
                                if (event.has("error")) {
                                    errorMessage = "OpenAI流式错误: ${event.opt("error")}"
                                    break
                                }

                                val choices = event.optJSONArray("choices") ?: continue
                                if (choices.length() == 0) continue

                                val delta = choices.getJSONObject(0).optJSONObject("delta") ?: continue
                                val finishReason = choices.getJSONObject(0).optString("finish_reason", "")

                                if (finishReason == "content_filter") {
                                    errorMessage = "输出被内容安全策略拦截"
                                    break
                                }

                                // 提取推理内容
                                extractReasoningDelta(delta)?.let { delta ->
                                    reasoningBuilder.append(delta)
                                    callback.onReasoningDelta(delta)
                                }

                                // 提取文本内容
                                val content = delta.optString("content", "")
                                if (content.isNotEmpty()) {
                                    textBuilder.append(content)
                                    callback.onTextDelta(content)
                                }

                                // 提取工具调用
                                delta.optJSONArray("tool_calls")?.let { toolCalls ->
                                    appendToolCallDeltas(toolCallBuilders, toolCalls, callback, startedIndices)
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
            if (!latch.await(120, TimeUnit.SECONDS)) {
                call.cancel()
                throw ModelCompletionException("流式请求超时")
            }
        } catch (e: InterruptedException) {
            call.cancel()
            throw ModelCompletionException("请求被中断")
        }

        errorMessage?.let { throw ModelCompletionException(it) }

        val toolCalls = buildToolCalls(toolCallBuilders)
        return ModelCompletionResponse(textBuilder.toString(), reasoningBuilder.toString(), toolCalls)
    }

    private fun extractReasoningDelta(delta: JSONObject): String? {
        if (delta.has("reasoning_content") && !delta.isNull("reasoning_content")) {
            return delta.optString("reasoning_content")
        }

        val reasoning = delta.opt("reasoning")
        if (reasoning is String) return reasoning
        if (reasoning is JSONObject) {
            return reasoning.optString("content")
                .ifEmpty { reasoning.optString("text") }
                .ifEmpty { reasoning.optString("reasoning_content") }
                .takeIf { it.isNotEmpty() }
        }

        return null
    }

    private fun appendToolCallDeltas(
        builders: MutableMap<Int, ToolCallBuilder>,
        toolCalls: JSONArray,
        callback: ModelStreamCallback,
        startedIndices: MutableSet<Int>
    ) {
        for (i in 0 until toolCalls.length()) {
            val item = toolCalls.optJSONObject(i) ?: continue
            val index = item.optInt("index", i)
            val builder = builders.getOrPut(index) { ToolCallBuilder() }

            // 记录是否有新 id/name 被设置
            val hadIdBefore = builder.id.isNotEmpty()
            val hadNameBefore = builder.name.isNotEmpty()

            item.optString("id").takeIf { it.isNotEmpty() }?.let { builder.id = it }

            item.optJSONObject("function")?.let { function ->
                function.optString("name").takeIf { it.isNotEmpty() }?.let { builder.name = it }
                function.optString("arguments").takeIf { it.isNotEmpty() }?.let { args ->
                    builder.arguments.append(args)

                    // 触发 onToolCallStart（仅首次，确保 id 和 name 都就绪）
                    if (index !in startedIndices && builder.id.isNotEmpty() && builder.name.isNotEmpty()) {
                        startedIndices.add(index)
                        callback.onToolCallStart(index, builder.id, builder.name)
                    }

                    // 参数增量通知（在 onToolCallStart 之后）
                    if (index in startedIndices) {
                        callback.onToolCallArgument(args, index)
                    }
                }
            }
        }
    }

    private fun buildToolCalls(builders: Map<Int, ToolCallBuilder>): List<ToolCall> {
        return builders.entries.sortedBy { it.key }.mapNotNull { (_, builder) ->
            if (builder.name.isEmpty()) null
            else {
                val id = builder.id.ifEmpty { "call_${System.currentTimeMillis()}" }
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

/**
 * OpenAI兼容协议的能力检测
 */
object OpenAiCapabilities {
    fun supportsReasoningRequestParameters(config: ModelConfig): Boolean {
        val base = config.baseUrl.lowercase()
        val model = config.modelId.lowercase()
        return base.contains("dashscope") || base.contains("deepseek") ||
               base.contains("moonshot") || base.contains("kimi") ||
               base.contains("zhipu") || base.contains("minimax") ||
               model.contains("qwen") || model.contains("deepseek") ||
               model.contains("glm")
    }
}

/**
 * 扩展函数：onComplete和onError回调
 */
object ExtendedCallbacks {
    fun ModelStreamCallback.onComplete(text: String) {}
    fun ModelStreamCallback.onError(error: String) {}
}
