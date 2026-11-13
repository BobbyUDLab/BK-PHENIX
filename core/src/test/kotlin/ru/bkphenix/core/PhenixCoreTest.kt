package ru.bkphenix.core

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import ru.bkphenix.core.Fixtures.T0
import ru.bkphenix.core.Fixtures.TRUE_POINT
import ru.bkphenix.core.Fixtures.obs
import ru.bkphenix.core.anchor.Anchor
import ru.bkphenix.core.anchor.AnchorState
import ru.bkphenix.core.integrity.IntegrityEngine
import ru.bkphenix.core.integrity.IntegrityReport
import ru.bkphenix.core.integrity.IntegrityState
import ru.bkphenix.core.model.PositionSource
import ru.bkphenix.core.model.TimeObservation
import ru.bkphenix.core.model.TimeSource

class PhenixCoreTest {
    private val core = PhenixCore()

    @Test
    fun `глушение ГНСС - решение по сотовым и Wi-Fi, состояние GNSS_LOST`() {
        val solution = core.solve(
            listOf(
                obs(PositionSource.WIFI_RTT, TRUE_POINT.offset(2.0, 0.0), 3.0),
                obs(PositionSource.CELL, TRUE_POINT.offset(60.0, 180.0), 150.0),
            ),
            T0,
        )
        assertEquals(IntegrityState.GNSS_LOST, solution.integrity.state)
        assertTrue(solution.point!!.distanceTo(TRUE_POINT) < 5.0)
    }

    @Test
    fun `подозрительная ГНСС уступает сети без независимого кворума`() {
        val spoofed = obs(PositionSource.GNSS, TRUE_POINT.offset(19_000.0, 45.0), 8.0)
        val network = obs(PositionSource.NETWORK, TRUE_POINT, 20.0)
        val core = PhenixCore(
            integrityEngine = IntegrityEngine {
                IntegrityReport(IntegrityState.SPOOFING_SUSPECTED, listOf("Тревога детектора"))
            },
        )

        val solution = core.solve(listOf(spoofed, network), T0)

        assertEquals(network.point, solution.point)
        assertTrue(network in solution.comparison.accepted)
        assertTrue(spoofed in solution.comparison.rejected)
        assertEquals(IntegrityState.SPOOFING_SUSPECTED, solution.integrity.state)
    }

    @Test
    fun `независимый кворум сохраняет GNSS при тревоге и конфликтующей сети`() {
        val spoofed = obs(PositionSource.GNSS, TRUE_POINT.offset(19_000.0, 45.0), 8.0)
        val network = obs(PositionSource.NETWORK, TRUE_POINT, 20.0)
        val cell = obs(PositionSource.CELL, spoofed.point, 80.0)
        val wifi = obs(PositionSource.WIFI_RTT, spoofed.point.offset(2.0, 90.0), 5.0)
        val core = PhenixCore(
            integrityEngine = IntegrityEngine {
                IntegrityReport(IntegrityState.SPOOFING_SUSPECTED, listOf("Тревога детектора"))
            },
        )

        val solution = core.solve(listOf(spoofed, network, cell, wifi), T0)

        assertTrue(solution.comparison.hasQuorum)
        assertTrue(solution.point!!.distanceTo(spoofed.point) < 100.0)
    }

    @Test
    fun `подмена ГНСС на 500 м при одном источнике выявляется якорем`() {
        core.anchorManager.setAnchor(Anchor(TRUE_POINT, 10.0, T0))
        val solution = core.solve(listOf(obs(PositionSource.GNSS, TRUE_POINT.offset(500.0, 0.0), 5.0)), T0 + 1_000)
        assertEquals(AnchorState.ANCHOR_CONFLICT, solution.anchor.state)
        assertEquals(IntegrityState.SPOOFING_SUSPECTED, solution.integrity.state)
    }

    @Test
    fun `расхождение времени ГНСС и NITZ - подозрение на подмену`() {
        val solution = core.solve(
            observations = listOf(obs(PositionSource.GNSS, TRUE_POINT, 5.0)),
            nowMillis = T0,
            timeObservations = listOf(
                TimeObservation(TimeSource.GNSS, T0 + 3_600_000, 10),
                TimeObservation(TimeSource.NITZ, T0, 500),
            ),
        )
        assertEquals(IntegrityState.SPOOFING_SUSPECTED, solution.integrity.state)
    }

    @Test
    fun `без наблюдений используется якорь`() {
        core.anchorManager.setAnchor(Anchor(TRUE_POINT, 10.0, T0))
        val solution = core.solve(emptyList(), T0)
        assertEquals(TRUE_POINT, solution.point)
        assertEquals(IntegrityState.NO_DATA, solution.integrity.state)
    }
}
