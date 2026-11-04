package ru.bkphenix.core.compare

import ru.bkphenix.core.model.PositionObservation
import ru.bkphenix.core.model.SourceGroup

/** Один независимый голос: наблюдения, связанные общими группами зависимости. */
public data class IndependentVote(
    val groups: Set<SourceGroup>,
    val observations: List<PositionObservation>,
)

/**
 * Разбиение наблюдений на независимые голоса. Наблюдения с пересекающимся происхождением
 * сливаются транзитивно: сеть {сотовая, Wi-Fi} объединяет вышки и Wi-Fi в один голос,
 * а WLS и чип (оба GNSS-сигнал) никогда не подтверждают друг друга.
 */
public object Independence {
    public fun votes(observations: List<PositionObservation>): List<IndependentVote> {
        val clusters = mutableListOf<Pair<MutableSet<SourceGroup>, MutableList<PositionObservation>>>()
        for (o in observations) {
            val touching = clusters.filter { (groups, _) -> groups.any { it in o.origin } }
            val groups = o.origin.toMutableSet()
            val members = mutableListOf(o)
            touching.forEach { (g, m) ->
                groups += g
                members += m
            }
            clusters.removeAll(touching)
            clusters += groups to members
        }
        return clusters.map { (g, m) -> IndependentVote(g.toSortedSet(compareBy { it.ordinal }), m.sortedBy { it.accuracyM }) }
            .sortedBy { it.groups.minOf { g -> g.ordinal } }
    }

    public fun count(observations: List<PositionObservation>): Int = votes(observations).size
}
