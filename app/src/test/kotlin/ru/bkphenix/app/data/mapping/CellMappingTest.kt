package ru.bkphenix.app.data.mapping

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import ru.bkphenix.app.data.model.CellSnapshot
import ru.bkphenix.core.radio.CellTechnology

class CellMappingTest {
    @Test
    fun `UNAVAILABLE превращается в null`() {
        assertNull(CellMapping.intOrNull(Int.MAX_VALUE))
        assertNull(CellMapping.longOrNull(Long.MAX_VALUE))
        assertEquals(-95, CellMapping.intOrNull(-95))
    }

    @Test
    fun `коды MCC и MNC разбираются из строк`() {
        assertEquals(250, CellMapping.codeOrNull("250"))
        assertEquals(1, CellMapping.codeOrNull("01"))
        assertNull(CellMapping.codeOrNull(null))
        assertNull(CellMapping.codeOrNull(""))
    }

    @Test
    fun `снимок соты переносится в модель ядра`() {
        val m = CellMapping.toMeasurement(
            CellSnapshot(CellTechnology.LTE, true, 250, 1, 7, 123_456L, 301, -97, 5),
        )
        assertEquals(CellTechnology.LTE, m.technology)
        assertEquals(301, m.pci)
        assertEquals(-97, m.rsrpDbm)
        assertEquals(5, m.timingAdvance)
    }
}
