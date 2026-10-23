package com.deskrobot.llm

import kotlinx.coroutines.flow.Flow

/** 对话消息（OpenAI chat 协议子集） */
data class ChatMessage(
    val role: String,
    val content: String,
    val toolCallId: String? = null,
    val toolCalls: List<ToolCall> = emptyList(),
) {
    companion object {
        fun system(text: String) = ChatMessage("system", text)
        fun user(text: String) = ChatMessage("user", text)
        fun assistant(text: String, toolCalls: List<ToolCall> = emptyList()) = ChatMessage("assistant", text, toolCalls = toolCalls)
        fun tool(callId: String, result: String) = ChatMessage("tool", result, toolCallId = callId)
    }
}

/** 可供大模型调用的工具，parametersJson 为 JSON Schema 字符串 */
data class ToolSpec(val name: String, val description: String, val parametersJson: String)

data class ToolCall(val id: String, val name: String, val argumentsJson: String)

sealed interface LlmEvent {
    /** 流式文本增量 */
    data class Delta(val text: String) : LlmEvent
    /** 模型请求调用工具（参数已拼接完整） */
    data class ToolCalls(val calls: List<ToolCall>) : LlmEvent
    data class Done(val finishReason: String?) : LlmEvent
}

/**
 * 大模型客户端抽象。业务层只依赖此接口；
 * 默认实现 [OpenAiCompatibleLlmClient] 走自建网关（网关再路由到文心/DeepSeek/通义等），
 * 换厂商只改网关配置，App 无需发版。
 */
interface LlmClient {
    fun chat(
        messages: List<ChatMessage>,
        tools: List<ToolSpec> = emptyList(),
        temperature: Double? = null,
        maxTokens: Int? = null,
    ): Flow<LlmEvent>
}

class LlmException(message: String, val httpStatus: Int? = null, cause: Throwable? = null) : Exception(message, cause)
