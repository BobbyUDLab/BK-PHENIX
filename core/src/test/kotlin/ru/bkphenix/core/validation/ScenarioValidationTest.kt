package ru.bkphenix.core.validation

import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestInstance
import ru.bkphenix.core.PhenixSolution
import ru.bkphenix.core.gnss.RaimStatus
import ru.bkphenix.core.integrity.IntegrityState
import java.io.File

/**
 * Сценарная валидация WLS, RAIM, HPL и детекторов (шаг 2a). Метрики пишутся в
 * `core/build/validation/metrics.md`; CI выкладывает их в сводку прогона.
 * Пороги: обнаружение ≥ 95 % (скачок, плохой спутник), ложные тревоги ≤ 1 %, покрытие HPL ≥ 99 %.
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class ScenarioValidationTest {
    private val gen = ScenarioGenerator()
    private val rows = mutableListOf<String>()

    private fun alarm(s: PhenixSolution) = s.integrity.state != IntegrityState.OK
    private fun spoofAlarm(s: PhenixSolution) = s.integrity.state == IntegrityState.SPOOFING_SUSPECTED
    private fun pct(x: Double) = "%.1f %%".format(x * 100)

    /**
     * Доля эпох, где реальная ошибка WLS ≤ HPL (среди эпох с решением и HPL). Эпохи подмены
     * со смещением пропускаются: там сырые измерения целиком честно описывают ложную точку.
     */
    private fun hplCoverage(r: List<Pair<ScenarioEpoch, PhenixSolution>>): Double? {
        val xs = r.filter { (e, _) -> !e.attack || e.faultySv != null }.mapNotNull { (e, s) ->
            val own = s.gnss?.ownSolution ?: return@mapNotNull null
            val hpl = s.gnss?.raim?.horizontalProtectionLevelM ?: return@mapNotNull null
            own.point.distanceTo(e.truth) <= hpl
        }
        return if (xs.isEmpty()) null else xs.count { it }.toDouble() / xs.size
    }

    private fun record(kind: ScenarioKind, detection: Double?, falseAlarm: Double?, hpl: Double?, note: String = "") {
        rows += "| ${kind.title} | ${detection?.let(::pct) ?: "—"} | ${falseAlarm?.let(::pct) ?: "—"} | " +
            "${hpl?.let(::pct) ?: "—"} | $note |"
    }

    @Test
    fun `чистый приём и шум — ложные тревоги не выше 1 процента, HPL покрывает ошибку`() {
        for (kind in listOf(ScenarioKind.CLEAN, ScenarioKind.NOISE)) {
            val r = run(gen.core(), gen.generate(kind))
            val fa = r.count { (_, s) -> alarm(s) }.toDouble() / r.size
            val hpl = hplCoverage(r)
            record(kind, null, fa, hpl)
            assertTrue(fa <= 0.01, "$kind: ложные тревоги ${pct(fa)}")
            assertTrue(hpl != null && hpl >= 0.99, "$kind: покрытие HPL $hpl")
        }
    }

    @Test
    fun `один плохой спутник обнаруживается RAIM не реже 95 процентов`() {
        // Порог проверяется для ошибки 200 м; 80 и 150 м — справочно: слабые спутники с большой σ
        // при такой ошибке статистически неотличимы от шума (минимально обнаружимое смещение).
        for (bias in listOf(80.0, 150.0, 200.0)) {
            val g = ScenarioGenerator(badSatelliteBiasM = bias)
            val r = run(g.core(), g.generate(ScenarioKind.BAD_SATELLITE))
            val detected = r.count { (e, s) ->
                val raim = s.gnss?.raim
                raim != null && (raim.status == RaimStatus.FAULT || raim.excluded.any { it.sat.svid == e.faultySv })
            }.toDouble() / r.size
            val hpl = hplCoverage(r)
            record(ScenarioKind.BAD_SATELLITE, detected, null, hpl, "ошибка +${bias.toInt()} м" + if (bias < 200) " (справочно)" else "")
            if (bias >= 200) assertTrue(detected >= 0.95, "обнаружение ${pct(detected)}")
            assertTrue(hpl != null && hpl >= 0.99, "+$bias м: покрытие HPL $hpl")
        }
    }

    @Test
    fun `спуфинг со смещением и скачок обнаруживаются не реже 95 процентов`() {
        for (kind in listOf(ScenarioKind.SPOOF_OFFSET, ScenarioKind.JUMP)) {
            val r = run(gen.core(), gen.generate(kind))
            val attacked = r.filter { it.first.attack }
            val clean = r.filterNot { it.first.attack }
            val det = attacked.count { (_, s) -> spoofAlarm(s) }.toDouble() / attacked.size
            val fa = clean.count { (_, s) -> alarm(s) }.toDouble() / clean.size
            record(kind, det, fa, null)
            assertTrue(det >= 0.95, "$kind: обнаружение ${pct(det)}")
            assertTrue(fa <= 0.01, "$kind: ложные тревоги до атаки ${pct(fa)}")
        }
    }

    @Test
    fun `плавный увод обнаруживается не позже чем за 5 минут`() {
        val r = run(gen.core(), gen.generate(ScenarioKind.SLOW_DRIFT, epochs = 400))
        val first = r.firstOrNull { (e, s) -> e.attack && spoofAlarm(s) }?.first?.t
        val delay = first?.let { it - 20 }
        val after = first?.let { f -> r.filter { it.first.t >= f } }
        val det = after?.let { a -> a.count { spoofAlarm(it.second) }.toDouble() / a.size }
        record(ScenarioKind.SLOW_DRIFT, det, null, null, "время до обнаружения: ${delay ?: "не обнаружен"} с")
        assertTrue(delay != null && delay <= 300, "увод обнаружен через $delay с")
    }

    @Test
    fun `согласованная подмена GNSS и сети не считается подтверждённой`() {
        val r = run(gen.core(), gen.generate(ScenarioKind.COORDINATED))
        val flagged = r.count { (_, s) -> s.integrity.state != IntegrityState.OK && !s.comparison.hasQuorum }.toDouble() / r.size
        record(ScenarioKind.COORDINATED, flagged, null, null, "обнаружение = «нет кворума», без третьего источника")
        assertTrue(flagged == 1.0, "помечено ${pct(flagged)}")
    }

    @AfterAll
    fun writeReport() {
        val md = buildString {
            appendLine("## Валидация ядра на синтетических сценариях")
            appendLine()
            appendLine("| Сценарий | Обнаружение | Ложные тревоги | Покрытие HPL | Примечание |")
            appendLine("|---|---|---|---|---|")
            rows.sorted().forEach(::appendLine)
        }
        File("build/validation").mkdirs()
        File("build/validation/metrics.md").writeText(md)
        println(md)
    }
}
