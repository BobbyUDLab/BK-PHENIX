package ru.bkphenix.app.data.source

import android.content.Context
import android.os.Build
import android.os.SystemClock
import android.provider.Settings
import androidx.annotation.RequiresApi
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import ru.bkphenix.app.data.model.NetworkTimeSample
import ru.bkphenix.app.data.model.SourceStatus
import java.time.DateTimeException

/**
 * Сетевое время (NITZ или NTP) относительно системных часов.
 *
 * Android 13+: SystemClock.currentNetworkTimeClock — независимый отсчёт.
 * Ниже 13 прямого API нет; если включена автосинхронизация времени по сети, системные часы
 * сами подстроены под NITZ/NTP, поэтому отдаём смещение 0 с широкой погрешностью
 * ([NetworkTimeFallback]). Время network-провайдера Location не используем: оно берётся
 * из тех же системных часов и независимой оценки не даёт.
 */
class NetworkTimeDataSource(private val context: Context) : DataSource("Сетевое время") {

    fun samples(intervalMillis: Long = 10_000): Flow<NetworkTimeSample> = flow {
        while (true) {
            val sample = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) networkClock() else fallback()
            sample?.let {
                setStatus(SourceStatus.Active)
                emit(it)
            }
            delay(intervalMillis)
        }
    }

    @RequiresApi(Build.VERSION_CODES.TIRAMISU)
    private fun networkClock(): NetworkTimeSample? = try {
        val network = SystemClock.currentNetworkTimeClock().millis()
        NetworkTimeSample(network - System.currentTimeMillis(), NetworkTimeFallback.NETWORK_CLOCK_UNCERTAINTY_MS, "сетевые часы")
    } catch (e: DateTimeException) {
        setStatus(SourceStatus.Unavailable("Сеть не сообщила время"))
        null
    }

    private fun fallback(): NetworkTimeSample? {
        val autoTime = guarded("Settings.Global.AUTO_TIME") {
            Settings.Global.getInt(context.contentResolver, Settings.Global.AUTO_TIME, -1)
        }?.takeIf { it >= 0 }?.let { it == 1 }
        val sample = NetworkTimeFallback.sample(autoTime)
        if (sample == null) {
            setStatus(SourceStatus.Unsupported("Android ниже 13 и автосинхронизация времени выключена"))
        }
        return sample
    }
}

/** Запасная оценка сетевого времени для Android ниже 13. */
object NetworkTimeFallback {
    const val NETWORK_CLOCK_UNCERTAINTY_MS: Long = 1_000

    /** Погрешность системных часов, подстроенных по NITZ/NTP: шаг коррекции и задержки сети. */
    const val AUTO_SYNC_UNCERTAINTY_MS: Long = 5_000

    fun sample(autoTimeEnabled: Boolean?): NetworkTimeSample? =
        if (autoTimeEnabled == true) NetworkTimeSample(0, AUTO_SYNC_UNCERTAINTY_MS, "автосинхронизация системных часов") else null
}
