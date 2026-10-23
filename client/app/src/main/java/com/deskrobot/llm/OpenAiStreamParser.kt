package com.deskrobot.llm

import org.json.JSONObject

/**
 * 解析 OpenAI 协议的 SSE 流（逐行喂入），把分片的 tool_calls 参数拼接完整后再产出。
 */
class OpenAiStreamParser {

    private class PartialCall(var id: String = "", var name: String = "", val args: StringBuilder = StringBuilder())

    private val calls = sortedMapOf<Int, PartialCall>()
    var finished = false
        private set

    /** 处理一行 SSE，返回本行产生的事件 */
    fun onLine(line: String): List<LlmEvent> {
        if (!line.startsWith("data:")) return emptyList()
        val data = line.removePrefix("data:").trim()
        if (data.isEmpty()) return emptyList()
        if (data == "[DONE]") return finish(null)

        val choice = JSONObject(data).optJSONArray("choices")?.optJSONObject(0) ?: return emptyList()
        val events = mutableListOf<LlmEvent>()
        val delta = choice.optJSONObject("delta")
        if (delta != null) {
            val text = delta.optString("content", "")
            if (text.isNotEmpty() && !delta.isNull("content")) events += LlmEvent.Delta(text)
            val toolCalls = delta.optJSONArray("tool_calls")
            if (toolCalls != null) {
                for (i in 0 until toolCalls.length()) {
                    val tc = toolCalls.getJSONObject(i)
                    val p = calls.getOrPut(tc.optInt("index", i)) { PartialCall() }
                    tc.optString("id").takeIf { it.isNotEmpty() }?.let { p.id = it }
                    tc.optJSONObject("function")?.let { fn ->
                        fn.optString("name").takeIf { it.isNotEmpty() }?.let { p.name = it }
                        if (!fn.isNull("arguments")) p.args.append(fn.optString("arguments", ""))
                    }
                }
            }
        }
        val reason = choice.optString("finish_reason", "")
        if (reason.isNotEmpty() && !choice.isNull("finish_reason")) events += finish(reason)
        return events
    }

    /** 流异常结束时调用，保证最终产出 Done */
    fun finishIfNeeded(): List<LlmEvent> = finish(null)

    private fun finish(reason: String?): List<LlmEvent> {
        if (finished) return emptyList()
        finished = true
        val out = mutableListOf<LlmEvent>()
        if (calls.isNotEmpty()) {
            out += LlmEvent.ToolCalls(calls.values.map { ToolCall(it.id, it.name, it.args.toString().ifEmpty { "{}" }) })
        }
        out += LlmEvent.Done(reason)
        return out
    }
}
