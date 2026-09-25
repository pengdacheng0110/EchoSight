package com.echosight.app

import android.annotation.SuppressLint
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import android.util.Log
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.MultipartBody
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

/** SenseAudio 云端 TTS / ASR 接口。 */
class VoiceApi(private val apiKey: String) {

    private val base = "https://api.senseaudio.cn"
    private val client = OkHttpClient.Builder()
        .connectTimeout(10, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .build()

    /** 文本转语音，返回 wav 字节；失败返回 null。 */
    fun synthesize(text: String): ByteArray? {
        val body = JSONObject().apply {
            put("model", "sensenova-tts-2.0")
            put("text", text)
            put("stream", false)
            put("voice_setting", JSONObject().apply {
                put("voice_id", "female_0033_b")
                put("speed", 1); put("vol", 1); put("pitch", 0)
            })
            put("audio_setting", JSONObject().apply {
                put("format", "wav"); put("sample_rate", 32000)
                put("channel", 1)
            })
        }
        val req = Request.Builder()
            .url("$base/v1/t2a_v2")
            .header("Authorization", "Bearer $apiKey")
            .post(body.toString().toRequestBody(JSON_MEDIA))
            .build()
        return try {
            client.newCall(req).execute().use { r ->
                if (!r.isSuccessful) {
                    Log.w("VoiceApi", "TTS失败 ${r.code}")
                    return null
                }
                val audio = JSONObject(r.body!!.string())
                    .getJSONObject("data").getString("audio")
                hexToBytes(audio)
            }
        } catch (e: Exception) {
            Log.w("VoiceApi", "TTS异常 $e")
            null
        }
    }

    /** 上传 wav 识别，返回文本；失败返回空串。 */
    fun transcribe(wav: ByteArray): String {
        val body = MultipartBody.Builder()
            .setType(MultipartBody.FORM)
            .addFormDataPart("model", "senseaudio-asr-1.5-260319")
            .addFormDataPart("language", "zh")
            .addFormDataPart("response_format", "json")
            .addFormDataPart("file", "speech.wav",
                wav.toRequestBody(WAV_MEDIA))
            .build()
        val req = Request.Builder()
            .url("$base/v1/audio/transcriptions")
            .header("Authorization", "Bearer $apiKey")
            .post(body)
            .build()
        return try {
            client.newCall(req).execute().use { r ->
                if (!r.isSuccessful) {
                    Log.w("VoiceApi", "ASR失败 ${r.code}")
                    return ""
                }
                val text = JSONObject(r.body!!.string())
                    .optString("text", "").trim()
                Log.i("VoiceApi", "听到: $text")
                text
            }
        } catch (e: Exception) {
            Log.w("VoiceApi", "ASR异常 $e")
            ""
        }
    }

    private fun hexToBytes(hex: String): ByteArray {
        val out = ByteArray(hex.length / 2)
        for (i in out.indices) {
            out[i] = ((Character.digit(hex[i * 2], 16) shl 4) +
                    Character.digit(hex[i * 2 + 1], 16)).toByte()
        }
        return out
    }

    companion object {
        val JSON_MEDIA = "application/json; charset=utf-8".toMediaType()
        val WAV_MEDIA = "audio/wav".toMediaType()

        /** PCM 字节加 44 字节 wav 头。 */
        fun pcmToWav(pcm: ByteArray, sampleRate: Int): ByteArray {
            val total = pcm.size + 44
            val out = ByteArrayOutputStream(total)
            fun writeShort(v: Int) {
                out.write(v and 0xFF)
                out.write((v shr 8) and 0xFF)
            }
            fun writeInt(v: Int) {
                out.write(v and 0xFF)
                out.write((v shr 8) and 0xFF)
                out.write((v shr 16) and 0xFF)
                out.write((v shr 24) and 0xFF)
            }
            out.write('R'.code); out.write('I'.code)
            out.write('F'.code); out.write('F'.code)
            writeInt(total - 8)
            out.write('W'.code); out.write('A'.code)
            out.write('V'.code); out.write('E'.code)
            out.write('f'.code); out.write('m'.code)
            out.write('t'.code); out.write(' '.code)
            writeInt(16); writeShort(1); writeShort(1)
            writeInt(sampleRate); writeInt(sampleRate * 2)
            writeShort(2); writeShort(16)
            out.write('d'.code); out.write('a'.code)
            out.write('t'.code); out.write('a'.code)
            writeInt(pcm.size)
            out.write(pcm)
            return out.toByteArray()
        }
    }
}

/**
 * 点一下开始、静音自动停的录音。
 *
 * ## 为什么不再"按住说话"
 *
 * 原先必须按住屏幕下方那个圆按钮说话。对盲人用户，**摸到按钮并一直按住**
 * 本身就是整套交互里最难的一步：看不见按钮在哪、按着不能松、松早了话没说完、
 * 手一抖滑出按钮还会被当成取消。改成点一下开始之后，用户只需要
 * "点一下 → 说话 → 等它自己结束"。
 *
 * ## 自动结束是怎么判的
 *
 * 按 100ms 一帧读 PCM，逐帧算 RMS：
 *   * 开头约 300ms 只用来估计**本机噪声底**。环境底噪因设备和房间而异，
 *     写死一个绝对阈值在安静房间里能用、在街边就完全失效（一直不静音）；
 *   * 门限 = max(噪声底 × [NOISE_FACTOR], [ABS_FLOOR])。绝对下限是给
 *     "一点开就立刻开口、还没来得及估噪声底"的情况兜底的；
 *   * 出现过人声之后，连续静音满 [SILENCE_BYTES]（800ms）就自动结束；
 *     静音时长按**字节**累计而不是"读了几次" —— `AudioRecord.read`
 *     允许短读，按次数算的话短读会让 800ms 缩水成一百多毫秒；
 *   * 一直没听到人声超过 [NO_SPEECH_MS] 就结束，并把原因报出去，
 *     让界面说"没听到声音"，而不是发一段空白音频去浪费一次识别；
 *   * 硬上限 [MAX_MS] 兜住"环境噪声一直高于门限所以永远不静音"的情况
 *     （比如站在马路边）。
 *
 * 这几个门限是经验值，不同手机的麦克风增益差很多，可能得按机型调 ——
 * 所以单独列出来并写清含义，别埋在代码里当魔法数字。
 *
 * ## 数据怎么交出去
 *
 * 手动停和自动停**都**通过 [onResult] 回调，不提供"停止并返回数据"的接口。
 * 两条路径各自返回数据的话，很容易出现同一次录音被送去识别两次
 * （用户手点得晚了一点，正好和自动结束撞上）。
 */
class VoiceCapture {

    /** 结束录音的原因。 */
    enum class StopReason {
        /** 用户又点了一下 */
        MANUAL,
        /** 说完了：静音超时 */
        AUTO_SILENCE,
        /** 一直没听到人说话 */
        AUTO_NO_SPEECH,
        /** 超过最长时长 */
        AUTO_MAX_LENGTH,
        /** 读音频出错 */
        ERROR,
    }

    private val sampleRate = 16000
    private var recorder: AudioRecord? = null
    @Volatile private var recording = false
    private var pcmOut = ByteArrayOutputStream()

    /**
     * 保护 [pcmOut] 的锁。
     *
     * 不要直接写 `synchronized(pcmOut)` —— pcmOut 是个会被重新赋值的字段，
     * 锁一个非 final 字段意味着两个线程可能锁在不同对象上，等于没锁。
     */
    private val lock = Any()
    private var thread: Thread? = null

    /** 已经交付过结果，防止同一次录音回调两次。 */
    private val delivered = AtomicBoolean(false)

    /** 正在丢弃（onDestroy），结束时不要再回调。 */
    @Volatile private var discarded = false

    /**
     * 录音结果回调。**在录音线程上被调用，不是主线程** ——
     * 调用方要碰 UI 必须自己切线程。
     *
     * 也正因为它在录音线程上被调用，[teardown] 里才不能无条件 join 自己。
     */
    @Volatile var onResult: ((wav: ByteArray, reason: StopReason) -> Unit)? = null

    val isRecording get() = recording

    /**
     * 开始录音。
     *
     * @return 是否真的进入了录音状态。麦克风被别的应用占用、或参数不被支持时
     *         会返回 false —— 原先这里直接调 startRecording()，
     *         设备处于 STATE_UNINITIALIZED 时会抛 IllegalStateException 把应用打崩。
     */
    @SuppressLint("MissingPermission")
    fun start(): Boolean {
        if (recording) return false
        val minBuf = AudioRecord.getMinBufferSize(
            sampleRate, AudioFormat.CHANNEL_IN_MONO,
            AudioFormat.ENCODING_PCM_16BIT)
        val bufSize = maxOf(minBuf, sampleRate * 2) // 至少2秒缓冲
        val rec = try {
            AudioRecord(
                MediaRecorder.AudioSource.MIC, sampleRate,
                AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT,
                bufSize)
        } catch (e: Exception) {
            Log.w(TAG, "创建 AudioRecord 失败: $e")
            return false
        }
        if (rec.state != AudioRecord.STATE_INITIALIZED) {
            Log.w(TAG, "AudioRecord 未初始化（麦克风可能被占用），放弃录音")
            rec.runCatching { release() }
            return false
        }
        recorder = rec
        synchronized(lock) { pcmOut = ByteArrayOutputStream() }
        delivered.set(false)
        discarded = false
        try {
            rec.startRecording()
        } catch (e: Exception) {
            Log.w(TAG, "startRecording 失败: $e")
            rec.runCatching { release() }
            recorder = null
            return false
        }
        recording = true
        thread = Thread({ readLoop(rec) }, "voice-capture").also { it.start() }
        return true
    }

    /**
     * 请求结束录音（用户又点了一下）。数据通过 [onResult] 交出去。
     *
     * 只置标志，不在这里 join：录音线程会自己收尾并回调。
     * 在这里同步等它结束的话，调用方（UI 线程）会被卡住，
     * 而且和自动结束撞在一起时容易变成双重交付。
     */
    fun requestStop() {
        if (!recording) return
        recording = false
    }

    /**
     * 丢弃并释放，不回调。供 onDestroy 兜底。
     *
     * 不释放的话麦克风会一直被占着，别的应用录不了音。
     */
    fun release() {
        discarded = true
        teardown()
    }

    /** 读循环。**运行在录音线程上**。 */
    private fun readLoop(rec: AudioRecord) {
        val frame = ByteArray(FRAME_BYTES)
        var bytesRead = 0L
        var calibFrames = 0
        // 噪声底取**最小值**而不是最大值：开始录音前会放一声提示音，
        // 那 120ms 必然落在 300ms 的标定窗口里。取最大值的话噪声底会被
        // 提示音抬起来（门限跟着涨 2.5 倍），正常说话的音量就再也过不了门限，
        // 结果每次都走"没听到声音"。取最小值就能自动躲开这个瞬态。
        var noiseFloor = Double.MAX_VALUE
        var speechSeen = false
        // 静音时长按**字节**累加，不按读到的次数累加。
        // AudioRecord.read 是允许短读的（要多少不一定给多少），
        // 按次数算的话：每次只返回 20ms 时，"连续 8 次静音"实际只有 160ms，
        // 用户刚停顿换口气就被判定说完了。按字节算就跟读多大块无关。
        var silentBytes = 0L
        var reason = StopReason.MANUAL

        while (recording) {
            val n = try {
                rec.read(frame, 0, frame.size)
            } catch (e: Exception) {
                Log.w(TAG, "读音频失败: $e")
                reason = StopReason.ERROR
                break
            }
            if (n <= 0) continue
            synchronized(lock) { pcmOut.write(frame, 0, n) }
            bytesRead += n
            val rms = rms(frame, n)
            // 时长一律由字节数换算，不假设"每次读回整整一帧"
            val elapsedMs = bytesRead * 1000L / (sampleRate * 2L)

            // 开头几帧只用来估本机噪声底，不参与"有没有人声"的判断
            if (calibFrames < NOISE_CALIB_FRAMES) {
                calibFrames++
                noiseFloor = minOf(noiseFloor, rms)
                continue
            }
            val floor = if (noiseFloor == Double.MAX_VALUE) ABS_FLOOR else noiseFloor
            val threshold = maxOf(floor * NOISE_FACTOR, ABS_FLOOR)

            if (rms > threshold) {
                speechSeen = true
                silentBytes = 0
            } else if (speechSeen) {
                silentBytes += n
                if (silentBytes >= SILENCE_BYTES) {
                    reason = StopReason.AUTO_SILENCE
                    break
                }
            }

            if (!speechSeen && elapsedMs >= NO_SPEECH_MS) {
                reason = StopReason.AUTO_NO_SPEECH
                break
            }
            if (elapsedMs >= MAX_MS) {
                reason = StopReason.AUTO_MAX_LENGTH
                break
            }
        }
        finish(reason)
    }

    /** 读循环退出后的统一收尾。**运行在录音线程上**。 */
    private fun finish(reason: StopReason) {
        recording = false
        // 只释放设备、不 join —— 这里就是录音线程自己。
        releaseRecorderOnly()
        if (discarded) return
        if (!delivered.compareAndSet(false, true)) return
        val pcm = synchronized(lock) { pcmOut.toByteArray() }
        Log.i(TAG, "录音结束：$reason，PCM ${pcm.size} 字节")
        onResult?.invoke(VoiceApi.pcmToWav(pcm, sampleRate), reason)
    }

    private fun teardown() {
        recording = false
        val t = thread
        thread = null
        // 关键：回调是在录音线程上触发的，那条路径会走到 [finish] → 这里。
        // 无条件 join(1500) 就是 join 自己 —— 永远等不到自己结束，
        // 白等 1.5 秒，而这 1.5 秒正好卡在"用户刚说完话"的关键路径上。
        if (t != null && t !== Thread.currentThread()) t.join(1500)
        releaseRecorderOnly()
    }

    /** 只释放 AudioRecord，不碰 thread、不做 join。 */
    private fun releaseRecorderOnly() {
        recorder?.runCatching { stop() }
        recorder?.runCatching { release() }
        recorder = null
    }

    /** 一小段 PCM16 的均方根，用来判断有没有人在说话。 */
    private fun rms(buf: ByteArray, n: Int): Double {
        var sum = 0.0
        var i = 0
        while (i + 1 < n) {
            // 小端、16 位有符号
            val s = ((buf[i + 1].toInt() shl 8) or
                    (buf[i].toInt() and 0xFF)).toShort().toInt()
            sum += s.toDouble() * s
            i += 2
        }
        val samples = n / 2
        return if (samples == 0) 0.0 else kotlin.math.sqrt(sum / samples)
    }

    private companion object {
        const val TAG = "VoiceCapture"

        /** 每帧时长（毫秒）。越短越灵敏，但 RMS 抖动越大。 */
        const val FRAME_MS = 100
        const val FRAME_BYTES = 16000 / (1000 / FRAME_MS) * 2   // 3200

        /** 开头用几帧估本机噪声底（3 帧 ≈ 300ms）。 */
        const val NOISE_CALIB_FRAMES = 3

        /** 门限 = 噪声底 × 该系数。 */
        const val NOISE_FACTOR = 2.5

        /** PCM16 的绝对下限：安静设备上噪声底可能接近 0，光靠倍数会过于灵敏。 */
        const val ABS_FLOOR = 250.0

        /**
         * 连续静音多少**字节**后认为说完了。
         * 16000Hz × 2 字节 × 0.8 秒 = 25600。用字节而不是"读了几次"，
         * 理由见 readLoop 里的注释。
         */
        const val SILENCE_BYTES = 16000L * 2L * 800L / 1000L

        /** 一直没人说话就结束，别让用户对着"正在听"干等。 */
        const val NO_SPEECH_MS = 6000L

        /** 硬上限。 */
        const val MAX_MS = 20_000L
    }
}
