package com.deskrobot

import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow

/** 机器人屏幕上显示的表情 */
enum class Expression {
    IDLE, HAPPY, ANGRY_RESPONSE, LISTENING, THINKING, SLEEPING,
    SURPRISED, SAD, CONFUSED, LOVE,
}

/** 识别出的用户情绪 */
enum class Emotion { NEUTRAL, HAPPY, ANGRY }

data class PerceptionState(
    val faceVisible: Boolean = false,
    /** 人脸中心在画面中的位置，0..1，已按镜像修正（0 = 用户在屏幕左侧） */
    val faceX: Float = 0.5f,
    val faceY: Float = 0.5f,
    val happyScore: Float = 0f,
    val angryScore: Float = 0f,
    val rawEmotion: Emotion = Emotion.NEUTRAL,
    val stableEmotion: Emotion = Emotion.NEUTRAL,
    val inferenceMs: Long = 0,
    val fps: Float = 0f,
    val delegate: String = "",
)

/** Service 与 UI 之间共享的状态（单进程内的简单总线） */
object RobotBus {
    val expression = MutableStateFlow(Expression.IDLE)
    val perception = MutableStateFlow(PerceptionState())
    val speaking = MutableStateFlow(false)
    val lastSpeech = MutableStateFlow("")
    val serviceRunning = MutableStateFlow(false)
    val lastHeartbeat = MutableStateFlow(0L)
    val cameraEnabled = MutableStateFlow(true)
    val thresholds = MutableStateFlow(EmotionThresholds())
    /** 调试面板手动锁定表情时为 true，自动逻辑不再覆盖 */
    val manualExpression = MutableStateFlow(false)
    /** UI 或其他模块请求机器人说话，由 RobotService 统一播报 */
    val speakRequests = MutableSharedFlow<String>(extraBufferCapacity = 4)
}
