package ru.bkphenix.core.radio

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import ru.bkphenix.core.Fixtures.T0
import ru.bkphenix.core.Fixtures.TRUE_POINT
import ru.bkphenix.core.geo.GeoPoint
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream

class LearnedCellDatabaseTest {
    private fun cell(id: Long, serving: Boolean = true) = CellMeasurement(CellTechnology.LTE, 250, 2, 9742, id, null, -80, null, serving)
    private fun north(m: Double) = GeoPoint(TRUE_POINT.latitude + m / 111_320.0, TRUE_POINT.longitude)

    /** Офлайн-база, где станция стоит в 16 км (как одна из базовых станций в полевых записях). */
    private val wrong = CellTowerDatabase { m ->
        CellTowerRecord(m.technology, 250, 2, 9742, m.cellId!!, north(16_000.0), 3_755.0)
    }

    @Test
    fun `после трёх надёжных точек своя сота важнее неверной офлайн-записи`() {
        val db = LearnedCellDatabase(wrong)
        assertEquals(16_000.0, db.find(cell(25_822_124))!!.position.distanceTo(TRUE_POINT), 50.0)
        for (i in 0 until 3) assertEquals(1, db.learn(listOf(cell(25_822_124)), north(i * 100.0), 10.0, T0 + i * 60_000L))
        val r = db.find(cell(25_822_124))!!
        assertTrue(r.position.distanceTo(north(100.0)) < 5, "центр ${r.position.distanceTo(north(100.0))}")
        assertEquals(LearnedCellDatabase.MIN_RANGE_M, r.rangeM)
        // Соседняя сота той же станции — по своей станции, а не по чужой базе.
        assertTrue(db.find(cell((25_822_124L and 0xFFFFF00) or 7))!!.position.distanceTo(TRUE_POINT) < 300)
    }

    @Test
    fun `стоянка и неточные точки не учат`() {
        val db = LearnedCellDatabase()
        assertEquals(1, db.learn(listOf(cell(1)), TRUE_POINT, 10.0, T0))
        for (i in 1..20) assertEquals(0, db.learn(listOf(cell(1)), north(3.0), 10.0, T0 + i * 5_000L))
        assertEquals(0, db.learn(listOf(cell(1)), north(500.0), 80.0, T0 + 200_000L))
        assertEquals(0, db.learn(listOf(cell(1).copy(ageMillis = 120_000)), north(500.0), 10.0, T0 + 210_000L))
        assertNull(db.find(cell(1)))
    }

    @Test
    fun `далёкая точка у зрелой соты — конфликт, переезд учится заново`() {
        val db = LearnedCellDatabase()
        for (i in 0 until 4) db.learn(listOf(cell(5)), north(i * 50.0), 10.0, T0 + i * 60_000L)
        val before = db.find(cell(5))!!.position
        assertEquals(0, db.learn(listOf(cell(5)), north(20_000.0), 10.0, T0 + 600_000L))
        assertEquals(0.0, db.find(cell(5))!!.position.distanceTo(before), 1.0)
        for (i in 1..6) db.learn(listOf(cell(5)), north(20_000.0 + i * 50), 10.0, T0 + 600_000L + i * 60_000L)
        assertTrue(db.find(cell(5))!!.position.distanceTo(north(20_250.0)) < 200)
    }

    @Test
    fun `экспорт и импорт складывают базы двух телефонов`() {
        val a = LearnedCellDatabase(); val b = LearnedCellDatabase()
        for (i in 0 until 2) a.learn(listOf(cell(9)), north(i * 100.0), 10.0, T0 + i * 60_000L)
        for (i in 2 until 4) b.learn(listOf(cell(9)), north(i * 100.0), 10.0, T0 + i * 60_000L)
        assertNull(a.find(cell(9)))
        val bytes = ByteArrayOutputStream().also { b.write(it) }.toByteArray()
        assertTrue(a.merge(ByteArrayInputStream(bytes)) > 0)
        val r = a.find(cell(9))!!
        assertTrue(r.position.distanceTo(north(150.0)) < 2, "центр ${r.position.distanceTo(north(150.0))}")
        val round = LearnedCellDatabase().apply { merge(ByteArrayInputStream(ByteArrayOutputStream().also { a.write(it) }.toByteArray())) }
        assertEquals(r, round.find(cell(9)))
    }
}
