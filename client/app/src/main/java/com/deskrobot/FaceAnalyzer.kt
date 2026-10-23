package com.deskrobot

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Matrix
import android.os.SystemClock
import android.util.Log
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.ImageProxy
import com.google.mediapipe.framework.image.BitmapImageBuilder
import com.google.mediapipe.tasks.core.BaseOptions
import com.google.mediapipe.tasks.core.Delegate
import com.google.mediapipe.tasks.vision.core.RunningMode
import com.google.mediapipe.tasks.vision.facelandmarker.FaceLandmarker
import com.google.mediapipe.tasks.vision.facelandmarker.FaceLandmarkerResult

/** 一帧人脸分析结果 */
data class FaceFrame(
    val timeMs: Long,
    val faceVisible: Boolean,
    val faceX: Float,
    val faceY: Float,
    val blendshapes: Map<String, Float>,
    val inferenceMs: Long,
)

/**
 * CameraX 分析器 + MediaPipe FaceLandmarker（LIVE_STREAM）。
 * 有人脸时约 10 fps；连续 [idleAfterMs] 无人脸后降为 2 fps 以省电。
 */
class FaceAnalyzer(
    context: Context,
    private val onFrame: (FaceFrame) -> Unit,
) : ImageAnalysis.Analyzer {

    private val landmarker: FaceLandmarker
    val delegateName: String

    private var lastSentAt = 0L
    private var lastFaceAt = SystemClock.uptimeMillis()

    init {
        var created: Pair<FaceLandmarker, String>? = null
        for (delegate in listOf(Delegate.GPU, Delegate.CPU)) {
            try {
                created = create(context, delegate) to delegate.name
                break
            } catch (e: Exception) {
                Log.w(TAG, "FaceLandmarker init with $delegate failed", e)
            }
        }
        val (lm, name) = created ?: error("FaceLandmarker init failed on all delegates")
        landmarker = lm
        delegateName = name
        Log.i(TAG, "FaceLandmarker ready, delegate=$name")
    }

    private fun create(context: Context, delegate: Delegate): FaceLandmarker {
        val base = BaseOptions.builder()
            .setModelAssetPath(MODEL)
            .setDelegate(delegate)
            .build()
        val options = FaceLandmarker.FaceLandmarkerOptions.builder()
            .setBaseOptions(base)
            .setRunningMode(RunningMode.LIVE_STREAM)
            .setNumFaces(1)
            .setOutputFaceBlendshapes(true)
            .setResultListener { result, _ -> handleResult(result) }
            .setErrorListener { e -> Log.e(TAG, "FaceLandmarker error", e) }
            .build()
        return FaceLandmarker.createFromOptions(context, options)
    }

    override fun analyze(image: ImageProxy) {
        image.use { proxy ->
            val now = SystemClock.uptimeMillis()
            val interval = if (now - lastFaceAt > IDLE_AFTER_MS) IDLE_INTERVAL_MS else ACTIVE_INTERVAL_MS
            if (now - lastSentAt < interval) return
            lastSentAt = now

            val bitmap = uprightMirrored(proxy.toBitmap(), proxy.imageInfo.rotationDegrees)
            landmarker.detectAsync(BitmapImageBuilder(bitmap).build(), now)
        }
    }

    private fun handleResult(result: FaceLandmarkerResult) {
        val now = SystemClock.uptimeMillis()
        val landmarks = result.faceLandmarks()
        val visible = landmarks.isNotEmpty()
        if (visible) lastFaceAt = now

        // 1 号关键点为鼻尖，用作人脸中心
        val nose = landmarks.firstOrNull()?.getOrNull(1)
        val blend = result.faceBlendshapes().orElse(emptyList()).firstOrNull()
            ?.associate { it.categoryName() to it.score() }
            .orEmpty()

        onFrame(
            FaceFrame(
                timeMs = now,
                faceVisible = visible,
                faceX = nose?.x() ?: 0.5f,
                faceY = nose?.y() ?: 0.5f,
                blendshapes = blend,
                inferenceMs = now - result.timestampMs(),
            )
        )
    }

    /** 旋转到正向并水平镜像，使坐标与用户看到的屏幕方向一致 */
    private fun uprightMirrored(src: Bitmap, rotation: Int): Bitmap {
        val m = Matrix().apply {
            postRotate(rotation.toFloat())
            postScale(-1f, 1f)
        }
        return Bitmap.createBitmap(src, 0, 0, src.width, src.height, m, false)
    }

    fun close() = landmarker.close()

    companion object {
        private const val TAG = "FaceAnalyzer"
        const val MODEL = "face_landmarker.task"
        private const val ACTIVE_INTERVAL_MS = 100L
        private const val IDLE_INTERVAL_MS = 500L
        private const val IDLE_AFTER_MS = 10_000L
    }
}
