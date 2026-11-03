package ru.bkphenix.core.integrity

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.CsvSource
import org.junit.jupiter.params.provider.ValueSource

class GpsParityTest {
    @ParameterizedTest
    @CsvSource("false,false", "false,true", "true,false", "true,true")
    fun `закодированное слово проходит проверку и декодируется`(d29: Boolean, d30: Boolean) {
        val data = 0x8B_12_34 // преамбула TLM 10001011 + данные
        val word = GpsParity.encode(data, d29, d30)
        assertTrue(GpsParity.check(word, d29, d30))
        assertEquals(data, GpsParity.decodeData(word, d30))
    }

    @ParameterizedTest
    @ValueSource(ints = [0, 5, 12, 23, 24, 29])
    fun `инверсия любого одного бита обнаруживается`(bitFromMsb: Int) {
        val word = GpsParity.encode(0x5A_A5_3C, previousD29 = false, previousD30 = true)
        val corrupted = word xor (1 shl (29 - bitFromMsb))
        assertFalse(GpsParity.check(corrupted, previousD29 = false, previousD30 = true))
    }

    @ParameterizedTest
    @ValueSource(ints = [0x000000, 0xFFFFFF, 0x123456])
    fun `цепочка слов проверяется с D29 и D30 предыдущего слова`(seed: Int) {
        var d29 = false
        var d30 = false
        repeat(10) { i ->
            val word = GpsParity.encode((seed + i * 0x010101) and 0xFFFFFF, d29, d30)
            assertTrue(GpsParity.check(word, d29, d30))
            d29 = GpsParity.d29(word)
            d30 = GpsParity.d30(word)
        }
    }

    @ParameterizedTest
    @ValueSource(booleans = [false, true])
    fun `HammingIntegrityEngine сообщает о порче навигационных данных`(corrupt: Boolean) {
        val words = (0 until 10).map { i ->
            val w = GpsParity.encode(i * 1000, false, false)
            NavigationWord(if (corrupt && i % 2 == 0) w xor 1 else w, false, false)
        }
        val ctx = IntegrityContext(
            observations = emptyList(),
            comparison = ru.bkphenix.core.compare.PositionComparator().compare(emptyList()),
            navigationWords = words,
        )
        val state = HammingIntegrityEngine().evaluate(ctx).state
        assertEquals(if (corrupt) IntegrityState.NAV_DATA_CORRUPTED else IntegrityState.OK, state)
    }
}
