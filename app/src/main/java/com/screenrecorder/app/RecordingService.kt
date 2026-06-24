package com.screenrecorder.app

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.hardware.display.DisplayManager
import android.hardware.display.VirtualDisplay
import android.media.MediaRecorder
import android.media.projection.MediaProjection
import android.media.projection.MediaProjectionManager
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.os.IBinder
import android.provider.MediaStore
import android.util.DisplayMetrics
import android.view.WindowManager
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class RecordingService : Service() {

    companion object {
        const val ACTION_START = "com.screenrecorder.app.START"
        const val ACTION_PAUSE = "com.screenrecorder.app.PAUSE"
        const val ACTION_RESUME = "com.screenrecorder.app.RESUME"
        const val ACTION_CUT = "com.screenrecorder.app.CUT"
        const val ACTION_STOP = "com.screenrecorder.app.STOP"
        private const val CHANNEL_ID = "screen_recorder"
        private const val NOTIFICATION_ID = 1
    }

    private var mediaProjection: MediaProjection? = null
    private var mediaRecorder: MediaRecorder? = null
    private var virtualDisplay: VirtualDisplay? = null
    private var isPaused = false
    private var segmentCount = 0
    private var audioIndex = AudioMode.NO_AUDIO.ordinal
    private var startTimestamp = ""
    private var currentFileUri: Uri? = null

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_START -> {
                audioIndex = intent.getIntExtra("audio_index", AudioMode.NO_AUDIO.ordinal)
                val resultCode = intent.getIntExtra("result_code", RESULT_CANCELED)
                val data: Intent? = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                    intent.getParcelableExtra("result_data", Intent::class.java)
                } else {
                    @Suppress("DEPRECATION")
                    intent.getParcelableExtra("result_data")
                }
                if (resultCode == RESULT_OK && data != null) {
                    initRecording(resultCode, data)
                } else {
                    stopSelf()
                }
            }
            ACTION_PAUSE -> pauseRecording()
            ACTION_RESUME -> resumeRecording()
            ACTION_CUT -> cutRecording()
            ACTION_STOP -> stopRecording()
        }
        return START_STICKY
    }

    private fun initRecording(resultCode: Int, data: Intent) {
        val manager = getSystemService(Context.MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
        mediaProjection = manager.getMediaProjection(resultCode, data)
        mediaProjection?.registerCallback(object : MediaProjection.Callback() {
            override fun onStop() {
                stopRecording()
            }
        }, null)

        startForeground(NOTIFICATION_ID, createNotification("Starting recording..."))
        startTimestamp = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date())
        segmentCount = 0

        startNewSegment()
        startService(Intent(this, OverlayService::class.java))
        updateNotification("Recording in progress")
    }

    private fun startNewSegment() {
        releaseRecorder()

        mediaRecorder = MediaRecorder()

        val audioMode = AudioMode.entries[audioIndex]
        if (audioMode != AudioMode.NO_AUDIO) {
            try {
                when (audioMode) {
                    AudioMode.DEVICE_AUDIO_ONLY -> {
                        mediaRecorder?.setAudioSource(MediaRecorder.AudioSource.REMOTE_SUBMIX)
                    }
                    AudioMode.MICROPHONE_ONLY -> {
                        mediaRecorder?.setAudioSource(MediaRecorder.AudioSource.MIC)
                    }
                    AudioMode.DEVICE_AND_MIC -> {
                        try {
                            mediaRecorder?.setAudioSource(MediaRecorder.AudioSource.REMOTE_SUBMIX)
                        } catch (_: Exception) {
                            mediaRecorder?.setAudioSource(MediaRecorder.AudioSource.MIC)
                        }
                    }
                    else -> {}
                }
                mediaRecorder?.setAudioEncoder(MediaRecorder.AudioEncoder.AAC)
            } catch (_: Exception) {
                // Audio source not available, continue without audio
            }
        }

        mediaRecorder?.setVideoSource(MediaRecorder.VideoSource.SURFACE)
        mediaRecorder?.setOutputFormat(MediaRecorder.OutputFormat.MPEG_4)
        setupOutputFile()

        val metrics = getDisplayMetrics()
        mediaRecorder?.setVideoSize(metrics.widthPixels, metrics.heightPixels)
        mediaRecorder?.setVideoFrameRate(30)
        mediaRecorder?.setVideoEncoder(MediaRecorder.VideoEncoder.H264)
        mediaRecorder?.setVideoBitRate(4_000_000)
        if (audioMode != AudioMode.NO_AUDIO) {
            try {
                mediaRecorder?.setAudioBitRate(128_000)
                mediaRecorder?.setAudioSamplingRate(44100)
            } catch (_: Exception) {}
        }

        mediaRecorder?.prepare()

        val surface = mediaRecorder?.surface
        virtualDisplay = mediaProjection?.createVirtualDisplay(
            "ScreenRecorder",
            metrics.widthPixels,
            metrics.heightPixels,
            metrics.densityDpi,
            DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR,
            surface,
            null,
            null
        )

        mediaRecorder?.start()
        isPaused = false
    }

    private fun setupOutputFile() {
        val baseName = "ScreenRecording_$startTimestamp"
        val fileName =
            if (segmentCount == 0) "$baseName.mp4" else "${baseName}_part$segmentCount.mp4"

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            val values = ContentValues().apply {
                put(MediaStore.Video.Media.DISPLAY_NAME, fileName)
                put(MediaStore.Video.Media.MIME_TYPE, "video/mp4")
                put(MediaStore.Video.Media.RELATIVE_PATH, Environment.DIRECTORY_MOVIES)
            }
            val uri = contentResolver.insert(
                MediaStore.Video.Media.EXTERNAL_CONTENT_URI,
                values
            )
            if (uri != null) {
                currentFileUri = uri
                contentResolver.openFileDescriptor(uri, "w")?.use { fd ->
                    mediaRecorder?.setOutputFile(fd.fileDescriptor)
                }
            }
        } else {
            @Suppress("DEPRECATION")
            val dir = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_MOVIES)
            dir.mkdirs()
            val file = File(dir, fileName)
            mediaRecorder?.setOutputFile(file.absolutePath)
        }
    }

    private fun pauseRecording() {
        if (!isPaused && Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
            try {
                mediaRecorder?.pause()
                isPaused = true
                updateNotification("Recording paused")
            } catch (_: Exception) {}
        }
    }

    private fun resumeRecording() {
        if (isPaused && Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
            try {
                mediaRecorder?.resume()
                isPaused = false
                updateNotification("Recording in progress")
            } catch (_: Exception) {}
        }
    }

    private fun cutRecording() {
        try {
            mediaRecorder?.stop()
        } catch (_: Exception) {}
        releaseRecorder()
        segmentCount++
        startNewSegment()
    }

    private fun stopRecording() {
        try {
            mediaRecorder?.stop()
        } catch (_: Exception) {}
        releaseRecorder()
        mediaProjection?.stop()
        mediaProjection = null

        stopService(Intent(this, OverlayService::class.java))
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    private fun releaseRecorder() {
        try {
            virtualDisplay?.release()
        } catch (_: Exception) {}
        virtualDisplay = null
        try {
            mediaRecorder?.release()
        } catch (_: Exception) {}
        mediaRecorder = null
    }

    private fun getDisplayMetrics(): DisplayMetrics {
        val metrics = DisplayMetrics()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            val wm = getSystemService(WindowManager::class.java)
            val bounds = wm.maximumWindowMetrics.bounds
            metrics.widthPixels = bounds.width()
            metrics.heightPixels = bounds.height()
            metrics.densityDpi = resources.configuration.densityDpi
        } else {
            @Suppress("DEPRECATION")
            val wm = getSystemService(WINDOW_SERVICE) as WindowManager
            wm.defaultDisplay.getRealMetrics(metrics)
        }
        return metrics
    }

    private fun createNotification(text: String): Notification {
        val stopIntent = PendingIntent.getService(
            this,
            0,
            Intent(this, RecordingService::class.java).apply { action = ACTION_STOP },
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        return Notification.Builder(this, CHANNEL_ID)
            .setContentTitle("Screen Recorder")
            .setContentText(text)
            .setSmallIcon(android.R.drawable.ic_menu_camera)
            .addAction(android.R.drawable.ic_media_pause, "Stop", stopIntent)
            .setOngoing(true)
            .build()
    }

    private fun updateNotification(text: String) {
        val manager = getSystemService(NotificationManager::class.java)
        manager.notify(NOTIFICATION_ID, createNotification(text))
    }

    private fun createNotificationChannel() {
        val channel = NotificationChannel(
            CHANNEL_ID,
            "Screen Recorder",
            NotificationManager.IMPORTANCE_LOW
        )
        val manager = getSystemService(NotificationManager::class.java)
        manager.createNotificationChannel(channel)
    }

    override fun onBind(intent: Intent?): IBinder? = null
}
