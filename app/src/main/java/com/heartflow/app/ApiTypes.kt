package com.heartflow.app

/**
 * API 相关数据类型
 * 从 ApiService.kt 中提取，供 ChatViewModel / ModelClientBridge 共用
 */
data class ToolCallData(
    val id: String,
    val name: String,
    val arguments: String
)

data class ApiResponse(val content: String, val error: String? = null) {
    val isSuccess get() = error == null
}

interface StreamCallback {
    fun onToken(token: String)
    fun onReasoningDelta(reasoning: String)
    fun onComplete(fullText: String)
    fun onToolCalls(calls: List<ToolCallData>)
    fun onError(error: String)
}
