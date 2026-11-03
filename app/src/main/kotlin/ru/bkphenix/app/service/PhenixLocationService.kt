package ru.bkphenix.app.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import androidx.lifecycle.LifecycleService
import ru.bkphenix.app.MainActivity
import ru.bkphenix.app.PhenixApplication
import ru.bkphenix.app.R
import ru.bkphenix.app.permissions.Permissions

/** Foreground-сервис типа location: держит сбор данных, пока приложение свёрнуто. */
class PhenixLocationService : LifecycleService() {
    private val repository get() = (application as PhenixApplication).container.repository

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        super.onStartCommand(intent, flags, startId)
        if (intent?.action == ACTION_STOP) {
            stopSelf()
            return START_NOT_STICKY
        }
        if (!Permissions.hasFineLocation(this)) {
            Log.w(TAG, "Нет разрешения на местоположение, сервис не запущен")
            stopSelf()
            return START_NOT_STICKY
        }
        try {
            ServiceCompat.startForeground(
                this,
                NOTIFICATION_ID,
                buildNotification(),
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) ServiceInfo.FOREGROUND_SERVICE_TYPE_LOCATION else 0,
            )
        } catch (e: RuntimeException) {
            // ForegroundServiceStartNotAllowedException (API 31+) и SecurityException на урезанных прошивках.
            Log.w(TAG, "Не удалось перейти в foreground", e)
            stopSelf()
            return START_NOT_STICKY
        }
        repository.start()
        return START_STICKY
    }

    override fun onDestroy() {
        repository.stop()
        super.onDestroy()
    }

    private fun buildNotification(): Notification {
        val nm = getSystemService(NotificationManager::class.java)
        nm?.createNotificationChannel(
            NotificationChannel(CHANNEL_ID, getString(R.string.notification_channel), NotificationManager.IMPORTANCE_LOW),
        )
        val open = PendingIntent.getActivity(
            this, 0, Intent(this, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE,
        )
        val stop = PendingIntent.getService(
            this, 1, Intent(this, PhenixLocationService::class.java).setAction(ACTION_STOP), PendingIntent.FLAG_IMMUTABLE,
        )
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle(getString(R.string.notification_title))
            .setContentText(getString(R.string.notification_text))
            .setContentIntent(open)
            .addAction(0, getString(R.string.notification_stop), stop)
            .setOngoing(true)
            .setForegroundServiceBehavior(NotificationCompat.FOREGROUND_SERVICE_IMMEDIATE)
            .build()
    }

    companion object {
        private const val TAG = "BkPhenix"
        private const val CHANNEL_ID = "positioning"
        private const val NOTIFICATION_ID = 1
        private const val ACTION_STOP = "ru.bkphenix.action.STOP"

        fun start(context: Context) {
            if (!Permissions.hasFineLocation(context)) return
            try {
                ContextCompat.startForegroundService(context, Intent(context, PhenixLocationService::class.java))
            } catch (e: RuntimeException) {
                Log.w(TAG, "Не удалось запустить сервис", e)
            }
        }

        fun stop(context: Context) {
            context.stopService(Intent(context, PhenixLocationService::class.java))
        }
    }
}
