package ru.bkphenix.tools

import ru.bkphenix.core.gnss.GnssIntegrityProcessor
import ru.bkphenix.core.gnss.GnssIntegrityResult
import ru.bkphenix.core.gnss.GpsEphemeris
import ru.bkphenix.core.gnss.Pseudoranges
import ru.bkphenix.core.gnss.RawGnssEpoch
import ru.bkphenix.core.gnss.RinexNavParser
import ru.bkphenix.core.model.GnssConstellation
import ru.bkphenix.core.model.PositionObservation
import ru.bkphenix.core.model.PositionSource
import ru.bkphenix.core.replay.GnssLoggerParser
import ru.bkphenix.core.replay.LoggedFix
import java.io.File
import kotlin.math.abs
import kotlin.system.exitProcess

private const val NANOS_PER_MILLI = 1_000_000L
private const val SECONDS_PER_WEEK = 604_800.0
private const val FIX_MATCH_MS = 1_500L

/** Секунды между GPS и UTC: 17 до 2017 года, 18 после. */
private fun leapSeconds(gpsMillis: Long): Long =
    if (gpsMillis + Pseudoranges.GPS_EPOCH_UNIX_MILLIS < 1_483_228_800_000L) 17 else 18

private fun usage(): Nothing {
    System.err.println("Использование: replay <журнал GnssLogger> [--nav <файл RINEX nav>]")
    exitProcess(2)
}

public fun main(args: Array<String>) {
    if (args.isEmpty()) usage()
    val logFile = File(args[0])
    val navFile = args.indexOf("--nav").takeIf { it >= 0 }?.let { args.getOrNull(it + 1) ?: usage() }?.let(::File)
    val log = logFile.useLines { GnssLoggerParser.parse(it) }
    val nav = navFile?.let { RinexNavParser.parseGps(it.readText()) }.orEmpty().groupBy { it.svid }
    println("# Прогон ${logFile.name}" + (navFile?.let { " с эфемеридами ${it.name}" } ?: " без внешних эфемерид"))
    println("Эпох: ${log.epochs.size}, фиксов чипа: ${log.fixes.size}, эфемерид RINEX: ${nav.values.sumOf { it.size }}")
    println(Replay(nav, log.fixes).run(log.epochs).report())
}

/** Проигрывает эпохи через [GnssIntegrityProcessor] и копит статистику. */
internal class Replay(private val nav: Map<Int, List<GpsEphemeris>>, fixes: List<LoggedFix>) {
    private val processor = GnssIntegrityProcessor()
    private val gpsFixes = fixes.filter { it.provider.equals("gps", ignoreCase = true) }.sortedBy { it.utcMillis }
    private val raimStatus = sortedMapOf<String, Int>()
    private val integrity = sortedMapOf<String, Int>()
    private val errors = ArrayList<Double>()
    private val hpls = ArrayList<Double>()
    private var covered = 0
    private var compared = 0

    fun run(epochs: List<RawGnssEpoch>): Replay {
        for (epoch in epochs) step(epoch)
        return this
    }

    private fun step(epoch: RawGnssEpoch) {
        val fullBias = epoch.clock.fullBiasNanos ?: return
        val gpsNanos = epoch.clock.timeNanos - fullBias - epoch.clock.biasNanos.toLong()
        val gpsMillis = gpsNanos / NANOS_PER_MILLI
        val utcMillis = gpsMillis + Pseudoranges.GPS_EPOCH_UNIX_MILLIS - leapSeconds(gpsMillis) * 1000
        val tow = (gpsNanos / 1e9) % SECONDS_PER_WEEK
        loadNearestEphemerides(tow)
        val fix = nearestFix(utcMillis)
        val obs = fix?.let { listOf(PositionObservation(PositionSource.GNSS, it.point, it.accuracyM, utcMillis)) }.orEmpty()
        val result = processor.process(epoch, obs, emptyList())
        record(result, fix)
    }

    /** Для каждого спутника — запись с ближайшим toe (как приёмник, получивший свежий кадр). */
    private fun loadNearestEphemerides(tow: Double) {
        for ((_, list) in nav) {
            list.filter { it.health == 0 }
                .minByOrNull { abs(wrap(tow - it.toe)) }
                ?.takeIf { it.isValidAt(tow) }
                ?.let(processor.ephemerides::put)
        }
    }

    private fun wrap(dt: Double): Double = when {
        dt > SECONDS_PER_WEEK / 2 -> dt - SECONDS_PER_WEEK
        dt < -SECONDS_PER_WEEK / 2 -> dt + SECONDS_PER_WEEK
        else -> dt
    }

    private fun nearestFix(utcMillis: Long): LoggedFix? =
        gpsFixes.minByOrNull { abs(it.utcMillis - utcMillis) }?.takeIf { abs(it.utcMillis - utcMillis) <= FIX_MATCH_MS }

    private fun record(result: GnssIntegrityResult, fix: LoggedFix?) {
        raimStatus.merge(result.raim?.status?.name ?: "NONE", 1, Int::plus)
        integrity.merge(result.report.state.name, 1, Int::plus)
        val hpl = result.raim?.horizontalProtectionLevelM
        hpl?.let(hpls::add)
        val own = result.ownSolution ?: return
        if (fix == null) return
        val err = own.point.distanceTo(fix.point)
        errors += err
        if (hpl != null) {
            compared++
            if (err <= hpl) covered++
        }
    }

    fun report(): String = buildString {
        val total = raimStatus.values.sum().coerceAtLeast(1)
        appendLine()
        appendLine("| Статус RAIM | Эпох | Доля |")
        appendLine("|---|---|---|")
        raimStatus.forEach { (k, v) -> appendLine("| $k | $v | ${pct(v.toDouble() / total)} |") }
        appendLine()
        appendLine("| Состояние целостности | Эпох | Доля |")
        appendLine("|---|---|---|")
        integrity.forEach { (k, v) -> appendLine("| $k | $v | ${pct(v.toDouble() / total)} |") }
        appendLine()
        appendLine("| Метрика | Значение |")
        appendLine("|---|---|")
        appendLine("| HPL, медиана / 95 % | ${q(hpls, 0.5)} / ${q(hpls, 0.95)} м |")
        appendLine("| Расхождение WLS и фикса чипа, медиана / 95 % / макс. | ${q(errors, 0.5)} / ${q(errors, 0.95)} / ${q(errors, 1.0)} м |")
        appendLine("| Покрытие: расхождение ≤ HPL | ${if (compared == 0) "—" else pct(covered.toDouble() / compared)} ($covered из $compared) |")
    }

    private fun pct(x: Double) = "%.1f %%".format(x * 100)

    private fun q(xs: List<Double>, p: Double): String {
        if (xs.isEmpty()) return "—"
        val s = xs.sorted()
        return "%.1f".format(s[((s.size - 1) * p).toInt()])
    }
}
