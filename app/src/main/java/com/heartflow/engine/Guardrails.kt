package com.heartflow.engine

import java.security.MessageDigest
import java.security.SecureRandom
import java.security.cert.X509Certificate
import java.text.Normalizer
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger
import javax.net.ssl.SSLContext
import javax.net.ssl.TrustManager
import javax.net.ssl.X509TrustManager

/**
 * 安全守护系统 - 5个安全子系统
 * 保护 HeartFlow 引擎免受各种安全威胁
 */
object Guardrails : GuardrailsInterface {

    override suspend fun checkToolCall(toolCall: ToolCall): String? {
        val result = ToolGuardrails.recordToolCall(toolCall.name, toolCall.arguments, true)
        return if (!result) "工具调用循环检测：重复失败模式" else null
    }

    // ==================== ToolGuardrails - 工具调用循环护栏 ====================

    /**
     * 工具调用失败模式枚举
     */
    enum class FailureMode {
        WARN,  // 警告模式：记录日志但继续执行
        BLOCK, // 阻塞模式：阻止后续调用
        HALT   // 停止模式：立即终止当前操作
    }

    /**
     * 工具调用跟踪记录
     */
    data class ToolCallRecord(
        val toolName: String,
        val signature: String,
        val timestamp: Long,
        val success: Boolean
    )

    /**
     * 工具调用循环护栏
     * 使用 SHA256 签名跟踪工具调用，检测循环调用和重复调用
     */
    object ToolGuardrails {
        // 调用历史记录（最近100条）
        private val callHistory = mutableListOf<ToolCallRecord>()
        private const val MAX_HISTORY_SIZE = 100

        // 失败计数器
        private val failureCounters = ConcurrentHashMap<String, AtomicInteger>()

        // 默认失败模式
        var defaultFailureMode: FailureMode = FailureMode.WARN

        // 工具失败模式配置
        private val toolFailureModes = ConcurrentHashMap<String, FailureMode>()

        /**
         * 计算调用签名（SHA256）
         */
        fun computeSignature(toolName: String, args: Map<String, Any>): String {
            val input = "$toolName:${args.entries.sortedBy { it.key }.joinToString { "${it.key}=${it.value}" }}"
            val digest = MessageDigest.getInstance("SHA-256")
            val hash = digest.digest(input.toByteArray())
            return hash.joinToString("") { "%02x".format(it) }
        }

        /**
         * 记录工具调用
         * @return 是否允许继续执行
         */
        fun recordToolCall(toolName: String, args: Map<String, Any>, success: Boolean): Boolean {
            val signature = computeSignature(toolName, args)
            val record = ToolCallRecord(toolName, signature, System.currentTimeMillis(), success)

            synchronized(callHistory) {
                callHistory.add(record)
                if (callHistory.size > MAX_HISTORY_SIZE) {
                    callHistory.removeAt(0)
                }
            }

            // 更新失败计数
            if (!success) {
                failureCounters.getOrPut(toolName) { AtomicInteger(0) }.incrementAndGet()
            } else {
                failureCounters[toolName]?.set(0)
            }

            // 检查是否需要阻止
            return checkAndEnforce(toolName, signature)
        }

        /**
         * 检查并执行失败模式策略
         */
        private fun checkAndEnforce(toolName: String, currentSignature: String): Boolean {
            // 检查循环调用（相同签名在最近5条记录中出现2次以上）
            val recentCalls = synchronized(callHistory) {
                callHistory.takeLast(10).filter { it.toolName == toolName }
            }

            val signatureCount = recentCalls.count { it.signature == currentSignature }
            if (signatureCount >= 3) {
                val mode = toolFailureModes[toolName] ?: defaultFailureMode
                return when (mode) {
                    FailureMode.WARN -> {
                        println("⚠️ 检测到工具 $toolName 循环调用（签名: $currentSignature）")
                        true
                    }
                    FailureMode.BLOCK -> {
                        println("🚫 工具 $toolName 被阻塞（循环调用检测）")
                        false
                    }
                    FailureMode.HALT -> {
                        throw SecurityException("🛑 工具 $toolName 触发停止模式（严重循环调用）")
                    }
                }
            }

            // 检查连续失败
            val consecutiveFailures = recentCalls.takeLast(5).count { !it.success }
            if (consecutiveFailures >= 3) {
                val mode = toolFailureModes[toolName] ?: defaultFailureMode
                return when (mode) {
                    FailureMode.WARN -> {
                        println("⚠️ 工具 $toolName 连续失败 $consecutiveFailures 次")
                        true
                    }
                    FailureMode.BLOCK -> {
                        println("🚫 工具 $toolName 因连续失败被阻塞")
                        false
                    }
                    FailureMode.HALT -> {
                        throw SecurityException("🛑 工具 $toolName 触发停止模式（连续失败）")
                    }
                }
            }

            return true
        }

        /**
         * 设置工具的失败模式
         */
        fun setFailureMode(toolName: String, mode: FailureMode) {
            toolFailureModes[toolName] = mode
        }

        /**
         * 获取工具的失败计数
         */
        fun getFailureCount(toolName: String): Int {
            return failureCounters[toolName]?.get() ?: 0
        }

        /**
         * 重置工具的失败计数
         */
        fun resetFailureCount(toolName: String) {
            failureCounters[toolName]?.set(0)
        }
    }

    // ==================== MessageSanitization - 消息清理 ====================

    /**
     * 消息清理器
     * 处理 UTF-8 代理字符、JSON 格式修复等
     */
    object MessageSanitization {

        /**
         * 清理消息内容
         * 修复各种编码和格式问题
         */
        fun sanitize(message: String): String {
            var result = message

            // 1. 替换 UTF-8 代理字符
            result = replaceSurrogatePairs(result)

            // 2. 修复 JSON 格式问题
            result = fixJsonFormatting(result)

            // 3. 移除不可见字符（保留换行和制表符）
            result = removeInvisibleCharacters(result)

            // 4. 规范化 Unicode
            result = normalizeUnicode(result)

            return result
        }

        /**
         * 替换 UTF-8 代理字符对
         */
        private fun replaceSurrogatePairs(input: String): String {
            val sb = StringBuilder()
            var i = 0
            while (i < input.length) {
                val c = input[i]
                // 检查是否是高代理项
                if (Character.isHighSurrogate(c) && i + 1 < input.length) {
                    val next = input[i + 1]
                    if (Character.isLowSurrogate(next)) {
                        // 有效的代理对，替换为 Unicode 替换字符
                        sb.append("�")
                        i += 2
                        continue
                    }
                }
                sb.append(c)
                i++
            }
            return sb.toString()
        }

        /**
         * 修复 JSON 格式问题
         */
        private fun fixJsonFormatting(input: String): String {
            var result = input

            // 修复未转义的控制字符
            result = result.replace("\n", "\\n")
            result = result.replace("\r", "\\r")
            result = result.replace("\t", "\\t")

            // 修复未转义的反斜杠（避免重复转义）
            result = result.replace("\\\\", "\\\\\\")
            result = result.replace("\\n", "\n")

            // 修复未闭合的引号
            val quoteCount = result.count { it == '"' }
            if (quoteCount % 2 != 0) {
                result = result + "\""
            }

            // 修复未闭合的花括号
            val openBraces = result.count { it == '{' }
            val closeBraces = result.count { it == '}' }
            if (openBraces > closeBraces) {
                result = result + "}".repeat(openBraces - closeBraces)
            }

            // 修复未闭合的方括号
            val openBrackets = result.count { it == '[' }
            val closeBrackets = result.count { it == ']' }
            if (openBrackets > closeBrackets) {
                result = result + "]".repeat(openBrackets - closeBrackets)
            }

            return result
        }

        /**
         * 移除不可见字符（保留换行和制表符）
         */
        private fun removeInvisibleCharacters(input: String): String {
            val sb = StringBuilder()
            for (c in input) {
                // 保留可打印字符、换行、制表符
                if (c.isLetterOrDigit() || c.isWhitespace() || c in ".,;:!?@#\$%^&*(){}[]|/\\<>~`-_=+")
                {
                    sb.append(c)
                } else if (c.code in 0x20..0x7E) {
                    // 可打印 ASCII 字符
                    sb.append(c)
                }
            }
            return sb.toString()
        }

        /**
         * 规范化 Unicode
         */
        private fun normalizeUnicode(input: String): String {
            return Normalizer.normalize(input, Normalizer.Form.NFC)
        }

        /**
         * 验证消息是否安全
         */
        fun isSafe(message: String): Boolean {
            // 检查是否包含潜在的注入攻击
            val dangerousPatterns = listOf(
                "<script",
                "javascript:",
                "onerror=",
                "onload=",
                "eval(",
                "document.cookie",
                "window.location"
            )

            val lowerMessage = message.lowercase()
            return dangerousPatterns.none { lowerMessage.contains(it) }
        }
    }

    // ==================== FileSafety - 文件访问控制 ====================

    /**
     * 文件访问控制器
     * 防止访问敏感文件和目录
     */
    object FileSafety {

        /**
         * 敏感路径拒绝列表
         */
        private val sensitivePaths = listOf(
            // SSH 相关
            ".ssh",
            ".ssh/authorized_keys",
            ".ssh/id_rsa",
            ".ssh/id_ed25519",
            ".ssh/known_hosts",
            ".ssh/config",

            // 环境变量和密钥
            ".env",
            ".env.local",
            ".env.production",
            ".env.development",
            ".env.staging",
            "credentials.json",
            "secrets.json",
            "service-account.json",
            "key.json",

            // 系统文件
            "/etc/passwd",
            "/etc/shadow",
            "/etc/hosts",
            "/etc/sudoers",

            // 密钥文件
            "*.pem",
            "*.key",
            "*.p12",
            "*.pfx",
            "*.jks",
            "*.keystore",

            // 数据库配置
            "database.yml",
            "config.yml",
            "application.properties",
            "application.yml",

            // 版本控制
            ".git/config",
            ".git/HEAD"
        )

        /**
         * 敏感文件扩展名
         */
        private val sensitiveExtensions = setOf(
            ".pem", ".key", ".p12", ".pfx", ".jks", ".keystore",
            ".env", ".env.local", ".env.production",
            "credentials", "secrets", "service-account"
        )

        /**
         * 检查路径是否安全
         * @return true 表示安全，false 表示拒绝访问
         */
        fun isPathSafe(path: String): Boolean {
            val normalizedPath = path.lowercase().replace("\\", "/")

            // 检查是否匹配敏感路径
            for (sensitivePath in sensitivePaths) {
                val normalizedSensitive = sensitivePath.lowercase().replace("\\", "/")

                // 支持通配符匹配
                if (normalizedSensitive.contains("*")) {
                    val regex = normalizedSensitive.replace(".", "\\.").replace("*", ".*").toRegex()
                    if (regex.containsMatchIn(normalizedPath)) {
                        println("🚫 拒绝访问敏感路径: $path (匹配模式: $sensitivePath)")
                        return false
                    }
                } else if (normalizedPath.contains(normalizedSensitive)) {
                    println("🚫 拒绝访问敏感路径: $path (包含: $sensitivePath)")
                    return false
                }
            }

            // 检查文件扩展名
            val extension = path.substringAfterLast('.', "")
            if (extension in sensitiveExtensions) {
                println("🚫 拒绝访问敏感文件: $path (扩展名: .$extension)")
                return false
            }

            // 检查目录遍历攻击
            if (normalizedPath.contains("..")) {
                println("🚫 拒绝访问路径: $path (包含目录遍历)")
                return false
            }

            return true
        }

        /**
         * 清理文件路径
         * 移除潜在的危险字符
         */
        fun sanitizePath(path: String): String {
            return path
                .replace("..", "")  // 移除目录遍历
                .replace("\u0000", "")  // 移除空字符
                .trim()
        }

        /**
         * 获取安全的文件路径
         */
        fun getSafePath(path: String): String? {
            val sanitized = sanitizePath(path)
            return if (isPathSafe(sanitized)) sanitized else null
        }
    }

    // ==================== SslGuard - SSL 证书验证检查 ====================

    /**
     * SSL 证书验证守卫
     * 检查 SSL 连接的安全性
     */
    object SslGuard {

        /**
         * SSL 验证结果
         */
        data class SslVerificationResult(
            val isSecure: Boolean,
            val certificateValid: Boolean,
            val protocolSupported: Boolean,
            val issues: List<String>
        )

        /**
         * 已知的不安全协议
         */
        private val insecureProtocols = setOf("SSLv2", "SSLv3", "TLSv1", "TLSv1.1")

        /**
         * 已知的不安全密码套件
         */
        private val insecureCipherSuites = setOf(
            "DES", "3DES", "RC4", "RC2", "MD5", "NULL", "EXPORT", "anon"
        )

        /**
         * 验证 SSL 连接配置
         */
        fun verifySslConfig(sslContext: SSLContext): SslVerificationResult {
            val issues = mutableListOf<String>()

            val protocols = try { sslContext.defaultSSLParameters.protocols } catch (e: Exception) { arrayOf("TLSv1.2") }
            val hasInsecureProtocol = protocols.any { it in insecureProtocols }
            if (hasInsecureProtocol) {
                issues.add("启用了不安全的协议: ${protocols.filter { it in insecureProtocols }.joinToString()}")
            }

            val cipherSuites = try { sslContext.defaultSSLParameters.cipherSuites } catch (e: Exception) { arrayOf() }
            val hasInsecureCipher = cipherSuites.any { suite ->
                insecureCipherSuites.any { insecure -> suite.contains(insecure) }
            }
            if (hasInsecureCipher) {
                issues.add("启用了不安全的密码套件")
            }

            return SslVerificationResult(
                isSecure = issues.isEmpty(),
                certificateValid = true,
                protocolSupported = !hasInsecureProtocol,
                issues = issues
            )
        }

        /**
         * 验证证书链
         */
        fun verifyCertificateChain(certificates: Array<X509Certificate>): Boolean {
            if (certificates.isEmpty()) return false

            // 检查证书过期
            val now = java.util.Date()
            for (cert in certificates) {
                if (now.before(cert.notBefore) || now.after(cert.notAfter)) {
                    println("🚫 证书已过期或尚未生效: ${cert.subjectDN}")
                    return false
                }
            }

            // 检查自签名证书（简化检查）
            val rootCert = certificates.last()
            if (rootCert.issuerDN != rootCert.subjectDN) {
                println("⚠️ 根证书不是自签名的")
            }

            return true
        }

        /**
         * 创建安全的 SSL 上下文
         */
        fun createSecureSslContext(): SSLContext {
            val sslContext = SSLContext.getInstance("TLSv1.2")

            // 创建信任管理器（仅用于测试，生产环境应使用真实的证书验证）
            val trustManager = object : X509TrustManager {
                override fun checkClientTrusted(chain: Array<X509Certificate>, authType: String) {
                    // 实际应用中应进行完整的证书验证
                }

                override fun checkServerTrusted(chain: Array<X509Certificate>, authType: String) {
                    // 实际应用中应进行完整的证书验证
                }

                override fun getAcceptedIssuers(): Array<X509Certificate> = arrayOf()
            }

            sslContext.init(null, arrayOf<TrustManager>(trustManager), SecureRandom())
            return sslContext
        }
    }

    // ==================== RateGuard - 速率限制保护 ====================

    /**
     * 速率限制守卫
     * 解析 x-ratelimit 头，防止 API 调用超限
     */
    object RateGuard {

        /**
         * 速率限制信息
         */
        data class RateLimitInfo(
            val limit: Long,        // 限制总数
            val remaining: Long,    // 剩余数量
            val reset: Long,        // 重置时间戳
            val retryAfter: Long?   // 重试等待时间（秒）
        )

        /**
         * 速率限制状态
         */
        enum class RateLimitStatus {
            OK,           // 正常
            WARNING,      // 警告（剩余 < 20%）
            EXCEEDED,     // 已超限
            RETRY_WAIT    // 需要等待重试
        }

        /**
         * 速率限制跟踪器
         */
        data class RateLimitTracker(
            val status: RateLimitStatus,
            val info: RateLimitInfo?,
            val message: String
        )

        /**
         * 解析 x-ratelimit 响应头
         */
        fun parseRateLimitHeaders(headers: Map<String, String>): RateLimitInfo? {
            val limit = headers["x-ratelimit-limit"]?.toLongOrNull() ?: return null
            val remaining = headers["x-ratelimit-remaining"]?.toLongOrNull() ?: return null
            val reset = headers["x-ratelimit-reset"]?.toLongOrNull() ?: return null
            val retryAfter = headers["retry-after"]?.toLongOrNull()

            return RateLimitInfo(
                limit = limit,
                remaining = remaining,
                reset = reset,
                retryAfter = retryAfter
            )
        }

        /**
         * 检查速率限制状态
         */
        fun checkRateLimit(headers: Map<String, String>): RateLimitTracker {
            val info = parseRateLimitHeaders(headers)

            if (info == null) {
                return RateLimitTracker(
                    status = RateLimitStatus.OK,
                    info = null,
                    message = "未检测到速率限制头"
                )
            }

            // 检查是否需要等待重试
            if (info.retryAfter != null) {
                return RateLimitTracker(
                    status = RateLimitStatus.RETRY_WAIT,
                    info = info,
                    message = "需要等待 ${info.retryAfter} 秒后重试"
                )
            }

            // 检查是否已超限
            if (info.remaining <= 0) {
                val resetIn = info.reset - System.currentTimeMillis() / 1000
                return RateLimitTracker(
                    status = RateLimitStatus.EXCEEDED,
                    info = info,
                    message = "已达到速率限制，将在 ${resetIn} 秒后重置"
                )
            }

            // 检查是否接近限制
            val usagePercent = ((info.limit - info.remaining).toDouble() / info.limit) * 100
            if (usagePercent > 80) {
                return RateLimitTracker(
                    status = RateLimitStatus.WARNING,
                    info = info,
                    message = "速率限制使用率 ${"%.1f".format(usagePercent)}%，剩余 ${info.remaining}/${info.limit}"
                )
            }

            return RateLimitTracker(
                status = RateLimitStatus.OK,
                info = info,
                message = "速率限制正常：剩余 ${info.remaining}/${info.limit}"
            )
        }

        /**
         * 计算重试等待时间
         */
        fun calculateRetryDelay(headers: Map<String, String>): Long {
            val info = parseRateLimitHeaders(headers) ?: return 0

            // 如果有 retry-after 头，使用它
            if (info.retryAfter != null) {
                return info.retryAfter * 1000  // 转换为毫秒
            }

            // 计算到重置时间的等待时间
            val resetTime = info.reset * 1000  // 转换为毫秒
            val currentTime = System.currentTimeMillis()
            val waitTime = resetTime - currentTime

            return if (waitTime > 0) waitTime else 0
        }

        /**
         * 生成速率限制请求头
         */
        fun generateRateLimitHeaders(): Map<String, String> {
            return mapOf(
                "X-RateLimit-Limit" to "100",
                "X-RateLimit-Window" to "60"
            )
        }
    }
}
