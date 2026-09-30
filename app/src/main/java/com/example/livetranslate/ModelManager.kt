package com.example.livetranslate

import android.content.Context
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL

/** 音声認識モデル（SenseVoice）と発話区切りモデル（Silero VAD）を初回だけダウンロードする */
object ModelManager {

    private const val SENSE_VOICE_BASE =
        "https://huggingface.co/csukuangfj/sherpa-onnx-sense-voice-zh-en-ja-ko-yue-2024-07-17/resolve/main"
    private const val VAD_URL =
        "https://github.com/k2-fsa/sherpa-onnx/releases/download/asr-models/silero_vad.onnx"

    class Models(val senseVoice: File, val tokens: File, val vad: File)

    private class Item(val label: String, val url: String, val file: File)

    /** 英語・中国語どちらも同じ SenseVoice モデルを使うので、ダウンロードは1回だけ */
    fun ensureModels(ctx: Context, progress: (String) -> Unit): Models {
        val dir = File(ctx.filesDir, "models/sense-voice").apply { mkdirs() }
        val models = Models(
            senseVoice = File(dir, "model.int8.onnx"),
            tokens = File(dir, "tokens.txt"),
            vad = File(dir, "silero_vad.onnx")
        )
        val items = listOf(
            Item("発話区切りモデル", VAD_URL, models.vad),
            Item("音声認識モデル", "$SENSE_VOICE_BASE/tokens.txt", models.tokens),
            Item("音声認識モデル", "$SENSE_VOICE_BASE/model.int8.onnx", models.senseVoice)
        )
        for (item in items) {
            if (!item.file.exists()) download(item, progress)
        }
        // 古い Vosk モデルが残っていたら容量を空ける
        File(ctx.filesDir, "models").listFiles()
            ?.filter { it.name.startsWith("vosk-model") }
            ?.forEach { it.deleteRecursively() }
        return models
    }

    private fun download(item: Item, progress: (String) -> Unit) {
        val part = File(item.file.path + ".part")
        progress("${item.label}をダウンロード中…")
        val conn = URL(item.url).openConnection() as HttpURLConnection
        conn.connectTimeout = 15000
        conn.readTimeout = 30000
        conn.instanceFollowRedirects = true
        // 途中で切れた場合は続きから再開する
        if (part.length() > 0) conn.setRequestProperty("Range", "bytes=${part.length()}-")
        try {
            val code = conn.responseCode
            if (code == 416) {
                part.delete()
                throw IOException("ダウンロードをやり直します。もう一度スタートしてください")
            }
            val resume = code == 206
            if (code != 200 && !resume) throw IOException("ダウンロード失敗 (HTTP $code)")
            var done = if (resume) part.length() else 0L
            val total = if (conn.contentLengthLong > 0) done + conn.contentLengthLong else -1L
            var lastPct = -1
            conn.inputStream.use { input ->
                FileOutputStream(part, resume).use { out ->
                    val buf = ByteArray(256 * 1024)
                    while (true) {
                        val n = input.read(buf)
                        if (n == -1) break
                        out.write(buf, 0, n)
                        done += n
                        if (total > 0) {
                            val pct = (done * 100 / total).toInt()
                            if (pct != lastPct) {
                                lastPct = pct
                                progress(
                                    "${item.label}をダウンロード中… $pct%" +
                                        "（${done / 1_000_000}/${total / 1_000_000}MB）"
                                )
                            }
                        }
                    }
                }
            }
            if (total > 0 && done != total) throw IOException("ダウンロードが途中で切れました。もう一度スタートしてください")
        } finally {
            conn.disconnect()
        }
        if (!part.renameTo(item.file)) throw IOException("モデルの保存に失敗しました")
    }
}
