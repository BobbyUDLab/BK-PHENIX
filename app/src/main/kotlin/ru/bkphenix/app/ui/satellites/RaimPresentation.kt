package ru.bkphenix.app.ui.satellites

import ru.bkphenix.core.gnss.SatId
import ru.bkphenix.core.model.GnssConstellation
import ru.bkphenix.core.gnss.DetectorVerdict
import ru.bkphenix.core.gnss.GnssIntegrityResult
import ru.bkphenix.core.gnss.RaimStatus
import java.util.Locale

/** Уровень важности строки для цвета на экране. */
enum class Severity { NEUTRAL, GOOD, WARNING, ALERT }

data class ExcludedRow(val label: String, val residual: String)

data class DetectorRow(val name: String, val verdict: String, val detail: String, val severity: Severity)

/** Готовое к показу состояние RAIM и детекторов подмены. */
data class RaimUi(
    val status: String,
    val severity: Severity,
    val excluded: List<ExcludedRow>,
    val ownSolution: String?,
    val protectionLevel: String?,
    val statistic: String?,
    val ephemerides: String,
    val detectors: List<DetectorRow>,
)

object RaimPresentation {
    fun from(result: GnssIntegrityResult?): RaimUi? {
        val r = result ?: return null
        val raim = r.raim
        val severity = when (raim?.status) {
            RaimStatus.OK -> Severity.GOOD
            RaimStatus.EXCLUDED -> Severity.WARNING
            RaimStatus.FAULT -> Severity.ALERT
            RaimStatus.DEGRADED -> Severity.WARNING
            RaimStatus.UNAVAILABLE, null -> Severity.NEUTRAL
        }
        return RaimUi(
            status = raim?.status?.description ?: "Нет данных",
            severity = severity,
            excluded = raim?.excluded.orEmpty().map { ExcludedRow(it.sat.label, "невязка ${fmt(it.residualM, 1)} м") },
            ownSolution = r.ownSolution?.let {
                "${fmt(it.point.latitude, 6)}, ${fmt(it.point.longitude, 6)} ± ${fmt(it.horizontalSigmaM, 1)} м · ${it.used.size} сп. ${systems(it.used)}"
            },
            protectionLevel = raim?.horizontalProtectionLevelM?.let { "${fmt(it, 1)} м" },
            statistic = raim?.testStatistic?.let { t -> "${fmt(t, 1)} из ${raim.threshold?.let { fmt(it, 1) } ?: "—"}" },
            ephemerides = "${r.ephemerisCount} сп." +
                (if (r.ephemerisBySystem.isNotEmpty()) " (" + bySystem(r.ephemerisBySystem) + ")" else "") +
                (if (r.externalEphemerisCount > 0) " (RINEX: ${r.externalEphemerisCount})" else "") +
                " · псевдодальностей ${r.pseudorangeCount}",
            detectors = r.detectors.map {
                DetectorRow(
                    name = it.kind.displayName,
                    verdict = when (it.verdict) {
                        DetectorVerdict.PASS -> "Норма"
                        DetectorVerdict.ALERT -> "Тревога"
                        DetectorVerdict.NOT_APPLICABLE -> "Нет данных"
                    },
                    detail = it.detail,
                    severity = when (it.verdict) {
                        DetectorVerdict.PASS -> Severity.GOOD
                        DetectorVerdict.ALERT -> Severity.ALERT
                        DetectorVerdict.NOT_APPLICABLE -> Severity.NEUTRAL
                    },
                )
            },
        )
    }

    private fun fmt(v: Double, digits: Int) = if (v.isNaN()) "—" else String.format(Locale.ROOT, "%.${digits}f", v)

    /** «G 12 · R 8 · E 9 · C 14» в порядке систем. */
    internal fun bySystem(counts: Map<GnssConstellation, Int>): String =
        counts.entries.sortedBy { it.key.ordinal }.joinToString(" · ") { "${SatId(it.key, 1).label.first()} ${it.value}" }

    /** Системы, вошедшие в решение: «GPS+ГЛОНАСС+Galileo». */
    internal fun systems(used: List<SatId>): String =
        used.map { it.constellation }.distinct().sortedBy { it.ordinal }.joinToString("+") { it.displayName }
}
