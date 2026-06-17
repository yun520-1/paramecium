package com.heartflow.chat

import android.content.Context
import android.util.Log
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.heartflow.client.*
import com.heartflow.context.SlidingWindowContextManager
import com.heartflow.context.CompactionResult
import com.heartflow.model.*
import com.heartflow.tool.ToolCall
import com.heartflow.data.ToolRegistry
import com.heartflow.app.ApiConfig
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import java.io.StringReader
import org.json.JSONObject
import org.json.JSONArray

/**
 * API服务核心
 * 基于新协议层重构的API服务
 */
class ApiServiceViewModel(
    private val context: Context
) : ViewModel() {

    companion object {
        private const val TAG = "ApiServiceViewModel"

        // 消息类型
        const val TYPE_USER = "user"
        const val TYPE_ASSISTANT = "assistant"
        const val TYPE_SYSTEM = "system"
        const val TYPE_TOOL = "tool"
        const val TYPE_ERROR = "error"
    }

    // 状态流
    private val _apiState = MutableStateFlow(ApiState())
    val apiState: StateFlow<ApiState> = _apiState.asStateFlow()

    // 文本流
    private val _textFlow = MutableSharedFlow<String>(replay = 0, extraBufferCapacity = 64)
    val textFlow: SharedFlow<String> = _textFlow.asSharedFlow()

    // 推理流
    private val _reasoningFlow = MutableSharedFlow<String>(replay = 0, extraBufferCapacity = 64)
    val reasoningFlow: SharedFlow<String> = _reasoningFlow.asSharedFlow()

    // 错误流
    private val _errorFlow = MutableSharedFlow<String>(replay = 0, extraBufferCapacity = 8)
    val errorFlow: SharedFlow<String> = _errorFlow.asSharedFlow()

    // 当前API配置
    private val _currentConfig = MutableStateFlow<ApiConfig?>(null)
    val currentConfig: StateFlow<ApiConfig?> = _currentConfig.asStateFlow()

    // 模型客户端
    private var modelClient: ModelClient? = null

    // 上下文管理器
    private var contextManager: SlidingWindowContextManager? = null

    // 工具注册表
    private var toolRegistry: ToolRegistry? = null

    // 协程作用域
    private val scope = viewModelScope + Dispatchers.Main

    // 当前消息构建器
    private var currentTextBuilder = StringBuilder()
    private var currentReasoningBuilder = StringBuilder()

    /**
     * 初始化
     */
    fun initialize(apiConfig: ApiConfig) {
        _currentConfig.value = apiConfig

        // 创建模型客户端
        modelClient = ModelClientFactory.create(apiConfig)

        // 创建上下文管理器
        val contextWindow = ApiConfig.getRecommendedContextWindow(apiConfig.effectiveModel).takeIf { it > 0 } ?: 128000
        contextManager = SlidingWindowContextManager(
            contextWindow = contextWindow,
            reserveTokens = 2000
        )

        Log.d(TAG, "初始化完成: ${apiConfig.effectiveModel}")
    }

    /**
     * 发送消息并获取流式响应
     */
    fun sendMessage(
        userMessage: String,
        systemPrompt: String = "",
        reasoningEnabled: Boolean = false
    ) {
        if (userMessage.isBlank()) return

        viewModelScope.launch {
            try {
                // 重置状态
                currentTextBuilder = StringBuilder()
                currentReasoningBuilder = StringBuilder()

                // 更新状态
                _apiState.value = _apiState.value.copy(
                    isLoading = true,
                    isStreaming = true,
                    error = null
                )

                // 添加系统提示
                if (systemPrompt.isNotBlank()) {
                    contextManager?.setSystemPrompt(systemPrompt)
                }

                // 添加用户消息到上下文
                val userModelMessage = UserModelMessage(userMessage)
                contextManager?.addMessage(userModelMessage)

                // 构建请求选项
                val reasoningEffort = if (reasoningEnabled) {
                    AiBehaviorSettings.REASONING_HIGH
                } else {
                    AiBehaviorSettings.REASONING_MEDIUM
                }

                val requestOptions = ModelRequestOptions(
                    reasoningEffort = reasoningEffort,
                    preserveReasoning = reasoningEnabled,
                    tools = toolRegistry?.getAll()?.map { ToolAdapter(it) } ?: emptyList()
                )

                // 构建消息列表
                val messages = buildMessages(systemPrompt)

                // 创建流式回调
                val callback = createStreamCallback()

                // 执行流式请求
                modelClient?.stream(
                    messages = messages,
                    callback = callback,
                    options = requestOptions
                ) ?: throw Exception("模型客户端未初始化")

                // 检查上下文压缩
                checkContextCompaction()

            } catch (e: CancellationException) {
                Log.d(TAG, "请求被取消")
                _apiState.value = _apiState.value.copy(isLoading = false, isStreaming = false)
            } catch (e: Exception) {
                Log.e(TAG, "API调用失败", e)
                handleError(e)
            }
        }
    }

    /**
     * 发送消息并获取完整响应（非流式）
     */
    suspend fun sendMessageBlocking(
        userMessage: String,
        systemPrompt: String = ""
    ): String {
        try {
            _apiState.value = _apiState.value.copy(isLoading = true, error = null)

            // 构建消息
            val messages = mutableListOf<ModelMessage>()
            if (systemPrompt.isNotBlank()) {
                messages.add(SystemModelMessage(systemPrompt))
            }
            messages.add(UserModelMessage(userMessage))

            // 执行请求
            val response = modelClient?.complete(messages)
                ?: throw Exception("模型客户端未初始化")

            // 发射文本
            if (response.text.isNotBlank()) {
                _textFlow.emit(response.text)
            }

            // 添加到上下文
            contextManager?.addMessage(AssistantModelMessage(response.text))

            _apiState.value = _apiState.value.copy(isLoading = false)
            return response.text

        } catch (e: Exception) {
            _apiState.value = _apiState.value.copy(isLoading = false)
            handleError(e)
            throw e
        }
    }

    /**
     * 执行带工具调用的消息
     */
    fun sendMessageWithTools(
        userMessage: String,
        tools: List<com.heartflow.data.AgentTool>,
        systemPrompt: String = "",
        toolExecutor: suspend (ToolCall) -> String
    ) {
        if (userMessage.isBlank()) return

        viewModelScope.launch {
            try {
                _apiState.value = _apiState.value.copy(isLoading = true, isStreaming = true)

                val messages = buildMessages(systemPrompt)
                messages.add(UserModelMessage(userMessage))

                val requestOptions = ModelRequestOptions(
                    tools = tools.map { ToolAdapter(it) }
                )

                val callback = createStreamCallback()

                val response = modelClient?.streamWithTools(
                    messages = messages,
                    callback = callback,
                    options = requestOptions,
                    toolExecutor = toolExecutor
                ) ?: throw Exception("模型客户端未初始化")

                _apiState.value = _apiState.value.copy(isLoading = false, isStreaming = false)

            } catch (e: CancellationException) {
                _apiState.value = _apiState.value.copy(isLoading = false, isStreaming = false)
            } catch (e: Exception) {
                handleError(e)
            }
        }
    }

    /**
     * 取消当前请求
     */
    fun cancel() {
        modelClient?.cancel()
        _apiState.value = _apiState.value.copy(isLoading = false, isStreaming = false)
    }

    /**
     * 清除上下文
     */
    fun clearContext() {
        contextManager?.clear()
        _apiState.value = _apiState.value.copy(contextCleared = true)
        Log.d(TAG, "上下文已清除")
    }

    /**
     * 获取上下文摘要
     */
    fun getContextSummary(): String {
        return contextManager?.summarize() ?: "上下文管理器未初始化"
    }

    /**
     * 执行上下文压缩
     */
    fun compactContext(): CompactionResult {
        return contextManager?.compaction() ?: CompactionResult(false)
    }

    /**
     * 健康检查
     */
    suspend fun healthCheck(): HealthResult {
        val apiConfig = _currentConfig.value ?: return HealthResult.Error("未配置", null)
        val config = ModelConfig.fromAppApiConfig(apiConfig)
        return HealthChecker(config).check()
    }

    /**
     * 创建流式回调
     */
    private fun createStreamCallback(): ModelStreamCallback {
        val reasoningBuilder = StringBuilder()
        val textBuilder = StringBuilder()

        return object : ModelStreamCallback {
            override fun onTextDelta(delta: String) {
                textBuilder.append(delta)
                currentTextBuilder.append(delta)
                scope.launch {
                    _textFlow.emit(delta)
                }
            }

            override fun onReasoningDelta(delta: String) {
                reasoningBuilder.append(delta)
                currentReasoningBuilder.append(delta)
                scope.launch {
                    _reasoningFlow.emit(delta)
                }
            }

            override var onComplete: ((String) -> Unit)? = { fullText ->
                scope.launch {
                    contextManager?.addMessage(
                        AssistantModelMessage(
                            content = fullText,
                            reasoningContent = reasoningBuilder.toString()
                        )
                    )

                    _apiState.value = _apiState.value.copy(
                        isLoading = false,
                        isStreaming = false,
                        lastResponseLength = fullText.length
                    )

                    Log.d(TAG, "流式响应完成: ${fullText.length} 字符")
                }
            }

            override var onError: ((String) -> Unit)? = { error ->
                scope.launch {
                    handleError(Exception(error))
                }
            }
        }
    }

    /**
     * 构建消息列表
     */
    private fun buildMessages(systemPrompt: String): MutableList<ModelMessage> {
        val messages = mutableListOf<ModelMessage>()

        // 系统提示
        if (systemPrompt.isNotBlank()) {
            messages.add(SystemModelMessage(systemPrompt))
        } else {
            // 从上下文管理器获取系统提示
            val storedSystemPrompt = contextManager?.getSystemPrompt() ?: ""
            if (storedSystemPrompt.isNotBlank()) {
                messages.add(SystemModelMessage(storedSystemPrompt))
            }
        }

        // 历史消息
        contextManager?.getMessages()?.forEach { msg ->
            messages.add(msg)
        }

        return messages
    }

    /**
     * 检查并压缩上下文
     */
    private fun checkContextCompaction() {
        val manager = contextManager ?: return

        if (manager.needsCompaction()) {
            val result = manager.compaction()
            if (result.didCompact) {
                Log.d(TAG, "上下文压缩: 节省 ${result.savedTokens} tokens")
                _apiState.value = _apiState.value.copy(
                    contextCompacted = true,
                    compactionInfo = "节省 ${result.savedTokens} tokens"
                )
            }
        }
    }

    /**
     * 处理错误
     */
    private fun handleError(e: Exception) {
        val errorMessage = e.message ?: "未知错误"

        _apiState.value = _apiState.value.copy(
            isLoading = false,
            isStreaming = false,
            error = errorMessage
        )

        viewModelScope.launch {
            _errorFlow.emit(errorMessage)
        }

        Log.e(TAG, "错误: $errorMessage")
    }

    override fun onCleared() {
        super.onCleared()
        modelClient?.release()
        scope.cancel()
    }
}

/**
 * API状态
 */
data class ApiState(
    val isLoading: Boolean = false,
    val isStreaming: Boolean = false,
    val error: String? = null,
    val contextCompacted: Boolean = false,
    val compactionInfo: String? = null,
    val contextCleared: Boolean = false,
    val lastResponseLength: Int = 0
)

/**
 * API服务工厂
 */
object ApiServiceFactory {
    fun create(context: Context, apiConfig: ApiConfig): ApiServiceViewModel {
        val viewModel = ApiServiceViewModel(context)
        viewModel.initialize(apiConfig)
        return viewModel
    }
}
