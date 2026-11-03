package ru.bkphenix.core.radio

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import ru.bkphenix.core.Fixtures.T0
import ru.bkphenix.core.Fixtures.TRUE_POINT

class CellLocatorTest {
    private fun cell(id: Long, rsrp: Int, serving: Boolean = false, ta: Int? = null) =
        CellMeasurement(CellTechnology.LTE, 250, 1, 10, id, null, rsrp, ta, serving)

    private val towers = mapOf(
        1L to CellTowerRecord(CellTechnology.LTE, 250, 1, 10, 1, TRUE_POINT, 2_000.0),
        2L to CellTowerRecord(CellTechnology.LTE, 250, 1, 10, 2, TRUE_POINT.offset(1_000.0, 90.0), 2_000.0),
    )
    private val db = CellTowerDatabase { m -> towers[m.cellId] }

    @Test
    fun `без вышек в базе позиции нет`() {
        assertNull(CellLocator(EmptyCellTowerDatabase).locate(listOf(cell(1, -80)), T0))
    }

    @Test
    fun `сильный сигнал смещает оценку к своей вышке`() {
        val obs = CellLocator(db).locate(listOf(cell(1, -70), cell(2, -100)), T0)!!
        assertTrue(obs.point.distanceTo(TRUE_POINT) < 50.0)
        assertEquals(1_000.0, obs.accuracyM, 1.0)
    }

    @Test
    fun `Timing Advance обслуживающей соты уточняет погрешность`() {
        val obs = CellLocator(db).locate(listOf(cell(1, -70, serving = true, ta = 3)), T0)!!
        assertEquals(3 * 78.12, obs.accuracyM, 0.01)
    }
}
