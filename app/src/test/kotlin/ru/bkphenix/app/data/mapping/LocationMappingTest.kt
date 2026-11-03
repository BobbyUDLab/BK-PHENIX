package ru.bkphenix.app.data.mapping

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import ru.bkphenix.app.data.model.RawLocation
import ru.bkphenix.core.model.PositionSource

class LocationMappingTest {
    private fun raw(provider: String = "gps", accuracy: Float? = 15.15f, mock: Boolean = false) =
        RawLocation(provider, 55.75, 37.62, 150.0, accuracy, 1_000L, 0L, mock)

    @Test
    fun `радиус 68 процентов переводится в сигму`() {
        val obs = LocationMapping.toObservation(raw())!!
        assertEquals(PositionSource.GNSS, obs.source)
        assertEquals(10.0, obs.accuracyM, 0.01)
    }

    @Test
    fun `провайдер network даёт источник NETWORK, passive и fused игнорируются`() {
        assertEquals(PositionSource.NETWORK, LocationMapping.toObservation(raw("network"))!!.source)
        assertNull(LocationMapping.toObservation(raw("passive")))
        assertNull(LocationMapping.toObservation(raw("fused")))
    }

    @Test
    fun `без погрешности или с mock наблюдение отбрасывается`() {
        assertNull(LocationMapping.toObservation(raw(accuracy = null)))
        assertNull(LocationMapping.toObservation(raw(accuracy = 0f)))
        assertNull(LocationMapping.toObservation(raw(mock = true)))
    }

    @Test
    fun `слишком малая погрешность ограничивается снизу`() {
        assertEquals(LocationMapping.MIN_SIGMA_M, LocationMapping.toObservation(raw(accuracy = 0.1f))!!.accuracyM, 0.0)
    }
}
