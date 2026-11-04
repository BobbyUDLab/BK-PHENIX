package ru.bkphenix.core.terrain

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import ru.bkphenix.core.geo.GeoPoint
import ru.bkphenix.core.integrity.IntegrityState
import ru.bkphenix.core.track.MotionMode
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.DataOutputStream
import java.util.Random
import kotlin.math.abs
import kotlin.math.pow

class TerrainTest {
    /** Рельеф 55–56° с. ш., 37–38° в. д., шаг 0.01°: наклон 100 м + 50 м на градус широты; геоид 15 м. */
    private fun file(magic: String = "BKDM", heightAt: (Double, Double) -> Double = { la, _ -> 100 + 50 * (la - 55) }): ByteArray =
        ByteArrayOutputStream().also { o ->
            DataOutputStream(o).apply {
                write(magic.toByteArray()); writeInt(1)
                writeDouble(55.0); writeDouble(37.0); writeDouble(0.01); writeDouble(0.01); writeInt(101); writeInt(101)
                for (r in 0..100) for (c in 0..100) {
                    writeShort(if (r == 100 && c == 100) TerrainModel.NODATA.toInt() else (heightAt(55 + r * 0.01, 37 + c * 0.01) * 10).toInt())
                }
                writeDouble(54.9); writeDouble(36.9); writeDouble(0.1); writeInt(13); writeInt(13)
                for (r in 0..12) for (c in 0..12) writeShort(1500 + c * 10) // N = 15 м + 0.1 м на 0.1° долготы
            }
        }.toByteArray()

    private val terrain = TerrainModel.read(ByteArrayInputStream(file()))
    private fun hPa(altM: Double) = BarometricAltimeter.STANDARD_HPA * (1 - altM / 44330.0).pow(5.255)

    @Test
    fun `рельеф и геоид билинейно, вне файла и без данных — null, битый файл — отказ`() {
        assertEquals(125.0, terrain.elevationM(GeoPoint(55.5, 37.5))!!, 0.05)
        assertEquals(15.15, terrain.geoidM(GeoPoint(55.5, 37.05))!!, 0.01)
        assertEquals(110.0 - 15.0, terrain.orthometricM(GeoPoint(55.0, 36.9 + 1e-9), 110.0)!!, 0.01)
        assertNull(terrain.elevationM(GeoPoint(54.9, 37.5)))
        assertNull(terrain.elevationM(GeoPoint(55.995, 37.995)))
        assertThrows<IllegalArgumentException> { TerrainModel.read(ByteArrayInputStream(file("XXXX"))) }
    }

    @Test
    fun `барометр — стандартная атмосфера, калибровка и рост σ со временем`() {
        assertEquals(0.0, BarometricAltimeter.pressureAltitudeM(1013.25), 1e-6)
        assertEquals(1000.0, BarometricAltimeter.pressureAltitudeM(hPa(1000.0)), 1.0)
        val b = BarometricAltimeter()
        b.onPressure(hPa(300.0)) // погода: барометр «думает», что мы на 300 м
        assertNull(b.altitude(0))
        b.calibrate(150.0, 8.0, 0, BarometricAltimeter.CalibrationSource.TERRAIN)
        assertEquals(150.0, b.altitude(0)!!.heightM, 0.01)
        b.onPressure(hPa(312.0)) // поднялись на 12 м
        assertEquals(162.0, b.altitude(0)!!.heightM, 0.1)
        assertTrue(b.altitude(3_600_000)!!.sigmaM > 11.0)
    }

    private fun walk(monitor: VerticalIntegrityMonitor, epochs: Int, mode: MotionMode = MotionMode.WALK, start: Long = 0, gnss: (GeoPoint, Double) -> Double): List<VerticalCheck> {
        val rnd = Random(7)
        return (0 until epochs).map { k ->
            val p = GeoPoint(55.2 + k * 0.0005, 37.3)
            val ground = terrain.elevationM(p)!! + 1.0
            monitor.onPressure(hPa(ground + 40.0)) // смещение барометра из-за погоды +40 м
            monitor.update(p, gnss(p, ground) + rnd.nextGaussian() * 5.0, 5.0, mode, true, start + k * 1000L)
        }
    }

    @Test
    fun `честная прогулка по склону с учётом геоида — тревоги нет, остаток около нуля`() {
        val m = VerticalIntegrityMonitor(terrain)
        val checks = walk(m, 300) { p, ground -> ground + terrain.geoidM(p)!! }
        assertTrue(checks.all { it.report.state == IntegrityState.OK })
        assertTrue(abs(checks.mapNotNull { it.residualM }.average()) < 2.0)
        // Барометр откалиброван по рельефу и снял погодное смещение 40 м.
        assertEquals(BarometricAltimeter.CalibrationSource.TERRAIN, checks.last().barometric!!.source)
        assertEquals(checks.last().terrainM!! + 1.0, checks.last().barometric!!.heightM, 3.0)
    }

    @Test
    fun `подмена высоты ГНСС на 60 м — подозрительно на третьей эпохе и не «привыкает»`() {
        val m = VerticalIntegrityMonitor(terrain)
        val checks = walk(m, 200) { p, ground -> ground + terrain.geoidM(p)!! + 60.0 }
        assertEquals(IntegrityState.OK, checks[1].report.state)
        assertTrue(checks.drop(2).all { it.report.state == IntegrityState.SUSPICIOUS })
        assertTrue(checks.last().report.reasons.single().contains("выше"))
    }

    @Test
    fun `на этаже без привязки опора — барометр, по рельефу не калибруется`() {
        val m = VerticalIntegrityMonitor(terrain)
        val p = GeoPoint(55.4, 37.4)
        val ground = terrain.elevationM(p)!! + 1.0
        m.onPressure(hPa(ground + 40.0))
        assertTrue(m.calibrateAtAnchor(p, 0))
        // Поднялись на 30 м (10-й этаж): барометр это видит, честная ГНСС тоже.
        m.onPressure(hPa(ground + 70.0))
        val honest = (1..5).map { m.update(p, ground + 30.0 + terrain.geoidM(p)!!, 6.0, MotionMode.NONE, true, it * 1000L) }
        assertTrue(honest.all { it.report.state == IntegrityState.OK })
        assertEquals(BarometricAltimeter.CalibrationSource.ANCHOR, honest.last().barometric!!.source)
        assertEquals(ground + 30.0, honest.last().barometric!!.heightM, 0.5)
        // Подмена «на земле» (на 50 м ниже барометра) — выше порога.
        val spoof = (6..10).map { m.update(p, ground - 20.0 + terrain.geoidM(p)!!, 3.0, MotionMode.NONE, true, it * 1000L) }
        assertEquals(IntegrityState.SUSPICIOUS, spoof.last().report.state)
        assertTrue(spoof.last().report.reasons.single().contains("ниже барометра"))
    }

    @Test
    fun `без барометра в режиме без привязки и без высоты ГНСС — проверки нет`() {
        val m = VerticalIntegrityMonitor(terrain)
        val p = GeoPoint(55.4, 37.4)
        assertNull(m.update(p, 500.0, 5.0, MotionMode.NONE, true, 0).residualM)
        assertNull(m.update(p, null, null, MotionMode.CAR, true, 0).residualM)
        assertNull(m.update(null, 500.0, 5.0, MotionMode.CAR, true, 0).residualM)
    }
}
