package com.deskrobot.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Slider
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.deskrobot.Expression
import com.deskrobot.RobotBus
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** 调试面板：实时得分、帧率、手动切换表情、阈值调节 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun DebugPanel(
    onOpenBatterySettings: () -> Unit,
    onClose: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val p by RobotBus.perception.collectAsStateWithLifecycle()
    val expression by RobotBus.expression.collectAsStateWithLifecycle()
    val manual by RobotBus.manualExpression.collectAsStateWithLifecycle()
    val cameraOn by RobotBus.cameraEnabled.collectAsStateWithLifecycle()
    val t by RobotBus.thresholds.collectAsStateWithLifecycle()
    val lastSpeech by RobotBus.lastSpeech.collectAsStateWithLifecycle()
    val running by RobotBus.serviceRunning.collectAsStateWithLifecycle()
    val heartbeat by RobotBus.lastHeartbeat.collectAsStateWithLifecycle()
    val fmt = SimpleDateFormat("HH:mm:ss", Locale.getDefault())

    Column(
        modifier
            .background(Color(0xCC111111))
            .verticalScroll(rememberScrollState())
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        val line = { s: String -> @Composable { Text(s, color = Color.White, fontSize = 14.sp) } }
        line("服务: ${if (running) "运行中" else "未运行"}  心跳: ${if (heartbeat > 0) fmt.format(Date(heartbeat)) else "-"}")()
        line("人脸: ${if (p.faceVisible) "有" else "无"}  位置: (%.2f, %.2f)".format(p.faceX, p.faceY))()
        line("喜得分: %.2f   怒得分: %.2f".format(p.happyScore, p.angryScore))()
        line("单帧情绪: ${p.rawEmotion}   稳定情绪: ${p.stableEmotion}")()
        line("推理: ${p.inferenceMs} ms   帧率: %.1f fps   ${p.delegate}".format(p.fps))()
        line("当前表情: $expression")()
        line("最近播报: $lastSpeech")()

        line("喜阈值 %.2f".format(t.happy))()
        Slider(t.happy, { RobotBus.thresholds.value = t.copy(happy = it) }, valueRange = 0.1f..0.9f)
        line("怒阈值 %.2f".format(t.angry))()
        Slider(t.angry, { RobotBus.thresholds.value = t.copy(angry = it) }, valueRange = 0.1f..0.9f)

        Row(verticalAlignment = Alignment.CenterVertically) {
            line("摄像头 ")()
            Switch(cameraOn, { RobotBus.cameraEnabled.value = it })
            line("   手动锁定表情 ")()
            Switch(manual, { RobotBus.manualExpression.value = it })
        }
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Expression.entries.forEach { e ->
                OutlinedButton(onClick = {
                    RobotBus.manualExpression.value = true
                    RobotBus.expression.value = e
                }) { Text(e.name, color = Color.White) }
            }
        }
        LlmTestSection()
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Button(onClick = onOpenBatterySettings) { Text("忽略电池优化") }
            Button(onClick = onClose) { Text("关闭面板") }
        }
    }
}
