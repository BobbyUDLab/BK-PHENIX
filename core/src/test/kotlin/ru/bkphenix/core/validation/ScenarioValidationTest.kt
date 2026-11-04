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
        val xs = r.filter { (e, _) -> !e.attack || e.faultySat != null }.mapNotNull { (e, s) ->
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
            val detected = detection(r)
            val hpl = hplCoverage(r)
            record(ScenarioKind.BAD_SATELLITE, detected, null, hpl, "GPS, ошибка +${bias.toInt()} м" + if (bias < 200) " (справочно)" else "")
            if (bias >= 200) assertTrue(detected >= 0.95, "обнаружение ${pct(detected)}")
            assertTrue(hpl != null && hpl >= 0.99, "+$bias м: покрытие HPL $hpl")
        }
    }

    /**
     * Доля эпох, где RAIM обнаружил ошибку (FAULT или исключён именно плохой спутник). Эпохи, где
     * плохой спутник не вошёл в решение (ниже маски по углу места), не учитываются: он не влияет на точку.
     */
    private fun detection(r: List<Pair<ScenarioEpoch, PhenixSolution>>): Double {
        val relevant = r.filter { (e, s) ->
            val raim = s.gnss?.raim
            raim?.solution?.used?.contains(e.faultySat) == true || raim?.excluded?.any { it.sat == e.faultySat } == true
        }
        return relevant.count { (e, s) ->
            val raim = s.gnss?.raim!!
            raim.status == RaimStatus.FAULT || raim.excluded.any { it.sat == e.faultySat }
        }.toDouble() / relevant.size
    }

    @Test
    fun `многосистемный приём — плохой спутник на 200 м обнаруживается, ложных тревог нет`() {
        // +80 м — справочно: с ростом числа степеней свободы растёт и порог χ², а σ измерений в модели
        // консервативна (χ² чистых эпох в разы ниже n − 6), поэтому обнаружимое смещение не падает до 80 м.
        for (bias in listOf(80.0, 200.0)) {
            val g = ScenarioGenerator(badSatelliteBiasM = bias, multiGnss = true)
            val r = run(g.core(), g.generate(ScenarioKind.BAD_SATELLITE))
            val detected = detection(r)
            val hpl = hplCoverage(r)
            record(ScenarioKind.BAD_SATELLITE, detected, null, hpl, "GPS+Galileo, ошибка +${bias.toInt()} м" + if (bias < 200) " (справочно)" else "")
            if (bias >= 200) assertTrue(detected >= 0.95, "GPS+Galileo: обнаружение ${pct(detected)}")
            assertTrue(hpl != null && hpl >= 0.99, "GPS+Galileo +$bias м: покрытие HPL $hpl")
        }
        val g = ScenarioGenerator(multiGnss = true)
        val clean = run(g.core(), g.generate(ScenarioKind.NOISE))
        val fa = clean.count { (_, s) -> alarm(s) }.toDouble() / clean.size
        val cov = hplCoverage(clean)
        record(ScenarioKind.NOISE, null, fa, cov, "GPS+Galileo")
        assertTrue(fa <= 0.01, "GPS+Galileo: ложные тревоги ${pct(fa)}")
        assertTrue(cov != null && cov >= 0.99, "GPS+Galileo: покрытие HPL $cov")
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

    /**
     * Опасно-ложная точка: ядро выдаёт её как надёжную (OK), а реальная ошибка больше 50 м и больше
     * 5σ заявленной погрешности. Главный критерий против согласованной подмены — таких эпох нет.
     */
    private fun misleading(e: ScenarioEpoch, s: PhenixSolution): Boolean {
        val p = s.point ?: return false
        val err = p.distanceTo(e.truth)
        return s.integrity.state == IntegrityState.OK && err > 50.0 && err > 5 * (s.accuracyM ?: 0.0)
    }

    @Test
    fun `согласованная подмена GNSS, сети и Wi-Fi не выдаётся за надёжную`() {
        for (kind in listOf(ScenarioKind.COORD_JUMP, ScenarioKind.COORD_DRIFT, ScenarioKind.COORD_KINEMATIC)) {
            val r = run(gen.core(), gen.generate(kind, epochs = 400))
            val attacked = r.filter { it.first.attack }
            val before = r.filterNot { it.first.attack }
            val flagged = attacked.count { (_, s) -> alarm(s) }.toDouble() / attacked.size
            val mis = attacked.count { (e, s) -> misleading(e, s) }
            val first = attacked.firstOrNull { (e, s) -> alarm(s) && s.point?.distanceTo(e.truth)?.let { it > 50 } != false }
            val start = attacked.first().first.t
            val fa = if (before.isEmpty()) null else before.count { (_, s) -> alarm(s) }.toDouble() / before.size
            // Наибольшая ошибка точки, выданной как надёжная: предел — 3σ честного свидетеля (сота, σ 100 м).
            val worst = attacked.filter { (_, s) -> s.integrity.state == IntegrityState.OK }
                .maxOfOrNull { (e, s) -> s.point?.distanceTo(e.truth) ?: 0.0 } ?: 0.0
            record(
                kind, flagged, fa, null,
                "опасно-ложных эпох: $mis из ${attacked.size}; макс. ошибка при «OK» ${worst.toInt()} м; " +
                    "первая пометка через ${first?.let { it.first.t - start } ?: "—"} с",
            )
            if (kind == ScenarioKind.COORD_JUMP) assertTrue(mis == 0, "$kind: опасно-ложных эпох $mis")
            assertTrue(worst <= 300.0, "$kind: ошибка при «OK» ${worst.toInt()} м")
            if (fa != null) assertTrue(fa <= 0.01, "$kind: ложные тревоги до атаки ${pct(fa)}")
        }
    }

    @Test
    fun `подмена времени обнаруживается по NITZ`() {
        // Допуск NITZ: 2 с + неопределённость меток (0,5 с); увод 50 мс/с превышает его через ~50 с.
        for (kind in listOf(ScenarioKind.TIME_STEP, ScenarioKind.TIME_DRIFT)) {
            val r = run(gen.core(), gen.generate(kind))
            val attacked = r.filter { it.first.attack }
            val start = attacked.first().first.t
            val first = attacked.firstOrNull { (_, s) -> spoofAlarm(s) }?.first?.t?.let { it - start }
            val after = first?.let { f -> attacked.filter { it.first.t >= start + f } }.orEmpty()
            val det = if (after.isEmpty()) 0.0 else after.count { spoofAlarm(it.second) }.toDouble() / after.size
            val fa = r.filterNot { it.first.attack }.count { (_, s) -> alarm(s) }.toDouble() / r.count { !it.first.attack }
            record(kind, det, fa, null, "время до обнаружения: ${first ?: "не обнаружен"} с")
            assertTrue(det >= 0.95, "$kind: обнаружение ${pct(det)}")
            assertTrue(first != null && first <= 60, "$kind: обнаружено через $first с")
            assertTrue(fa <= 0.01, "$kind: ложные тревоги ${pct(fa)}")
        }
    }

    /**
     * RAIM/FDE: P_md — доля эпох с ошибкой, где RAIM не сработал (ни FAULT, ни исключения плохого спутника);
     * HMI — доля эпох без тревоги, где ошибка собственного решения больше HPL.
     */
    @Test
    fun `RAIM и FDE — два плохих спутника и многолучёвость`() {
        val g = ScenarioGenerator(badSatelliteBiasM = 200.0, multiGnss = true)
        val two = run(g.core(), g.generate(ScenarioKind.TWO_BAD_SATELLITES))
        val relevant = two.filter { (e, s) ->
            val raim = s.gnss?.raim
            e.faultySats.any { sat -> raim?.solution?.used?.contains(sat) == true || raim?.excluded?.any { it.sat == sat } == true }
        }
        val detected = relevant.count { (_, s) -> s.gnss!!.raim!!.status == RaimStatus.FAULT || s.gnss!!.raim!!.excluded.isNotEmpty() }
        val pmd = 1.0 - detected.toDouble() / relevant.size
        val hmiTwo = hmi(two)
        record(ScenarioKind.TWO_BAD_SATELLITES, 1 - pmd, null, hplCoverage(two), "GPS+Galileo, +200 и −160 м; P_md ${pct(pmd)}; HMI ${pct(hmiTwo)}")
        assertTrue(pmd <= 0.05, "два плохих спутника: P_md ${pct(pmd)}")
        assertTrue(hmiTwo <= 0.001, "два плохих спутника: HMI ${pct(hmiTwo)}")

        val mp = run(g.core(), g.generate(ScenarioKind.MULTIPATH))
        val pfa = mp.count { (_, s) -> s.gnss?.raim?.status == RaimStatus.FAULT }.toDouble() / mp.size
        val excl = mp.count { (_, s) -> s.gnss?.raim?.status == RaimStatus.EXCLUDED }.toDouble() / mp.size
        val alarms = mp.count { (_, s) -> alarm(s) }.toDouble() / mp.size
        val hmiMp = hmi(mp)
        record(
            ScenarioKind.MULTIPATH, null, alarms, hplCoverage(mp),
            "3 низких спутника +10…60 м; FAULT ${pct(pfa)}, исключение ${pct(excl)}; HMI ${pct(hmiMp)}",
        )
        assertTrue(alarms <= 0.01, "многолучёвость: тревоги ${pct(alarms)}")
        assertTrue(hmiMp <= 0.001, "многолучёвость: HMI ${pct(hmiMp)}")
    }

    private fun hmi(r: List<Pair<ScenarioEpoch, PhenixSolution>>): Double {
        val xs = r.mapNotNull { (e, s) ->
            val raim = s.gnss?.raim ?: return@mapNotNull null
            val own = s.gnss?.ownSolution ?: return@mapNotNull null
            val hpl = raim.horizontalProtectionLevelM ?: return@mapNotNull null
            raim.status != RaimStatus.FAULT && own.point.distanceTo(e.truth) > hpl
        }
        return if (xs.isEmpty()) 0.0 else xs.count { it }.toDouble() / xs.size
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
