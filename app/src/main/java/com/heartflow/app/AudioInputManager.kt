package com.heartflow.app

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.util.Locale

/**
 * 语音输入管理器 — 封装 Android SpeechRecognizer
 *
 * 职责：
 * - 开始/停止录音识别
 * - 实时回调部分识别结果（onPartialResult）
 * - 静音超时（5秒无语音自动结束）
 * - 错误处理（网络、服务不可用等，返回友好中文提示）
 * - 生命周期管理与资源释放
 *
 * 使用方式：
 *   val manager = AudioInputManager(context, viewModelScope,
 *       onResult = { text -> sendVoiceMessage(text) },
 *       onPartialResult = { text -> showPartial(text) },
 *       onError = { msg -> showError(msg) }
 *   )
 *   manager.start()
 *   manager.stop()
 *   manager.destroy()  // 页面/ViewModel销毁时调用
 */
class AudioInputManager(
    private val context: Context,
    private val scope: CoroutineScope,
    private val onResult: (String) -> Unit,
    private val onPartialResult: (String) -> Unit = {},
    private val onError: (String) -> Unit = {}
) {
    private var recognizer: SpeechRecognizer? = null
    private var silenceJob: Job? = null

    val isListening: Boolean get() = recognizer != null

    /**
     * 开始语音识别
     */
    fun start() {
        stop() // 确保之前的实例已清理
        Log.d(TAG, "开始语音识别")

        try {
            recognizer = SpeechRecognizer.createSpeechRecognizer(context)
            val currentRecognizer = recognizer ?: run {
                onError("语音识别器创建失败")
                return
            }

            currentRecognizer.setRecognitionListener(object : RecognitionListener {
                override fun onReadyForSpeech(params: Bundle?) {
                    Log.d(TAG, "语音识别就绪")
                }

                override fun onBeginningOfSpeech() {
                    Log.d(TAG, "检测到开始说话")
                }

                override fun onRmsChanged(rmsdB: Float) {
                    // 可用于显示音量指示器（预留）
                }

                override fun onBufferReceived(buffer: ByteArray?) {}

                override fun onEndOfSpeech() {
                    Log.d(TAG, "检测到说话结束")
                }

                override fun onResults(results: Bundle?) {
                    val matches = results?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
                    val text = matches?.firstOrNull()
                    Log.d(TAG, "识别结果: $text")
                    if (text != null) {
                        onResult(text)
                    } else {
                        onError("未识别到语音，请再试一次")
                    }
                    destroyRecognizer()
                }

                override fun onPartialResults(partialResults: Bundle?) {
                    val matches = partialResults?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
                    val text = matches?.firstOrNull()
                    if (text != null) {
                        onPartialResult(text)
                    }
                    // 每次收到部分结果，重置静音超时
                    resetSilenceTimeout()
                }

                override fun onEvent(eventType: Int, params: Bundle?) {}

                override fun onError(error: Int) {
                    val message = when (error) {
                        SpeechRecognizer.ERROR_NO_MATCH -> "未识别到语音，请再试一次"
                        SpeechRecognizer.ERROR_SPEECH_TIMEOUT -> "语音输入超时，请说话"
                        SpeechRecognizer.ERROR_RECOGNIZER_BUSY -> "识别器正忙，请稍后再试"
                        SpeechRecognizer.ERROR_NETWORK -> "网络不可用，无法进行语音识别"
                        SpeechRecognizer.ERROR_AUDIO -> "音频录制出现问题"
                        SpeechRecognizer.ERROR_CLIENT -> "语音识别客户端出错"
                        SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS -> "录音权限不足"
                        SpeechRecognizer.ERROR_NETWORK_TIMEOUT -> "网络连接超时"
                        SpeechRecognizer.ERROR_SERVER -> "识别服务器出错，请稍后再试"
                        SpeechRecognizer.ERROR_TOO_MANY_REQUESTS -> "请求过于频繁，请稍后再试"
                        SpeechRecognizer.ERROR_LANGUAGE_NOT_SUPPORTED -> "不支持中文语音识别"
                        SpeechRecognizer.ERROR_LANGUAGE_UNAVAILABLE -> "中文语言包不可用"
                        else -> "语音识别出错（错误码: $error）"
                    }
                    Log.w(TAG, "语音识别错误: $message")
                    onError(message)
                    destroyRecognizer()
                }
            })

            val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
                putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
                putExtra(RecognizerIntent.EXTRA_LANGUAGE, Locale.CHINESE)
                putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 1)
                putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true)
            }
            currentRecognizer.startListening(intent)

            // 5秒静音超时（如果没有任何部分结果，自动结束）
            silenceJob = scope.launch {
                delay(SILENCE_TIMEOUT_MS)
                if (recognizer != null) {
                    Log.d(TAG, "静音超时（5秒），自动结束")
                    onError("没有检测到语音，已自动结束")
                    destroyRecognizer()
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "创建语音识别器失败", e)
            onError("启动语音识别失败: ${e.message}")
            destroyRecognizer()
        }
    }

    /**
     * 停止语音识别
     */
    fun stop() {
        silenceJob?.cancel()
        silenceJob = null
        try {
            recognizer?.stopListening()
        } catch (e: Exception) {
            Log.w(TAG, "stopListening 异常", e)
        }
        destroyRecognizer()
    }

    /**
     * 每次收到部分结果时重置静音超时计时器
     */
    private fun resetSilenceTimeout() {
        silenceJob?.cancel()
        silenceJob = scope.launch {
            delay(SILENCE_TIMEOUT_MS)
            if (recognizer != null) {
                Log.d(TAG, "静音超时（5秒无新输入），自动结束")
                onError("长时间未检测到语音，已自动结束")
                destroyRecognizer()
            }
        }
    }

    /**
     * 销毁识别器实例，清除所有回调
     */
    private fun destroyRecognizer() {
        silenceJob?.cancel()
        silenceJob = null
        val r = recognizer
        if (r != null) {
            try {
                r.setRecognitionListener(null) // 防止销毁后回调
                r.destroy()
            } catch (e: Exception) {
                Log.w(TAG, "销毁识别器异常", e)
            }
        }
        recognizer = null
    }

    /**
     * 彻底释放所有资源
     */
    fun destroy() {
        stop()
    }

    companion object {
        private const val TAG = "AudioInputManager"
        private const val SILENCE_TIMEOUT_MS = 5000L
    }
}
