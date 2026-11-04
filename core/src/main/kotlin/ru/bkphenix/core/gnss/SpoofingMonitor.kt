package ru.bkphenix.core.gnss

import ru.bkphenix.core.integrity.IntegrityReport
import ru.bkphenix.core.integrity.IntegrityState
import ru.bkphenix.core.model.GnssBand
import ru.bkphenix.core.model.PositionObservation
import ru.bkphenix.core.model.TimeObservation
import ru.bkphenix.core.model.TimeSource
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.sqrt

/** Детекторы подмены и глушения. */
public enum class DetectorKind(public val displayName: String) {
    CN0_UNIFORMITY("Одинаковый C/N0"),
    DUAL_FREQUENCY("Согласованность L1/L5"),
    CLOCK_JUMP("Скачки часов приёмника"),
    NETWORK_POSITION("Сверка с сетевой позицией"),
    NITZ_TIME("Сверка времени с NITZ"),
    AGC("АРУ и C/N0"),
    CHIP_VS_OWN("Решение чипа против собственного"),
}

public enum class DetectorVerdict { PASS, ALERT, NOT_APPLICABLE }

public data class DetectorResult(
    val kind: DetectorKind,
    val verdict: DetectorVerdict,
    val detail: String,
    /** Состояние целостности при тревоге. */
    val alertState: IntegrityState = IntegrityState.SPOOFING_SUSPECTED,
)

/** Данные одной эпохи для детекторов. */
public data class SpoofingInput(
    val epoch: RawGnssEpoch?,
    val pseudoranges: List<Pseudorange>,
    val ownSolution: WlsSolution?,
    val chipFix: PositionObservation?,
    val networkFix: PositionObservation?,
    val timeObservations: List<TimeObservation>,
)

/** Пороги детекторов. Значения по умолчанию подобраны консервативно, чтобы не давать ложных тревог. */
public data class SpoofingThresholds(
    val minSatellitesForCn0: Int = 6,
    val minCn0SpreadDb: Double = 1.0,
    val minMeanCn0ForSpreadDb: Double = 30.0,
    /** Допустимый диапазон ρ(L5) − ρ(L1): ионосфера даёт 0…~50 м плюс аппаратная задержка. */
    val dualFrequencyRangeM: ClosedFloatingPointRange<Double> = -15.0..60.0,
    val maxDualFrequencySpreadM: Double = 40.0,
    val clockJumpNanos: Double = 1_000.0,
    val maxClockGapSeconds: Double = 5.0,
    val networkGateSigma: Double = 4.0,
    val networkMinGateM: Double = 200.0,
    val nitzToleranceMillis: Long = 2_000,
    val agcDropDb: Double = 6.0,
    val agcBaselineEpochs: Int = 10,
    val chipGateSigma: Double = 4.0,
    val chipMinGateM: Double = 30.0,
)

/**
 * Набор детекторов подмены ГНСС. Хранит состояние между эпохами (часы, базовый уровень АРУ),
 * поэтому один экземпляр должен получать эпохи по порядку. Не потокобезопасен.
 */
public class SpoofingMonitor(private val t: SpoofingThresholds = SpoofingThresholds()) {
    private var previousClock: RawGnssClock? = null
    private var previousRateNsPerS: Double? = null
    private var previousOwnClockM: Double? = null
    private var agcBaseline: Double? = null
    private var cn0Baseline: Double? = null
    private var baselineEpochs = 0

    public fun evaluate(input: SpoofingInput): List<DetectorResult> = listOf(
        cn0Uniformity(input),
        dualFrequency(input),
        clockJump(input),
        network(input),
        nitz(input),
        agc(input),
        chipVsOwn(input),
    )

    public fun reset() {
        previousClock = null
        previousRateNsPerS = null
        previousOwnClockM = null
        agcBaseline = null
        cn0Baseline = null
        baselineEpochs = 0
    }

    /** Спуфер с одной антенны выдаёт почти равные C/N0; у реального неба разброс из-за углов места. */
    internal fun cn0Uniformity(input: SpoofingInput): DetectorResult {
        val cn0 = input.epoch?.measurements.orEmpty()
            .filter { it.band == GnssBand.L1 && it.cn0DbHz > 0 }
            .groupBy { it.sat }.map { (_, m) -> m.maxOf { it.cn0DbHz } }
        if (cn0.size < t.minSatellitesForCn0) {
            return na(DetectorKind.CN0_UNIFORMITY, "Мало спутников: ${cn0.size}")
        }
        val mean = cn0.average()
        val sd = sqrt(cn0.sumOf { (it - mean) * (it - mean) } / (cn0.size - 1))
        val detail = "СКО C/N0 %.1f дБ·Гц по %d спутникам, среднее %.1f".format(sd, cn0.size, mean)
        return if (sd < t.minCn0SpreadDb && mean >= t.minMeanCn0ForSpreadDb) alert(DetectorKind.CN0_UNIFORMITY, detail) else pass(DetectorKind.CN0_UNIFORMITY, detail)
    }

    /** Разность псевдодальностей L5 и L1 одного спутника — ионосферная задержка; подделка её обычно не воспроизводит. */
    internal fun dualFrequency(input: SpoofingInput): DetectorResult {
        val bySat = input.pseudoranges.groupBy { it.measurement.sat }
        val diffs = bySat.mapNotNull { (_, list) ->
            val l1 = list.firstOrNull { it.measurement.band == GnssBand.L1 } ?: return@mapNotNull null
            val l5 = list.firstOrNull { it.measurement.band == GnssBand.L5 } ?: return@mapNotNull null
            l5.rangeM - l1.rangeM
        }
        if (diffs.size < 4) return na(DetectorKind.DUAL_FREQUENCY, "Двухчастотных спутников: ${diffs.size}")
        val bad = diffs.count { it !in t.dualFrequencyRangeM }
        // Одиночный выброс (неоднозначность кода L5, многолучёвость) — сбой измерения, а не подмена:
        // разброс считается по парам в допуске, при ≥ 5 парах без крайних значений.
        // Журнал 2026-11-04: 201 ложная эпоха из-за одной пары с разностью ~20 км.
        val inliers = diffs.filter { it in t.dualFrequencyRangeM }.sorted()
            .let { if (it.size >= 5) it.subList(1, it.size - 1) else it }
        val spread = if (inliers.size >= 2) inliers.last() - inliers.first() else 0.0
        val detail = "Пар L1/L5: ${diffs.size}, вне допуска: $bad, разброс %.1f м".format(spread)
        return if (bad * 2 > diffs.size || spread > t.maxDualFrequencySpreadM) alert(DetectorKind.DUAL_FREQUENCY, detail) else pass(DetectorKind.DUAL_FREQUENCY, detail)
    }

    /** Смещение часов приёмника меняется плавно (дрейф); скачок — признак перехвата времени. */
    internal fun clockJump(input: SpoofingInput): DetectorResult {
        val clock = input.epoch?.clock
        val fullBias = clock?.fullBiasNanos ?: return na(DetectorKind.CLOCK_JUMP, "Нет оценки времени GPS")
        val prev = previousClock
        previousClock = clock
        val ownClockM = input.ownSolution?.clockBiasM
        val prevOwnClockM = previousOwnClockM
        previousOwnClockM = ownClockM
        val prevFull = prev?.fullBiasNanos
        if (prev == null || prevFull == null || prev.hardwareClockDiscontinuityCount != clock.hardwareClockDiscontinuityCount) {
            previousRateNsPerS = null
            return na(DetectorKind.CLOCK_JUMP, "Нет предыдущей эпохи без разрыва часов")
        }
        val dtS = (clock.timeNanos - prev.timeNanos) / 1e9
        if (dtS <= 0 || dtS > t.maxClockGapSeconds) {
            previousRateNsPerS = null
            return na(DetectorKind.CLOCK_JUMP, "Разрыв между эпохами %.1f с".format(dtS))
        }
        val deltaBias = (fullBias - prevFull).toDouble() + (clock.biasNanos - prev.biasNanos)
        val rate = deltaBias / dtS
        val expectedRate = clock.driftNanosPerSecond ?: prev.driftNanosPerSecond ?: previousRateNsPerS ?: rate
        previousRateNsPerS = rate
        val jump = abs(deltaBias - expectedRate * dtS)
        val detail = "Отклонение от дрейфа %.0f нс за %.1f с".format(jump, dtS)
        if (jump <= t.clockJumpNanos) return pass(DetectorKind.CLOCK_JUMP, detail)
        // Kirin переоценивает FullBiasNanos скачками 4–80 мс без счётчика разрыва. Подмена времени даёт скачок
        // при согласованных псевдодальностях; сбой чипа — несогласованные (RAIM без решения) или скачок,
        // поглощённый смещением часов в МНК. Отличаем по полной оценке смещения: FullBias + Bias + b_МНК/c.
        if (ownClockM == null) {
            return na(DetectorKind.CLOCK_JUMP, "$detail; псевдодальности несогласованы — сбой часов чипа, не подмена")
        }
        if (prevOwnClockM != null) {
            val total = deltaBias + (ownClockM - prevOwnClockM) / GpsEphemeris.C * 1e9
            val totalJump = abs(total - expectedRate * dtS)
            if (totalJump <= t.clockJumpNanos) {
                return pass(DetectorKind.CLOCK_JUMP, "$detail; поглощено смещением часов МНК, остаток %.0f нс".format(totalJump))
            }
            return alert(DetectorKind.CLOCK_JUMP, "$detail; полная оценка времени скачет на %.0f нс при согласованных дальностях".format(totalJump))
        }
        return alert(DetectorKind.CLOCK_JUMP, detail)
    }

    internal fun network(input: SpoofingInput): DetectorResult {
        val net = input.networkFix ?: return na(DetectorKind.NETWORK_POSITION, "Нет сетевой позиции")
        val own = input.ownSolution
        val (point, sigma, name) = when {
            own != null -> Triple(own.point, own.horizontalSigmaM, "собственное решение")
            input.chipFix != null -> Triple(input.chipFix.point, input.chipFix.accuracyM, "решение чипа")
            else -> return na(DetectorKind.NETWORK_POSITION, "Нет решения ГНСС")
        }
        val d = point.distanceTo(net.point)
        val gate = max(t.networkMinGateM, t.networkGateSigma * sqrt(sigma * sigma + net.accuracyM * net.accuracyM))
        val detail = "$name в %.0f м от сети (допуск %.0f м)".format(d, gate)
        return if (d > gate) alert(DetectorKind.NETWORK_POSITION, detail) else pass(DetectorKind.NETWORK_POSITION, detail)
    }

    internal fun nitz(input: SpoofingInput): DetectorResult {
        val gnss = input.timeObservations.firstOrNull { it.source == TimeSource.GNSS }
        val net = input.timeObservations.firstOrNull { it.source == TimeSource.NITZ }
        if (gnss == null || net == null) return na(DetectorKind.NITZ_TIME, "Нет одной из меток времени")
        val diff = abs(gnss.utcMillis - net.utcMillis)
        val gate = gnss.uncertaintyMillis + net.uncertaintyMillis + t.nitzToleranceMillis
        val detail = "Расхождение %d мс (допуск %d мс)".format(diff, gate)
        return if (diff > gate) alert(DetectorKind.NITZ_TIME, detail) else pass(DetectorKind.NITZ_TIME, detail)
    }

    /**
     * Внешний сигнал повышает мощность на входе — АРУ снижает усиление. Если при этом C/N0 не падает,
     * сигнал похож на навигационный (подмена); если падает — это шум (глушение).
     */
    internal fun agc(input: SpoofingInput): DetectorResult {
        val ms = input.epoch?.measurements.orEmpty().filter { it.band == GnssBand.L1 }
        val agcValues = ms.mapNotNull { it.agcDb }
        if (agcValues.isEmpty()) return na(DetectorKind.AGC, "Чип не сообщает АРУ")
        val agc = agcValues.average()
        val cn0 = ms.filter { it.cn0DbHz > 0 }.map { it.cn0DbHz }.takeIf { it.isNotEmpty() }?.average() ?: 0.0
        val baseA = agcBaseline
        val baseC = cn0Baseline
        if (baseA == null || baseC == null || baselineEpochs < t.agcBaselineEpochs) {
            updateBaseline(agc, cn0)
            return na(DetectorKind.AGC, "Набор базового уровня: $baselineEpochs из ${t.agcBaselineEpochs}")
        }
        val dAgc = agc - baseA
        val dCn0 = cn0 - baseC
        val detail = "АРУ %+.1f дБ, C/N0 %+.1f дБ·Гц от базового уровня".format(dAgc, dCn0)
        return when {
            dAgc < -t.agcDropDb && dCn0 > -2.0 -> alert(DetectorKind.AGC, "$detail: мощный сигнал без потери C/N0")
            dAgc < -t.agcDropDb -> DetectorResult(DetectorKind.AGC, DetectorVerdict.ALERT, "$detail: похоже на глушение", IntegrityState.GNSS_LOST)
            else -> {
                updateBaseline(agc, cn0)
                pass(DetectorKind.AGC, detail)
            }
        }
    }

    private fun updateBaseline(agc: Double, cn0: Double) {
        baselineEpochs++
        val k = 1.0 / minOf(baselineEpochs, t.agcBaselineEpochs)
        agcBaseline = agcBaseline?.let { it + k * (agc - it) } ?: agc
        cn0Baseline = cn0Baseline?.let { it + k * (cn0 - it) } ?: cn0
    }

    internal fun chipVsOwn(input: SpoofingInput): DetectorResult {
        val own = input.ownSolution ?: return na(DetectorKind.CHIP_VS_OWN, "Нет собственного решения")
        val chip = input.chipFix ?: return na(DetectorKind.CHIP_VS_OWN, "Нет решения чипа")
        val d = own.point.distanceTo(chip.point)
        val s = own.horizontalSigmaM
        val gate = max(t.chipMinGateM, t.chipGateSigma * sqrt(s * s + chip.accuracyM * chip.accuracyM))
        val detail = "Расхождение %.1f м (допуск %.0f м)".format(d, gate)
        return if (d > gate) {
            DetectorResult(DetectorKind.CHIP_VS_OWN, DetectorVerdict.ALERT, detail, IntegrityState.INCONSISTENT)
        } else {
            pass(DetectorKind.CHIP_VS_OWN, detail)
        }
    }

    private fun pass(k: DetectorKind, d: String) = DetectorResult(k, DetectorVerdict.PASS, d)
    private fun alert(k: DetectorKind, d: String) = DetectorResult(k, DetectorVerdict.ALERT, d)
    private fun na(k: DetectorKind, d: String) = DetectorResult(k, DetectorVerdict.NOT_APPLICABLE, d)

    public companion object {
        /** Отчёт целостности по сработавшим детекторам. */
        public fun report(results: List<DetectorResult>): IntegrityReport = IntegrityReport.merge(
            results.filter { it.verdict == DetectorVerdict.ALERT }
                .map { IntegrityReport(it.alertState, listOf("${it.kind.displayName}: ${it.detail}")) },
        )
    }
}
