package ru.bkphenix.core.roads

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import ru.bkphenix.core.geo.GeoPoint
import ru.bkphenix.core.integrity.IntegrityState
import ru.bkphenix.core.track.MotionMode
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.DataOutputStream
import java.util.Random
import kotlin.math.abs

class MapMatcherTest {
    private val origin = GeoPoint(55.75, 37.62)

    /**
     * Две параллельные дороги на восток (y = 0 и y = 60 м), длиной 2 км, отрезки по 100 м, соединены
     * поперечинами на x = 0 и x = 2000; пешеходная тропа на y = −40 м.
     */
    private fun graph(): Pair<RoadGraph, List<GeoPoint>> {
        val pts = ArrayList<GeoPoint>(); val a = ArrayList<Int>(); val b = ArrayList<Int>(); val f = ArrayList<Byte>()
        fun line(y: Double, flags: Int): List<Int> {
            val ids = (0..20).map { i -> pts.add(origin.offset(y, 0.0).let { if (y < 0) origin.offset(-y, 180.0) else it }.offset(i * 100.0, 90.0)); pts.size - 1 }
            ids.zipWithNext().forEach { (x, z) -> a += x; b += z; f += flags.toByte() }
            return ids
        }
        val r0 = line(0.0, 3); val r1 = line(60.0, 3); val path = line(-40.0, 2)
        listOf(0, 20).forEach { i -> a += r0[i]; b += r1[i]; f += 3; a += r0[i]; b += path[i]; f += 2 }
        val g = RoadGraph(
            pts.map { (it.latitude * 1e6).toInt() }.toIntArray(), pts.map { (it.longitude * 1e6).toInt() }.toIntArray(),
            a.toIntArray(), b.toIntArray(), f.toByteArray(),
        )
        return g to pts
    }

    @Test
    fun `кандидаты и маршрут по графу`() {
        val (g, _) = graph()
        val p = origin.offset(550.0, 90.0).offset(10.0, 0.0)
        val c = g.candidates(p, 30.0, RoadClass.CAR)
        assertEquals(1, c.size)
        assertEquals(10.0, c[0].distanceM, 0.5)
        val far = g.candidates(origin.offset(1500.0, 90.0), 30.0, RoadClass.CAR)[0]
        assertEquals(950.0, g.routeDistanceM(c[0], far, 3_000.0, RoadClass.CAR)!!, 2.0)
        // На соседнюю дорогу — только через поперечину на x = 0 или 2000.
        val other = g.candidates(origin.offset(60.0, 0.0).offset(550.0, 90.0), 5.0, RoadClass.CAR)[0]
        assertEquals(550.0 + 60.0 + 550.0, g.routeDistanceM(c[0], other, 3_000.0, RoadClass.CAR)!!, 2.0)
        assertNull(g.routeDistanceM(c[0], other, 500.0, RoadClass.CAR))
    }

    @Test
    fun `авто держится своей дороги при шуме, соседняя в 60 м не перехватывает`() {
        val (g, _) = graph()
        val m = MapMatcher(g)
        val rnd = Random(3)
        var onRoad = 0; val n = 150
        for (k in 0 until n) {
            val truth = origin.offset(100.0 + k * 10.0, 90.0)
            val raw = truth.offset(rnd.nextGaussian() * 15.0, 0.0).offset(rnd.nextGaussian() * 15.0, 90.0)
            val r = m.match(raw, 15.0, MotionMode.CAR)
            assertEquals(raw, r.raw)
            if (r.matched != null && abs(r.matched!!.latitude - truth.latitude) * 111_000 < 5.0) onRoad++
        }
        assertTrue(onRoad >= 0.95 * n, "на верной дороге $onRoad из $n")
    }

    @Test
    fun `пешком привязка к тропе, авто тропу не видит, без привязки — ничего`() {
        val (g, _) = graph()
        val p = origin.offset(40.0, 180.0).offset(700.0, 90.0).offset(3.0, 0.0)
        val walk = MapMatcher(g).match(p, 5.0, MotionMode.WALK)
        assertTrue(walk.matched!!.distanceTo(p) < 5.0)
        val car = MapMatcher(g).match(p, 5.0, MotionMode.CAR)
        assertEquals(37.0, car.distanceToRoadM!!, 1.0)
        val none = MapMatcher(g).match(p, 5.0, MotionMode.NONE)
        assertNull(none.matched); assertNull(none.distanceToRoadM); assertFalse(none.offRoad)
    }

    @Test
    fun `увод с дороги — исходная точка и расстояние видны, после трёх эпох сигнал целостности`() {
        val (g, _) = graph()
        val m = MapMatcher(g); val mon = OffRoadMonitor()
        val states = (0 until 5).map { k ->
            val raw = origin.offset(300.0 + k * 10.0, 90.0).offset(200.0, 180.0)
            val r = m.match(raw, 8.0, MotionMode.CAR)
            assertNull(r.matched)
            assertEquals(200.0, r.distanceToRoadM!!, 1.0)
            assertTrue(r.offRoad)
            mon.update(r)
        }
        assertEquals(IntegrityState.OK, states[1].state)
        assertEquals(IntegrityState.SUSPICIOUS, states[2].state)
        assertTrue(states[4].reasons.single().contains("200 м"))
        // Вернулись на дорогу — сигнал снимается.
        assertEquals(IntegrityState.OK, mon.update(m.match(origin.offset(400.0, 90.0), 8.0, MotionMode.CAR)).state)
        // Без привязки вне дорог тревоги нет.
        assertEquals(IntegrityState.OK, mon.update(m.match(origin.offset(5_000.0, 0.0), 8.0, MotionMode.NONE)).state)
    }

    @Test
    fun `чтение BKRG и отказ на повреждённом файле`() {
        fun file(nodes: Int, a: Int, b: Int): ByteArray = ByteArrayOutputStream().also { o ->
            DataOutputStream(o).apply {
                write("BKRG".toByteArray()); writeInt(1); writeInt(nodes); writeInt(1)
                repeat(nodes) { writeInt(55_750_000 + it * 1000); writeInt(37_620_000) }
                writeInt(a); writeInt(b); writeByte(1)
            }
        }.toByteArray()
        val g = RoadGraph.read(ByteArrayInputStream(file(2, 0, 1)))
        assertEquals(2, g.nodeCount); assertEquals(1, g.segmentCount)
        assertNotNull(g.nearest(GeoPoint(55.7505, 37.6201), 50.0, RoadClass.CAR))
        assertNull(g.nearest(GeoPoint(55.7505, 37.6201), 50.0, RoadClass.FOOT))
        assertThrows<IllegalArgumentException> { RoadGraph.read(ByteArrayInputStream(file(2, 0, 5))) }
    }
}
