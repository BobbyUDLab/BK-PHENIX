package ru.bkphenix.app.data.source

import android.annotation.SuppressLint
import android.content.Context
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import android.os.Build
import android.os.Bundle
import android.os.Looper
import android.os.SystemClock
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import ru.bkphenix.app.data.model.RawLocation
import ru.bkphenix.app.data.model.SourceStatus
import ru.bkphenix.app.permissions.Permissions

/** Координаты от AOSP LocationManager: провайдеры gps, network и passive. Без GMS. */
// Разрешения проверяются через Permissions, SecurityException перехватывается в guarded().
@SuppressLint("MissingPermission")
class LocationDataSource(private val context: Context) : DataSource("Местоположение") {
    private val manager = context.getSystemService(LocationManager::class.java)

    fun locations(): Flow<RawLocation> = callbackFlow {
        if (manager == null) {
            setStatus(SourceStatus.Unsupported("LocationManager недоступен"))
            awaitClose { }
            return@callbackFlow
        }
        if (!Permissions.hasFineLocation(context)) {
            setStatus(SourceStatus.PermissionDenied)
            awaitClose { }
            return@callbackFlow
        }
        var lastGpsAt = SystemClock.elapsedRealtime()
        val listener = object : LocationListener {
            override fun onLocationChanged(location: Location) {
                if (location.provider == LocationManager.GPS_PROVIDER) lastGpsAt = SystemClock.elapsedRealtime()
                trySend(location.toRaw())
            }

            override fun onProviderEnabled(provider: String) = Unit
            override fun onProviderDisabled(provider: String) = Unit

            @Deprecated("Deprecated in Java")
            @Suppress("OVERRIDE_DEPRECATION")
            override fun onStatusChanged(provider: String?, status: Int, extras: Bundle?) = Unit
        }
        val available = guarded("allProviders") { manager.allProviders }.orEmpty()
        val providers = PROVIDERS.filter { it in available }
        fun register(): Int {
            var registered = 0
            for (provider in providers) {
                guarded("requestLocationUpdates($provider)") {
                    manager.requestLocationUpdates(provider, INTERVAL_MS, 0f, listener, Looper.getMainLooper())
                    registered++
                }
            }
            return registered
        }
        val registered = register()
        setStatus(
            if (registered > 0) SourceStatus.Active else SourceStatus.Unavailable("Нет доступных провайдеров"),
        )
        // Сторож GPS (0.8.1): DOOGEE V Max 12.11 45 минут не отдавал фиксов, а статус спутников «застыл»
        // (53 спутника, 1 измерение). Если фиксов gps нет дольше [GPS_STALE_MS], подписка перерегистрируется,
        // не чаще раза в [GPS_RESTART_MIN_MS]. В тоннеле это безвредно: перерегистрация ничего не ломает.
        if (LocationManager.GPS_PROVIDER in providers) launch {
            var lastRestart = 0L
            while (isActive) {
                delay(WATCHDOG_TICK_MS)
                val now = SystemClock.elapsedRealtime()
                if (now - lastGpsAt > GPS_STALE_MS && now - lastRestart > GPS_RESTART_MIN_MS) {
                    lastRestart = now
                    restarts++
                    guarded("removeUpdates") { manager.removeUpdates(listener) }
                    register()
                    setStatus(SourceStatus.Active)
                }
            }
        }
        awaitClose {
            guarded("removeUpdates") { manager.removeUpdates(listener) }
            setStatus(SourceStatus.Idle)
        }
    }

    /** Сколько раз сторож перерегистрировал подписку за сеанс (для диагностики). */
    @Volatile var restarts: Int = 0
        private set

    companion object {
        private const val WATCHDOG_TICK_MS = 10_000L
        private const val GPS_STALE_MS = 60_000L
        private const val GPS_RESTART_MIN_MS = 120_000L
        private val PROVIDERS = listOf(LocationManager.GPS_PROVIDER, LocationManager.NETWORK_PROVIDER, LocationManager.PASSIVE_PROVIDER)
        private const val INTERVAL_MS = 1_000L

        fun Location.toRaw(): RawLocation = RawLocation(
            provider = provider ?: "unknown",
            latitude = latitude,
            longitude = longitude,
            altitude = if (hasAltitude()) altitude else null,
            accuracy68M = if (hasAccuracy()) accuracy else null,
            timeMillis = time,
            elapsedRealtimeNanos = elapsedRealtimeNanos,
            isMock = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) isMock else @Suppress("DEPRECATION") isFromMockProvider,
            verticalAccuracyM = if (hasVerticalAccuracy()) verticalAccuracyMeters else null,
        )
    }
}
