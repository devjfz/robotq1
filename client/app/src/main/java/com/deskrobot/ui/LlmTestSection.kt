package com.deskrobot.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.Button
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.deskrobot.Expression
import com.deskrobot.RobotBus
import com.deskrobot.llm.ChatMessage
import com.deskrobot.llm.LlmEvent
import com.deskrobot.llm.RobotLlm
import kotlinx.coroutines.launch

/** 调试面板中的网关配置与大模型连通性测试 */
@Composable
fun LlmTestSection() {
    val context = LocalContext.current
    val settings = remember { RobotLlm.settings(context) }
    val scope = rememberCoroutineScope()
    var baseUrl by remember { mutableStateOf(settings.baseUrl) }
    var deviceId by remember { mutableStateOf(settings.deviceId) }
    var code by remember { mutableStateOf(settings.activationCode) }
    var prompt by remember { mutableStateOf("用一句话介绍一下你自己") }
    var reply by remember { mutableStateOf("") }
    var busy by remember { mutableStateOf(false) }

    Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Text("大模型网关", color = Color.White, fontSize = 16.sp)
        OutlinedTextField(baseUrl, { baseUrl = it }, Modifier.fillMaxWidth(), label = { Text("网关地址，如 http://192.168.1.10:8787") }, singleLine = true)
        OutlinedTextField(deviceId, { deviceId = it }, Modifier.fillMaxWidth(), label = { Text("设备 ID") }, singleLine = true)
        OutlinedTextField(
            code, { code = it }, Modifier.fillMaxWidth(), label = { Text("激活码") }, singleLine = true,
            visualTransformation = PasswordVisualTransformation(),
        )
        OutlinedTextField(prompt, { prompt = it }, Modifier.fillMaxWidth(), label = { Text("测试问题") })
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Button(onClick = {
                val changed = settings.deviceId != deviceId.trim() || settings.activationCode != code.trim()
                settings.baseUrl = baseUrl
                settings.deviceId = deviceId
                settings.activationCode = code
                if (changed) settings.clearTokens()
                reply = "已保存"
            }) { Text("保存") }
            Button(enabled = !busy, onClick = {
                busy = true
                reply = ""
                RobotBus.expression.value = Expression.THINKING
                scope.launch {
                    val started = System.currentTimeMillis()
                    var firstTokenMs = -1L
                    val text = StringBuilder()
                    try {
                        RobotLlm.client(context)
                            .chat(listOf(ChatMessage.system(RobotLlm.PERSONA), ChatMessage.user(prompt)))
                            .collect { e ->
                                if (e is LlmEvent.Delta) {
                                    if (firstTokenMs < 0) firstTokenMs = System.currentTimeMillis() - started
                                    text.append(e.text)
                                    reply = text.toString()
                                }
                            }
                        reply = "$text\n（首字 ${firstTokenMs} ms，总耗时 ${System.currentTimeMillis() - started} ms）"
                        RobotBus.speakRequests.tryEmit(text.toString())
                    } catch (e: Exception) {
                        reply = "失败：${e.message}"
                        RobotBus.expression.value = Expression.IDLE
                    } finally {
                        busy = false
                    }
                }
            }) { Text(if (busy) "请求中…" else "测试大模型") }
        }
        if (reply.isNotEmpty()) Text(reply, color = Color.White, fontSize = 14.sp)
    }
}
