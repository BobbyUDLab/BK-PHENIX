package ru.bkphenix.core.integrity

import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import ru.bkphenix.core.PhenixCore
import ru.bkphenix.core.geo.GeoPoint
import ru.bkphenix.core.model.PositionObservation
import ru.bkphenix.core.model.PositionSource

/**
 * Регрессия по реальной подмене (поездка 04.11.2026, 0.5.1): чип 2 минуты выдавал точку у Шереметьево в 20 км.
 * Отсев работал, но через > 30 с без принятой GNSS фильтр непрерывности перезапускался прямо на поддельной точке,
 * и в 13:10:40 и 13:12:11 МСК итог «прыгал» в аэропорт.
 */
class SheremetyevoSpoofTest {
    private val svo = GeoPoint(55.972, 37.415)

    @Test
    fun `поддельная точка у аэропорта не попадает в итог`() {
        val rows = javaClass.getResource("/svo_spoof_20261104.csv")!!.readText().lines()
            .filter { it.isNotBlank() && !it.startsWith("#") && !it.startsWith("provider") }
            .map { it.split(",") }
            .map { r ->
                val src = if (r[0] == "GPS") PositionSource.GNSS else PositionSource.NETWORK
                PositionObservation(src, GeoPoint(r[1].toDouble(), r[2].toDouble()), r[3].toDouble().coerceAtLeast(1.0), r[4].toLong())
            }
            .sortedBy { it.timestampMillis }
        val core = PhenixCore()
        var lastNetwork: PositionObservation? = null
        var spoofedEpochs = 0
        var maxJumpToAirport = Double.MAX_VALUE
        for (o in rows) {
            if (o.source == PositionSource.NETWORK) { lastNetwork = o; continue }
            val now = o.timestampMillis
            val net = lastNetwork?.takeIf { now - it.timestampMillis <= 60_000 }
            val s = core.solve(listOfNotNull(o, net), now)
            if (o.point.distanceTo(svo) < 5_000) spoofedEpochs++
            s.point?.let { maxJumpToAirport = minOf(maxJumpToAirport, it.distanceTo(svo)) }
        }
        assertTrue(spoofedEpochs > 50, "в выборке должна быть подмена: $spoofedEpochs")
        assertTrue(maxJumpToAirport > 10_000, "итог приблизился к аэропорту на ${maxJumpToAirport.toInt()} м")
    }
}
