package com.deskrobot

import java.util.ArrayDeque

data class EmotionThresholds(
    val happy: Float = 0.5f,
    val angry: Float = 0.4f,
    /** 判定为"怒"时，喜得分必须低于该值，避免大笑时误判 */
    val angryMaxHappy: Float = 0.2f,
)

/** 基于 MediaPipe 52 个 blendshape 的规则版喜/怒分类器（P1 v1） */
object EmotionClassifier {

    fun happyScore(b: Map<String, Float>): Float =
        avg(b, "mouthSmileLeft", "mouthSmileRight") * 0.7f +
            avg(b, "cheekSquintLeft", "cheekSquintRight") * 0.3f

    fun angryScore(b: Map<String, Float>): Float =
        avg(b, "browDownLeft", "browDownRight") * 0.5f +
            avg(b, "mouthPressLeft", "mouthPressRight") * 0.25f +
            avg(b, "noseSneerLeft", "noseSneerRight") * 0.25f

    fun classify(happy: Float, angry: Float, t: EmotionThresholds): Emotion = when {
        happy > t.happy -> Emotion.HAPPY
        angry > t.angry && happy < t.angryMaxHappy -> Emotion.ANGRY
        else -> Emotion.NEUTRAL
    }

    private fun avg(b: Map<String, Float>, l: String, r: String) =
        ((b[l] ?: 0f) + (b[r] ?: 0f)) / 2f
}

/**
 * 时间窗口平滑：窗口内同一情绪占比 >= [ratio] 且帧数足够才切换稳定情绪，否则保持上一个稳定值。
 */
class EmotionSmoother(
    private val windowMs: Long = 1000,
    private val ratio: Float = 0.7f,
    private val minFrames: Int = 5,
) {
    private val frames = ArrayDeque<Pair<Long, Emotion>>()
    var stable: Emotion = Emotion.NEUTRAL
        private set

    fun add(timeMs: Long, emotion: Emotion): Emotion {
        frames.addLast(timeMs to emotion)
        while (frames.isNotEmpty() && timeMs - frames.first().first > windowMs) frames.removeFirst()
        if (frames.size >= minFrames) {
            val dominant = frames.groupingBy { it.second }.eachCount().maxBy { it.value }
            if (dominant.value.toFloat() / frames.size >= ratio) stable = dominant.key
        }
        return stable
    }

    fun reset() {
        frames.clear()
        stable = Emotion.NEUTRAL
    }
}
