package com.heartflow.app.voice

import android.util.Log

/**
 * 语音活动检测器 — 基于 SpeechRecognizer.onRmsChanged 的音量级别分析
 *
 * 不依赖 WebRTC 库或 AudioRecord，直接复用 SpeechRecognizer 提供的 RMS dB 值。
 * 当音量连续低于阈值超过 silenceTimeoutMs 时，判定为静音。
 *
 * RMS dB 范围参考：
 * - 0 ~ 3 dB：很安静 / 背景噪音
 * - 3 ~ 7 dB：轻微说话或环境音
 * - 7 ~ 15 dB：正常说话
 * - 15+ dB：大声说话
 *
 * 使用方式：
 *   val vad = VoiceActivityDetector(silenceTimeoutMs = 1500)
 *   // 在 onRmsChanged 中调用：
 *   val state = vad.feedRms(rmsdB)
 *   if (state == DetectionState.SILENT) { /* 停止录音 */ }
 */
class VoiceActivityDetector(
    /** 连续静音超过此时间（毫秒）判定为说话结束 */
    private val silenceTimeoutMs: Long = 1500L,
    /** 音量阈值（dB），低于此值视为静音 */
    private val silenceThresholdDb: Float = 5.0f,
    /** 音量阈值（dB），高于此值视为正在说话 */
    private val speechThresholdDb: Float = 7.0f
) {

    /** 检测状态 */
    enum class DetectionState {
        /** 正在说话（音量高于阈值） */
        ACTIVE,
        /** 安静（音量低于阈值，但未超时） */
        IDLE,
        /** 静音超时 — 应停止录音 */
        SILENT
    }

    /** 当前检测状态 */
    var state: DetectionState = DetectionState.IDLE
        private set

    /** 当前 RMS 值（dB） */
    var currentRms: Float = 0f
        private set

    /** 本次说话的持续时长（毫秒） */
    var speechDurationMs: Long = 0L
        private set

    /** 上次说话结束的时间戳 */
    var lastSpeechTimestampMs: Long = 0L
        private set

    private var lastActiveTimeMs: Long = System.currentTimeMillis()
    private var speechStartTimeMs: Long = 0L
    private var lastResetMs: Long = System.currentTimeMillis()
    private var consecutiveSilenceMs: Long = 0L
    private var wasEverActive = false

    /**
     * 输入 RMS dB 值，返回当前检测状态
     * @param rmsdB SpeechRecognizer.onRmsChanged 传入的 dB 值
     * @return 当前的检测状态
     */
    fun feedRms(rmsdB: Float): DetectionState {
        currentRms = rmsdB
        val now = System.currentTimeMillis()

        if (rmsdB >= speechThresholdDb) {
            // 检测到说话
            if (!wasEverActive) {
                speechStartTimeMs = now
                wasEverActive = true
            }
            lastActiveTimeMs = now
            consecutiveSilenceMs = 0

            if (state != DetectionState.ACTIVE) {
                Log.d(TAG, "VAD: 检测到说话 (RMS=${"%.1f".format(rmsdB)} dB)")
            }
            state = DetectionState.ACTIVE
            speechDurationMs = now - speechStartTimeMs

        } else if (rmsdB >= silenceThresholdDb) {
            // 过渡区：轻微音量，不算完全静音也不到说话
            // 重置静音计时但不标记为说话
            consecutiveSilenceMs = 0
            state = DetectionState.IDLE

        } else {
            // 安静状态
            if (wasEverActive) {
                // 曾经说过话，开始计算静音时长
                if (consecutiveSilenceMs == 0L) {
                    // 第一次进入静音，记录时间
                    consecutiveSilenceMs = 0
                    lastActiveTimeMs = now
                }
                consecutiveSilenceMs = now - lastActiveTimeMs

                if (consecutiveSilenceMs >= silenceTimeoutMs) {
                    Log.d(TAG, "VAD: 静音超时 (${consecutiveSilenceMs}ms >= ${silenceTimeoutMs}ms)")
                    state = DetectionState.SILENT
                    lastSpeechTimestampMs = now
                } else {
                    state = DetectionState.IDLE
                }
            } else {
                // 还从未说过话，但有一定音量（可能在背景噪音中）
                state = DetectionState.IDLE
            }
        }

        return state
    }

    /**
     * 重置检测器状态
     */
    fun reset() {
        state = DetectionState.IDLE
        currentRms = 0f
        consecutiveSilenceMs = 0L
        wasEverActive = false
        speechDurationMs = 0L
        lastResetMs = System.currentTimeMillis()
        Log.d(TAG, "VAD: 已重置")
    }

    companion object {
        private const val TAG = "VoiceActivityDetector"
    }
}
