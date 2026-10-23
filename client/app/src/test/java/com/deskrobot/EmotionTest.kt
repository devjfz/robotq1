package com.deskrobot

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test
import kotlin.random.Random

class EmotionTest {

    private val t = EmotionThresholds()

    @Test
    fun smileIsHappy() {
        val b = mapOf("mouthSmileLeft" to 0.9f, "mouthSmileRight" to 0.9f, "cheekSquintLeft" to 0.5f, "cheekSquintRight" to 0.5f)
        assertEquals(Emotion.HAPPY, EmotionClassifier.classify(EmotionClassifier.happyScore(b), EmotionClassifier.angryScore(b), t))
    }

    @Test
    fun frownIsAngry() {
        val b = mapOf(
            "browDownLeft" to 0.8f, "browDownRight" to 0.8f,
            "mouthPressLeft" to 0.5f, "mouthPressRight" to 0.5f,
            "noseSneerLeft" to 0.3f, "noseSneerRight" to 0.3f,
        )
        assertEquals(Emotion.ANGRY, EmotionClassifier.classify(EmotionClassifier.happyScore(b), EmotionClassifier.angryScore(b), t))
    }

    @Test
    fun emptyIsNeutral() {
        assertEquals(Emotion.NEUTRAL, EmotionClassifier.classify(0f, 0f, t))
    }

    @Test
    fun smootherIgnoresFlicker() {
        val s = EmotionSmoother()
        // 10 fps，1 秒内只有 2 帧笑 -> 保持中性
        for (i in 0 until 10) s.add(i * 100L, if (i % 5 == 0) Emotion.HAPPY else Emotion.NEUTRAL)
        assertEquals(Emotion.NEUTRAL, s.stable)
    }

    @Test
    fun smootherSwitchesOnSustainedEmotion() {
        val s = EmotionSmoother()
        for (i in 0 until 10) s.add(i * 100L, Emotion.NEUTRAL)
        for (i in 10 until 20) s.add(i * 100L, Emotion.HAPPY)
        assertEquals(Emotion.HAPPY, s.stable)
    }

    @Test
    fun responderOnlyFiresOnTransition() {
        val r = EmotionResponder(random = Random(1))
        assertNotNull(r.onStableEmotion(Emotion.HAPPY, 0))
        // 持续笑 1 分钟，不再重复反馈
        for (ms in 100L..60_000L step 100) assertNull(r.onStableEmotion(Emotion.HAPPY, ms))
    }

    @Test
    fun responderRespectsCooldowns() {
        val r = EmotionResponder(random = Random(1))
        assertNotNull(r.onStableEmotion(Emotion.HAPPY, 0))
        r.onStableEmotion(Emotion.NEUTRAL, 1_000)
        assertNull("全局冷却 10 秒", r.onStableEmotion(Emotion.ANGRY, 5_000))
        r.onStableEmotion(Emotion.NEUTRAL, 6_000)
        assertNull("同情绪冷却 30 秒", r.onStableEmotion(Emotion.HAPPY, 20_000))
        r.onStableEmotion(Emotion.NEUTRAL, 21_000)
        assertNotNull(r.onStableEmotion(Emotion.ANGRY, 22_000))
        r.onStableEmotion(Emotion.NEUTRAL, 33_000)
        assertNotNull(r.onStableEmotion(Emotion.HAPPY, 35_000))
    }

    @Test
    fun responderAvoidsRecentLines() {
        val r = EmotionResponder(sameCooldownMs = 0, anyCooldownMs = 0, random = Random(7))
        val said = mutableListOf<String>()
        repeat(20) { i ->
            r.onStableEmotion(Emotion.NEUTRAL, i * 2L)
            said += r.onStableEmotion(Emotion.HAPPY, i * 2L + 1)!!.text
        }
        said.windowed(4).forEach { w -> assertEquals(4, w.toSet().size) }
    }
}
