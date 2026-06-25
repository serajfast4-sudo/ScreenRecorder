package com.screenrecorder.app

import android.app.Service
import android.content.Intent
import android.graphics.Color
import android.graphics.PixelFormat
import android.graphics.drawable.GradientDrawable
import android.os.IBinder
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.widget.ImageButton
import android.widget.ImageView
import android.widget.LinearLayout

class OverlayService : Service() {

    private lateinit var windowManager: WindowManager
    private lateinit var overlayView: View
    private lateinit var controlsView: View
    private var isControlsVisible = false

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        windowManager = getSystemService(WINDOW_SERVICE) as WindowManager
        createOverlay()
    }

    override fun onDestroy() {
        super.onDestroy()
        if (::overlayView.isInitialized) windowManager.removeView(overlayView)
        if (::controlsView.isInitialized) windowManager.removeView(controlsView)
    }

    private fun createOverlay() {
        val button = createOverlayButton()
        overlayView = button

        val params = WindowManager.LayoutParams(
            dp(64), dp(64),
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            x = 100
            y = 300
        }

        var lastX = 0
        var lastY = 0
        var startX = 0
        var startY = 0
        var moved = false

        button.setOnTouchListener { v, event ->
            when (event.action) {
                MotionEvent.ACTION_DOWN -> {
                    lastX = event.rawX.toInt()
                    lastY = event.rawY.toInt()
                    startX = lastX
                    startY = lastY
                    moved = false
                    true
                }
                MotionEvent.ACTION_MOVE -> {
                    val dx = event.rawX.toInt() - lastX
                    val dy = event.rawY.toInt() - lastY
                    if (Math.abs(dx) > 5 || Math.abs(dy) > 5) moved = true
                    params.x += dx
                    params.y += dy
                    lastX = event.rawX.toInt()
                    lastY = event.rawY.toInt()
                    windowManager.updateViewLayout(overlayView, params)
                    true
                }
                MotionEvent.ACTION_UP -> {
                    if (!moved) toggleControls(params)
                    true
                }
                else -> false
            }
        }

        windowManager.addView(overlayView, params)
    }

    private fun toggleControls(anchorParams: WindowManager.LayoutParams) {
        if (isControlsVisible) {
            if (::controlsView.isInitialized) windowManager.removeView(controlsView)
            isControlsVisible = false
        } else {
            showControls(anchorParams)
            isControlsVisible = true
        }
    }

    private fun showControls(anchorParams: WindowManager.LayoutParams) {
        val layout = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            background = createRoundedDrawable(Color.parseColor("#CC000000"))
            setPadding(dp(8), dp(8), dp(8), dp(8))
        }

        val pauseBtn = createControlButton("⏸")
        val cutBtn = createControlButton("✂")
        val stopBtn = createControlButton("⏹")

        pauseBtn.setOnClickListener {
            sendBroadcast(Intent(RecordingService.ACTION_PAUSE))
        }
        cutBtn.setOnClickListener {
            sendBroadcast(Intent(RecordingService.ACTION_CUT))
        }
        stopBtn.setOnClickListener {
            sendBroadcast(Intent(RecordingService.ACTION_STOP))
            stopSelf()
        }

        layout.addView(pauseBtn)
        layout.addView(cutBtn)
        layout.addView(stopBtn)

        controlsView = layout

        val params = WindowManager.LayoutParams(
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            x = anchorParams.x
            y = anchorParams.y + dp(70)
        }

        windowManager.addView(controlsView, params)
    }

    private fun createOverlayButton(): ImageButton {
        return ImageButton(this).apply {
            setImageResource(R.drawable.ic_overlay_main)
            scaleType = ImageView.ScaleType.CENTER_INSIDE
            setPadding(dp(14), dp(14), dp(14), dp(14))
            setImageTintList(android.content.res.ColorStateList.valueOf(Color.WHITE))
            background = createCircleDrawable(Color.parseColor("#E53935"))
        }
    }

    private fun createControlButton(emoji: String): android.widget.TextView {
        return android.widget.TextView(this).apply {
            text = emoji
            textSize = 22f
            setTextColor(Color.WHITE)
            setPadding(dp(12), dp(8), dp(12), dp(8))
            gravity = Gravity.CENTER
        }
    }

    private fun createCircleDrawable(color: Int): GradientDrawable {
        return GradientDrawable().apply {
            shape = GradientDrawable.OVAL
            setColor(color)
        }
    }

    private fun createRoundedDrawable(color: Int): GradientDrawable {
        return GradientDrawable().apply {
            shape = GradientDrawable.RECTANGLE
            cornerRadius = dp(16).toFloat()
            setColor(color)
        }
    }

    private fun dp(value: Int): Int {
        return (value * resources.displayMetrics.density).toInt()
    }
}
