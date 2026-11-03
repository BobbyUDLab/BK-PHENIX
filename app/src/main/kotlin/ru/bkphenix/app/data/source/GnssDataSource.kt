package ru.bkphenix.app.data.source

import android.annotation.SuppressLint
import android.content.Context
import android.location.GnssMeasurement
import android.location.GnssMeasurementsEvent
import android.location.GnssNavigationMessage
import android.location.GnssStatus
import android.location.LocationManager
import android.os.Build
import android.os.Handler
import android.os.Looper
import androidx.core.content.ContextCompat
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.callbackFlow
import ru.bkphenix.app.data.mapping.GnssMapping
import ru.bkphenix.app.data.mapping.NavigationMessageMapping
import ru.bkphenix.app.data.model.GnssCapabilitiesSnapshot
import ru.bkphenix.app.data.model.MeasurementSummary
import ru.bkphenix.app.data.model.SourceStatus
import ru.bkphenix.app.permissions.Permissions
import ru.bkphenix.core.integrity.NavigationWord
import ru.bkphenix.core.model.SatelliteStatus

/**
 * Данные ГНСС-чипа: статус спутников, сырые измерения, навигационные сообщения и возможности.
 * Каждый поток регистрируется независимо: отказ одного API не мешает остальным.
 */
// Разрешения проверяются через Permissions, SecurityException перехватывается в guarded().
@SuppressLint("MissingPermission")
class GnssDataSource(private val context: Context) : DataSource("ГНСС") {
    private val manager = context.getSystemService(LocationManager::class.java)
    private val handler = Handler(Looper.getMainLooper())
    private val executor = ContextCompat.getMainExecutor(context)

    private val _measurementsStatus = MutableStateFlow<SourceStatus>(SourceStatus.Idle)
    val measurementsStatus: StateFlow<SourceStatus> = _measurementsStatus.asStateFlow()
    private val _navigationStatus = MutableStateFlow<SourceStatus>(SourceStatus.Idle)
    val navigationStatus: StateFlow<SourceStatus> = _navigationStatus.asStateFlow()

    fun capabilities(): GnssCapabilitiesSnapshot {
        val m = manager
        val model = if (m != null && Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            guarded("getGnssHardwareModelName") { m.gnssHardwareModelName }
        } else {
            null
        }
        val year = if (m != null && Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            guarded("getGnssYearOfHardware") { m.gnssYearOfHardware }?.takeIf { it > 0 }
        } else {
            null
        }
        val caps = if (m != null && Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            guarded("getGnssCapabilities") { m.gnssCapabilities }
        } else {
            null
        }
        val s = Build.VERSION.SDK_INT >= Build.VERSION_CODES.S
        return GnssCapabilitiesSnapshot(
            hardwareModel = model,
            yearOfHardware = year,
            measurements = if (s && caps != null) caps.hasMeasurements() else null,
            navigationMessages = if (s && caps != null) caps.hasNavigationMessages() else null,
            antennaInfo = if (s && caps != null) caps.hasAntennaInfo() else null,
            measurementCorrections = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE && caps != null) {
                caps.hasMeasurementCorrections()
            } else {
                null
            },
            accumulatedDeltaRange = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE && caps != null) {
                caps.hasAccumulatedDeltaRange() == android.location.GnssCapabilities.CAPABILITY_SUPPORTED
            } else {
                null
            },
        )
    }

    fun satellites(): Flow<List<SatelliteStatus>> = callbackFlow {
        val m = manager
        if (m == null || !Permissions.hasFineLocation(context)) {
            setStatus(if (m == null) SourceStatus.Unsupported("LocationManager недоступен") else SourceStatus.PermissionDenied)
            awaitClose { }
            return@callbackFlow
        }
        val callback = object : GnssStatus.Callback() {
            override fun onSatelliteStatusChanged(status: GnssStatus) {
                trySend(status.toSatellites())
            }
        }
        val ok = guarded("registerGnssStatusCallback") {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                m.registerGnssStatusCallback(executor, callback)
            } else {
                m.registerGnssStatusCallback(callback, handler)
            }
        } == true
        setStatus(if (ok) SourceStatus.Active else SourceStatus.Unavailable("Статус спутников недоступен"))
        awaitClose { guarded("unregisterGnssStatusCallback") { m.unregisterGnssStatusCallback(callback) } }
    }

    fun measurements(): Flow<MeasurementSummary> = callbackFlow {
        val m = manager
        if (m == null || !Permissions.hasFineLocation(context)) {
            _measurementsStatus.value = SourceStatus.PermissionDenied
            awaitClose { }
            return@callbackFlow
        }
        val callback = object : GnssMeasurementsEvent.Callback() {
            override fun onGnssMeasurementsReceived(event: GnssMeasurementsEvent) {
                trySend(event.toSummary())
            }

            @Suppress("OVERRIDE_DEPRECATION", "DEPRECATION") // единственный способ узнать статус до API 33
            override fun onStatusChanged(status: Int) {
                _measurementsStatus.value = when (status) {
                    STATUS_READY -> SourceStatus.Active
                    STATUS_NOT_SUPPORTED -> SourceStatus.Unsupported("Чип не выдаёт сырые измерения")
                    else -> SourceStatus.Unavailable("Сырые измерения выключены (код $status)")
                }
            }
        }
        val ok = guarded("registerGnssMeasurementsCallback") {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                m.registerGnssMeasurementsCallback(executor, callback)
            } else {
                @Suppress("DEPRECATION")
                m.registerGnssMeasurementsCallback(callback, handler)
            }
        } == true
        if (!ok) _measurementsStatus.value = SourceStatus.Unsupported("Регистрация отклонена")
        awaitClose { guarded("unregisterGnssMeasurementsCallback") { m.unregisterGnssMeasurementsCallback(callback) } }
    }

    /** Слова GPS LNAV из каждого навигационного сообщения; прочие типы считаются, но не разбираются. */
    fun navigationWords(): Flow<List<NavigationWord>> = callbackFlow {
        val m = manager
        if (m == null || !Permissions.hasFineLocation(context)) {
            _navigationStatus.value = SourceStatus.PermissionDenied
            awaitClose { }
            return@callbackFlow
        }
        val callback = object : GnssNavigationMessage.Callback() {
            override fun onGnssNavigationMessageReceived(event: GnssNavigationMessage) {
                val words = NavigationMessageMapping.gpsL1caWords(event.type, event.data)
                if (words.isNotEmpty()) trySend(words)
            }

            @Suppress("OVERRIDE_DEPRECATION", "DEPRECATION") // единственный способ узнать статус до API 33
            override fun onStatusChanged(status: Int) {
                _navigationStatus.value = when (status) {
                    STATUS_READY -> SourceStatus.Active
                    STATUS_NOT_SUPPORTED -> SourceStatus.Unsupported("Чип не выдаёт навигационные сообщения")
                    else -> SourceStatus.Unavailable("Навигационные сообщения выключены (код $status)")
                }
            }
        }
        val ok = guarded("registerGnssNavigationMessageCallback") {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                m.registerGnssNavigationMessageCallback(executor, callback)
            } else {
                @Suppress("DEPRECATION")
                m.registerGnssNavigationMessageCallback(callback, handler)
            }
        } == true
        if (!ok) _navigationStatus.value = SourceStatus.Unsupported("Регистрация отклонена")
        awaitClose {
            guarded("unregisterGnssNavigationMessageCallback") { m.unregisterGnssNavigationMessageCallback(callback) }
        }
    }

    private fun GnssStatus.toSatellites(): List<SatelliteStatus> = (0 until satelliteCount).map { i ->
        val constellation = GnssMapping.constellation(getConstellationType(i))
        SatelliteStatus(
            constellation = constellation,
            svid = getSvid(i),
            band = GnssMapping.band(if (hasCarrierFrequencyHz(i)) getCarrierFrequencyHz(i).toDouble() else null),
            cn0DbHz = getCn0DbHz(i).toDouble(),
            elevationDeg = getElevationDegrees(i).toDouble(),
            azimuthDeg = getAzimuthDegrees(i).toDouble(),
            usedInFix = usedInFix(i),
        )
    }

    @Suppress("DEPRECATION") // AGC на уровне измерения: на API 33+ дублируется GnssAutomaticGainControl.
    private fun GnssMeasurementsEvent.toSummary(): MeasurementSummary {
        val list = measurements.toList()
        val agc = list.filter { it.hasAutomaticGainControlLevelDb() }.map { it.automaticGainControlLevelDb }
        return MeasurementSummary(
            count = list.size,
            withCarrierPhase = list.count {
                it.accumulatedDeltaRangeState and GnssMeasurement.ADR_STATE_VALID != 0
            },
            bands = list.map { GnssMapping.band(if (it.hasCarrierFrequencyHz()) it.carrierFrequencyHz.toDouble() else null) }.toSet(),
            agcDb = agc.takeIf { it.isNotEmpty() }?.average(),
            timeMillis = System.currentTimeMillis(),
        )
    }
}
