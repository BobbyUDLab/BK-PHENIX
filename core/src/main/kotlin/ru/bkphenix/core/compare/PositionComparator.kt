package ru.bkphenix.core.compare

import ru.bkphenix.core.geo.GeoPoint
import ru.bkphenix.core.model.PositionObservation
import kotlin.math.abs
import kotlin.math.sqrt

/** Результат сверки источников. */
public data class ComparisonResult(
    /** Итоговая оценка или `null`, если наблюдений нет. */
    val estimate: GeoPoint?,
    /** Итоговая погрешность (1σ), метры. */
    val accuracyM: Double?,
    val accepted: List<PositionObservation>,
    val rejected: List<PositionObservation>,
    /** Независимые голоса среди принятых наблюдений. */
    val votes: List<IndependentVote> = Independence.votes(accepted),
) {
    val isConsistent: Boolean get() = rejected.isEmpty()

    /** Число независимых подтверждений итога (групп, а не источников). */
    val independentConfirmations: Int get() = votes.size

    /**
     * Голоса, которым можно верить как независимым: голос, состоящий только из источников,
     * способных опираться на GNSS ([ru.bkphenix.core.model.PositionSource.mayUseGnss]), не считается.
     */
    val strongConfirmations: Int get() = votes.count { v -> v.observations.any { !it.source.mayUseGnss } }

    /** Кворум: итог поддерживают не менее двух сильных независимых групп. */
    val hasQuorum: Boolean get() = strongConfirmations >= QUORUM

    public companion object {
        public const val QUORUM: Int = 2
    }
}

/**
 * Компаратор: сравнивает источники попарно и выбирает взаимно согласованное подмножество
 * с наибольшим числом **независимых голосов** (см. [Independence]); источники одной группы
 * зависимости голосуют один раз. Остальные наблюдения отсекаются как ложные.
 *
 * Два наблюдения согласованы, если расстояние между ними не превышает
 * `gateSigma · √(σ₁² + σ₂²)`. При равенстве голосов выигрывает подмножество с большим
 * весом голосов; при равном весе берётся подмножество с большим числом наблюдений.
 *
 * Совмещение тоже учитывает зависимость: внутри голоса ошибки коррелированы, поэтому голос
 * даёт взвешенное среднее своих наблюдений с погрешностью лучшего из них (без выигрыша √n),
 * а голоса совмещаются между собой по обратной дисперсии.
 */
public class PositionComparator(
    private val gateSigma: Double = DEFAULT_GATE_SIGMA,
) {
    init {
        require(gateSigma > 0.0)
    }

    /**
     * @param priority подтверждённый якорь: если задан, он обязательно входит в итоговое подмножество
     *   и решает спор между группами — всё, что с ним не согласуется, отсекается.
     */
    public fun compare(
        observations: List<PositionObservation>,
        priority: PositionObservation? = null,
    ): ComparisonResult {
        if (observations.isEmpty() && priority == null) return ComparisonResult(null, null, emptyList(), emptyList())

        val sorted = observations.sortedBy { it.accuracyM }
        val room = if (priority != null) MAX_OBSERVATIONS - 1 else MAX_OBSERVATIONS
        val candidates = listOfNotNull(priority) + sorted.take(room)
        val overflow = sorted.drop(room)
        val best = largestConsistentSubset(candidates, required = if (priority != null) 0 else null)
        val accepted = best.map { candidates[it] }
        val rejected = candidates.filterIndexed { i, _ -> i !in best } + overflow

        val votes = Independence.votes(accepted)
        val fused = votes.map { v ->
            val w = v.observations.sumOf { it.effectiveWeight }
            val lat = v.observations.sumOf { it.point.latitude * it.effectiveWeight } / w
            val lon = v.observations.sumOf { it.point.longitude * it.effectiveWeight } / w
            Triple(lat, lon, v.observations.maxOf { it.effectiveWeight })
        }
        val totalWeight = fused.sumOf { it.third }
        return ComparisonResult(
            estimate = GeoPoint(fused.sumOf { it.first * it.third } / totalWeight, fused.sumOf { it.second * it.third } / totalWeight),
            accuracyM = 1.0 / sqrt(totalWeight),
            accepted = accepted,
            rejected = rejected,
            votes = votes,
        )
    }

    public fun areConsistent(a: PositionObservation, b: PositionObservation): Boolean {
        val gate = gateSigma * sqrt(a.accuracyM * a.accuracyM + b.accuracyM * b.accuracyM)
        return a.point.distanceTo(b.point) <= gate
    }

    private fun largestConsistentSubset(obs: List<PositionObservation>, required: Int?): Set<Int> {
        val n = obs.size
        val compatible = Array(n) { i -> BooleanArray(n) { j -> i == j || areConsistent(obs[i], obs[j]) } }
        val start = required ?: 0
        var bestMask = 1 shl start
        var bestVotes = 1
        var bestWeight = obs[start].effectiveWeight
        for (mask in 1 until (1 shl n)) {
            if (required != null && mask and (1 shl required) == 0) continue
            val members = (0 until n).filter { mask and (1 shl it) != 0 }
            val clique = members.all { i -> members.all { j -> compatible[i][j] } }
            if (!clique) continue
            val votes = Independence.count(members.map { obs[it] })
            // Вес — по голосам (лучшее наблюдение голоса), чтобы дубли одной группы не перевешивали.
            val weight = Independence.votes(members.map { obs[it] }).sumOf { v -> v.observations.maxOf { it.effectiveWeight } }
            val sameWeight = abs(weight - bestWeight) <= bestWeight * 1e-9
            val better = votes > bestVotes ||
                (votes == bestVotes && !sameWeight && weight > bestWeight) ||
                (votes == bestVotes && sameWeight && Integer.bitCount(mask) > Integer.bitCount(bestMask))
            if (better) {
                bestMask = mask
                bestVotes = votes
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

/** Вес в компараторе: источник, который может опираться на GNSS, весит меньше. */
internal val PositionObservation.effectiveWeight: Double
    get() = if (source.mayUseGnss) weight * GNSS_ASSISTED_WEIGHT else weight

/** Множитель веса источника, который может опираться на GNSS (эквивалент σ ×2). */
internal const val GNSS_ASSISTED_WEIGHT: Double = 0.25
