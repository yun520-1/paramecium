package com.heartflow.engine

import com.heartflow.data.*
import kotlinx.coroutines.*
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.channels.Channel.Factory.CONFLATED
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CopyOnWriteArrayList
import kotlin.coroutines.coroutineContext
import kotlin.math.min
import kotlin.random.Random

/**
 * AgentLoop - 核心对话循环（参考hermes conversation_loop.py + tool_executor.py）
 * 
 * 增强特性：
 * 1. 并行工具执行：使用coroutineScope+async并发执行多个toolCalls
 * 2. 顺序执行：交互式工具按顺序执行
 * 3. 中断机制：Job.cancel()+isActive检查
 * 4. 重试/回退：jittered_backoff+fallback_chain
 * 5. 流式响应处理：支持streamCallback逐步返回文本
 * 6. 工具守卫栏：pluginHooks.preToolCall和guardrails
 * 7. 工具执行计时：记录每个工具的执行时间
 * 8. IterationBudget正确递增
 */
class AgentLoop(
    private val agent: AIAgent,
    private val toolExecutor: ToolExecutor,
    private val contextManager: ContextManager,
    private val pluginHooks: PluginHooks = PluginHooks.NoOp,
    private val guardrails: GuardrailsInterface = GuardrailsInterface.NoOp,
) {
    // 工具执行时间记录（线程安全）
    private val toolTimings = ConcurrentHashMap<String, CopyOnWriteArrayList<Long>>()
    
    // 当前对话的job引用（用于中断）
    @Volatile
    private var currentJob: Job? = null

    /**
     * 运行对话循环
     * @param userMessage 用户消息
     * @param streamCallback 流式响应回调，逐步返回文本片段
     * @param conversationId 对话ID（用于取消）
     * @return 对话结果
     */
    suspend fun runConversation(
        userMessage: String,
        streamCallback: ((String) -> Unit)? = null,
        conversationId: String? = null,
    ): ConversationResult = withContext(Dispatchers.Default) {
        val messages = mutableListOf<Message>()
        var apiCallCount = 0
        var finalResponse: String? = null
        
        // 保存当前job引用用于中断
        val job = coroutineContext[Job]!!
        currentJob = job
        
        // 重置迭代预算
        agent.iterationBudget.reset()
        
        val systemPrompt = contextManager.buildOrRestoreSystemPrompt(agent)
        
        try {
            while (apiCallCount < agent.maxIterations && 
                   agent.iterationBudget.remaining > 0 &&
                   coroutineContext.isActive &&
                   !agent.interruptRequested) {
                
                apiCallCount++
                agent.iterationBudget.increment()
                
                // 构建API消息
                val apiMessages = buildApiMessages(systemPrompt, messages, userMessage)
                
                // 调用LLM（带重试机制）
                val response = withRetryAndFallback {
                    callLlm(apiMessages, streamCallback)
                }
                
                when {
                    response.toolCalls.isNotEmpty() -> {
                        // 工具调用：并行执行非交互式工具，顺序执行交互式工具
                        val toolResults = executeToolCallsParallel(
                            toolCalls = response.toolCalls,
                            messages = messages,
                            streamCallback = streamCallback,
                        )
                        messages.addAll(toolResults)
                        
                        // 添加助手消息（包含工具调用）
                        messages.add(AssistantMessage(response))
                    }
                    response.content.isNotBlank() -> {
                        // 纯文本响应
                        finalResponse = response.content
                        messages.add(AssistantMessage(response))
                        break
                    }
                    else -> {
                        // 空响应处理
                        handleEmptyResponse(apiCallCount)
                    }
                }
                
                // 检查中断
                if (!coroutineContext.isActive) {
                    break
                }
            }
        } catch (e: CancellationException) {
            // 协程取消异常，正常退出
            throw e
        } catch (e: Exception) {
            // 其他异常，记录并返回
            finalResponse = finalResponse ?: "(error: ${e.message})"
        } finally {
            currentJob = null
        }
        
        ConversationResult(
            finalResponse = finalResponse ?: "(empty)",
            messages = messages,
            apiCalls = apiCallCount,
            toolTimings = toolTimings.toMap(),
        )
    }
    
    /**
     * 并行执行工具调用（参考hermes tool_executor.py）
     * - 非交互式工具：使用coroutineScope+async并行执行
     * - 交互式工具：顺序执行
     */
    private suspend fun executeToolCallsParallel(
        toolCalls: List<ToolCall>,
        messages: MutableList<Message>,
        streamCallback: ((String) -> Unit)?,
    ): List<Message> = coroutineScope {
        // 分离交互式和非交互式工具
        val (interactiveTools, parallelTools) = toolCalls.partition { tool ->
            pluginHooks.isInteractiveTool(tool.name)
        }
        
        val results = mutableListOf<Message>()
        
        // 并行执行非交互式工具
        if (parallelTools.isNotEmpty()) {
            val deferredResults = parallelTools.map { toolCall ->
                async(Dispatchers.Default) {
                    executeSingleToolWithGuard(toolCall, streamCallback)
                }
            }
            
            // 等待所有并行工具完成
            results.addAll(deferredResults.awaitAll())
        }
        
        // 顺序执行交互式工具
        for (toolCall in interactiveTools) {
            val result = executeSingleToolWithGuard(toolCall, streamCallback)
            results.add(result)
        }
        
        results
    }
    
    /**
     * 执行单个工具（带守卫栏检查）
     */
    private suspend fun executeSingleToolWithGuard(
        toolCall: ToolCall,
        streamCallback: ((String) -> Unit)?,
    ): Message {
        val startTime = System.currentTimeMillis()
        
        // 守卫栏检查：preToolCall
        val preCheckResult = pluginHooks.preToolCall(toolCall)
        if (preCheckResult != null) {
            return ToolResult(
                name = toolCall.name,
                args = toolCall.arguments,
                result = preCheckResult,
                duration = System.currentTimeMillis() - startTime,
                toolCallId = toolCall.id,
            )
        }
        
        // 守卫栏检查：guardrails
        val guardrailResult = guardrails.checkToolCall(toolCall)
        if (guardrailResult != null) {
            return ToolResult(
                name = toolCall.name,
                args = toolCall.arguments,
                result = guardrailResult,
                duration = System.currentTimeMillis() - startTime,
                toolCallId = toolCall.id,
            )
        }
        
        // 执行工具
        val result = try {
            withTimeout(agent.toolTimeout) {
                toolExecutor.execute(toolCall)
            }
        } catch (e: TimeoutCancellationException) {
            "工具执行超时: ${toolCall.name}"
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            "工具执行错误: ${e.message}"
        }
        
        val duration = System.currentTimeMillis() - startTime
        
        // 记录执行时间
        recordToolTiming(toolCall.name, duration)
        
        // 守卫栏检查：postToolCall
        val postResult = pluginHooks.postToolCall(toolCall, result)
        
        return ToolResult(
            name = toolCall.name,
            args = toolCall.arguments,
            result = postResult ?: result,
            duration = duration,
            toolCallId = toolCall.id,
        )
    }
    
    /**
     * 记录工具执行时间
     */
    private fun recordToolTiming(toolName: String, duration: Long) {
        toolTimings.getOrPut(toolName) { CopyOnWriteArrayList() }.add(duration)
    }
    
    /**
     * 构建API消息列表
     */
    private fun buildApiMessages(
        systemPrompt: String,
        messages: MutableList<Message>,
        userMessage: String
    ): List<Map<String, Any>> {
        val apiMessages = mutableListOf<Map<String, Any>>()
        apiMessages.add(mapOf("role" to "system", "content" to systemPrompt))
        
        for (msg in messages) {
            val messageMap = mutableMapOf<String, Any>(
                "role" to msg.role,
                "content" to msg.content,
            )
            
            // 如果是助手消息且有工具调用，添加tool_calls字段
            if (msg is AssistantMessage && msg.response.toolCalls.isNotEmpty()) {
                messageMap["tool_calls"] = msg.response.toolCalls.map { tc ->
                    mapOf(
                        "id" to tc.id,
                        "type" to "function",
                        "function" to mapOf(
                            "name" to tc.name,
                            "arguments" to tc.arguments,
                        ),
                    )
                }
            }
            
            // 如果是工具结果消息，添加tool_call_id字段
            if (msg is ToolResult) {
                messageMap["tool_call_id"] = msg.toolCallId
            }
            
            apiMessages.add(messageMap)
        }
        
        apiMessages.add(mapOf("role" to "user", "content" to userMessage))
        return apiMessages
    }
    
    /**
     * 调用LLM
     */
    private suspend fun callLlm(
        messages: List<Map<String, Any>>,
        streamCallback: ((String) -> Unit)?,
    ): LlmResponse {
        return agent.providerRouter.callWithFallback(
            messages = messages,
            tools = agent.tools,
            streamCallback = streamCallback,
        )
    }
    
    /**
     * 带重试和回退的LLM调用
     */
    private suspend fun <T> withRetryAndFallback(
        maxRetries: Int = 3,
        initialDelayMs: Long = 500,
        maxDelayMs: Long = 10000,
        block: suspend () -> T,
    ): T {
        var lastException: Exception? = null
        var delayMs = initialDelayMs
        
        repeat(maxRetries) { attempt ->
            try {
                return block()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                lastException = e
                
                if (attempt < maxRetries - 1) {
                    // 指数退避 + 抖动
                    val jitteredDelay = delayMs + Random.nextLong(delayMs / 2)
                    delay(min(jitteredDelay, maxDelayMs))
                    delayMs *= 2
                }
            }
        }
        
        throw lastException ?: Exception("重试失败")
    }
    
    /**
     * 处理空响应
     */
    private fun handleEmptyResponse(apiCallCount: Int) {
        if (apiCallCount >= agent.maxIterations - 1) {
            agent.requestInterrupt()
        }
    }
    
    /**
     * 请求中断当前对话
     */
    fun requestInterrupt() {
        agent.requestInterrupt()
        currentJob?.cancel()
    }
    
    /**
     * 获取工具执行统计
     */
    fun getToolStats(): Map<String, ToolStats> {
        return toolTimings.map { (name, times) ->
            name to ToolStats(
                count = times.size,
                totalDurationMs = times.sum(),
                avgDurationMs = times.average().toLong(),
                maxDurationMs = times.maxOrNull() ?: 0L,
                minDurationMs = times.minOrNull() ?: 0L,
            )
        }.toMap()
    }
    
    /**
     * 重置工具执行统计
     */
    fun resetToolStats() {
        toolTimings.clear()
    }
}

/**
 * 对话结果
 */
data class ConversationResult(
    val finalResponse: String,
    val messages: List<Message>,
    val apiCalls: Int,
    val toolTimings: Map<String, List<Long>> = emptyMap(),
)

/**
 * 工具执行统计
 */
data class ToolStats(
    val count: Int,
    val totalDurationMs: Long,
    val avgDurationMs: Long,
    val maxDurationMs: Long,
    val minDurationMs: Long,
)

/**
 * LLM响应
 */
data class LlmResponse(
    val content: String,
    val toolCalls: List<ToolCall>,
)

/**
 * 消息基类
 */
open class Message(
    val role: String,
    val content: String,
)

/**
 * 助手消息
 */
class AssistantMessage(val response: LlmResponse) : Message(
    role = "assistant",
    content = response.content,
)

/**
 * 工具调用
 */
data class ToolCall(
    val id: String,
    val name: String,
    val arguments: Map<String, Any>,
)

/**
 * LLM客户端接口
 */
interface LlmClient {
    suspend fun chat(
        messages: List<Map<String, Any>>,
        tools: List<Map<String, Any>>? = null,
        streamCallback: ((String) -> Unit)? = null,
    ): LlmResponse
}

/**
 * 提供商路由器（带fallback）
 */
class ProviderRouter(private val clients: Map<String, LlmClient>) {
    private var currentProvider: String = clients.keys.firstOrNull() ?: ""
    
    suspend fun callWithFallback(
        messages: List<Map<String, Any>>,
        tools: List<Map<String, Any>>? = null,
        streamCallback: ((String) -> Unit)? = null,
    ): LlmResponse {
        val client = clients[currentProvider] 
            ?: return LlmResponse("No provider available", emptyList())
        return client.chat(messages, tools = tools, streamCallback = streamCallback)
    }
    
    fun switchProvider(name: String) {
        if (clients.containsKey(name)) {
            currentProvider = name
        }
    }
    
    fun getCurrentProvider(): String = currentProvider
    
    fun getAvailableProviders(): List<String> = clients.keys.toList()
}

/**
 * 工具执行器接口
 */
interface ToolExecutor {
    suspend fun execute(toolCall: ToolCall): String
    
    suspend fun executeAll(toolCalls: List<ToolCall>, messages: MutableList<Message>): List<Message> {
        return withContext(Dispatchers.Default) {
            val deferredResults = toolCalls.map { toolCall ->
                async {
                    val startTime = System.currentTimeMillis()
                    val result = execute(toolCall)
                    val duration = System.currentTimeMillis() - startTime
                    ToolResult(
                        name = toolCall.name,
                        args = toolCall.arguments,
                        result = result,
                        duration = duration,
                        toolCallId = toolCall.id,
                    )
                }
            }
            deferredResults.awaitAll()
        }
    }
}

/**
 * 上下文管理器
 */
class ContextManager {
    private var cachedSystemPrompt: String? = null
    
    fun buildOrRestoreSystemPrompt(agent: AIAgent): String {
        if (cachedSystemPrompt != null) return cachedSystemPrompt!!
        val prompt = agent.buildSystemPrompt()
        cachedSystemPrompt = prompt
        return prompt
    }
    
    fun invalidateCache() {
        cachedSystemPrompt = null
    }
}

/**
 * AI代理
 */
class AIAgent(
    val model: String,
    val maxIterations: Int = 90,
    val providerRouter: ProviderRouter,
    private val toolExecutor: ToolExecutor,
    private val contextManager: ContextManager,
    val tools: List<Map<String, Any>>? = null,
    val toolTimeout: Long = 30_000L,
) {
    @Volatile
    var interruptRequested = false
        private set
    
    val iterationBudget = IterationBudget(maxIterations)
    
    fun buildSystemPrompt(): String {
        return "你是心虫AI助手，基于上下文回答，简洁有效。"
    }
    
    suspend fun runConversation(
        userMessage: String,
        streamCallback: ((String) -> Unit)? = null,
        conversationId: String? = null,
    ): ConversationResult {
        val loop = AgentLoop(this, toolExecutor, contextManager)
        return loop.runConversation(userMessage, streamCallback, conversationId)
    }
    
    fun requestInterrupt() {
        interruptRequested = true
    }
    
    fun resetInterrupt() {
        interruptRequested = false
    }
}

/**
 * 迭代预算
 */
class IterationBudget(private val maxIterations: Int) {
    @Volatile
    private var count = 0
    
    val remaining: Int get() = maxIterations - count
    
    val used: Int get() = count
    
    val isExhausted: Boolean get() = count >= maxIterations
    
    fun increment() {
        count++
    }
    
    fun reset() {
        count = 0
    }
}

/**
 * 工具执行结果
 */
class ToolResult(
    val name: String,
    val args: Map<String, Any>,
    result: String,
    val duration: Long,
    val toolCallId: String,
) : Message(role = "tool", content = result)

/**
 * 插件钩子接口
 */
interface PluginHooks {
    /**
     * 工具调用前检查
     * @return 非null则跳过工具执行，直接返回该结果
     */
    suspend fun preToolCall(toolCall: ToolCall): String? = null
    
    /**
     * 工具调用后处理
     * @return 非null则替换原始结果
     */
    suspend fun postToolCall(toolCall: ToolCall, result: String): String? = null
    
    /**
     * 检查工具是否为交互式（需要顺序执行）
     */
    fun isInteractiveTool(toolName: String): Boolean = false
    
    /**
     * 空操作实现
     */
    object NoOp : PluginHooks
}

/**
 * 守卫栏接口
 */
interface GuardrailsInterface {
    /**
     * 检查工具调用是否被允许
     * @return 非null则拒绝执行，返回拒绝原因
     */
    suspend fun checkToolCall(toolCall: ToolCall): String? = null
    
    /**
     * 空操作实现
     */
    object NoOp : GuardrailsInterface
}

/**
 * 默认工具执行器实现
 */
class DefaultToolExecutor : ToolExecutor {
    override suspend fun execute(toolCall: ToolCall): String {
        val tool = com.heartflow.data.ToolRegistry.getByName(toolCall.name)
            ?: return "未知工具: ${toolCall.name}"
        return tool.execute(toolCall.arguments)
    }
}
