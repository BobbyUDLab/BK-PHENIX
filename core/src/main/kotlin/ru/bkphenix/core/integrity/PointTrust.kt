package ru.bkphenix.core.integrity

import ru.bkphenix.core.PhenixSolution
import ru.bkphenix.core.compare.ComparisonResult

/** Доверие к итоговой точке — одно слово и причина для экрана. */
public enum class PointTrustLevel(public val displayName: String) {
    RELIABLE("Надёжно"),
    LIMITED("Ограниченно"),
    UNRELIABLE("Не подтверждено"),
    ALARM("Не доверять"),
    NONE("Нет решения"),
}

public data class PointTrust(val level: PointTrustLevel, val reason: String) {
    public companion object {
        /**
         * Точка без кворума независимых источников никогда не бывает «надёжной», даже при OK по целостности:
         * её подтверждает один голос (часто сама ГНСС), и подмену такого голоса нечем обнаружить.
         */
        public fun of(solution: PhenixSolution?): PointTrust {
            if (solution?.point == null) return PointTrust(PointTrustLevel.NONE, "нет решения")
            val state = solution.integrity.state
            val firstReason = solution.integrity.reasons.firstOrNull()
            if (state in AlarmFilter.ALARM_STATES) return PointTrust(PointTrustLevel.ALARM, firstReason ?: state.description)
            val strong = solution.comparison.strongConfirmations
            if (!solution.comparison.hasQuorum) {
                return PointTrust(
                    PointTrustLevel.UNRELIABLE,
                    "нет кворума: независимых подтверждений $strong из ${ComparisonResult.QUORUM}",
                )
            }
            if (state != IntegrityState.OK) return PointTrust(PointTrustLevel.LIMITED, firstReason ?: state.description)
            return PointTrust(PointTrustLevel.RELIABLE, "кворум: $strong независимых подтверждения")
        }
    }
}
