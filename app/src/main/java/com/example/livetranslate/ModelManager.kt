package com.example.livetranslate

import android.content.Context
import java.io.BufferedInputStream
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.util.zip.ZipInputStream

/** 音声認識モデル（Vosk）を初回だけダウンロードして展開する */
object ModelManager {

    private val MODELS = mapOf(
        "en" to "vosk-model-small-en-us-0.15",
        "zh" to "vosk-model-small-cn-0.22"
    )

    fun ensureModel(ctx: Context, lang: String, progress: (String) -> Unit): File {
        val name = MODELS.getValue(lang)
        val root = File(ctx.filesDir, "models")
        val dir = File(root, name)
        if (File(dir, ".complete").exists()) return dir

        dir.deleteRecursively()
        root.mkdirs()

        progress("音声認識モデルをダウンロード中…")
        val tmp = File(ctx.cacheDir, "$name.zip")
        val conn = URL("https://alphacephei.com/vosk/models/$name.zip").openConnection() as HttpURLConnection
        conn.connectTimeout = 15000
        conn.readTimeout = 30000
        conn.instanceFollowRedirects = true
        try {
            if (conn.responseCode != 200) throw IOException("ダウンロード失敗 (HTTP ${conn.responseCode})")
            val total = conn.contentLengthLong
            var done = 0L
            var lastPct = -1
            conn.inputStream.use { input ->
                FileOutputStream(tmp).use { out ->
                    val buf = ByteArray(64 * 1024)
                    while (true) {
                        val n = input.read(buf)
                        if (n == -1) break
                        out.write(buf, 0, n)
                        done += n
                        if (total > 0) {
                            val pct = (done * 100 / total).toInt()
                            if (pct != lastPct) {
                                lastPct = pct
                                progress("音声認識モデルをダウンロード中… $pct%")
                            }
                        }
                    }
                }
            }
        } finally {
            conn.disconnect()
        }

        progress("モデルを展開中…")
        val rootPath = root.canonicalPath + File.separator
        ZipInputStream(BufferedInputStream(FileInputStream(tmp))).use { zis ->
            var entry = zis.nextEntry
            while (entry != null) {
                val outFile = File(root, entry.name)
                if (!outFile.canonicalPath.startsWith(rootPath)) throw IOException("不正なzipファイルです")
                if (entry.isDirectory) {
                    outFile.mkdirs()
                } else {
                    outFile.parentFile?.mkdirs()
                    FileOutputStream(outFile).use { zis.copyTo(it) }
                }
                entry = zis.nextEntry
            }
        }
        tmp.delete()
        if (!dir.isDirectory) throw IOException("モデルの展開に失敗しました")
        File(dir, ".complete").createNewFile()
        return dir
    }
}
