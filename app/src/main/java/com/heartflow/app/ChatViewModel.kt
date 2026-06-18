package com.heartflow.app

import android.app.Application
import android.util.Log
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.heartflow.app.tts.TextToSpeechManager
import com.heartflow.app.views.BrowserCommand
import com.heartflow.client.ModelClientBridge
import com.heartflow.data.*
import com.heartflow.engine.HeartEngine
import com.heartflow.engine.HeartResult
import com.heartflow.memory.MemorySystem
import com.heartflow.memory.MemoryLayer
import kotlinx.coroutines.*
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.channels.Channel.Factory.CONFLATED
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import org.json.JSONObject
import java.util.concurrent.ConcurrentHashMap

data class ToolCallUiData(
    val name: String,
    val status: String = "running", // "running", "success", "error"
    val result: String? = null,
    val durationMs: Long? = null
)

data class ChatUiMessage(
    val content: String,
    val isUser: Boolean,
    val isLoading: Boolean = false,
    val isError: Boolean = false,
    val emotion: String? = null,
    val isStreaming: Boolean = false,
    val isCode: Boolean = false,
    val isVoice: Boolean = false,
    val toolResults: List<String> = emptyList(),
    val toolCalls: List<ToolCallUiData> = emptyList(),
    val runningTool: String? = null,
    val attachment: MediaAttachment? = null
)

data class VoiceUiState(
    val isRecording: Boolean = false,
    val recordingDuration: Int = 0, // seconds
    val partialResult: String? = null,   // 语音识别部分结果（实时显示）
    val voiceError: String? = null,      // 语音识别错误消息
    val audioFile: String? = null,
    val isPlaying: Boolean = false,
    val transcription: String? = null
)

/**
 * 工具执行事件 — 通过 SharedFlow 发出，供 UI 层实时收集展示
 * 每个事件代表工具执行的某个阶段（开始/日志/成功/错误）
 */
data class ToolExecutionEvent(
    val toolIndex: Int,
    val toolName: String,
    val logLine: String,
    val status: String = "log", // "running", "log", "success", "error"
    val timestamp: Long = System.currentTimeMillis()
)

data class ChatUiState(
    val messages: List<ChatUiMessage> = emptyList(),
    val isProcessing: Boolean = false,
    val config: ApiConfig? = null,
    val showSettings: Boolean = false,
    val currentEmotion: String? = null,
    val conversations: List<Conversation> = emptyList(),
    val currentConversationId: String? = null,
    val personality: Personality = Personalities.default,
    val userProfile: UserProfile = UserProfile(),
    val themeMode: ThemeMode = ThemeMode.SYSTEM,
    val themeVariant: ThemeVariant = ThemeVariant.AURORA_PURPLE,
    val currentPage: String = "chat",
    val fontSize: Float = 14f,
    val voiceState: VoiceUiState = VoiceUiState(),
    val speakingMessageIndex: Int? = null
)

class ChatViewModel(application: Application) : AndroidViewModel(application) {
    private val modelClientBridge = ModelClientBridge()
    private val apiHistory = mutableListOf<Map<String, Any>>()
    private var currentApiConfig: ApiConfig? = null
    private val historyManager = ChatHistoryManager(application)
    private val prefs = AppPreferences(application)
    val profile = UserProfile.load(application)
    val memorySystem = MemorySystem(application)

    private val _uiState = MutableStateFlow(ChatUiState())
    val uiState: StateFlow<ChatUiState> = _uiState.asStateFlow()

    /** 工具执行进度事件管道 — 无重放，缓冲 64 条，溢出丢弃旧事件 */
    private val _toolExecutionFlow = MutableSharedFlow<ToolExecutionEvent>(
        replay = 0,
        extraBufferCapacity = 64,
        onBufferOverflow = kotlinx.coroutines.channels.BufferOverflow.DROP_OLDEST
    )
    val toolExecutionFlow: SharedFlow<ToolExecutionEvent> = _toolExecutionFlow.asSharedFlow()

    /**
     * 浏览器导航命令 — 工具调用通过此流发送浏览器控制命令
     * 暴露为公开属性，供 HeartFlowApp 收集并传递给 BrowserViewModel
     */
    private val _browserCommand = MutableSharedFlow<BrowserCommand>(
        replay = 1,
        extraBufferCapacity = 16,
        onBufferOverflow = kotlinx.coroutines.channels.BufferOverflow.DROP_OLDEST
    )
    val browserCommand: SharedFlow<BrowserCommand> = _browserCommand.asSharedFlow()

    /** 发送浏览器导航命令 — 工具执行结果中可调用此方法 */
    fun sendBrowserCommand(command: BrowserCommand) {
        viewModelScope.launch {
            try {
                _browserCommand.emit(command)
            } catch (e: Exception) {
                Log.e(TAG, "发送浏览器命令失败: ${e.message}")
            }
        }
    }

    private var currentJob: Job? = null
    private val cancelledToolIndices = ConcurrentHashMap.newKeySet<Int>()
    @Volatile private var isLoopCancelled = false
    companion object { private const val TAG = "ChatViewModel" }
    val ttsManager = TextToSpeechManager(getApplication())
    private var audioInputManager: AudioInputManager? = null
    private var durationJob: Job? = null
    private var startTimeMs: Long = 0

    init {
        ToolRegistry.init(application)
        val savedConfig = ApiConfig.load(application)
        val personality = Personalities.getById(prefs.personalityId)
        val conversations = historyManager.loadAll()

        _uiState.value = ChatUiState(
            messages = listOf(
                ChatUiMessage("你好！我是${personality.name}${personality.emoji}", isUser = false),
                ChatUiMessage(personality.description, isUser = false),
                ChatUiMessage(personality.purpose, isUser = false)
            ),
            config = savedConfig,
            personality = personality,
            userProfile = profile,
            themeMode = prefs.themeMode,
            themeVariant = prefs.themeVariant,
            conversations = conversations,
            fontSize = prefs.fontSize
        )
        ttsManager.pitch = prefs.ttsPitch
        ttsManager.speed = prefs.ttsSpeed
    }

    fun sendMessage(text: String) {
        if (text.isBlank() || _uiState.value.isProcessing) return

        // 取消之前的请求（如果仍在运行）
        modelClientBridge.cancel()

        val config = _uiState.value.config
        if (config == null || config.apiKey.isBlank()) {
            _uiState.value = _uiState.value.copy(
                messages = _uiState.value.messages + ChatUiMessage(
                    "请先点击 ⚙️ 配置API", isUser = false, isError = true
                )
            )
            return
        }

        val heartResult = HeartEngine.analyze(text)
        val personality = _uiState.value.personality

        _uiState.value = _uiState.value.copy(
            messages = _uiState.value.messages +
                ChatUiMessage(text, isUser = true, emotion = heartResult.emotion) +
                ChatUiMessage("", isUser = false, isStreaming = true, isLoading = true),
            isProcessing = true,
            currentEmotion = heartResult.emotion
        )

        memorySystem.store(text, MemoryLayer.WORKING, tags = heartResult.tags,
            importance = if (heartResult.isPain) 3 else 1, emotion = heartResult.emotion, source = "user")

        // 自动获取上下文记忆（基于 HeartEngine 标签匹配）
        val memoryContext = memorySystem.getContextString(tags = heartResult.tags)

        viewModelScope.launch {
            currentJob = coroutineContext[Job]
            isLoopCancelled = false
            val parentJob = coroutineContext[Job]
            try {
                // 初始化 ModelClientBridge（如配置变更新建客户端）
                if (currentApiConfig != config) {
                    modelClientBridge.initialize(config)
                    currentApiConfig = config
                }
                withTimeout(180000L) {
                    runAgentLoop(text, heartResult, personality, config, memoryContext, agentJob = parentJob)
                }
            } catch (_: TimeoutCancellationException) {
                val msg = "⏱️ 响应超时，已自动中止"
                _uiState.value = _uiState.value.copy(messages = _uiState.value.messages +
                    ChatUiMessage(msg, isUser = false, isError = true),
                    isProcessing = false, currentEmotion = null)
                // 确保超时后显示完成状态
                _toolExecutionFlow.tryEmit(ToolExecutionEvent(-1, "系统", "⏱️ 任务超时", "error"))
            } catch (_: CancellationException) {
                // 用户手动停止，不追加错误
                // 但需要重置状态
                if (_uiState.value.isProcessing) {
                    _uiState.value = _uiState.value.copy(isProcessing = false, currentEmotion = null)
                }
            } finally {
                currentJob = null
                isLoopCancelled = false
                // 确保finally块中也重置状态
                if (_uiState.value.isProcessing) {
                    _uiState.value = _uiState.value.copy(isProcessing = false, currentEmotion = null)
                }
            }
        }
    }

    private sealed class LoopSignal {
        data object Continue : LoopSignal()
        data object Complete : LoopSignal()
        data class Break(val reason: String) : LoopSignal()
    }

    private suspend fun runAgentLoop(
        userText: String,
        heartResult: HeartResult,
        personality: Personality,
        config: ApiConfig,
        memoryContext: String,
        extraMessages: List<Map<String, Any>>? = null,
        agentJob: Job? = null
    ) {
        val safeAgentJob = agentJob
        var streamingIndex = _uiState.value.messages.lastIndex
        // 首次请求的 userMessage 只在第一次循环传入；后续循环通过 extraMessages 传递
        var isFirstLoop = true
        val streamingText = StringBuilder()
        var currentExtraMessages = if (extraMessages != null) {
            apiHistory + extraMessages
        } else if (apiHistory.isNotEmpty()) {
            apiHistory
        } else null
        var loopCount = 0
        val maxLoops = 5
        val toolResultsWindow = ArrayDeque<Boolean>(5)
        var retryDelayMs = 1000L
        val maxRetryDelayMs = 4000L
        // 收集本轮所有工具结果摘要，用于循环结束后强制获取最终回答
        val allToolResultsSummary = StringBuilder()

        // 上下文压缩：当消息超过 300 条时，压缩早期 50% 消息为摘要
        fun compressHistoryIfNeeded() {
            val msgs = _uiState.value.messages
            val maxHistory = 300
            if (msgs.size <= maxHistory) return
            val keepCount = maxHistory / 2 // 保留最近一半
            val compressCount = msgs.size - keepCount
            val compressedContent = msgs.take(compressCount)
                .filter { it.content.isNotBlank() }
                .takeLast(50) // 最多取 50 条内容做摘要
                .joinToString(" | ") {
                    if (it.isUser) "用户: ${it.content.take(100)}"
                    else "AI: ${it.content.take(100)}"
                }
            val summary = ChatUiMessage(
                content = "📌 较早的 ${compressCount} 条消息已压缩为摘要 | $compressedContent",
                isUser = false, isCode = true
            )
            val newMsgs = mutableListOf<ChatUiMessage>()
            newMsgs.add(summary)
            newMsgs.addAll(msgs.drop(compressCount))
            _uiState.value = _uiState.value.copy(messages = newMsgs)
        }

        try {
            compressHistoryIfNeeded()
            while (loopCount < maxLoops && !isLoopCancelled) {
                loopCount++
                val channel = Channel<LoopSignal>(CONFLATED)
                Log.d(TAG, "AgentLoop #$loopCount: extraMessages=${currentExtraMessages?.size ?: 0}")
                val disabledSet = prefs.disabledTools
                val systemPrompt = HeartEngine.buildSystemPrompt(
                    personality = personality,
                    userProfile = _uiState.value.userProfile,
                    heartResult = heartResult,
                    toolDescriptions = if (prefs.enableTools) {
                        val allTools = ToolRegistry.getAll().filter { it.name !in disabledSet }
                        allTools.joinToString("\n") { "- ${it.name}: ${it.description}" }
                    } else null,
                    customPrompt = prefs.systemPrompt.ifBlank { null },
                    memoryContext = memoryContext
                )

                try {
                    // 首次循环传 userText，后续循环通过 extraMessages 传递工具调用上下文
                    val currentUserMessage = if (isFirstLoop) userText else null
                    isFirstLoop = false
                    withTimeout(120000L) {
                        modelClientBridge.sendMessage(
                            userMessage = currentUserMessage,
                            systemPrompt = systemPrompt,
                            tools = if (prefs.enableTools) ToolRegistry.getAll().filter { it.name !in disabledSet } else null,
                            extraMessages = currentExtraMessages,
                            callback = object : StreamCallback {
                                override fun onToken(token: String) {
                                    if (isLoopCancelled) return
                                    viewModelScope.launch(Dispatchers.Main) {
                                        streamingText.append(token)
                                        val msgs = _uiState.value.messages.toMutableList()
                                        if (streamingIndex < msgs.size) {
                                            msgs[streamingIndex] = ChatUiMessage(
                                                content = streamingText.toString(), isUser = false, isStreaming = true
                                            )
                                            _uiState.value = _uiState.value.copy(messages = msgs)
                                        }
                                    }
                                }

                                override fun onReasoningDelta(reasoning: String) {
                                    // 深度思考内容可在这里处理（如显示折叠的思考过程）
                                    // 目前默认不显示，只在需要时通过调试日志输出
                                    Log.d("ChatViewModel", "推理内容: ${reasoning.take(100)}...")
                                }

                                override fun onComplete(fullText: String) {
                                    viewModelScope.launch(Dispatchers.Main) {
                                        updateMessage(streamingIndex, fullText)
                                        _uiState.value = _uiState.value.copy(isProcessing = false, currentEmotion = null)
                                        saveCurrentConversation()
                                        // 将助手最终回答写入API历史（user消息由executeRequest自动添加）
                                        apiHistory.add(mapOf("role" to "assistant", "content" to fullText))
                                        memorySystem.store(fullText, MemoryLayer.EPISODIC,
                                            tags = heartResult.tags + "assistant", importance = 1, source = "assistant")
                                        _toolExecutionFlow.tryEmit(ToolExecutionEvent(-1, "系统", "✅ 任务完成", "success"))
                                        channel.trySend(LoopSignal.Complete)
                                    }
                                }

                                override fun onToolCalls(calls: List<ToolCallData>) {
                                    // 在异步并行前保存 streamingText 的快照（同一线程，onToken 不会再被调用）
                                    val previousContent = streamingText.toString()
                                    // 在主线程更新 Compose 状态
                                    viewModelScope.launch(Dispatchers.Main) {
                                        streamingIndex = _uiState.value.messages.lastIndex
                                        val toolCallUis = calls.map { ToolCallUiData(name = it.name, status = "running") }
                                        val msgs = _uiState.value.messages.toMutableList()
                                        msgs[streamingIndex] = ChatUiMessage(
                                            content = previousContent,
                                            isUser = false,
                                            toolCalls = toolCallUis,
                                            runningTool = calls.firstOrNull()?.name
                                        )
                                        _uiState.value = _uiState.value.copy(messages = msgs)
                                        streamingText.clear()
                                    }

                                    // 在 agentJob（withTimeout 子作用域 Job）中执行工具
                                    val toolJob = safeAgentJob ?: Job()
                                    val toolJobDeferred = viewModelScope.async(toolJob + Dispatchers.Default) {
                                        if (isLoopCancelled) return@async LoopSignal.Break("已取消")
                                        var runningToolName: String? = calls.firstOrNull()?.name
                                        val toolResults = executeToolCalls(calls) { index, status, result, durationMs ->
                                            if (isLoopCancelled) return@executeToolCalls
                                            // 在主线程更新 Compose 状态
                                            viewModelScope.launch(Dispatchers.Main) {
                                                runningToolName = if (status == "running") calls.getOrNull(index)?.name
                                                    else {
                                                        val stillRunning = _uiState.value.messages.lastOrNull()
                                                            ?.toolCalls?.any { it.status == "running" } == true
                                                        if (stillRunning) runningToolName else null
                                                    }
                                                val current = _uiState.value.messages.toMutableList()
                                                val last = current.lastOrNull()?.copy(
                                                    toolCalls = current.last().toolCalls.toMutableList().also { list ->
                                                        if (index < list.size) {
                                                            list[index] = list[index].copy(status = status, result = result, durationMs = durationMs)
                                                        }
                                                    },
                                                    runningTool = runningToolName
                                                )
                                                if (last != null) current[current.lastIndex] = last
                                                _uiState.value = _uiState.value.copy(messages = current)
                                            }
                                        }

                                        val allFailed = toolResults.all { it.startsWith("❌") }
                                        toolResultsWindow.addLast(allFailed)
                                        if (toolResultsWindow.size > 5) toolResultsWindow.removeFirst()
                                        val recentFailureCount = toolResultsWindow.count { it }
                                        val shouldFuse = toolResultsWindow.size >= 3 && recentFailureCount >= 3

                                        val assistantMsg = buildToolCallMessage(calls, previousContent)
                                        val resultMsgs = buildToolResultMessages(calls, toolResults)
                                        // 在主线程读取最新状态（而非使用快照），防止非主线程突变导致崩溃
                                        viewModelScope.launch(Dispatchers.Main) {
                                            val currentMsgs = _uiState.value.messages.toMutableList()
                                            currentMsgs.add(ChatUiMessage("", isUser = false, isStreaming = true, isLoading = true))
                                            _uiState.value = _uiState.value.copy(messages = currentMsgs)
                                        }
                                        // 累加而非替换
                                        val previousExtra = currentExtraMessages ?: emptyList()
                                        currentExtraMessages = previousExtra + listOf(assistantMsg) + resultMsgs

                                        // 工具执行完成提示
                                        val successCount = toolResults.count { it.startsWith("✅") }
                                        val failCount = toolResults.count { it.startsWith("❌") }
                                        val completionMessage = when {
                                            successCount == toolResults.size -> "✅ 工具执行完成"
                                            failCount == toolResults.size -> "❌ 工具执行失败"
                                            else -> "✅ 工具执行完成 (${successCount}/${toolResults.size} 成功)"
                                        }
                                        _toolExecutionFlow.tryEmit(ToolExecutionEvent(-1, "系统", completionMessage, "success"))

                                        calls.zip(toolResults).forEach { (call, result) ->
                                            allToolResultsSummary.appendLine("【${call.name}】结果：${result.take(500)}")
                                        }

                                        if (shouldFuse) LoopSignal.Break("工具失败 ${recentFailureCount}/5，已自动中止")
                                        else LoopSignal.Continue
                                    }

                                    // 带超时等待工具执行结果（最多60秒）
                                    viewModelScope.launch(Dispatchers.Main) {
                                        try {
                                            val toolSignal = withTimeout(60_000L) { toolJobDeferred.await() }
                                            channel.trySend(toolSignal)
                                            if (toolSignal is LoopSignal.Continue) {
                                                _toolExecutionFlow.tryEmit(ToolExecutionEvent(-1, "系统", "✅ 任务完成", "success"))
                                            }
                                        } catch (_: TimeoutCancellationException) {
                                            toolJobDeferred.cancel()
                                            _toolExecutionFlow.tryEmit(ToolExecutionEvent(-1, "系统", "⏱️ 工具执行超时（60秒）", "error"))
                                            channel.trySend(LoopSignal.Break("工具执行超时（60秒），已自动中止"))
                                        } catch (_: CancellationException) {
                                            _toolExecutionFlow.tryEmit(ToolExecutionEvent(-1, "系统", "❌ 工具执行被取消", "error"))
                                            channel.trySend(LoopSignal.Break("工具执行被取消"))
                                        } catch (e: Exception) {
                                            _toolExecutionFlow.tryEmit(ToolExecutionEvent(-1, "系统", "❌ 工具执行异常: ${e.message}", "error"))
                                            channel.trySend(LoopSignal.Break("工具执行异常: ${e.message}"))
                                        }
                                    }
                                }

                                override fun onError(error: String) {
                                    viewModelScope.launch(Dispatchers.Main) {
                                        val msgs = _uiState.value.messages.toMutableList()
                                        if (streamingIndex < msgs.size) msgs.removeAt(streamingIndex)
                                        msgs.add(ChatUiMessage("❌ $error", isUser = false, isError = true))
                                        _uiState.value = _uiState.value.copy(messages = msgs, isProcessing = false, currentEmotion = null)
                                        // 显示错误状态提示
                                        _toolExecutionFlow.tryEmit(ToolExecutionEvent(-1, "系统", "❌ 任务失败: $error", "error"))
                                        channel.trySend(LoopSignal.Complete)
                                    }
                                }
                            }
                        )
                    }
                } catch (_: TimeoutCancellationException) {
                    val errorMsg = "⏱️ 单次请求超时（30秒），已自动中止"
                    val msgs = _uiState.value.messages.toMutableList()
                    if (streamingIndex < msgs.size && (msgs[streamingIndex].isLoading || msgs[streamingIndex].isStreaming)) {
                        msgs[streamingIndex] = ChatUiMessage(content = errorMsg, isUser = false, isError = true)
                    } else {
                        msgs.add(ChatUiMessage(content = errorMsg, isUser = false, isError = true))
                    }
                    _uiState.value = _uiState.value.copy(messages = msgs, isProcessing = false, currentEmotion = null)
                    saveCurrentConversation()
                    break
                }

                val signal = try {
                    withTimeout(120_000L) { channel.receive() }
                } catch (_: TimeoutCancellationException) {
                    Log.w(TAG, "等待工具执行信号超时(120s)")
                    _toolExecutionFlow.tryEmit(ToolExecutionEvent(-1, "系统", "⏱️ 等待工具执行信号超时", "error"))
                    LoopSignal.Break("等待工具执行结果超时，已自动中止")
                }
                when (signal) {
                    is LoopSignal.Complete -> break
                    is LoopSignal.Break -> {
                        val msg = "⛔ ${signal.reason}"
                        _uiState.value = _uiState.value.copy(messages = _uiState.value.messages +
                            ChatUiMessage(msg, isUser = false, isError = true),
                            isProcessing = false, currentEmotion = null)
                        break
                    }
                    is LoopSignal.Continue -> {
                        // 刷新 streamingIndex 指向新添加的流式消息位
                        streamingIndex = _uiState.value.messages.lastIndex
                        // 指数退避：如果最近有工具失败，延迟再试
                        if (toolResultsWindow.isNotEmpty() && toolResultsWindow.last()) {
                            delay(retryDelayMs)
                            retryDelayMs = (retryDelayMs * 2).coerceAtMost(maxRetryDelayMs)
                        } else {
                            retryDelayMs = 1000L // 成功后重置
                        }
                    }
                }
            }

            // ===== 循环结束后：如果没得到最终回答，强制不带tools请求一次 =====
            val lastMsg = _uiState.value.messages.lastOrNull()
            val hasFinalAnswer = lastMsg != null && !lastMsg.isUser && !lastMsg.isLoading &&
                lastMsg.content.isNotBlank() && lastMsg.toolCalls.isEmpty()
            if (!hasFinalAnswer && allToolResultsSummary.isNotEmpty()) {
                streamingIndex = _uiState.value.messages.lastIndex
                streamingText.clear()
                // 添加loading消息
                val msgs = _uiState.value.messages.toMutableList()
                msgs.add(ChatUiMessage("", isUser = false, isStreaming = true, isLoading = true))
                _uiState.value = _uiState.value.copy(messages = msgs)
                streamingIndex = _uiState.value.messages.lastIndex

                val summaryPrompt = "根据以上工具执行结果，请直接给出最终回答，不要再调用任何工具。\n\n工具执行结果摘要：\n$allToolResultsSummary"
                val summaryExtra = (currentExtraMessages ?: emptyList()) + listOf(
                    mapOf("role" to "user", "content" to summaryPrompt)
                )
                try {
                    withTimeout(30000L) {
                        modelClientBridge.sendMessage(
                            userMessage = null,
                            systemPrompt = HeartEngine.buildSystemPrompt(
                                personality = personality,
                                userProfile = _uiState.value.userProfile,
                                heartResult = heartResult,
                                toolDescriptions = null,
                                customPrompt = "你是心虫AI助手。用户请求了搜索/查询任务，工具已经执行完毕并返回了结果。请根据工具结果直接给出最终回答。绝对不要再调用任何工具。",
                                memoryContext = memoryContext
                            ),
                            tools = null,
                            extraMessages = summaryExtra,
                            callback = object : StreamCallback {
                                override fun onToken(token: String) {
                                    if (isLoopCancelled) return
                                    streamingText.append(token)
                                    val contentSnapshot = streamingText.toString()
                                    viewModelScope.launch(Dispatchers.Main) {
                                        val m = _uiState.value.messages.toMutableList()
                                        if (streamingIndex < m.size) {
                                            m[streamingIndex] = ChatUiMessage(
                                                content = contentSnapshot, isUser = false, isStreaming = true
                                            )
                                            _uiState.value = _uiState.value.copy(messages = m)
                                        }
                                    }
                                }

                                override fun onReasoningDelta(reasoning: String) {
                                    Log.d("ChatViewModel", "推理内容: ${reasoning.take(100)}...")
                                }

                                override fun onComplete(fullText: String) {
                                    viewModelScope.launch(Dispatchers.Main) {
                                        updateMessage(streamingIndex, fullText)
                                        apiHistory.add(mapOf("role" to "assistant", "content" to fullText))
                                        _toolExecutionFlow.tryEmit(ToolExecutionEvent(-1, "系统", "✅ 任务完成", "success"))
                                    }
                                }
                                override fun onToolCalls(calls: List<ToolCallData>) {
                                    Log.w(TAG, "摘要循环中模型返回了${calls.size}个工具调用（不应发生），已忽略")
                                }
                                override fun onError(error: String) {
                                    viewModelScope.launch(Dispatchers.Main) {
                                        updateMessage(streamingIndex, "⚠️ 生成回答失败: $error")
                                    }
                                }
                            }
                        )
                    }
                } catch (_: Exception) {
                    // 超时或异常，显示工具结果摘要作为兜底
                    val fallback = allToolResultsSummary.toString().take(2000)
                    viewModelScope.launch(Dispatchers.Main) {
                        updateMessage(streamingIndex, "📋 工具执行结果：\n$fallback")
                    }
                }
            }

        } finally {
            if (_uiState.value.isProcessing) {
                _uiState.value = _uiState.value.copy(isProcessing = false, currentEmotion = null)
            }
        }
    }

    /**
     * 判断模型是否支持 tool/function calling（某些模型如 deepseek-reasoner 不支持）
     */
    private fun supportsToolCalling(config: ApiConfig): Boolean {
        val model = config.effectiveModel
        // deepseek-reasoner 不支持 function calling
        if (model.contains("reasoner", ignoreCase = true)) return false
        return true
    }

    private suspend fun executeToolCalls(
        calls: List<ToolCallData>,
        onProgress: ((index: Int, status: String, result: String?, durationMs: Long?) -> Unit)? = null
    ): List<String> {
        val results = arrayOfNulls<String>(calls.size)

        // ── 特殊处理：open_browser 工具直接控制内置浏览器 ─────────────────
        calls.forEachIndexed { index, call ->
            if (call.name == "open_browser") {
                try {
                    val args = parseJsonArgs(call.arguments)
                    val url = args["url"]?.toString() ?: ""
                    if (url.isNotBlank()) {
                        val formattedUrl = formatUrl(url)
                        sendBrowserCommand(BrowserCommand.LoadUrl(formattedUrl))
                        onProgress?.invoke(index, "success", "已在浏览器中打开: $formattedUrl", 0)
                        results[index] = "✅ open_browser: 已在浏览器中打开 $formattedUrl"
                    } else {
                        onProgress?.invoke(index, "error", "缺少 url 参数", null)
                        results[index] = "❌ open_browser: 缺少 url 参数"
                    }
                } catch (e: Exception) {
                    onProgress?.invoke(index, "error", e.message, null)
                    results[index] = "❌ open_browser: ${e.message}"
                }
            }
        }

        // 执行其他正常工具（排除已处理的 open_browser）
        withContext(Dispatchers.IO.limitedParallelism(4)) {
            val deferred = calls.mapIndexed { index, call ->
                async {
                    // 跳过已处理的 open_browser
                    if (call.name == "open_browser") return@async results[index]

                    // 检查是否已被单独取消
                    if (cancelledToolIndices.contains(index)) {
                        onProgress?.invoke(index, "error", "已取消", null)
                        return@async "❌ ${call.name}: 已取消"
                    }
                    val tool = ToolRegistry.getByName(call.name)
                    if (tool != null) {
                        val startTime = System.currentTimeMillis()
                        try {
                            _toolExecutionFlow.tryEmit(ToolExecutionEvent(index, call.name, "🔄 开始执行...", "running"))
                            onProgress?.invoke(index, "running", null, null)
                            val args = withTimeout(prefs.toolTimeoutMs) {
                                parseJsonArgs(call.arguments)
                            }
                            _toolExecutionFlow.tryEmit(ToolExecutionEvent(index, call.name, "参数解析完成，共 ${args.size} 个参数", "log"))
                            val result = withTimeout(prefs.toolTimeoutMs) {
                                tool.execute(args)
                            }
                            val elapsed = System.currentTimeMillis() - startTime
                            _toolExecutionFlow.tryEmit(ToolExecutionEvent(index, call.name, "✅ 成功 (${elapsed}ms)", "success"))
                            onProgress?.invoke(index, "success", result, elapsed)
                            "✅ ${call.name} (${elapsed}ms): $result"
                        } catch (e: TimeoutCancellationException) {
                            val elapsed = System.currentTimeMillis() - startTime
                            _toolExecutionFlow.tryEmit(ToolExecutionEvent(index, call.name, "⏱️ 超时 (${elapsed}ms)", "error"))
                            onProgress?.invoke(index, "error", "超时", elapsed)
                            "❌ ${call.name}: 执行超时"
                        } catch (e: Exception) {
                            val elapsed = System.currentTimeMillis() - startTime
                            _toolExecutionFlow.tryEmit(ToolExecutionEvent(index, call.name, "❌ ${e.message}", "error"))
                            onProgress?.invoke(index, "error", e.message, elapsed)
                            "❌ ${call.name}: ${e.message}"
                        }
                    } else {
                        onProgress?.invoke(index, "error", "未知工具", null)
                        "❌ ${call.name}: 未知工具"
                    }
                }
            }
            deferred.awaitAll().forEachIndexed { i, result -> if (results[i] == null) results[i] = result }
        }

        return results.filterNotNull()
    }

    /** URL 格式化：自动补全 http/https 或作为搜索词 */
    private fun formatUrl(input: String): String {
        return when {
            input.startsWith("http://") || input.startsWith("https://") -> input
            input.contains(".") -> "https://$input"
            else -> "https://www.baidu.com/s?wd=$input"
        }
    }

    private fun buildToolCallMessage(calls: List<ToolCallData>, content: String = ""): Map<String, Any> {
        val toolCalls = calls.map { call ->
            mapOf(
                "id" to call.id,
                "type" to "function",
                "function" to mapOf("name" to call.name, "arguments" to call.arguments)
            )
        }
        val msg = mutableMapOf<String, Any>("role" to "assistant", "tool_calls" to toolCalls)
        // ⚠️ DeepSeek 等模型要求：有 tool_calls 时 content 必须为 null 或不传
        // 仅保留模型在 tool_calls 之前返回的非空文本内容
        if (content.isNotBlank()) {
            msg["content"] = content
        } else {
            msg["content"] = JSONObject.NULL  // 明确设为 null
        }
        return msg
    }

    private fun buildToolResultMessages(calls: List<ToolCallData>, results: List<String>): List<Map<String, Any>> {
        return calls.mapIndexed { i, call ->
            mapOf("role" to "tool", "tool_call_id" to call.id, "content" to results.getOrElse(i) { "执行失败" })
        }
    }

    private fun parseJsonArgs(json: String): Map<String, Any> {
        return try {
            val obj = org.json.JSONObject(json)
            val result = mutableMapOf<String, Any>()
            val iter = obj.keys()
            while (iter.hasNext()) {
                val key = iter.next()
                val v = obj.get(key)
                result[key] = when (v) {
                    is Number -> v
                    is Boolean -> v
                    else -> v.toString()
                }
            }
            result
        } catch (e: Exception) {
            Log.w(TAG, "stopGeneration/JSON parsing failed", e)
            emptyMap()
        }
    }

    private fun updateMessage(index: Int, content: String) {
        // 必须在主线程调用（直接读写 MutableStateFlow.value）
        val msgs = _uiState.value.messages.toMutableList()
        if (index < msgs.size) {
            msgs[index] = ChatUiMessage(content = content, isUser = false)
            _uiState.value = _uiState.value.copy(messages = msgs)
        }
    }

    fun newChat() {
        // 先停止任何正在运行的生成
        currentJob?.cancel()
        currentJob = null
        isLoopCancelled = false
        cancelledToolIndices.clear()
        apiHistory.clear()
        memorySystem.clearWorking()
        _uiState.value = _uiState.value.copy(
            messages = listOf(
                ChatUiMessage("你好！我是${_uiState.value.personality.name}${_uiState.value.personality.emoji}", isUser = false),
                ChatUiMessage(_uiState.value.personality.description, isUser = false)
            ),
            isProcessing = false,
            currentConversationId = null,
            currentEmotion = null
        )
    }

    fun loadConversation(id: String) {
        val conv = historyManager.load(id) ?: return
        val messages = conv.messages.map { msg ->
            ChatUiMessage(
                content = msg.content,
                isUser = msg.role == "user",
                emotion = msg.emotion,
                isCode = msg.isCode
            )
        }
        _uiState.value = _uiState.value.copy(
            messages = messages,
            currentConversationId = id
        )
        // 将全部历史消息加载到 API 上下文，确保 API 有完整的对话上下文
        apiHistory.clear()
        conv.messages.forEach { msg ->
            if (msg.role == "user" || msg.role == "assistant") {
                apiHistory.add(mapOf(
                    "role" to msg.role,
                    "content" to msg.content
                ))
            }
        }
    }

    fun deleteConversation(id: String) {
        historyManager.delete(id)
        _uiState.value = _uiState.value.copy(
            conversations = historyManager.loadAll()
        )
        if (_uiState.value.currentConversationId == id) newChat()
    }

    private fun saveCurrentConversation() {
        val messages = _uiState.value.messages.filter { !it.isStreaming && it.content.isNotBlank() }
        if (messages.size < 2) return

        val id = _uiState.value.currentConversationId ?: java.util.UUID.randomUUID().toString()
        val conv = Conversation(
            id = id,
            title = historyManager.generateTitle(messages.firstOrNull { it.isUser }?.content ?: "新对话"),
            personalityId = _uiState.value.personality.id
        )
        messages.forEach { msg ->
            conv.messages.add(ChatMessage(
                role = if (msg.isUser) "user" else "assistant",
                content = msg.content,
                emotion = msg.emotion,
                isCode = msg.content.contains("```")
            ))
        }
        historyManager.save(conv)
        _uiState.value = _uiState.value.copy(
            currentConversationId = id,
            conversations = historyManager.loadAll()
        )
    }

    fun updateConfig(config: ApiConfig) {
        ApiConfig.save(getApplication(), config)
        _uiState.value = _uiState.value.copy(
            config = config,
            showSettings = false,
            messages = _uiState.value.messages + ChatUiMessage(
                "✅ API配置已保存: ${config.provider}", isUser = false
            )
        )
    }

    fun setPersonality(personality: Personality) {
        prefs.personalityId = personality.id
        _uiState.value = _uiState.value.copy(personality = personality)
    }

    fun setTheme(mode: ThemeMode) {
        prefs.themeMode = mode
        _uiState.value = _uiState.value.copy(themeMode = mode)
    }

    fun setThemeVariant(variant: ThemeVariant) {
        prefs.themeVariant = variant
        _uiState.value = _uiState.value.copy(themeVariant = variant)
    }

    fun updateProfile(profile: UserProfile) {
        UserProfile.save(getApplication(), profile)
        _uiState.value = _uiState.value.copy(userProfile = profile)
    }

    fun setPage(page: String) {
        _uiState.value = _uiState.value.copy(currentPage = page)
    }

    fun toggleSettings() {
        _uiState.value = _uiState.value.copy(showSettings = !_uiState.value.showSettings)
    }

    fun stopGeneration() {
        // 先设置取消标记，防止后续 tool call 回调修改 UI 状态
        isLoopCancelled = true
        // 取消网络请求
        modelClientBridge.cancel()
        currentJob?.cancel()
        currentJob = null
        // 取消信号传播到子协程后，再清除取消索引
        cancelledToolIndices.clear()
        _uiState.value = _uiState.value.copy(isProcessing = false, currentEmotion = null)
        val msgs = _uiState.value.messages.toMutableList()
        if (msgs.isNotEmpty() && !msgs.last().isUser && msgs.last().isStreaming) {
            msgs.removeAt(msgs.lastIndex)
        }
        _uiState.value = _uiState.value.copy(messages = msgs)
        // 显示取消状态提示
        _toolExecutionFlow.tryEmit(ToolExecutionEvent(-1, "系统", "⏹️ 任务已取消", "error"))
    }

    fun cancelToolCall(index: Int) {
        cancelledToolIndices.add(index)
        // 立即更新UI反映取消状态
        val current = _uiState.value.messages.toMutableList()
        val last = current.lastOrNull()?.copy(
            toolCalls = current.lastOrNull()?.toolCalls?.toMutableList().also { list ->
                if (list != null && index < list.size) {
                    list[index] = list[index].copy(status = "error", result = "已取消")
                }
            } ?: return,
            // 如果所有工具都已取消，清除 runningTool
            runningTool = if (current.last().toolCalls.all { it.status != "running" }) null
                         else current.last().runningTool
        )
        if (last != null) current[current.lastIndex] = last
        _uiState.value = _uiState.value.copy(messages = current)
        // 显示取消状态提示
        val toolName = current.lastOrNull()?.toolCalls?.getOrNull(index)?.name ?: "工具"
        _toolExecutionFlow.tryEmit(ToolExecutionEvent(index, toolName, "⏹️ 已取消", "error"))
    }

    fun startDream() {
        val config = _uiState.value.config ?: return
        val personality = _uiState.value.personality

        val dreamPrompt = when (personality.id) {
            "coder" -> "请帮我回顾最近的代码项目，总结学到的编程技巧和需要注意的问题。"
            "philosopher" -> "请进行一次哲学冥想，思考最近的人生体验和感悟。"
            "creative" -> "请发挥创意，想象一个有趣的场景或故事灵感。"
            "tutor" -> "请总结最近学到的知识，指出还需要加强的地方。"
            "analyst" -> "请分析最近的数据趋势，给出洞察和建议。"
            else -> "请安静地回顾今天的对话，总结重要的记忆和感悟。"
        }

        _uiState.value = _uiState.value.copy(
            messages = _uiState.value.messages + ChatUiMessage(dreamPrompt, isUser = true),
            isProcessing = true
        )

        viewModelScope.launch {
            val heartResult = HeartEngine.analyze(dreamPrompt)
            val memoryContext = memorySystem.getContextString(tags = emptyList())
            runAgentLoop(dreamPrompt, heartResult, personality, config, memoryContext)
        }
    }

    fun evolve() {
        val config = _uiState.value.config ?: return
        val personality = _uiState.value.personality

        val evolvePrompt = """请分析你自己的表现，提出改进建议：
1. 回答质量如何？有什么可以改进的？
2. 工具使用是否合理？
3. 记忆系统是否有效？
4. 给出3条具体的自我优化建议。"""

        _uiState.value = _uiState.value.copy(
            messages = _uiState.value.messages + ChatUiMessage(evolvePrompt, isUser = true),
            isProcessing = true
        )

        viewModelScope.launch {
            val heartResult = HeartEngine.analyze(evolvePrompt)
            val memoryContext = memorySystem.getContextString(tags = emptyList())
            runAgentLoop(evolvePrompt, heartResult, personality, config, memoryContext)
        }
    }

    fun setFontSize(size: Float) {
        prefs.fontSize = size
        _uiState.value = _uiState.value.copy(fontSize = size)
    }

    fun saveCustomPrompt(prompt: String) {
        prefs.systemPrompt = prompt
    }

    fun clearMemory() {
        memorySystem.clear()
    }

    fun clearWorkingMemory() {
        memorySystem.clearWorking()
    }

    fun installSkill(url: String): String {
        return ToolRegistry.installSkill(url)
    }

    fun getInstalledSkillNames(): List<String> {
        return ToolRegistry.getInstalledSkillNames()
    }

    fun exportConversations(): String {
        val conversations = historyManager.loadAll()
        val json = org.json.JSONArray(conversations.map { it.toJson() })
        return json.toString(2)
    }

    fun updateVoiceState(updater: (VoiceUiState) -> VoiceUiState) {
        _uiState.value = _uiState.value.copy(
            voiceState = updater(_uiState.value.voiceState)
        )
    }

    fun sendVoiceMessage(text: String) {
        if (text.isBlank()) return
        sendMessage(text)
    }

    // ── 语音输入（AudioInputManager） ──────────────────────────

    /**
     * 开始语音识别 — 由 ChatInput 在获取录音权限后调用
     */
    fun startListening() {
        // 如果已经在录音，不重复启动
        if (audioInputManager != null) return

        // 录音计时器（每秒更新一次录音时长）
        startTimeMs = System.currentTimeMillis()
        durationJob = viewModelScope.launch {
            while (isActive) {
                _uiState.value = _uiState.value.copy(
                    voiceState = _uiState.value.voiceState.copy(
                        recordingDuration = ((System.currentTimeMillis() - startTimeMs) / 1000).toInt()
                    )
                )
                delay(1000)
            }
        }

        // 初始化语音输入管理器，与应用 Application 生命周期绑定
        audioInputManager = AudioInputManager(
            context = getApplication(),
            scope = viewModelScope,
            onResult = { text ->
                // 识别完成：清理状态并发送消息
                durationJob?.cancel()
                durationJob = null
                val resultText = text.trim()
                _uiState.value = _uiState.value.copy(
                    voiceState = VoiceUiState() // 重置为初始状态
                )
                if (resultText.isNotBlank()) {
                    sendVoiceMessage(resultText)
                }
            },
            onPartialResult = { text ->
                // 部分识别结果：实时显示在录音栏
                _uiState.value = _uiState.value.copy(
                    voiceState = _uiState.value.voiceState.copy(
                        partialResult = text
                    )
                )
            },
            onError = { error ->
                // 识别出错：停止录音并显示错误提示
                durationJob?.cancel()
                durationJob = null
                _uiState.value = _uiState.value.copy(
                    voiceState = _uiState.value.voiceState.copy(
                        isRecording = false,
                        partialResult = null,
                        voiceError = error
                    )
                )
            }
        )
        audioInputManager?.start()
    }

    /**
     * 停止语音识别 — 由 ChatInput 用户点击停止按钮时调用
     */
    fun stopListening() {
        durationJob?.cancel()
        durationJob = null
        audioInputManager?.stop()
        audioInputManager = null
        _uiState.value = _uiState.value.copy(
            voiceState = _uiState.value.voiceState.copy(
                isRecording = false,
                partialResult = null
            )
        )
    }

    /** 获取录音时长（秒），用于 UI 显示 */
    fun getRecordingDuration(): Int = _uiState.value.voiceState.recordingDuration

    // ── 语音朗读测试 ──────────────────────────────────────────

    /** 测试 TTS 语音朗读 */
    fun testTts(text: String = "你好，我是心虫。这是我的语音朗读测试。") {
        if (!ttsManager.isInitialized) {
            // TTS 引擎尚未就绪，尝试初始化后朗读（由 TextToSpeechManager 自动积压）
            ttsManager.speak(text)
        } else {
            ttsManager.speak(text)
        }
    }

    fun speakMessage(index: Int) {
        val messages = _uiState.value.messages
        if (index < 0 || index >= messages.size) return
        val msg = messages[index]
        if (msg.isUser || msg.isStreaming || msg.content.isBlank()) return
        _uiState.value = _uiState.value.copy(speakingMessageIndex = index)
        ttsManager.speak(msg.content) {
            _uiState.value = _uiState.value.copy(speakingMessageIndex = null)
        }
    }

    fun stopSpeaking() {
        ttsManager.stop()
        _uiState.value = _uiState.value.copy(speakingMessageIndex = null)
    }

    fun updateTtsSettings(speed: Float, pitch: Float) {
        prefs.ttsSpeed = speed
        prefs.ttsPitch = pitch
        ttsManager.speed = speed
        ttsManager.pitch = pitch
    }

    /** 获取 TTS 语速（从持久化配置） */
    fun getTtsSpeed(): Float = prefs.ttsSpeed

    /** 获取 TTS 音调（从持久化配置） */
    fun getTtsPitch(): Float = prefs.ttsPitch

    /** 获取浏览器首页 URL（从持久化配置） */
    fun getBrowserHomeUrl(): String = prefs.browserHomeUrl

    /** 设置浏览器首页 URL（持久化保存） */
    fun setBrowserHomeUrl(url: String) {
        prefs.browserHomeUrl = url
    }

    fun sendAttachment(text: String, attachment: MediaAttachment, base64Data: String) {
        val config = _uiState.value.config ?: return
        val heartResult = HeartEngine.analyze(text.ifBlank { "请描述这张图片/文件" })

        val attWithData = attachment.copy(base64Data = base64Data)

        _uiState.value = _uiState.value.copy(
            messages = _uiState.value.messages +
                ChatUiMessage(
                    content = text.ifBlank { "📎 发送了一个附件" },
                    isUser = true,
                    emotion = heartResult.emotion,
                    attachment = attWithData
                ) +
                ChatUiMessage("", isUser = false, isStreaming = true, isLoading = true),
            isProcessing = true,
            currentEmotion = heartResult.emotion
        )

        memorySystem.store(text, MemoryLayer.WORKING, tags = heartResult.tags,
            importance = 1, emotion = heartResult.emotion, source = "user")

        viewModelScope.launch {
            currentJob = coroutineContext[Job]
            val parentJob = coroutineContext[Job]
            try {
                // 初始化 ModelClientBridge（如配置变更新建客户端）
                if (currentApiConfig != config) {
                    modelClientBridge.initialize(config)
                    currentApiConfig = config
                }
                withTimeout(60000L) {
                    runAgentLoop(
                        text.ifBlank { "请分析这张图片/文件" },
                        heartResult, _uiState.value.personality, config, "",
                        extraMessages = attWithData.let {
                            if (it.type == "image") buildImageMessage(it) else buildFileMessage(it)
                        },
                        agentJob = parentJob
                    )
                }
            } catch (e: TimeoutCancellationException) {
                Log.w(TAG, "发送附件超时")
            } finally {
                currentJob = null
                if (_uiState.value.isProcessing) {
                    _uiState.value = _uiState.value.copy(isProcessing = false)
                }
            }
        }
    }

    private fun buildImageMessage(attachment: MediaAttachment): List<Map<String, Any>> {
        val content = mutableListOf<Map<String, Any>>()
        content.add(mapOf("type" to "text", "text" to "请分析这张图片/文件"))
        content.add(mapOf(
            "type" to "image_url",
            "image_url" to mapOf("url" to "data:${attachment.mimeType};base64,${attachment.base64Data}")
        ))
        return listOf(mapOf("role" to "user", "content" to content))
    }

    private fun buildFileMessage(attachment: MediaAttachment): List<Map<String, Any>> {
        return listOf(mapOf(
            "role" to "user",
            "content" to "用户发送了文件: ${attachment.fileName} (${attachment.type}/${attachment.mimeType})"
        ))
    }

    fun resendMessage(messageIndex: Int) {
        val msgs = _uiState.value.messages
        if (messageIndex < 0 || messageIndex >= msgs.size || !msgs[messageIndex].isUser) return

        val text = msgs[messageIndex].content
        val keepMessages = msgs.take(messageIndex + 1)
        _uiState.value = _uiState.value.copy(messages = keepMessages)
        // 将保留的消息重新加载到 API 上下文
        reloadApiHistory(keepMessages)
        sendMessage(text)
    }

    fun editMessage(index: Int, newText: String) {
        val msgs = _uiState.value.messages.toMutableList()
        if (index < 0 || index >= msgs.size) return
        msgs[index] = msgs[index].copy(content = newText)
        val keepMessages = msgs.take(index + 1)
        _uiState.value = _uiState.value.copy(messages = keepMessages)
        // 将保留的消息重新加载到 API 上下文
        reloadApiHistory(keepMessages)
        sendMessage(newText)
    }

    /** 从 UI 消息列表重新加载 API 上下文历史 */
    private fun reloadApiHistory(keepMessages: List<ChatUiMessage>) {
        apiHistory.clear()
        keepMessages.forEach { msg ->
            if (msg.content.isNotBlank() && !msg.isLoading && !msg.isStreaming) {
                // 跳过欢迎/系统消息（来自 ViewModel 初始化）
                val role = if (msg.isUser) "user" else "assistant"
                apiHistory.add(mapOf(
                    "role" to role,
                    "content" to msg.content
                ))
            }
        }
    }

    fun getToolTimeoutMs(): Long = prefs.toolTimeoutMs
    fun getMaxToolLoops(): Int = prefs.maxToolLoops
    fun getEnableTools(): Boolean = prefs.enableTools
    fun setMaxToolLoops(value: Int) { prefs.maxToolLoops = value }
    fun setToolTimeoutMs(value: Long) { prefs.toolTimeoutMs = value }
    fun setEnableTools(value: Boolean) { prefs.enableTools = value }

    /** 获取已禁用的工具名集合 */
    fun getDisabledTools(): Set<String> = prefs.disabledTools

    /** 切换某个工具启用/禁用状态 */
    fun setToolEnabled(name: String, enabled: Boolean) {
        val current = prefs.disabledTools.toMutableSet()
        if (enabled) current.remove(name) else current.add(name)
        prefs.disabledTools = current
    }

    /** 判断某个工具是否启用（默认启用） */
    fun isToolEnabled(name: String): Boolean = name !in prefs.disabledTools

    /** 获取启用的工具列表，供渲染 UI 使用 */
    fun getEnabledToolNames(): List<String> {
        if (!prefs.enableTools) return emptyList()
        return ToolRegistry.getAll().map { it.name }.filter { it !in prefs.disabledTools }
    }

    // ── 上下文选择器 ── (已移除，改用 ApiService 自动上下文窗口管理)

    override fun onCleared() {
        super.onCleared()
        audioInputManager?.destroy()
        audioInputManager = null
        durationJob?.cancel()
        durationJob = null
        ttsManager.shutdown()
    }
}
