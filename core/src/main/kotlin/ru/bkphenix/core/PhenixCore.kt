package ru.bkphenix.core

import ru.bkphenix.core.anchor.AnchorEvaluation
import ru.bkphenix.core.anchor.AnchorManager
import ru.bkphenix.core.anchor.AnchorState
import ru.bkphenix.core.compare.ComparisonResult
import ru.bkphenix.core.compare.PositionComparator
import ru.bkphenix.core.geo.GeoPoint
import ru.bkphenix.core.gnss.DetectorKind
import ru.bkphenix.core.gnss.DetectorVerdict
import ru.bkphenix.core.gnss.GnssIntegrityProcessor
import ru.bkphenix.core.gnss.GnssIntegrityResult
import ru.bkphenix.core.gnss.NavigationSubframe
import ru.bkphenix.core.gnss.RawGnssEpoch
import ru.bkphenix.core.integrity.AlarmFilter
import ru.bkphenix.core.integrity.CompositeIntegrityEngine
import ru.bkphenix.core.integrity.ConsistencyIntegrityEngine
import ru.bkphenix.core.integrity.ContinuityMonitor
import ru.bkphenix.core.integrity.HammingIntegrityEngine
import ru.bkphenix.core.integrity.IntegrityContext
import ru.bkphenix.core.integrity.IntegrityEngine
import ru.bkphenix.core.integrity.IntegrityReport
import ru.bkphenix.core.integrity.IntegrityState
import ru.bkphenix.core.integrity.NavigationWord
import ru.bkphenix.core.model.PositionObservation
import ru.bkphenix.core.model.PositionSource
import ru.bkphenix.core.model.TimeObservation

/** Итоговое решение одной эпохи. */
public data class PhenixSolution(
    val point: GeoPoint?,
    val accuracyM: Double?,
    val integrity: IntegrityReport,
    val anchor: AnchorEvaluation,
    val comparison: ComparisonResult,
    /** Контроль по сырым измерениям ГНСС, если они были в эпохе. */
    val gnss: GnssIntegrityResult? = null,
)

/**
 * Единый контур доверия: RAIM и детекторы подмены по сырым измерениям → контроль непрерывности → компаратор → контроль
 * целостности → проверка якорем. Собственное решение WLS идёт в компаратор как источник
 * [PositionSource.GNSS_WLS] той же группы, что и чип ([ru.bkphenix.core.model.SourceGroup.GNSS_SIGNAL]):
 * оно уточняет GNSS-голос, но никогда не считается независимым подтверждением чипа.
 */
public class PhenixCore(
    private val comparator: PositionComparator = PositionComparator(),
    private val integrityEngine: IntegrityEngine = CompositeIntegrityEngine(
        listOf(ConsistencyIntegrityEngine(), HammingIntegrityEngine()),
    ),
    public val anchorManager: AnchorManager = AnchorManager(),
    public val gnssProcessor: GnssIntegrityProcessor = GnssIntegrityProcessor(),
    public val continuity: ContinuityMonitor = ContinuityMonitor(),
    private val alarmFilter: AlarmFilter = AlarmFilter(),
) {
    public fun solve(
        observations: List<PositionObservation>,
        nowMillis: Long,
        navigationWords: List<NavigationWord> = emptyList(),
        timeObservations: List<TimeObservation> = emptyList(),
        rawGnss: RawGnssEpoch? = null,
        navigationSubframes: List<NavigationSubframe> = emptyList(),
    ): PhenixSolution {
        gnssProcessor.addSubframes(navigationSubframes)
        val words = navigationWords + navigationSubframes.flatMap { it.words }
        val gnss = rawGnss?.let { gnssProcessor.process(it, observations, timeObservations) }
        val wls = gnss?.ownSolution?.let {
            PositionObservation(PositionSource.GNSS_WLS, it.point, it.horizontalSigmaM.coerceAtLeast(MIN_WLS_SIGMA_M), nowMillis)
        }
        // Кинематика и непрерывность: скачок GNSS отсекается до компаратора.
        val continuityResult = continuity.check(observations + listOfNotNull(wls), nowMillis)
        // Якорь проверяется против решения без него, а в итоговом решении он решает спор групп.
        val unanchored = comparator.compare(continuityResult.kept)
        val anchorObs = anchorManager.observation(nowMillis)
        // Только актуальный якорь решает спор групп; устаревший — обычный голос и никого не отсеивает
        // (0.5.1, авто: старый якорь с ростом σ 3 м/с отсеивал все источники, позиция замирала, трек не шёл).
        val compared = when {
            anchorObs == null -> unanchored
            anchorManager.isPriority -> comparator.compare(continuityResult.kept, priority = anchorObs)
            else -> comparator.compare(continuityResult.kept + anchorObs)
        }
        val comparison = compared.copy(rejected = compared.rejected + continuityResult.dropped)
        // При сырых данных время сверяет детектор NITZ с подробностями; общий движок его не дублирует.
        val timeForEngine = if (gnss != null) emptyList() else timeObservations
        var integrity = integrityEngine.evaluate(
            IntegrityContext(observations, comparison, words, timeForEngine),
        )
        // Тревоги по сырым данным (RAIM, детекторы) — через гистерезис: у домов одиночные эпохи FAULT обычны.
        // Расхождение с NITZ на секунды — сильный признак, без задержки.
        if (gnss != null) {
            val filtered = alarmFilter.filter(gnss.report)
            val nitz = gnss.detectors.any { it.kind == DetectorKind.NITZ_TIME && it.verdict == DetectorVerdict.ALERT }
            integrity = IntegrityReport.merge(listOf(integrity, if (nitz) gnss.report else filtered))
        }
        integrity = IntegrityReport.merge(listOf(integrity, continuityResult.report))
        val spoof = integrity.state == IntegrityState.SPOOFING_SUSPECTED
        val anchor = anchorManager.evaluate(
            unanchored.estimate, unanchored.accuracyM, nowMillis, unanchored.hasQuorum, spoof,
            unanchored.accepted.map { it.source.displayName }.distinct().joinToString(", "),
        )
        if (anchor.state == AnchorState.ANCHOR_CONFLICT) {
            integrity = IntegrityReport.merge(
                listOf(integrity, IntegrityReport(IntegrityState.SPOOFING_SUSPECTED, listOf(anchor.message))),
            )
        }
        // Позиция не пропадает молча: без других источников — положение по якорю с накопленной σ.
        // Якорь никогда не останавливает навигацию: если с ним все отсеяны, берём решение без якоря.
        val est = comparison.estimate ?: unanchored.estimate
        val estAcc = comparison.accuracyM ?: unanchored.accuracyM
        val usesAnchorFallback = est == null && anchor.anchor != null
        return PhenixSolution(
            point = if (usesAnchorFallback) anchor.propagatedPoint ?: anchor.anchor?.point else est,
            accuracyM = if (usesAnchorFallback) anchor.propagatedSigmaM ?: anchor.anchor?.accuracyM else estAcc,
            integrity = integrity,
            anchor = anchor,
            comparison = comparison,
            gnss = gnss,
        )
    }

    private companion object {
        /** Нижняя граница σ собственного решения: модель ошибок псевдодальностей оптимистична. */
        const val MIN_WLS_SIGMA_M = 3.0
    }
}
