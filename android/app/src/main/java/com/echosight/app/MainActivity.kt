package com.echosight.app

import android.Manifest
import android.content.pm.PackageManager
import android.content.res.ColorStateList
import android.media.AudioAttributes
import android.media.AudioManager
import android.media.MediaPlayer
import android.media.PlaybackParams
import android.media.ToneGenerator
import android.os.Bundle
import android.view.HapticFeedbackConstants
import android.view.View
import android.view.ViewGroup
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
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.updateLayoutParams
import androidx.core.view.updatePadding
import androidx.lifecycle.lifecycleScope
import com.google.android.material.button.MaterialButton
import com.google.android.material.card.MaterialCardView
import com.google.android.material.color.DynamicColors
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import android.util.Log
import java.io.File
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean

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

    /**
     * 连续处理失败多少帧。
     * 只在 analysisExecutor 这一个线程上读写，不需要 @Volatile。
     */
    private var frameErrorStreak = 0

    /**
     * 当前检测器。
     *
     * 允许为空：模型是异步加载的（建会话要读 assets + 初始化 ONNX，约 1 秒），
     * 相机可能先出帧。加载完之前 [processFrame] 直接返回，什么都不画 ——
     * 比画一堆旧框安全。
     *
     * 换模型时只在 [analysisExecutor] 上改这个引用（见 [applyModel]），
     * @Volatile 是给 onDestroy 那条主线程路径读的。
     */
    @Volatile private var detector: YoloDetector? = null

    /** 当前使用的检测模型。类别 id 是模型内局部的，所有 id → 名字都走它。 */
    @Volatile private var activeModel: ModelSpec = Models.DEFAULT

    private val api = VoiceApi(BuildConfig.SENSEAUDIO_KEY)
    private val capture = VoiceCapture()

    private val ttsExecutor = Executors.newSingleThreadExecutor()
    private val analysisExecutor = Executors.newSingleThreadExecutor()

    /**
     * 建模型会话用的线程池。
     *
     * 单独一个而不是复用 analysisExecutor：建会话要一秒左右，
     * 排在分析线程上会把相机帧全堵住（画面直接卡住）。
     */
    private val modelExecutor = Executors.newSingleThreadExecutor()

    /**
     * 提示音。ToneGenerator 在个别设备上构造会失败，所以整段包 runCatching。
     *
     * 标 @Volatile：正常提示音在主线程播（[startListening]），
     * 但 TTS 失败后的错误提示音是从 [ttsExecutor] 上播的 ——
     * 那是唯一还能用的通道，网络一断每条播报都会走到那里。
     * 两个线程同时读写这个引用，不加 @Volatile 就可能读到 null 而**静默不出声**，
     * 正好把最后一条反馈通道也弄丢。
     */
    @Volatile private var tone: ToneGenerator? = null

    /** 保护 [tone] 的惰性创建，避免两个线程各建一个（另一个永远漏着不释放）。 */
    private val toneLock = Any()

    // ---------------- 播报状态 ----------------

    /**
     * 静音中（语音指令"安静"）。
     *
     * 与"待命"（[standby]）不同：待命是不再提示目标位置，静音是不再出声，
     * 但扫描和界面刷新照旧。两个状态互相独立。
     */
    @Volatile private var muted = false

    /** 最近一次播报的原文，"再说一遍"直接重播它。 */
    @Volatile private var lastSpokenText: String? = null

    /** 播报音量档位 0~2（对应 [VOLUME_LEVELS]），"大点声/小点声"会改。 */
    @Volatile private var volumeLevel = 2

    /** 语速档位 0~2（对应 [SPEED_LEVELS]），"说慢点/说快点"会改。 */
    @Volatile private var speedLevel = 1

    /**
     * 最近一帧的全部检测框，"看看周围有什么"用它。
     *
     * 分析线程写、主线程读，所以 @Volatile。存的是不可变列表，读的时候不会变。
     */
    @Volatile private var lastFrameBoxes: List<DetBox> = emptyList()

    // ---------------- 退出确认 ----------------

    /**
     * 正在等用户确认退出。
     *
     * 退出不可撤销，所以必须二次确认；同时这个状态**有时限** ——
     * 不然用户过一会儿随口说句带"确认"的话就会被退出。
     */
    @Volatile private var awaitingExit = false
    @Volatile private var exitAskedAt = 0L

    // ---------------- 目标状态 ----------------
    //
    // 下面这几个字段**两个线程都会写**：
    //   * 分析线程 —— processFrame() 每帧更新"上次看到/上次播报"的时间戳；
    //   * 主线程   —— setTarget() 在切目标时把它们重置（走 lifecycleScope，
    //                 默认 Dispatchers.Main）。
    // 所以全部标 @Volatile。原先只有 targetId / standby / lastSeenHit 标了，
    // 这几个漏了 —— 漏掉不会崩，但主线程写进去的"刚刚重置过"分析线程可能看不见，
    // 于是切目标之后第一次播报会拿着**上一个目标的** lastSpokenDist 去算差值，
    // 张口就说"注意，你在远离目标"。用户什么都没做，却被告知正在远离。
    // 单个字段的可见性缺失，最后表现为一句错误的方向判断。
    @Volatile private var targetId: Int? = null
    @Volatile private var standby = false
    @Volatile private var searchStart = 0L
    @Volatile private var lastLosePrompt = 0L
    @Volatile private var lastFoundReport = 0L
    private var ttsCounter = 0          // 只在 ttsExecutor 线程上读写

    // 用于进展播报与丢失记忆
    @Volatile private var lastSpokenDist: Float? = null
    @Volatile private var lastSeenHit: Guidance.Hit? = null
    @Volatile private var lastSeenTime = 0L

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

        applySystemBarInsets()

        if (BuildConfig.SENSEAUDIO_KEY.isBlank()) {
            // 没有 Key 时语音链路整条不可用。而目标物品只能靠语音指定（见 switchTarget），
            // 所以这不是"功能降级"，是应用根本用不起来 —— 必须把话说清楚。
            voiceDisabled = true
            updateHud(getString(R.string.status_no_key),
                getString(R.string.sub_no_key), UiState.ERROR)
            // 按钮**保持可点**，只把它压暗。原先这里是 isEnabled = false，
            // 结果是点击事件根本不触发，盲人用户摸到按钮按下去一点声音都没有 ——
            // 他不知道是应用坏了、还是自己没按到，只能反复按。
            // 现在按下去会响一声"否"（见 bindButton），把"确实收到你的操作了，
            // 但这事做不了"讲明白。视觉上仍然压暗，给看得见的人同样的信息。
            pushButton.alpha = 0.4f
            // TTS 没 Key 也发不出去，启动时会是死一样的安静，
            // 两声"否"是唯一能告诉用户"应用起来了、但用不了"的方式。
            beepStartupFailure()
        }
        cleanStaleTtsFiles()

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

    /**
     * 把系统栏高度让出来。
     *
     * targetSdk 35 起 Android 15 对应用强制 edge-to-edge，themes.xml 里的
     * `statusBarColor` / `navigationBarColor` 会被直接忽略，窗口内容铺到状态栏和
     * 导航栏底下 —— 顶部状态卡片会被时钟、电量压住，底部大按钮会被导航栏压住。
     * 那两个主题属性只对 API 26~34 生效，所以这里必须自己补 insets。
     *
     * 基线值从 XML 现读，不在代码里再抄一份 16dp / 40dp，避免两处不一致。
     * 同时带上 displayCutout，刘海屏上状态栏高度未必覆盖挖孔。
     */
    private fun applySystemBarInsets() {
        val topBar = findViewById<View>(R.id.topBar)
        val baseTop = topBar.paddingTop
        val baseBottom = (pushButton.layoutParams as ViewGroup.MarginLayoutParams).bottomMargin
        ViewCompat.setOnApplyWindowInsetsListener(findViewById(R.id.root)) { _, insets ->
            val bars = insets.getInsets(
                WindowInsetsCompat.Type.systemBars() or
                    WindowInsetsCompat.Type.displayCutout())
            topBar.updatePadding(top = bars.top + baseTop)
            pushButton.updateLayoutParams<ViewGroup.MarginLayoutParams> {
                bottomMargin = bars.bottom + baseBottom
            }
            insets
        }
    }

    private fun startEverything() {
        // 模型异步加载：建会话要读 assets 再初始化 ONNX（约 1 秒），放主线程会把
        // 启动卡住。加载完之前 processFrame 直接返回，什么都不画。
        applyModel(Models.DEFAULT)

        tilt = CameraTilt(this)
        tilt.start()
        capture.onResult = ::onCaptureResult
        bindCamera()
        bindButton()
        speak("回声视见已启动。请问你要寻找什么物品？" +
            "点一下屏幕下方的大按钮就可以说话，说完我会自己停下来。" +
            "想让我做什么直接说就行，比如，找杯子，看看周围，或者，帮助。",
            // 启动问候语本身就是一次网络请求。网络不通时它静默失败，
            // 应用会一声不响地打开 —— 用户以为没开起来，可能去按电源键、
            // 反复点图标。两声"否"就是在说"我起来了，但我连不上网"。
            onFail = { beepStartupFailure() })
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
                    frameErrorStreak = 0        // 成功一帧就把连续失败计数清零
                } catch (e: Throwable) {
                    // 这里必须自己兜住 —— CameraX **不会**替你兜。
                    // 反汇编 androidx.camera:camera-core:1.4.1 的
                    // ImageAnalysisAbstractAnalyzer 可以看到：它只捕获
                    // acquireImage 的 IllegalStateException，类里的字符串常量
                    // 也没有任何"analyzer 抛异常"的日志。异常会一路冒到
                    // analysisExecutor 线程的默认未捕获处理器 —— Android 上
                    // 那就是 killProcess，用户看到的是闪退。
                    //
                    // 而 detect() 的抛点是真实存在的：CameraX 在
                    // STRATEGY_KEEP_ONLY_LATEST 下会提前关掉上一帧，这时读
                    // planes 就抛 IllegalStateException；此外还有 Bitmap 分配
                    // 失败、OrtException 等等。
                    //
                    // 一帧坏掉不该让整个应用消失：记下来、清空画面上的旧框，
                    // 然后继续出下一帧。
                    onFrameError(e)
                } finally {
                    image.close()
                }
            }
            provider.unbindAll()
            provider.bindToLifecycle(
                this, CameraSelector.DEFAULT_BACK_CAMERA, preview, analysis)
        }, ContextCompat.getMainExecutor(this))
    }

    /**
     * 某一帧处理失败。
     *
     * 关键是**清掉画面上的旧框**：异常时 processFrame 会在写 overlay 之前就中断，
     * 于是 overlay 会一直停在最后一帧的结果上 —— 那是"看起来一切正常的错数据"，
     * 用户会按着一个早就不成立的位置行动。宁可什么都不显示。
     */
    private fun onFrameError(e: Throwable) {
        frameErrorStreak++
        // 第一帧报一次，之后每 30 帧报一次。
        // 不能每帧都报：相机 30fps，一直失败就是每秒 30 条 Log —— 日志会被刷爆，
        // 而且 Log 调用本身有开销，在一个已经在出错的路径上再叠负担不合适。
        if (frameErrorStreak == 1 || frameErrorStreak % 30 == 0) {
            Log.e(TAG, "处理相机帧失败（连续第 $frameErrorStreak 次）", e)
            frameHud(
                getString(R.string.status_frame_error),
                getString(R.string.sub_frame_error),
                UiState.IDLE)
        }
        // 清空是**每一帧**都要做的，不能跟着日志一起被节流：
        // 异常时 processFrame 在写 overlay 之前就中断了，overlay 会停在最后一帧
        // 的结果上 —— 那是"看起来一切正常的错数据"，用户会按着早已失效的位置行动。
        // 宁可什么都不显示。
        overlay.drawBoxes = emptyList()
    }

    private fun processFrame(image: ImageProxy) {
        // 模型还没加载好（启动后约 1 秒内，或正在换模型）。什么都不画，
        // 而不是拿上一次的结果接着画 —— 那是"看起来一切正常的错数据"。
        val det = detector ?: return
        val boxes = det.detect(image)
        lastFrameBoxes = boxes
        overlay.frameWidth = det.frameWidth
        overlay.frameHeight = det.frameHeight

        val tid = targetId
        val now = System.currentTimeMillis()

        if (tid == null) {
            overlay.drawBoxes = emptyList()
            frameHud("等待指令：点一下下方大按钮说话",
                getString(R.string.sub_no_target), UiState.IDLE)
            return
        }
        val frameW = det.frameWidth.toFloat()
        val frameH = det.frameHeight.toFloat()

        val hits = boxes.filter { it.cls == tid }.map {
            Guidance.buildHit(it, frameW, frameH, tid, tilt.depressionDeg)
        }
        val cn = activeModel.nameOf(tid)

        // 待机：只画不播
        if (standby) {
            overlay.drawBoxes = hits.map {
                DrawBox(it.box, getString(R.string.box_label_no_dist, cn, it.direction))
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
                    getString(R.string.box_label, cn, h.direction,
                        "%.1f".format(h.dist)),
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

    // ---------------- 点一下说话 ----------------

    /**
     * 主按钮：点一下开始录音，再点一下表示"我说完了"。
     *
     * 原先必须按住不放。对盲人用户，"摸到按钮并一直按住"恰恰是整套交互里最难的一步：
     * 看不见按钮在哪、按着不能松、松早了话没说完、手一抖滑出按钮还会被当成取消。
     * 现在点一下就行，而且**说完不用管它** —— 静音一会儿会自动结束
     * （见 [VoiceCapture]），所以连"再点一下"都不是必须的。
     */
    private fun bindButton() {
        pushButton.setOnClickListener {
            if (voiceDisabled) {
                // 没有 Key：说不了话，但**必须响一声**。
                // 静默返回会让用户以为自己没按到，然后一直按。
                pushButton.performHapticFeedback(HapticFeedbackConstants.VIRTUAL_KEY)
                beepError()
                return@setOnClickListener
            }
            if (capture.isRecording) {
                // 再点一下 = 我说完了。手动结束也走同一个回调，不会重复识别。
                pushButton.performHapticFeedback(HapticFeedbackConstants.VIRTUAL_KEY)
                capture.requestStop()
                setRecordingUi(false)
            } else {
                startListening()
            }
        }
    }

    private fun startListening() {
        // 先停掉正在播的语音，否则会被自己的喇叭录进去
        stopSpeaking()
        if (!capture.start()) {
            // 麦克风被占用时 start() 会返回 false（不再抛异常打崩应用），
            // 这里把原因说出来，别让用户对着一个没反应的按钮干按。
            updateHud(getString(R.string.status_mic_busy),
                getString(R.string.sub_searching), UiState.ERROR, holdMs = 3000L)
            speak(getString(R.string.status_mic_busy))
            return
        }
        // 开始录音给一个短振动 + 一声提示音。看不见界面的时候，
        // "它到底开始听了没有"只能靠这两个反馈来确认。
        pushButton.performHapticFeedback(HapticFeedbackConstants.LONG_PRESS)
        beep(TONE_START)
        setRecordingUi(true)
    }

    /**
     * 录音结果。**在录音线程上被调用，不是主线程** ——
     * 里面所有碰界面的动作都经过 updateHud / runOnUiThread。
     */
    private fun onCaptureResult(wav: ByteArray, reason: VoiceCapture.StopReason) {
        // 先解除录音态。顺序不能反：setRecordingUi(false) 会清掉 hudHoldUntil，
        // 排在后面的话会把下面刚设好的占位时间一起清掉，提示一闪就没。
        setRecordingUi(false)

        when {
            reason == VoiceCapture.StopReason.AUTO_NO_SPEECH -> {
                // 空白音频不要发去识别：白花一次请求，用户还得多等几秒才知道没听清。
                updateHud("没有听到声音，请再点一下按钮说话。",
                    getString(R.string.sub_no_speech), UiState.SEARCHING, holdMs = 2500L)
            }
            wav.size < MIN_WAV_BYTES -> {
                updateHud("说话时间太短了，请点一下按钮多说一会儿。",
                    getString(R.string.sub_searching), UiState.SEARCHING, holdMs = 2500L)
                speak("说话时间太短了，请点一下按钮多说一会儿。")
            }
            else -> sendForAsr(wav)
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
            // 录音期间靠 frameHud 里对 capture.isRecording 的判断跳过刷新，
            // 这里的占位只是兜底。占位时长要盖得住自动结束的最长等待：
            // VoiceCapture 的硬上限是 20 秒，取 25 秒。
            updateHud("正在聆听，请说话", getString(R.string.sub_recognizing),
                UiState.LISTENING, holdMs = 25_000L)
        } else {
            // 解除占位。onCaptureResult 紧接着会设它自己的占位时间，
            // 所以这里必须先清零，否则"没有听到声音"之类的提示会被这段占位挡住。
            hudHoldUntil = 0L
        }
    }

    private fun sendForAsr(wav: ByteArray) {
        updateHud("正在识别，请稍等…", getString(R.string.sub_recognizing),
            UiState.SEARCHING, holdMs = 2500L)
        lifecycleScope.launch {
            val result = withContext(Dispatchers.IO) { api.transcribe(wav) }

            // 退出确认态有时限：先说"我要退出"、过很久再随口说句带"确认"的话，
            // 不该把应用退掉。这里每次识别前先检查一次。
            if (awaitingExit && System.currentTimeMillis() - exitAskedAt > EXIT_CONFIRM_MS) {
                awaitingExit = false
            }

            // 服务没答上来 —— 这不是用户说得不好，必须说清楚，否则他会
            // 一遍遍提高音量重试，而真正该做的是去看网络。
            if (result is VoiceApi.AsrResult.Failed) {
                Log.w(TAG, "识别请求失败 code=${result.code}：${result.detail}")
                updateHud(getString(R.string.status_asr_failed),
                    getString(R.string.sub_asr_failed), UiState.ERROR, holdMs = 4000L)
                // 先给一声错误提示音：这句话本身也要靠网络才能合成，
                // 网络不通时它同样播不出来，那就只剩这一声了。
                beepError()
                speak("识别服务连不上，我没有听清你说什么。" +
                    "这不是你说得不对，请检查手机的网络，然后再点一下按钮。")
                return@launch
            }

            val text = (result as VoiceApi.AsrResult.Ok).text
            val cmd = Labels.parseCommand(text, awaitingExit)
            if (cmd == null) {
                when {
                    // 确认态下听不懂就再问一次，绝不猜 —— 退出不可撤销。
                    awaitingExit ->
                        speak("我没有听清。要退出请说，确认退出；不退出请说，取消。")
                    // 走到这里才是真的"服务答上来了，但没听出内容"。
                    text.isBlank() ->
                        speak("没有听清，请点一下按钮，靠近手机再说一次。")
                    else ->
                        speak("没有听懂“$text”。你可以说，找杯子，看看周围，或者，帮助。")
                }
                return@launch
            }
            handleCommand(cmd)
        }
    }

    /**
     * 执行一条语音指令。
     *
     * `when` 覆盖全部 [Labels.Command] 分支，而且**故意不写 else** ——
     * 以后往词表里加指令时，编译器会在这里直接报错，逼着人来接上行为。
     * 写个 else 兜住的话，就会出现"词表加了、但说了没反应"的静默失效，
     * 而那种问题不编译、不报错、也没日志，只能靠用户发现。
     */
    private fun handleCommand(cmd: Labels.Command) {
        when (cmd) {
            is Labels.Command.Target -> switchTarget(cmd.ref)

            Labels.Command.Found -> {
                standby = true
                speak("好的，我先安静待命。需要找别的东西时，点一下按钮说，找，加上物品名字。")
            }

            Labels.Command.Resume -> {
                val cn = targetId?.let { activeModel.nameOf(it) }
                if (cn == null) {
                    speak("请先告诉我要找什么，比如说，找杯子。")
                } else {
                    standby = false
                    val now = System.currentTimeMillis()
                    searchStart = now
                    lastLosePrompt = now
                    lastFoundReport = now
                    lastSpokenDist = null
                    speak("好，继续帮你找$cn。")
                }
            }

            Labels.Command.Repeat -> repeatLastReport()

            Labels.Command.Mute -> {
                // 先播确认、再置标志。speak() 的静音检查是同步做的，
                // 顺序反了这句确认就永远出不来。
                speak("好的，我先不说话。要我再开口，就说，可以说了。")
                muted = true
            }

            Labels.Command.Unmute -> {
                muted = false
                speak("好，我继续说话。")
            }

            Labels.Command.Describe -> describeSurroundings()

            Labels.Command.Help -> speak(HELP_TEXT)

            Labels.Command.Louder -> {
                if (volumeLevel >= VOLUME_LEVELS.lastIndex) {
                    speak("已经是最响了，还想更大请按手机侧面的音量键。")
                } else {
                    volumeLevel++
                    speak("好，声音调大一点。")
                }
            }

            Labels.Command.Quieter -> {
                if (volumeLevel <= 0) {
                    speak("已经是最轻了。")
                } else {
                    volumeLevel--
                    speak("好，声音调小一点。")
                }
            }

            Labels.Command.Slower -> {
                if (speedLevel <= 0) {
                    speak("已经是最慢了。")
                } else {
                    speedLevel--
                    speak("好，我说慢一点。")
                }
            }

            Labels.Command.Faster -> {
                if (speedLevel >= SPEED_LEVELS.lastIndex) {
                    speak("已经是最快了。")
                } else {
                    speedLevel++
                    speak("好，我说快一点。")
                }
            }

            is Labels.Command.SwitchModel -> requestModelSwitch(cmd.modelId)

            Labels.Command.Exit -> askExit()

            Labels.Command.Confirm -> {
                awaitingExit = false
                speakThenExit("好的，再见。")
            }

            Labels.Command.Cancel -> {
                awaitingExit = false
                speak("好，不退出。")
            }
        }
    }

    /**
     * 语音切换模型。
     *
     * 三种情况都要**说出来**：已经就是这个模型、没装进来、正在换。
     * 静默什么都不做是最糟的结果 —— 用户不知道要不要再试一次。
     */
    private fun requestModelSwitch(modelId: String) {
        val spec = Models.byId(modelId)
        if (spec == null) {
            speak("没有这个模型。")
            return
        }
        if (spec.id == activeModel.id) {
            speak("现在用的就是${spec.displayName}。")
            return
        }
        applyModel(spec)
    }

    /**
     * 换用另一个检测模型。
     *
     * 分两步，为的是不阻塞任何一条关键路径：
     *   1. 在 [modelExecutor] 上建会话（读 assets + 初始化 ONNX，约 1 秒）；
     *   2. 建好之后，把"换引用 + 关旧会话"丢到 [analysisExecutor] 上做。
     *
     * 第 2 步**必须**落在分析线程上：那条线程独占 [detector]，在它上面换引用就
     * 不可能有帧正在用旧的 detector。反过来，若在主线程直接关旧会话，而恰好有
     * 一帧卡在 session.run 里，那就是在释放正在使用的原生句柄 —— 直接崩。
     * onDestroy 里关会话用的是同一个道理。
     *
     * @param announce 换成功后是否播报一句。启动时首次加载不需要（启动语已覆盖）。
     * @param then     换好之后在主线程上执行（比如接着设置目标）。
     *                 **失败时不会调用** —— 模型没换成还去设目标，
     *                 用户会以为"它听懂了但找不到"，而真实原因是没有那个模型。
     */
    private fun applyModel(
        spec: ModelSpec,
        announce: Boolean = true,
        then: (() -> Unit)? = null,
    ) {
        if (!Models.isAvailable(this, spec)) {
            Log.w(TAG, "模型 ${spec.id} 不可用：assets/${spec.assetName} 不存在，或类别表为空")
            speak("${spec.displayName}还没有装进来，我继续用${activeModel.displayName}。")
            return
        }
        modelExecutor.execute {
            val built = runCatching { YoloDetector(this, spec) }.getOrNull()
            // 包一层 runCatching：onDestroy 会关掉 analysisExecutor，
            // 若这次投递正好落在关闭之后，会被拒绝并抛 RejectedExecutionException ——
            // 那是在线程池线程上抛的，没人接就是一次闪退。
            runCatching {
                analysisExecutor.execute {
                    if (built == null) {
                        Log.e(TAG, "模型 ${spec.id} 加载失败")
                        speak("${spec.displayName}加载失败，我继续用${activeModel.displayName}。")
                        return@execute
                    }
                    val old = detector
                    detector = built
                    activeModel = spec
                    // 旧会话是原生内存（模型本体 + 运行时缓冲，约 10MB），不关就永远漏着，
                    // 每换一次漏一份。
                    old?.runCatching { close() }
                    Models.warnMissingHeights(spec)
                    runOnUiThread {
                        // 类别 id 是模型内局部的，换模型后旧目标 id 不再有意义。
                        // 不清掉的话会拿大模型的 3 号（摩托车）去小模型里查，
                        // 然后自信地播报一个完全不相干的物品名。
                        targetId = null
                        standby = false
                        lastSeenHit = null
                        lastSpokenDist = null
                        lastSeenTime = 0L
                        tracker.reset()
                        then?.invoke()
                        if (announce) speak("好，现在用${spec.displayName}。")
                    }
                }
            }
        }
    }

    /**
     * 看看周围：把画面里当前看到的物体一次说出来。
     *
     * 用最近一帧的结果（[lastFrameBoxes]）而不是重新推理一次 —— 重新跑要几百毫秒，
     * 而用户问的是"现在有什么"，最近一帧最多也就 33ms 前，足够了。
     */
    private fun describeSurroundings() {
        val boxes = lastFrameBoxes
        if (boxes.isEmpty()) {
            speak("我现在没有看到认识的东西。可以慢慢转动身体，再问我一次。")
            return
        }
        // 按类别归并计数；同类里取最大框的面积参与排序（框大通常意味着更近）
        val grouped = boxes.groupBy { it.cls }
            .map { (cls, list) ->
                Triple(cls, list.size,
                    list.maxOf { (it.x2 - it.x1) * (it.y2 - it.y1) })
            }
            .sortedByDescending { it.third }
        val parts = grouped.take(MAX_DESCRIBE_CLASSES).map { (cls, n, _) ->
            val name = activeModel.nameOf(cls)
            if (n > 1) "${name}${n}个" else name
        }
        val more = if (grouped.size > MAX_DESCRIBE_CLASSES) "，还有别的东西" else ""
        speak("我看到${parts.joinToString("、")}$more。")
    }

    /** 再说一遍：把最近一次播报原样重播。 */
    private fun repeatLastReport() {
        val last = lastSpokenText
        if (last == null) speak("我还没说过什么。先告诉我你要找什么，比如说，找杯子。")
        else speak(last)
    }

    /** 退出前的二次确认。退出不可撤销，所以必须问一次。 */
    private fun askExit() {
        awaitingExit = true
        exitAskedAt = System.currentTimeMillis()
        updateHud("要退出吗？", getString(R.string.sub_exit_confirm),
            UiState.ERROR, holdMs = EXIT_CONFIRM_MS)
        speak("确定要退出吗？要退出请说，确认退出；不退出请说，取消。")
    }

    /**
     * 播完最后一句再退出。
     *
     * 不能用固定延时糊弄：TTS 是网络请求，慢的时候一两秒才返回 ——
     * 延时短了话被切断，延时长了界面卡在那里。
     *
     * 所以走 [speak] 的完成回调，**并且必须带超时兜底**：网络挂了、播放器出错时
     * 回调永远不会来，那就永远退不出去了 —— 对盲人用户意味着"说退出没反应"。
     */
    private fun speakThenExit(text: String) {
        // 静音状态下不留遗言，直接退（用户明确要求过别说话）
        if (muted || voiceDisabled) {
            finish()
            return
        }
        val done = AtomicBoolean(false)
        val go = { if (done.compareAndSet(false, true)) finish() }
        speak(text, onDone = go)
        lifecycleScope.launch {
            delay(EXIT_SPEAK_TIMEOUT_MS)
            go()
        }
    }

    /** 提示音。ToneGenerator 在个别设备上构造会失败，整段包住，失败就算了。 */
    private fun beep(type: Int, ms: Int = BEEP_MS) {
        runCatching {
            val t = synchronized(toneLock) {
                tone ?: ToneGenerator(AudioManager.STREAM_MUSIC, BEEP_VOLUME).also { tone = it }
            }
            t.startTone(type, ms)
        }
    }

    /**
     * 出错了 —— 用一声"否"告诉用户，**不依赖网络**。
     *
     * 这是整个应用最后一条可靠通道。TTS 是网络请求，网络不通时
     * "识别服务连不上"这句解释本身就播不出来；能说出口的只剩这一声。
     *
     * 带最小间隔：距离播报每 5 秒一次，断网时会连着失败，
     * 不设限就变成报警器一样每隔几秒响一下。
     */
    @Volatile private var lastErrorBeepAt = 0L

    private fun beepError() {
        val now = System.currentTimeMillis()
        if (now - lastErrorBeepAt < ERROR_BEEP_MIN_GAP_MS) return
        lastErrorBeepAt = now
        beep(TONE_ERROR, BEEP_ERROR_MS)
    }

    /**
     * 启动时连不上网：连响两声"否"。
     *
     * 单声"否"和日常的错误音分不开。启动是特殊时刻 —— 用户刚点开应用，
     * 最需要知道的恰恰是"它到底起来了没有"。而启动问候语本身就是一次 TTS 请求，
     * 网络不通时它静默失败，应用会一声不响地打开：用户以为没开起来，
     * 可能去按电源键、反复点图标。两声"否"就是在说"我起来了，但我连不上"。
     */
    private fun beepStartupFailure() {
        beep(TONE_ERROR, BEEP_ERROR_MS)
        lastErrorBeepAt = System.currentTimeMillis()
        lifecycleScope.launch {
            delay(ERROR_DOUBLE_GAP_MS)
            beep(TONE_ERROR, BEEP_ERROR_MS)
        }
    }

    /**
     * 切换目标。
     *
     * [ref] 带模型标识：用户说的物品可能根本不在当前模型里
     * （比如"钥匙"将来只在小模型里）。那种情况下先把模型换过去，再设目标，
     * 而不是拿一个当前模型里毫无意义的类别 id 去搜。
     */
    private fun switchTarget(ref: TargetRef) {
        if (ref.modelId == activeModel.id) {
            setTarget(ref.classId)
            return
        }
        val spec = Models.byId(ref.modelId)
        if (spec == null) {
            speak("我还认不出这个物品。")
            return
        }
        // 不播"正在换模型"这种过渡话术：它会被紧接着的目标播报打断，
        // 用户只会听到后半句。换模型的事实由 setTarget 的播报带出来。
        applyModel(spec, announce = false) { setTarget(ref.classId) }
    }

    /** 真正设置目标并播报。 */
    private fun setTarget(classId: Int) {
        val cn = activeModel.nameOf(classId)
        val now = System.currentTimeMillis()
        if (classId == targetId && !standby) {
            speak("已经在帮你找$cn 了。")
            return
        }
        targetId = classId
        standby = false
        searchStart = now
        lastLosePrompt = now
        lastFoundReport = now
        lastSpokenDist = null
        lastSeenHit = null
        lastSeenTime = 0L
        tracker.reset()
        updateHud(getString(R.string.sub_target, cn), getString(R.string.sub_searching),
            UiState.SEARCHING, holdMs = 2500L)
        // 不是默认模型时把模型名带出来 —— 用户刚说了"换小模型"，
        // 需要一句确认让他知道确实换过去了。
        val via = if (activeModel.id == Models.DEFAULT.id) "" else "用${activeModel.displayName}"
        speak("好的，现在${via}帮你寻找$cn。" +
            "请把手机摄像头对准前方，慢慢转动身体，我会告诉你它在哪里。")
    }

    // ---------------- 语音播放 ----------------
    @Volatile private var currentPlayer: MediaPlayer? = null

    /**
     * 当前正在播放的临时文件。只在 [ttsExecutor] 这一个线程里读写 ——
     * 唯一的例外是 onDestroy 里的同步收尾，所以标 @Volatile。
     */
    @Volatile private var currentTtsFile: File? = null

    /**
     * 已进入销毁流程。
     *
     * 播报线程可能正卡在 synthesize() 的网络请求里（读超时 30 秒），
     * `shutdownNow()` 只是打断等待、并不能取消那个阻塞调用 —— 它拿到结果后
     * 还会继续往下走。有这个标记，它才会在建播放器之前停手，
     * 否则主线程刚收完尾，它又 new 一个 MediaPlayer 出来，照样漏。
     */
    @Volatile private var ttsStopped = false

    private fun stopSpeaking() = ttsExecutor.execute { releaseCurrentPlayer() }

    /**
     * 释放播放器**并删掉它的临时文件**，两件事必须成对做。
     *
     * 原先只在 `setOnCompletionListener` 里删文件，但播报被下一条打断时走的是
     * `stop()` + `release()`，onCompletion 根本不会触发 —— 于是每被打断一次就
     * 永久留下一个 wav。距离播报每 5 秒一条，长时间使用会一直堆在 cacheDir 里。
     */
    private fun releaseCurrentPlayer() {
        currentPlayer?.runCatching {
            if (isPlaying) stop()
            release()
        }
        currentPlayer = null
        currentTtsFile?.delete()
        currentTtsFile = null
    }

    /**
     * 清掉上次运行残留的临时语音文件。cacheDir 跨进程存活，应用被系统杀掉时
     * 来不及删的文件会一直留着，启动时统一扫一遍。
     */
    private fun cleanStaleTtsFiles() {
        runCatching {
            cacheDir.listFiles { f -> f.name.startsWith("tts_") && f.name.endsWith(".wav") }
                ?.forEach { it.delete() }
        }
    }

    /**
     * 播报。
     *
     * @param onDone 播完之后的回调，**主线程**。注意播放被打断时不会触发
     *               （[releaseCurrentPlayer] 走的是 stop + release，不触发 onCompletion），
     *               所以调用方必须自己带超时兜底 —— 见 [speakThenExit]。
     * @param onFail 这句话**没能送到用户耳朵里**时的回调，**主线程**。
     *               合成失败（网络/鉴权/服务）和播放失败都会走这里。
     *               默认行为是响一声错误提示音，因为"什么都没说"和"说了一句话"
     *               在听觉上是完全不同的两件事，而用户看不见屏幕上写了什么。
     */
    private fun speak(text: String, onDone: (() -> Unit)? = null, onFail: (() -> Unit)? = null) {
        // 静音检查放在调用方线程上同步判断，而不是丢进线程池里再判：
        // "安静"这条指令需要"先播确认、再置标志"，顺序反过来那句确认就出不来了。
        // 注意这里提前返回**不算失败** —— 用户自己要求的安静，不该响错误音。
        if (voiceDisabled || ttsStopped || muted) return
        lastSpokenText = text
        val vol = VOLUME_LEVELS[volumeLevel]
        val speed = SPEED_LEVELS[speedLevel]
        ttsExecutor.execute {
            val wav = api.synthesize(text)
            if (wav == null) {
                // 原先这里是 `?: return@execute` —— 网络一断，应用就变成哑巴，
                // 而且不响、不报、不提示，用户以为是自己没按到按钮。
                Log.w(TAG, "TTS 合成失败（网络或服务）：${text.take(40)}")
                beepError()
                if (onFail != null) runOnUiThread { runCatching { onFail() } }
                return@execute
            }
            // synthesize() 是阻塞的网络请求，shutdownNow() 打断不了它，
            // 所以拿到结果之后必须再看一眼是否已经进入销毁流程。
            if (capture.isRecording || ttsStopped) return@execute
            try {
                releaseCurrentPlayer()
                val file = File(cacheDir, "tts_${ttsCounter++}.wav")
                file.writeBytes(wav)
                val player = MediaPlayer().apply {
                    setAudioAttributes(
                        AudioAttributes.Builder()
                            .setUsage(AudioAttributes.USAGE_ASSISTANT)
                            .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                            .build())
                    setDataSource(file.absolutePath)
                    // 回调在主线程，统一丢回 ttsExecutor，避免和 speak()/stopSpeaking()
                    // 并发改 currentPlayer / currentTtsFile
                    setOnCompletionListener { mp ->
                        runCatching {
                            ttsExecutor.execute {
                                mp.release()
                                file.delete()
                                if (currentPlayer === mp) {
                                    currentPlayer = null
                                    currentTtsFile = null
                                }
                            }
                        }
                        // 完成回调留在主线程（MediaPlayer 的回调本来就在主线程）。
                        // 播放被打断时这里不会走到，所以调用方必须自己兜超时。
                        if (onDone != null) runCatching { onDone() }
                    }
                    prepare()
                }
                currentPlayer = player
                currentTtsFile = file
                applyVolume(player, vol)
                applySpeed(player, speed)
                player.start()
            } catch (e: Exception) {
                // 原先这里写的是"播放失败不影响主流程"，对这个应用恰恰相反：
                // 唯一的输出通道就是声音，播放失败等于一个字都没送到用户耳朵里。
                // 尤其是退出确认 —— 问了"确定要退出吗"却没播出来，
                // 用户根本不知道应用在等他回话，八秒后自己退了。
                Log.w(TAG, "TTS 播放失败：$e")
                beepError()
                if (onFail != null) runOnUiThread { runCatching { onFail() } }
            }
        }
    }

    /**
     * 音量。
     *
     * 用播放器自己的音量，而不是改 TTS 请求里的 `vol` 参数 —— 后者要猜接口
     * 接受什么范围，猜错会让整条合成请求失败，那就成了"彻底不出声"，
     * 比"声音大小没变"严重得多。播放器音量是本地行为，出错也只是没效果。
     *
     * 上限 1.0 就是设备当前音量，想再大只能按硬件音量键 ——
     * 所以"大点声"到顶时会明确让用户去按音量键，而不是假装调过了。
     */
    private fun applyVolume(player: MediaPlayer, vol: Float) {
        runCatching { player.setVolume(vol, vol) }
    }

    /**
     * 语速。
     *
     * `playbackParams` 在个别设备/音频通道上不被支持会抛异常，失败就保持原速 ——
     * 那是"没变快"而不是"听不到"，可以接受。
     * 固定 `setPitch(1f)`：只变速不变调，否则会像快进磁带。
     */
    private fun applySpeed(player: MediaPlayer, speed: Float) {
        if (speed == 1f) return
        runCatching {
            player.playbackParams = PlaybackParams()
                .setSpeed(speed)
                .setPitch(1f)
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
     * 没有配置语音 Key。此时状态卡片要常驻错误提示 —— 否则启动后第一帧（约 30fps）
     * 就会把它覆盖成"等待指令"，用户根本来不及看见。
     */
    private var voiceDisabled = false

    // 上一次真正写进控件的值。检测帧约 30fps，而 setText / setImageResource 每次都会
    // 触发重新测量或重新解析 VectorDrawable，内容没变就不该重复写。只在 UI 线程访问。
    private var hudText: String? = null
    private var hudSub: String? = null
    private var hudState: UiState? = null

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
            if (hudText != text) {
                hudText = text
                statusText.text = text
            }
            if (hudSub != sub) {
                hudSub = sub
                statusSub.text = sub
            }
            if (hudState != state) {
                hudState = state
                val c = ContextCompat.getColor(this, state.colorRes)
                val tint = ColorStateList.valueOf(c)
                statusDot.backgroundTintList = tint
                statusIcon.setImageResource(state.iconRes)
                statusIcon.imageTintList = tint
            }
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
        // 没配 Key 时状态卡片常驻错误提示，不被检测帧冲掉
        if (voiceDisabled) return
        if (capture.isRecording) return
        if (System.currentTimeMillis() < hudHoldUntil) return
        updateHud(text, sub, state, bearingAngle)
    }

    /** 测距来源的中文说法，显示在状态副标题里。 */
    private fun sourceLabel(hit: Guidance.Hit): String = getString(
        if (hit.source == "fused") R.string.source_fused else R.string.source_height)

    override fun onDestroy() {
        super.onDestroy()
        if (::tilt.isInitialized) tilt.stop()
        // 兜底释放麦克风：销毁时若还占着 AudioRecord，别的应用会录不了音。
        // release() 是同步的，不像 stopSpeaking() 那样要排进线程池 ——
        // 下面紧跟着 shutdownNow()，排进去的任务会被直接丢弃，等于没写。
        capture.release()

        // 同一个道理，上面那句注释没管到的另一半：正在播放的 MediaPlayer 和它的
        // 临时 wav，原先只靠 stopSpeaking() 排进 ttsExecutor 去释放，而这里紧接着
        // 就 shutdownNow() 把排队任务丢掉了 —— 于是销毁时正在播报的那份谁都不管。
        // 先立标记挡住还没开始的播报，再中断线程，最后在主线程同步收尾。
        ttsStopped = true
        ttsExecutor.shutdownNow()
        releaseCurrentPlayer()

        // 提示音发生器也是原生资源，一起放掉
        runCatching { tone?.release() }
        tone = null

        // 先关建模型的线程池：它内部会往 analysisExecutor 投任务，
        // 后关 analysisExecutor 的话那次投递会被拒绝。
        modelExecutor.shutdown()

        // ONNX 会话占的是原生内存（模型本体加运行时缓冲，约 10MB），GC 管不到，
        // 原先没有任何地方释放它 —— Activity 每次重建都会再建一个。
        // 关闭动作必须排在检测线程自己身上，不能在主线程直接关：万一还有一帧
        // 卡在 session.run 里，那就是在释放正在使用的原生句柄，会直接崩。
        // 这里用 shutdown() 而不是 shutdownNow()，后者会把关闭任务本身丢掉。
        runCatching { analysisExecutor.execute { detector?.close() } }
        analysisExecutor.shutdown()
    }

    companion object {
        private const val TAG = "EchoSight"
        private const val LOSE_PROMPT_INTERVAL = 7000L
        private const val FOUND_REPORT_INTERVAL = 5000L

        /** 小于这个字节数的录音直接丢弃：多半是误触。 */
        private const val MIN_WAV_BYTES = 1000

        /** 退出确认的有效期。过期后说"确认"不会退出。 */
        private const val EXIT_CONFIRM_MS = 8000L

        /** 告别语最多等这么久，超时就直接退出（TTS 是网络请求，可能永远不返回）。 */
        private const val EXIT_SPEAK_TIMEOUT_MS = 4000L

        /** 描述周围时最多报几个类别，太多了听不完。 */
        private const val MAX_DESCRIBE_CLASSES = 6

        /** 提示音：开始用短哔，结束用确认音。 */
        private const val TONE_START = ToneGenerator.TONE_PROP_BEEP
        private const val BEEP_VOLUME = 80
        private const val BEEP_MS = 120

        /**
         * 出错提示音。
         *
         * 用 `TONE_PROP_NACK`（negative acknowledgement，一声低沉的"否"），
         * 和开始录音的 [TONE_START]（清脆一声"哔"）在音高上完全不同，
         * 用户不用学就能分辨 —— 这很关键，因为它是**唯一不依赖网络**的输出通道：
         * TTS 挂了的时候，这声"否"是应用还能说出的最后一句话。
         */
        private const val TONE_ERROR = ToneGenerator.TONE_PROP_NACK
        private const val BEEP_ERROR_MS = 220

        /** 两声错误音的间隔（启动时用来表示"我起来了，但我连不上"）。 */
        private const val ERROR_DOUBLE_GAP_MS = 260L

        /**
         * 错误音的最小间隔。
         *
         * 距离播报每 5 秒一次，网络一断就会连着失败 —— 不设限的话会变成
         * 每隔几秒一声"否"，像报警器一样吵，用户第一反应是把应用关掉。
         * 3 秒足够让人注意到，又不至于变成噪声。
         */
        private const val ERROR_BEEP_MIN_GAP_MS = 3000L

        /**
         * 音量档位。1.0 是设备当前音量，不能超过 —— 想更大只能按硬件音量键。
         * 所以"大点声"到顶时会明确让用户去按音量键。
         */
        private val VOLUME_LEVELS = floatArrayOf(0.35f, 0.6f, 1.0f)

        /** 语速档位。1.0 为正常。 */
        private val SPEED_LEVELS = floatArrayOf(0.75f, 1.0f, 1.3f)

        /**
         * "帮助"念出来的说明。
         *
         * 必须和 [Labels.parseCommand] 里真实支持的词对得上 —— 念了做不到的指令，
         * 比不念更糟：用户会反复尝试一个不存在的功能。
         */
        private const val HELP_TEXT =
            "你可以这样说。" +
                "找东西，比如说，找杯子，找手机。" +
                "不想找了，说，停下。" +
                "继续找，说，继续找。" +
                "想听周围有什么，说，看看周围。" +
                "想让我重说一遍，说，再说一遍。" +
                "想让我安静，说，安静；想让我开口，说，可以说了。" +
                "声音大小说，大点声，小点声；快慢说，说慢点，说快点。" +
                "换模型说，换小模型，或者，换大模型。" +
                "要退出，说，退出。"
    }
}
