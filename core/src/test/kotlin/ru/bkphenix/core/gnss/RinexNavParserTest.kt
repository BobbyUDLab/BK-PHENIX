package ru.bkphenix.core.gnss

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertSame
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import kotlin.math.sqrt

class RinexNavParserTest {
    private val text = javaClass.getResource("/rinex_nav_sample.rnx")!!.readText()

    @Test
    fun `разбирает записи GPS и пропускает SBAS`() {
        val list = RinexNavParser.parseGps(text)
        assertEquals(listOf(7, 20, 27, 23, 16, 10), list.map { it.svid })
        val g7 = list.first()
        assertEquals(2443 % 1024, g7.weekMod1024)
        assertEquals(252_000.0, g7.toe)
        // 3 ноября 2026 — вторник: 2 сут от начала недели + 22 ч.
        assertEquals(2 * 86_400.0 + 22 * 3600.0, g7.toc)
        assertEquals(-0.238121021539e-03, g7.af0, 1e-18)
        assertEquals(0.515374831390e+04, g7.sqrtA, 1e-9)
        assertEquals(0.210353556322e-01, g7.eccentricity, 1e-15)
        assertEquals(-0.107102096081e-07, g7.tgd, 1e-20)
        assertEquals(43, g7.iode)
        assertEquals(43, g7.iodc)
        assertEquals(0, g7.health)
        assertEquals(0, g7.uraIndex)
    }

    @Test
    fun `положение спутника из RINEX на орбите GPS`() {
        val g7 = RinexNavParser.parseGps(text).first()
        val p = g7.stateAt(g7.toe + 600).position
        val r = sqrt(p.x * p.x + p.y * p.y + p.z * p.z)
        val a = g7.sqrtA * g7.sqrtA
        assertTrue(r in a * (1 - g7.eccentricity) - 1.0..a * (1 + g7.eccentricity) + 1.0, "r = $r")
    }

    @Test
    fun `RINEX 4 берёт только LNAV`() {
        val body = text.substringAfter("END OF HEADER").lines().drop(1)
        val g7 = body.subList(0, 8).joinToString("\n")
        val v4 = "     4.01           N: GNSS NAV DATA    M: MIXED            RINEX VERSION / TYPE\n" +
            "                                                            END OF HEADER\n" +
            "> EPH G07 LNAV\n$g7\n> EPH G07 CNAV\n$g7\n"
        assertEquals(1, RinexNavParser.parseGps(v4).size)
    }

    @Test
    fun `хранилище выбирает внешние эфемериды по времени и предпочитает собственные`() {
        val base = RinexNavParser.parseGps(text).first()
        val early = base.copy(toe = 244_800.0)
        val store = EphemerisStore()
        store.replaceExternal(listOf(early, base))
        assertEquals(1, store.externalSize)
        assertSame(base, store.select(7, 2443, 252_000.0 + 600))
        assertSame(early, store.select(7, 2443, 244_800.0 + 600))
        assertNull(store.select(7, 2443, 252_000.0 + 3 * 3600))
        assertNull(store.select(7, 2444, 252_000.0), "неделя другая — эфемериды не подходят")
        val own = base.copy(iode = 99)
        store.put(own)
        assertSame(own, store.select(7, 2443, 252_000.0))
        assertTrue(7 in store.availableSvids)
    }
}
