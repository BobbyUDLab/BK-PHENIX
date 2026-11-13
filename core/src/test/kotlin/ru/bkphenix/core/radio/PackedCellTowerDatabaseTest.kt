package ru.bkphenix.core.radio

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.DataOutputStream

class PackedCellTowerDatabaseTest {
    private fun m(tech: CellTechnology, mnc: Int, lac: Int?, cid: Long, mcc: Int = 250) =
        CellMeasurement(tech, mcc, mnc, lac, cid, null, -90, null, true)

    private fun bytes(rows: List<Triple<Long, Pair<Double, Double>, Int>>, sort: Boolean = true): ByteArray {
        val out = ByteArrayOutputStream()
        DataOutputStream(out).apply {
            write("BKCL".toByteArray()); writeInt(1); writeInt(rows.size)
            (if (sort) rows.sortedBy { it.first } else rows).forEach { (k, p, r) ->
                writeLong(k); writeInt((p.first * 1e6).toInt()); writeInt((p.second * 1e6).toInt()); writeInt(r)
            }
        }
        return out.toByteArray()
    }

    @Test
    fun `ключи совпадают со сборщиком базы`() {
        // Значения посчитаны tools/celldb/build_cell_db.py: key(tech, mnc, lac, cid).
        assertEquals((2L shl 51) or (1L shl 44) or 123456789L, CellKey.of(m(CellTechnology.LTE, 1, 7700, 123456789)))
        assertEquals((0L shl 51) or (99L shl 44) or (7700L shl 16) or 4321L, CellKey.of(m(CellTechnology.GSM, 99, 7700, 4321)))
        assertEquals((1L shl 51) or (2L shl 44) or (7700L shl 28) or 26_000_000L, CellKey.of(m(CellTechnology.WCDMA, 2, 7700, 26_000_000)))
        assertNull(CellKey.of(m(CellTechnology.GSM, 1, null, 4321)))
    }

    @Test
    fun `поиск находит соту, чужие и неизвестные — нет`() {
        val lte = m(CellTechnology.LTE, 1, 7700, 123456789)
        val gsm = m(CellTechnology.GSM, 99, 7700, 4321)
        val db = PackedCellTowerDatabase.read(ByteArrayInputStream(bytes(listOf(
            Triple(CellKey.of(lte)!!, 55.75 to 37.62, 1500),
            Triple(CellKey.of(gsm)!!, 55.70 to 37.50, 0),
        ))))
        assertEquals(2, db.size)
        val r = db.find(lte)!!
        assertEquals(55.75, r.position.latitude, 1e-6)
        assertEquals(1500.0, r.rangeM)
        assertEquals(PackedCellTowerDatabase.UNKNOWN_RANGE_M, db.find(gsm)!!.rangeM)
        assertNull(db.find(m(CellTechnology.LTE, 1, 7700, 5)))
        assertNull(db.find(m(CellTechnology.LTE, 1, 7700, 123456789, mcc = 257)))
    }

    @Test
    fun `неизвестная сота находится по станции LTE с радиусом не меньше 2 км`() {
        val known = m(CellTechnology.LTE, 2, 9742, 25_822_124)
        val sibling = m(CellTechnology.LTE, 2, 9742, (25_822_124L and 0xFFFFF00L) or 0x05L)
        // Ключ станции считает build_cell_db.py: site_key(mnc, eci).
        val site = (2L shl 51) or (2L shl 44) or CellKey.SITE_BIT or (25_822_124L shr 8)
        assertEquals(site, CellKey.siteOf(sibling))
        val db = PackedCellTowerDatabase.read(ByteArrayInputStream(bytes(listOf(
            Triple(CellKey.of(known)!!, 55.7512 to 37.6184, 300),
            Triple(site, 55.7522 to 37.6194, 900),
        ))))
        assertEquals(300.0, db.find(known)!!.rangeM)
        val r = db.find(sibling)!!
        assertEquals(55.7522, r.position.latitude, 1e-6)
        assertEquals(PackedCellTowerDatabase.SITE_MIN_RANGE_M, r.rangeM)
        assertNull(db.find(m(CellTechnology.LTE, 2, 9742, 99_999_999)))
        assertNull(CellKey.siteOf(m(CellTechnology.WCDMA, 2, 9742, 25_822_124)))
    }

    @Test
    fun `повреждённая база отвергается`() {
        val k = CellKey.of(m(CellTechnology.LTE, 1, 1, 1))!!
        assertThrows<IllegalArgumentException> {
            PackedCellTowerDatabase.read(ByteArrayInputStream(bytes(listOf(Triple(k + 1, 0.0 to 0.0, 0), Triple(k, 0.0 to 0.0, 0)), sort = false)))
        }
        assertThrows<IllegalArgumentException> { PackedCellTowerDatabase.read(ByteArrayInputStream("XXXX".toByteArray() + ByteArray(8))) }
    }
}
