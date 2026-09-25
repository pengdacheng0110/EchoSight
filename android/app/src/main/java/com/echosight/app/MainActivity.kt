package com.echosight.app

import android.Manifest
import android.content.pm.PackageManager
import android.content.res.ColorStateList
import android.media.AudioAttributes
import android.media.MediaPlayer
import android.os.Bundle
import android.view.HapticFeedbackConstants
import android.view.MotionEvent
import android.view.View
import android.widget.ImageView
import android.widget.TextView
import androidx.activity.result.ActivityResultLauncher
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.ImageProxy
import androidx.camera.core.Preview
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import com.google.android.material.button.MaterialButton
import com.google.android.material.card.MaterialCardView
import com.google.android.material.color.DynamicColors
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.util.concurrent.Executors

class MainActivity : AppCompatActivity() {

    private lateinit var previewView: PreviewView
    private lateinit var overlay: OverlayView
    private lateinit var statusCard: MaterialCardView
    private lateinit var statusDot: View
    private lateinit var statusIcon: ImageView
    private lateinit var statusText: TextView
    private lateinit var statusSub: TextView
    private lateinit var bearing: BearingView
    private lateinit var pushButton: MaterialButton

    private lateinit var detector: YoloDetector
    private val api = VoiceApi(BuildConfig.SENSEAUDIO_KEY)
    private val ptt = PushToTalk()
    private val ttsExecutor = Executors.newSingleThreadExecutor()
    private val analysisExecutor = Executors.newSingleThreadExecutor()

    // ---------------- 目标状态 ----------------
    @Volatile private var targetId: Int? = null
    @Volatile private var standby = false
    private var searchStart = 0L
    private var lastLosePrompt = 0L
    private var lastFoundReport = 0L
    private var ttsCounter = 0

    // 用于进展播报与丢失记忆
    private var lastSpokenDist: Float? = null
    @Volatile private var lastSeenHit: Guidance.Hit? = null
    private var lastSeenTime = 0L

    // 测距时序滤波：跨帧复用，切换目标时重置
    private val tracker = DistanceTracker()

    // 相机俯仰角（重力传感器），供地面法测距使用
    private lateinit var tilt: CameraTilt

    private val permissionLauncher: ActivityResultLauncher<Array<String>> =
        registerForActivityResult(
            ActivityResultContracts.RequestMultiplePermissions()) { result ->
        if (result[Manifest.permission.CAMERA] == true &&
            result[Manifest.permission.RECORD_AUDIO] == true) {
            startEverything()
        } else {
            updateHud(getString(R.string.status_need_permission), "", UiState.ERROR)
            // updateHud 会清掉卡片点击监听，这里补回来，让用户能点卡片重新授权
            statusCard.setOnClickListener { requestPermissions() }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        DynamicColors.applyToActivityIfAvailable(this)
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)
        window.addFlags(android.view.WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)

        previewView = findViewById(R.id.previewView)
        overlay = findViewById(R.id.overlayView)
        statusCard = findViewById(R.id.statusCard)
        statusDot = findViewById(R.id.statusDot)
        statusIcon = findViewById(R.id.statusIcon)
        statusText = findViewById(R.id.statusText)
        statusSub = findViewById(R.id.statusSub)
        bearing = findViewById(R.id.bearingView)
        pushButton = findViewById(R.id.pushButton)

        if (BuildConfig.SENSEAUDIO_KEY.isBlank()) {
            updateHud(getString(R.string.status_no_key), "", UiState.ERROR)
        }

        if (hasPermissions()) startEverything() else requestPermissions()
    }

    private fun hasPermissions() =
        ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA) ==
            PackageManager.PERMISSION_GRANTED &&
        ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) ==
            PackageManager.PERMISSION_GRANTED

    private fun requestPermissions() {
        permissionLauncher.launch(
            arrayOf(Manifest.permission.CAMERA, Manifest.permission.RECORD_AUDIO))
    }

    private fun startEverything() {
        detector = YoloDetector(this)
        tilt = CameraTilt(this)
        tilt.start()
        bindCamera()
        bindPushButton()
        speak("回声视见已启动。请问你要寻找什么物品？请按住屏幕下方的大按钮，对着手机说话，说完松手。")
    }

    // ---------------- 相机 ----------------
    private fun bindCamera() {
        val providerFuture = ProcessCameraProvider.getInstance(this)
        providerFuture.addListener({
            val provider = providerFuture.get()
            val preview = Preview.Builder().build().also {
                it.surfaceProvider = previewView.surfaceProvider
            }
            val analysis = ImageAnalysis.Builder()
                .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                .setOutputImageFormat(ImageAnalysis.OUTPUT_IMAGE_FORMAT_YUV_420_888)
                .build()
            analysis.setAnalyzer(analysisExecutor) { image ->
                try {
                    processFrame(image)
                } finally {
                    image.close()
                }
            }
            provider.unbindAll()
            provider.bindToLifecycle(
                this, CameraSelector.DEFAULT_BACK_CAMERA, preview, analysis)
        }, ContextCompat.getMainExecutor(this))
    }

    private fun processFrame(image: ImageProxy) {
        val boxes = detector.detect(image)
        overlay.frameWidth = detector.frameWidth
        overlay.frameHeight = detector.frameHeight

        val tid = targetId
        val now = System.currentTimeMillis()

        if (tid == null) {
            overlay.drawBoxes = emptyList()
            frameHud("等待指令：按住下方大按钮说话",
                getString(R.string.sub_no_target), UiState.IDLE)
            return
        }
        val frameW = detector.frameWidth.toFloat()
        val frameH = detector.frameHeight.toFloat()

        val hits = boxes.filter { it.cls == tid }.map {
            Guidance.buildHit(it, frameW, frameH, tid, tilt.depressionDeg)
        }
        val cn = Labels.CLASS_CN[tid]

        // 待机：只画不播
        if (standby) {
            overlay.drawBoxes = hits.map {
                DrawBox(it.box, "${cn} ${it.direction}")
            }
            frameHud("已找到${cn}，安静待命中",
                getString(R.string.sub_standby), UiState.STANDBY)
            return
        }

        if (hits.isNotEmpty()) {
            searchStart = now
            // 面积最大的框视为最近目标；只对它做时序平滑，否则同一画面里
            // 多个同类目标（比如三把椅子）的距离会被混进同一个滤波器。
            val nearIdx = hits.indices.maxByOrNull {
                (hits[it].box.y2 - hits[it].box.y1) *
                    (hits[it].box.x2 - hits[it].box.x1)
            } ?: 0
            val nearest = tracker.smooth(tid, hits[nearIdx], now)
            val shown = hits.mapIndexed { i, h -> if (i == nearIdx) nearest else h }
            lastSeenHit = nearest
            lastSeenTime = now

            overlay.drawBoxes = shown.mapIndexed { i, h ->
                DrawBox(h.box,
                    "${cn} ${h.direction} ${"%.1f".format(h.dist)}米",
                    i == nearIdx)
            }
            frameHud(
                "找到${hits.size.let { if (it > 1) "${it}个" else "" }}$cn：" +
                    "${nearest.direction}·${nearest.vertical} " +
                    "${"%.1f".format(nearest.dist)}米",
                getString(R.string.sub_distance,
                    "%.1f".format(nearest.dist), sourceLabel(nearest)),
                UiState.FOUND,
                nearest.angleDeg)

            if (now - lastFoundReport > FOUND_REPORT_INTERVAL) {
                val prev = lastSpokenDist
                val phrase = if (prev == null || now - lastFoundReport >
                        FOUND_REPORT_INTERVAL * 1.6f) {
                    Guidance.fullReport(cn, nearest, hits.size)
                } else {
                    Guidance.briefReport(cn, nearest, nearest.dist - prev)
                }
                lastSpokenDist = nearest.dist
                speak(phrase)
                lastFoundReport = now
            }
        } else {
            overlay.drawBoxes = emptyList()
            val age = now - lastSeenTime
            frameHud("寻找$cn 中…",
                getString(R.string.sub_searching), UiState.SEARCHING)
            if (now - searchStart > 4000 &&
                now - lastLosePrompt > LOSE_PROMPT_INTERVAL) {
                speak(Guidance.lostReport(cn, lastSeenHit, age))
                lastLosePrompt = now
            }
        }
    }

    // ---------------- 按住说话 ----------------
    private fun bindPushButton() {
        pushButton.setOnTouchListener { v: View, event: MotionEvent ->
            when (event.action) {
                MotionEvent.ACTION_DOWN -> {
                    v.performHapticFeedback(HapticFeedbackConstants.VIRTUAL_KEY)
                    stopSpeaking()
                    ptt.start()
                    setRecordingUi(true)
                    true
                }
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                    v.performHapticFeedback(HapticFeedbackConstants.VIRTUAL_KEY)
                    val wav = ptt.stop()
                    setRecordingUi(false)
                    if (event.action == MotionEvent.ACTION_UP && wav.size > 1000) {
                        sendForAsr(wav)
                    } else if (event.action == MotionEvent.ACTION_UP) {
                        updateHud("说话时间太短了，请按住按钮多说一会儿。",
                            getString(R.string.sub_searching), UiState.SEARCHING,
                            holdMs = 2500L)
                        speak("说话时间太短了，请按住按钮多说一会儿。")
                    }
                    true
                }
                else -> false
            }
        }
    }

    /**
     * 录音态的视觉反馈：按钮变红 + 文案切换，状态卡片同步进入"聆听"状态。
     * （原先 [R.color.mic_button_recording] 定义了却从未被使用，按钮录音时不会变色。）
     */
    private fun setRecordingUi(recording: Boolean) {
        pushButton.setText(if (recording) R.string.btn_talk_listening else R.string.btn_talk)
        pushButton.backgroundTintList = ColorStateList.valueOf(
            ContextCompat.getColor(this,
                if (recording) R.color.mic_button_recording else R.color.mic_button))
        if (recording) {
            updateHud("正在聆听，请说话", getString(R.string.sub_recognizing),
                UiState.LISTENING, holdMs = 30_000L)
        }
        // 松手后的状态交给 sendForAsr 或"说话太短"分支，它们各自带占位时间
    }

    private fun sendForAsr(wav: ByteArray) {
        updateHud("正在识别，请稍等…", getString(R.string.sub_recognizing),
            UiState.SEARCHING, holdMs = 2500L)
        lifecycleScope.launch {
            val text = withContext(Dispatchers.IO) { api.transcribe(wav) }
            when (val cmd = Labels.parseCommand(text)) {
                is Labels.Command.Target -> switchTarget(cmd.classId)
                Labels.Command.Found -> {
                    standby = true
                    speak("好的，我先安静待命。需要找别的东西时，按住按钮说，找，加上物品名字。")
                }
                null -> {
                    if (text.isBlank()) speak("没有听清，请按住按钮，靠近手机再说一次。")
                    else speak("没有听懂“$text”。请按住按钮说，找，加上物品名字，比如找杯子。")
                }
            }
        }
    }

    /** 完整的目标切换处理。 */
    private fun switchTarget(newId: Int) {
        val cn = Labels.CLASS_CN[newId]
        val now = System.currentTimeMillis()
        if (newId == targetId && !standby) {
            speak("已经在帮你找$cn 了。")
            return
        }
        targetId = newId
        standby = false
        searchStart = now
        lastLosePrompt = now
        lastFoundReport = now
        lastSpokenDist = null
        lastSeenHit = null
        lastSeenTime = 0L
        tracker.reset()
        updateHud("当前目标：$cn", getString(R.string.sub_searching),
            UiState.SEARCHING, holdMs = 2500L)
        speak("好的，现在帮你寻找$cn。请把手机摄像头对准前方，慢慢转动身体，我会告诉你它在哪里。")
    }

    // ---------------- 语音播放 ----------------
    @Volatile private var currentPlayer: MediaPlayer? = null

    private fun stopSpeaking() = ttsExecutor.execute {
        currentPlayer?.runCatching {
            if (isPlaying) stop()
            release()
        }
        currentPlayer = null
    }

    private fun speak(text: String) {
        ttsExecutor.execute {
            val wav = api.synthesize(text) ?: return@execute
            if (ptt.isRecording) return@execute
            try {
                currentPlayer?.runCatching {
                    if (isPlaying) stop()
                    release()
                }
                val file = File(cacheDir, "tts_${ttsCounter++}.wav")
                file.writeBytes(wav)
                val player = MediaPlayer().apply {
                    setAudioAttributes(
                        AudioAttributes.Builder()
                            .setUsage(AudioAttributes.USAGE_ASSISTANT)
                            .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                            .build())
                    setDataSource(file.absolutePath)
                    setOnCompletionListener {
                        it.release()
                        file.delete()
                        if (currentPlayer === it) currentPlayer = null
                    }
                    prepare()
                }
                currentPlayer = player
                player.start()
            } catch (e: Exception) {
                // 播放失败不影响主流程
            }
        }
    }

    // ---------------- 顶部信息区状态 ----------------

    /** 状态卡片外观：指示点与图标共用强调色，图标形状区分语义。 */
    private enum class UiState(val colorRes: Int, val iconRes: Int) {
        IDLE(R.color.state_idle, R.drawable.ic_visibility),
        SEARCHING(R.color.state_searching, R.drawable.ic_search),
        FOUND(R.color.state_found, R.drawable.ic_target),
        STANDBY(R.color.state_standby, R.drawable.ic_check),
        LISTENING(R.color.state_error, R.drawable.ic_mic),
        ERROR(R.color.state_error, R.drawable.ic_error),
    }

    /** 临时提示的占位截止时间：在此之前不让每帧的状态刷新把它冲掉。 */
    @Volatile private var hudHoldUntil = 0L

    /**
     * 更新顶部信息区（状态卡片 + 方位条）。
     *
     * @param bearingAngle 方位条游标角度，NaN 表示隐藏方位条
     * @param holdMs 大于 0 时，这段时间内不接受 [frameHud] 的覆盖，
     *               用于"正在聆听 / 正在识别"这类一闪而过的临时提示
     */
    private fun updateHud(
        text: String,
        sub: String,
        state: UiState,
        bearingAngle: Float = Float.NaN,
        holdMs: Long = 0L,
    ) {
        if (holdMs > 0L) hudHoldUntil = System.currentTimeMillis() + holdMs
        runOnUiThread {
            statusText.text = text
            statusSub.text = sub
            val c = ContextCompat.getColor(this, state.colorRes)
            statusDot.backgroundTintList = ColorStateList.valueOf(c)
            statusIcon.setImageResource(state.iconRes)
            statusIcon.imageTintList = ColorStateList.valueOf(c)
            if (bearingAngle.isNaN()) {
                bearing.visibility = View.INVISIBLE
                bearing.angleDeg = null
            } else {
                bearing.visibility = View.VISIBLE
                bearing.angleDeg = bearingAngle
            }
            // 只有权限出错那条路径需要卡片可点，其余状态都清掉监听
            statusCard.setOnClickListener(null)
        }
    }

    /**
     * 检测帧里的状态刷新。录音期间、以及临时提示占位期间都不更新，
     * 否则"正在聆听 / 正在识别"会被下一帧（约 30fps）立刻冲掉。
     */
    private fun frameHud(
        text: String, sub: String, state: UiState, bearingAngle: Float = Float.NaN
    ) {
        if (ptt.isRecording) return
        if (System.currentTimeMillis() < hudHoldUntil) return
        updateHud(text, sub, state, bearingAngle)
    }

    /** 测距来源的中文说法，显示在状态副标题里。 */
    private fun sourceLabel(hit: Guidance.Hit): String = getString(
        if (hit.source == "fused") R.string.source_fused else R.string.source_height)

    override fun onDestroy() {
        super.onDestroy()
        if (::tilt.isInitialized) tilt.stop()
        ttsExecutor.shutdownNow()
        analysisExecutor.shutdownNow()
    }

    companion object {
        private const val LOSE_PROMPT_INTERVAL = 7000L
        private const val FOUND_REPORT_INTERVAL = 5000L
    }
}
