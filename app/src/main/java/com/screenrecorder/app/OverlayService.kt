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
    private var controlsView: View? = null
    private var isControlsVisible = false
    private val overlayParams = WindowManager.LayoutParams()

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        windowManager = getSystemService(WINDOW_SERVICE) as WindowManager
        createOverlay()
    }

    override fun onDestroy() {
        if (::overlayView.isInitialized) {
            try { windowManager.removeView(overlayView) } catch (_: Exception) {}
        }
        controlsView?.let {
            try { windowManager.removeView(it) } catch (_: Exception) {}
        }
        super.onDestroy()
    }

    private fun createOverlay() {
        val button = createOverlayButton()
        overlayView = button

        with(overlayParams) {
            type = WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
            flags = WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                    WindowManager.LayoutParams.FLAG_SECURE
            format = PixelFormat.TRANSLUCENT
            width = dp(56)
            height = dp(56)
            gravity = Gravity.TOP or Gravity.START
            x = dp(16)
            y = dp(160)
        }

        var lastX = 0f
        var lastY = 0f
        var moved = false

        button.setOnTouchListener { _, event ->
            when (event.action) {
                MotionEvent.ACTION_DOWN -> {
                    lastX = event.rawX
                    lastY = event.rawY
                    moved = false
                    true
                }
                MotionEvent.ACTION_MOVE -> {
                    val dx = event.rawX - lastX
                    val dy = event.rawY - lastY
                    if (kotlin.math.abs(dx) > 3 || kotlin.math.abs(dy) > 3) moved = true
                    overlayParams.x += dx.toInt()
                    overlayParams.y += dy.toInt()
                    lastX = event.rawX
                    lastY = event.rawY
                    windowManager.updateViewLayout(overlayView, overlayParams)
                    true
                }
                MotionEvent.ACTION_UP -> {
                    if (!moved) toggleControls()
                    true
                }
                else -> false
            }
        }

        windowManager.addView(overlayView, overlayParams)
    }

    private fun toggleControls() {
        if (isControlsVisible) {
            controlsView?.let {
                try { windowManager.removeView(it) } catch (_: Exception) {}
            }
            controlsView = null
            isControlsVisible = false
        } else {
            showControls()
            isControlsVisible = true
        }
    }

    private fun showControls() {
        val layout = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            background = GradientDrawable().apply {
                shape = GradientDrawable.RECTANGLE
                cornerRadius = dp(20).toFloat()
                setColor(Color.parseColor("#DD1A1A2E"))
                setStroke(dp(1), Color.parseColor("#333355"))
            }
            setPadding(dp(8), dp(6), dp(8), dp(6))
        }

        val pauseBtn = ImageButton(this).apply {
            setImageResource(R.drawable.ic_pause)
            scaleType = ImageView.ScaleType.CENTER_INSIDE
            setPadding(dp(10), dp(8), dp(10), dp(8))
            background = GradientDrawable().apply {
                shape = GradientDrawable.OVAL
                setColor(Color.parseColor("#FFB300"))
            }
            layoutParams = LinearLayout.LayoutParams(dp(44), dp(44)).apply {
                marginEnd = dp(8)
            }
            setOnClickListener {
                startService(Intent(this@OverlayService, RecordingService::class.java).apply {
                    action = RecordingService.ACTION_PAUSE
                })
                toggleControls()
            }
        }

        val cutBtn = ImageButton(this).apply {
            setImageResource(R.drawable.ic_cut)
            scaleType = ImageView.ScaleType.CENTER_INSIDE
            setPadding(dp(10), dp(8), dp(10), dp(8))
            background = GradientDrawable().apply {
                shape = GradientDrawable.OVAL
                setColor(Color.parseColor("#43A047"))
            }
            layoutParams = LinearLayout.LayoutParams(dp(44), dp(44)).apply {
                marginEnd = dp(8)
            }
            setOnClickListener {
                startService(Intent(this@OverlayService, RecordingService::class.java).apply {
                    action = RecordingService.ACTION_CUT
                })
                toggleControls()
            }
        }

        val stopBtn = ImageButton(this).apply {
            setImageResource(R.drawable.ic_stop)
            scaleType = ImageView.ScaleType.CENTER_INSIDE
            setPadding(dp(10), dp(8), dp(10), dp(8))
            background = GradientDrawable().apply {
                shape = GradientDrawable.OVAL
                setColor(Color.parseColor("#E53935"))
            }
            layoutParams = LinearLayout.LayoutParams(dp(44), dp(44))
            setOnClickListener {
                startService(Intent(this@OverlayService, RecordingService::class.java).apply {
                    action = RecordingService.ACTION_STOP
                })
            }
        }

        layout.addView(pauseBtn)
        layout.addView(cutBtn)
        layout.addView(stopBtn)

        controlsView = layout

        val params = WindowManager.LayoutParams(
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                    WindowManager.LayoutParams.FLAG_SECURE,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            x = overlayParams.x
            y = overlayParams.y + dp(64)
        }

        try {
            windowManager.addView(layout, params)
        } catch (_: Exception) {}
    }

    private fun createOverlayButton(): ImageButton {
        return ImageButton(this).apply {
            setImageResource(R.drawable.ic_overlay_main)
            scaleType = ImageView.ScaleType.CENTER_INSIDE
            setPadding(dp(12), dp(12), dp(12), dp(12))
            background = GradientDrawable().apply {
                shape = GradientDrawable.OVAL
                setColor(Color.parseColor("#E53935"))
                setStroke(dp(2), Color.parseColor("#FFFFFF"))
            }
        }
    }

    private fun dp(value: Int): Int {
        return (value * resources.displayMetrics.density).toInt()
    }
}
