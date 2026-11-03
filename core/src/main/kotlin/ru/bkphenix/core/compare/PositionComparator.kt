package ru.bkphenix.core.compare

import ru.bkphenix.core.geo.GeoPoint
import ru.bkphenix.core.model.PositionObservation
import kotlin.math.sqrt

/** Результат сверки источников. */
public data class ComparisonResult(
    /** Итоговая оценка или `null`, если наблюдений нет. */
    val estimate: GeoPoint?,
    /** Итоговая погрешность (1σ), метры. */
    val accuracyM: Double?,
    val accepted: List<PositionObservation>,
    val rejected: List<PositionObservation>,
) {
    val isConsistent: Boolean get() = rejected.isEmpty()
}

/**
 * Компаратор: сравнивает источники попарно, выбирает наибольшую взаимно согласованную группу
 * и совмещает её взвешенным средним. Остальные наблюдения отсекаются как ложные.
 *
 * Два наблюдения согласованы, если расстояние между ними не превышает
 * `gateSigma · √(σ₁² + σ₂²)`. При равном размере групп выигрывает группа с большим суммарным весом.
 */
public class PositionComparator(
    private val gateSigma: Double = DEFAULT_GATE_SIGMA,
) {
    init {
        require(gateSigma > 0.0)
    }

    public fun compare(observations: List<PositionObservation>): ComparisonResult {
        if (observations.isEmpty()) return ComparisonResult(null, null, emptyList(), emptyList())

        val candidates = observations.sortedBy { it.accuracyM }.take(MAX_OBSERVATIONS)
        val overflow = observations.sortedBy { it.accuracyM }.drop(MAX_OBSERVATIONS)
        val best = largestConsistentSubset(candidates)
        val accepted = best.map { candidates[it] }
        val rejected = candidates.filterIndexed { i, _ -> i !in best } + overflow

        val totalWeight = accepted.sumOf { it.weight }
        val lat = accepted.sumOf { it.point.latitude * it.weight } / totalWeight
        val lon = accepted.sumOf { it.point.longitude * it.weight } / totalWeight
        return ComparisonResult(
            estimate = GeoPoint(lat, lon),
            accuracyM = 1.0 / sqrt(totalWeight),
            accepted = accepted,
            rejected = rejected,
        )
    }

    public fun areConsistent(a: PositionObservation, b: PositionObservation): Boolean {
        val gate = gateSigma * sqrt(a.accuracyM * a.accuracyM + b.accuracyM * b.accuracyM)
        return a.point.distanceTo(b.point) <= gate
    }

    private fun largestConsistentSubset(obs: List<PositionObservation>): Set<Int> {
        val n = obs.size
        val compatible = Array(n) { i -> BooleanArray(n) { j -> i == j || areConsistent(obs[i], obs[j]) } }
        var bestMask = 1
        var bestSize = 1
        var bestWeight = obs[0].weight
        for (mask in 1 until (1 shl n)) {
            val members = (0 until n).filter { mask and (1 shl it) != 0 }
            if (members.size < bestSize) continue
            val clique = members.all { i -> members.all { j -> compatible[i][j] } }
            if (!clique) continue
            val weight = members.sumOf { obs[it].weight }
            if (members.size > bestSize || weight > bestWeight) {
                bestMask = mask
                bestSize = members.size
                bestWeight = weight
            }
        }
        return (0 until n).filter { bestMask and (1 shl it) != 0 }.toSet()
    }

    public companion object {
        public const val DEFAULT_GATE_SIGMA: Double = 3.0

        /** Перебор подмножеств ограничен; лишние (наименее точные) наблюдения отбрасываются. */
        public const val MAX_OBSERVATIONS: Int = 12
    }
}
