package ru.bkphenix.core.integrity

import ru.bkphenix.core.geo.GeoPoint
import ru.bkphenix.core.inertial.MotionSummary
import ru.bkphenix.core.model.PositionObservation
import kotlin.math.abs
import kotlin.math.roundToInt

/** Итог сверки движения ГНСС с датчиками за эпоху. */
public data class MotionCheck(
    val report: IntegrityReport,
    /** ГНСС не принимать (устойчивое противоречие шагам или покою). */
    val veto: Boolean,
    /** Скорость ГНСС за окно, м/с, если считалась. */
    val gnssSpeedMps: Double?,
)

/**
 * Сверка движения ГНСС с датчиками телефона — без сети, SIM и эфемерид.
 *
 * Во всех полевых эпизодах 05–07.11.2026 подменённая точка у Шереметьево едет ≈ 100 км/ч и кружит,
 * а человек идёт пешком с собакой или стоит. Правила (каждое — только при устойчивом противоречии):
 * - **шаги:** датчики видят регулярную ходьбу ([MotionSummary.walking]), а ГНСС едет быстрее [maxWalkSpeedMps];
 * - **покой:** телефон неподвижен ≥ [minStillS] с, а ГНСС едет быстрее [maxStillSpeedMps];
 * - **поворот:** ГНСС на скорости разворачивается на [turnMismatchDeg]° больше, чем показывает гироскоп
 *   (резкий разворот, которого машина не делала).
 * «Шаги» и «покой» после [vetoEpochs] эпох подряд запрещают ГНСС (как грубая ошибка высоты); «поворот» — только тревога.
 */
public class MotionConsistencyMonitor(
    private val maxWalkSpeedMps: Double = 8.0,
    private val minStillS: Double = 20.0,
    private val maxStillSpeedMps: Double = 10.0,
    private val minTurnSpeedMps: Double = 8.0,
    private val turnMismatchDeg: Double = 25.0,
    private val alarmEpochs: Int = 5,
    private val turnAlarmEpochs: Int = 3,
    private val vetoEpochs: Int = 20,
    private val clearEpochs: Int = 10,
) {
    private val history = ArrayDeque<Pair<Long, GeoPoint>>()
    private var bad = 0
    private var turnBad = 0
    private var clean = 0
    private var vetoed = false
    private var lastReason: String? = null

    public fun update(gnss: PositionObservation?, motion: MotionSummary?, nowMillis: Long): MotionCheck {
        gnss?.let { g ->
            if (history.isEmpty() || g.timestampMillis > history.last().first) history.addLast(g.timestampMillis to g.point)
        }
        while (history.isNotEmpty() && nowMillis - history.first().first > HISTORY_MS) history.removeFirst()
        val speed = speedOver(SPEED_WINDOW_MS)
        if (motion == null || speed == null) return result(null, null, speed)

        val reason = when {
            motion.walking && speed > maxWalkSpeedMps ->
                "ГНСС движется ${kmh(speed)} км/ч, а датчики видят шаги (${motion.steps} за 10 с) — возможна подмена ГНСС"
            motion.stationaryS >= minStillS && speed > maxStillSpeedMps ->
                "ГНСС движется ${kmh(speed)} км/ч, а телефон неподвижен ${motion.stationaryS.roundToInt()} с — возможна подмена ГНСС"
            else -> null
        }
        val turn = turnMismatch(motion)
        return result(reason, turn, speed)
    }

    private fun result(reason: String?, turn: String?, speed: Double?): MotionCheck {
        if (reason != null) { bad++; clean = 0; lastReason = reason } else { bad = 0 }
        if (turn != null) turnBad++ else turnBad = 0
        if (reason == null && turn == null) clean++
        if (bad >= vetoEpochs) vetoed = true
        if (vetoed && clean >= clearEpochs) vetoed = false
        val reasons = buildList {
            if (bad >= alarmEpochs || vetoed) add((lastReason ?: "ГНСС противоречит датчикам движения") + if (vetoed) "; позиция ГНСС отброшена" else "")
            if (turnBad >= turnAlarmEpochs && turn != null) add(turn)
        }
        val report = if (reasons.isEmpty()) IntegrityReport.OK else IntegrityReport(IntegrityState.SPOOFING_SUSPECTED, reasons)
        return MotionCheck(report, vetoed, speed)
    }

    /** Скорость по хорде за последние [windowMs]; null — мало точек. */
    private fun speedOver(windowMs: Long): Double? {
        val last = history.lastOrNull() ?: return null
        val first = history.firstOrNull { last.first - it.first <= windowMs } ?: return null
        val dt = (last.first - first.first) / 1000.0
        if (dt < MIN_SPEED_SPAN_S) return null
        return first.second.distanceTo(last.second) / dt
    }

    private fun turnMismatch(m: MotionSummary): String? {
        val yaw = m.yawChangeDeg ?: return null
        val last = history.lastOrNull() ?: return null
        fun at(msAgo: Long) = history.lastOrNull { last.first - it.first >= msAgo }
        val a0 = at(TURN_FROM_MS + SEGMENT_MS) ?: return null
        val a1 = at(TURN_FROM_MS) ?: return null
        val b0 = at(TURN_TO_MS + SEGMENT_MS) ?: return null
        val b1 = at(TURN_TO_MS) ?: return null
        val vA = a0.second.distanceTo(a1.second) / ((a1.first - a0.first) / 1000.0).coerceAtLeast(1.0)
        val vB = b0.second.distanceTo(b1.second) / ((b1.first - b0.first) / 1000.0).coerceAtLeast(1.0)
        if (vA < minTurnSpeedMps || vB < minTurnSpeedMps) return null
        val course = abs(wrap180(b0.second.bearingTo(b1.second) - a0.second.bearingTo(a1.second)))
        if (course - yaw <= turnMismatchDeg) return null
        return "ГНСС повернула на ${course.roundToInt()}° за 20 с, а гироскоп — на ${yaw.roundToInt()}°: возможна подмена ГНСС"
    }

    public fun reset() {
        history.clear(); bad = 0; turnBad = 0; clean = 0; vetoed = false; lastReason = null
    }

    private companion object {
        const val HISTORY_MS = 40_000L
        const val SPEED_WINDOW_MS = 10_000L
        const val MIN_SPEED_SPAN_S = 5.0
        /** Курс ГНСС сравнивается по отрезкам 5 с, оканчивающимся 25 и 5 с назад — как поворот гироскопа (25…5 с). */
        const val SEGMENT_MS = 5_000L
        const val TURN_FROM_MS = 20_000L
        const val TURN_TO_MS = 0L
        fun kmh(v: Double) = (v * 3.6).roundToInt()
        fun wrap180(d: Double) = ((d + 540.0) % 360.0) - 180.0
    }
}
