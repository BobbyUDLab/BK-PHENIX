package ru.bkphenix.core.compare

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import ru.bkphenix.core.Fixtures.TRUE_POINT
import ru.bkphenix.core.Fixtures.obs
import ru.bkphenix.core.model.PositionSource

class PositionComparatorTest {
    private val comparator = PositionComparator()

    @Test
    fun `без наблюдений оценки нет`() {
        val result = comparator.compare(emptyList())
        assertNull(result.estimate)
        assertNull(result.accuracyM)
    }

    @Test
    fun `согласованные источники принимаются, погрешность меньше лучшей`() {
        val result = comparator.compare(
            listOf(
                obs(PositionSource.GNSS, TRUE_POINT.offset(3.0, 90.0), 5.0),
                obs(PositionSource.WIFI_RSSI, TRUE_POINT.offset(15.0, 180.0), 20.0),
                obs(PositionSource.CELL, TRUE_POINT.offset(100.0, 0.0), 150.0),
            ),
        )
        assertTrue(result.isConsistent)
        assertTrue(result.accuracyM!! < 5.0)
        assertTrue(result.estimate!!.distanceTo(TRUE_POINT) < 5.0)
    }

    @Test
    fun `подменённая ГНСС со сдвигом 500 м отсекается независимыми источниками`() {
        val spoofed = obs(PositionSource.GNSS, TRUE_POINT.offset(500.0, 90.0), 5.0)
        val result = comparator.compare(
            listOf(
                spoofed,
                obs(PositionSource.WIFI_RSSI, TRUE_POINT.offset(10.0, 0.0), 20.0),
                obs(PositionSource.CELL, TRUE_POINT.offset(80.0, 270.0), 150.0),
            ),
        )
        assertEquals(listOf(spoofed), result.rejected)
        assertTrue(result.estimate!!.distanceTo(TRUE_POINT) < 30.0)
    }

    @Test
    fun `при равных группах выигрывает более точная`() {
        val gnss = obs(PositionSource.GNSS, TRUE_POINT, 5.0)
        val wifi = obs(PositionSource.WIFI_RSSI, TRUE_POINT.offset(1_000.0, 0.0), 20.0)
        val result = comparator.compare(listOf(gnss, wifi))
        assertEquals(listOf(gnss), result.accepted)
        assertEquals(listOf(wifi), result.rejected)
    }
}
