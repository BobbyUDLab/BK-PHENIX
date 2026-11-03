package ru.bkphenix.core.model

import kotlin.math.abs

/** Источник метки времени. */
public enum class TimeSource { GNSS, NITZ, SYSTEM }

/** Метка времени от источника с погрешностью, мс. */
public data class TimeObservation(val source: TimeSource, val utcMillis: Long, val uncertaintyMillis: Long)

/**
 * Сверка времени ГНСС с сетевым временем (NITZ). Сильное расхождение — признак подмены ГНСС.
 * @return `true`, если метки согласованы с учётом погрешностей и допуска [toleranceMillis].
 */
public fun isTimeConsistent(gnss: TimeObservation, nitz: TimeObservation, toleranceMillis: Long = 2_000): Boolean =
    abs(gnss.utcMillis - nitz.utcMillis) <= gnss.uncertaintyMillis + nitz.uncertaintyMillis + toleranceMillis
