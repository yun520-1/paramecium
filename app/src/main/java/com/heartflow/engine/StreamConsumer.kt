package com.heartflow.engine

import kotlinx.coroutines.*
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.channels.Channel.Factory.RENDEZVOUS
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * 流式消费者 - 接收SSE增量token，通过Channel线程安全桥接给UI层
 *
 * 职责：
 * - 从OkHttp IO线程接收onDelta回调
 * - 通过Channel安全桥接到协程作用域
 * - 支持SegmentBreak事件（段落分割点）
 * - 提供finish/error信号
 */
class StreamConsumer(
    private val capacity: Int = 64
) {
    /** 流式事件通道，UI层通过receiveChannel消费 */
    private val eventChannel = Channel<StreamEvent>(capacity)

    /** 暴露给UI层的只读通道 */
    val receiveChannel = eventChannel

    /** 当前会话的累计token数 */
    private var tokenCount = 0

    /** 当前段落的token数（遇到SegmentBreak时重置） */
    private var segmentTokenCount = 0

    /**
     * 增量token回调，从OkHttp IO线程调用
     * 内部通过Channel发送到协程作用域
     */
    fun onDelta(delta: String) {
        if (delta.isEmpty()) return
        tokenCount++
        segmentTokenCount++
        eventChannel.trySend(StreamEvent.Delta(delta))
    }

    /**
     * 流结束信号
     */
    fun onFinish(fullText: String) {
        eventChannel.trySend(StreamEvent.Finish(fullText, tokenCount))
    }

    /**
     * 段落分割点（模型输出中的自然分段，如连续换行）
     */
    fun onSegmentBreak() {
        eventChannel.trySend(StreamEvent.SegmentBreak(segmentTokenCount))
        segmentTokenCount = 0
    }

    /**
     * 错误信号
     */
    fun onError(error: String) {
        eventChannel.trySend(StreamEvent.Error(error))
        eventChannel.close()
    }

    /**
     * 关闭通道
     */
    fun close() {
        eventChannel.close()
    }

    /** 累计token数 */
    fun getTokenCount(): Int = tokenCount

    /** 重置计数器（新会话时调用） */
    fun reset() {
        tokenCount = 0
        segmentTokenCount = 0
    }
}

/**
 * 流式事件类型
 */
sealed class StreamEvent {
    /** 增量token */
    data class Delta(val text: String) : StreamEvent()

    /** 流结束，携带完整文本和总token数 */
    data class Finish(val fullText: String, val totalTokens: Int) : StreamEvent()

    /** 段落分割点，携带当前段落的token数 */
    data class SegmentBreak(val segmentTokens: Int) : StreamEvent()

    /** 错误事件 */
    data class Error(val message: String) : StreamEvent()
}

/**
 * 渐进式编辑模式 - 控制消息在UI中的更新方式
 *
 * 策略：
 * - 首次发送：创建新消息（isStreaming=true）
 * - 后续更新：原地替换内容，不追加新消息
 * - 完成时：关闭streaming状态
 */
class ProgressiveEditMode(
    private val scope: CoroutineScope
) {
    /** 当前流式消息的内容 */
    private val _currentContent = MutableStateFlow("")
    val currentContent: StateFlow<String> = _currentContent.asStateFlow()

    /** 是否正在流式接收中 */
    private val _isActive = MutableStateFlow(false)
    val isActive: StateFlow<Boolean> = _isActive.asStateFlow()

    /** 流式内容变更事件，UI层收集此flow更新气泡 */
    private val _contentUpdates = MutableSharedFlow<ContentUpdate>(extraBufferCapacity = 64)
    val contentUpdates: SharedFlow<ContentUpdate> = _contentUpdates.asSharedFlow()

    private val mutex = Mutex()
    private var isStarted = false

    /**
     * 开始新的流式消息（首次调用）
     */
    fun startNewMessage() {
        scope.launch {
            mutex.withLock {
                _currentContent.value = ""
                _isActive.value = true
                isStarted = false
            }
        }
    }

    /**
     * 更新当前流式消息内容（原地更新，不追加）
     */
    fun appendDelta(delta: String) {
        scope.launch {
            mutex.withLock {
                _currentContent.value += delta
                if (!isStarted) {
                    isStarted = true
                    _contentUpdates.emit(ContentUpdate.Created(_currentContent.value))
                } else {
                    _contentUpdates.emit(ContentUpdate.Updated(_currentContent.value))
                }
            }
        }
    }

    /**
     * 完成当前流式消息
     */
    fun finish(finalContent: String) {
        scope.launch {
            mutex.withLock {
                _currentContent.value = finalContent
                _isActive.value = false
                _contentUpdates.emit(ContentUpdate.Finished(finalContent))
            }
        }
    }

    /**
     * 中断当前流式消息
     */
    fun abort(reason: String) {
        scope.launch {
            mutex.withLock {
                _isActive.value = false
                _contentUpdates.emit(ContentUpdate.Aborted(_currentContent.value, reason))
            }
        }
    }
}

/**
 * 内容更新事件
 */
sealed class ContentUpdate {
    /** 首次创建消息 */
    data class Created(val content: String) : ContentUpdate()

    /** 原地更新内容 */
    data class Updated(val content: String) : ContentUpdate()

    /** 流式完成 */
    data class Finished(val finalContent: String) : ContentUpdate()

    /** 流式中断 */
    data class Aborted(val partialContent: String, val reason: String) : ContentUpdate()
}

/**
 * 洪泛控制 - 防止连续失败导致的资源浪费
 *
 * 策略：
 * - 连续失败计数，达到阈值时自动fallback
 * - 使用指数退避的冷却时间
 * - 成功时重置计数
 */
class FloodControl(
    private val maxConsecutiveFailures: Int = 3,
    private val cooldownMs: Long = 5000L
) {
    /** 连续失败次数 */
    private var consecutiveFailures = 0

    /** 上次失败时间 */
    private var lastFailureTime = 0L

    /** 是否处于冷却中 */
    private val _isCoolingDown = MutableStateFlow(false)
    val isCoolingDown: StateFlow<Boolean> = _isCoolingDown.asStateFlow()

    /** 当前状态 */
    private val _state = MutableStateFlow(FloodState.NORMAL)
    val state: StateFlow<FloodState> = _state.asStateFlow()

    /**
     * 记录成功，重置计数器
     */
    fun recordSuccess() {
        consecutiveFailures = 0
        lastFailureTime = 0L
        _state.value = FloodState.NORMAL
    }

    /**
     * 记录失败，检查是否需要fallback
     *
     * @return true表示应该fallback到其他provider
     */
    fun recordFailure(): Boolean {
        consecutiveFailures++
        lastFailureTime = System.currentTimeMillis()

        return when {
            consecutiveFailures >= maxConsecutiveFailures -> {
                _state.value = FloodState.FALLBACK
                true
            }
            consecutiveFailures > 1 -> {
                _state.value = FloodState.BACKOFF
                false
            }
            else -> {
                _state.value = FloodState.RETRY
                false
            }
        }
    }

    /**
     * 检查是否可以重试（冷却时间已过）
     */
    fun canRetry(): Boolean {
        if (consecutiveFailures < maxConsecutiveFailures) return true
        if (lastFailureTime == 0L) return true
        return System.currentTimeMillis() - lastFailureTime >= cooldownMs
    }

    /**
     * 重置所有状态
     */
    fun reset() {
        consecutiveFailures = 0
        lastFailureTime = 0L
        _isCoolingDown.value = false
        _state.value = FloodState.NORMAL
    }

    /** 获取当前连续失败次数 */
    fun getConsecutiveFailures(): Int = consecutiveFailures
}

/**
 * 洪泛控制状态
 */
enum class FloodState {
    /** 正常状态 */
    NORMAL,

    /** 单次失败，可重试 */
    RETRY,

    /** 多次失败，指数退避 */
    BACKOFF,

    /** 超过阈值，需要fallback */
    FALLBACK
}

/**
 * Think块过滤器 - 状态机过滤模型输出中的<think>...</think>内容
 *
 * 状态机：
 * - OUTSIDE: 在<think>外部，正常输出
 * - INSIDE_TAG: 遇到<，正在匹配<think>
 * - INSIDE_THINK: 在<think>块内部，过滤内容
 * - CLOSING_TAG: 遇到<，正在匹配</think>
 *
 * 用途：DeepSeek等模型会在<think>标签中输出推理过程，
 * 这些内容不应展示给用户
 */
class ThinkBlockFilter {
    /** 当前状态 */
    private var state = ThinkState.OUTSIDE

    /** 缓冲区，用于匹配部分标签 */
    private val tagBuffer = StringBuilder()

    /** 累计的输出文本 */
    private val outputBuffer = StringBuilder()

    /** 累计的思考文本（调试用） */
    private val thinkBuffer = StringBuilder()

    /**
     * 处理增量token，返回过滤后的输出
     *
     * @param delta 原始增量token
     * @return 过滤后的增量（可能为空字符串）
     */
    fun processDelta(delta: String): String {
        val result = StringBuilder()

        for (char in delta) {
            when (state) {
                ThinkState.OUTSIDE -> {
                    if (char == '<') {
                        tagBuffer.clear()
                        tagBuffer.append(char)
                        state = ThinkState.INSIDE_TAG
                    } else {
                        outputBuffer.append(char)
                        result.append(char)
                    }
                }

                ThinkState.INSIDE_TAG -> {
                    tagBuffer.append(char)
                    val tag = tagBuffer.toString()

                    when {
                        tag == "<think>" -> {
                            tagBuffer.clear()
                            state = ThinkState.INSIDE_THINK
                        }
                        tag.startsWith("<think") && !tag.startsWith("<thinking") -> {
                            // 继续匹配，可能后面还有字符
                        }
                        tag.length > 10 || (tag.contains('>') && tag != "<think>") -> {
                            // 匹配失败，输出缓冲内容
                            outputBuffer.append(tagBuffer)
                            result.append(tagBuffer)
                            tagBuffer.clear()
                            state = ThinkState.OUTSIDE
                        }
                    }
                }

                ThinkState.INSIDE_THINK -> {
                    if (char == '<') {
                        tagBuffer.clear()
                        tagBuffer.append(char)
                        state = ThinkState.CLOSING_TAG
                    } else {
                        thinkBuffer.append(char)
                    }
                }

                ThinkState.CLOSING_TAG -> {
                    tagBuffer.append(char)
                    val tag = tagBuffer.toString()

                    when {
                        tag == "</think>" -> {
                            tagBuffer.clear()
                            state = ThinkState.OUTSIDE
                        }
                        tag.startsWith("</think") && !tag.startsWith("</thinking") -> {
                            // 继续匹配
                        }
                        tag.length > 10 || (tag.contains('>') && tag != "</think>") -> {
                            // 匹配失败，输出缓冲内容
                            thinkBuffer.append(tagBuffer)
                            tagBuffer.clear()
                            state = ThinkState.INSIDE_THINK
                        }
                    }
                }
            }
        }

        return result.toString()
    }

    /**
     * 获取过滤掉的思考内容
     */
    fun getThinkContent(): String = thinkBuffer.toString()

    /**
     * 获取过滤后的完整输出
     */
    fun getOutputContent(): String = outputBuffer.toString()

    /**
     * 获取当前状态
     */
    fun getState(): ThinkState = state

    /**
     * 重置状态机
     */
    fun reset() {
        state = ThinkState.OUTSIDE
        tagBuffer.clear()
        outputBuffer.clear()
        thinkBuffer.clear()
    }
}

/**
 * Think过滤状态
 */
enum class ThinkState {
    /** 在<think>外部，正常输出 */
    OUTSIDE,

    /** 遇到<，正在匹配<think>标签 */
    INSIDE_TAG,

    /** 在<think>块内部，过滤内容 */
    INSIDE_THINK,

    /** 遇到</，正在匹配</think>标签 */
    CLOSING_TAG
}

/**
 * 消息溢出分割器 - 当输出超过token上限时自动分割
 *
 * 策略：
 * - 按token数估算，超过maxTokens时分割
 * - 优先在段落边界分割
 * - 次选在句号/换行符分割
 * - 避免在代码块内部分割
 * - 每个chunk添加续接标记
 */
class MessageOverflowSplitter(
    private val maxTokens: Int = 4096,
    private val tokenEstimator: (String) -> Int = ::defaultEstimator
) {
    /** 分割后的chunk列表 */
    private val chunks = mutableListOf<String>()

    /** 当前累积的token数 */
    private var currentTokenCount = 0

    /** 当前chunk的内容 */
    private val currentChunk = StringBuilder()

    /**
     * 添加文本，自动分割
     *
     * @param text 输入文本
     * @return 分割后的chunk列表（空列表表示还没达到分割点）
     */
    fun addText(text: String): List<String> {
        currentChunk.append(text)
        currentTokenCount += tokenEstimator(text)

        if (currentTokenCount > maxTokens) {
            val splitChunks = splitOverflow(currentChunk.toString())
            chunks.addAll(splitChunks.dropLast(1))
            currentChunk.clear()
            currentChunk.append(splitChunks.last())
            currentTokenCount = tokenEstimator(currentChunk.toString())
            return splitChunks.dropLast(1)
        }

        return emptyList()
    }

    /**
     * 完成输入，返回最终的chunk列表
     */
    fun finish(): List<String> {
        val result = mutableListOf<String>()
        result.addAll(chunks)
        if (currentChunk.isNotEmpty()) {
            result.add(currentChunk.toString())
        }
        reset()
        return result
    }

    /**
     * 强制分割当前累积的内容
     */
    fun forceFlush(): List<String> {
        if (currentChunk.isEmpty()) return emptyList()
        val text = currentChunk.toString()
        currentChunk.clear()
        currentTokenCount = 0
        return splitOverflow(text)
    }

    /**
     * 重置状态
     */
    fun reset() {
        chunks.clear()
        currentChunk.clear()
        currentTokenCount = 0
    }

    /**
     * 分割溢出文本
     */
    private fun splitOverflow(text: String): List<String> {
        val result = mutableListOf<String>()
        var remaining = text

        while (remaining.isNotEmpty()) {
            if (tokenEstimator(remaining) <= maxTokens) {
                result.add(remaining)
                break
            }

            val splitPoint = findSplitPoint(remaining, maxTokens)
            result.add(remaining.substring(0, splitPoint))
            remaining = remaining.substring(splitPoint).trimStart()
        }

        return result
    }

    /**
     * 查找最佳分割点
     */
    private fun findSplitPoint(text: String, maxTok: Int): Int {
        var charCount = 0
        var lastGoodSplit = 0
        var inCodeBlock = false
        var inThinkBlock = false

        for ((index, char) in text.withIndex()) {
            if (char == '\n') {
                // 检查是否在代码块中
                val precedingText = text.substring(maxOf(0, index - 3), index + 1)
                if (precedingText.contains("```")) {
                    inCodeBlock = !inCodeBlock
                }

                // 检查是否在think块中
                if (text.substring(maxOf(0, index - 7), index + 1).contains("<think>")) {
                    inThinkBlock = true
                }
                if (text.substring(maxOf(0, index - 8), index + 1).contains("</think>")) {
                    inThinkBlock = false
                }

                // 不在代码块或think块中时，记录为好的分割点
                if (!inCodeBlock && !inThinkBlock) {
                    lastGoodSplit = index + 1
                }
            }

            charCount++
            if (charCount >= maxTok * 4 && lastGoodSplit > 0) {
                return lastGoodSplit
            }
        }

        // 没有找到好的分割点，按字符数硬分割
        return minOf(text.length, maxTok * 4)
    }
}

/**
 * 默认token估算器
 */
private fun defaultEstimator(text: String): Int {
    if (text.isBlank()) return 0
    var chinese = 0
    var english = 0
    for (c in text) {
        if (c.code in 0x4e00..0x9fff || c.code in 0x3000..0x303f) chinese++
        else english++
    }
    return maxOf(1, chinese * 2 / 3 + english / 4)
}

/**
 * 流式消息构建器 - 组合各组件，提供完整的流式处理管线
 *
 * 管线：
 * StreamConsumer → ThinkBlockFilter → ProgressiveEditMode
 *                ↘ FloodControl
 *                ↘ MessageOverflowSplitter
 */
class StreamMessageBuilder(
    private val maxTokens: Int = 4096,
    private val scope: CoroutineScope
) {
    private val consumer = StreamConsumer()
    private val filter = ThinkBlockFilter()
    private val editMode = ProgressiveEditMode(scope)
    private val overflowSplitter = MessageOverflowSplitter(maxTokens)
    private val floodControl = FloodControl()

    private var fullText = StringBuilder()

    /** 暴露子组件 */
    val streamConsumer: StreamConsumer get() = consumer
    val thinkFilter: ThinkBlockFilter get() = filter
    val progressiveEdit: ProgressiveEditMode get() = editMode
    val overflow: MessageOverflowSplitter get() = overflowSplitter
    val flood: FloodControl get() = floodControl

    /** 完整输出内容 */
    fun getFullText(): String = fullText.toString()

    /**
     * 处理一个增量token，经过过滤后更新UI
     */
    fun processDelta(delta: String) {
        val filtered = filter.processDelta(delta)
        if (filtered.isNotEmpty()) {
            fullText.append(filtered)
            editMode.appendDelta(filtered)
            overflowSplitter.addText(filtered)
        }
        consumer.onDelta(delta)
    }

    /**
     * 流完成
     */
    fun finish() {
        editMode.finish(fullText.toString())
        consumer.onFinish(fullText.toString())
    }

    /**
     * 重置所有状态（新会话时调用）
     */
    fun reset() {
        consumer.reset()
        filter.reset()
        overflowSplitter.reset()
        floodControl.reset()
        fullText.clear()
    }
}

/**
 * 流式处理回调接口
 */
interface StreamCallback {
    /** 增量token（已过滤） */
    fun onDelta(delta: String)

    /** 流完成 */
    fun onComplete(fullText: String)

    /** 段落分割 */
    fun onSegmentBreak(segmentTokens: Int)

    /** 错误 */
    fun onError(error: String)

    /** 洪泛状态变化 */
    fun onFloodStateChange(state: FloodState, consecutiveFailures: Int)
}

/**
 * 流式管线 - 将所有组件串联为统一管线
 *
 * 用法：
 * ```kotlin
 * val pipeline = StreamPipeline(maxTokens = 4096, scope = viewModelScope)
 *
 * // 收集UI更新
 * viewModelScope.launch {
 *     pipeline.contentUpdates.collect { update ->
 *         when (update) {
 *             is ContentUpdate.Created -> addNewMessage(update.content)
 *             is ContentUpdate.Updated -> updateLastMessage(update.content)
 *             is ContentUpdate.Finished -> finalizeMessage(update.finalContent)
 *             is ContentUpdate.Aborted -> showPartial(update.partialContent)
 *         }
 *     }
 * }
 *
 * // 在API调用中使用
 * val consumer = pipeline.consumer
 * consumer.onDelta(token)
 * consumer.onFinish(fullText)
 * ```
 */
class StreamPipeline(
    maxTokens: Int = 4096,
    private val scope: CoroutineScope
) {
    private val builder = StreamMessageBuilder(maxTokens, scope)

    /** UI层收集此flow更新消息气泡 */
    val contentUpdates: SharedFlow<ContentUpdate> get() = builder.progressiveEdit.contentUpdates

    /** 当前流式内容 */
    val currentContent: StateFlow<String> get() = builder.progressiveEdit.currentContent

    /** 是否正在流式接收 */
    val isStreaming: StateFlow<Boolean> get() = builder.progressiveEdit.isActive

    /** 洪泛状态 */
    val floodState: StateFlow<FloodState> get() = builder.flood.state

    /** StreamConsumer实例，供OkHttp回调使用 */
    val consumer: StreamConsumer get() = builder.streamConsumer

    /** Think过滤器 */
    val thinkFilter: ThinkBlockFilter get() = builder.thinkFilter

    /**
     * 开始新的流式消息
     */
    fun startNewMessage() {
        builder.reset()
        builder.progressiveEdit.startNewMessage()
    }

    /**
     * 处理增量token
     */
    fun processDelta(delta: String) {
        builder.processDelta(delta)
    }

    /**
     * 流完成
     */
    fun finish() {
        builder.finish()
    }

    /**
     * 记录API调用成功
     */
    fun recordSuccess() {
        builder.flood.recordSuccess()
    }

    /**
     * 记录API调用失败
     *
     * @return true表示应该fallback到其他provider
     */
    fun recordFailure(): Boolean {
        return builder.flood.recordFailure()
    }

    /**
     * 获取过滤掉的思考内容（调试用）
     */
    fun getThinkContent(): String = builder.thinkFilter.getThinkContent()

    /**
     * 获取分割后的chunks（如果发生了溢出分割）
     */
    fun getChunks(): List<String> = builder.overflow.finish()
}
