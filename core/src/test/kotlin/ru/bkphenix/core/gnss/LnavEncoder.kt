package ru.bkphenix.core.gnss

import ru.bkphenix.core.integrity.GpsParity
import ru.bkphenix.core.integrity.NavigationWord

/** Тестовый кодировщик подкадров LNAV: раскладывает поля по битам ИКД и считает чётность. */
internal class LnavEncoder(subframeId: Int) {
    private val bits = IntArray(300)

    init {
        put(1, 8, 0x8B) // преамбула
        put(50, 3, subframeId.toLong())
    }

    fun put(start: Int, len: Int, value: Long): LnavEncoder {
        for (k in 0 until len) {
            val bit = ((value ushr (len - 1 - k)) and 1L).toInt()
            bits[start - 1 + k] = bit
        }
        return this
    }

    fun put2(s1: Int, l1: Int, s2: Int, l2: Int, value: Long): LnavEncoder {
        put(s1, l1, (value ushr l2) and ((1L shl l1) - 1))
        return put(s2, l2, value and ((1L shl l2) - 1))
    }

    fun encode(svid: Int): NavigationSubframe {
        var d29 = false
        var d30 = false
        val words = (0 until 10).map { w ->
            var data = 0
            for (i in 0 until 24) data = (data shl 1) or bits[w * 30 + i]
            val word = GpsParity.encode(data, d29, d30)
            NavigationWord(word, d29, d30).also {
                d29 = (word ushr 1) and 1 == 1
                d30 = word and 1 == 1
            }
        }
        return NavigationSubframe(svid, words)
    }
}
