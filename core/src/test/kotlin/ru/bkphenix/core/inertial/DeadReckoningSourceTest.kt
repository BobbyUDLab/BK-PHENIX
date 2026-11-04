package ru.bkphenix.core.inertial

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import ru.bkphenix.core.geo.GeoPoint
import ru.bkphenix.core.integrity.IntegrityState
import ru.bkphenix.core.model.PositionSource
import ru.bkphenix.core.model.SourceGroup

class DeadReckoningSourceTest {
    private val p = GeoPoint(55.75, 37.62)

    @Test
    fun `без решений наблюдения нет`() {
        assertNull(DeadReckoningSource().observation(1_000, 1_000))
    }

    @Test
    fun `наблюдение — прогноз группы датчиков с растущей погрешностью`() {
        val dr = DeadReckoningSource()
        assertTrue(dr.onSolution(p, 5.0, IntegrityState.SUSPICIOUS, 1_000))
        val o1 = dr.observation(2_000, 50_000)!!
        val o2 = dr.observation(6_000, 54_000)!!
        assertEquals(PositionSource.DEAD_RECKONING, o1.source)
        assertEquals(setOf(SourceGroup.INERTIAL), o1.origin)
        assertEquals(50_000, o1.timestampMillis)
        assertTrue(o2.accuracyM > o1.accuracyM)
    }

    @Test
    fun `противоречивые решения и подмена не подтягивают счисление`() {
        val dr = DeadReckoningSource()
        assertFalse(dr.onSolution(p, 5.0, IntegrityState.SPOOFING_SUSPECTED, 1_000))
        assertFalse(dr.onSolution(p, 5.0, IntegrityState.INCONSISTENT, 1_000))
        assertFalse(dr.onSolution(null, null, IntegrityState.OK, 1_000))
        assertNull(dr.observation(2_000, 2_000))
    }

    @Test
    fun `счисление от фикса GNSS без независимого подтверждения помечено как производное`() {
        val dr = DeadReckoningSource()
        assertTrue(dr.onSolution(p, 5.0, IntegrityState.SUSPICIOUS, 1_000))
        assertTrue(dr.observation(2_000, 2_000)!!.gnssDerived)
        assertTrue(dr.onSolution(p, 5.0, IntegrityState.OK, 3_000, independentlyConfirmed = true))
        assertFalse(dr.observation(4_000, 4_000)!!.gnssDerived)
    }
}
