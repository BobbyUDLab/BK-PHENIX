package ru.bkphenix.core.roads

import ru.bkphenix.core.geo.GeoPoint
import ru.bkphenix.core.track.MotionMode
import kotlin.math.abs
import kotlin.math.ln
import kotlin.math.max

/**
 * Итог привязки одной эпохи. Исходная точка и расстояние до дороги сохраняются всегда —
 * привязанная точка не заменяет и не прячет исходную.
 */
public data class MatchResult(
    val raw: GeoPoint,
    /** Точка на дороге или null, если дороги рядом нет (или режим без привязки). */
    val matched: GeoPoint?,
    /** Расстояние от исходной точки до ближайшей дороги класса режима, м (null — дальше [MapMatcher.SEARCH_M]). */
    val distanceToRoadM: Double?,
    /** Порог, выше которого точка считается «вне дорог», м. */
    val offRoadThresholdM: Double,
) {
    val offRoad: Boolean get() = (distanceToRoadM ?: Double.MAX_VALUE) > offRoadThresholdM
}

/**
 * Привязка к дорогам скрытой марковской моделью (Ньюсон — Крамм, 2009), онлайн-Витерби:
 * состояния — точки на отрезках в радиусе, излучение — гауссиана расстояния до дороги с σ решения,
 * переход — экспонента от разницы пути по графу и расстояния по прямой (за невозможные переходы — штраф).
 *
 * «Авто» — проезжие дороги, «пешком» — тропы, тротуары и улицы, «без привязки» — ничего.
 */
public class MapMatcher(private val graph: RoadGraph) {
    private var prev: List<Pair<RoadCandidate, Double>> = emptyList()
    private var prevPoint: GeoPoint? = null
    private var prevMode: MotionMode? = null

    public fun reset() { prev = emptyList(); prevPoint = null }

    public fun match(raw: GeoPoint, accuracyM: Double?, mode: MotionMode): MatchResult {
        val roadClass = when (mode) {
            MotionMode.CAR -> RoadClass.CAR
            MotionMode.WALK -> RoadClass.FOOT
            MotionMode.NONE -> { reset(); return MatchResult(raw, null, null, Double.MAX_VALUE) }
        }
        if (mode != prevMode) reset()
        prevMode = mode
        val sigma = max(accuracyM ?: DEFAULT_SIGMA_M, MIN_SIGMA_M)
        val threshold = max(OFF_ROAD_SIGMAS * sigma, if (mode == MotionMode.CAR) CAR_MIN_OFF_ROAD_M else WALK_MIN_OFF_ROAD_M)
        val nearest = graph.nearest(raw, SEARCH_M, roadClass)
        val cands = graph.candidates(raw, threshold, roadClass, MAX_CANDIDATES)
        if (cands.isEmpty()) {
            reset()
            return MatchResult(raw, null, nearest?.distanceM, threshold)
        }
        val emission = cands.map { -0.5 * (it.distanceM / sigma).let { z -> z * z } }
        val p = prevPoint
        val scores: List<Double> = if (prev.isEmpty() || p == null) emission else {
            val straight = p.distanceTo(raw)
            val beta = max(MIN_BETA_M, BETA_FRACTION * straight)
            val maxRoute = straight * MAX_ROUTE_FACTOR + 2 * threshold + MIN_ROUTE_SLACK_M
            cands.mapIndexed { j, c ->
                emission[j] + prev.maxOf { (pc, score) ->
                    val route = graph.routeDistanceM(pc, c, maxRoute, roadClass)
                    score + if (route == null) UNREACHABLE_LOG else -abs(route - straight) / beta
                }
            }
        }
        // Нормируем, чтобы оценки не уходили в −∞ на долгой поездке.
        val best = scores.max()
        prev = cands.zip(scores.map { it - best })
        prevPoint = raw
        val winner = cands[scores.indexOf(best)]
        return MatchResult(raw, winner.point, nearest?.distanceM ?: winner.distanceM, threshold)
    }

    public companion object {
        public const val SEARCH_M: Double = 500.0
        public const val MAX_CANDIDATES: Int = 8
        public const val MIN_SIGMA_M: Double = 5.0
        public const val DEFAULT_SIGMA_M: Double = 20.0
        public const val OFF_ROAD_SIGMAS: Double = 3.0
        public const val CAR_MIN_OFF_ROAD_M: Double = 30.0
        public const val WALK_MIN_OFF_ROAD_M: Double = 50.0
        public const val MIN_BETA_M: Double = 5.0
        public const val BETA_FRACTION: Double = 0.2
        public const val MAX_ROUTE_FACTOR: Double = 3.0
        public const val MIN_ROUTE_SLACK_M: Double = 100.0
        public val UNREACHABLE_LOG: Double = ln(1e-6)
    }
}
