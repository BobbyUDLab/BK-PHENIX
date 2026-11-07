package ru.bkphenix.core.radio

import ru.bkphenix.core.geo.GeoPoint
import java.io.BufferedReader
import java.io.InputStream
import java.io.InputStreamReader
import java.io.OutputStream
import java.io.OutputStreamWriter
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.sqrt

/**
 * Своя обучаемая база вышек: где телефон реально видел каждую соту, пока позиция была надёжной.
 *
 * Зачем: в краудсорсинговых базах бывают целые станции не на своём месте (одна из базовых станций — в 16 км от
 * реального места, полевые записи 11.04–02.07.2026), а свежие соты туда попадают с опозданием. Своя база
 * исправляет это после первого же надёжного прохода; 9 из 20 сот прогулки 07.11 уже встречались 04–05.11.
 *
 * Правила обучения (вызывающий отвечает за «надёжность» точки — см. [learn]):
 * - точка добавляется к соте, только если она в [MIN_STEP_M] от прошлой точки этой соты или прошло
 *   [MIN_INTERVAL_MS] — иначе стоянка дома весила бы больше всей прогулки;
 * - у зрелой соты точка дальше max([CONFLICT_MIN_M], 4·СКО) — конфликт, а не обучение; если конфликтов больше,
 *   чем точек, сота «переехала» и учится заново;
 * - позиция соты — центр наблюдений, радиус — max([MIN_RANGE_M], 2·СКО). Ответ дают соты с [MIN_SAMPLES] точками.
 *
 * Поиск: своя сота → своя станция LTE (eNB) → [fallback] (офлайн-база). Своё — раньше чужого: это наши измерения.
 * Не потокобезопасна: вызывать из одного потока (ядро приложения уже работает в одном).
 */
public class LearnedCellDatabase(
    private val fallback: CellTowerDatabase = EmptyCellTowerDatabase,
) : CellTowerDatabase {
    private class Stat(
        val technology: CellTechnology, val mcc: Int, val mnc: Int, val areaCode: Int, val cellId: Long,
        var n: Int = 0, var lat: Double = 0.0, var lon: Double = 0.0, var m2: Double = 0.0,
        var lastLat: Double = 0.0, var lastLon: Double = 0.0, var lastAt: Long = 0, var conflicts: Int = 0,
    ) {
        val rms: Double get() = if (n > 1) sqrt(m2 / n) else 0.0

        fun add(p: GeoPoint, t: Long) {
            n++
            val kx = M_PER_DEG * cos(lat0() * PI / 180)
            val dE0 = (p.longitude - lon) * kx; val dN0 = (p.latitude - lat) * M_PER_DEG
            if (n == 1) { lat = p.latitude; lon = p.longitude } else { lat += dN0 / n / M_PER_DEG; lon += dE0 / n / kx }
            val dE1 = (p.longitude - lon) * kx; val dN1 = (p.latitude - lat) * M_PER_DEG
            if (n > 1) m2 += dE0 * dE1 + dN0 * dN1
            lastLat = p.latitude; lastLon = p.longitude; lastAt = t
        }

        private fun lat0() = if (n <= 1) lastLat.takeIf { it != 0.0 } ?: lat else lat

        fun record(minRange: Double) = CellTowerRecord(
            technology, mcc, mnc, areaCode, cellId, GeoPoint(lat, lon), max(minRange, 2 * rms),
        )
    }

    private val cells = HashMap<Long, Stat>()

    /** Сколько сот и станций уже дают ответ. */
    public val matureCount: Int get() = cells.values.count { it.n >= MIN_SAMPLES }
    public val size: Int get() = cells.size

    override fun find(measurement: CellMeasurement): CellTowerRecord? {
        CellKey.of(measurement)?.let { k -> cells[k]?.takeIf { it.n >= MIN_SAMPLES }?.let { return it.record(MIN_RANGE_M) } }
        CellKey.siteOf(measurement)?.let { k -> cells[k]?.takeIf { it.n >= MIN_SAMPLES }?.let { return it.record(SITE_MIN_RANGE_M) } }
        return fallback.find(measurement)
    }

    /**
     * Учит соты эпохи по надёжной точке. Вызывающий передаёт точку только при целостности «OK», без запрета GNSS
     * и с точностью не хуже [MAX_ACCURACY_M] — иначе подменённая ГНСС отравила бы базу. Возвращает число учтённых сот.
     */
    public fun learn(measurements: List<CellMeasurement>, point: GeoPoint, accuracyM: Double, timestampMillis: Long): Int {
        if (accuracyM > MAX_ACCURACY_M) return 0
        var learned = 0
        for (m in measurements) {
            if ((m.ageMillis ?: 0) > CellLocator.MAX_AGE_MS) continue
            val mcc = m.mcc ?: continue
            val key = CellKey.of(m) ?: continue
            if (update(key, m, mcc, m.cellId!!, point, timestampMillis)) learned++
            CellKey.siteOf(m)?.let { update(it, m, mcc, (m.cellId and 0xFFFFFFF) shr 8, point, timestampMillis) }
        }
        return learned
    }

    private fun update(key: Long, m: CellMeasurement, mcc: Int, id: Long, p: GeoPoint, t: Long): Boolean {
        val s = cells.getOrPut(key) { Stat(m.technology, mcc, m.mnc ?: return false, m.areaCode ?: 0, id) }
        if (s.n > 0) {
            val kx = M_PER_DEG * cos(s.lat * PI / 180)
            val step = hypot((p.longitude - s.lastLon) * kx, (p.latitude - s.lastLat) * M_PER_DEG)
            if (step < MIN_STEP_M && t - s.lastAt in 0 until MIN_INTERVAL_MS) return false
            if (s.n >= MIN_SAMPLES) {
                val d = hypot((p.longitude - s.lon) * kx, (p.latitude - s.lat) * M_PER_DEG)
                if (d > max(CONFLICT_MIN_M, 4 * s.rms)) {
                    s.conflicts++
                    if (s.conflicts > s.n) cells[key] = Stat(s.technology, s.mcc, s.mnc, s.areaCode, s.cellId).also { it.add(p, t) }
                    return false
                }
            }
        }
        s.add(p, t)
        return true
    }

    /** Текстовый формат BKLC 1: строка на соту. Пишется целиком (файл маленький: сотни–тысячи строк). */
    public fun write(out: OutputStream) {
        val w = OutputStreamWriter(out, Charsets.UTF_8)
        w.write("$MAGIC\n")
        for ((k, s) in cells.entries.sortedBy { it.key }) {
            w.write("$k,${s.technology.name},${s.mcc},${s.mnc},${s.areaCode},${s.cellId},${s.n},${s.lat},${s.lon},${s.m2}," +
                "${s.lastLat},${s.lastLon},${s.lastAt},${s.conflicts}\n")
        }
        w.flush()
    }

    /**
     * Читает файл BKLC и объединяет со своим: одинаковые соты складываются (параллельная формула Уэлфорда),
     * так что импорт с другого телефона дополняет базу, а не затирает. Повреждённые строки пропускаются.
     * Возвращает число прочитанных строк.
     */
    public fun merge(input: InputStream): Int {
        val r = BufferedReader(InputStreamReader(input, Charsets.UTF_8))
        require(r.readLine()?.trim() == MAGIC) { "Не файл обучаемой базы вышек" }
        var read = 0
        r.forEachLine { line ->
            val f = line.split(',')
            if (f.size < 14) return@forEachLine
            val o = runCatching {
                f[0].toLong() to Stat(
                    CellTechnology.valueOf(f[1]), f[2].toInt(), f[3].toInt(), f[4].toInt(), f[5].toLong(),
                    f[6].toInt(), f[7].toDouble(), f[8].toDouble(), f[9].toDouble(),
                    f[10].toDouble(), f[11].toDouble(), f[12].toLong(), f[13].toInt(),
                )
            }.getOrNull() ?: return@forEachLine
            val (k, b) = o
            if (b.n <= 0 || b.lat !in -90.0..90.0 || b.lon !in -180.0..180.0) return@forEachLine
            read++
            val a = cells[k]
            if (a == null || a.n == 0) { cells[k] = b; return@forEachLine }
            val kx = M_PER_DEG * cos(a.lat * PI / 180)
            val dE = (b.lon - a.lon) * kx; val dN = (b.lat - a.lat) * M_PER_DEG
            if (a.n >= MIN_SAMPLES && b.n >= MIN_SAMPLES && hypot(dE, dN) > max(CONFLICT_MIN_M, 4 * max(a.rms, b.rms))) {
                if (b.lastAt > a.lastAt) cells[k] = b  // сота переехала — берём более свежее знание
                return@forEachLine
            }
            val n = a.n + b.n
            a.m2 += b.m2 + (dE * dE + dN * dN) * a.n * b.n / n
            a.lat += dN * b.n / n / M_PER_DEG; a.lon += dE * b.n / n / kx
            a.n = n
            if (b.lastAt > a.lastAt) { a.lastAt = b.lastAt; a.lastLat = b.lastLat; a.lastLon = b.lastLon }
        }
        return read
    }

    public companion object {
        public const val MAGIC: String = "BKLC 1"
        public const val MIN_SAMPLES: Int = 3
        public const val MIN_RANGE_M: Double = 500.0
        public const val SITE_MIN_RANGE_M: Double = 1_000.0
        public const val MIN_STEP_M: Double = 25.0
        public const val MIN_INTERVAL_MS: Long = 5 * 60_000L
        public const val CONFLICT_MIN_M: Double = 3_000.0
        /** Учимся только по точке не хуже этой точности. */
        public const val MAX_ACCURACY_M: Double = 30.0
        private const val M_PER_DEG = 111_320.0
    }
}
