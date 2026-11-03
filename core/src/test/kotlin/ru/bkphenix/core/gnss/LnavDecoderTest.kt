package ru.bkphenix.core.gnss

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import ru.bkphenix.core.integrity.NavigationWord

class LnavDecoderTest {
    private val pi = 3.1415926535898
    private fun p2(e: Int) = Math.scalb(1.0, e)
    private fun tc(v: Long, len: Int) = v and ((1L shl len) - 1) // дополнительный код

    private fun subframes(svid: Int, iode: Int = 77): List<NavigationSubframe> = listOf(
        LnavEncoder(1).put(61, 10, 417).put(73, 4, 2).put(77, 6, 0).put2(83, 2, 211, 8, (1L shl 8) or iode.toLong())
            .put(197, 8, tc(-12, 8)).put(219, 16, 18_900).put(241, 8, 0).put(249, 16, tc(-50, 16))
            .put(271, 22, tc(-123_456, 22)).encode(svid),
        LnavEncoder(2).put(61, 8, iode.toLong()).put(69, 16, tc(-1_000, 16)).put(91, 16, 12_000)
            .put2(107, 8, 121, 24, tc(-1_000_000_000, 32)).put(151, 16, tc(-2_000, 16))
            .put2(167, 8, 181, 24, 42_000_000).put(211, 16, 3_000).put2(227, 8, 241, 24, 2_702_000_000)
            .put(271, 16, 18_900).encode(svid),
        LnavEncoder(3).put(61, 16, 100).put2(77, 8, 91, 24, 500_000_000).put(121, 16, tc(-100, 16))
            .put2(137, 8, 151, 24, 660_000_000).put(181, 16, 7_000).put2(197, 8, 211, 24, tc(-300_000_000, 32))
            .put(241, 24, tc(-20_000, 24)).put(271, 8, iode.toLong()).put(279, 14, tc(-300, 14)).encode(svid),
    )

    @Test
    fun `подкадры 1–3 собираются в эфемериды с верным масштабом полей`() {
        val store = EphemerisStore()
        val updates = subframes(5).map(store::add)
        assertEquals(listOf(false, false, true), updates)
        val e = store[5]!!
        assertEquals(417, e.weekMod1024)
        assertEquals(256 + 77, e.iodc)
        assertEquals(-12 * p2(-31), e.tgd)
        assertEquals(18_900 * 16.0, e.toc)
        assertEquals(-50 * p2(-43), e.af1)
        assertEquals(-123_456 * p2(-31), e.af0)
        assertEquals(-1_000 * p2(-5), e.crs)
        assertEquals(12_000 * p2(-43) * pi, e.deltaN)
        assertEquals(-1_000_000_000 * p2(-31) * pi, e.m0)
        assertEquals(42_000_000 * p2(-33), e.eccentricity)
        assertEquals(2_702_000_000 * p2(-19), e.sqrtA)
        assertEquals(500_000_000 * p2(-31) * pi, e.omega0)
        assertEquals(660_000_000 * p2(-31) * pi, e.i0)
        assertEquals(-300_000_000 * p2(-31) * pi, e.omega)
        assertEquals(-20_000 * p2(-43) * pi, e.omegaDot)
        assertEquals(-300 * p2(-43) * pi, e.iDot)
        assertTrue(e.isHealthy)
    }

    @Test
    fun `несогласованные IODE не дают эфемерид`() {
        val store = EphemerisStore()
        val a = subframes(7, iode = 10)
        val b = subframes(7, iode = 11)
        store.add(a[0])
        store.add(a[1])
        assertFalse(store.add(b[2]))
        assertNull(store[7])
    }

    @Test
    fun `подкадр с ошибкой чётности отбрасывается`() {
        val sf = subframes(9)[1]
        val broken = sf.copy(words = sf.words.mapIndexed { i, w -> if (i == 4) NavigationWord(w.word xor (1 shl 10), w.previousD29, w.previousD30) else w })
        assertNull(LnavDecoder.subframeId(broken))
        assertEquals(2, LnavDecoder.subframeId(sf))
    }
}
