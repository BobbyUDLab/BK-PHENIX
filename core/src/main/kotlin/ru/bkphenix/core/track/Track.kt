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
 * Прореживание трека. Эпоха из [TrackPolicy.SKIP_STATES] и физически невозможный скачок обрывают
 * непрерывность: следующее решение начинает новую последовательность. Какие эпохи вообще писать,
 * решает [TrackPolicy.classify]; до прореживания точки проходят [TrackSmoother].
 */
public class TrackThinner(
    private val minDistanceM: Double = 3.0,
    private val maxIntervalMillis: Long = 30_000,
    private val maxSpeedMps: Double = MAX_REASONABLE_SPEED_MPS,
) {
    private var last: TrackPoint? = null

    public fun accept(p: TrackPoint): Boolean {
        if (p.integrity != null && TrackPolicy.SKIP_STATES.any { it.name == p.integrity }) {
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
        val exportable = points.filter { p -> TrackPolicy.SKIP_STATES.none { it.name == p.integrity } }
        append("<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n")
        append("<gpx version=\"1.1\" creator=\"").append(escape(creator)).append("\" ")
        append("xmlns=\"http://www.topografix.com/GPX/1/1\" ")
        append("xmlns:bk=\"https://github.com/BobbyUDLab/BK-PHENIX/gpx/1\">\n")
        exportable.firstOrNull()?.let {
            append("  <metadata><time>").append(iso.format(Instant.ofEpochMilli(it.timeMillis))).append("</time></metadata>\n")
        }
        append("  <trk>\n    <name>").append(escape(name)).append("</name>\n")
        // Отрезки — как на карте: разрыв по времени и невозможной скорости; смена качества линию не рвёт.
        val segments = mergeQuality(TrackSegments.split(exportable, minPoints = 1))
        for (segment in segments) {
            append("    <trkseg>\n")
            for (p in segment) {
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
            append("    </trkseg>\n")
        }
        append("  </trk>\n</gpx>\n")
    }

    /** Склейка соседних отрезков разного качества (они делят точку стыка) в один trkseg. */
    private fun mergeQuality(segments: List<TrackSegment>): List<List<TrackPoint>> {
        val out = mutableListOf<MutableList<TrackPoint>>()
        for (s in segments) {
            val last = out.lastOrNull()
            if (last != null && last.last() == s.points.first()) last += s.points.drop(1) else out += s.points.toMutableList()
        }
        return out
    }

    private fun num(v: Double, digits: Int) = String.format(Locale.ROOT, "%.${digits}f", v)

    private fun escape(s: String) = s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;").replace("\"", "&quot;")
}
