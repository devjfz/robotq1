package com.deskrobot.llm

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.Job
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import org.json.JSONArray
import org.json.JSONObject

/**
 * OpenAI chat/completions 协议客户端（流式）。
 * 默认指向自建网关；也可直接指向任何 OpenAI 兼容端点（仅限本地调试，勿把厂商 Key 打进 APK）。
 *
 * @param baseUrl   返回服务根地址，例如 https://robot-gw.example.com
 * @param authToken 返回 Bearer 令牌；参数为 true 表示上次被拒（401），需要强制续期
 */
class OpenAiCompatibleLlmClient(
    private val http: OkHttpClient,
    private val baseUrl: () -> String,
    private val authToken: suspend (forceRenew: Boolean) -> String,
    private val model: String = "default",
) : LlmClient {

    override fun chat(
        messages: List<ChatMessage>,
        tools: List<ToolSpec>,
        temperature: Double?,
        maxTokens: Int?,
    ): Flow<LlmEvent> = flow {
        val body = buildBody(messages, tools, temperature, maxTokens).toString()
        var resp = execute(body, authToken(false))
        if (resp.code == 401) {
            resp.close()
            resp = execute(body, authToken(true))
        }
        resp.use { r ->
            if (!r.isSuccessful) {
                val msg = runCatching {
                    JSONObject(r.body!!.string()).getJSONObject("error").getString("code")
                }.getOrDefault("http_${r.code}")
                throw LlmException("大模型请求失败: $msg", r.code)
            }
            val source = r.body!!.source()
            val parser = OpenAiStreamParser()
            while (!parser.finished) {
                val line = source.readUtf8Line() ?: break
                parser.onLine(line).forEach { emit(it) }
            }
            parser.finishIfNeeded().forEach { emit(it) }
        }
    }.flowOn(Dispatchers.IO)

    private suspend fun execute(body: String, token: String): Response {
        val call = http.newCall(
            Request.Builder()
                .url("${baseUrl()}/v1/chat/completions")
                .header("Authorization", "Bearer $token")
                .header("Accept", "text/event-stream")
                .post(body.toRequestBody(GatewayAuth.JSON))
                .build()
        )
        // 协程取消时中断阻塞读取
        currentCoroutineContext()[Job]?.invokeOnCompletion { call.cancel() }
        return try {
            call.execute()
        } catch (e: Exception) {
            throw LlmException("无法连接网关: ${e.message}", cause = e)
        }
    }

    internal fun buildBody(
        messages: List<ChatMessage>,
        tools: List<ToolSpec>,
        temperature: Double?,
        maxTokens: Int?,
    ): JSONObject = JSONObject().apply {
        put("model", model)
        put("stream", true)
        put("messages", JSONArray().apply {
            messages.forEach { m ->
                put(JSONObject().apply {
                    put("role", m.role)
                    put("content", m.content)
                    m.toolCallId?.let { put("tool_call_id", it) }
                    if (m.toolCalls.isNotEmpty()) put("tool_calls", JSONArray().apply {
                        m.toolCalls.forEach { tc ->
                            put(JSONObject()
                                .put("id", tc.id)
                                .put("type", "function")
                                .put("function", JSONObject().put("name", tc.name).put("arguments", tc.argumentsJson)))
                        }
                    })
                })
            }
        })
        if (tools.isNotEmpty()) put("tools", JSONArray().apply {
            tools.forEach { t ->
                put(JSONObject()
                    .put("type", "function")
                    .put("function", JSONObject()
                        .put("name", t.name)
                        .put("description", t.description)
                        .put("parameters", JSONObject(t.parametersJson))))
            }
        })
        temperature?.let { put("temperature", it) }
        maxTokens?.let { put("max_tokens", it) }
    }
}
