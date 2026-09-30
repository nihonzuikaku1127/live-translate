package com.example.livetranslate

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Color
import android.graphics.PixelFormat
import android.view.Gravity
import android.view.MotionEvent
import android.view.WindowManager
import android.widget.LinearLayout
import android.widget.TextView

/** 他のアプリの上に重ねて表示する字幕ウィンドウ */
class SubtitleOverlay(private val ctx: Context) {

    private val wm = ctx.getSystemService(WindowManager::class.java)
    private val lines = ArrayDeque<String>()
    private var root: LinearLayout? = null
    private lateinit var originalView: TextView
    private lateinit var translatedView: TextView
    private lateinit var params: WindowManager.LayoutParams

    @SuppressLint("ClickableViewAccessibility")
    fun show() {
        val dp = ctx.resources.displayMetrics.density
        originalView = TextView(ctx).apply {
            setTextColor(0xFFBBBBBB.toInt())
            textSize = 12f
        }
        translatedView = TextView(ctx).apply {
            setTextColor(Color.WHITE)
            textSize = 18f
        }
        val layout = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(0xB0000000.toInt())
            val p = (10 * dp).toInt()
            setPadding(p, p, p, p)
            addView(originalView)
            addView(translatedView)
        }
        params = WindowManager.LayoutParams(
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.BOTTOM
            y = (80 * dp).toInt()
        }

        // 指でドラッグして上下に移動できる
        var startY = 0
        var touchY = 0f
        layout.setOnTouchListener { _, ev ->
            when (ev.action) {
                MotionEvent.ACTION_DOWN -> { startY = params.y; touchY = ev.rawY; true }
                MotionEvent.ACTION_MOVE -> {
                    params.y = startY - (ev.rawY - touchY).toInt()
                    wm.updateViewLayout(layout, params)
                    true
                }
                else -> false
            }
        }
        wm.addView(layout, params)
        root = layout
    }

    fun setOriginal(text: String) {
        if (root == null) return
        originalView.text = text
    }

    fun addTranslation(text: String) {
        if (root == null) return
        lines.addLast(text)
        while (lines.size > 2) lines.removeFirst()
        translatedView.text = lines.joinToString("\n")
    }

    fun hide() {
        root?.let { runCatching { wm.removeView(it) } }
        root = null
    }
}
