package com.deskrobot.ui

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import com.deskrobot.Expression
import kotlinx.coroutines.delay
import kotlin.random.Random

private val FaceColor = Color(0xFF39E0FF)

/** 用 Canvas 绘制的机器人脸：眨眼、眼睛跟随用户、说话时嘴巴开合 */
@Composable
fun RobotFace(
    expression: Expression,
    gazeX: Float,
    gazeY: Float,
    speaking: Boolean,
    modifier: Modifier = Modifier,
) {
    val blink = remember { Animatable(1f) }
    LaunchedEffect(expression) {
        if (expression == Expression.SLEEPING) return@LaunchedEffect
        while (true) {
            delay(Random.nextLong(2500, 6000))
            blink.animateTo(0.1f, tween(80))
            blink.animateTo(1f, tween(120))
        }
    }

    val lookX by animateFloatAsState(
        if (expression == Expression.THINKING) 0.6f else (gazeX - 0.5f) * 2f, tween(250), label = "lookX"
    )
    val lookY by animateFloatAsState(
        if (expression == Expression.THINKING) -0.6f else (gazeY - 0.5f) * 2f, tween(250), label = "lookY"
    )
    val eyeOpen by animateFloatAsState(
        when (expression) {
            Expression.SLEEPING -> 0.08f
            Expression.LISTENING -> 1.15f
            Expression.ANGRY_RESPONSE -> 0.8f
            Expression.SURPRISED -> 1.5f
            Expression.SAD -> 0.7f
            else -> 1f
        },
        tween(300), label = "eyeOpen"
    )
    val talk = rememberInfiniteTransition(label = "talk")
    val talkPhase by talk.animateFloat(
        0f, 1f, infiniteRepeatable(tween(160), RepeatMode.Reverse), label = "talkPhase"
    )
    val breathe by talk.animateFloat(
        0f, 1f, infiniteRepeatable(tween(2000), RepeatMode.Reverse), label = "breathe"
    )

    Canvas(
        modifier
            .background(Color.Black)
            .semantics { contentDescription = "机器人表情：${expression.name}" }
    ) {
        val unit = minOf(size.width, size.height)
        val eyeW = unit * 0.22f
        val eyeH = unit * 0.30f
        val gap = unit * 0.20f
        val cy = size.height * 0.42f + if (expression == Expression.SLEEPING) breathe * unit * 0.02f else 0f
        val shiftX = lookX.coerceIn(-1f, 1f) * unit * 0.06f
        val shiftY = lookY.coerceIn(-1f, 1f) * unit * 0.04f
        val leftCx = size.width / 2 - gap / 2 - eyeW / 2 + shiftX
        val rightCx = size.width / 2 + gap / 2 + eyeW / 2 + shiftX

        for ((i, cx) in listOf(leftCx, rightCx).withIndex()) {
            val center = Offset(cx, cy + shiftY)
            when {
                expression == Expression.HAPPY -> drawHappyEye(center, eyeW, eyeH)
                expression == Expression.LOVE -> drawHeartEye(center, eyeW, eyeH)
                else -> {
                    val h = eyeH * eyeOpen * blink.value
                    drawRoundRect(
                        FaceColor,
                        topLeft = Offset(center.x - eyeW / 2, center.y - h / 2),
                        size = Size(eyeW, h),
                        cornerRadius = CornerRadius(eyeW * 0.35f),
                    )
                }
            }
            when (expression) {
                Expression.ANGRY_RESPONSE -> {
                    // 关切的眉毛：内侧上扬
                    val dir = if (i == 0) 1f else -1f
                    val y = center.y - eyeH * 0.65f
                    drawLine(
                        FaceColor,
                        Offset(center.x - dir * eyeW * 0.5f, y + unit * 0.02f),
                        Offset(center.x + dir * eyeW * 0.5f, y - unit * 0.03f),
                        strokeWidth = unit * 0.025f, cap = StrokeCap.Round,
                    )
                }
                Expression.SAD -> {
                    // 八字眉：内侧下压
                    val dir = if (i == 0) 1f else -1f
                    val y = center.y - eyeH * 0.65f
                    drawLine(
                        FaceColor,
                        Offset(center.x - dir * eyeW * 0.5f, y - unit * 0.02f),
                        Offset(center.x + dir * eyeW * 0.5f, y + unit * 0.03f),
                        strokeWidth = unit * 0.025f, cap = StrokeCap.Round,
                    )
                }
                Expression.CONFUSED -> {
                    // 一条眉毛挑起（只画右眼）
                    if (i == 1) {
                        val y = center.y - eyeH * 0.7f
                        drawLine(
                            FaceColor,
                            Offset(center.x - eyeW * 0.45f, y + unit * 0.025f),
                            Offset(center.x + eyeW * 0.45f, y - unit * 0.025f),
                            strokeWidth = unit * 0.022f, cap = StrokeCap.Round,
                        )
                    }
                }
                else -> {}
            }
        }

        drawMouth(expression, speaking, talkPhase, unit, Offset(size.width / 2 + shiftX * 0.5f, size.height * 0.72f))
    }
}

private fun DrawScope.drawHappyEye(center: Offset, w: Float, h: Float) {
    // ^ 形笑眼
    val path = Path().apply {
        moveTo(center.x - w / 2, center.y + h * 0.15f)
        quadraticTo(center.x, center.y - h * 0.55f, center.x + w / 2, center.y + h * 0.15f)
    }
    drawPath(path, FaceColor, style = Stroke(width = w * 0.22f, cap = StrokeCap.Round))
}

private fun DrawScope.drawHeartEye(center: Offset, w: Float, h: Float) {
    // 实心爱心眼
    val s = minOf(w, h) * 0.4f
    val path = Path().apply {
        moveTo(center.x, center.y + s)
        cubicTo(center.x - 2 * s, center.y - s * 0.5f, center.x - s * 0.5f, center.y - 1.6f * s, center.x, center.y - s * 0.5f)
        cubicTo(center.x + s * 0.5f, center.y - 1.6f * s, center.x + 2 * s, center.y - s * 0.5f, center.x, center.y + s)
        close()
    }
    drawPath(path, FaceColor)
}

private fun DrawScope.drawMouth(e: Expression, speaking: Boolean, phase: Float, unit: Float, c: Offset) {
    val stroke = Stroke(width = unit * 0.03f, cap = StrokeCap.Round)
    if (speaking) {
        val h = unit * (0.02f + 0.06f * phase)
        drawOval(FaceColor, Offset(c.x - unit * 0.06f, c.y - h / 2), Size(unit * 0.12f, h))
        return
    }
    when (e) {
        Expression.HAPPY -> {
            val path = Path().apply {
                moveTo(c.x - unit * 0.14f, c.y - unit * 0.03f)
                quadraticTo(c.x, c.y + unit * 0.12f, c.x + unit * 0.14f, c.y - unit * 0.03f)
            }
            drawPath(path, FaceColor, style = stroke)
        }
        Expression.ANGRY_RESPONSE -> {
            val path = Path().apply {
                moveTo(c.x - unit * 0.07f, c.y + unit * 0.02f)
                quadraticTo(c.x, c.y - unit * 0.02f, c.x + unit * 0.07f, c.y + unit * 0.02f)
            }
            drawPath(path, FaceColor, style = stroke)
        }
        Expression.THINKING, Expression.LISTENING ->
            drawCircle(FaceColor, radius = unit * 0.025f, center = c, style = stroke)
        Expression.SLEEPING ->
            drawLine(FaceColor, Offset(c.x - unit * 0.04f, c.y), Offset(c.x + unit * 0.04f, c.y), unit * 0.02f, StrokeCap.Round)
        Expression.SURPRISED -> {
            // 小圆嘴
            drawOval(FaceColor, Offset(c.x - unit * 0.045f, c.y - unit * 0.05f), Size(unit * 0.09f, unit * 0.1f), style = stroke)
        }
        Expression.SAD -> {
            // 下弯的嘴
            val path = Path().apply {
                moveTo(c.x - unit * 0.1f, c.y + unit * 0.03f)
                quadraticTo(c.x, c.y - unit * 0.04f, c.x + unit * 0.1f, c.y + unit * 0.03f)
            }
            drawPath(path, FaceColor, style = stroke)
        }
        Expression.CONFUSED -> {
            // 波浪嘴
            val path = Path().apply {
                moveTo(c.x - unit * 0.1f, c.y)
                quadraticTo(c.x - unit * 0.05f, c.y - unit * 0.03f, c.x, c.y)
                quadraticTo(c.x + unit * 0.05f, c.y + unit * 0.03f, c.x + unit * 0.1f, c.y)
            }
            drawPath(path, FaceColor, style = stroke)
        }
        Expression.LOVE -> {
            // 微张的嘴 + 脸颊两团腮红
            drawOval(FaceColor.copy(alpha = 0.45f), Offset(c.x - unit * 0.34f, c.y - unit * 0.08f), Size(unit * 0.09f, unit * 0.05f))
            drawOval(FaceColor.copy(alpha = 0.45f), Offset(c.x + unit * 0.25f, c.y - unit * 0.08f), Size(unit * 0.09f, unit * 0.05f))
            drawOval(FaceColor, Offset(c.x - unit * 0.04f, c.y - unit * 0.03f), Size(unit * 0.08f, unit * 0.06f), style = stroke)
        }
        Expression.IDLE -> {
            val path = Path().apply {
                moveTo(c.x - unit * 0.08f, c.y)
                quadraticTo(c.x, c.y + unit * 0.05f, c.x + unit * 0.08f, c.y)
            }
            drawPath(path, FaceColor, style = stroke)
        }
    }
}
