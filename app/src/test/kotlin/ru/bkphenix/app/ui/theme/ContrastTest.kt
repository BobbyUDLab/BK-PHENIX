package ru.bkphenix.app.ui.theme

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** WCAG 2.x AA: обычный текст ≥ 4,5:1 на фоне и на карточках. */
class ContrastTest {
    private fun assertAa(name: String, fg: Long, vararg bgs: Long) = bgs.forEach { bg ->
        val r = PhenixPalette.contrast(fg, bg)
        assertTrue("$name на ${PhenixPalette.hex(bg)}: %.2f < 4.5".format(r), r >= 4.5)
    }

    @Test
    fun `OLED — фон чистый чёрный`() = assertEquals(0xFF000000L, PhenixPalette.Oled.BACKGROUND)

    @Test
    fun `OLED — статусы, текст и акценты читаемы на чёрном`() = with(PhenixPalette.Oled) {
        listOf("ок" to STATUS_OK, "подозрение" to STATUS_WARN, "отвергнут" to STATUS_BAD, "текст" to ON_SURFACE,
            "вторичный текст" to ON_SURFACE_VARIANT, "циан" to PRIMARY, "оранжевый" to SECONDARY, "золото" to TERTIARY)
            .forEach { (n, c) -> assertAa(n, c, BACKGROUND, SURFACE_CONTAINER) }
    }

    @Test
    fun `светлая — статусы, текст и акценты читаемы на белом`() = with(PhenixPalette.Light) {
        listOf("ок" to STATUS_OK, "подозрение" to STATUS_WARN, "отвергнут" to STATUS_BAD, "текст" to ON_SURFACE,
            "вторичный текст" to ON_SURFACE_VARIANT, "акцент" to PRIMARY, "оранжевый" to SECONDARY, "золото" to TERTIARY)
            .forEach { (n, c) -> assertAa(n, c, BACKGROUND, SURFACE_CONTAINER) }
    }

    @Test
    fun `контраст считается по WCAG`() {
        assertEquals(21.0, PhenixPalette.contrast(0xFF000000L, 0xFFFFFFFFL), 1e-9)
        assertEquals(1.0, PhenixPalette.contrast(0xFF14CFFEL, 0xFF14CFFEL), 1e-9)
    }
}

class DarkMapStyleTest {
    private val style = java.io.File("src/main/assets/map/style-ru-dark.json").readText()

    @Test
    fun `тёмный стиль карты — чёрный фон, тёмные спрайты, подписи читаемы`() {
        assertTrue(style.contains("\"background-color\":\"#000000\""))
        assertTrue(style.contains("asset://sprites/dark"))
        Regex("\"text-color\":\"(#[0-9A-Fa-f]{6})\"").findAll(style).forEach { m ->
            val c = 0xFF000000L or m.groupValues[1].substring(1).toLong(16)
            assertTrue(m.value, PhenixPalette.contrast(c, 0xFF000000L) >= 4.5)
        }
    }
}
