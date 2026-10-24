package com.ytdstudio.app

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.IBinder
import android.os.PowerManager

/** Keeps the process alive while downloads run in the background. */
class DownloadService : Service() {
    private var wake: PowerManager.WakeLock? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        ensureChannel(this)
        startForeground(
            NOTIF_ID, build(this, "Downloading…"),
            ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC
        )
        running = true
        if (wake == null) {
            val pm = getSystemService(Context.POWER_SERVICE) as PowerManager
            wake = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "ytdstudio:dl").apply { acquire(6 * 60 * 60 * 1000L) }
        }
        return START_NOT_STICKY
    }

    override fun onDestroy() {
        running = false
        wake?.let { if (it.isHeld) it.release() }
        wake = null
        super.onDestroy()
    }

    companion object {
        const val CHANNEL = "downloads"
        const val NOTIF_ID = 7
        @Volatile var running = false

        fun ensureChannel(ctx: Context) {
            val nm = ctx.getSystemService(NotificationManager::class.java)
            if (nm.getNotificationChannel(CHANNEL) == null) {
                nm.createNotificationChannel(
                    NotificationChannel(CHANNEL, "Downloads", NotificationManager.IMPORTANCE_LOW)
                )
            }
        }

        fun build(ctx: Context, text: String): Notification {
            val open = PendingIntent.getActivity(
                ctx, 0, Intent(ctx, MainActivity::class.java),
                PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
            )
            return Notification.Builder(ctx, CHANNEL)
                .setContentTitle("YTD Studio")
                .setContentText(text)
                .setSmallIcon(android.R.drawable.stat_sys_download)
                .setOngoing(true)
                .setOnlyAlertOnce(true)
                .setContentIntent(open)
                .build()
        }

        fun update(ctx: Context, text: String) {
            if (!running) return
            ctx.getSystemService(NotificationManager::class.java).notify(NOTIF_ID, build(ctx, text))
        }
    }
}
