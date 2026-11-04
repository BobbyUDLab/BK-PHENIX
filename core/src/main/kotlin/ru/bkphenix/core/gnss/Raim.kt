package ru.bkphenix.core.gnss

import ru.bkphenix.core.math.Matrix
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
) {
    public fun process(inputs: List<RangeInput>): RaimResult {
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
