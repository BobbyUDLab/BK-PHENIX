package ru.bkphenix.app.data.track

import org.junit.Assert.assertEquals
import org.junit.Test
import ru.bkphenix.core.geo.GeoPoint
import ru.bkphenix.core.track.TrackPoint

class TrackMappingTest {
    @Test
    fun roundTripKeepsAllFields() {
        val p = TrackPoint(123L, GeoPoint(55.75, 37.61, 140.0), 4.5, "OK")
        val e = p.toEntity(7)
        assertEquals(7L, e.trackId)
        assertEquals(p, e.toTrackPoint())
    }
}
