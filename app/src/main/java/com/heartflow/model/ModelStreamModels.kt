package com.heartflow.model

import com.heartflow.tool.ToolCall

/**
 * 模型完成响应
 * 包含文本内容、推理内容和工具调用
 */
data class ModelCompletionResponse(
    val text: String,
    val reasoningContent: String = "",
    val toolCalls: List<ToolCall> = emptyList()
) {
    /**
     * 是否包含工具调用
     */
    fun hasToolCalls(): Boolean = toolCalls.isNotEmpty()

    companion object {
        /**
         * 创建空响应
         */
        fun empty(): ModelCompletionResponse = ModelCompletionResponse("")
    }
}

/**
 * 流式回调接口
 * 用于处理流式响应数据
 */
interface ModelStreamCallback {
    /**
     * 接收到文本增量
     */
    fun onTextDelta(delta: String)

    /**
     * 接收到推理内容增量
     */
    fun onReasoningDelta(delta: String)

    /**
     * 工具调用开始（可选实现）
     */
    fun onToolCallStart(index: Int, id: String, name: String) {}

    /**
     * 工具调用参数增量（可选实现）
     */
    fun onToolCallArgument(delta: String, index: Int) {}

    /**
     * 流式结束回调（可选实现）
     */
    var onComplete: ((String) -> Unit)?

    /**
     * 流式错误回调（可选实现）
     */
    var onError: ((String) -> Unit)?
}

/**
 * 取消令牌
 * 用于取消正在进行的请求
 */
class ModelCancellationToken {
    private val listeners = mutableListOf<Runnable>()
    @Volatile private var cancelled = false

    /**
     * 是否已取消
     */
    fun isCancelled(): Boolean = cancelled

    /**
     * 重置取消状态
     */
    fun reset() {
        cancelled = false
    }

    /**
     * 取消请求
     */
    fun cancel() {
        val callbacks: List<Runnable>
        synchronized(listeners) {
            if (cancelled) return
            cancelled = true
            callbacks = listeners.toList()
            listeners.clear()
        }
        callbacks.forEach { it.run() }
    }

    /**
     * 注册取消回调
     */
    fun onCancel(callback: Runnable) {
        val runNow: Boolean
        synchronized(listeners) {
            runNow = cancelled
            if (!runNow) {
                listeners.add(callback)
            }
        }
        if (runNow) {
            callback.run()
        }
    }
}

/**
 * 模型完成异常
 */
class ModelCompletionException(
    message: String,
    cause: Throwable? = null
) : Exception(message, cause)

/**
 * 基础抽象流式回调实现
 */
abstract class BaseStreamCallback : ModelStreamCallback {
    private var _onComplete: ((String) -> Unit)? = null
    private var _onError: ((String) -> Unit)? = null

    override var onComplete: ((String) -> Unit)?
        get() = _onComplete
        set(value) { _onComplete = value }

    override var onError: ((String) -> Unit)?
        get() = _onError
        set(value) { _onError = value }

    /**
     * 处理完成事件
     */
    protected fun handleComplete(text: String) {
        _onComplete?.invoke(text)
        onFinalResponse(text)
    }

    /**
     * 处理错误事件
     */
    protected fun handleError(error: String) {
        _onError?.invoke(error)
        onErrorOccurred(error)
    }

    /**
     * 子类可重写：最终响应
     */
    protected open fun onFinalResponse(text: String) {}

    /**
     * 子类可重写：错误发生
     */
    protected open fun onErrorOccurred(error: String) {}

    /**
     * 子类可重写：文本增量
     */
    override open fun onTextDelta(delta: String) {}

    /**
     * 子类可重写：推理增量
     */
    override open fun onReasoningDelta(delta: String) {}
}
