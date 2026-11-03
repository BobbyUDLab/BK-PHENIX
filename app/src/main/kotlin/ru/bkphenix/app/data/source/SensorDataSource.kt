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
