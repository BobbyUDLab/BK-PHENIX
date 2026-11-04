package ru.bkphenix.core.integrity

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

class AlarmFilterTest {
    private val bad = IntegrityReport(IntegrityState.INCONSISTENT, listOf("RAIM"))

    @Test
    fun `одиночная тревога понижается до подозрения, устойчивая — объявляется и снимается с задержкой`() {
        val f = AlarmFilter(raiseEpochs = 3, clearEpochs = 2)
        assertEquals(IntegrityState.SUSPICIOUS, f.filter(bad).state)
        assertEquals(IntegrityState.OK, f.filter(IntegrityReport.OK).state)
        assertEquals(IntegrityState.SUSPICIOUS, f.filter(bad).state)
        assertEquals(IntegrityState.SUSPICIOUS, f.filter(bad).state)
        assertEquals(IntegrityState.INCONSISTENT, f.filter(bad).state)
        assertEquals(IntegrityState.SUSPICIOUS, f.filter(IntegrityReport.OK).state)
        assertEquals(IntegrityState.OK, f.filter(IntegrityReport.OK).state)
    }

    @Test
    fun `ошибки навигационных сообщений — без задержки`() {
        val f = AlarmFilter()
        assertEquals(IntegrityState.NAV_DATA_CORRUPTED, f.filter(IntegrityReport(IntegrityState.NAV_DATA_CORRUPTED, emptyList())).state)
    }
}
