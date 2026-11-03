package ru.bkphenix.app.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import ru.bkphenix.app.ui.coordinate.AnchorInput
import ru.bkphenix.app.ui.coordinate.AnchorInputResult

class AnchorInputTest {
    @Test
    fun `запятая как разделитель и погрешность по умолчанию`() {
        val r = AnchorInput.parse("55,753930", " 37.620795 ", "") as AnchorInputResult.Valid
        assertEquals(55.75393, r.point.latitude, 1e-9)
        assertEquals(37.620795, r.point.longitude, 1e-9)
        assertEquals(AnchorInput.DEFAULT_ACCURACY_M, r.accuracyM, 0.0)
    }

    @Test
    fun `типографский минус и явная погрешность`() {
        val r = AnchorInput.parse("−33,86", "151,2", "25") as AnchorInputResult.Valid
        assertEquals(-33.86, r.point.latitude, 1e-9)
        assertEquals(25.0, r.accuracyM, 0.0)
    }

    @Test
    fun `некорректный ввод не принимается`() {
        assertTrue(AnchorInput.parse("abc", "37", "") is AnchorInputResult.Invalid)
        assertTrue(AnchorInput.parse("91", "37", "") is AnchorInputResult.Invalid)
        assertTrue(AnchorInput.parse("55", "181", "") is AnchorInputResult.Invalid)
        assertTrue(AnchorInput.parse("55", "37", "0") is AnchorInputResult.Invalid)
    }
}
