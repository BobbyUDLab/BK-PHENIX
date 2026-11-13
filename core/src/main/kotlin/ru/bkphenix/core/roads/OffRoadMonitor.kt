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

    /**
     * @param speedMps скорость ГНСС: медленно (дворы, парковки, стоянка) «вне дорог» не считается — там графа
     * часто нет. Ночная поездка 08.11: 2934 эпохи «вне дорог» на медиане 4 км/ч.
     */
    public fun update(result: MatchResult?, speedMps: Double? = null): IntegrityReport {
        if (result == null || result.offRoadThresholdM == Double.MAX_VALUE) { count = 0; return IntegrityReport.OK }
        if (speedMps != null && speedMps < MIN_SPEED_MPS) { count = 0; return IntegrityReport.OK }
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
        /** Ниже этой скорости (18 км/ч) проверка не ведётся. */
        public const val MIN_SPEED_MPS: Double = 5.0
    }
}
