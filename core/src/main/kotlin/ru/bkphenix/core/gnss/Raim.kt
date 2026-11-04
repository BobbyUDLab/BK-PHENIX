package ru.bkphenix.core.gnss

import ru.bkphenix.core.math.Matrix
import kotlin.math.abs
import kotlin.math.ln
import kotlin.math.max
import kotlin.math.pow
import kotlin.math.sqrt

/** Состояние контроля целостности приёмником (RAIM). */
public enum class RaimStatus(public val description: String) {
    /** Нет избыточности (спутников меньше 5 + число дополнительных систем): ошибку обнаружить невозможно. */
    UNAVAILABLE("RAIM недоступен: мало спутников"),
    OK("RAIM: невязки согласованы"),
    /** Ошибка обнаружена и неисправные спутники исключены (FDE). */
    EXCLUDED("RAIM: исключены неисправные спутники"),
    /** Ошибка обнаружена, исключить не удалось — решению доверять нельзя. */
    FAULT("RAIM: ошибка обнаружена, исключение невозможно"),
    /**
     * Массовые грубые (> 500 м) ошибки, отсев не сходится: срыв слежения при перезахвате, а не признак подмены
     * (подмена даёт согласованные дальности). Собственному решению доверять нельзя, но это не тревога.
     */
    DEGRADED("RAIM недоступен: массовый срыв слежения, измерения несогласованы"),
}

public data class ExcludedSatellite(val sat: SatId, val residualM: Double)

public data class RaimResult(
    val status: RaimStatus,
    /** Итоговое решение (после исключения), если оно есть. */
    val solution: WlsSolution?,
    val excluded: List<ExcludedSatellite>,
    /** Статистика χ² и порог при заданной вероятности ложной тревоги. */
    val testStatistic: Double?,
    val threshold: Double?,
    /** Оценка горизонтального уровня защиты, метры. */
    val horizontalProtectionLevelM: Double?,
)

/**
 * RAIM по сумме квадратов невязок (Parkinson–Axelrad) и FDE перебором подмножеств.
 *
 * @param falseAlarmProbability вероятность ложной тревоги на эпоху.
 * @param missedDetectionProbability вероятность пропуска — для уровня защиты.
 * @param maxExclusions сколько спутников подряд можно исключить.
 */
public class Raim(
    private val solver: WlsSolver = WlsSolver(),
    private val falseAlarmProbability: Double = 1e-5,
    private val missedDetectionProbability: Double = 1e-3,
    private val maxExclusions: Int = 2,
    /** Грубая ошибка: невязка больше этого порога — сбой слежения (мс-неоднозначность, срыв), не многолучёвость. */
    private val grossErrorM: Double = 500.0,
    /** Сколько грубых ошибок можно отсеять до проверки χ² (не больше трети измерений). */
    private val maxGrossExclusions: Int = 10,
    /** Порог слабого сигнала, дБ·Гц: грубые ошибки таких спутников не ограничены лимитом отсева. */
    private val weakCn0DbHz: Double = 25.0,
) {
    public fun process(inputs: List<RangeInput>): RaimResult {
        val (screened, gross) = screenGross(inputs) ?: return degraded(inputs)
        val result = processScreened(screened)
        if (gross.isEmpty() || result.solution == null) return result
        val status = if (result.status == RaimStatus.OK) RaimStatus.EXCLUDED else result.status
        return result.copy(status = status, excluded = gross + result.excluded)
    }

    /**
     * Отсев грубых ошибок по одной: убирается спутник с наибольшей |невязкой|, пока она больше [grossErrorM].
     * Такие сбои у телефонов в городе — обычное дело; одиночный подменённый спутник с км-смещением
     * так же отсеивается, а согласованная подмена всех спутников невязок не даёт и ловится другими детекторами.
     */
    private fun degraded(inputs: List<RangeInput>): RaimResult =
        RaimResult(RaimStatus.DEGRADED, solver.solve(inputs), emptyList(), null, null, null)

    /** null — грубых ошибок больше допустимого: отсев мог бы сойтись к ложному кластеру. */
    private fun screenGross(inputs: List<RangeInput>): Pair<List<RangeInput>, List<ExcludedSatellite>>? {
        var current = inputs
        val excluded = mutableListOf<ExcludedSatellite>()
        val weakExcluded = mutableSetOf<SatId>()
        // Не больше четверти измерений и с запасом избыточности: иначе отсев может сойтись к ложному кластеру
        // (журнал 2026-11-04, эпоха скачка часов: 7 спутников «согласовались» в 18 км от истины).
        val limit = minOf(maxGrossExclusions, inputs.size / 4)
        while (true) {
            val s = solver.solve(current) ?: break
            if (s.degreesOfFreedom < 2) break
            val byMagnitude = s.residualsM.entries.sortedByDescending { abs(it.value) }
            if (abs(byMagnitude.first().value) <= grossErrorM) break
            // Слабый сигнал с км-невязкой — срыв слежения (неразрешённая мс-неоднозначность при C/N0 < 25),
            // а не подмена: такие отсеиваются первыми и сверх лимита, иначе срыв на перезахвате давал FAULT
            // (журнал 2026-11-04: 11 из 14 эпизодов тревоги — перезахват у домов, χ² до 10⁹).
            val cn0 = current.associate { it.sat to it.cn0DbHz }
            val weakGross = byMagnitude.firstOrNull { abs(it.value) > grossErrorM && (cn0[it.key] ?: 99.0) < weakCn0DbHz }
            val worst = weakGross ?: byMagnitude.first()
            if (weakGross == null && excluded.size - weakExcluded.size >= limit) return null
            if (current.size - 1 < minForDetection(current) + 2) return null
            if (weakGross != null) weakExcluded += worst.key
            excluded += ExcludedSatellite(worst.key, worst.value)
            current = current.filter { it.sat != worst.key }
        }
        return current to excluded
    }

    private fun processScreened(inputs: List<RangeInput>): RaimResult {
        val all = solver.solve(inputs) ?: return RaimResult(RaimStatus.UNAVAILABLE, null, emptyList(), null, null, null)
        if (all.degreesOfFreedom < 1) return RaimResult(RaimStatus.UNAVAILABLE, all, emptyList(), null, null, null)
        if (passes(all)) return result(RaimStatus.OK, all, emptyList())

        var current = inputs.filter { it.sat in all.used }
        var reference = all
        val excluded = mutableListOf<ExcludedSatellite>()
        repeat(maxExclusions) {
            // Исключение имеет смысл, только если после него останется избыточность для проверки.
            if (current.size - 1 < minForDetection(current)) return result(RaimStatus.FAULT, all, excluded)
            val candidates = current.indices.mapNotNull { i ->
                val subset = current.filterIndexed { k, _ -> k != i }
                solver.solve(subset, reference.position)?.let { Triple(current[i], subset, it) }
            }
            val best = candidates.minByOrNull { (_, _, s) -> s.chiSquare / s.degreesOfFreedom } ?: return result(RaimStatus.FAULT, all, excluded)
            excluded += ExcludedSatellite(best.first.sat, reference.residualsM[best.first.sat] ?: Double.NaN)
            current = best.second
            reference = best.third
            if (passes(reference)) return result(RaimStatus.EXCLUDED, reference, excluded)
        }
        return result(RaimStatus.FAULT, all, excluded)
    }

    private fun passes(s: WlsSolution): Boolean = s.chiSquare <= threshold(s.degreesOfFreedom)

    private fun result(status: RaimStatus, s: WlsSolution, excluded: List<ExcludedSatellite>): RaimResult {
        val dof = s.degreesOfFreedom
        return RaimResult(
            status = status,
            solution = s,
            excluded = excluded.toList(),
            testStatistic = s.chiSquare,
            threshold = if (dof >= 1) threshold(dof) else null,
            horizontalProtectionLevelM = if (dof >= 1) hpl(s) else null,
        )
    }

    public fun threshold(dof: Int): Double = chiSquareQuantile(dof, 1 - falseAlarmProbability)

    /** HPL = max(slope_i) · (√T + z_md): наихудший спутник при смещении на грани обнаружения. */
    private fun hpl(s: WlsSolution): Double? {
        val h = s.geometryEnu
        val n = h.rows
        val sig = s.used.map { s.sigmasM.getValue(it) }
        val u = h.cols
        val a = Matrix(u, n).also { m ->
            val ht = h.transpose()
            val htw = Matrix(u, n).also { for (r in 0 until u) for (c in 0 until n) it[r, c] = ht[r, c] / (sig[c] * sig[c]) }
            val prod = s.covarianceEnu * htw
            for (r in 0 until u) for (c in 0 until n) m[r, c] = prod[r, c]
        }
        val p = h * a
        var maxSlope = 0.0
        for (i in 0 until n) {
            val sii = 1.0 - p[i, i]
            if (sii <= 1e-9) continue
            val slope = sqrt(a[0, i] * a[0, i] + a[1, i] * a[1, i]) * sig[i] / sqrt(sii)
            maxSlope = max(maxSlope, slope)
        }
        val zmd = normalQuantile(1 - missedDetectionProbability)
        return maxSlope * (sqrt(threshold(s.degreesOfFreedom)) + zmd)
    }

    public companion object {
        /** Минимум спутников одной системы, при котором RAIM ещё обнаруживает ошибку (4 + 1). */
        public const val MIN_FOR_DETECTION: Int = 5

        /** Минимум для обнаружения с учётом межсистемных смещений: 4 + (число систем − 1) + 1. */
        public fun minForDetection(inputs: List<RangeInput>): Int =
            MIN_FOR_DETECTION + WlsSolver.clockSystems(inputs).size - 1

        /** Квантиль нормального распределения (Абрамовиц–Стиган 26.2.23, погрешность < 4.5·10⁻⁴). */
        public fun normalQuantile(p: Double): Double {
            require(p > 0.0 && p < 1.0)
            val q = if (p > 0.5) 1 - p else p
            val t = sqrt(-2 * ln(q))
            val z = t - (2.515517 + 0.802853 * t + 0.010328 * t * t) / (1 + 1.432788 * t + 0.189269 * t * t + 0.001308 * t * t * t)
            return if (p > 0.5) z else -z
        }

        /** Квантиль χ² по Уилсону–Хилферти. */
        public fun chiSquareQuantile(dof: Int, p: Double): Double {
            require(dof >= 1)
            val k = dof.toDouble()
            val z = normalQuantile(p)
            val c = 2.0 / (9.0 * k)
            return k * (1 - c + z * sqrt(c)).pow(3)
        }
    }
}
