package ru.bkphenix.app.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.PowerManager
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import androidx.lifecycle.LifecycleService
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.launch
import ru.bkphenix.app.MainActivity
import ru.bkphenix.app.PhenixApplication
import ru.bkphenix.app.R
import ru.bkphenix.app.permissions.Permissions

/** Foreground-сервис типа location: держит сбор данных, пока приложение свёрнуто. */
class PhenixLocationService : LifecycleService() {
    private val container get() = (application as PhenixApplication).container
    private val repository get() = container.repository
    private var wakeLock: PowerManager.WakeLock? = null
    private var watchJob: Job? = null

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
        watchRecording()
        return START_STICKY
    }

    /**
     * Пока пишется сырой лог или трек, держим процессор (PARTIAL_WAKE_LOCK): на EMUI/MagicOS
     * при выключенном экране иначе засыпает обработка эпох, и запись рвётся. Без записи — не держим.
     */
    private fun watchRecording() {
        if (watchJob != null) return
        watchJob = lifecycleScope.launch {
            launch {
                combine(repository.logState, container.trackRecorder.state) { log, track -> log.recording || track.recording }
                    .distinctUntilChanged()
                    .collect { recording -> if (recording) acquireWakeLock() else releaseWakeLock() }
            }
            while (true) {
                delay(FLUSH_MILLIS)
                repository.flushLog()
            }
        }
    }

    private fun acquireWakeLock() {
        if (wakeLock?.isHeld == true) return
        wakeLock = getSystemService(PowerManager::class.java)
            ?.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "BkPhenix:recording")
            ?.apply {
                setReferenceCounted(false)
                acquire(MAX_WAKE_MILLIS)
            }
    }

    private fun releaseWakeLock() {
        wakeLock?.takeIf { it.isHeld }?.release()
        wakeLock = null
    }

    override fun onDestroy() {
        // Сервис остановлен (кнопка «Остановить» или система): закрываем записи, чтобы файлы были целыми.
        container.trackRecorder.stop()
        repository.stopLog()
        releaseWakeLock()
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
        private const val FLUSH_MILLIS = 5_000L
        private const val MAX_WAKE_MILLIS = 12 * 60 * 60 * 1000L
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
