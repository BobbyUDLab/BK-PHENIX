package ru.bkphenix.core.track

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import ru.bkphenix.core.geo.GeoPoint

class TrackTest {
    private val start = GeoPoint(55.751244, 37.618423, 150.0)

    @Test
    fun `прореживание пропускает стоянку и берёт движение`() {
        val t = TrackThinner(minDistanceM = 3.0, maxIntervalMillis = 30_000)
        assertTrue(t.accept(TrackPoint(0, start, 2.0, "OK")))
        assertFalse(t.accept(TrackPoint(1_000, start.offset(1.0, 90.0), 2.0, "OK")))
        assertTrue(t.accept(TrackPoint(2_000, start.offset(5.0, 90.0), 2.0, "OK")))
        assertFalse(t.accept(TrackPoint(3_000, start.offset(9.0, 90.0), 20.0, "OK")), "шаг меньше половины погрешности")
        assertTrue(t.accept(TrackPoint(40_000, start.offset(5.0, 90.0), 2.0, "OK")), "по времени")
    }

    @Test
    fun `недостоверная эпоха отбрасывается и сбрасывает предыдущую точку`() {
        val t = TrackThinner()
        assertTrue(t.accept(TrackPoint(1_000, start, 5.0, "OK")))
        assertFalse(t.accept(TrackPoint(2_000, start.offset(20.0, 90.0), 5.0, "INCONSISTENT")))
        assertTrue(t.accept(TrackPoint(3_000, start.offset(5_000.0, 90.0), 5.0, "OK")))
    }

    @Test
    fun `невозможный скачок не попадает в трек и обрывает непрерывность`() {
        val t = TrackThinner()
        assertTrue(t.accept(TrackPoint(1_000, start, 5.0, "OK")))
        assertFalse(t.accept(TrackPoint(2_000, start.offset(13_800.0, 0.0), 5.0, "OK")))
        assertTrue(t.accept(TrackPoint(3_000, start.offset(15.0, 90.0), 5.0, "OK")))
    }

    @Test
    fun `GPX фильтрует недостоверные точки и начинает новый сегмент после скачка`() {
        val gpx = GpxWriter.write(
            "Трек",
            listOf(
                TrackPoint(1_700_000_000_000, start, 3.25, "OK"),
                TrackPoint(1_700_000_001_000, GeoPoint(55.8, 37.0), null, "SPOOFING_SUSPECTED"),
                TrackPoint(1_700_000_001_000, GeoPoint(55.0, 37.0), null, "OK"),
            ),
        )
        assertEquals(2, Regex("<trkseg>").findAll(gpx).count())
        assertEquals(2, Regex("<trkpt ").findAll(gpx).count())
        assertFalse("55.8000000" in gpx)
    }

    @Test
    fun `GPX 1_1 с временем высотой и расширениями`() {
        val gpx = GpxWriter.write(
            "Прогулка <1>",
            listOf(TrackPoint(1_700_000_000_000, start, 3.25, "OK"), TrackPoint(1_700_000_001_000, GeoPoint(55.0, 37.0), null)),
        )
        assertTrue(gpx.startsWith("<?xml version=\"1.0\" encoding=\"UTF-8\"?>"))
        assertTrue("<name>Прогулка &lt;1&gt;</name>" in gpx)
        assertTrue(
            "<trkpt lat=\"55.7512440\" lon=\"37.6184230\"><ele>150.0</ele><time>2023-11-14T22:13:20Z</time>" +
                "<extensions><bk:accuracy>3.3</bk:accuracy><bk:integrity>OK</bk:integrity></extensions></trkpt>" in gpx,
        )
        assertTrue("<trkpt lat=\"55.0000000\" lon=\"37.0000000\"><time>2023-11-14T22:13:21Z</time></trkpt>" in gpx)
        assertEquals(2, Regex("<trkpt ").findAll(gpx).count())
    }
}
