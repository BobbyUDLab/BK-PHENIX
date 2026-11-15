package ru.bkphenix.core.integrity

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import ru.bkphenix.core.compare.PositionComparator
import ru.bkphenix.core.geo.GeoPoint
import ru.bkphenix.core.inertial.ImuSample
import ru.bkphenix.core.inertial.InertialNavigator
import ru.bkphenix.core.inertial.MotionSummary
import ru.bkphenix.core.model.PositionObservation
import ru.bkphenix.core.model.PositionSource
import ru.bkphenix.core.track.MotionMode
import ru.bkphenix.core.track.PedestrianSmoother
import kotlin.math.sin

/** Разбор журналов 14–15.11.2026: переоценка точности счисления, якорь, вагон МЦК, скачки сети пешком. */
class Field1415Test {
    private val home = GeoPoint(55.7512, 37.6184)
    private val dtNanos = 10_000_000L // 100 Гц

    @Test
    fun `σ счисления не сходится к нулю при частых фиксах и растёт без шагов`() {
        val nav = InertialNavigator(MotionMode.WALK)
        // Телефон в руке: дрожание (не покой), шагов нет. Фиксы раз в секунду с σ 3 м в течение 2 минут.
        var tNanos = 0L
        for (s in 0 until 120) {
            for (k in 0 until 100) {
                tNanos += dtNanos
                val t = tNanos / 1e9
                nav.onImu(ImuSample(tNanos, doubleArrayOf(0.3 * sin(t * 7), 0.2 * sin(t * 5), 9.81 + 0.3 * sin(t * 3)), doubleArrayOf(0.06 * sin(t * 4), 0.0, 0.07)))
            }
            nav.onFix(home, 3.0, tNanos / 1_000_000)
        }
        val atFix = nav.estimate(tNanos / 1_000_000)!!
        assertTrue(atFix.sigmaM >= 2.0, "после фиксов σ ${atFix.sigmaM} — не ниже предела")
        // 40 с без фиксов и без шагов: σ обязана вырасти хотя бы до ~0,7 м/с · 40 с.
        for (k in 0 until 4000) {
            tNanos += dtNanos
            val t = tNanos / 1e9
            nav.onImu(ImuSample(tNanos, doubleArrayOf(0.3 * sin(t * 7), 0.2 * sin(t * 5), 9.81 + 0.3 * sin(t * 3)), doubleArrayOf(0.06 * sin(t * 4), 0.0, 0.07)))
        }
        val later = nav.estimate(tNanos / 1_000_000)
        assertTrue(later == null || later.sigmaM > 20.0, "σ без шагов 40 с: ${later?.sigmaM}")
    }

    @Test
    fun `источники в 8 м друг от друга согласны при заявленной σ 1-3 м`() {
        val c = PositionComparator()
        val gnss = PositionObservation(PositionSource.GNSS, home, 3.0, 0)
        val dr = PositionObservation(PositionSource.DEAD_RECKONING, home.offset(8.0, 0.0), 0.5, 0)
        assertTrue(c.areConsistent(gnss, dr))
        assertFalse(c.areConsistent(gnss, dr.copy(point = home.offset(120.0, 0.0))))
    }

    @Test
    fun `вагон МЦК — шаги и скорость ГНСС, подтверждённая сотами, не запрещают ГНСС`() {
        val m = MotionConsistencyMonitor()
        val shaking = MotionSummary(steps = 16, cadenceHz = 1.8, stepIntervalCv = 0.2, stationaryS = 0.0, yawChangeDeg = 0.0)
        var veto = false
        var vetoUncorroborated = false
        val m2 = MotionConsistencyMonitor()
        for (i in 0..60) {
            val p = PositionObservation(PositionSource.GNSS, home.offset(15.0 * i, 0.0), 4.0, i * 1000L)
            veto = veto || m.update(p, shaking, i * 1000L, corroborated = true).veto
            vetoUncorroborated = vetoUncorroborated || m2.update(p, shaking, i * 1000L, corroborated = false).veto
        }
        assertFalse(veto, "подтверждённое сотами движение не запрещается")
        assertTrue(vetoUncorroborated, "без подтверждения правило по-прежнему срабатывает")
    }

    @Test
    fun `пешком точка не скачет за сетевой позицией быстрее пешехода`() {
        val s = PedestrianSmoother()
        s.apply(home, 40.0, 0, MotionMode.WALK)
        val out = s.apply(home.offset(160.0, 90.0), 40.0, 1000, MotionMode.WALK)
        assertTrue(out.limited)
        assertEquals(2.5, home.distanceTo(out.point), 0.1)
        assertTrue(out.accuracyM >= 157.0, "расстояние до оценки входит в погрешность")
        // Точный источник принимается сразу; в авто ограничения нет.
        assertFalse(s.apply(home.offset(160.0, 90.0), 4.0, 2000, MotionMode.WALK).limited)
        val car = PedestrianSmoother()
        car.apply(home, 40.0, 0, MotionMode.CAR)
        assertFalse(car.apply(home.offset(160.0, 90.0), 40.0, 1000, MotionMode.CAR).limited)
    }
}
