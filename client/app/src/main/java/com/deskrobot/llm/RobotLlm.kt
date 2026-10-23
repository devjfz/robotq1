package com.deskrobot.llm

import android.content.Context
import okhttp3.OkHttpClient
import java.util.concurrent.TimeUnit

/** 大模型相关单例（P2 引入依赖注入后替换） */
object RobotLlm {

    const val PERSONA = "你是一个放在用户桌面上的陪伴机器人，名叫小宝。" +
        "说话口语化、温暖、有点俏皮，每次回复不超过 50 个字，不使用表情符号和 Markdown。"

    @Volatile private var instance: Pair<GatewaySettings, LlmClient>? = null

    private fun get(context: Context): Pair<GatewaySettings, LlmClient> = instance ?: synchronized(this) {
        instance ?: run {
            val http = OkHttpClient.Builder()
                .connectTimeout(10, TimeUnit.SECONDS)
                .readTimeout(60, TimeUnit.SECONDS)
                .build()
            val settings = GatewaySettings(context)
            val auth = GatewayAuth(settings, http)
            val client = OpenAiCompatibleLlmClient(http, { settings.baseUrl }, { force -> auth.accessToken(force) })
            (settings to client).also { instance = it }
        }
    }

    fun settings(context: Context) = get(context).first
    fun client(context: Context) = get(context).second
}
