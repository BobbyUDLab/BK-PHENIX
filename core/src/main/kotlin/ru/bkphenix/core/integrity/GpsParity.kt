package ru.bkphenix.core.integrity

/**
 * Чётность слов навигационного сообщения GPS LNAV по IS-GPS-200, табл. 20-XIV.
 *
 * Слово — 30 бит, D1 — старший (бит 29 в [Int]), D25…D30 — биты чётности.
 * Передаваемые биты данных D1…D24 инвертированы, если D30* предыдущего слова равен 1.
 */
public object GpsParity {
    // Номера бит данных d1..d24, входящих в каждое уравнение D25..D30.
    private val EQUATIONS: List<IntArray> = listOf(
        intArrayOf(1, 2, 3, 5, 6, 10, 11, 12, 13, 14, 17, 18, 20, 23),
        intArrayOf(2, 3, 4, 6, 7, 11, 12, 13, 14, 15, 18, 19, 21, 24),
        intArrayOf(1, 3, 4, 5, 7, 8, 12, 13, 14, 15, 16, 19, 20, 22),
        intArrayOf(2, 4, 5, 6, 8, 9, 13, 14, 15, 16, 17, 20, 21, 23),
        intArrayOf(1, 3, 5, 6, 7, 9, 10, 14, 15, 16, 17, 18, 21, 22, 24),
        intArrayOf(3, 5, 6, 8, 9, 10, 11, 13, 15, 19, 22, 23, 24),
    )

    // D25, D27, D30 используют D29*; D26, D28, D29 — D30*.
    private val USES_D29 = booleanArrayOf(true, false, true, false, false, true)

    private const val WORD_MASK = (1 shl 30) - 1

    /** Бит Di (1…30) слова. */
    private fun bit(word: Int, i: Int): Int = (word ushr (30 - i)) and 1

    private fun parity(data24: Int, previousD29: Boolean, previousD30: Boolean): Int {
        var result = 0
        for ((k, eq) in EQUATIONS.withIndex()) {
            var p = if (USES_D29[k]) previousD29.toBit() else previousD30.toBit()
            for (i in eq) p = p xor ((data24 ushr (24 - i)) and 1)
            result = (result shl 1) or p
        }
        return result
    }

    /** Формирует передаваемое слово из 24 бит исходных данных. */
    public fun encode(data24: Int, previousD29: Boolean, previousD30: Boolean): Int {
        require(data24 ushr 24 == 0) { "Данных должно быть 24 бита" }
        val transmitted = if (previousD30) data24 xor 0xFFFFFF else data24
        return (transmitted shl 6) or parity(data24, previousD29, previousD30)
    }

    /** Проверяет чётность принятого 30-битного слова. */
    public fun check(word: Int, previousD29: Boolean, previousD30: Boolean): Boolean {
        val w = word and WORD_MASK
        val transmitted = w ushr 6
        val data = if (previousD30) transmitted xor 0xFFFFFF else transmitted
        return parity(data, previousD29, previousD30) == (w and 0x3F)
    }

    /** Исходные 24 бита данных из принятого слова. */
    public fun decodeData(word: Int, previousD30: Boolean): Int {
        val transmitted = (word and WORD_MASK) ushr 6
        return if (previousD30) transmitted xor 0xFFFFFF else transmitted
    }

    internal fun d29(word: Int): Boolean = bit(word, 29) == 1
    internal fun d30(word: Int): Boolean = bit(word, 30) == 1

    private fun Boolean.toBit(): Int = if (this) 1 else 0
}
