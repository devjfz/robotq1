package com.deskrobot

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.SystemClock
import android.util.Log
import android.util.Size
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.resolutionselector.ResolutionSelector
import androidx.camera.core.resolutionselector.ResolutionStrategy
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import androidx.lifecycle.LifecycleService
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors

/**
 * 前台服务：持有摄像头、表情识别管线与 TTS，保证切到后台/锁屏后机器人仍在运行。
 */
class RobotService : LifecycleService() {

    private lateinit var speaker: Speaker
    private val smoother = EmotionSmoother()
    private val responder = EmotionResponder()
    private var analyzer: FaceAnalyzer? = null
    private var cameraProvider: ProcessCameraProvider? = null
    private val analysisExecutor: ExecutorService = Executors.newSingleThreadExecutor()

    private var fpsWindowStart = 0L
    private var fpsFrames = 0
    private var fps = 0f
    private var noFaceSince = SystemClock.uptimeMillis()

    override fun onCreate() {
        super.onCreate()
        startAsForeground()
        RobotBus.serviceRunning.value = true
        speaker = Speaker(this) { setAutoExpression(Expression.IDLE) }

        lifecycleScope.launch {
            RobotBus.cameraEnabled.collect { enabled -> if (enabled) bindCamera() else unbindCamera() }
        }
        lifecycleScope.launch {
            RobotBus.speakRequests.collect { text -> speaker.speak(text) }
        }
        lifecycleScope.launch {
            while (true) {
                RobotBus.lastHeartbeat.value = System.currentTimeMillis()
                Log.i(TAG, "heartbeat perception=${RobotBus.perception.value}")
                delay(HEARTBEAT_MS)
            }
        }
        lifecycleScope.launch {
            // 无人脸超过 60 秒进入睡眠表情，人脸回来后醒来
            while (true) {
                delay(1000)
                val idleMs = SystemClock.uptimeMillis() - noFaceSince
                val current = RobotBus.expression.value
                if (idleMs > SLEEP_AFTER_MS && current == Expression.IDLE) setAutoExpression(Expression.SLEEPING)
            }
        }
    }

    private fun startAsForeground() {
        val nm = getSystemService(NotificationManager::class.java)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            nm.createNotificationChannel(
                NotificationChannel(CHANNEL_ID, getString(R.string.notif_channel), NotificationManager.IMPORTANCE_LOW)
            )
        }
        val contentIntent = PendingIntent.getActivity(
            this, 0, Intent(this, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE
        )
        val notification: Notification = NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.ic_menu_camera)
            .setContentTitle(getString(R.string.app_name))
            .setContentText(getString(R.string.notif_text))
            .setContentIntent(contentIntent)
            .setOngoing(true)
            .build()
        val type = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) ServiceInfo.FOREGROUND_SERVICE_TYPE_CAMERA else 0
        ServiceCompat.startForeground(this, NOTIFICATION_ID, notification, type)
    }

    private fun bindCamera() {
        val future = ProcessCameraProvider.getInstance(this)
        future.addListener({
            try {
                val provider = future.get()
                cameraProvider = provider
                val fa = analyzer ?: FaceAnalyzer(this, ::onFaceFrame).also { analyzer = it }
                val analysis = ImageAnalysis.Builder()
                    .setResolutionSelector(
                        ResolutionSelector.Builder()
                            .setResolutionStrategy(
                                ResolutionStrategy(Size(640, 480), ResolutionStrategy.FALLBACK_RULE_CLOSEST_LOWER_THEN_HIGHER)
                            )
                            .build()
                    )
                    .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                    .setOutputImageFormat(ImageAnalysis.OUTPUT_IMAGE_FORMAT_RGBA_8888)
                    .build()
                    .also { it.setAnalyzer(analysisExecutor, fa) }
                provider.unbindAll()
                provider.bindToLifecycle(this, CameraSelector.DEFAULT_FRONT_CAMERA, analysis)
                RobotBus.perception.value = RobotBus.perception.value.copy(delegate = fa.delegateName)
                Log.i(TAG, "camera bound")
            } catch (e: Exception) {
                Log.e(TAG, "bind camera failed", e)
            }
        }, ContextCompat.getMainExecutor(this))
    }

    private fun unbindCamera() {
        cameraProvider?.unbindAll()
        smoother.reset()
        RobotBus.perception.value = PerceptionState()
        Log.i(TAG, "camera unbound")
    }

    /** 运行在 MediaPipe 回调线程 */
    private fun onFaceFrame(frame: FaceFrame) {
        updateFps(frame.timeMs)
        val t = RobotBus.thresholds.value
        val happy = EmotionClassifier.happyScore(frame.blendshapes)
        val angry = EmotionClassifier.angryScore(frame.blendshapes)
        val raw = if (frame.faceVisible) EmotionClassifier.classify(happy, angry, t) else Emotion.NEUTRAL
        val stable = smoother.add(frame.timeMs, raw)

        if (frame.faceVisible) {
            if (RobotBus.expression.value == Expression.SLEEPING) setAutoExpression(Expression.IDLE)
            noFaceSince = SystemClock.uptimeMillis()
        }

        RobotBus.perception.value = PerceptionState(
            faceVisible = frame.faceVisible,
            faceX = frame.faceX,
            faceY = frame.faceY,
            happyScore = happy,
            angryScore = angry,
            rawEmotion = raw,
            stableEmotion = stable,
            inferenceMs = frame.inferenceMs,
            fps = fps,
            delegate = analyzer?.delegateName.orEmpty(),
        )

        val response = responder.onStableEmotion(stable, frame.timeMs) ?: return
        Log.i(TAG, "emotion response: $stable -> ${response.text}")
        setAutoExpression(response.expression)
        speaker.speak(response.text)
    }

    private fun updateFps(now: Long) {
        if (fpsWindowStart == 0L) fpsWindowStart = now
        fpsFrames++
        val elapsed = now - fpsWindowStart
        if (elapsed >= 1000) {
            fps = fpsFrames * 1000f / elapsed
            fpsFrames = 0
            fpsWindowStart = now
        }
    }

    private fun setAutoExpression(e: Expression) {
        if (!RobotBus.manualExpression.value) RobotBus.expression.value = e
    }

    override fun onDestroy() {
        RobotBus.serviceRunning.value = false
        cameraProvider?.unbindAll()
        analysisExecutor.shutdown()
        analyzer?.close()
        speaker.shutdown()
        super.onDestroy()
    }

    companion object {
        private const val TAG = "RobotService"
        private const val CHANNEL_ID = "robot_running"
        private const val NOTIFICATION_ID = 1
        private const val HEARTBEAT_MS = 60_000L
        private const val SLEEP_AFTER_MS = 60_000L

        fun start(context: Context) {
            ContextCompat.startForegroundService(context, Intent(context, RobotService::class.java))
        }
    }
}
