package ru.bkphenix.core.terrain

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import ru.bkphenix.core.PhenixCore
import ru.bkphenix.core.anchor.Anchor
import ru.bkphenix.core.geo.GeoPoint
import ru.bkphenix.core.inertial.DeadReckoningSource
import ru.bkphenix.core.integrity.IntegrityReport
import ru.bkphenix.core.integrity.IntegrityState
import ru.bkphenix.core.model.PositionObservation
import ru.bkphenix.core.model.PositionSource

class GrossAltitudeVetoTest {
    @Test
    fun `запрет включается после трёх эпох с грубой ошибкой и снимается после десяти нормальных`() {
        val v = GrossAltitudeVeto()
        assertFalse(v.update(780.0))
        assertFalse(v.update(785.0))
        assertTrue(v.update(779.0), "третья эпоха включает запрет")
        assertTrue(v.active)
        assertEquals(IntegrityState.SPOOFING_SUSPECTED, v.report()!!.state)
        repeat(9) { v.update(12.0) }
        assertTrue(v.active)
        v.update(10.0)
        assertFalse(v.active)
        assertNull(v.report())
    }

    @Test
    fun `обычная ошибка высоты телефона и высотный этаж не включают запрет`() {
        val v = GrossAltitudeVeto()
        listOf(40.0, -35.0, 120.0, 330.0, 360.0, 25.0).forEach { v.update(it) }
        assertFalse(v.active)
    }

    @Test
    fun `эпоха без проверки высоты не меняет состояние`() {
        val v = GrossAltitudeVeto()
        repeat(3) { v.update(800.0) }
        repeat(20) { v.update(null) }
        assertTrue(v.active)
    }

    /**
     * P30 lite 07.11.2026: подмена с первой эпохи, других источников нет (сети, сот, сырых данных).
     * Раньше итог всей прогулки — у аэропорта; теперь после включения запрета — «нет позиции».
     */
    @Test
    fun `подмена без других источников — итог пропадает, а не остаётся у аэропорта`() {
        val fake = GeoPoint(55.97, 37.41)
        val core = PhenixCore()
        val dr = DeadReckoningSource()
        val veto = GrossAltitudeVeto()
        var rt = 0L
        var nearFakeAfterVeto = 0
        for (i in 0 until 120) {
            val t = 1_000_000L + i * 1_000
            rt += 1_000
            val obs = listOfNotNull(PositionObservation(PositionSource.GNSS, fake.offset(i * 1.2, 45.0), 4.0, t), dr.observation(rt, t))
            val s = core.solve(obs, t, gnssVetoed = veto.active)
            // Приложение: проверка высоты фикса (+780 м) после решения, запрет — со следующей эпохи.
            if (veto.update(780.0)) dr.resetIfUnconfirmed()
            val withVeto = veto.report()?.let { s.copy(integrity = IntegrityReport.merge(listOf(s.integrity, it))) } ?: s
            dr.onSolution(withVeto, rt)
            if (i >= 3 && withVeto.point?.let { it.distanceTo(fake) < 5_000 } == true) nearFakeAfterVeto++
            if (i >= 3) assertEquals(IntegrityState.SPOOFING_SUSPECTED, withVeto.integrity.state)
        }
        assertEquals(0, nearFakeAfterVeto, "итог у подменённой точки при активном запрете")
    }

    @Test
    fun `якорь при запрете GNSS остаётся итогом`() {
        val home = GeoPoint(55.80, 37.50)
        val core = PhenixCore()
        core.anchorManager.setAnchor(Anchor(home, 20.0, 1_000_000L))
        val s = core.solve(listOf(PositionObservation(PositionSource.GNSS, GeoPoint(55.97, 37.41), 4.0, 1_001_000L)), 1_001_000L, gnssVetoed = true)
        assertTrue(s.point!!.distanceTo(home) < 100, "итог по якорю, а не по GNSS")
        assertTrue(s.comparison.accepted.none { it.source == PositionSource.GNSS })
    }
}
