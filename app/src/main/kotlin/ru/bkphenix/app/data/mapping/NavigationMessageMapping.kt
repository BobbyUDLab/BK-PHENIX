package ru.bkphenix.app.data.mapping

import ru.bkphenix.core.integrity.GpsParity
import ru.bkphenix.core.integrity.NavigationWord

/** Разбор GnssNavigationMessage типа GPS L1 C/A (LNAV) в слова для проверки чётности ядром. */
object NavigationMessageMapping {
    /** GnssNavigationMessage.TYPE_GPS_L1CA. */
    const val TYPE_GPS_L1CA: Int = 0x0101

    private const val WORDS_PER_SUBFRAME = 10
    private const val BYTES_PER_WORD = 4

    /**
     * По документации Android подкадр GPS L1 C/A — 10 слов по 30 бит, каждое в младших 30 битах
     * 4-байтового слова, старший байт первым. D29* и D30* для первого слова (TLM) равны 0:
     * биты 23–24 десятого слова подбираются так, что его D29 = D30 = 0 (IS-GPS-200).
     *
     * @return слова подкадра или пустой список, если тип не LNAV или данные неполные.
     */
    fun gpsL1caWords(type: Int, data: ByteArray?): List<NavigationWord> {
        if (type != TYPE_GPS_L1CA || data == null || data.size < WORDS_PER_SUBFRAME * BYTES_PER_WORD) return emptyList()
        var prevD29 = false
        var prevD30 = false
        return (0 until WORDS_PER_SUBFRAME).map { i ->
            var raw = 0
            for (b in 0 until BYTES_PER_WORD) raw = (raw shl 8) or (data[i * BYTES_PER_WORD + b].toInt() and 0xFF)
            val word = raw and 0x3FFFFFFF
            NavigationWord(word, prevD29, prevD30).also {
                prevD29 = (word ushr 1) and 1 == 1
                prevD30 = word and 1 == 1
            }
        }
    }

    /** Обратная операция — упаковка слов (используется в тестах и для журналирования). */
    fun pack(words: List<Int>): ByteArray = ByteArray(words.size * BYTES_PER_WORD).also { out ->
        words.forEachIndexed { i, w ->
            for (b in 0 until BYTES_PER_WORD) out[i * BYTES_PER_WORD + b] = (w ushr (24 - 8 * b)).toByte()
        }
    }

    /** Доля слов с ошибкой чётности, для диагностики. */
    fun parityFailureCount(words: List<NavigationWord>): Int =
        words.count { !GpsParity.check(it.word, it.previousD29, it.previousD30) }
}
