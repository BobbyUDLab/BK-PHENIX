package ru.bkphenix.app.data.mapping

import ru.bkphenix.core.PhenixSolution
import ru.bkphenix.core.model.PositionSource
import ru.bkphenix.core.model.SourceGroup
import kotlin.math.sqrt

/** Уровень доверия к источнику в текущей эпохе. */
enum class TrustLevel(val displayName: String) { HIGH("Высокое"), MEDIUM("Среднее"), LOW("Низкое"), REJECTED("Отсеян") }

data class SourceTrust(
    val source: PositionSource,
    val accuracyM: Double,
    /** Отклонение от итоговой точки, метры. */
    val deviationM: Double?,
    val level: TrustLevel,
    /** Группы зависимости (происхождение) наблюдения. */
    val groups: Set<SourceGroup> = source.defaultOrigin,
) {
    val groupsLabel: String get() = groups.sortedBy { it.ordinal }.joinToString(" + ") { it.displayName }
}

object TrustMapping {
    /**
     * Доверие по нормированному отклонению от итога: |d| / √(σᵢ² + σ²).
     * < 1 — высокое, < 2 — среднее, иначе низкое; отсеянные компаратором — [TrustLevel.REJECTED].
     */
    fun fromSolution(solution: PhenixSolution): List<SourceTrust> {
        val estimate = solution.comparison.estimate
        val sigma = solution.comparison.accuracyM ?: 0.0
        val accepted = solution.comparison.accepted.map { obs ->
            val d = estimate?.let { obs.point.distanceTo(it) }
            val z = d?.let { it / sqrt(obs.accuracyM * obs.accuracyM + sigma * sigma) }
            val level = when {
                z == null -> TrustLevel.MEDIUM
                z < 1.0 -> TrustLevel.HIGH
                z < 2.0 -> TrustLevel.MEDIUM
                else -> TrustLevel.LOW
            }
            SourceTrust(obs.source, obs.accuracyM, d, level, obs.origin)
        }
        val rejected = solution.comparison.rejected.map { obs ->
            SourceTrust(obs.source, obs.accuracyM, estimate?.let { obs.point.distanceTo(it) }, TrustLevel.REJECTED, obs.origin)
        }
        return (accepted + rejected).sortedBy { it.level.ordinal }
    }
}
