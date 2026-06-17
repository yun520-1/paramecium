package com.heartflow.engine

// ToolCall在同包内，无需导入

/**
 * 增强版插件钩子实现
 *
 * 支持四种钩子阶段：
 * - preToolCall: 工具调用前拦截/修改
 * - postToolCall: 工具调用后处理结果
 * - preUserMessage: 用户消息发送前处理（注入检测）
 * - postAssistantMessage: 助手消息接收后处理（清理/过滤）
 *
 * 配置：
 * - injectionDetector: 注入检测器，可选
 * - streamScrubber: 流式清理器，可选
 * - blockedTools: 被禁用的工具列表
 * - maxResultLength: 工具结果最大长度
 */
class DefaultPluginHooks(
    private val injectionDetector: InjectionDetector = InjectionDetector(),
    private val streamScrubber: StreamScrubber = StreamScrubber(),
    private val blockedTools: Set<String> = emptySet(),
    private val maxResultLength: Int = 32768,
) : PluginHooks {

    /** 钩子执行日志（调试用） */
    private val hookLog = mutableListOf<HookEvent>()

    /** 钩子执行统计 */
    private val stats = HookStats()

    /**
     * 工具调用前拦截
     *
     * 检查项：
     * 1. 工具是否在黑名单中
     * 2. 工具参数是否包含注入模式
     *
     * @return 非null则跳过工具执行，直接返回该结果
     */
    override suspend fun preToolCall(toolCall: ToolCall): String? {
        stats.preToolCallCount++

        // 检查工具黑名单
        if (toolCall.name in blockedTools) {
            val reason = "工具 '${toolCall.name}' 已被管理员禁用"
            hookLog.add(HookEvent.PreToolCallBlocked(toolCall.name, reason))
            stats.blockedToolCalls++
            return reason
        }

        // 检查工具参数中的注入模式
        val argsString = toolCall.arguments.toString()
        if (injectionDetector.detectInjection(argsString)) {
            val reason = "工具参数检测到潜在注入攻击，已拦截"
            hookLog.add(HookEvent.InjectionDetected(toolCall.name, argsString.take(200)))
            stats.injectionsBlocked++
            return reason
        }

        stats.passedToolCalls++
        return null
    }

    /**
     * 工具调用后处理结果
     *
     * 处理项：
     * 1. 结果超长时截断
     * 2. 清理结果中的敏感信息
     *
     * @return 非null则替换原始结果
     */
    override suspend fun postToolCall(toolCall: ToolCall, result: String): String? {
        stats.postToolCallCount++

        // 截断超长结果
        if (result.length > maxResultLength) {
            val truncated = result.take(maxResultLength) + "\n\n... [结果已截断，原始长度: ${result.length} 字符]"
            hookLog.add(HookEvent.ResultTruncated(toolCall.name, result.length, maxResultLength))
            stats.truncatedResults++
            return truncated
        }

        return null
    }

    /**
     * 检查工具是否为交互式（需要顺序执行）
     */
    override fun isInteractiveTool(toolName: String): Boolean {
        return toolName in setOf("bash", "shell", "terminal", "exec")
    }

    /**
     * 用户消息发送前处理
     *
     * 处理项：
     * 1. 检测prompt注入攻击
     * 2. 清理特殊控制字符
     * 3. 长度检查
     *
     * @param message 原始用户消息
     * @return 处理后的消息，null表示原始消息无修改
     */
    suspend fun preUserMessage(message: String): PreUserMessageResult {
        stats.preUserMessageCount++

        // 注入检测
        val injectionResult = injectionDetector.analyze(message)
        if (injectionResult.isMalicious) {
            stats.injectionsDetected++
            hookLog.add(HookEvent.InjectionDetected("user_message", injectionResult.patterns.joinToString()))
            return PreUserMessageResult(
                originalMessage = message,
                processedMessage = "[消息已被安全过滤：检测到潜在注入攻击]",
                wasModified = true,
                injectionResult = injectionResult
            )
        }

        // 清理控制字符（保留换行和制表符）
        val cleaned = message.replace(Regex("[\\x00-\\x08\\x0B\\x0C\\x0E-\\x1F\\x7F]"), "")
        if (cleaned != message) {
            stats.messagesCleaned++
            return PreUserMessageResult(
                originalMessage = message,
                processedMessage = cleaned,
                wasModified = true,
                injectionResult = injectionResult
            )
        }

        return PreUserMessageResult(
            originalMessage = message,
            processedMessage = message,
            wasModified = false,
            injectionResult = injectionResult
        )
    }

    /**
     * 助手消息接收后处理
     *
     * 处理项：
     * 1. 过滤<think>块（通过StreamScrubber）
     * 2. 清理多余的空白字符
     * 3. 检测助手是否被诱导输出系统提示
     *
     * @param message 助手原始响应
     * @return 处理后的消息
     */
    suspend fun postAssistantMessage(message: String): PostAssistantMessageResult {
        stats.postAssistantMessageCount++

        var processed = message
        var wasModified = false

        // 使用StreamScrubber过滤<think>块
        val scrubbed = streamScrubber.scrub(processed)
        if (scrubbed != processed) {
            processed = scrubbed
            wasModified = true
            stats.thinkBlocksFiltered++
        }

        // 清理多余空行（超过2个连续换行合并为2个）
        val normalized = processed.replace(Regex("\\n{3,}"), "\n\n")
        if (normalized != processed) {
            processed = normalized
            wasModified = true
        }

        // 检测系统提示泄露
        val leakCheck = injectionDetector.detectSystemPromptLeak(processed)
        if (leakCheck.isLeaking) {
            stats.systemPromptLeaksDetected++
            hookLog.add(HookEvent.SystemPromptLeak(leakCheck.details))
            return PostAssistantMessageResult(
                originalMessage = message,
                processedMessage = "[助手响应已过滤：检测到系统提示泄露风险]",
                wasModified = true,
                thinkContent = streamScrubber.getLastThinkContent(),
                leakDetected = true
            )
        }

        return PostAssistantMessageResult(
            originalMessage = message,
            processedMessage = processed,
            wasModified = wasModified,
            thinkContent = streamScrubber.getLastThinkContent(),
            leakDetected = false
        )
    }

    /**
     * 获取钩子执行日志
     */
    fun getHookLog(): List<HookEvent> = hookLog.toList()

    /**
     * 获取统计数据
     */
    fun getStats(): HookStats = stats.copy()

    /**
     * 重置统计和日志
     */
    fun reset() {
        hookLog.clear()
        stats.reset()
        streamScrubber.reset()
    }
}

/**
 * 用户消息处理结果
 */
data class PreUserMessageResult(
    val originalMessage: String,
    val processedMessage: String,
    val wasModified: Boolean,
    val injectionResult: InjectionResult,
)

/**
 * 助手消息处理结果
 */
data class PostAssistantMessageResult(
    val originalMessage: String,
    val processedMessage: String,
    val wasModified: Boolean,
    val thinkContent: String,
    val leakDetected: Boolean,
)

/**
 * 钩子事件类型（调试日志）
 */
sealed class HookEvent {
    /** 工具调用被拦截 */
    data class PreToolCallBlocked(val toolName: String, val reason: String) : HookEvent()

    /** 检测到注入攻击 */
    data class InjectionDetected(val source: String, val pattern: String) : HookEvent()

    /** 工具结果被截断 */
    data class ResultTruncated(val toolName: String, val originalLength: Int, val maxLength: Int) : HookEvent()

    /** 系统提示泄露 */
    data class SystemPromptLeak(val details: String) : HookEvent()
}

/**
 * 钩子执行统计
 */
data class HookStats(
    var preToolCallCount: Int = 0,
    var postToolCallCount: Int = 0,
    var preUserMessageCount: Int = 0,
    var postAssistantMessageCount: Int = 0,
    var blockedToolCalls: Int = 0,
    var injectionsBlocked: Int = 0,
    var injectionsDetected: Int = 0,
    var passedToolCalls: Int = 0,
    var truncatedResults: Int = 0,
    var messagesCleaned: Int = 0,
    var thinkBlocksFiltered: Int = 0,
    var systemPromptLeaksDetected: Int = 0,
) {
    fun reset() {
        preToolCallCount = 0
        postToolCallCount = 0
        preUserMessageCount = 0
        postAssistantMessageCount = 0
        blockedToolCalls = 0
        injectionsBlocked = 0
        injectionsDetected = 0
        passedToolCalls = 0
        truncatedResults = 0
        messagesCleaned = 0
        thinkBlocksFiltered = 0
        systemPromptLeaksDetected = 0
    }
}

/**
 * 注入检测器 - 检测prompt注入攻击模式
 *
 * 检测三类注入模式：
 * 1. 忽略指令类（Ignore Previous Instructions）
 * 2. 系统提示泄露类（System Prompt Leakage）
 * 3. 角色覆盖类（Role Override）
 *
 * 使用方式：
 * ```kotlin
 * val detector = InjectionDetector()
 * val result = detector.detectInjection(userInput)
 * if (result) { // 阻断 }
 * ```
 */
class InjectionDetector {

    /** 忽略指令类模式 */
    private val ignoreInstructionPatterns = listOf(
        Regex("(?i)忽略[之此前上面的]*指[示令命]|ignore\\s+(previous|above|prior|all)\\s+(instructions?|prompts?|rules?)", RegexOption.IGNORE_CASE),
        Regex("(?i)忽略[之此前上面]*所有|忽略[之此前上面]*规则|忽略[之此前上面]*设定", RegexOption.IGNORE_CASE),
        Regex("(?i)forget\\s+(everything|all|previous|above)", RegexOption.IGNORE_CASE),
        Regex("(?i)disregard\\s+(all|previous|above|your)", RegexOption.IGNORE_CASE),
        Regex("(?i)override\\s+(your|all|previous|above)\\s+(instructions?|rules?|prompts?)", RegexOption.IGNORE_CASE),
        Regex("(?i)新的指令[如下是：]|new\\s+instructions?\\s*[=：:]", RegexOption.IGNORE_CASE),
        Regex("(?i)从现在起[你都]|starting\\s+now\\s+you", RegexOption.IGNORE_CASE),
        Regex("(?i)停止[之前所有原有]|stop\\s+(following|using|applying)", RegexOption.IGNORE_CASE),
    )

    /** 系统提示泄露类模式 */
    private val systemPromptLeakPatterns = listOf(
        Regex("(?i)输出[你的你的系统提示|print\\s+(your|the)\\s+system\\s+prompt", RegexOption.IGNORE_CASE),
        Regex("(?i)显示[你的初始设定|show\\s+(your|the)\\s+(system|initial)\\s+(prompt|instructions?)", RegexOption.IGNORE_CASE),
        Regex("(?i)告诉我[你的系统|what\\s+(is|are)\\s+your\\s+(system\\s+)?(prompt|instructions?|rules?)", RegexOption.IGNORE_CASE),
        Regex("(?i)你的系统提示[是吗为]|you[r]?\\s+system\\s+prompt\\s+is", RegexOption.IGNORE_CASE),
        Regex("(?i)重复[你的系统|repeat\\s+(your|the)\\s+(system|initial)\\s+(prompt|instructions?)", RegexOption.IGNORE_CASE),
        Regex("(?i)回显[你的设定|echo\\s+(your|the)\\s+(system|initial)", RegexOption.IGNORE_CASE),
        Regex("(?i)dump\\s+(your|the)\\s+(system|full)\\s+prompt", RegexOption.IGNORE_CASE),
        Regex("(?i)reveal\\s+(your|the)\\s+(system|hidden|initial)\\s+(prompt|instructions?)", RegexOption.IGNORE_CASE),
    )

    /** 角色覆盖类模式 */
    private val roleOverridePatterns = listOf(
        Regex("(?i)你现在[是为扮演]|从现在起你是|you\\s+are\\s+now", RegexOption.IGNORE_CASE),
        Regex("(?i)假装[你是]|act\\s+as\\s+(if\\s+you\\s+are\\s+)?a\\s+", RegexOption.IGNORE_CASE),
        Regex("(?i)角色[切换变成更]|role\\s*(play|switch|change)", RegexOption.IGNORE_CASE),
        Regex("(?i)不再是[之前原来的]|you\\s+(are|will)\\s+no\\s+longer", RegexOption.IGNORE_CASE),
        Regex("(?i)进入[Dd]AN\\s*模式|DAN\\s+mode|do\\s+anything\\s+now", RegexOption.IGNORE_CASE),
        Regex("(?i)jailbreak|越狱模式|无限制模式", RegexOption.IGNORE_CASE),
        Regex("(?i)你是一个[没有任何限制]|无过滤[器限制]|unfiltered\\s+(ai|assistant|model)", RegexOption.IGNORE_CASE),
        Regex("(?i)developer\\s+mode|debug\\s+mode|admin\\s+mode", RegexOption.IGNORE_CASE),
        Regex("(?i)扮演[devil|evil|dark]\\s*advocate|扮演反派", RegexOption.IGNORE_CASE),
    )

    /** 高风险组合模式（多种模式同时出现时风险更高） */
    private val highRiskCombinations = listOf(
        // 角色覆盖 + 指令忽略
        Pair(roleOverridePatterns, ignoreInstructionPatterns),
        // 系统提示泄露 + 角色覆盖
        Pair(systemPromptLeakPatterns, roleOverridePatterns),
    )

    /**
     * 检测输入是否包含注入模式
     *
     * @param input 待检测文本
     * @return true表示检测到注入，应阻断
     */
    fun detectInjection(input: String): Boolean {
        return analyze(input).isMalicious
    }

    /**
     * 详细分析输入的注入风险
     *
     * @param input 待检测文本
     * @return 分析结果
     */
    fun analyze(input: String): InjectionResult {
        val matchedPatterns = mutableListOf<String>()
        var riskScore = 0

        // 检测忽略指令类
        for (pattern in ignoreInstructionPatterns) {
            if (pattern.containsMatchIn(input)) {
                matchedPatterns.add("ignore_instruction:${pattern.pattern}")
                riskScore += 30
            }
        }

        // 检测系统提示泄露类
        for (pattern in systemPromptLeakPatterns) {
            if (pattern.containsMatchIn(input)) {
                matchedPatterns.add("system_leak:${pattern.pattern}")
                riskScore += 40
            }
        }

        // 检测角色覆盖类
        for (pattern in roleOverridePatterns) {
            if (pattern.containsMatchIn(input)) {
                matchedPatterns.add("role_override:${pattern.pattern}")
                riskScore += 35
            }
        }

        // 检查高风险组合
        for ((patterns1, patterns2) in highRiskCombinations) {
            val hit1 = patterns1.any { it.containsMatchIn(input) }
            val hit2 = patterns2.any { it.containsMatchIn(input) }
            if (hit1 && hit2) {
                riskScore += 20
                matchedPatterns.add("high_risk_combination")
            }
        }

        // Base64编码检测（常见绕过手段）
        if (detectBase64EncodedInjection(input)) {
            riskScore += 25
            matchedPatterns.add("base64_encoded")
        }

        // Unicode同形字攻击检测
        if (detectHomoglyphAttack(input)) {
            riskScore += 15
            matchedPatterns.add("homoglyph_attack")
        }

        return InjectionResult(
            isMalicious = riskScore >= 50,
            riskScore = riskScore,
            patterns = matchedPatterns,
        )
    }

    /**
     * 检测系统提示是否被泄露到助手响应中
     *
     * @param response 助手响应文本
     * @return 泄露检测结果
     */
    fun detectSystemPromptLeak(response: String): LeakDetectionResult {
        val leakIndicators = mutableListOf<String>()

        // 检测典型的系统提示泄露特征
        val suspiciousPatterns = listOf(
            Regex("(?i)我的系统提示[是为：]|my\\s+system\\s+prompt\\s+is", RegexOption.IGNORE_CASE),
            Regex("(?i)我被设定为|I\\s+(was|am)\\s+(told|instructed|configured)\\s+to", RegexOption.IGNORE_CASE),
            Regex("(?i)我的指令[是为：]|my\\s+instructions?\\s+(are|is|say)", RegexOption.IGNORE_CASE),
            Regex("(?i)我的初始设定[是为：]|I\\s+(should|must|will)\\s+(always|never)", RegexOption.IGNORE_CASE),
        )

        for (pattern in suspiciousPatterns) {
            if (pattern.containsMatchIn(response)) {
                leakIndicators.add(pattern.pattern)
            }
        }

        return LeakDetectionResult(
            isLeaking = leakIndicators.isNotEmpty(),
            details = leakIndicators.joinToString("; "),
        )
    }

    /**
     * 检测Base64编码的注入内容
     *
     * 常见绕过手段：将注入指令进行Base64编码后嵌入
     */
    private fun detectBase64EncodedInjection(input: String): Boolean {
        // 匹配连续的Base64字符（至少40个字符，以避免误报）
        val base64Pattern = Regex("[A-Za-z0-9+/]{40,}={0,2}")
        val matches = base64Pattern.findAll(input)

        for (match in matches) {
            try {
                val decoded = java.util.Base64.getDecoder().decode(match.value)
                val decodedStr = String(decoded, Charsets.UTF_8)
                // 对解码后的内容也进行注入检测
                if (analyzeWithoutBase64(decodedStr).isMalicious) {
                    return true
                }
            } catch (_: IllegalArgumentException) {
                // 解码失败，跳过
            }
        }
        return false
    }

    /**
     * 不检查Base64的注入分析（避免递归）
     */
    private fun analyzeWithoutBase64(input: String): InjectionResult {
        var riskScore = 0
        val patterns = mutableListOf<String>()

        for (pattern in ignoreInstructionPatterns + systemPromptLeakPatterns + roleOverridePatterns) {
            if (pattern.containsMatchIn(input)) {
                riskScore += 30
                patterns.add(pattern.pattern)
            }
        }

        return InjectionResult(
            isMalicious = riskScore >= 50,
            riskScore = riskScore,
            patterns = patterns,
        )
    }

    /**
     * 检测Unicode同形字攻击
     *
     * 攻击者使用视觉相似的字符替换关键指令中的字符
     * 例如：用 Cyrillic 'а' 替换 Latin 'a'
     */
    private fun detectHomoglyphAttack(input: String): Boolean {
        // 检查是否包含大量非ASCII但视觉相似的字符
        var suspiciousCount = 0
        for (char in input) {
            val code = char.code
            // Cyrillic区块（与Latin视觉相似）
            if (code in 0x0400..0x04FF) suspiciousCount++
            // Greek区块
            if (code in 0x0370..0x03FF) suspiciousCount++
        }
        // 如果非ASCII字符占比过高，可能是在用同形字绕过检测
        return input.length > 10 && suspiciousCount > input.length * 0.3
    }
}

/**
 * 注入分析结果
 */
data class InjectionResult(
    /** 是否判定为恶意 */
    val isMalicious: Boolean,
    /** 风险分数（0-100） */
    val riskScore: Int,
    /** 匹配到的模式列表 */
    val patterns: List<String>,
)

/**
 * 泄露检测结果
 */
data class LeakDetectionResult(
    /** 是否检测到泄露 */
    val isLeaking: Boolean,
    /** 泄露详情 */
    val details: String,
)

/**
 * StreamScrubber - Think块过滤状态机
 *
 * 处理跨chunk的<think>标签，状态机模型：
 *
 *   ┌─────────┐  '<'  ┌──────────┐  't'  ┌────────────┐
 *   │ OUTSIDE │──────>│ MAYBE_THINK_OPEN │────>│ THINK_OPEN │
 *   └─────────┘       └──────────┘       └────────────┘
 *        │                   │                     │
 *        │ 其他字符          │ 非't'              │ '>' → 进入INSIDE_THINK
 *        │                   │                     │
 *        ▼                   ▼                     ▼
 *   输出字符            输出缓冲内容          输出缓冲内容
 *
 *   ┌──────────────┐  '<'  ┌────────────────┐
 *   │ INSIDE_THINK │──────>│ MAYBE_THINK_CLOSE│
 *   └──────────────┘       └────────────────┘
 *        │                        │
 *        │ 其他字符                │ '/' → 进入CLOSE_THINK
 *        │ → 丢弃                 │
 *        ▼                        ▼
 *   丢弃字符              丢弃缓冲内容
 *
 * 用途：
 * - DeepSeek/Qwen等模型的<think>...</think>输出过滤
 * - 流式场景下跨chunk的标签匹配
 * - 防止<think>标签被拆分到多个HTTP chunk中导致匹配失败
 *
 * 用法：
 * ```kotlin
 * val scrubber = StreamScrubber()
 * // 流式处理
 * for (chunk in chunks) {
 *     val cleaned = scrubber.processChunk(chunk)
 *     emit(cleaned)
 * }
 * // 完成后获取过滤掉的内容
 * val thinkContent = scrubber.getLastThinkContent()
 * ```
 */
class StreamScrubber {

    /** 当前状态 */
    private var state = ScrubState.OUTSIDE

    /** 标签匹配缓冲区 */
    private val tagBuffer = StringBuilder()

    /** 过滤掉的<think>内容 */
    private val thinkContent = StringBuilder()

    /** 输出缓冲区 */
    private val outputBuffer = StringBuilder()

    /** 是否正在处理中（用于reset判断） */
    private var isProcessing = false

    /**
     * 处理一个文本chunk，返回清理后的内容
     *
     * 逐字符处理，状态机驱动
     *
     * @param chunk 原始文本片段
     * @return 过滤后的文本（可能为空字符串）
     */
    fun processChunk(chunk: String): String {
        isProcessing = true
        val result = StringBuilder()

        for (char in chunk) {
            result.append(processChar(char))
        }

        return result.toString()
    }

    /**
     * 处理单个字符，返回应输出的字符（可能为空）
     */
    private fun processChar(char: Char): Char? {
        when (state) {
            ScrubState.OUTSIDE -> {
                if (char == '<') {
                    tagBuffer.clear()
                    tagBuffer.append(char)
                    state = ScrubState.MAYBE_THINK_OPEN
                    return null
                }
                outputBuffer.append(char)
                return char
            }

            ScrubState.MAYBE_THINK_OPEN -> {
                tagBuffer.append(char)
                val tag = tagBuffer.toString()
                when {
                    tag == "<think>" -> {
                        tagBuffer.clear()
                        state = ScrubState.INSIDE_THINK
                        return null
                    }
                    tag.startsWith("<think") -> {
                        // 继续匹配，等待闭合
                        return null
                    }
                    else -> {
                        // 不是<think>，输出缓冲内容
                        val buffered = tagBuffer.toString()
                        outputBuffer.append(buffered)
                        tagBuffer.clear()
                        state = ScrubState.OUTSIDE
                        return buffered[0] // 返回第一个字符，其余通过buffer
                    }
                }
            }

            ScrubState.INSIDE_THINK -> {
                if (char == '<') {
                    tagBuffer.clear()
                    tagBuffer.append(char)
                    state = ScrubState.MAYBE_THINK_CLOSE
                    return null
                }
                thinkContent.append(char)
                return null
            }

            ScrubState.MAYBE_THINK_CLOSE -> {
                tagBuffer.append(char)
                val tag = tagBuffer.toString()
                when {
                    tag == "</think>" -> {
                        tagBuffer.clear()
                        state = ScrubState.OUTSIDE
                        return null
                    }
                    tag.startsWith("</think") -> {
                        // 继续匹配
                        return null
                    }
                    else -> {
                        // 不是</think>，这些字符属于think内容
                        thinkContent.append(tagBuffer)
                        tagBuffer.clear()
                        state = ScrubState.INSIDE_THINK
                        return null
                    }
                }
            }
        }
    }

    /**
     * 获取最后一次过滤的<think>内容
     */
    fun getLastThinkContent(): String = thinkContent.toString()

    /**
     * 获取当前输出缓冲区内容
     */
    fun getOutputBuffer(): String = outputBuffer.toString()

    /**
     * 获取当前状态
     */
    fun getState(): ScrubState = state

    /**
     * 是否正在<think>块内
     */
    fun isInsideThink(): Boolean = state == ScrubState.INSIDE_THINK || state == ScrubState.MAYBE_THINK_CLOSE

    /**
     * 批量清理文本（非流式场景）
     *
     * @param text 完整文本
     * @return 清理后的文本，以及被过滤的think内容
     */
    fun scrub(text: String): String {
        reset()
        val result = processChunk(text)
        return result
    }

    /**
     * 完成处理，返回累积的输出
     */
    fun finish(): String {
        val result = outputBuffer.toString()
        reset()
        return result
    }

    /**
     * 重置状态机
     */
    fun reset() {
        state = ScrubState.OUTSIDE
        tagBuffer.clear()
        thinkContent.clear()
        outputBuffer.clear()
        isProcessing = false
    }
}

/**
 * StreamScrubber状态
 */
enum class ScrubState {
    /** 在<think>外部，正常输出 */
    OUTSIDE,

    /** 遇到<，正在匹配</think> */
    MAYBE_THINK_CLOSE,

    /** 在<think>块内部，过滤内容 */
    INSIDE_THINK,

    /** 遇到<，正在匹配<think>标签 */
    MAYBE_THINK_OPEN,
}
