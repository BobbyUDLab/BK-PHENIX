package ru.bkphenix.app.data.source

import android.content.Context
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import ru.bkphenix.app.data.model.SensorKind
import ru.bkphenix.app.data.model.SensorSnapshot
import ru.bkphenix.app.data.model.SourceStatus
import ru.bkphenix.core.inertial.ImuSample

/** Барометр, акселерометр, гироскоп и магнитометр. Отсутствующие датчики пропускаются. */
class SensorDataSource(context: Context) : DataSource("Датчики") {
    private val manager = context.getSystemService(SensorManager::class.java)

    fun readings(): Flow<SensorSnapshot> = callbackFlow {
        val sm = manager
        if (sm == null) {
            setStatus(SourceStatus.Unsupported("SensorManager недоступен"))
            awaitClose { }
            return@callbackFlow
        }
        val listener = object : SensorEventListener {
            override fun onSensorChanged(event: SensorEvent) {
                val kind = KINDS[event.sensor.type] ?: return
                trySend(SensorSnapshot(kind, event.sensor.name, event.values.toList(), System.currentTimeMillis()))
            }

            override fun onAccuracyChanged(sensor: Sensor, accuracy: Int) = Unit
        }
        val registered = KINDS.keys.count { type ->
            val sensor = guarded("getDefaultSensor($type)") { sm.getDefaultSensor(type) }
            sensor != null && guarded("registerListener") {
                sm.registerListener(listener, sensor, SensorManager.SENSOR_DELAY_UI)
            } == true
        }
        setStatus(if (registered > 0) SourceStatus.Active else SourceStatus.Unsupported("Датчики не найдены"))
        awaitClose { sm.unregisterListener(listener) }
    }

    /**
     * Поток ИИБ для счисления пути: отсчёт на каждое событие гироскопа с последними акселерометром
     * и магнитометром. Частота SENSOR_DELAY_GAME (~50 Гц), время — event.timestamp (elapsedRealtimeNanos).
     */
    fun imu(): Flow<ImuSample> = callbackFlow {
        val sm = manager
        val accel = sm?.let { guarded("getDefaultSensor(accel)") { it.getDefaultSensor(Sensor.TYPE_ACCELEROMETER) } }
        val gyro = sm?.let { guarded("getDefaultSensor(gyro)") { it.getDefaultSensor(Sensor.TYPE_GYROSCOPE) } }
        if (sm == null || accel == null || gyro == null) {
            awaitClose { }
            return@callbackFlow
        }
        val mag = guarded("getDefaultSensor(mag)") { sm.getDefaultSensor(Sensor.TYPE_MAGNETIC_FIELD) }
        var lastAccel: DoubleArray? = null
        var lastMag: DoubleArray? = null
        val listener = object : SensorEventListener {
            override fun onSensorChanged(event: SensorEvent) {
                val v = DoubleArray(3) { event.values[it].toDouble() }
                when (event.sensor.type) {
                    Sensor.TYPE_ACCELEROMETER -> lastAccel = v
                    Sensor.TYPE_MAGNETIC_FIELD -> lastMag = v
                    Sensor.TYPE_GYROSCOPE -> lastAccel?.let { a -> trySend(ImuSample(event.timestamp, a, v, lastMag)) }
                }
            }

            override fun onAccuracyChanged(sensor: Sensor, accuracy: Int) = Unit
        }
        listOfNotNull(accel, gyro, mag).forEach { sensor ->
            guarded("registerListener") { sm.registerListener(listener, sensor, SensorManager.SENSOR_DELAY_GAME) }
        }
        awaitClose { sm.unregisterListener(listener) }
    }

    companion object {
        private val KINDS = mapOf(
            Sensor.TYPE_PRESSURE to SensorKind.BAROMETER,
            Sensor.TYPE_ACCELEROMETER to SensorKind.ACCELEROMETER,
            Sensor.TYPE_GYROSCOPE to SensorKind.GYROSCOPE,
            Sensor.TYPE_MAGNETIC_FIELD to SensorKind.MAGNETOMETER,
        )

        /** Барометрическая высота по стандартной атмосфере, метры. */
        fun pressureAltitudeM(pressureHpa: Float): Float =
            SensorManager.getAltitude(SensorManager.PRESSURE_STANDARD_ATMOSPHERE, pressureHpa)
    }
}
