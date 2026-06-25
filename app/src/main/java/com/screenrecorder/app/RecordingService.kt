package com.screenrecorder.app

import android.app.Activity
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
import android.os.IBinder
import android.provider.MediaStore
import android.util.DisplayMetrics
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
    private var audioIndex = AudioMode.NO_AUDIO.ordinal
    private var startTimestamp = ""

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_START -> {
                audioIndex = intent.getIntExtra("audio_index", AudioMode.NO_AUDIO.ordinal)
                val resultCode = intent.getIntExtra("result_code", Activity.RESULT_CANCELED)
                val data: Intent? = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                    intent.getParcelableExtra("result_data", Intent::class.java)
                } else {
                    @Suppress("DEPRECATION")
                    intent.getParcelableExtra("result_data")
                }
                if (resultCode == Activity.RESULT_OK && data != null) {
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

        startForeground(NOTIFICATION_ID, createNotification("Recording in progress"))
        startTimestamp = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date())

        startNewSegment()
        startService(Intent(this, OverlayService::class.java))
    }

    private fun startNewSegment() {
        releaseRecorder()

        mediaRecorder = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            MediaRecorder(this)
        } else {
            @Suppress("DEPRECATION")
            MediaRecorder()
        }

        val audioMode = AudioMode.entries[audioIndex]
        if (audioMode != AudioMode.NO_AUDIO) {
            try {
                when (audioMode) {
                    AudioMode.DEVICE_AUDIO_ONLY ->
                        mediaRecorder?.setAudioSource(MediaRecorder.AudioSource.REMOTE_SUBMIX)
                    AudioMode.MICROPHONE_ONLY ->
                        mediaRecorder?.setAudioSource(MediaRecorder.AudioSource.MIC)
                    AudioMode.DEVICE_AND_MIC -> {
                        try {
                            mediaRecorder?.setAudioSource(MediaRecorder.AudioSource.REMOTE_SUBMIX)
                        } catch (e: Exception) {
                            mediaRecorder?.setAudioSource(MediaRecorder.AudioSource.MIC)
                        }
                    }
                    else -> {}
                }
                mediaRecorder?.setAudioEncoder(MediaRecorder.AudioEncoder.AAC)
                mediaRecorder?.setAudioEncodingBitRate(128_000)
                mediaRecorder?.setAudioSamplingRate(44100)
            } catch (e: Exception) {
                // Audio source not available
            }
        }

        mediaRecorder?.setVideoSource(MediaRecorder.VideoSource.SURFACE)
        mediaRecorder?.setOutputFormat(MediaRecorder.OutputFormat.MPEG_4)

        setupOutputFile()

        val metrics = DisplayMetrics()
        val wm = getSystemService(Context.WINDOW_SERVICE) as android.view.WindowManager
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            val display = display
            display?.getRealMetrics(metrics)
        } else {
            @Suppress("DEPRECATION")
            wm.defaultDisplay.getRealMetrics(metrics)
        }

        mediaRecorder?.setVideoSize(metrics.widthPixels, metrics.heightPixels)
        mediaRecorder?.setVideoFrameRate(30)
        mediaRecorder?.setVideoEncoder(MediaRecorder.VideoEncoder.H264)
        mediaRecorder?.setVideoEncodingBitRate(4_000_000)

        mediaRecorder?.prepare()

        virtualDisplay = mediaProjection?.createVirtualDisplay(
            "ScreenRecorder",
            metrics.widthPixels, metrics.heightPixels, metrics.densityDpi,
            DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR,
            mediaRecorder?.surface, null, null
        )

        mediaRecorder?.start()
    }

    private fun setupOutputFile() {
        val fileName = "ScreenRecorder_${startTimestamp}.mp4"
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            val values = ContentValues().apply {
                put(MediaStore.Video.Media.DISPLAY_NAME, fileName)
                put(MediaStore.Video.Media.MIME_TYPE, "video/mp4")
                put(MediaStore.Video.Media.RELATIVE_PATH, "Movies/ScreenRecorder")
            }
            val uri = contentResolver.insert(MediaStore.Video.Media.EXTERNAL_CONTENT_URI, values)
            val pfd = uri?.let { contentResolver.openFileDescriptor(it, "w") }
            pfd?.let { mediaRecorder?.setOutputFile(it.fileDescriptor) }
        } else {
            val dir = getExternalFilesDir("Movies")
            dir?.mkdirs()
            mediaRecorder?.setOutputFile("${dir?.absolutePath}/$fileName")
        }
    }

    private fun pauseRecording() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N && !isPaused) {
            mediaRecorder?.pause()
            isPaused = true
            updateNotification("Recording paused")
        }
    }

    private fun resumeRecording() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N && isPaused) {
            mediaRecorder?.resume()
            isPaused = false
            updateNotification("Recording in progress")
        }
    }

    private fun cutRecording() {
        startNewSegment()
    }

    private fun stopRecording() {
        try {
            mediaRecorder?.stop()
        } catch (e: Exception) { }
        releaseRecorder()
        mediaProjection?.stop()
        mediaProjection = null
        stopService(Intent(this, OverlayService::class.java))
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    private fun releaseRecorder() {
        virtualDisplay?.release()
        virtualDisplay = null
        try {
            mediaRecorder?.release()
        } catch (e: Exception) { }
        mediaRecorder = null
    }

    private fun createNotificationChannel() {
        val channel = NotificationChannel(CHANNEL_ID, "Screen Recorder", NotificationManager.IMPORTANCE_LOW)
        val manager = getSystemService(NotificationManager::class.java)
        manager.createNotificationChannel(channel)
    }

    private fun createNotification(text: String): Notification {
        val intent = PendingIntent.getActivity(this, 0, Intent(this, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE)
        return Notification.Builder(this, CHANNEL_ID)
            .setContentTitle("Screen Recorder")
            .setContentText(text)
            .setSmallIcon(android.R.drawable.ic_media_play)
            .setContentIntent(intent)
            .build()
    }

    private fun updateNotification(text: String) {
        val manager = getSystemService(NotificationManager::class.java)
        manager.notify(NOTIFICATION_ID, createNotification(text))
    }
}
