package ru.bkphenix.core.anchor

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertSame
import org.junit.jupiter.api.Test
import ru.bkphenix.core.Fixtures.T0
import ru.bkphenix.core.Fixtures.TRUE_POINT

class AnchorManagerTest {
    private val manager = AnchorManager()
    private val anchor = Anchor(TRUE_POINT, accuracyM = 10.0, timestampMillis = T0)

    @Test
    fun `без якоря состояние NO_ANCHOR`() {
        assertEquals(AnchorState.NO_ANCHOR, manager.evaluate(TRUE_POINT, 5.0, T0).state)
    }

    @Test
    fun `решение рядом с якорем даёт HUMAN_ANCHOR`() {
        manager.setAnchor(anchor)
        assertEquals(AnchorState.HUMAN_ANCHOR, manager.evaluate(TRUE_POINT.offset(20.0, 10.0), 5.0, T0).state)
    }

    @Test
    fun `сдвиг ГНСС на 500 м даёт ANCHOR_CONFLICT, якорь не переписывается`() {
        manager.setAnchor(anchor)
        val evaluation = manager.evaluate(TRUE_POINT.offset(500.0, 90.0), 5.0, T0 + 10_000)
        assertEquals(AnchorState.ANCHOR_CONFLICT, evaluation.state)
        assertEquals(500.0, evaluation.deviationM!!, 0.5)
        assertSame(anchor, manager.anchor)
    }

    @Test
    fun `перемещение пешком за достаточное время не считается конфликтом`() {
        manager.setAnchor(anchor)
        val evaluation = manager.evaluate(TRUE_POINT.offset(500.0, 90.0), 5.0, T0 + 5 * 60_000)
        assertEquals(AnchorState.HUMAN_ANCHOR, evaluation.state)
    }
}
