package ru.bkphenix.core.radio

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import ru.bkphenix.core.Fixtures.T0
import ru.bkphenix.core.Fixtures.TRUE_POINT
import ru.bkphenix.core.geo.GeoPoint
import java.util.Random
import kotlin.math.log10

class CellLocatorTest {
    private fun cell(id: Long, rsrp: Int?, serving: Boolean = false, ta: Int? = null) =
        CellMeasurement(CellTechnology.LTE, 250, 1, 10, id, null, rsrp, ta, serving)

    private fun tower(id: Long, p: GeoPoint, range: Double = 2_000.0) = CellTowerRecord(CellTechnology.LTE, 250, 1, 10, id, p, range)

    private fun dbOf(towers: List<CellTowerRecord>): CellTowerDatabase {
        val byId = towers.associateBy { it.cellId }
        return CellTowerDatabase { m -> byId[m.cellId] }
    }

    /** Мощность по той же модели, что в локаторе, с логнормальным разбросом [noiseDb]. */
    private fun rsrp(d: Double, noiseDb: Double) = (-70.0 - 30.0 * log10(d / 100.0) + noiseDb).toInt()

    @Test
    fun `без вышек в базе позиции нет`() {
        assertNull(CellLocator(EmptyCellTowerDatabase).locate(listOf(cell(1, -80)), T0))
    }

    @Test
    fun `одна сота без мощности — позиция вышки с половиной радиуса`() {
        val obs = CellLocator(dbOf(listOf(tower(1, TRUE_POINT, 3_000.0)))).locate(listOf(cell(1, null)), T0)!!
        assertTrue(obs.point.distanceTo(TRUE_POINT) < 1.0)
        assertTrue(obs.accuracyM in 1_000.0..1_600.0, "σ ${obs.accuracyM}")
    }

    @Test
    fun `трилатерация без шума находит точку и σ по TA меньше, чем по мощности`() {
        val towers = listOf(0.0, 120.0, 240.0).mapIndexed { i, b -> tower(i + 1L, TRUE_POINT.offset(800.0, b)) }
        val db = dbOf(towers)
        val byPower = towers.map { cell(it.cellId, rsrp(800.0, 0.0)) }
        val p = CellLocator(db).locate(byPower, T0)!!
        assertTrue(p.point.distanceTo(TRUE_POINT) < 60.0, "ошибка ${p.point.distanceTo(TRUE_POINT)}")
        val withTa = listOf(cell(1, rsrp(800.0, 0.0), serving = true, ta = 10)) + byPower.drop(1)
        val t = CellLocator(db).locate(withTa, T0)!!
        assertTrue(t.accuracyM < p.accuracyM, "TA ${t.accuracyM}, мощность ${p.accuracyM}")
    }

    @Test
    fun `σ честная на случайных сценариях с шумом мощности и ошибкой положения вышек`() {
        val rnd = Random(11)
        var covered = 0
        val errors = ArrayList<Double>()
        val trials = 300
        repeat(trials) { k ->
            val n = 1 + rnd.nextInt(5)
            val dist = List(n) { 200.0 + rnd.nextDouble() * 2_500.0 }
            val truthTowers = List(n) { TRUE_POINT.offset(dist[it], rnd.nextDouble() * 360) }
            // В базе — центр наблюдений, смещённый от антенны.
            val towers = truthTowers.mapIndexed { i, p ->
                tower(i + 1L, p.offset(kotlin.math.abs(rnd.nextGaussian()) * 200.0, rnd.nextDouble() * 360), 3_000.0)
            }
            val nearest = dist.indices.minBy { dist[it] }
            val meas = towers.indices.map { i ->
                val serving = i == nearest && k % 2 == 0
                cell(i + 1L, rsrp(dist[i], rnd.nextGaussian() * 8.0), serving, if (serving) (dist[i] / 78.12).toInt() else null)
            }
            val obs = CellLocator(dbOf(towers)).locate(meas, T0)!!
            val err = obs.point.distanceTo(TRUE_POINT)
            errors += err
            if (err <= 2.5 * obs.accuracyM) covered++
        }
        val coverage = covered.toDouble() / trials
        assertTrue(coverage >= 0.9, "покрытие 2.5σ: $coverage")
        assertTrue(errors.sorted()[trials / 2] < 1_000.0, "медиана ошибки ${errors.sorted()[trials / 2]}")
    }

    @Test
    fun `дальность по TA — середина шага`() {
        val (r, s) = CellLocator.taRange(CellTechnology.LTE, 3)!!
        assertEquals(3.5 * 78.12, r, 0.01)
        assertTrue(s in 60.0..70.0)
        assertNull(CellLocator.taRange(CellTechnology.NR, 3))
    }

    @Test
    fun `вышка второй SIM в 38 км не утягивает позицию`() {
        // 19.05.2026: сота основной SIM-карты рядом, сота второй SIM-карты из кэша второй SIM — в 38 км.
        val far = GeoPoint(TRUE_POINT.latitude + 0.34, TRUE_POINT.longitude)
        val db = dbOf(listOf(tower(1, TRUE_POINT, 1_000.0), tower(2, far, 3_560.0)))
        val obs = CellLocator(db).locate(listOf(cell(1, -75, serving = true), cell(2, -85, serving = true)), T0)!!
        assertTrue(obs.point.distanceTo(TRUE_POINT) < 1_000, "ошибка ${obs.point.distanceTo(TRUE_POINT)}")
    }

    @Test
    fun `больше согласованных вышек побеждает одну точную`() {
        val far = GeoPoint(TRUE_POINT.latitude + 0.3, TRUE_POINT.longitude)
        val near2 = GeoPoint(TRUE_POINT.latitude + 0.005, TRUE_POINT.longitude)
        val db = dbOf(listOf(tower(1, far, 300.0), tower(2, TRUE_POINT, 2_000.0), tower(3, near2, 2_000.0)))
        val obs = CellLocator(db).locate(listOf(cell(1, -80), cell(2, -80), cell(3, -80)), T0)!!
        assertTrue(obs.point.distanceTo(TRUE_POINT) < 2_000, "ошибка ${obs.point.distanceTo(TRUE_POINT)}")
    }

    @Test
    fun `замёрзшая обслуживающая сота отбрасывается, живая — нет`() {
        val db = dbOf(listOf(tower(1, TRUE_POINT)))
        val loc = CellLocator(db)
        for (s in 0..12) assertTrue(loc.locate(listOf(cell(1, -80 - s % 3, serving = true)), T0 + s * 5_000L) != null)
        val frozen = CellLocator(db)
        assertTrue(frozen.locate(listOf(cell(1, -85, serving = true)), T0) != null)
        assertTrue(frozen.locate(listOf(cell(1, -85, serving = true)), T0 + 55_000) != null)
        assertNull(frozen.locate(listOf(cell(1, -85, serving = true)), T0 + 65_000))
        assertNull(loc.locate(listOf(cell(1, -80, serving = true).copy(ageMillis = 120_000)), T0 + 70_000))
    }
}
