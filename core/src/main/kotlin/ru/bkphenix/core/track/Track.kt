package ru.bkphenix.core.track

import ru.bkphenix.core.geo.GeoPoint
import java.time.Instant
import java.time.format.DateTimeFormatter
import java.util.Locale

/** Точка трека: итоговое решение приложения в момент [timeMillis]. */
public data class TrackPoint(
    val timeMillis: Long,
    val point: GeoPoint,
    /** Погрешность решения (1σ), метры. */
    val accuracyM: Double?,
    /** Состояние целостности (IntegrityState.name) — чтобы на ПК видеть, где решению не доверяли. */
    val integrity: String? = null,
)

/**
 * Прореживание трека: точка добавляется, если ушли дальше [minDistanceM] с учётом погрешности
 * или прошло [maxIntervalMillis]. Так стоянка не забивает базу шумом, а движение пишется плотно.
 */
public class TrackThinner(
    private val minDistanceM: Double = 3.0,
    private val maxIntervalMillis: Long = 30_000,
) {
    private var last: TrackPoint? = null

    public fun accept(p: TrackPoint): Boolean {
        val prev = last
        val take = prev == null ||
            p.timeMillis - prev.timeMillis >= maxIntervalMillis ||
            prev.point.distanceTo(p.point) >= maxOf(minDistanceM, (p.accuracyM ?: 0.0) * 0.5)
        if (take) last = p
        return take
    }

    public fun reset() {
        last = null
    }
}

/** Экспорт трека в GPX 1.1; погрешность и целостность — в расширениях bkphenix. */
public object GpxWriter {
    private val iso = DateTimeFormatter.ISO_INSTANT

    public fun write(name: String, points: List<TrackPoint>, creator: String = "BK-PHENIX"): String = buildString {
        append("<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n")
        append("<gpx version=\"1.1\" creator=\"").append(escape(creator)).append("\" ")
        append("xmlns=\"http://www.topografix.com/GPX/1/1\" ")
        append("xmlns:bk=\"https://github.com/BobbyUDLab/BK-PHENIX/gpx/1\">\n")
        points.firstOrNull()?.let { append("  <metadata><time>").append(iso.format(Instant.ofEpochMilli(it.timeMillis))).append("</time></metadata>\n") }
        append("  <trk>\n    <name>").append(escape(name)).append("</name>\n    <trkseg>\n")
        for (p in points) {
            append("      <trkpt lat=\"").append(num(p.point.latitude, 7)).append("\" lon=\"").append(num(p.point.longitude, 7)).append("\">")
            p.point.altitude?.let { append("<ele>").append(num(it, 1)).append("</ele>") }
            append("<time>").append(iso.format(Instant.ofEpochMilli(p.timeMillis))).append("</time>")
            if (p.accuracyM != null || p.integrity != null) {
                append("<extensions>")
                p.accuracyM?.let { append("<bk:accuracy>").append(num(it, 1)).append("</bk:accuracy>") }
                p.integrity?.let { append("<bk:integrity>").append(escape(it)).append("</bk:integrity>") }
                append("</extensions>")
            }
            append("</trkpt>\n")
        }
        append("    </trkseg>\n  </trk>\n</gpx>\n")
    }

    private fun num(v: Double, digits: Int) = String.format(Locale.ROOT, "%.${digits}f", v)

    private fun escape(s: String) = s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;").replace("\"", "&quot;")
}
