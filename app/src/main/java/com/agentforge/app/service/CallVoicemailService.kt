package com.agentforge.app.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Intent
import android.media.MediaRecorder
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat
import com.agentforge.app.service.AgentAccessibilityService
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class CallVoicemailService : Service() {

    private var recorder: MediaRecorder? = null
    private var isRecording = false

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val action = intent?.action
        val caller = intent?.getStringExtra("CALLER_NUMBER") ?: "Unknown"

        if (action == "START_RECORDING" && !isRecording) {
            startForegroundNotification(caller)
            startCallRecording(caller)
        } else if (action == "STOP_RECORDING" && isRecording) {
            stopCallRecording()
            stopSelf()
        }

        return START_NOT_STICKY
    }

    private fun startForegroundNotification(caller: String) {
        val channelId = "voicemail_channel"
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(channelId, "Voicemail Recorder", NotificationManager.IMPORTANCE_LOW)
            getSystemService(NotificationManager::class.java)?.createNotificationChannel(channel)
        }

        val notification: Notification = NotificationCompat.Builder(this, channelId)
            .setContentTitle("Live Voicemail Recording")
            .setContentText("Recording caller audio from: $caller")
            .setSmallIcon(android.R.drawable.ic_btn_speak_now)
            .build()

        startForeground(202, notification)
        AgentAccessibilityService.instance?.showIsland("Voicemail: Recording $caller")
    }

    private fun startCallRecording(caller: String) {
        try {
            val audioDir = File(filesDir, "voicemails").apply { if (!exists()) mkdirs() }
            val time = SimpleDateFormat("ddMMM_HHmm", Locale.getDefault()).format(Date())
            val cleanCaller = caller.replace("+", "").replace(" ", "")
            val file = File(audioDir, "Caller_${cleanCaller}_$time.m4a")

            val mr = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                MediaRecorder(this)
            } else {
                @Suppress("DEPRECATION")
                MediaRecorder()
            }

            // Android me phone call audio record karne ke liye VOICE_COMMUNICATION ya MIC use hota hai
            mr.apply {
                setAudioSource(MediaRecorder.AudioSource.VOICE_COMMUNICATION)
                setOutputFormat(MediaRecorder.OutputFormat.MPEG_4)
                setAudioEncoder(MediaRecorder.AudioEncoder.AAC)
                setOutputFile(file.absolutePath)
                prepare()
                start()
            }
            recorder = mr
            isRecording = true
        } catch (_: Exception) {
            // Agar carrier lock ki wajah se VOICE_COMMUNICATION fail ho toh MIC fallback
            try {
                val audioDir = File(filesDir, "voicemails")
                val time = SimpleDateFormat("ddMMM_HHmm", Locale.getDefault()).format(Date())
                val file = File(audioDir, "Caller_${caller}_$time.m4a")
                val fallbackRecorder = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                    MediaRecorder(this)
                } else {
                    @Suppress("DEPRECATION")
                    MediaRecorder()
                }
                fallbackRecorder.apply {
                    setAudioSource(MediaRecorder.AudioSource.MIC)
                    setOutputFormat(MediaRecorder.OutputFormat.MPEG_4)
                    setAudioEncoder(MediaRecorder.AudioEncoder.AAC)
                    setOutputFile(file.absolutePath)
                    prepare()
                    start()
                }
                recorder = fallbackRecorder
                isRecording = true
            } catch (_: Exception) {
                isRecording = false
            }
        }
    }

    private fun stopCallRecording() {
        try {
            recorder?.apply {
                stop()
                release()
            }
        } catch (_: Exception) {}
        recorder = null
        isRecording = false
        AgentAccessibilityService.instance?.showIsland("Voicemail Saved")
    }

    override fun onDestroy() {
        if (isRecording) stopCallRecording()
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null
}
