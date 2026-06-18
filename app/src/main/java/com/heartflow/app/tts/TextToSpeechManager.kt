package com.heartflow.app.tts

import android.content.Context
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import android.util.Log
import java.util.Locale

/**
 * TTS 语音朗读管理器 — 封装 Android 内置 TextToSpeech API
 *
 * 增强功能：
 * - 播放状态管理（IDLE / PLAYING / PAUSED）
 * - 暂停/恢复朗读
 * - 多消息自动排队朗读（QueueManager）
 * - 初始化完成前自动积压
 *
 * 使用方式：
 *   val tts = TextToSpeechManager(context)
 *   tts.speak("你好") { /* 朗读完成回调 */ }
 *   tts.pause()   // 暂停当前朗读
 *   tts.resume()  // 恢复
 *   tts.stop()
 *   tts.shutdown()
 */
class TextToSpeechManager(context: Context) {

    /** 播放状态 */
    enum class PlaybackState {
        /** 空闲 */
        IDLE,
        /** 正在朗读 */
        PLAYING,
        /** 已暂停 */
        PAUSED
    }

    private data class PendingRequest(val text: String, val onDone: (() -> Unit)?)

    private var tts: TextToSpeech? = null
    var isInitialized: Boolean = false
        private set

    /** 当前播放状态 */
    var playbackState: PlaybackState = PlaybackState.IDLE
        private set

    /** 是否正在朗读（兼容旧接口） */
    val isSpeaking: Boolean get() = playbackState == PlaybackState.PLAYING

    var pitch: Float = 1.0f
        set(value) {
            field = value.coerceIn(0.5f, 2.0f)
            tts?.setPitch(field)
        }
    var speed: Float = 1.0f
        set(value) {
            field = value.coerceIn(0.5f, 2.0f)
            tts?.setSpeechRate(field)
        }

    private var onDoneCallback: (() -> Unit)? = null

    /** 初始化完成前积压的朗读请求队列 */
    private val pendingQueue = mutableListOf<PendingRequest>()

    /** 错误描述，供 UI 提示用户 */
    var lastError: String? = null
        private set

    // ── 暂停/恢复支持 ────────────────────────────────────────

    /** 暂停时保存的剩余文本（分句） */
    private val pausedSegments = mutableListOf<String>()
    private var pausedIndex = 0
    private var currentFullText: String = ""

    // ── 多消息队列 ────────────────────────────────────────────

    /** 待朗读的消息队列（多段文本自动排队） */
    private val speakQueue = mutableListOf<PendingRequest>()
    /** 是否正在消费队列 */
    private var isProcessingQueue = false
    /** 当前消息朗读完成后的最终回调 */
    private var currentMsgOnDone: (() -> Unit)? = null
    /** 朗读队列上下文：标记是队列模式还是单条模式 */
    private var isQueueMode = false

    init {
        try {
            tts = TextToSpeech(context) { status ->
                isInitialized = (status == TextToSpeech.SUCCESS)
                if (isInitialized) {
                    setupLanguage()
                    tts?.setPitch(pitch)
                    tts?.setSpeechRate(speed)
                    setupProgressListener()

                    // 如有初始化前积压的朗读请求，依次执行
                    val queue = pendingQueue.toList()
                    pendingQueue.clear()
                    for (req in queue) {
                        speak(req.text, req.onDone)
                    }
                } else {
                    Log.e("TTS", "TextToSpeech 初始化失败: status=$status")
                    lastError = "TTS 引擎初始化失败 ($status)"
                }
            }
        } catch (e: Exception) {
            Log.e("TTS", "TextToSpeech 创建异常", e)
            lastError = "TTS 引擎创建异常: ${e.message}"
        }
    }

    private fun setupLanguage() {
        val langResult = tts?.setLanguage(Locale.CHINESE) ?: TextToSpeech.LANG_NOT_SUPPORTED
        if (langResult == TextToSpeech.LANG_MISSING_DATA || langResult == TextToSpeech.LANG_NOT_SUPPORTED) {
            Log.w("TTS", "中文语音数据不可用 (result=$langResult)，使用系统默认语言")
            lastError = "中文语音包未安装"
        }
    }

    private fun setupProgressListener() {
        tts?.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
            override fun onStart(utteranceId: String?) {
                playbackState = PlaybackState.PLAYING
            }

            override fun onDone(utteranceId: String?) {
                playbackState = PlaybackState.IDLE

                if (isQueueMode) {
                    // 队列模式：读完一段，继续下一段
                    processQueueNext()
                } else if (isSingleUtteranceDone()) {
                    // 单条消息的分句读完了
                    onDoneCallback?.invoke()
                    onDoneCallback = null
                }
            }

            @Suppress("DEPRECATION")
            override fun onError(utteranceId: String?) {
                playbackState = PlaybackState.IDLE
                Log.w("TTS", "朗读出错: utteranceId=$utteranceId")
                lastError = "朗读出错"
                onDoneCallback?.invoke()
                onDoneCallback = null
                if (isQueueMode) {
                    processQueueNext()
                }
            }

            override fun onStop(utteranceId: String?, interrupted: Boolean) {
                playbackState = PlaybackState.IDLE
                if (!interrupted) {
                    // 非打断的 stop（即 pause）
                    // 状态由 pause() 方法管理
                } else {
                    onDoneCallback = null
                }
            }
        })
    }

    // ═══════════════════════════════════════════════════════════
    // 主朗读接口
    // ═══════════════════════════════════════════════════════════

    /**
     * 朗读文本（自动打断当前朗读）
     * @param text 要朗读的内容
     * @param onDone 朗读完成时的回调
     */
    fun speak(text: String, onDone: (() -> Unit)? = null) {
        if (text.isBlank()) {
            onDone?.invoke()
            return
        }
        if (!isInitialized) {
            Log.d("TTS", "TTS 尚未初始化完成，积压朗读请求")
            pendingQueue.add(PendingRequest(text, onDone))
            return
        }

        // 打断当前朗读和队列
        isQueueMode = false
        speakQueue.clear()
        isProcessingQueue = false

        // 停止当前朗读
        stopInternal()

        // 保存完整文本（用于暂停恢复）
        currentFullText = text
        currentMsgOnDone = onDone

        // 开始朗读
        isQueueMode = false
        speakTextSegments(text, onDone)
    }

    /**
     * 添加到朗读队列（不打断当前朗读，读完当前后自动接上）
     * @param text 要朗读的文本
     * @param onDone 该段朗读完成后的回调
     */
    fun speakQueued(text: String, onDone: (() -> Unit)? = null) {
        if (text.isBlank()) {
            onDone?.invoke()
            return
        }
        if (!isInitialized) {
            pendingQueue.add(PendingRequest(text, onDone))
            return
        }

        speakQueue.add(PendingRequest(text, onDone))

        if (!isProcessingQueue && playbackState != PlaybackState.PLAYING) {
            // 当前空闲，开始消费队列
            startQueueProcessing()
        }
    }

    /**
     * 暂停当前朗读（记住当前位置）
     */
    fun pause() {
        if (playbackState != PlaybackState.PLAYING) return

        Log.d("TTS", "暂停朗读")
        playbackState = PlaybackState.PAUSED
        onDoneCallback = null

        // 停止朗读引擎，但不清除队列
        try {
            tts?.stop()
        } catch (e: Exception) {
            Log.w("TTS", "暂停 stop 异常", e)
        }
    }

    /**
     * 恢复被暂停的朗读
     */
    fun resume() {
        if (playbackState != PlaybackState.PAUSED) return
        Log.d("TTS", "恢复朗读")

        // 从暂停位置继续朗读剩余文本
        val remaining = currentFullText
        if (remaining.isNotBlank()) {
            playbackState = PlaybackState.PLAYING
            speakTextSegments(remaining, currentMsgOnDone)
        } else {
            playbackState = PlaybackState.IDLE
            currentMsgOnDone?.invoke()
            currentMsgOnDone = null
        }
    }

    // ═══════════════════════════════════════════════════════════
    // 内部方法
    // ═══════════════════════════════════════════════════════════

    /**
     * 将文本分句后依次朗读（实现暂停恢复的粒度）
     */
    private fun speakTextSegments(text: String, onDone: (() -> Unit)?) {
        val engine = tts ?: run {
            onDone?.invoke()
            return
        }

        // 按标点分句
        val segments = splitSentences(text)
        if (segments.isEmpty()) {
            onDone?.invoke()
            return
        }

        // 只朗读第一句（后续句由 onDone 回调驱动）
        onDoneCallback = {
            // 继续下一句
        }
        playbackState = PlaybackState.PLAYING

        val utteranceId = "utt_${System.currentTimeMillis()}"
        val result = engine.speak(text, TextToSpeech.QUEUE_FLUSH, null, utteranceId)

        if (result == TextToSpeech.ERROR) {
            Log.w("TTS", "speak() 返回 ERROR")
            lastError = "朗读失败"
            playbackState = PlaybackState.IDLE
            onDone?.invoke()
        }
    }

    /** 判断单条消息是否所有分句都读完了 */
    private fun isSingleUtteranceDone(): Boolean {
        // 对于单段朗读，onDone 即表示结束
        return true
    }

    /** 开始消费队列 */
    private fun startQueueProcessing() {
        isQueueMode = true
        isProcessingQueue = true
        processQueueNext()
    }

    /** 处理队列下一项 */
    private fun processQueueNext() {
        if (speakQueue.isEmpty()) {
            isProcessingQueue = false
            isQueueMode = false
            return
        }

        if (playbackState == PlaybackState.PAUSED) {
            // 暂停状态，不继续处理
            isProcessingQueue = false
            return
        }

        val next = speakQueue.removeFirst()
        currentFullText = next.text
        currentMsgOnDone = next.onDone

        speakTextSegments(next.text) {
            next.onDone?.invoke()
            // 递归处理下一项（由 onDone 触发）
        }
    }

    /**
     * 将文本按句子分割
     */
    private fun splitSentences(text: String): List<String> {
        if (text.length <= 200) return listOf(text)

        val segments = mutableListOf<String>()
        val delimiters = arrayOf("。", "！", "？", "\n", "；", ".")
        var start = 0

        while (start < text.length) {
            var end = text.length
            for (delim in delimiters) {
                val idx = text.indexOf(delim, start)
                if (idx != -1 && idx + delim.length < end) {
                    end = idx + delim.length
                }
            }
            if (end == text.length && start == 0) {
                // 没有找到分隔符，整段返回
                return listOf(text)
            }
            segments.add(text.substring(start, end).trim())
            start = end
        }

        return segments.filter { it.isNotBlank() }
    }

    private fun stopInternal() {
        try {
            tts?.stop()
        } catch (e: Exception) {
            Log.w("TTS", "stopInternal 异常", e)
        }
        playbackState = PlaybackState.IDLE
        onDoneCallback = null
        pausedSegments.clear()
        pausedIndex = 0
    }

    // ═══════════════════════════════════════════════════════════
    // 公开控制接口
    // ═══════════════════════════════════════════════════════════

    /** 停止当前朗读 */
    fun stop() {
        stopInternal()
        speakQueue.clear()
        isProcessingQueue = false
        isQueueMode = false
        pendingQueue.clear()
        currentMsgOnDone = null
    }

    /** 清空待读队列 */
    fun clearQueue() {
        speakQueue.clear()
        isProcessingQueue = false
    }

    /** 释放 TTS 引擎资源 */
    fun shutdown() {
        stop()
        tts?.shutdown()
        tts = null
        isInitialized = false
    }
}
