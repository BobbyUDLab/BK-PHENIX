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
 * Прореживание трека. Недостоверная эпоха и физически невозможный скачок обрывают
 * непрерывность: следующее подтверждённое решение начинает новую последовательность.
 */
public class TrackThinner(
    private val minDistanceM: Double = 3.0,
    private val maxIntervalMillis: Long = 30_000,
    private val maxSpeedMps: Double = MAX_REASONABLE_SPEED_MPS,
) {
    private var last: TrackPoint? = null

    public fun accept(p: TrackPoint): Boolean {
        if (p.integrity != null && p.integrity != "OK") {
            reset()
            return false
        }
        val prev = last
        if (prev != null) {
            val elapsedMillis = p.timeMillis - prev.timeMillis
            if (elapsedMillis <= 0 || prev.point.distanceTo(p.point) / (elapsedMillis / 1000.0) > maxSpeedMps) {
                reset()
                return false
            }
        }
        val take = prev == null ||
            p.timeMillis - prev.timeMillis >= maxIntervalMillis ||
            prev.point.distanceTo(p.point) >= maxOf(minDistanceM, (p.accuracyM ?: 0.0) * 0.5)
        if (take) last = p
        return take
    }

    public fun reset() {
        last = null
    }

    public companion object {
        /** Жёсткий предел для трека приложения: 360 км/ч, выше — разрыв/ошибка позиции. */
        public const val MAX_REASONABLE_SPEED_MPS: Double = 100.0
    }
}

/** Экспорт трека в GPX 1.1; погрешность и целостность — в расширениях bkphenix. */
public object GpxWriter {
    private val iso = DateTimeFormatter.ISO_INSTANT

    public fun write(name: String, points: List<TrackPoint>, creator: String = "BK-PHENIX"): String = buildString {
        val exportable = points.filter { it.integrity == null || it.integrity == "OK" }
        append("<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n")
        append("<gpx version=\"1.1\" creator=\"").append(escape(creator)).append("\" ")
        append("xmlns=\"http://www.topografix.com/GPX/1/1\" ")
        append("xmlns:bk=\"https://github.com/BobbyUDLab/BK-PHENIX/gpx/1\">\n")
        exportable.firstOrNull()?.let {
            append("  <metadata><time>").append(iso.format(Instant.ofEpochMilli(it.timeMillis))).append("</time></metadata>\n")
        }
        append("  <trk>\n    <name>").append(escape(name)).append("</name>\n")
        var previous: TrackPoint? = null
        var segmentOpen = false
        fun closeSegment() {
            if (segmentOpen) append("    </trkseg>\n")
            segmentOpen = false
        }
        for (p in exportable) {
            val prev = previous
            if (prev != null) {
                val elapsedMillis = p.timeMillis - prev.timeMillis
                val speed = if (elapsedMillis > 0) prev.point.distanceTo(p.point) / (elapsedMillis / 1000.0) else Double.POSITIVE_INFINITY
                if (speed > TrackThinner.MAX_REASONABLE_SPEED_MPS) closeSegment()
            }
            if (!segmentOpen) {
                append("    <trkseg>\n")
                segmentOpen = true
            }
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
            previous = p
        }
        closeSegment()
        append("  </trk>\n</gpx>\n")
    }

    private fun num(v: Double, digits: Int) = String.format(Locale.ROOT, "%.${digits}f", v)

    private fun escape(s: String) = s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;").replace("\"", "&quot;")
}
