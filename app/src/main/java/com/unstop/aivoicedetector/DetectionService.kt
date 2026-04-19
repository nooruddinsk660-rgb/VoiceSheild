package com.unstop.aivoicedetector

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat

class DetectionService : Service() {

    companion object {
        private const val CHANNEL_ID = "detection_channel"
        private const val NOTIFICATION_ID = 1001

        fun start(context: Context) {
            val intent = Intent(context, DetectionService::class.java)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                context.startForegroundService(intent)
            } else {
                context.startService(intent)
            }
        }

        fun stop(context: Context) {
            val intent = Intent(context, DetectionService::class.java)
            context.stopService(intent)
        }
        
        fun updateNotification(context: Context, score: Float, engine: String) {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                val notificationManager = context.getSystemService(NotificationManager::class.java)
                
                val tapIntent = Intent(context, MainActivity::class.java).apply {
                    this.flags = Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP
                }
                val pendingIntent = PendingIntent.getActivity(
                    context, 0, tapIntent,
                    PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
                )

                val text = if (score > 0.6f) "⚠️ AI Voice Detected! (${(score*100).toInt()}%)" else "Monitoring for AI-generated voices... (${(score*100).toInt()}%)"
                val subText = if (engine.isNotEmpty() && score > 0.4f) "Engine: $engine" else ""
                
                val notification = NotificationCompat.Builder(context, CHANNEL_ID)
                    .setContentTitle("🛡️ Shield Active")
                    .setContentText(text)
                    .setSubText(subText)
                    .setSmallIcon(android.R.drawable.ic_lock_lock)
                    .setPriority(NotificationCompat.PRIORITY_LOW)
                    .setOngoing(true)
                    .setContentIntent(pendingIntent)
                    .build()
                
                notificationManager.notify(NOTIFICATION_ID, notification)
            }
        }
    }

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val tapIntent = Intent(this, MainActivity::class.java).apply {
            this.flags = Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP
        }
        val pendingIntent = PendingIntent.getActivity(
            this, 0, tapIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val notification = NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("🛡️ Shield Active")
            .setContentText("Monitoring for AI-generated voices...")
            .setSmallIcon(android.R.drawable.ic_lock_lock)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setOngoing(true)
            .setContentIntent(pendingIntent)
            .build()

        startForeground(NOTIFICATION_ID, notification)
        return START_STICKY
    }

    override fun onBind(intent: Intent?): IBinder? = null

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                "AI Voice Detection",
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = "Shows when real-time voice detection is active"
                setShowBadge(false)
            }
            val manager = getSystemService(NotificationManager::class.java)
            manager.createNotificationChannel(channel)
        }
    }
}
