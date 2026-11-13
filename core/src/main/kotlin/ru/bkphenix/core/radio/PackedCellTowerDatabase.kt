package ru.bkphenix.core.radio

import ru.bkphenix.core.geo.GeoPoint
import java.io.DataInputStream
import java.io.InputStream

/**
 * Офлайн-база вышек в компактном формате BKCL (см. tools/celldb/build_cell_db.py): отсортированные
 * ключи и координаты в массивах, поиск двоичный. ~20 байт на соту в памяти.
 */
public class PackedCellTowerDatabase private constructor(
    private val keys: LongArray,
    private val lat: IntArray,
    private val lon: IntArray,
    private val range: IntArray,
) : CellTowerDatabase {
    public val size: Int get() = keys.size

    override fun find(measurement: CellMeasurement): CellTowerRecord? {
        if (measurement.mcc != MCC_RUSSIA) return null
        val key = CellKey.of(measurement) ?: return null
        var i = keys.binarySearch(key)
        var site = false
        if (i < 0) {
            // Точной соты нет — базовая станция LTE (eNB) с бо́льшим радиусом: позиция станции по известным
            // сотам. Полевые записи 04–07.11.2026: точных сот 22 из 156, по станции — 82, медиана 375 м.
            val sk = CellKey.siteOf(measurement) ?: return null
            i = keys.binarySearch(sk)
            if (i < 0) return null
            site = true
        }
        val r = if (range[i] > 0) range[i].toDouble() else UNKNOWN_RANGE_M
        return CellTowerRecord(
            measurement.technology, measurement.mcc, measurement.mnc ?: return null,
            measurement.areaCode ?: 0, measurement.cellId ?: return null,
            GeoPoint(lat[i] / 1e6, lon[i] / 1e6),
            if (site) maxOf(r, SITE_MIN_RANGE_M) else r,
        )
    }

    public companion object {
        /** База собирается только для России (MCC 250). */
        public const val MCC_RUSSIA: Int = 250

        /** Радиус соты, если в выгрузке его нет (одно наблюдение). */
        public const val UNKNOWN_RANGE_M: Double = 1_000.0

        /** Минимальный радиус записи базовой станции (eNB), м: точная сота неизвестна. */
        public const val SITE_MIN_RANGE_M: Double = 2_000.0

        public fun read(input: InputStream): PackedCellTowerDatabase {
            val data = DataInputStream(input.buffered())
            val magic = ByteArray(4).also { data.readFully(it) }
            require(String(magic, Charsets.US_ASCII) == "BKCL") { "Не база вышек BKCL" }
            val version = data.readInt()
            require(version == 1) { "Неизвестная версия базы вышек: $version" }
            val n = data.readInt()
            require(n >= 0) { "Повреждённая база вышек" }
            val keys = LongArray(n); val lat = IntArray(n); val lon = IntArray(n); val range = IntArray(n)
            for (i in 0 until n) {
                keys[i] = data.readLong(); lat[i] = data.readInt(); lon[i] = data.readInt(); range[i] = data.readInt()
                require(i == 0 || keys[i] > keys[i - 1]) { "Ключи базы вышек не отсортированы" }
            }
            return PackedCellTowerDatabase(keys, lat, lon, range)
        }
    }
}

/** Ключ соты в базе BKCL: технология (2 бита) | MNC (7 бит) | идентификатор (44 бита). */
public object CellKey {
    private const val ID_BITS = 44
    private const val ID_MASK = (1L shl ID_BITS) - 1

    public fun of(m: CellMeasurement): Long? {
        val mnc = m.mnc?.takeIf { it in 0..127 } ?: return null
        val cid = m.cellId?.takeIf { it > 0 } ?: return null
        val lac = m.areaCode?.toLong()
        val (tech, ident) = when (m.technology) {
            CellTechnology.GSM -> 0L to (((lac ?: return null) and 0xFFFF) shl 16 or (cid and 0xFFFF))
            CellTechnology.WCDMA -> 1L to (((lac ?: return null) and 0xFFFF) shl 28 or (cid and 0xFFFFFFF))
            CellTechnology.LTE -> 2L to (cid and 0xFFFFFFF)
            CellTechnology.NR -> 3L to (cid and 0xFFFFFFFFF)
        }
        return (tech shl 51) or (mnc.toLong() shl ID_BITS) or (ident and ID_MASK)
    }

    /** Признак записи базовой станции LTE в идентификаторе (бит 43; ECI занимает 28 бит). */
    public const val SITE_BIT: Long = 1L shl 43

    /** Ключ базовой станции LTE (eNB = ECI >> 8) или null для других технологий. */
    public fun siteOf(m: CellMeasurement): Long? {
        if (m.technology != CellTechnology.LTE) return null
        val mnc = m.mnc?.takeIf { it in 0..127 } ?: return null
        val cid = m.cellId?.takeIf { it > 0 } ?: return null
        return (2L shl 51) or (mnc.toLong() shl ID_BITS) or SITE_BIT or ((cid and 0xFFFFFFF) shr 8)
    }
}
