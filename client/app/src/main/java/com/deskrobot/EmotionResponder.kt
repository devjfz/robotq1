package com.deskrobot

import kotlin.random.Random

data class EmotionResponse(val expression: Expression, val text: String)

/**
 * 决定何时对稳定情绪做语音反馈：
 * - 只在稳定情绪"跳变"到喜/怒时触发
 * - 同一情绪冷却 [sameCooldownMs]，任意反馈冷却 [anyCooldownMs]
 * - 话术随机且不与最近 3 条重复
 */
class EmotionResponder(
    private val sameCooldownMs: Long = 30_000,
    private val anyCooldownMs: Long = 10_000,
    private val random: Random = Random.Default,
) {
    private var lastEmotion = Emotion.NEUTRAL
    private var lastAnyAt = Long.MIN_VALUE / 2
    private val lastAt = mutableMapOf<Emotion, Long>()
    private val recent = ArrayDeque<String>()

    fun onStableEmotion(emotion: Emotion, nowMs: Long): EmotionResponse? {
        val changed = emotion != lastEmotion
        lastEmotion = emotion
        if (!changed || emotion == Emotion.NEUTRAL) return null
        if (nowMs - lastAnyAt < anyCooldownMs) return null
        if (nowMs - (lastAt[emotion] ?: Long.MIN_VALUE / 2) < sameCooldownMs) return null

        lastAnyAt = nowMs
        lastAt[emotion] = nowMs
        return when (emotion) {
            Emotion.HAPPY -> EmotionResponse(Expression.HAPPY, pick(HAPPY_LINES))
            Emotion.ANGRY -> EmotionResponse(Expression.ANGRY_RESPONSE, pick(ANGRY_LINES))
            Emotion.NEUTRAL -> null
        }
    }

    private fun pick(lines: List<String>): String {
        val candidates = lines.filter { it !in recent }.ifEmpty { lines }
        val line = candidates[random.nextInt(candidates.size)]
        recent.addLast(line)
        if (recent.size > 3) recent.removeFirst()
        return line
    }

    companion object {
        val HAPPY_LINES = listOf(
            "看到你笑，我也好开心！",
            "哇，今天心情不错嘛！",
            "你笑起来真好看！",
            "有什么开心的事，跟我分享一下吧？",
            "嘿嘿，你一笑我就想跟着笑。",
            "好心情会传染哦，我也被感染啦！",
            "保持这个笑容，今天一定很顺利！",
            "你开心，我就开心！",
            "这个笑容我给满分！",
            "哈哈，是遇到什么好事了吗？",
        )
        val ANGRY_LINES = listOf(
            "怎么啦？要不要跟我说说？",
            "看起来有点不开心，我陪着你。",
            "别生气啦，深呼吸，慢慢来。",
            "遇到烦心事了吗？我在这儿听你说。",
            "先喝口水，放松一下好不好？",
            "生气对身体不好哦，我给你讲个笑话吧？",
            "皱眉头会长皱纹的，笑一个嘛。",
            "不管发生什么，我都站在你这边。",
            "要不休息一下，换换心情？",
            "我能帮你做点什么吗？",
        )
    }
}
