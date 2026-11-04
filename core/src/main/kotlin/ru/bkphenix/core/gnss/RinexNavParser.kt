package ru.bkphenix.core.gnss

import ru.bkphenix.core.geo.EcefCoordinate
import ru.bkphenix.core.model.GnssConstellation
import java.time.LocalDate
import java.time.temporal.ChronoUnit

/** Коэффициенты модели Клобушара (α₀…α₃, β₀…β₃) из заголовка RINEX. */
public data class KlobucharCoefficients(val alpha: List<Double>, val beta: List<Double>) {
    init {
        require(alpha.size == 4 && beta.size == 4) { "Нужно по 4 коэффициента α и β" }
    }
}

/** Содержимое навигационного файла RINEX: эфемериды всех систем и параметры заголовка. */
public data class RinexNav(
    val ephemerides: List<NavEphemeris>,
    val ionosphere: KlobucharCoefficients?,
    val leapSeconds: Int?,
) {
    val gps: List<GpsEphemeris> get() = ephemerides.filterIsInstance<GpsEphemeris>()
}

/**
 * Разбор GPS LNAV из навигационных файлов RINEX 3.x/4.x (broadcast ephemeris, например BRDC IGS)
 * и GPS-навигационных файлов RINEX 2.x (`*.yyn`): они приводятся к раскладке RINEX 3.
 * [parse] читает смешанные файлы (BRDM/BRDC BKG и DLR): GPS, ГЛОНАСС, Galileo, BeiDou, QZSS.
 * Прочие системы и записи RINEX 4, отличные от LNAV, пропускаются. Углы в RINEX уже в радианах,
 * toe — в секундах GPS-недели; toc переводится из календарной даты GPS-времени.
 */
public object RinexNavParser {
    private val GPS_EPOCH: LocalDate = LocalDate.of(1980, 1, 6)
    private const val SECONDS_PER_DAY = 86_400.0
    private const val SECONDS_PER_WEEK = 604_800.0
    private const val ORBIT_LINES = 7
    private const val FIELD_WIDTH = 19
    private const val RECORD_START = 23
    private const val ORBIT_START = 4
    private val recordStart = Regex("^[GRECJSI][ 0-9]\\d \\d{4} ")

    /** Все записи GPS LNAV файла; повреждённые записи пропускаются. */
    public fun parseGps(text: String): List<GpsEphemeris> {
        val raw = text.lineSequence().toList()
        val version = raw.firstOrNull()?.take(9)?.trim()?.toDoubleOrNull() ?: 3.0
        val lines = if (version < 3.0) toRinex3(raw) else raw
        val body = lines.indexOfFirst { it.contains("END OF HEADER") }.let { if (it < 0) 0 else it + 1 }
        val result = ArrayList<GpsEphemeris>()
        var lnavExpected = true
        var i = body
        while (i < lines.size) {
            val line = lines[i]
            when {
                // RINEX 4: «> EPH G01 LNAV» перед записью задаёт её тип.
                line.startsWith(">") -> {
                    lnavExpected = line.startsWith("> EPH") && line.contains(" G") && line.trim().endsWith("LNAV")
                    i++
                }
                line.startsWith("G") && recordStart.containsMatchIn(line) && lnavExpected &&
                    i + ORBIT_LINES < lines.size + 1 -> {
                    parseRecord(lines.subList(i, minOf(i + ORBIT_LINES + 1, lines.size)))?.let(result::add)
                    i += ORBIT_LINES + 1
                }
                else -> i++
            }
        }
        return result
    }

    private val anyRecordStart = Regex("^[GRECJSI]\\d\\d \\d{4} \\d\\d \\d\\d \\d\\d \\d\\d \\d\\d")
    private const val QZSS_PRN_OFFSET = 192
    private const val DEFAULT_LEAP_SECONDS = 18

    /** Все поддерживаемые системы; записи SBAS, IRNSS, CNAV и повреждённые пропускаются. */
    public fun parse(text: String): RinexNav {
        val raw = text.lineSequence().toList()
        val version = raw.firstOrNull()?.take(9)?.trim()?.toDoubleOrNull() ?: 3.0
        if (version < 3.0) {
            val gps = parseGps(text)
            return RinexNav(gps, ionosphere(raw), leapSeconds(raw))
        }
        val body = raw.indexOfFirst { it.contains("END OF HEADER") }.let { if (it < 0) 0 else it + 1 }
        val header = raw.subList(0, body)
        val leap = leapSeconds(header)
        val result = ArrayList<NavEphemeris>()
        var supported = true
        var i = body
        while (i < raw.size) {
            val line = raw[i]
            if (line.startsWith(">")) {
                val type = line.trim().substringAfterLast(' ')
                supported = line.startsWith("> EPH") && type in RINEX4_TYPES
                i++
                continue
            }
            if (!anyRecordStart.containsMatchIn(line)) {
                i++
                continue
            }
            var end = i + 1
            while (end < raw.size && raw[end].startsWith(" ")) end++
            if (supported) {
                val rec = raw.subList(i, end)
                when (line[0]) {
                    'G' -> parseRecord(rec)
                    'R' -> glonass(rec, leap ?: DEFAULT_LEAP_SECONDS)
                    'E', 'C', 'J' -> kepler(rec)
                    else -> null
                }?.let(result::add)
            }
            i = end
        }
        return RinexNav(result, ionosphere(header), leap)
    }

    private val RINEX4_TYPES = setOf("LNAV", "INAV", "FNAV", "D1", "D2", "FDMA")

    private fun leapSeconds(header: List<String>): Int? =
        header.firstOrNull { it.contains("LEAP SECONDS") }?.take(6)?.trim()?.toIntOrNull()

    /** GPSA/GPSB (RINEX 3) или ION ALPHA/ION BETA (RINEX 2). */
    private fun ionosphere(header: List<String>): KlobucharCoefficients? {
        fun row(prefix: String, label: String, start: Int): List<Double>? =
            header.firstOrNull { it.startsWith(prefix) && it.contains(label) }?.let { l ->
                (0 until 4).map { k ->
                    l.substring(start + 12 * k, minOf(start + 12 * (k + 1), l.length)).trim().replace('D', 'E').toDouble()
                }
            }
        return try {
            val a = row("GPSA", "IONOSPHERIC CORR", 5) ?: row(" ", "ION ALPHA", 2)
            val b = row("GPSB", "IONOSPHERIC CORR", 5) ?: row(" ", "ION BETA", 2)
            if (a != null && b != null) KlobucharCoefficients(a, b) else null
        } catch (e: NumberFormatException) {
            null
        } catch (e: IndexOutOfBoundsException) {
            null
        }
    }

    private class Epoch(val date: LocalDate, val secondOfDay: Double) {
        val daysSinceGpsEpoch: Long get() = ChronoUnit.DAYS.between(GPS_EPOCH, date)
        val secondOfWeek: Double get() = (daysSinceGpsEpoch % 7) * SECONDS_PER_DAY + secondOfDay
    }

    private fun epoch(head: String): Epoch = Epoch(
        LocalDate.of(head.substring(4, 8).trim().toInt(), head.substring(9, 11).trim().toInt(), head.substring(12, 14).trim().toInt()),
        head.substring(15, 17).trim().toInt() * 3600.0 + head.substring(18, 20).trim().toInt() * 60.0 + head.substring(21, 23).trim().toInt(),
    )

    /** Galileo, BeiDou D1/D2, QZSS LNAV: общая раскладка кеплеровых записей RINEX 3. */
    private fun kepler(rec: List<String>): KeplerEphemeris? {
        if (rec.size < ORBIT_LINES + 1) return null
        return try {
            val head = rec[0]
            val number = head.substring(1, 3).trim().toInt()
            val constellation = when (head[0]) {
                'E' -> GnssConstellation.GALILEO
                'C' -> GnssConstellation.BEIDOU
                else -> GnssConstellation.QZSS
            }
            val af = fields(head, RECORD_START, 3)
            val o = (1..ORBIT_LINES).map { fields(rec[it], ORBIT_START, 4) }
            val week = o[4][2].toInt().let { if (constellation == GnssConstellation.BEIDOU) it + GpsTime.BDT_WEEK_OFFSET else it }
            val source = if (constellation == GnssConstellation.GALILEO) o[4][1].toInt() else 0
            val groupDelay = when (constellation) {
                // I/NAV (E1-B, биты 0 и 9) — BGD E5b/E1; F/NAV — BGD E5a/E1.
                GnssConstellation.GALILEO -> if (source and 0x201 != 0) o[5][3] else o[5][2]
                else -> o[5][2]
            }
            KeplerEphemeris(
                sat = SatId(constellation, if (constellation == GnssConstellation.QZSS) number + QZSS_PRN_OFFSET else number),
                weekGps = week,
                health = o[5][1].toInt(),
                toc = epoch(head).secondOfWeek,
                af0 = af[0], af1 = af[1], af2 = af[2],
                groupDelayS = groupDelay,
                toe = o[2][0],
                sqrtA = o[1][3],
                eccentricity = o[1][1],
                i0 = o[3][0],
                omega0 = o[2][2],
                omega = o[3][2],
                m0 = o[0][3],
                deltaN = o[0][2],
                omegaDot = o[3][3],
                iDot = o[4][0],
                cuc = o[1][0], cus = o[1][2],
                crc = o[3][1], crs = o[0][1],
                cic = o[2][1], cis = o[2][3],
                dataSource = source,
            ).takeIf { it.sqrtA > 0 }
        } catch (e: NumberFormatException) {
            null
        } catch (e: IndexOutOfBoundsException) {
            null
        }
    }

    /** ГЛОНАСС: эпоха tb в UTC, координаты в км; переводится в шкалу GPS. */
    private fun glonass(rec: List<String>, leapSeconds: Int): GlonassEphemeris? {
        if (rec.size < GLONASS_ORBIT_LINES + 1) return null
        return try {
            val head = rec[0]
            val e = epoch(head)
            val gpsSeconds = e.daysSinceGpsEpoch * SECONDS_PER_DAY + e.secondOfDay + leapSeconds
            val week = (gpsSeconds / SECONDS_PER_WEEK).toInt()
            val c = fields(head, RECORD_START, 3)
            val o = (1..GLONASS_ORBIT_LINES).map { fields(rec[it], ORBIT_START, 4) }
            fun km(k: Int) = EcefCoordinate(o[0][k] * 1e3, o[1][k] * 1e3, o[2][k] * 1e3)
            GlonassEphemeris(
                sat = SatId(GnssConstellation.GLONASS, head.substring(1, 3).trim().toInt()),
                frequencyChannel = o[1][3].toInt(),
                health = o[0][3].toInt(),
                tbWeek = week,
                tbTow = gpsSeconds - week * SECONDS_PER_WEEK,
                clockBias = c[0],
                relativeFrequencyBias = c[1],
                position = km(0),
                velocity = km(1),
                acceleration = km(2),
            ).takeIf { it.position.norm > 1e7 }
        } catch (e: NumberFormatException) {
            null
        } catch (e: IndexOutOfBoundsException) {
            null
        }
    }

    private const val GLONASS_ORBIT_LINES = 3

    private val rinex2Start = Regex("^[ 0-9]\\d( [ 0-9]\\d){5} [ 0-9]\\d\\.\\d")

    /** RINEX 2: «PP YY MM DD HH MM SS.S» + поля с колонки 22, строки орбит — с колонки 3. */
    private fun toRinex3(lines: List<String>): List<String> {
        val body = lines.indexOfFirst { it.contains("END OF HEADER") } + 1
        val out = ArrayList<String>(lines.size)
        out += lines.subList(0, body)
        var i = body
        while (i < lines.size) {
            val head = lines[i]
            if (rinex2Start.containsMatchIn(head) && head.length >= 22) {
                val n = head.substring(0, 22).trim().split(Regex("\\s+"))
                val yy = n[1].toInt()
                val year = if (yy < 80) 2000 + yy else 1900 + yy
                val sec = n[6].toDouble().toInt()
                out += "G%02d %04d %02d %02d %02d %02d %02d".format(n[0].toInt(), year, n[2].toInt(), n[3].toInt(), n[4].toInt(), n[5].toInt(), sec) +
                    head.substring(22)
                for (k in 1..ORBIT_LINES) if (i + k < lines.size) out += " " + lines[i + k]
                i += ORBIT_LINES + 1
            } else {
                i++
            }
        }
        return out
    }

    private fun parseRecord(rec: List<String>): GpsEphemeris? {
        if (rec.size < ORBIT_LINES + 1) return null
        return try {
            val head = rec[0]
            val svid = head.substring(1, 3).trim().toInt()
            val year = head.substring(4, 8).trim().toInt()
            val month = head.substring(9, 11).trim().toInt()
            val day = head.substring(12, 14).trim().toInt()
            val hour = head.substring(15, 17).trim().toInt()
            val minute = head.substring(18, 20).trim().toInt()
            val second = head.substring(21, 23).trim().toInt()
            val af = fields(head, RECORD_START, 3)
            val o = (1..ORBIT_LINES).map { fields(rec[it], ORBIT_START, 4) }
            val days = ChronoUnit.DAYS.between(GPS_EPOCH, LocalDate.of(year, month, day))
            val toc = (days % 7) * SECONDS_PER_DAY + hour * 3600.0 + minute * 60.0 + second
            val week = o[4][2].toInt()
            GpsEphemeris(
                svid = svid,
                weekMod1024 = week % 1024,
                health = o[5][1].toInt(),
                uraIndex = uraIndex(o[5][0]),
                iodc = o[5][3].toInt(),
                iode = o[0][0].toInt(),
                tgd = o[5][2],
                toc = toc.coerceIn(0.0, SECONDS_PER_WEEK),
                af0 = af[0], af1 = af[1], af2 = af[2],
                toe = o[2][0],
                sqrtA = o[1][3],
                eccentricity = o[1][1],
                i0 = o[3][0],
                omega0 = o[2][2],
                omega = o[3][2],
                m0 = o[0][3],
                deltaN = o[0][2],
                omegaDot = o[3][3],
                iDot = o[4][0],
                cuc = o[1][0], cus = o[1][2],
                crc = o[3][1], crs = o[0][1],
                cic = o[2][1], cis = o[2][3],
            ).takeIf { it.sqrtA > 0 }
        } catch (e: NumberFormatException) {
            null
        } catch (e: IndexOutOfBoundsException) {
            null
        }
    }

    /** Поля фиксированной ширины (D19.12); отсутствующие в конце строки считаются нулём. */
    private fun fields(line: String, start: Int, count: Int): DoubleArray = DoubleArray(count) { k ->
        val from = start + k * FIELD_WIDTH
        if (from >= line.length) {
            0.0
        } else {
            val raw = line.substring(from, minOf(from + FIELD_WIDTH, line.length)).trim()
            if (raw.isEmpty()) 0.0 else raw.replace('D', 'E').replace('d', 'E').toDouble()
        }
    }

    /** Индекс URA (IS-GPS-200, 20.3.3.3.1.3) по точности в метрах из RINEX. */
    private fun uraIndex(meters: Double): Int {
        val bounds = doubleArrayOf(2.4, 3.4, 4.85, 6.85, 9.65, 13.65, 24.0, 48.0, 96.0, 192.0, 384.0, 768.0, 1536.0, 3072.0, 6144.0)
        val idx = bounds.indexOfFirst { meters <= it }
        return if (idx < 0) 15 else idx
    }
}
