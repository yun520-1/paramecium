package com.heartflow.app.voice

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
 * 语音识别管理器 — 封装 Android SpeechRecognizer + VAD 增强
 *
 * 相比 [AudioInputManager] 的改进：
 * - 基于 RMS 音量的实时 VAD 检测，替代固定 5 秒超时
 * - 音量级别回调，支持 UI 可视化（音量柱/波形）
 * - 说话结束检测更灵敏（1.5s 静音 vs 5s）
 * - 更好的错误恢复和生命周期管理
 *
 * 使用方式：
 *   val manager = VoiceRecognitionManager(context, viewModelScope,
 *       onResult = { text -> sendVoiceMessage(text) },
 *       onPartialResult = { text -> showPartial(text) },
 *       onVolumeChanged = { level -> showVolumeLevel(level) },
 *       onError = { msg -> showError(msg) }
 *   )
 *   manager.start()
 *   manager.stop()
 *   manager.destroy()
 */
class VoiceRecognitionManager(
    private val context: Context,
    private val scope: CoroutineScope,
    private val onResult: (String) -> Unit,
    private val onPartialResult: (String) -> Unit = {},
    /** 实时音量级别 0.0 ~ 1.0（用于 UI 可视化） */
    private val onVolumeChanged: (Float) -> Unit = {},
    private val onError: (String) -> Unit = {}
) {
    private var recognizer: SpeechRecognizer? = null
    private var _isListening = false

    /** 兜底总超时 Job（防止 SpeechRecognizer 卡住） */
    private var cleanupJob: Job? = null

    /** 是否正在由 VAD 触发自动结束 */
    @Volatile
    private var endingByVad = false

    /** 语音活动检测器 */
    private val vad = VoiceActivityDetector(
        silenceTimeoutMs = SILENCE_TIMEOUT_MS
    )

    /** 是否正在监听 */
    val isListening: Boolean get() = _isListening

    /** 是否检测到正在说话 */
    val isSpeaking: Boolean get() = vad.state == VoiceActivityDetector.DetectionState.ACTIVE

    /** 当前 RMS 音量级别（归一化 0.0 ~ 1.0，供 UI 可视化） */
    val currentVolumeLevel: Float
        get() = normalizeRms(vad.currentRms)

    /**
     * 开始语音识别
     */
    fun start() {
        stop() // 确保之前的实例已清理
        Log.d(TAG, "开始语音识别（VAD 静音超时: ${SILENCE_TIMEOUT_MS}ms）")

        try {
            recognizer = SpeechRecognizer.createSpeechRecognizer(context)
            val currentRecognizer = recognizer ?: run {
                onError("语音识别器创建失败")
                return
            }

            vad.reset()
            endingByVad = false

            currentRecognizer.setRecognitionListener(object : RecognitionListener {
                override fun onReadyForSpeech(params: Bundle?) {
                    Log.d(TAG, "语音识别就绪")
                }

                override fun onBeginningOfSpeech() {
                    Log.d(TAG, "检测到开始说话")
                }

                override fun onRmsChanged(rmsdB: Float) {
                    // 1. 驱动 VAD 检测
                    val detectionState = vad.feedRms(rmsdB)

                    // 2. 回调归一化音量级别（0.0 ~ 1.0）
                    onVolumeChanged(normalizeRms(rmsdB))

                    // 3. VAD 检测到静音超时 → 主动结束录音
                    if (detectionState == VoiceActivityDetector.DetectionState.SILENT && !endingByVad) {
                        endingByVad = true
                        Log.d(TAG, "VAD 检测到静音超时，自动停止录音")
                        // 调用 recognizer.stopListening() 会触发 onResults（部分结果）
                        try {
                            recognizer?.stopListening()
                        } catch (e: Exception) {
                            Log.w(TAG, "VAD stopListening 异常", e)
                            onError("停止录音失败")
                            destroyRecognizer()
                        }
                    }
                }

                override fun onBufferReceived(buffer: ByteArray?) {}

                override fun onEndOfSpeech() {
                    Log.d(TAG, "SpeechRecognizer 检测到说话结束")
                }

                override fun onResults(results: Bundle?) {
                    val matches = results?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
                    val text = matches?.firstOrNull()

                    Log.d(TAG, "识别结果: ${if (text != null) "\"$text\"" else "null"}")

                    if (text != null && text.isNotBlank()) {
                        if (endingByVad) {
                            // VAD 触发结束：部分结果可能已通过 onPartialResult 显示
                            // 如果 onPartialResult 最后一帧与 onResults 相同，用户不会感知重复
                            Log.d(TAG, "VAD 结束模式：提交最终结果")
                        }
                        onResult(text)
                    } else {
                        // 空结果：仅当 VAD 触发时静默忽略
                        if (!endingByVad) {
                            onError("未识别到语音，请再试一次")
                        }
                    }
                    destroyRecognizer()
                }

                override fun onPartialResults(partialResults: Bundle?) {
                    val matches = partialResults?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
                    val text = matches?.firstOrNull()
                    if (text != null) {
                        onPartialResult(text)
                    }
                }

                override fun onEvent(eventType: Int, params: Bundle?) {}

                override fun onError(error: Int) {
                    val message = when (error) {
                        SpeechRecognizer.ERROR_NO_MATCH -> if (endingByVad) null else "未识别到语音，请再试一次"
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
                    if (message != null) {
                        Log.w(TAG, "语音识别错误: $message")
                        onError(message)
                    }
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
            _isListening = true

            // 兜底超时：15 秒后强制结束（防止 SpeechRecognizer 彻底卡死）
            cleanupJob = scope.launch {
                delay(SHUTDOWN_TIMEOUT_MS)
                if (recognizer != null) {
                    Log.w(TAG, "兜底超时（${SHUTDOWN_TIMEOUT_MS}ms），强制结束")
                    onError("语音识别超时，请重试")
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
        Log.d(TAG, "主动停止语音识别")
        cancelJobs()
        try {
            recognizer?.stopListening()
        } catch (e: Exception) {
            Log.w(TAG, "stopListening 异常", e)
        }
        destroyRecognizer()
    }

    /**
     * 取消所有关联 Job
     */
    private fun cancelJobs() {
        cleanupJob?.cancel()
        cleanupJob = null
    }

    /**
     * 销毁识别器实例，清除所有回调
     */
    private fun destroyRecognizer() {
        cancelJobs()
        _isListening = false
        endingByVad = false
        vad.reset()

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

    /**
     * 将 RMS dB 值归一化为 0.0 ~ 1.0
     * - 0 dB → 0.0（静音）
     * - 10 dB → ~0.5（正常说话）
     * - 20+ dB → 1.0（大声说话）
     */
    private fun normalizeRms(rmsdB: Float): Float {
        return (rmsdB / MAX_RMS_DB).coerceIn(0f, 1f)
    }

    companion object {
        private const val TAG = "VoiceRecognitionManager"

        /** VAD 静音超时（连续低于音量阈值后等待时间） */
        private const val SILENCE_TIMEOUT_MS = 1500L

        /** 兜底总超时：防止 SpeechRecognizer 卡住无回调 */
        private const val SHUTDOWN_TIMEOUT_MS = 15_000L

        /** RMS dB 最大值参考（用于 0.0~1.0 归一化） */
        private const val MAX_RMS_DB = 20f
    }
}
