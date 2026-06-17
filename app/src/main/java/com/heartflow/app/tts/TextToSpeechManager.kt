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

    init {
        try {
            tts = TextToSpeech(context) { status ->
                isInitialized = (status == TextToSpeech.SUCCESS)
                if (isInitialized) {
                    tts?.language = Locale.CHINESE
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
                            onDoneCallback?.invoke()
                            onDoneCallback = null
                        }
                        override fun onStop(utteranceId: String?, interrupted: Boolean) {
                            isSpeaking = false
                            onDoneCallback = null
                        }
                    })
                } else {
                    Log.e("TTS", "TextToSpeech 初始化失败: status=$status")
                }
            }
        } catch (e: Exception) {
            Log.e("TTS", "TextToSpeech 创建异常", e)
        }
    }

    /**
     * 朗读文本
     * @param text 要朗读的内容
     * @param onDone 朗读完成时的回调（包括正常结束、出错、被停止）
     */
    fun speak(text: String, onDone: (() -> Unit)? = null) {
        val engine = tts ?: return
        if (!isInitialized) {
            Log.w("TTS", "TTS 尚未初始化完成")
            onDone?.invoke()
            return
        }
        if (text.isBlank()) {
            onDone?.invoke()
            return
        }
        // 停止当前朗读
        stop()
        onDoneCallback = onDone
        engine.speak(text, TextToSpeech.QUEUE_FLUSH, null, "utt_${System.currentTimeMillis()}")
    }

    /** 停止当前朗读 */
    fun stop() {
        tts?.stop()
        isSpeaking = false
        onDoneCallback = null
    }

    /** 释放 TTS 引擎资源 */
    fun shutdown() {
        stop()
        tts?.shutdown()
        tts = null
        isInitialized = false
    }
}
