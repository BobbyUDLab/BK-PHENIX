package ru.bkphenix.core.roads

import ru.bkphenix.core.integrity.IntegrityReport
import ru.bkphenix.core.integrity.IntegrityState
import kotlin.math.roundToInt

/**
 * Сигнал целостности «вне дорог»: если исходная точка держится дальше порога от дороги класса режима
 * [CONSECUTIVE] эпох подряд — это либо подмена/увод ГНСС, либо дороги нет в графе. Привязанная точка
 * при этом не выдаётся за правду: состояние понижается до «подозрительно» с причиной и расстоянием.
 */
public class OffRoadMonitor(private val consecutive: Int = CONSECUTIVE) {
    private var count = 0

    public fun update(result: MatchResult?): IntegrityReport {
        if (result == null || result.offRoadThresholdM == Double.MAX_VALUE) { count = 0; return IntegrityReport.OK }
        count = if (result.offRoad) count + 1 else 0
        if (count < consecutive) return IntegrityReport.OK
        val d = result.distanceToRoadM
        val text = if (d == null) "дальше ${MapMatcher.SEARCH_M.roundToInt()} м" else "${d.roundToInt()} м"
        return IntegrityReport(
            IntegrityState.SUSPICIOUS,
            listOf("Позиция в $text от дороги (порог ${result.offRoadThresholdM.roundToInt()} м) $count эпох подряд: возможна подмена ГНСС или дороги нет в графе"),
        )
    }

    public fun reset() { count = 0 }

    public companion object {
        public const val CONSECUTIVE: Int = 3
    }
}
