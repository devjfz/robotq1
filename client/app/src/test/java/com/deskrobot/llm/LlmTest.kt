package com.deskrobot.llm

import okhttp3.OkHttpClient
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class LlmTest {

    @Test
    fun parsesTextDeltasAndDone() {
        val p = OpenAiStreamParser()
        val events = listOf(
            "data: {\"choices\":[{\"delta\":{\"role\":\"assistant\",\"content\":\"你\"}}]}",
            "",
            ": keep-alive",
            "data: {\"choices\":[{\"delta\":{\"content\":\"好\"},\"finish_reason\":null}]}",
            "data: {\"choices\":[{\"delta\":{},\"finish_reason\":\"stop\"}]}",
            "data: [DONE]",
        ).flatMap(p::onLine)

        assertEquals(listOf(LlmEvent.Delta("你"), LlmEvent.Delta("好"), LlmEvent.Done("stop")), events)
    }

    @Test
    fun assemblesStreamedToolCallArguments() {
        val p = OpenAiStreamParser()
        val events = listOf(
            """data: {"choices":[{"delta":{"tool_calls":[{"index":0,"id":"call_1","type":"function","function":{"name":"create_reminder","arguments":"{\"content\":"}}]}}]}""",
            """data: {"choices":[{"delta":{"tool_calls":[{"index":0,"function":{"arguments":"\"开会\"}"}}]}}]}""",
            """data: {"choices":[{"delta":{},"finish_reason":"tool_calls"}]}""",
        ).flatMap(p::onLine)

        assertEquals(
            listOf(
                LlmEvent.ToolCalls(listOf(ToolCall("call_1", "create_reminder", "{\"content\":\"开会\"}"))),
                LlmEvent.Done("tool_calls"),
            ),
            events,
        )
    }

    @Test
    fun finishIfNeededEmitsDoneOnce() {
        val p = OpenAiStreamParser()
        p.onLine("data: [DONE]")
        assertTrue(p.finishIfNeeded().isEmpty())
    }

    @Test
    fun buildsOpenAiRequestBody() {
        val client = OpenAiCompatibleLlmClient(OkHttpClient(), { "http://x" }, { "t" })
        val body = client.buildBody(
            listOf(
                ChatMessage.system("s"),
                ChatMessage.assistant("", listOf(ToolCall("c1", "f", "{}"))),
                ChatMessage.tool("c1", "ok"),
            ),
            listOf(ToolSpec("f", "desc", "{\"type\":\"object\"}")),
            temperature = 0.7,
            maxTokens = 100,
        )
        assertEquals(true, body.getBoolean("stream"))
        assertEquals("c1", body.getJSONArray("messages").getJSONObject(1).getJSONArray("tool_calls").getJSONObject(0).getString("id"))
        assertEquals("c1", body.getJSONArray("messages").getJSONObject(2).getString("tool_call_id"))
        assertEquals("object", body.getJSONArray("tools").getJSONObject(0).getJSONObject("function").getJSONObject("parameters").getString("type"))
        assertEquals(100, body.getInt("max_tokens"))
    }
}
