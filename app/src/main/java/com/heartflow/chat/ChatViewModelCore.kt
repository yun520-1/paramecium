package com.heartflow.chat

import android.content.Context
import android.util.Log
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.heartflow.client.*
import com.heartflow.context.ContextManager
import com.heartflow.context.SlidingWindowContextManager
import com.heartflow.context.CompactionResult
import com.heartflow.engine.SessionKey
import com.heartflow.engine.SessionManagerFactory
import com.heartflow.model.*
import com.heartflow.tool.ToolCall
import com.heartflow.data.ToolRegistry
import com.heartflow.app.ApiConfig
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*

/**
 * ChatViewModel核心
 * 集成新的协议层到聊天功能
 */
class ChatViewModelCore(
    private val context: Context
) : ViewModel() {

    companion object {
        private const val TAG = "ChatViewModelCore"

        /** 默认Token预算 */
        private const val DEFAULT_MAX_TOKENS = 8000

        /** 系统提示Token估算 */
        private const val SYSTEM_PROMPT_TOKENS = 500
    }

    // 状态流
    private val _uiState = MutableStateFlow(ChatUiState())
    val uiState: StateFlow<ChatUiState> = _uiState.asStateFlow()

    // 对话历史
    private val _messages = MutableStateFlow<List<ChatMessage>>(emptyList())
    val messages: StateFlow<List<ChatMessage>> = _messages.asStateFlow()

    // 当前API配置
    private val _currentApiConfig = MutableStateFlow<ApiConfig?>(null)
    val currentApiConfig: StateFlow<ApiConfig?> = _currentApiConfig.asStateFlow()

    // 模型客户端
    private var modelClient: ModelClient? = null

    // 上下文管理器
    private var contextManager: ContextManager? = null

    // 工具注册表
    private val toolRegistry = ToolRegistry

    // 协程作用域
    private val scope = viewModelScope + Dispatchers.Main

    /**
     * 初始化API配置
     */
    fun initializeWithConfig(apiConfig: ApiConfig) {
        _currentApiConfig.value = apiConfig

        // 创建模型客户端
        modelClient = ModelClientFactory.create(apiConfig)

        // 创建上下文管理器
        val contextWindow = ApiConfig.getRecommendedContextWindow(apiConfig.effectiveModel).takeIf { it > 0 } ?: DEFAULT_MAX_TOKENS * 10
        contextManager = SlidingWindowContextManager(
            contextWindow = contextWindow,
            reserveTokens = 2000
        )

        Log.d(TAG, "初始化完成: ${apiConfig.effectiveModel}, 上下文窗口: $contextWindow")
    }

    /**
     * 发送消息
     */
    fun sendMessage(content: String) {
        if (content.isBlank()) return

        scope.launch {
            try {
                // 添加用户消息到UI
                val userMessage = ChatMessage(
                    id = generateMessageId(),
                    role = "user",
                    content = content,
                    timestamp = System.currentTimeMillis()
                )
                _messages.value = _messages.value + userMessage

                // 更新状态为加载中
                _uiState.value = _uiState.value.copy(
                    isLoading = true,
                    error = null
                )

                // 添加用户消息到上下文
                contextManager?.addMessage(UserModelMessage(content))

                // 准备助手消息
                val assistantMessageId = generateMessageId()
                val assistantMessage = ChatMessage(
                    id = assistantMessageId,
                    role = "assistant",
                    content = "",
                    timestamp = System.currentTimeMillis()
                )
                _messages.value = _messages.value + assistantMessage

                // 构建请求选项
                val requestOptions = ModelRequestOptions(
                    reasoningEffort = AiBehaviorSettings.REASONING_MEDIUM,
                    tools = toolRegistry.getAll().map { ToolAdapter(it) }
                )

                // 流式调用
                val callback = createStreamCallback(assistantMessageId)

                modelClient?.stream(
                    messages = buildRequestMessages(),
                    callback = callback,
                    options = requestOptions
                ) ?: throw Exception("模型客户端未初始化")

                // 检查是否需要压缩
                checkAndCompact()

            } catch (e: CancellationException) {
                Log.d(TAG, "请求被取消")
                _uiState.value = _uiState.value.copy(isLoading = false)
            } catch (e: Exception) {
                Log.e(TAG, "发送消息失败", e)
                handleError(e)
            }
        }
    }

    /**
     * 取消当前请求
     */
    fun cancelRequest() {
        modelClient?.cancel()
        scope.launch {
            _uiState.value = _uiState.value.copy(
                isLoading = false,
                isStreaming = false
            )
        }
    }

    /**
     * 清除对话
     */
    fun clearConversation() {
        _messages.value = emptyList()
        contextManager?.clear()
        modelClient?.resetState()
        _uiState.value = ChatUiState()
    }

    /**
     * 保存当前会话
     */
    fun saveSession(title: String? = null) {
        val apiConfig = _currentApiConfig.value ?: return

        scope.launch(Dispatchers.IO) {
            try {
                // 使用 SessionManagerFactory
                val manager = SessionManagerFactory.getManager()
                val sessionKey = SessionKey.build()
                val session = manager.create(sessionKey, title ?: "新会话")

                Log.d(TAG, "会话已保存: ${session.id}")
            } catch (e: Exception) {
                Log.e(TAG, "保存会话失败", e)
            }
        }
    }

    /**
     * 恢复会话
     */
    fun restoreSession(sessionId: String) {
        scope.launch(Dispatchers.IO) {
            try {
                val manager = SessionManagerFactory.getManager()
                val session = manager.get(sessionId)

                withContext(Dispatchers.Main) {
                    _currentApiConfig.value?.let { apiConfig ->
                        com.heartflow.app.ApiConfig(
                            provider = apiConfig.provider,
                            model = apiConfig.effectiveModel
                        )
                    }
                    _messages.value = emptyList()
                    contextManager?.clear()

                    Log.d(TAG, "会话已恢复: $sessionId")
                }
            } catch (e: Exception) {
                Log.e(TAG, "恢复会话失败", e)
            }
        }
    }

    /**
     * 创建流式回调
     */
    private fun createStreamCallback(messageId: String): ModelStreamCallback {
        return object : ModelStreamCallback {
            private val textBuilder = StringBuilder()
            private val reasoningBuilder = StringBuilder()

            override fun onTextDelta(delta: String) {
                textBuilder.append(delta)
                updateAssistantMessage(messageId, textBuilder.toString(), reasoningBuilder.toString())
            }

            override fun onReasoningDelta(delta: String) {
                reasoningBuilder.append(delta)
                updateAssistantMessage(messageId, textBuilder.toString(), reasoningBuilder.toString())
            }

            override var onComplete: ((String) -> Unit)? = { text ->
                scope.launch {
                    _uiState.value = _uiState.value.copy(isLoading = false, isStreaming = false)

                    // 添加助手消息到上下文
                    contextManager?.addMessage(
                        AssistantModelMessage(
                            content = textBuilder.toString(),
                            reasoningContent = reasoningBuilder.toString()
                        )
                    )

                    Log.d(TAG, "消息完成: ${textBuilder.length} 字符")
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
     * 更新助手消息
     */
    private fun updateAssistantMessage(messageId: String, content: String, reasoning: String) {
        _messages.value = _messages.value.map { msg ->
            if (msg.id == messageId) {
                msg.copy(content = content, reasoningContent = reasoning)
            } else msg
        }
        _uiState.value = _uiState.value.copy(isStreaming = true)
    }

    /**
     * 构建请求消息列表
     */
    private fun buildRequestMessages(): List<ModelMessage> {
        val result = mutableListOf<ModelMessage>()

        // 添加系统提示
        val systemPrompt = _currentApiConfig.value?.let { config ->
            // 可以从配置或记忆系统获取系统提示
            getSystemPrompt()
        } ?: getSystemPrompt()

        if (systemPrompt.isNotBlank()) {
            result.add(SystemModelMessage(systemPrompt))
            contextManager?.setSystemPrompt(systemPrompt)
        }

        // 添加历史消息
        contextManager?.getMessages()?.forEach { msg ->
            result.add(msg)
        }

        return result
    }

    /**
     * 获取系统提示
     */
    private fun getSystemPrompt(): String {
        // 可以从记忆系统或配置获取
        return ""
    }

    /**
     * 检查并压缩上下文
     */
    private fun checkAndCompact() {
        val manager = contextManager ?: return

        if (manager.needsCompaction()) {
            scope.launch {
                val result = (manager as? SlidingWindowContextManager)?.addMessageWithAutoCompaction(
                    AssistantModelMessage("")
                ) ?: CompactionResult(didCompact = false)

                if (result.didCompact) {
                    Log.d(TAG, "上下文已压缩: 节省 ${result.savedTokens} tokens")
                    _uiState.value = _uiState.value.copy(
                        contextCompacted = true,
                        compactionInfo = "压缩节省 ${result.savedTokens} tokens"
                    )
                }
            }
        }
    }

    /**
     * 处理错误
     */
    private fun handleError(e: Exception) {
        _uiState.value = _uiState.value.copy(
            isLoading = false,
            isStreaming = false,
            error = e.message ?: "未知错误"
        )

        // 显示错误消息
        val errorMessage = ChatMessage(
            id = generateMessageId(),
            role = "error",
            content = "错误: ${e.message}",
            timestamp = System.currentTimeMillis()
        )
        _messages.value = _messages.value + errorMessage
    }

    /**
     * 生成消息ID
     */
    private fun generateMessageId(): String {
        return "msg_${System.currentTimeMillis()}_${(1000..9999).random()}"
    }

    override fun onCleared() {
        super.onCleared()
        modelClient?.release()
        scope.cancel()
    }
}

/**
 * Chat UI状态
 */
data class ChatUiState(
    val isLoading: Boolean = false,
    val isStreaming: Boolean = false,
    val error: String? = null,
    val contextCompacted: Boolean = false,
    val compactionInfo: String? = null
)

/**
 * Chat消息
 */
data class ChatMessage(
    val id: String,
    val role: String,
    val content: String,
    val reasoningContent: String = "",
    val timestamp: Long = System.currentTimeMillis()
) {
    fun isUser(): Boolean = role == "user"
    fun isAssistant(): Boolean = role == "assistant"
    fun isError(): Boolean = role == "error"
}

/**
 * 工具调用消息扩展
 */
fun ChatMessage.isToolMessage(): Boolean {
    return role == "tool" || content.startsWith("[TOOL_CALL]")
}
