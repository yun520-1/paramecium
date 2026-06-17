package com.heartflow.app.tts

import android.content.Context
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import android.util.Log
import java.util.Locale

/**
 * TTS 语音朗读管理器 — 封装 Android 内置 TextToSpeech API
 *
 * 使用方式：
 *   val tts = TextToSpeechManager(context)
 *   tts.speak("你好") { /* 朗读完成回调 */ }
 *   tts.stop()
 *   tts.shutdown()  // 页面/ViewModel 销毁时调用
 */
class TextToSpeechManager(context: Context) {

    private var tts: TextToSpeech? = null
    var isSpeaking: Boolean = false
        private set
    var isInitialized: Boolean = false
        private set

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

    /** 初始化完成前积压的朗读请求 */
    private var pendingText: String? = null
    private var pendingOnDone: (() -> Unit)? = null

    /** 错误描述，供 UI 提示用户 */
    var lastError: String? = null
        private set

    init {
        try {
            tts = TextToSpeech(context) { status ->
                isInitialized = (status == TextToSpeech.SUCCESS)
                if (isInitialized) {
                    // 设置中文，如果不支持则回退默认
                    val langResult = tts?.setLanguage(Locale.CHINESE) ?: TextToSpeech.LANG_NOT_SUPPORTED
                    if (langResult == TextToSpeech.LANG_MISSING_DATA || langResult == TextToSpeech.LANG_NOT_SUPPORTED) {
                        Log.w("TTS", "中文语音数据不可用 (result=$langResult)，使用系统默认语言")
                        lastError = "中文语音包未安装"
                    }
                    tts?.setPitch(pitch)
                    tts?.setSpeechRate(speed)
                    tts?.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
                        override fun onStart(utteranceId: String?) {
                            isSpeaking = true
                        }
                        override fun onDone(utteranceId: String?) {
                            isSpeaking = false
                            onDoneCallback?.invoke()
                            onDoneCallback = null
                        }
                        override fun onError(utteranceId: String?) {
                            isSpeaking = false
                            Log.w("TTS", "朗读出错: utteranceId=$utteranceId")
                            lastError = "朗读出错"
                            onDoneCallback?.invoke()
                            onDoneCallback = null
                        }
                        override fun onStop(utteranceId: String?, interrupted: Boolean) {
                            isSpeaking = false
                            onDoneCallback = null
                        }
                    })
                    // 如有初始化前积压的朗读请求，立即执行
                    pendingText?.let { text ->
                        val cb = pendingOnDone
                        pendingText = null
                        pendingOnDone = null
                        speakNow(text, cb)
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

    /**
     * 朗读文本（支持初始化未完成时积压）
     * @param text 要朗读的内容
     * @param onDone 朗读完成时的回调
     */
    fun speak(text: String, onDone: (() -> Unit)? = null) {
        if (text.isBlank()) {
            onDone?.invoke()
            return
        }
        if (!isInitialized) {
            // 引擎还在初始化中，积压请求，初始化完成后自动朗读
            Log.d("TTS", "TTS 尚未初始化完成，积压朗读请求")
            pendingText = text
            pendingOnDone = onDone
            return
        }
        val engine = tts ?: return
        // 停止当前朗读
        stop()
        speakNow(text, onDone)
    }

    /** 引擎已就绪时的内部朗读方法 */
    private fun speakNow(text: String, onDone: (() -> Unit)? = null) {
        val engine = tts ?: run {
            onDone?.invoke()
            return
        }
        onDoneCallback = onDone
        val result = engine.speak(text, TextToSpeech.QUEUE_FLUSH, null, "utt_${System.currentTimeMillis()}")
        if (result == TextToSpeech.ERROR) {
            Log.w("TTS", "speak() 返回 ERROR")
            lastError = "朗读失败"
            onDoneCallback = null
            onDone?.invoke()
        }
    }

    /** 停止当前朗读 */
    fun stop() {
        tts?.stop()
        isSpeaking = false
        onDoneCallback = null
        // 清除积压请求
        pendingText = null
        pendingOnDone = null
    }

    /** 释放 TTS 引擎资源 */
    fun shutdown() {
        stop()
        tts?.shutdown()
        tts = null
        isInitialized = false
    }
}
