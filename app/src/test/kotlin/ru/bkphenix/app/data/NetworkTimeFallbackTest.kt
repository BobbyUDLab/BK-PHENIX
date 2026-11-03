package ru.bkphenix.app.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import ru.bkphenix.app.data.source.NetworkTimeFallback

class NetworkTimeFallbackTest {
    @Test
    fun `при автосинхронизации смещение нулевое с широкой погрешностью`() {
        val s = NetworkTimeFallback.sample(true)!!
        assertEquals(0L, s.offsetMillis)
        assertEquals(NetworkTimeFallback.AUTO_SYNC_UNCERTAINTY_MS, s.uncertaintyMillis)
    }

    @Test
    fun `без автосинхронизации сетевого времени нет`() {
        assertNull(NetworkTimeFallback.sample(false))
        assertNull(NetworkTimeFallback.sample(null))
    }
}
