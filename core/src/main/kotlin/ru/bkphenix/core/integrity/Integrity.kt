package ru.bkphenix.core.integrity

import ru.bkphenix.core.compare.ComparisonResult
import ru.bkphenix.core.model.PositionObservation
import ru.bkphenix.core.model.PositionSource
import ru.bkphenix.core.model.SourceGroup
import ru.bkphenix.core.model.TimeObservation
import ru.bkphenix.core.model.TimeSource
import ru.bkphenix.core.model.isTimeConsistent

/** Состояние целостности, упорядочено по возрастанию серьёзности. */
public enum class IntegrityState(public val description: String) {
    OK("Источники согласованы"),
    NO_DATA("Нет данных о местоположении"),
    SUSPICIOUS("Источники согласованы, но подтверждение недостаточно"),
    UNCONFIRMED("Восстановлено без независимого подтверждения"),
    INCONSISTENT("Источники не согласуются между собой"),
    GNSS_LOST("ГНСС недоступна: возможно глушение"),
    NAV_DATA_CORRUPTED("Ошибки чётности навигационных сообщений"),
    SPOOFING_SUSPECTED("Подозрение на подмену ГНСС"),
}

public data class IntegrityReport(val state: IntegrityState, val reasons: List<String>) {
    public companion object {
        public val OK: IntegrityReport = IntegrityReport(IntegrityState.OK, emptyList())

        /** Объединяет отчёты: итоговое состояние — самое серьёзное. */
        public fun merge(reports: List<IntegrityReport>): IntegrityReport {
            val worst = reports.maxByOrNull { it.state.ordinal } ?: return OK
            return IntegrityReport(worst.state, reports.flatMap { it.reasons }.distinct())
        }
    }
}

/** Слово навигационного сообщения GPS LNAV (30 бит) вместе с D29*, D30* предыдущего слова. */
public data class NavigationWord(val word: Int, val previousD29: Boolean, val previousD30: Boolean)

/** Входные данные для контроля целостности в одной эпохе. */
public data class IntegrityContext(
    val observations: List<PositionObservation>,
    val comparison: ComparisonResult,
    val navigationWords: List<NavigationWord> = emptyList(),
    val timeObservations: List<TimeObservation> = emptyList(),
)

public fun interface IntegrityEngine {
    public fun evaluate(context: IntegrityContext): IntegrityReport
}

/** Проверка согласованности решения: отсутствие ГНСС, отсев ГНСС компаратором, расхождение времени с NITZ. */
public class ConsistencyIntegrityEngine : IntegrityEngine {
    override fun evaluate(context: IntegrityContext): IntegrityReport {
        val reports = mutableListOf<IntegrityReport>()
        if (context.observations.isEmpty()) return IntegrityReport(IntegrityState.NO_DATA, listOf("Нет наблюдений"))

        val gnss = context.observations.filter { it.source.isSatellite }
        if (gnss.isEmpty()) {
            reports += IntegrityReport(IntegrityState.GNSS_LOST, listOf("Нет решения ГНСС при наличии других источников"))
        }
        val rejectedGnss = context.comparison.rejected.filter { it.source.isSatellite }
        // Подменой считаем только спор с независимым свидетелем; расхождение чипа и WLS — внутри одной группы.
        val independentWitness = context.comparison.accepted.any { SourceGroup.GNSS_SIGNAL !in it.origin }
        if (rejectedGnss.isNotEmpty() && independentWitness) {
            reports += IntegrityReport(
                IntegrityState.SPOOFING_SUSPECTED,
                listOf("Решение ГНСС не согласуется с независимыми источниками"),
            )
        } else if (rejectedGnss.isNotEmpty()) {
            reports += IntegrityReport(
                IntegrityState.INCONSISTENT,
                listOf("Решения ГНСС расходятся между собой: " + rejectedGnss.joinToString { it.source.displayName }),
            )
        }
        // «Несогласованно» — только когда отсеян самостоятельный свидетель (соты, Wi-Fi, ориентир, якорь).
        // Счисление дрейфует по модели, а сеть платформы запаздывает и повторяет ГНСС: их отсев — норма, а не
        // противоречие. В 0.8 он давал INCONSISTENT в 1849 из 2271 эпох обычной поездки (08.11, 00:37).
        val others = context.comparison.rejected.filter {
            !it.source.isSatellite && it.isGnssIndependent && it.source != PositionSource.DEAD_RECKONING
        }
        if (others.isNotEmpty()) {
            reports += IntegrityReport(
                IntegrityState.INCONSISTENT,
                others.map { "Отсеян источник: ${it.source.displayName}" },
            )
        }
        val comparison = context.comparison
        if (comparison.estimate != null && !comparison.hasQuorum) {
            val weak = comparison.votes.filter { v -> v.observations.none { it.isIndependentEvidence } }
            val why = buildString {
                append("Итог подтверждают ${comparison.strongConfirmations} незав. групп(ы), нужно ${ComparisonResult.QUORUM}")
                if (weak.isNotEmpty()) {
                    append("; не засчитано: ")
                    append(weak.flatMap { it.observations }.joinToString { it.source.displayName })
                    append(" (повторяет GNSS)")
                }
            }
            reports += IntegrityReport(IntegrityState.SUSPICIOUS, listOf(why))
        }
        val gnssTime = context.timeObservations.firstOrNull { it.source == TimeSource.GNSS }
        val nitzTime = context.timeObservations.firstOrNull { it.source == TimeSource.NITZ }
        if (gnssTime != null && nitzTime != null && !isTimeConsistent(gnssTime, nitzTime)) {
            reports += IntegrityReport(IntegrityState.SPOOFING_SUSPECTED, listOf("Время ГНСС расходится с NITZ"))
        }
        return IntegrityReport.merge(reports)
    }
}

/** Контроль чётности навигационных сообщений GPS (коды Хэмминга, IS-GPS-200). */
public class HammingIntegrityEngine(
    private val maxFailureRatio: Double = 0.1,
) : IntegrityEngine {
    override fun evaluate(context: IntegrityContext): IntegrityReport {
        val words = context.navigationWords
        if (words.isEmpty()) return IntegrityReport.OK
        val failures = words.count { !GpsParity.check(it.word, it.previousD29, it.previousD30) }
        val ratio = failures.toDouble() / words.size
        return if (ratio > maxFailureRatio) {
            IntegrityReport(
                IntegrityState.NAV_DATA_CORRUPTED,
                listOf("Ошибки чётности: $failures из ${words.size} слов"),
            )
        } else {
            IntegrityReport.OK
        }
    }
}

/** Комбинация нескольких движков; итог — самое серьёзное состояние. */
public class CompositeIntegrityEngine(private val engines: List<IntegrityEngine>) : IntegrityEngine {
    override fun evaluate(context: IntegrityContext): IntegrityReport =
        IntegrityReport.merge(engines.map { it.evaluate(context) })
}
