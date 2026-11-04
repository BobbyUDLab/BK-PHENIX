package ru.bkphenix.core.compare

import ru.bkphenix.core.model.PositionObservation
import ru.bkphenix.core.model.SourceGroup

/** Один независимый голос: наблюдения, связанные общими группами зависимости. */
public data class IndependentVote(
    val groups: Set<SourceGroup>,
    val observations: List<PositionObservation>,
)

/**
 * Разбиение наблюдений на независимые голоса. Самостоятельные наблюдения с пересекающимся
 * происхождением сливаются транзитивно: WLS и чип (оба GNSS-сигнал) никогда не подтверждают друг
 * друга. Повторители GNSS присоединяются к первому голосу с общей группой, не склеивая голоса.
 */
public object Independence {
    public fun votes(observations: List<PositionObservation>): List<IndependentVote> {
        val clusters = mutableListOf<Pair<MutableSet<SourceGroup>, MutableList<PositionObservation>>>()
        // Сначала самостоятельные свидетельства: они сливаются транзитивно по общим группам.
        for (o in observations.filter { it.isIndependentEvidence }) {
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
        // Повторители GNSS (сеть/Fused платформы, счисление от фикса GNSS) голоса не дают и не должны
        // склеивать чужие голоса: «Сеть» {соты, Wi-Fi} иначе сливала бы собственные соты и Wi-Fi в один
        // голос, и компаратор отсекал бы согласованную сеть ради лишнего голоса (ложная тревога).
        for (o in observations.filterNot { it.isIndependentEvidence }) {
            val touching = clusters.firstOrNull { (groups, _) -> groups.any { it in o.origin } }
            if (touching != null) touching.second += o else clusters += o.origin.toMutableSet() to mutableListOf(o)
        }
        return clusters.map { (g, m) -> IndependentVote(g.toSortedSet(compareBy { it.ordinal }), m.sortedBy { it.accuracyM }) }
            .sortedBy { it.groups.minOf { g -> g.ordinal } }
    }

    /** Число сильных голосов: в голосе есть хотя бы одно самостоятельное свидетельство. */
    public fun strongCount(votes: List<IndependentVote>): Int =
        votes.count { v -> v.observations.any { it.isIndependentEvidence } }

    public fun count(observations: List<PositionObservation>): Int = votes(observations).size
}
