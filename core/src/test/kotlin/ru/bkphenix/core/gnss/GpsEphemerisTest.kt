package ru.bkphenix.core.gnss

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt

class GpsEphemerisTest {
    private val circular = SyntheticConstellation.ephemeris(svid = 1, omega0 = 0.0, m0 = 0.0, toe = 0.0, inclination = 0.0)

    @Test
    fun `круговая экваториальная орбита в момент toe лежит на оси X`() {
        val s = circular.stateAt(0.0)
        val a = circular.sqrtA * circular.sqrtA
        assertEquals(a, s.position.x, 1e-3)
        assertEquals(0.0, s.position.y, 1e-3)
        assertEquals(0.0, s.position.z, 1e-3)
    }

    @Test
    fun `через dt спутник смещается на n·dt, узел поворачивается вместе с Землёй`() {
        val dt = 600.0
        val a = circular.sqrtA * circular.sqrtA
        val n = sqrt(GpsEphemeris.MU / (a * a * a))
        val angle = n * dt - GpsEphemeris.OMEGA_E * dt
        val p = circular.stateAt(dt).position
        assertEquals(a * cos(angle), p.x, 1e-3)
        assertEquals(a * sin(angle), p.y, 1e-3)
    }

    @Test
    fun `поправка часов учитывает полином, TGD и релятивистский член`() {
        val e = circular.copy(af0 = 1e-4, af1 = 1e-11, af2 = 0.0, tgd = 5e-9, toc = 0.0)
        assertEquals(1e-4 + 1e-11 * 100 - 5e-9, e.stateAt(100.0).clockBiasS, 1e-15)
    }

    @Test
    fun `переход через границу недели`() {
        assertEquals(-100.0, GpsEphemeris.wrapWeek(604_700.0), 1e-9)
        assertEquals(100.0, GpsEphemeris.wrapWeek(-604_700.0), 1e-9)
    }
}
