package ru.bkphenix.app.data.mapping

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import ru.bkphenix.core.integrity.GpsParity

class NavigationMessageMappingTest {
    /** Корректный подкадр: каждое слово кодируется с D29*, D30* предыдущего. */
    private fun subframe(): List<Int> {
        var d29 = false
        var d30 = false
        return (0 until 10).map { i ->
            GpsParity.encode((0x8B0000 + i * 0x1111) and 0xFFFFFF, d29, d30).also {
                d29 = (it ushr 1) and 1 == 1
                d30 = it and 1 == 1
            }
        }
    }

    @Test
    fun `корректный подкадр LNAV проходит проверку чётности`() {
        val words = NavigationMessageMapping.gpsL1caWords(
            NavigationMessageMapping.TYPE_GPS_L1CA,
            NavigationMessageMapping.pack(subframe()),
        )
        assertEquals(10, words.size)
        assertEquals(0, NavigationMessageMapping.parityFailureCount(words))
    }

    @Test
    fun `порча бита обнаруживается`() {
        val data = NavigationMessageMapping.pack(subframe())
        data[13] = (data[13].toInt() xor 0x04).toByte()
        val words = NavigationMessageMapping.gpsL1caWords(NavigationMessageMapping.TYPE_GPS_L1CA, data)
        assertTrue(NavigationMessageMapping.parityFailureCount(words) >= 1)
    }

    @Test
    fun `старшие два бита 4-байтового слова игнорируются`() {
        val data = NavigationMessageMapping.pack(subframe().map { it or (0b11 shl 30) })
        val words = NavigationMessageMapping.gpsL1caWords(NavigationMessageMapping.TYPE_GPS_L1CA, data)
        assertEquals(0, NavigationMessageMapping.parityFailureCount(words))
    }

    @Test
    fun `другие типы и неполные данные дают пустой список`() {
        assertTrue(NavigationMessageMapping.gpsL1caWords(0x0301, ByteArray(40)).isEmpty())
        assertTrue(NavigationMessageMapping.gpsL1caWords(NavigationMessageMapping.TYPE_GPS_L1CA, ByteArray(12)).isEmpty())
        assertTrue(NavigationMessageMapping.gpsL1caWords(NavigationMessageMapping.TYPE_GPS_L1CA, null).isEmpty())
    }
}
