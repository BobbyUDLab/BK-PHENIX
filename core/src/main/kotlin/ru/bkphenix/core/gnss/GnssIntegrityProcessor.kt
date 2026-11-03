package ru.bkphenix.core.gnss

import ru.bkphenix.core.integrity.IntegrityReport
import ru.bkphenix.core.integrity.IntegrityState
import ru.bkphenix.core.model.PositionObservation
import ru.bkphenix.core.model.PositionSource
import ru.bkphenix.core.model.TimeObservation

/** Итог контроля целостности по сырым данным в одной эпохе. */
public data class GnssIntegrityResult(
    val raim: RaimResult?,
    val detectors: List<DetectorResult>,
    /** Сколько спутников GPS имеют эфемериды из навигационных сообщений. */
    val ephemerisCount: Int,
    val pseudorangeCount: Int,
    /** Собственное решение, если RAIM его подтвердил (OK или после исключения). */
    val ownSolution: WlsSolution?,
    val report: IntegrityReport,
)

/**
 * Контур проверки по сырым измерениям: эфемериды → псевдодальности → МНК → RAIM/FDE → детекторы подмены.
 * Хранит состояние между эпохами. Не потокобезопасен.
 */
public class GnssIntegrityProcessor(
    private val solver: WlsSolver = WlsSolver(),
    private val raim: Raim = Raim(solver),
    private val monitor: SpoofingMonitor = SpoofingMonitor(),
    public val ephemerides: EphemerisStore = EphemerisStore(),
) {
    /** Добавляет подкадры LNAV; возвращает число спутников с обновлёнными эфемеридами. */
    public fun addSubframes(subframes: List<NavigationSubframe>): Int = subframes.count(ephemerides::add)

    public fun process(
        epoch: RawGnssEpoch,
        observations: List<PositionObservation>,
        timeObservations: List<TimeObservation>,
    ): GnssIntegrityResult {
        val ranges = Pseudoranges.compute(epoch)
        val inputs = solver.prepare(ranges, ephemerides)
        val raimResult = raim.process(inputs)
        val own = raimResult.solution?.takeIf { raimResult.status == RaimStatus.OK || raimResult.status == RaimStatus.EXCLUDED }
        val detectors = monitor.evaluate(
            SpoofingInput(
                epoch = epoch,
                pseudoranges = ranges,
                ownSolution = own,
                chipFix = observations.filter { it.source == PositionSource.GNSS }.maxByOrNull { it.timestampMillis },
                networkFix = observations.filter { it.source == PositionSource.NETWORK }.maxByOrNull { it.timestampMillis },
                timeObservations = timeObservations,
            ),
        )
        val raimReport = when (raimResult.status) {
            RaimStatus.FAULT -> IntegrityReport(IntegrityState.INCONSISTENT, listOf(raimResult.status.description))
            RaimStatus.EXCLUDED -> IntegrityReport(
                IntegrityState.OK,
                listOf("RAIM исключил: " + raimResult.excluded.joinToString { it.sat.label }),
            )
            RaimStatus.OK, RaimStatus.UNAVAILABLE -> IntegrityReport.OK
        }
        return GnssIntegrityResult(
            raim = raimResult,
            detectors = detectors,
            ephemerisCount = ephemerides.size,
            pseudorangeCount = ranges.size,
            ownSolution = own,
            report = IntegrityReport.merge(listOf(raimReport, SpoofingMonitor.report(detectors))),
        )
    }
}
