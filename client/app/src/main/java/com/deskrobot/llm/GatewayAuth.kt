package com.deskrobot.llm

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject

/** 网关连接配置，保存在应用私有存储中 */
class GatewaySettings(context: Context) {
    private val prefs = context.applicationContext.getSharedPreferences("gateway", Context.MODE_PRIVATE)

    var baseUrl: String
        get() = prefs.getString("baseUrl", "").orEmpty().trimEnd('/')
        set(v) = prefs.edit().putString("baseUrl", v.trim()).apply()
    var deviceId: String
        get() = prefs.getString("deviceId", "").orEmpty()
        set(v) = prefs.edit().putString("deviceId", v.trim()).apply()
    var activationCode: String
        get() = prefs.getString("activationCode", "").orEmpty()
        set(v) = prefs.edit().putString("activationCode", v.trim()).apply()
    var refreshToken: String
        get() = prefs.getString("refreshToken", "").orEmpty()
        set(v) = prefs.edit().putString("refreshToken", v).apply()
    var refreshExpiresAt: Long
        get() = prefs.getLong("refreshExpiresAt", 0)
        set(v) = prefs.edit().putLong("refreshExpiresAt", v).apply()

    val isConfigured get() = baseUrl.isNotEmpty() && deviceId.isNotEmpty() && activationCode.isNotEmpty()

    fun clearTokens() {
        refreshToken = ""
        refreshExpiresAt = 0
    }
}

/**
 * 向网关换取短期访问令牌：优先用刷新令牌续期，失败再用设备 ID + 激活码重新认证。
 * 访问令牌只放内存，过期前 60 秒自动续期。
 */
class GatewayAuth(
    private val settings: GatewaySettings,
    private val http: OkHttpClient,
    private val now: () -> Long = System::currentTimeMillis,
) {
    private val mutex = Mutex()
    private var accessToken: String? = null
    private var accessExpiresAt = 0L

    suspend fun accessToken(forceRenew: Boolean = false): String = mutex.withLock {
        if (!forceRenew && accessToken != null && now() < accessExpiresAt - 60_000) return accessToken!!
        if (!settings.isConfigured) throw LlmException("网关未配置（地址 / 设备 ID / 激活码）")

        val viaRefresh = settings.refreshToken.isNotEmpty() && now() < settings.refreshExpiresAt - 60_000
        val result = if (viaRefresh) {
            post("/v1/auth/refresh", JSONObject().put("refreshToken", settings.refreshToken))
                .takeIf { it.first == 200 } ?: authByDevice()
        } else {
            authByDevice()
        }
        val (status, json) = result
        if (status != 200) {
            settings.clearTokens()
            val code = json?.optJSONObject("error")?.optString("code")
            throw LlmException("网关认证失败: ${code ?: status}", status)
        }
        json!!
        accessToken = json.getString("accessToken")
        accessExpiresAt = now() + json.getLong("accessExpiresIn") * 1000
        settings.refreshToken = json.getString("refreshToken")
        settings.refreshExpiresAt = now() + json.getLong("refreshExpiresIn") * 1000
        accessToken!!
    }

    private suspend fun authByDevice() = post(
        "/v1/auth/device",
        JSONObject().put("deviceId", settings.deviceId).put("activationCode", settings.activationCode),
    )

    private suspend fun post(path: String, body: JSONObject): Pair<Int, JSONObject?> = withContext(Dispatchers.IO) {
        val req = Request.Builder()
            .url(settings.baseUrl + path)
            .post(body.toString().toRequestBody(JSON))
            .build()
        try {
            http.newCall(req).execute().use { resp ->
                val text = resp.body?.string().orEmpty()
                resp.code to runCatching { JSONObject(text) }.getOrNull()
            }
        } catch (e: Exception) {
            throw LlmException("无法连接网关: ${e.message}", cause = e)
        }
    }

    companion object {
        val JSON = "application/json; charset=utf-8".toMediaType()
    }
}
