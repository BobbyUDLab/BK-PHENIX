package ru.bkphenix.core.terrain

import ru.bkphenix.core.geo.GeoPoint
import java.io.DataInputStream
import java.io.InputStream
import kotlin.math.floor

/**
 * Рельеф и геоид региона (файл BKDM v1, см. tools/dem/build_dem.py).
 *
 * Высоты рельефа — ортометрические (над геоидом EGM2008, как в Copernicus DEM); [geoidM] — высота
 * геоида N над эллипсоидом WGS 84, так что эллипсоидальная высота h = H + N. ГНСС даёт h, барометр и
 * рельеф — H; без учёта N (в Москве ≈ 14–16 м) расхождение было бы систематическим.
 *
 * Copernicus DEM — модель поверхности (DSM): в плотной застройке и лесу она выше земли на высоту
 * крыш и крон; это учитывается в σ рельефа ([TERRAIN_SIGMA_M]).
 */
public class TerrainModel(
    private val south: Double,
    private val west: Double,
    private val dLat: Double,
    private val dLon: Double,
    public val rows: Int,
    public val cols: Int,
    private val heightsDm: ShortArray,
    private val geoid: Grid,
) {
    /** Сетка геоида с шагом [step] (см). */
    public class Grid(
        internal val south: Double,
        internal val west: Double,
        internal val step: Double,
        internal val rows: Int,
        internal val cols: Int,
        internal val valuesCm: ShortArray,
    )

    init {
        require(heightsDm.size == rows * cols && geoid.valuesCm.size == geoid.rows * geoid.cols)
    }

    /** Ортометрическая высота рельефа, м (билинейно), или null вне файла / без данных. */
    public fun elevationM(p: GeoPoint): Double? {
        val y = (p.latitude - south) / dLat
        val x = (p.longitude - west) / dLon
        if (y < 0 || x < 0 || y > rows - 1 || x > cols - 1) return null
        val r = minOf(floor(y).toInt(), rows - 2).coerceAtLeast(0)
        val c = minOf(floor(x).toInt(), cols - 2).coerceAtLeast(0)
        val v00 = heightsDm[r * cols + c]; val v01 = heightsDm[r * cols + c + 1]
        val v10 = heightsDm[(r + 1) * cols + c]; val v11 = heightsDm[(r + 1) * cols + c + 1]
        if (v00 == NODATA || v01 == NODATA || v10 == NODATA || v11 == NODATA) return null
        val fy = y - r; val fx = x - c
        return ((v00 * (1 - fx) + v01 * fx) * (1 - fy) + (v10 * (1 - fx) + v11 * fx) * fy) / 10.0
    }

    /** Высота геоида EGM2008 над эллипсоидом, м, или null вне сетки. */
    public fun geoidM(p: GeoPoint): Double? {
        val y = (p.latitude - geoid.south) / geoid.step
        val x = (p.longitude - geoid.west) / geoid.step
        if (y < 0 || x < 0 || y > geoid.rows - 1 || x > geoid.cols - 1) return null
        val r = minOf(floor(y).toInt(), geoid.rows - 2); val c = minOf(floor(x).toInt(), geoid.cols - 2)
        val v = geoid.valuesCm; val n = geoid.cols
        val fy = y - r; val fx = x - c
        return ((v[r * n + c] * (1 - fx) + v[r * n + c + 1] * fx) * (1 - fy) +
            (v[(r + 1) * n + c] * (1 - fx) + v[(r + 1) * n + c + 1] * fx) * fy) / 100.0
    }

    /** Эллипсоидальная высота → ортометрическая (над геоидом). */
    public fun orthometricM(p: GeoPoint, ellipsoidalM: Double): Double? = geoidM(p)?.let { ellipsoidalM - it }

    public companion object {
        public const val NODATA: Short = Short.MIN_VALUE

        /** σ рельефа как опоры высоты: вертикальная точность GLO-90 (~4 м) плюс крыши и кроны DSM. */
        public const val TERRAIN_SIGMA_M: Double = 8.0

        public fun read(input: InputStream): TerrainModel {
            val d = DataInputStream(input)
            val magic = ByteArray(4).also(d::readFully)
            require(String(magic, Charsets.US_ASCII) == "BKDM") { "не файл рельефа BKDM" }
            require(d.readInt() == 1) { "неподдерживаемая версия BKDM" }
            val s = d.readDouble(); val w = d.readDouble(); val dl = d.readDouble(); val dn = d.readDouble()
            val rows = d.readInt(); val cols = d.readInt()
            require(rows in 2..20_000 && cols in 2..20_000 && dl > 0 && dn > 0) { "битый заголовок рельефа" }
            val h = readShorts(d, rows * cols)
            val gs = d.readDouble(); val gw = d.readDouble(); val st = d.readDouble()
            val gr = d.readInt(); val gc = d.readInt()
            require(gr in 2..10_000 && gc in 2..10_000 && st > 0) { "битый заголовок геоида" }
            return TerrainModel(s, w, dl, dn, rows, cols, h, Grid(gs, gw, st, gr, gc, readShorts(d, gr * gc)))
        }

        private fun readShorts(d: DataInputStream, n: Int): ShortArray {
            val bytes = ByteArray(n * 2).also(d::readFully)
            return ShortArray(n) { (((bytes[2 * it].toInt() and 0xFF) shl 8) or (bytes[2 * it + 1].toInt() and 0xFF)).toShort() }
        }
    }
}
