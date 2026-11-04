package ru.bkphenix.core.roads

import ru.bkphenix.core.geo.GeoPoint
import java.io.DataInputStream
import java.io.InputStream
import java.util.PriorityQueue
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.floor
import kotlin.math.hypot

/** Какие дороги годятся для режима: бит 0 — авто, бит 1 — пешеход. */
public enum class RoadClass(public val mask: Int) { CAR(1), FOOT(2) }

/** Точка на отрезке графа: отрезок, доля вдоль него от A к B, сама точка и расстояние до исходной. */
public data class RoadCandidate(val segment: Int, val fraction: Double, val point: GeoPoint, val distanceM: Double)

/**
 * Неориентированный дорожный граф из OSM (формат BKRG, см. tools/roads/build_road_graph.py) с сеточным
 * индексом отрезков (~250 м) и ограниченным по дальности поиском кратчайшего пути.
 * Координаты хранятся в микроградусах; расстояния — в локальной плоскости (для десятков км точно).
 */
public class RoadGraph(
    private val lat: IntArray,
    private val lon: IntArray,
    private val segA: IntArray,
    private val segB: IntArray,
    private val segFlags: ByteArray,
) {
    public val nodeCount: Int get() = lat.size
    public val segmentCount: Int get() = segA.size

    private val mPerLat = 111_320.0
    private val mPerLon = 111_320.0 * cos((if (lat.isEmpty()) 55.0 else lat.average() / 1e6) * PI / 180)
    private val cellLat = CELL_M / mPerLat
    private val cellLon = CELL_M / mPerLon

    // Сеточный индекс: номер ячейки (строка·столбцов + столбец) → отрезки, задевающие её охватывающим
    // прямоугольником. Пары «ячейка, отрезок» упакованы в long и сортируются без упаковки в объекты.
    private val row0: Long
    private val col0: Long
    private val cols: Long
    private val cellIds: IntArray
    private val cellStart: IntArray
    private val cellRefs: IntArray

    // Смежность (CSR): узел → инцидентные отрезки.
    private val adjStart: IntArray
    private val adjSeg: IntArray

    init {
        require(segA.size == segB.size && segA.size == segFlags.size)
        var rMin = Long.MAX_VALUE; var rMax = Long.MIN_VALUE; var cMin = Long.MAX_VALUE; var cMax = Long.MIN_VALUE
        for (i in lat.indices) {
            val r = row(lat[i] / 1e6); val c = col(lon[i] / 1e6)
            if (r < rMin) rMin = r; if (r > rMax) rMax = r; if (c < cMin) cMin = c; if (c > cMax) cMax = c
        }
        row0 = if (lat.isEmpty()) 0 else rMin; col0 = if (lat.isEmpty()) 0 else cMin
        cols = if (lat.isEmpty()) 1 else cMax - cMin + 1
        var packed = LongArray(segA.size + 16); var n = 0
        for (s in segA.indices) {
            val la1 = lat[segA[s]] / 1e6; val lo1 = lon[segA[s]] / 1e6
            val la2 = lat[segB[s]] / 1e6; val lo2 = lon[segB[s]] / 1e6
            for (r in row(minOf(la1, la2))..row(maxOf(la1, la2))) for (c in col(minOf(lo1, lo2))..col(maxOf(lo1, lo2))) {
                if (n == packed.size) packed = packed.copyOf(n * 2)
                packed[n++] = (cellId(r, c).toLong() shl 32) or s.toLong()
            }
        }
        packed = packed.copyOf(n)
        packed.sort()
        cellRefs = IntArray(n) { (packed[it] and 0xFFFFFFFFL).toInt() }
        var cells = 0
        for (i in 0 until n) if (i == 0 || packed[i] ushr 32 != packed[i - 1] ushr 32) cells++
        cellIds = IntArray(cells); cellStart = IntArray(cells + 1)
        var k = 0
        for (i in 0 until n) if (i == 0 || packed[i] ushr 32 != packed[i - 1] ushr 32) { cellIds[k] = (packed[i] ushr 32).toInt(); cellStart[k++] = i }
        cellStart[cells] = n

        val deg = IntArray(lat.size + 1)
        for (s in segA.indices) { deg[segA[s] + 1]++; deg[segB[s] + 1]++ }
        for (i in 1..lat.size) deg[i] += deg[i - 1]
        adjStart = deg
        adjSeg = IntArray(segA.size * 2)
        val fill = deg.copyOf()
        for (s in segA.indices) { adjSeg[fill[segA[s]]++] = s; adjSeg[fill[segB[s]]++] = s }
    }

    private fun row(latDeg: Double) = floor(latDeg / cellLat).toLong()
    private fun col(lonDeg: Double) = floor(lonDeg / cellLon).toLong()
    private fun cellId(r: Long, c: Long): Int = ((r - row0) * cols + (c - col0)).toInt()

    private fun x(node: Int) = lon[node] / 1e6 * mPerLon
    private fun y(node: Int) = lat[node] / 1e6 * mPerLat

    public fun segmentLengthM(s: Int): Double = hypot(x(segB[s]) - x(segA[s]), y(segB[s]) - y(segA[s]))

    /** Отрезки класса [roadClass] в радиусе [radiusM]: ближайшая точка на каждом, по возрастанию расстояния. */
    public fun candidates(p: GeoPoint, radiusM: Double, roadClass: RoadClass, limit: Int = 8): List<RoadCandidate> {
        val px = p.longitude * mPerLon; val py = p.latitude * mPerLat
        val r0 = maxOf(row(p.latitude - radiusM / mPerLat), row0)
        val r1 = row(p.latitude + radiusM / mPerLat)
        val c0 = maxOf(col(p.longitude - radiusM / mPerLon), col0)
        val c1 = minOf(col(p.longitude + radiusM / mPerLon), col0 + cols - 1)
        val seen = HashSet<Int>()
        val out = ArrayList<RoadCandidate>()
        for (r in r0..r1) for (c in c0..c1) {
            val i = cellIds.binarySearch(cellId(r, c))
            if (i < 0) continue
            for (k in cellStart[i] until cellStart[i + 1]) {
                val s = cellRefs[k]
                if (segFlags[s].toInt() and roadClass.mask == 0 || !seen.add(s)) continue
                val ax = x(segA[s]); val ay = y(segA[s]); val bx = x(segB[s]); val by = y(segB[s])
                val dx = bx - ax; val dy = by - ay
                val l2 = dx * dx + dy * dy
                val t = if (l2 == 0.0) 0.0 else (((px - ax) * dx + (py - ay) * dy) / l2).coerceIn(0.0, 1.0)
                val qx = ax + t * dx; val qy = ay + t * dy
                val d = hypot(px - qx, py - qy)
                if (d <= radiusM) out += RoadCandidate(s, t, GeoPoint(qy / mPerLat, qx / mPerLon), d)
            }
        }
        return out.sortedBy { it.distanceM }.take(limit)
    }

    /** Ближайшая дорога класса в пределах [maxM] или null. */
    public fun nearest(p: GeoPoint, maxM: Double, roadClass: RoadClass): RoadCandidate? =
        candidates(p, maxM, roadClass, limit = 1).firstOrNull()

    /**
     * Кратчайшее расстояние по графу между точками на отрезках (Дейкстра, не дальше [maxM]) или null.
     * Движение по дорогам класса [roadClass]; односторонность не учитывается.
     */
    public fun routeDistanceM(from: RoadCandidate, to: RoadCandidate, maxM: Double, roadClass: RoadClass): Double? {
        val lenFrom = segmentLengthM(from.segment)
        val lenTo = segmentLengthM(to.segment)
        if (from.segment == to.segment) return kotlin.math.abs(to.fraction - from.fraction) * lenFrom
        val dist = HashMap<Int, Double>()
        val pq = PriorityQueue<Pair<Double, Int>>(compareBy { it.first })
        fun push(n: Int, d: Double) {
            if (d <= maxM && d < (dist[n] ?: Double.MAX_VALUE)) { dist[n] = d; pq.add(d to n) }
        }
        push(segA[from.segment], from.fraction * lenFrom)
        push(segB[from.segment], (1 - from.fraction) * lenFrom)
        val targetA = segA[to.segment]; val targetB = segB[to.segment]
        var best = Double.MAX_VALUE
        while (pq.isNotEmpty()) {
            val (d, n) = pq.poll()
            if (d > (dist[n] ?: Double.MAX_VALUE) || d >= best) continue
            if (n == targetA) best = minOf(best, d + to.fraction * lenTo)
            if (n == targetB) best = minOf(best, d + (1 - to.fraction) * lenTo)
            for (k in adjStart[n] until adjStart[n + 1]) {
                val s = adjSeg[k]
                if (segFlags[s].toInt() and roadClass.mask == 0) continue
                val m = if (segA[s] == n) segB[s] else segA[s]
                push(m, d + segmentLengthM(s))
            }
        }
        return best.takeIf { it <= maxM }
    }

    public companion object {
        public const val CELL_M: Double = 250.0

        public fun read(input: InputStream): RoadGraph {
            val data = DataInputStream(input.buffered(1 shl 16))
            val magic = ByteArray(4).also { data.readFully(it) }
            require(String(magic, Charsets.US_ASCII) == "BKRG") { "Не дорожный граф BKRG" }
            val version = data.readInt()
            require(version == 1) { "Неизвестная версия дорожного графа: $version" }
            val n = data.readInt(); val m = data.readInt()
            require(n >= 0 && m >= 0) { "Повреждённый дорожный граф" }
            val lat = IntArray(n); val lon = IntArray(n)
            for (i in 0 until n) { lat[i] = data.readInt(); lon[i] = data.readInt() }
            val a = IntArray(m); val b = IntArray(m); val f = ByteArray(m)
            for (i in 0 until m) {
                a[i] = data.readInt(); b[i] = data.readInt(); f[i] = data.readByte()
                require(a[i] in 0 until n && b[i] in 0 until n) { "Отрезок $i ссылается на несуществующий узел" }
            }
            return RoadGraph(lat, lon, a, b, f)
        }
    }
}

