package ru.bkphenix.core.inertial

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import ru.bkphenix.core.geo.GeoPoint
import ru.bkphenix.core.track.MotionMode
import kotlin.math.cos
import kotlin.math.sin

class InertialNavigatorTest {
    private val origin = GeoPoint(55.75, 37.62, 150.0)
    private val magNorth = doubleArrayOf(0.0, 22.0, -40.0)
    private val dtNanos = 10_000_000L

    /** Пешеход идёт на север со скоростью шагов; ГНСС есть первые 30 с, затем пропадает на [outageS]. */
    private fun walk(
        outageS: Int,
        steps: Boolean,
        gyroBiasZ: Double = 0.0,
        mag: DoubleArray? = magNorth,
        nav: InertialNavigator = InertialNavigator(MotionMode.WALK),
    ): Pair<Double, InertialEstimate?> {
        // Скорость, которую даёт детектор шагов на этом сигнале: 1.8 шаг/с × длина по Вайнбергу.
        val speed = 1.8 * 0.48 * Math.pow(2 * 2.5 * 0.9, 0.25)
        // Сначала 5 с на месте: ориентация сходится.
        for (i in 0 until 500) nav.onImu(ImuSample(i * dtNanos, doubleArrayOf(0.0, 0.0, 9.81), doubleArrayOf(0.0, 0.0, gyroBiasZ), mag))
        val start = 500
        val total = (30 + outageS) * 100
        var truth = origin
        for (k in 0 until total) {
            val i = start + k
            val t = k / 100.0
            truth = origin.offset(speed * t, 0.0)
            val accel = if (steps) WalkSignal.accel(t) else doubleArrayOf(0.0, 0.0, 9.81)
            nav.onImu(ImuSample(i * dtNanos, accel, WalkSignal.gyro(t, steps).let { doubleArrayOf(it[0], it[1], gyroBiasZ) }, mag))
            if (k % 100 == 0 && t < 30) nav.onFix(truth, 4.0, i * dtNanos / 1_000_000)
        }
        val e = nav.estimate((start + total) * dtNanos / 1_000_000)
        return (e?.let { truth.distanceTo(it.point) } ?: Double.NaN) to e
    }

    @Test
    fun `PDR держит пешехода 30 с без ГНСС, погрешность покрывает ошибку`() {
        val (err, e) = walk(30, steps = true)
        assertNotNull(e)
        assertTrue(err < 10.0, "ошибка $err м")
        assertTrue(err < 2.5 * e!!.sigmaM, "ошибка $err при σ ${e.sigmaM}")
        assertTrue(e.sinceFixMillis > 29_000)
    }

    /**
     * Критерий п.7: дрейф за 60 с без ГНСС при ходьбе. Худший случай — магнитометр недоступен (помехи в
     * помещении), гироскоп со смещением 0,02 рад/с (~1,1 °/с, типично для MEMS телефона без калибровки).
     */
    @Test
    fun `60 с без ГНСС — дрейф с калибровкой смещения гироскопа в покое не больше 15 м и в пределах 2,5σ`() {
        val nav = InertialNavigator(MotionMode.WALK)
        val (err, e) = walk(60, steps = true, gyroBiasZ = 0.02, mag = null, nav = nav)
        assertNotNull(e)
        assertEquals(0.02, nav.gyroBiasRadS[2], 0.002)
        assertTrue(nav.gyroCalibrated)
        println("Дрейф счисления за 60 с: ${"%.1f".format(err)} м, σ ${"%.1f".format(e!!.sigmaM)} м")
        assertTrue(err < 15.0, "ошибка $err м")
        assertTrue(err < 2.5 * e.sigmaM, "ошибка $err при σ ${e.sigmaM}")
    }

    @Test
    fun `склонение переводит магнитный курс в истинный`() {
        val nav = InertialNavigator(MotionMode.WALK)
        for (i in 0 until 500) nav.onImu(ImuSample(i * dtNanos, doubleArrayOf(0.0, 0.0, 9.81), doubleArrayOf(0.0, 0.0, 0.0), magNorth))
        val magnetic = nav.headingDeg
        nav.declinationDeg = 11.5
        assertEquals(11.5, angleDiff(nav.headingDeg, magnetic), 1e-9)
        nav.declinationDeg = -20.0
        assertEquals(-20.0, angleDiff(nav.headingDeg, magnetic), 1e-9)
        assertTrue(nav.headingDeg in 0.0..360.0)
    }

    @Test
    fun `слишком долгий пропуск — оценки нет`() {
        val (_, e) = walk(600, steps = true)
        assertNull(e)
    }

    @Test
    fun `авто — поворот по гироскопу в тоннеле`() {
        val nav = InertialNavigator(MotionMode.CAR)
        val v = 15.0
        var pos = origin
        var bearing = 90.0
        val accel = doubleArrayOf(0.0, 0.0, 9.81)
        for (i in 0 until 500) nav.onImu(ImuSample(i * dtNanos, accel, doubleArrayOf(0.0, 0.0, 0.0), magNorth))
        var k = 0
        // 20 с на восток с ГНСС, затем 20 с без ГНСС: 10 с прямо и поворот налево на 90° за 10 с.
        while (k < 4000) {
            val t = k / 100.0
            val turning = t >= 30 && t < 40
            val rate = if (turning) Math.PI / 20 else 0.0
            bearing -= Math.toDegrees(rate) * 0.01
            pos = pos.offset(v * 0.01, bearing)
            // Небольшая вибрация, чтобы ZUPT не считал машину стоящей.
            val vib = doubleArrayOf(0.3 * sin(t * 40), 0.3 * cos(t * 37), 9.81 + 0.3 * sin(t * 50))
            nav.onImu(ImuSample((500 + k) * dtNanos, vib, doubleArrayOf(0.02 * sin(t * 30), 0.02 * cos(t * 30), rate), magNorth))
            if (k % 100 == 0 && t < 20) nav.onFix(pos, 4.0, (500 + k) * dtNanos / 1_000_000)
            k++
        }
        val e = nav.estimate((500 + k) * dtNanos / 1_000_000)!!
        val err = pos.distanceTo(e.point)
        assertTrue(err < 40.0, "ошибка $err м, σ ${e.sigmaM}")
        assertTrue(err < 2.5 * e.sigmaM, "ошибка $err при σ ${e.sigmaM}")
        assertTrue(kotlin.math.abs(e.bearingDeg!! - 0.0).let { minOf(it, 360 - it) } < 10, "курс ${e.bearingDeg}")
    }

    private fun angleDiff(a: Double, b: Double) = ((a - b) % 360.0 + 540.0) % 360.0 - 180.0
}
