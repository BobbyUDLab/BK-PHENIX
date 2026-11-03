package ru.bkphenix.app.data.source

import android.os.Build
import android.os.SystemClock
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import ru.bkphenix.app.data.model.SourceStatus
import java.time.DateTimeException

/**
 * Сетевое время (NITZ или NTP) через SystemClock.currentNetworkTimeClock (API 33+).
 * Отдаёт смещение сетевого времени относительно системных часов, мс.
 */
class NetworkTimeDataSource : DataSource("Сетевое время") {

    fun offsets(intervalMillis: Long = 10_000): Flow<Long> = flow {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) {
            setStatus(SourceStatus.Unsupported("Нужен Android 13+"))
            return@flow
        }
        while (true) {
            try {
                val network = SystemClock.currentNetworkTimeClock().millis()
                setStatus(SourceStatus.Active)
                emit(network - System.currentTimeMillis())
            } catch (e: DateTimeException) {
                setStatus(SourceStatus.Unavailable("Сеть не сообщила время"))
            }
            delay(intervalMillis)
        }
    }
}
