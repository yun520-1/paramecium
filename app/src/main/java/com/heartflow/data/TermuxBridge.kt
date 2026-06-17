package com.heartflow.data

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.os.Build
import android.app.PendingIntent
import android.util.Log
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout

/**
 * Termux 桥接器 — 通过 Termux RUN_COMMAND Intent API 在对话/终端中执行命令
 *
 * 方案 A：使用 RunCommandService Intent，每条命令独立执行
 * 要求：
 *   1. Termux 已安装（检测包名 com.termux）
 *   2. Termux 设置中 allow-external-apps = true
 *
 * 回退策略：不可用时返回失败结果，由调用方回退到内置 sh
 */
class TermuxBridge(private val context: Context) {

    companion object {
        private const val TAG = "TermuxBridge"

        /** Termux RUN_COMMAND 常量（与 TermuxConstants 同步） */
        const val TERMUX_PACKAGE = "com.termux"
        const val ACTION_RUN_COMMAND = "com.termux.RUN_COMMAND"

        const val EXTRA_COMMAND_PATH = "com.termux.RUN_COMMAND_PATH"
        const val EXTRA_ARGUMENTS = "com.termux.RUN_COMMAND_ARGUMENTS"
        const val EXTRA_STDIN = "com.termux.RUN_COMMAND_STDIN"
        const val EXTRA_WORKDIR = "com.termux.RUN_COMMAND_WORKDIR"
        const val EXTRA_RUNNER = "com.termux.RUN_COMMAND_RUNNER"
        const val EXTRA_PENDING_INTENT = "com.termux.RUN_COMMAND_PENDING_INTENT"

        /** 广播回传的 extras */
        const val EXTRA_STDOUT = "com.termux.RUN_COMMAND_STDOUT"
        const val EXTRA_STDERR = "com.termux.RUN_COMMAND_STDERR"
        const val EXTRA_EXIT_CODE = "com.termux.RUN_COMMAND_EXIT_CODE"

        /** Runner 模式 */
        const val RUNNER_APP_SHELL = "app_shell"          // 后台无 UI
        const val RUNNER_TERMINAL_SESSION = "terminal_session" // 前台 Termux 窗口

        /** Termux bash 绝对路径 */
        const val BASH_PATH = "/data/data/com.termux/files/usr/bin/bash"

        const val DEFAULT_TIMEOUT_MS = 30_000L
    }

    /** 单次 Termux 执行结果 */
    data class TermuxResult(
        val stdout: String,
        val stderr: String,
        val exitCode: Int,
        val success: Boolean = exitCode == 0
    )

    // 缓存可用性状态（避免每次检测都跑一条命令）
    private var availableCache: Boolean? = null
    private var lastAvailableCheck: Long = 0

    // ──────────────────────────────────────────────
    // 可用性检测
    // ──────────────────────────────────────────────

    /** 检测 Termux 是否已安装（查包名，无需启动） */
    fun isInstalled(): Boolean {
        return try {
            context.packageManager.getPackageInfo(TERMUX_PACKAGE, 0)
            true
        } catch (_: PackageManager.NameNotFoundException) {
            false
        }
    }

    /** 获取 Termux 版本号（显示用） */
    fun getVersion(): String {
        return try {
            val pkg = context.packageManager.getPackageInfo(TERMUX_PACKAGE, 0)
            pkg.versionName ?: "未知"
        } catch (_: Exception) { "未安装" }
    }

    /**
     * 检测 Termux 是否可用（已安装 + allow-external-apps 已启用）
     * 结果会缓存 30 秒
     */
    suspend fun isAvailable(timeoutMs: Long = 5000): Boolean {
        val now = System.currentTimeMillis()
        if (availableCache != null && now - lastAvailableCheck < 30_000) {
            return availableCache!!
        }

        if (!isInstalled()) {
            availableCache = false
            lastAvailableCheck = now
            return false
        }

        // 实际运行一条 echo 命令验证 allow-external-apps
        val result = execute("echo 'termux_ok'", timeoutMs = timeoutMs)
        availableCache = result.success && result.stdout.trim().contains("termux_ok")
        lastAvailableCheck = now
        return availableCache!!
    }

    /** 清除可用性缓存（让下一次 isAvailable 重测） */
    fun resetAvailability() {
        availableCache = null
    }

    // ──────────────────────────────────────────────
    // 命令执行（方案 A：RunCommandService + PendingIntent）
    // ──────────────────────────────────────────────

    /**
     * 在 Termux 环境中执行命令
     *
     * @param command   Shell 命令字符串
     * @param workdir   工作目录（null = Termux 默认 ~）
     * @param timeoutMs 超时毫秒
     * @param stdin     标准输入内容（可选）
     */
    suspend fun execute(
        command: String,
        workdir: String? = null,
        timeoutMs: Long = DEFAULT_TIMEOUT_MS,
        stdin: String? = null
    ): TermuxResult = withContext(Dispatchers.IO) {

        if (!isInstalled()) {
            return@withContext TermuxResult("", "Termux 未安装", -1)
        }

        // 唯一请求 ID，用于 BroadcastReceiver 的 IntentFilter
        val requestId = "hf_${System.currentTimeMillis()}_${(Math.random() * 99999).toInt()}"
        val deferred = CompletableDeferred<TermuxResult>()

        // 动态注册 BroadcastReceiver，接收 Termux 返回的结果
        val receiver = object : BroadcastReceiver() {
            override fun onReceive(ctx: Context, intent: Intent) {
                val stdout = intent.getStringExtra(EXTRA_STDOUT) ?: ""
                val stderr = intent.getStringExtra(EXTRA_STDERR) ?: ""
                val exitCode = intent.getIntExtra(EXTRA_EXIT_CODE, -1)
                deferred.complete(TermuxResult(stdout, stderr, exitCode))
            }
        }

        try {
            // API 33+：声明不导出，防止其他 App 向我们的 Receiver 发消息
            val flags = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                Context.RECEIVER_NOT_EXPORTED
            } else {
                0
            }
            context.registerReceiver(receiver, IntentFilter(requestId), flags)

            // 构建广播 PendingIntent — Termux 执行完成后发回结果
            val resultIntent = Intent(requestId).setPackage(context.packageName)
            val pendingFlags = PendingIntent.FLAG_UPDATE_CURRENT or
                    PendingIntent.FLAG_IMMUTABLE
            val pendingIntent = PendingIntent.getBroadcast(
                context,
                requestId.hashCode(),
                resultIntent,
                pendingFlags
            )

            // 构建 RUN_COMMAND Intent
            val runIntent = Intent(ACTION_RUN_COMMAND).apply {
                `package` = TERMUX_PACKAGE
                putExtra(EXTRA_COMMAND_PATH, BASH_PATH)      // 用 Termux 的 bash
                putExtra(EXTRA_ARGUMENTS, arrayOf("-c", command))
                putExtra(EXTRA_RUNNER, RUNNER_APP_SHELL)      // 后台执行
                putExtra(EXTRA_PENDING_INTENT, pendingIntent) // 结果回传
                if (workdir != null) putExtra(EXTRA_WORKDIR, workdir)
                if (stdin != null) putExtra(EXTRA_STDIN, stdin)
            }

            try {
                context.startService(runIntent)
            } catch (e: Exception) {
                // Android 12+ 后台服务启动限制可能投递失败
                return@withContext TermuxResult(
                    stdout = "",
                    stderr = "无法启动 Termux 服务: ${e.message}",
                    exitCode = -1
                )
            }

            // 等待结果（带超时）
            val result = withTimeout(timeoutMs) {
                deferred.await()
            }

            result
        } catch (e: TimeoutCancellationException) {
            TermuxResult("", "执行超时 (${timeoutMs / 1000}s)", -1)
        } catch (e: Exception) {
            TermuxResult("", "执行错误: ${e.message}", -1)
        } finally {
            try { context.unregisterReceiver(receiver) } catch (_: Exception) {}
        }
    }
}
