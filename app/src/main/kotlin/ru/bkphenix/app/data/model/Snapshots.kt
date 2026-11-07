package ru.bkphenix.app.data.model

import ru.bkphenix.core.model.GnssBand
import ru.bkphenix.core.radio.CellTechnology

/**
 * Состояние отдельного источника данных. Урезанные API (например, на EMUI) приводят к
 * [Unsupported] или [Unavailable], но не к падению приложения.
 */
sealed interface SourceStatus {
    data object Idle : SourceStatus
    data object Active : SourceStatus

    /** API зарегистрирован, но данных от него ещё не было. */
    data object Waiting : SourceStatus
    data class Unsupported(val reason: String) : SourceStatus
    data class Unavailable(val reason: String) : SourceStatus
    data object PermissionDenied : SourceStatus
}

/** Координата от провайдера LocationManager, без привязки к android.location.Location. */
data class RawLocation(
    val provider: String,
    val latitude: Double,
    val longitude: Double,
    val altitude: Double?,
    /** Радиус 68 % из Location.getAccuracy(), метры. */
    val accuracy68M: Float?,
    val timeMillis: Long,
    val elapsedRealtimeNanos: Long,
    val isMock: Boolean,
    /** σ высоты (verticalAccuracyMeters, 68 %), метры. */
    val verticalAccuracyM: Float? = null,
)

/** Агрегированная сводка по сырым измерениям ГНСС одной эпохи. */
data class MeasurementSummary(
    val count: Int,
    val withCarrierPhase: Int,
    val bands: Set<GnssBand>,
    /** Уровень АРУ, дБ: резкий рост по модулю — признак глушения. */
    val agcDb: Double?,
    val timeMillis: Long,
)

/** Эпоха сырых измерений: сводка для экрана и данные для ядра. */
data class GnssMeasurementBatch(
    val summary: MeasurementSummary,
    val epoch: ru.bkphenix.core.gnss.RawGnssEpoch,
    /** Секунды координации GPS−UTC из GnssClock, если чип их сообщает. */
    val leapSeconds: Int?,
    /** Системное время получения эпохи, мс. */
    val receivedAtMillis: Long,
)

/** Возможности ГНСС-чипа. `null` — неизвестно (API недоступно). */
data class GnssCapabilitiesSnapshot(
    val hardwareModel: String?,
    val yearOfHardware: Int?,
    val measurements: Boolean?,
    val navigationMessages: Boolean?,
    val antennaInfo: Boolean?,
    val measurementCorrections: Boolean?,
    val accumulatedDeltaRange: Boolean?,
)

data class CellSnapshot(
    val technology: CellTechnology,
    val isServing: Boolean,
    val mcc: Int?,
    val mnc: Int?,
    val areaCode: Int?,
    val cellId: Long?,
    val pci: Int?,
    val signalDbm: Int?,
    val timingAdvance: Int?,
    /** Возраст по часам модема, мс; `null` — неизвестен. */
    val ageMillis: Long? = null,
)

data class WifiSnapshot(
    val bssid: String,
    val ssid: String?,
    val rssiDbm: Int,
    val frequencyMhz: Int,
    val supportsRtt: Boolean,
    val rttDistanceM: Double? = null,
    val rttStdDevM: Double? = null,
)

data class BleSnapshot(val address: String, val name: String?, val rssiDbm: Int, val txPowerDbm: Int?)

enum class SensorKind(val displayName: String, val unit: String) {
    BAROMETER("Барометр", "гПа"),
    ACCELEROMETER("Акселерометр", "м/с²"),
    GYROSCOPE("Гироскоп", "рад/с"),
    MAGNETOMETER("Магнитометр", "мкТл"),
}

data class SensorSnapshot(
    val kind: SensorKind,
    val name: String?,
    val values: List<Float>,
    val timeMillis: Long,
)

/** Смещение сетевого времени относительно системных часов и способ его получения. */
data class NetworkTimeSample(val offsetMillis: Long, val uncertaintyMillis: Long, val method: String)
