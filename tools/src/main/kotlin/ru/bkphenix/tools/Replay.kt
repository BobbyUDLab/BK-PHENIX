package ru.bkphenix.tools

import ru.bkphenix.core.gnss.DetectorVerdict
import ru.bkphenix.core.gnss.GnssIntegrityProcessor
import ru.bkphenix.core.gnss.GnssIntegrityResult
import ru.bkphenix.core.gnss.Pseudoranges
import ru.bkphenix.core.gnss.RawGnssEpoch
import ru.bkphenix.core.gnss.RinexNavParser
import ru.bkphenix.core.model.GnssConstellation
import ru.bkphenix.core.model.PositionObservation
import ru.bkphenix.core.model.PositionSource
import ru.bkphenix.core.replay.GnssLoggerParser
import ru.bkphenix.core.replay.LoggedFix
import java.io.File
import java.util.zip.GZIPInputStream
import kotlin.math.abs
import kotlin.math.sqrt
import kotlin.system.exitProcess

private const val NANOS_PER_MILLI = 1_000_000L
private const val FIX_MATCH_MS = 1_500L

/** Коэффициент R95 для двумерной круговой ошибки: R95 ≈ 2,448·σ на ось. */
internal const val R95_PER_AXIS_SIGMA = 2.448

/** Секунды между GPS и UTC: 17 до 2017 года, 18 после. */
private fun leapSeconds(gpsMillis: Long): Long =
    if (gpsMillis + Pseudoranges.GPS_EPOCH_UNIX_MILLIS < 1_483_228_800_000L) 17 else 18

private fun usage(): Nothing {
    System.err.println("Использование: replay <журнал GnssLogger> [--nav <файл RINEX nav, можно .gz>]")
    exitProcess(2)
}

internal fun readMaybeGz(file: File): String =
    if (file.name.endsWith(".gz")) GZIPInputStream(file.inputStream()).bufferedReader().readText() else file.readText()

public fun main(args: Array<String>) {
    if (args.isEmpty()) usage()
    val logFile = File(args[0])
    val navFile = args.indexOf("--nav").takeIf { it >= 0 }?.let { args.getOrNull(it + 1) ?: usage() }?.let(::File)
    val log = logFile.useLines { GnssLoggerParser.parse(it) }
    val nav = navFile?.let { RinexNavParser.parse(readMaybeGz(it)) }
    val processor = GnssIntegrityProcessor()
    nav?.let { processor.ephemerides.replaceExternalNav(it.ephemerides, it.ionosphere) }
    println("# Прогон ${logFile.name}" + (navFile?.let { " с эфемеридами ${it.name}" } ?: " без внешних эфемерид"))
    val bySys = nav?.ephemerides.orEmpty().groupingBy { it.sat.constellation }.eachCount()
    println("Эпох: ${log.epochs.size}, фиксов чипа: ${log.fixes.size}, записей эфемерид RINEX: $bySys")
    println(Replay(processor, log.fixes).run(log.epochs).report())
}

/** Проигрывает эпохи через [GnssIntegrityProcessor] и копит статистику. */
internal class Replay(private val processor: GnssIntegrityProcessor, fixes: List<LoggedFix>) {
    private val gpsFixes = fixes.filter { it.provider.equals("gps", ignoreCase = true) }.sortedBy { it.utcMillis }
    private val raimStatus = sortedMapOf<String, Int>()
    private val integrity = sortedMapOf<String, Int>()
    private val detectorAlarms = sortedMapOf<String, Int>()
    private val detectorAlarmExample = HashMap<String, String>()
    private val measured = sortedMapOf<String, Int>()
    private val withEph = sortedMapOf<String, Int>()
    private val used = sortedMapOf<String, Int>()
    private val isb = HashMap<String, MutableList<Double>>()
    private val errors = ArrayList<Double>()
    private val upErrors = ArrayList<Double>()
    private val hpls = ArrayList<Double>()
    private val r95s = ArrayList<Double>()
    private val chipAcc = ArrayList<Double>()
    private var covered = 0
    private var compared = 0
    private var r95Covered = 0
    private var chipCovered = 0
    private var epochs = 0
    private var firstMillis: Long? = null
    private var lastMillis: Long? = null
    private var alarmEpisodes = 0
    private var prevAlarm = false

    fun run(epochs: List<RawGnssEpoch>): Replay {
        for (epoch in epochs) step(epoch)
        return this
    }

    private fun step(epoch: RawGnssEpoch) {
        val fullBias = epoch.clock.fullBiasNanos ?: return
        val gpsNanos = epoch.clock.timeNanos - fullBias - epoch.clock.biasNanos.toLong()
        val gpsMillis = gpsNanos / NANOS_PER_MILLI
        val utcMillis = gpsMillis + Pseudoranges.GPS_EPOCH_UNIX_MILLIS - leapSeconds(gpsMillis) * 1000
        if (firstMillis == null) firstMillis = utcMillis
        lastMillis = utcMillis
        epochs++
        Pseudoranges.compute(epoch).map { it.measurement.sat }.distinct()
            .groupingBy { it.constellation.name }.eachCount().forEach { (k, v) -> measured.merge(k, v, Int::plus) }
        val fix = nearestFix(utcMillis)
        val obs = fix?.let { listOf(PositionObservation(PositionSource.GNSS, it.point, it.accuracyM, utcMillis)) }.orEmpty()
        val result = processor.process(epoch, obs, emptyList())
        record(result, fix)
    }

    private fun nearestFix(utcMillis: Long): LoggedFix? =
        gpsFixes.minByOrNull { abs(it.utcMillis - utcMillis) }?.takeIf { abs(it.utcMillis - utcMillis) <= FIX_MATCH_MS }

    private fun record(result: GnssIntegrityResult, fix: LoggedFix?) {
        raimStatus.merge(result.raim?.status?.name ?: "NONE", 1, Int::plus)
        integrity.merge(result.report.state.name, 1, Int::plus)
        val alarm = result.report.state.name != "OK"
        if (alarm && !prevAlarm) alarmEpisodes++
        prevAlarm = alarm
        result.detectors.filter { it.verdict == DetectorVerdict.ALERT }.forEach {
            detectorAlarms.merge(it.kind.name, 1, Int::plus)
            detectorAlarmExample.putIfAbsent(it.kind.name, it.detail)
        }
        result.ephemerisBySystem.forEach { (k, v) -> withEph.merge(k.name, v, Int::plus) }
        val hpl = result.raim?.horizontalProtectionLevelM
        hpl?.let(hpls::add)
        val own = result.ownSolution ?: return
        own.used.groupingBy { it.constellation.name }.eachCount().forEach { (k, v) -> used.merge(k, v, Int::plus) }
        own.interSystemBiasesM.forEach { (k, v) -> isb.getOrPut(k.name) { ArrayList() } += v }
        val c = own.covarianceEnu
        val r95 = R95_PER_AXIS_SIGMA * sqrt((c[0, 0] + c[1, 1]) / 2)
        r95s += r95
        if (fix == null) return
        val err = own.point.distanceTo(fix.point)
        errors += err
        chipAcc += fix.accuracyM
        if (err <= r95) r95Covered++
        if (err <= fix.accuracyM * R95_PER_AXIS_SIGMA / 1.515) chipCovered++
        val ownAlt = own.point.altitude
        val fixAlt = fix.point.altitude
        if (ownAlt != null && fixAlt != null) upErrors += abs(ownAlt - fixAlt)
        if (hpl != null) {
            compared++
            if (err <= hpl) covered++
        }
    }

    fun report(): String = buildString {
        val total = raimStatus.values.sum().coerceAtLeast(1)
        val hours = ((lastMillis ?: 0) - (firstMillis ?: 0)) / 3_600_000.0
        appendLine()
        appendLine("Длительность: ${"%.2f".format(hours * 60)} мин")
        appendLine()
        appendLine("| Система | Измерено (сп.·эпох) | С эфемеридами (сп.·эпох) | В решении (сп.·эпох) | ISB медиана, м |")
        appendLine("|---|---|---|---|---|")
        (measured.keys + withEph.keys + used.keys).toSortedSet().forEach { k ->
            appendLine("| $k | ${measured[k] ?: 0} | ${withEph[k] ?: 0} | ${used[k] ?: 0} | ${q(isb[k].orEmpty(), 0.5)} |")
        }
        appendLine()
        appendLine("| Статус RAIM | Эпох | Доля |")
        appendLine("|---|---|---|")
        raimStatus.forEach { (k, v) -> appendLine("| $k | $v | ${pct(v.toDouble() / total)} |") }
        appendLine()
        appendLine("| Состояние целостности | Эпох | Доля |")
        appendLine("|---|---|---|")
        integrity.forEach { (k, v) -> appendLine("| $k | $v | ${pct(v.toDouble() / total)} |") }
        appendLine()
        appendLine("Эпизодов тревоги: $alarmEpisodes" + if (hours > 0) " (${"%.1f".format(alarmEpisodes / hours)} в час)" else "")
        appendLine()
        appendLine("| Детектор | Эпох с тревогой | Пример |")
        appendLine("|---|---|---|")
        detectorAlarms.forEach { (k, v) -> appendLine("| $k | $v | ${detectorAlarmExample[k]} |") }
        appendLine()
        appendLine("| Метрика | Значение |")
        appendLine("|---|---|")
        appendLine("| HPL, медиана / 95 % | ${q(hpls, 0.5)} / ${q(hpls, 0.95)} м |")
        appendLine("| R95 из ковариации WLS, медиана / 95 % | ${q(r95s, 0.5)} / ${q(r95s, 0.95)} м |")
        appendLine("| Расхождение WLS и фикса чипа, медиана / 95 % / макс. | ${q(errors, 0.5)} / ${q(errors, 0.95)} / ${q(errors, 1.0)} м |")
        appendLine("| Расхождение по высоте, медиана / 95 % | ${q(upErrors, 0.5)} / ${q(upErrors, 0.95)} м |")
        appendLine("| Accuracy чипа (68 %), медиана | ${q(chipAcc, 0.5)} м |")
        appendLine("| Покрытие: расхождение ≤ HPL | ${frac(covered, compared)} |")
        appendLine("| Покрытие: расхождение ≤ R95 WLS | ${frac(r95Covered, errors.size)} |")
        appendLine("| Покрытие: расхождение ≤ R95 чипа (accuracy·1,62) | ${frac(chipCovered, errors.size)} |")
    }

    private fun frac(a: Int, b: Int) = if (b == 0) "— (0 из 0)" else "${pct(a.toDouble() / b)} ($a из $b)"

    private fun pct(x: Double) = "%.1f %%".format(x * 100)

    private fun q(xs: List<Double>, p: Double): String {
        if (xs.isEmpty()) return "—"
        val s = xs.sorted()
        return "%.1f".format(s[((s.size - 1) * p).toInt()])
    }
}
