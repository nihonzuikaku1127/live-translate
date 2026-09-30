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
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import androidx.core.content.IntentCompat
import com.google.android.gms.tasks.Tasks
import com.google.mlkit.nl.translate.TranslateLanguage
import com.google.mlkit.nl.translate.Translation
import com.google.mlkit.nl.translate.Translator
import com.google.mlkit.nl.translate.TranslatorOptions
import org.json.JSONObject
import org.vosk.Model
import org.vosk.Recognizer
import java.io.File

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
            NotificationChannel(CHANNEL_ID, "ライブ翻訳", NotificationManager.IMPORTANCE_LOW)
        )
        val stopIntent = PendingIntent.getService(
            this, 0,
            Intent(this, TranslateService::class.java).setAction(ACTION_STOP),
            PendingIntent.FLAG_IMMUTABLE
        )
        val notification = NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.ic_btn_speak_now)
            .setContentTitle("ライブ翻訳 実行中")
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
                val modelDir = ModelManager.ensureModel(this, lang) { showOriginal(it) }
                if (!running) return@Thread
                showOriginal("翻訳モデルを準備中…（初回のみ少し時間がかかります）")
                prepareTranslator(lang)
                if (!running) return@Thread
                showOriginal("準備完了。動画を再生してください")
                recognize(modelDir, lang)
            } catch (e: Exception) {
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

    @SuppressLint("MissingPermission")
    private fun recognize(modelDir: File, lang: String) {
        val mp = projection ?: return
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
        val record = AudioRecord.Builder()
            .setAudioFormat(format)
            .setBufferSizeInBytes(maxOf(minBuf, 8192) * 2)
            .setAudioPlaybackCaptureConfig(captureConfig)
            .build()
        audioRecord = record

        val model = Model(modelDir.absolutePath)
        val recognizer = Recognizer(model, SAMPLE_RATE.toFloat())
        // 話が長く続くとき、この文字数を超えたら区切って翻訳する
        val forceLimit = if (lang == "zh") 30 else 100
        val buffer = ByteArray(4096)
        var lastPartial = ""

        record.startRecording()
        try {
            while (running) {
                val n = record.read(buffer, 0, buffer.size)
                if (n <= 0) continue
                if (recognizer.acceptWaveForm(buffer, n)) {
                    handleFinal(field(recognizer.result, "text"), lang)
                    lastPartial = ""
                } else {
                    val partial = clean(field(recognizer.partialResult, "partial"), lang)
                    if (partial != lastPartial) {
                        lastPartial = partial
                        if (partial.isNotEmpty()) showOriginal(partial)
                    }
                    if (partial.length > forceLimit) {
                        handleFinal(field(recognizer.finalResult, "text"), lang)
                        lastPartial = ""
                    }
                }
            }
        } finally {
            runCatching { record.stop() }
            runCatching { record.release() }
            runCatching { recognizer.close() }
            runCatching { model.close() }
        }
    }

    private fun field(json: String, key: String): String =
        runCatching { JSONObject(json).optString(key, "") }.getOrDefault("")

    // 中国語モデルは文字の間にスペースが入るので取り除く
    private fun clean(text: String, lang: String): String =
        if (lang == "zh") text.replace(" ", "").trim() else text.trim()

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
