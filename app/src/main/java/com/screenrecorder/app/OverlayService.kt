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
import android.widget.FrameLayout
import android.widget.ImageButton
import android.widget.LinearLayout
import kotlin.math.sqrt

class OverlayService : Service() {

    private lateinit var windowManager: WindowManager
    private var overlayView: FrameLayout? = null
    private var params: WindowManager.LayoutParams? = null
    private var isExpanded = false
    private var isPaused = false

    private var initialX = 0
    private var initialY = 0
    private var initialTouchX = 0f
    private var initialTouchY = 0f
    private var isDragging = false

    private var mainButton: ImageButton? = null
    private var actionsLayout: LinearLayout? = null
    private var pauseButton: ImageButton? = null

    override fun onCreate() {
        super.onCreate()
        windowManager = getSystemService(WINDOW_SERVICE) as WindowManager
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (overlayView == null) {
            showOverlay()
        }
        return START_STICKY
    }

    private fun showOverlay() {
        overlayView = FrameLayout(this)

        mainButton = createOverlayButton().apply {
            layoutParams = FrameLayout.LayoutParams(dp(52), dp(52)).apply {
                gravity = Gravity.TOP or Gravity.CENTER_HORIZONTAL
            }
        }

        actionsLayout = createActionsLayout().apply {
            visibility = View.GONE
            layoutParams = FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.WRAP_CONTENT,
                FrameLayout.LayoutParams.WRAP_CONTENT
            ).apply {
                gravity = Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL
                topMargin = dp(8)
            }
        }

        overlayView?.addView(mainButton)
        overlayView?.addView(actionsLayout)

        params = WindowManager.LayoutParams(
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            x = dp(16)
            y = dp(100)
        }

        windowManager.addView(overlayView, params)
        setupMainButtonTouch()
    }

    private fun createOverlayButton(): ImageButton {
        val button = ImageButton(this)
        button.setImageResource(R.drawable.ic_overlay_main)
        button.scaleType = ImageButton.ScaleType.CENTER_INSIDE
        button.setPadding(dp(14), dp(14), dp(14), dp(14))
        button.setImageTintList(
            android.content.res.ColorStateList.valueOf(Color.WHITE)
        )
        button.background = createCircleDrawable(Color.parseColor("#E53935"))
        button.setOnClickListener { toggleExpanded() }
        return button
    }

    private fun createActionsLayout(): LinearLayout {
        val layout = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding(dp(4), dp(4), dp(4), dp(4))
            background = createRoundedDrawable(Color.parseColor("#99000000"))
        }

        pauseButton = createActionButton(R.drawable.ic_pause) {
            if (isPaused) {
                sendAction(RecordingService.ACTION_RESUME)
                isPaused = false
                pauseButton?.setImageResource(R.drawable.ic_pause)
            } else {
                sendAction(RecordingService.ACTION_PAUSE)
                isPaused = true
                pauseButton?.setImageResource(R.drawable.ic_play)
            }
            collapse()
        }

        val cutButton = createActionButton(R.drawable.ic_cut) {
            sendAction(RecordingService.ACTION_CUT)
            collapse()
        }

        val stopButton = createActionButton(R.drawable.ic_stop) {
            sendAction(RecordingService.ACTION_STOP)
            stopSelf()
        }

        layout.addView(pauseButton)
        layout.addView(cutButton)
        layout.addView(stopButton)

        return layout
    }

    private fun createActionButton(
        iconRes: Int,
        onClick: () -> Unit
    ): ImageButton {
        val button = ImageButton(this)
        button.setImageResource(iconRes)
        button.scaleType = ImageButton.ScaleType.CENTER_INSIDE
        button.setPadding(dp(10), dp(10), dp(10), dp(10))
        button.setImageTintList(
            android.content.res.ColorStateList.valueOf(Color.WHITE)
        )
        button.background = createCircleDrawable(Color.parseColor("#555555"))
        val size = dp(44)
        button.layoutParams = LinearLayout.LayoutParams(size, size).apply {
            setMargins(dp(4), dp(4), dp(4), dp(4))
        }
        button.setOnClickListener { onClick() }
        return button
    }

    private fun setupMainButtonTouch() {
        mainButton?.setOnTouchListener { _, event ->
            when (event.action) {
                MotionEvent.ACTION_DOWN -> {
                    isDragging = false
                    initialX = params?.x ?: 0
                    initialY = params?.y ?: 0
                    initialTouchX = event.rawX
                    initialTouchY = event.rawY
                    true
                }
                MotionEvent.ACTION_MOVE -> {
                    val dx = event.rawX - initialTouchX
                    val dy = event.rawY - initialTouchY
                    val distance = sqrt(dx * dx + dy * dy)

                    if (distance > TOUCH_SLOP) {
                        isDragging = true
                        params?.x = (initialX + dx).toInt()
                        params?.y = (initialY + dy).toInt()
                        overlayView?.let {
                            windowManager.updateViewLayout(it, params)
                        }
                    }
                    true
                }
                MotionEvent.ACTION_UP -> {
                    if (!isDragging) {
                        mainButton?.performClick()
                    }
                    true
                }
            }
        }
    }

    private fun toggleExpanded() {
        if (isExpanded) collapse() else expand()
    }

    private fun expand() {
        isExpanded = true
        actionsLayout?.visibility = View.VISIBLE
    }

    private fun collapse() {
        isExpanded = false
        actionsLayout?.visibility = View.GONE
    }

    private fun sendAction(action: String) {
        val intent = Intent(this, RecordingService::class.java).apply {
            this.action = action
        }
        startService(intent)
    }

    override fun onDestroy() {
        overlayView?.let {
            try {
                windowManager.removeView(it)
            } catch (_: Exception) {}
        }
        overlayView = null
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    private fun dp(value: Int): Int {
        return (value * resources.displayMetrics.density).toInt()
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
            setColor(color)
            cornerRadius = dp(12).toFloat()
        }
    }

    companion object {
        private const val TOUCH_SLOP = 10f
    }
}
