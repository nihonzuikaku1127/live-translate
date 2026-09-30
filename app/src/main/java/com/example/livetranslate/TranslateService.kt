package com.example.livetranslate

import android.annotation.SuppressLint
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import android.content.pm.ServiceInfo
import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioPlaybackCaptureConfiguration
import android.media.AudioRecord
import android.media.projection.MediaProjection
import android.media.projection.MediaProjectionManager
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.Process
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import androidx.core.content.IntentCompat
import com.google.android.gms.tasks.Tasks
import com.google.mlkit.nl.translate.TranslateLanguage
import com.google.mlkit.nl.translate.Translation
import com.google.mlkit.nl.translate.Translator
import com.google.mlkit.nl.translate.TranslatorOptions
import com.k2fsa.sherpa.onnx.FeatureConfig
import com.k2fsa.sherpa.onnx.OfflineModelConfig
import com.k2fsa.sherpa.onnx.OfflineRecognizer
import com.k2fsa.sherpa.onnx.OfflineRecognizerConfig
import com.k2fsa.sherpa.onnx.OfflineSenseVoiceModelConfig
import com.k2fsa.sherpa.onnx.SileroVadModelConfig
import com.k2fsa.sherpa.onnx.Vad
import com.k2fsa.sherpa.onnx.VadModelConfig

class TranslateService : Service() {

    companion object {
        const val ACTION_START = "com.example.livetranslate.START"
        const val ACTION_STOP = "com.example.livetranslate.STOP"
        const val EXTRA_RESULT_CODE = "result_code"
        const val EXTRA_RESULT_DATA = "result_data"
        const val EXTRA_LANG = "lang"
        private const val CHANNEL_ID = "live_translate"
        private const val NOTIF_ID = 1
        private const val SAMPLE_RATE = 16000
        private const val TAG = "LiveTranslate"
        // 話が途切れずに続くときは、この秒数で区切って字幕を出す
        private const val MAX_SEGMENT_SEC = 5.0f
        // 話している途中の原文プレビューを更新する間隔（サンプル数 = 0.8秒）
        private const val PREVIEW_INTERVAL = SAMPLE_RATE * 8 / 10
    }

    private val main = Handler(Looper.getMainLooper())
    private var projection: MediaProjection? = null
    private var audioRecord: AudioRecord? = null
    private var translator: Translator? = null
    private var overlay: SubtitleOverlay? = null
    @Volatile private var running = false

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_STOP -> stopSelf()
            ACTION_START -> {
                startForegroundNow()
                if (projection != null) return START_NOT_STICKY

                val code = intent.getIntExtra(EXTRA_RESULT_CODE, 0)
                val data = IntentCompat.getParcelableExtra(intent, EXTRA_RESULT_DATA, Intent::class.java)
                val lang = intent.getStringExtra(EXTRA_LANG) ?: "en"
                if (data == null) {
                    stopSelf()
                    return START_NOT_STICKY
                }
                val mpm = getSystemService(MediaProjectionManager::class.java)
                projection = mpm.getMediaProjection(code, data)?.also {
                    it.registerCallback(object : MediaProjection.Callback() {
                        override fun onStop() { main.post { stopSelf() } }
                    }, main)
                }
                if (projection == null) {
                    stopSelf()
                    return START_NOT_STICKY
                }
                overlay = SubtitleOverlay(this).also { it.show() }
                begin(lang)
            }
        }
        return START_NOT_STICKY
    }

    private fun startForegroundNow() {
        val nm = getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(
            NotificationChannel(CHANNEL_ID, "ライブ翻訳HQ", NotificationManager.IMPORTANCE_LOW)
        )
        val stopIntent = PendingIntent.getService(
            this, 0,
            Intent(this, TranslateService::class.java).setAction(ACTION_STOP),
            PendingIntent.FLAG_IMMUTABLE
        )
        val notification = NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.ic_btn_speak_now)
            .setContentTitle("ライブ翻訳HQ 実行中")
            .setContentText("「停止」を押すと終了します")
            .addAction(0, "停止", stopIntent)
            .setOngoing(true)
            .build()
        var type = ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION
        if (Build.VERSION.SDK_INT >= 30) type = type or ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE
        ServiceCompat.startForeground(this, NOTIF_ID, notification, type)
    }

    private fun begin(lang: String) {
        running = true
        Thread {
            try {
                // 音声処理スレッドを優先して動かす
                Process.setThreadPriority(Process.THREAD_PRIORITY_AUDIO)
                val models = ModelManager.ensureModels(this) { showOriginal(it) }
                if (!running) return@Thread
                showOriginal("翻訳モデルを準備中…（初回のみ少し時間がかかります）")
                prepareTranslator(lang)
                if (!running) return@Thread
                recognize(models, lang)
            } catch (e: Throwable) {
                Log.e(TAG, "error", e)
                showOriginal("エラー: ${e.message}")
            }
        }.start()
    }

    private fun prepareTranslator(lang: String) {
        val source = if (lang == "zh") TranslateLanguage.CHINESE else TranslateLanguage.ENGLISH
        val options = TranslatorOptions.Builder()
            .setSourceLanguage(source)
            .setTargetLanguage(TranslateLanguage.JAPANESE)
            .build()
        val t = Translation.getClient(options)
        Tasks.await(t.downloadModelIfNeeded())
        translator = t
    }

    /**
     * Snapdragon 8 Elite / Dimensity 9600 Pro はどちらも高性能コアが多いので、
     * 認識には4スレッドを使う（コア数が少ない端末では減らす）
     */
    private fun recognizerThreads(): Int =
        (Runtime.getRuntime().availableProcessors() / 2).coerceIn(2, 4)

    private fun createRecognizer(models: ModelManager.Models, lang: String) = OfflineRecognizer(
        config = OfflineRecognizerConfig(
            featConfig = FeatureConfig(sampleRate = SAMPLE_RATE, featureDim = 80),
            modelConfig = OfflineModelConfig(
                senseVoice = OfflineSenseVoiceModelConfig(
                    model = models.senseVoice.absolutePath,
                    // 英語モードは "en"、中国語モードは "zh" に固定して誤判定を防ぐ
                    language = lang,
                    // 句読点や数字を読みやすい形で出力する
                    useInverseTextNormalization = true
                ),
                tokens = models.tokens.absolutePath,
                numThreads = recognizerThreads(),
                provider = "cpu"
            )
        )
    )

    private fun createVad(models: ModelManager.Models) = Vad(
        config = VadModelConfig(
            sileroVadModelConfig = SileroVadModelConfig(
                model = models.vad.absolutePath,
                threshold = 0.5f,
                minSilenceDuration = 0.3f,
                minSpeechDuration = 0.25f,
                windowSize = 512,
                maxSpeechDuration = MAX_SEGMENT_SEC
            ),
            sampleRate = SAMPLE_RATE,
            numThreads = 1,
            provider = "cpu"
        )
    )

    @SuppressLint("MissingPermission")
    private fun recognize(models: ModelManager.Models, lang: String) {
        val mp = projection ?: return
        showOriginal("音声認識エンジンを起動中…")
        val recognizer = createRecognizer(models, lang)
        val vad = createVad(models)

        val captureConfig = AudioPlaybackCaptureConfiguration.Builder(mp)
            .addMatchingUsage(AudioAttributes.USAGE_MEDIA)
            .addMatchingUsage(AudioAttributes.USAGE_GAME)
            .addMatchingUsage(AudioAttributes.USAGE_UNKNOWN)
            .build()
        val format = AudioFormat.Builder()
            .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
            .setSampleRate(SAMPLE_RATE)
            .setChannelMask(AudioFormat.CHANNEL_IN_MONO)
            .build()
        val minBuf = AudioRecord.getMinBufferSize(
            SAMPLE_RATE, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT
        )
        // 認識処理中も音声を取りこぼさないよう、約2秒分のバッファを確保する
        val record = AudioRecord.Builder()
            .setAudioFormat(format)
            .setBufferSizeInBytes(maxOf(minBuf, SAMPLE_RATE * 2 * 2))
            .setAudioPlaybackCaptureConfig(captureConfig)
            .build()
        audioRecord = record

        val chunk = ShortArray(SAMPLE_RATE / 10) // 0.1秒ずつ読む
        val speech = FloatBuffer()
        var sinceLastPreview = 0

        record.startRecording()
        showOriginal("準備完了。動画を再生してください")
        try {
            while (running) {
                val n = record.read(chunk, 0, chunk.size)
                if (n <= 0) continue
                val samples = FloatArray(n) { chunk[it] / 32768f }
                vad.acceptWaveform(samples)

                // 区切りが確定した発話を認識して翻訳する
                var segmentDone = false
                while (!vad.empty()) {
                    val segment = vad.front()
                    vad.pop()
                    handleFinal(decode(recognizer, segment.samples), lang)
                    segmentDone = true
                }

                if (segmentDone || !vad.isSpeechDetected()) {
                    speech.clear()
                    sinceLastPreview = 0
                } else {
                    // 話している途中は原文のプレビューを表示する
                    speech.add(samples)
                    sinceLastPreview += n
                    if (sinceLastPreview >= PREVIEW_INTERVAL && speech.size >= SAMPLE_RATE / 2) {
                        sinceLastPreview = 0
                        val partial = clean(decode(recognizer, speech.toArray()), lang)
                        if (partial.isNotEmpty()) showOriginal("$partial…")
                    }
                }
            }
        } finally {
            runCatching { record.stop() }
            runCatching { record.release() }
            runCatching { vad.release() }
            runCatching { recognizer.release() }
        }
    }

    private fun decode(recognizer: OfflineRecognizer, samples: FloatArray): String {
        val stream = recognizer.createStream()
        try {
            stream.acceptWaveform(samples, SAMPLE_RATE)
            recognizer.decode(stream)
            return recognizer.getResult(stream).text
        } finally {
            stream.release()
        }
    }

    /** 伸び縮みする float 配列（発話途中の音声をためておく） */
    private class FloatBuffer {
        private var data = FloatArray(SAMPLE_RATE * 8)
        var size = 0
            private set

        fun add(src: FloatArray) {
            if (size + src.size > data.size) data = data.copyOf(maxOf(data.size * 2, size + src.size))
            System.arraycopy(src, 0, data, size, src.size)
            size += src.size
        }

        fun clear() { size = 0 }

        fun toArray(): FloatArray = data.copyOf(size)
    }

    // SenseVoice は中国語の文字間に空白を入れないので、前後の空白だけ取り除く
    @Suppress("UNUSED_PARAMETER")
    private fun clean(text: String, lang: String): String = text.trim()

    private fun handleFinal(raw: String, lang: String) {
        val text = clean(raw, lang)
        if (text.isEmpty()) return
        showOriginal(text)
        translator?.translate(text)
            ?.addOnSuccessListener { ja -> overlay?.addTranslation(ja) }
            ?.addOnFailureListener { e -> Log.e(TAG, "translate failed", e) }
    }

    private fun showOriginal(text: String) {
        main.post { overlay?.setOriginal(text) }
    }

    override fun onDestroy() {
        running = false
        runCatching { audioRecord?.stop() }
        translator?.close()
        translator = null
        overlay?.hide()
        overlay = null
        runCatching { projection?.stop() }
        projection = null
        super.onDestroy()
    }
}
