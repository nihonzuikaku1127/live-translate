package com.example.livetranslate

import android.Manifest
import android.app.Activity
import android.content.Intent
import android.content.pm.PackageManager
import android.media.projection.MediaProjectionConfig
import android.media.projection.MediaProjectionManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.widget.Button
import android.widget.RadioButton
import android.widget.TextView
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat

class MainActivity : AppCompatActivity() {

    private lateinit var status: TextView
    private val projectionManager by lazy { getSystemService(MediaProjectionManager::class.java) }

    private val permissionLauncher =
        registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { result ->
            if (result[Manifest.permission.RECORD_AUDIO] == true || hasAudioPermission()) {
                requestCapture()
            } else {
                status.text = "「音声の録音」を許可しないと、動画の音声を取得できません"
            }
        }

    private val captureLauncher =
        registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
            val data = result.data
            if (result.resultCode == Activity.RESULT_OK && data != null) {
                val intent = Intent(this, TranslateService::class.java)
                    .setAction(TranslateService.ACTION_START)
                    .putExtra(TranslateService.EXTRA_RESULT_CODE, result.resultCode)
                    .putExtra(TranslateService.EXTRA_RESULT_DATA, data)
                    .putExtra(TranslateService.EXTRA_LANG, selectedLang())
                ContextCompat.startForegroundService(this, intent)
                status.text = "起動しました。画面下の字幕欄に準備状況が表示されます。準備完了と出たら動画を再生してください。"
            } else {
                status.text = "画面の録画（キャプチャ）が許可されませんでした"
            }
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)
        status = findViewById(R.id.statusText)

        findViewById<Button>(R.id.startButton).setOnClickListener { start() }
        findViewById<Button>(R.id.stopButton).setOnClickListener {
            startService(Intent(this, TranslateService::class.java).setAction(TranslateService.ACTION_STOP))
            status.text = "停止しました"
        }
    }

    private fun selectedLang(): String =
        if (findViewById<RadioButton>(R.id.radioZh).isChecked) "zh" else "en"

    private fun start() {
        if (!Settings.canDrawOverlays(this)) {
            status.text = "「他のアプリの上に重ねて表示」をオンにしてから、この画面に戻ってもう一度スタートを押してください"
            startActivity(
                Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:$packageName"))
            )
            return
        }
        val perms = mutableListOf(Manifest.permission.RECORD_AUDIO)
        if (Build.VERSION.SDK_INT >= 33) perms += Manifest.permission.POST_NOTIFICATIONS
        permissionLauncher.launch(perms.toTypedArray())
    }

    private fun hasAudioPermission() =
        ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) ==
            PackageManager.PERMISSION_GRANTED

    private fun requestCapture() {
        val intent = if (Build.VERSION.SDK_INT >= 34) {
            // 「画面全体」を共有させる（アプリ単体だと音声が取れない場合があるため）
            projectionManager.createScreenCaptureIntent(
                MediaProjectionConfig.createConfigForDefaultDisplay()
            )
        } else {
            projectionManager.createScreenCaptureIntent()
        }
        captureLauncher.launch(intent)
    }
}
