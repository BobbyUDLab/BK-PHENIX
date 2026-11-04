package ru.bkphenix.core.compare

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import ru.bkphenix.core.Fixtures.TRUE_POINT
import ru.bkphenix.core.Fixtures.obs
import ru.bkphenix.core.model.PositionObservation
import ru.bkphenix.core.model.PositionSource
import ru.bkphenix.core.model.SourceGroup

class IndependenceTest {
    private val comparator = PositionComparator()

    @Test
    fun `чип и WLS — один голос`() {
        val votes = Independence.votes(listOf(obs(PositionSource.GNSS, TRUE_POINT, 5.0), obs(PositionSource.GNSS_WLS, TRUE_POINT, 5.0)))
        assertEquals(1, votes.size)
        assertEquals(setOf(SourceGroup.GNSS_SIGNAL), votes.single().groups)
    }

    @Test
    fun `сеть платформы не склеивает собственные соты и Wi-Fi`() {
        val list = listOf(
            obs(PositionSource.CELL, TRUE_POINT, 300.0),
            obs(PositionSource.WIFI_RSSI, TRUE_POINT, 30.0),
            obs(PositionSource.NETWORK, TRUE_POINT, 40.0),
            obs(PositionSource.GNSS, TRUE_POINT, 5.0),
        )
        // «Сеть» — повторитель GNSS: присоединяется к голосу сот, но не сливает соты с Wi-Fi.
        assertEquals(3, Independence.count(list))
        assertEquals(3, Independence.count(list.filterNot { it.source == PositionSource.NETWORK }))
    }

    @Test
    fun `сеть, опирающаяся на GNSS, не голосует отдельно`() {
        val assisted = PositionObservation(
            PositionSource.NETWORK, TRUE_POINT, 20.0, 0,
            origin = PositionSource.NETWORK.defaultOrigin + SourceGroup.GNSS_SIGNAL,
        )
        assertEquals(1, Independence.count(listOf(obs(PositionSource.GNSS, TRUE_POINT, 5.0), assisted)))
    }

    @Test
    fun `подменённые чип и WLS не перевешивают два независимых голоса`() {
        val fake = TRUE_POINT.offset(600.0, 45.0)
        val result = comparator.compare(
            listOf(
                obs(PositionSource.GNSS, fake, 4.0),
                obs(PositionSource.GNSS_WLS, fake.offset(2.0, 0.0), 4.0),
                obs(PositionSource.CELL, TRUE_POINT.offset(150.0, 200.0), 300.0),
                obs(PositionSource.WIFI_RTT, TRUE_POINT.offset(3.0, 10.0), 5.0),
                obs(PositionSource.BLUETOOTH, TRUE_POINT.offset(4.0, 100.0), 8.0),
            ),
        )
        assertEquals(setOf(PositionSource.GNSS, PositionSource.GNSS_WLS), result.rejected.map { it.source }.toSet())
        assertEquals(3, result.independentConfirmations)
    }

    @Test
    fun `зависимые наблюдения не сужают погрешность`() {
        val single = comparator.compare(listOf(obs(PositionSource.GNSS, TRUE_POINT, 5.0)))
        val pair = comparator.compare(listOf(obs(PositionSource.GNSS, TRUE_POINT, 5.0), obs(PositionSource.GNSS_WLS, TRUE_POINT, 5.0)))
        assertEquals(single.accuracyM!!, pair.accuracyM!!, 1e-9)
        assertEquals(1, pair.independentConfirmations)
        val independent = comparator.compare(listOf(obs(PositionSource.GNSS, TRUE_POINT, 5.0), obs(PositionSource.WIFI_RTT, TRUE_POINT, 5.0)))
        assertTrue(independent.accuracyM!! < 4.0)
        assertEquals(2, independent.independentConfirmations)
    }
}
