package ru.bkphenix.core.gnss

import ru.bkphenix.core.integrity.GpsParity
import ru.bkphenix.core.integrity.NavigationWord
import ru.bkphenix.core.model.GnssConstellation
import kotlin.math.abs

/** Подкадр GPS LNAV одного спутника: 10 слов по 30 бит. */
public data class NavigationSubframe(val svid: Int, val words: List<NavigationWord>)

/**
 * Разбор подкадров 1–3 GPS LNAV в эфемериды (IS-GPS-200, рис. 20-1 и табл. 20-I, 20-III).
 * Подкадр с ошибкой чётности хотя бы в одном слове отбрасывается.
 */
public object LnavDecoder {
    private const val WORDS = 10
    private const val PI = 3.1415926535898 // значение π, принятое в IS-GPS-200

    /** 300 бит подкадра, где на месте каждого слова — исходные 24 бита данных и 6 бит чётности. */
    public class Bits internal constructor(private val data: IntArray) {
        /** Беззнаковое поле из бит [start]…[start]+[len]-1 (нумерация с 1, как в ИКД). */
        public fun u(start: Int, len: Int): Long {
            var v = 0L
            for (i in start until start + len) {
                val word = (i - 1) / 30
                val bitInWord = (i - 1) % 30 // 0 — D1
                require(bitInWord < 24) { "Бит $i — бит чётности" }
                v = (v shl 1) or ((data[word] ushr (23 - bitInWord)) and 1).toLong()
            }
            return v
        }

        public fun s(start: Int, len: Int): Long {
            val v = u(start, len)
            return if (v and (1L shl (len - 1)) != 0L) v - (1L shl len) else v
        }

        /** Поле, разнесённое на две части (старшие и младшие биты). */
        public fun u2(s1: Int, l1: Int, s2: Int, l2: Int): Long = (u(s1, l1) shl l2) or u(s2, l2)

        public fun s2(s1: Int, l1: Int, s2: Int, l2: Int): Long {
            val v = u2(s1, l1, s2, l2)
            val len = l1 + l2
            return if (v and (1L shl (len - 1)) != 0L) v - (1L shl len) else v
        }
    }

    /** Номер подкадра (1–5) из HOW или `null`, если слова повреждены. */
    public fun subframeId(subframe: NavigationSubframe): Int? = bits(subframe)?.u(50, 3)?.toInt()

    internal fun bits(subframe: NavigationSubframe): Bits? {
        if (subframe.words.size != WORDS) return null
        if (subframe.words.any { !GpsParity.check(it.word, it.previousD29, it.previousD30) }) return null
        return Bits(IntArray(WORDS) { GpsParity.decodeData(subframe.words[it].word, subframe.words[it].previousD30) })
    }

    internal fun p2(e: Int): Double = Math.scalb(1.0, e)

    /** Поля подкадра 1. */
    public data class Subframe1(
        val week: Int, val ura: Int, val health: Int, val iodc: Int,
        val tgd: Double, val toc: Double, val af2: Double, val af1: Double, val af0: Double,
    )

    /** Поля подкадра 2. */
    public data class Subframe2(
        val iode: Int, val crs: Double, val deltaN: Double, val m0: Double, val cuc: Double,
        val e: Double, val cus: Double, val sqrtA: Double, val toe: Double,
    )

    /** Поля подкадра 3. */
    public data class Subframe3(
        val cic: Double, val omega0: Double, val cis: Double, val i0: Double, val crc: Double,
        val omega: Double, val omegaDot: Double, val iode: Int, val iDot: Double,
    )

    public fun parse1(b: Bits): Subframe1 = Subframe1(
        week = b.u(61, 10).toInt(),
        ura = b.u(73, 4).toInt(),
        health = b.u(77, 6).toInt(),
        iodc = b.u2(83, 2, 211, 8).toInt(),
        tgd = b.s(197, 8) * p2(-31),
        toc = b.u(219, 16) * 16.0,
        af2 = b.s(241, 8) * p2(-55),
        af1 = b.s(249, 16) * p2(-43),
        af0 = b.s(271, 22) * p2(-31),
    )

    public fun parse2(b: Bits): Subframe2 = Subframe2(
        iode = b.u(61, 8).toInt(),
        crs = b.s(69, 16) * p2(-5),
        deltaN = b.s(91, 16) * p2(-43) * PI,
        m0 = b.s2(107, 8, 121, 24) * p2(-31) * PI,
        cuc = b.s(151, 16) * p2(-29),
        e = b.u2(167, 8, 181, 24) * p2(-33),
        cus = b.s(211, 16) * p2(-29),
        sqrtA = b.u2(227, 8, 241, 24) * p2(-19),
        toe = b.u(271, 16) * 16.0,
    )

    public fun parse3(b: Bits): Subframe3 = Subframe3(
        cic = b.s(61, 16) * p2(-29),
        omega0 = b.s2(77, 8, 91, 24) * p2(-31) * PI,
        cis = b.s(121, 16) * p2(-29),
        i0 = b.s2(137, 8, 151, 24) * p2(-31) * PI,
        crc = b.s(181, 16) * p2(-5),
        omega = b.s2(197, 8, 211, 24) * p2(-31) * PI,
        omegaDot = b.s(241, 24) * p2(-43) * PI,
        iode = b.u(271, 8).toInt(),
        iDot = b.s(279, 14) * p2(-43) * PI,
    )

    /** Эфемериды из согласованных подкадров (IODE₂ = IODE₃ = младшие 8 бит IODC) или `null`. */
    public fun combine(svid: Int, s1: Subframe1, s2: Subframe2, s3: Subframe3): GpsEphemeris? {
        if (s2.iode != s3.iode || (s1.iodc and 0xFF) != s2.iode) return null
        return GpsEphemeris(
            svid = svid, weekMod1024 = s1.week, health = s1.health, uraIndex = s1.ura, iodc = s1.iodc,
            iode = s2.iode, tgd = s1.tgd, toc = s1.toc, af0 = s1.af0, af1 = s1.af1, af2 = s1.af2,
            toe = s2.toe, sqrtA = s2.sqrtA, eccentricity = s2.e, i0 = s3.i0, omega0 = s3.omega0,
            omega = s3.omega, m0 = s2.m0, deltaN = s2.deltaN, omegaDot = s3.omegaDot, iDot = s3.iDot,
            cuc = s2.cuc, cus = s2.cus, crc = s3.crc, crs = s2.crs, cic = s3.cic, cis = s3.cis,
        )
    }
}

/**
 * Накопитель эфемерид: собирает подкадры 1–3 по каждому спутнику и выдаёт актуальные эфемериды.
 * Не потокобезопасен.
 */
public class EphemerisStore {
    private val sf1 = HashMap<Int, LnavDecoder.Subframe1>()
    private val sf2 = HashMap<Int, LnavDecoder.Subframe2>()
    private val sf3 = HashMap<Int, LnavDecoder.Subframe3>()
    private val ephemerides = HashMap<Int, GpsEphemeris>()
    private val external = HashMap<Int, List<GpsEphemeris>>()

    /** Добавляет подкадр; возвращает `true`, если в результате обновились эфемериды спутника. */
    public fun add(subframe: NavigationSubframe): Boolean {
        val bits = LnavDecoder.bits(subframe) ?: return false
        val sv = subframe.svid
        when (bits.u(50, 3).toInt()) {
            1 -> sf1[sv] = LnavDecoder.parse1(bits)
            2 -> sf2[sv] = LnavDecoder.parse2(bits)
            3 -> sf3[sv] = LnavDecoder.parse3(bits)
            else -> return false
        }
        val eph = LnavDecoder.combine(sv, sf1[sv] ?: return false, sf2[sv] ?: return false, sf3[sv] ?: return false)
            ?: return false
        if (ephemerides[sv] == eph) return false
        ephemerides[sv] = eph
        return true
    }

    /** Ручная загрузка эфемерид (тесты, внешние источники). */
    public fun put(ephemeris: GpsEphemeris) {
        ephemerides[ephemeris.svid] = ephemeris
    }

    /** Эфемериды из собственных навигационных сообщений чипа. */
    public operator fun get(svid: Int): GpsEphemeris? = ephemerides[svid]

    public val size: Int get() = ephemerides.size

    public val svids: Set<Int> get() = ephemerides.keys.toSet()

    /**
     * Заменяет внешний набор (например, broadcast-эфемериды RINEX, скачанные из сети).
     * На спутник хранится несколько наборов: нужный выбирается по времени в [select].
     */
    public fun replaceExternal(list: List<GpsEphemeris>) {
        external.clear()
        list.groupBy { it.svid }.forEach { (sv, items) -> external[sv] = items.distinctBy { it.weekMod1024 to it.toe } }
    }

    /** Число спутников, для которых есть внешние эфемериды. */
    public val externalSize: Int get() = external.size + externalNav.size

    /** Спутники, для которых есть хоть какие-то эфемериды. */
    public val availableSvids: Set<Int> get() = ephemerides.keys + external.keys

    /**
     * Эфемериды для момента (неделя GPS, секунды недели): собственные из навигационных
     * сообщений, если они действуют, иначе ближайший по toe внешний набор в окне аппроксимации.
     */
    public fun select(svid: Int, gpsWeek: Int, tow: Double): GpsEphemeris? {
        ephemerides[svid]?.takeIf { it.isValidAt(tow) }?.let { return it }
        return external[svid].orEmpty()
            .map { it to abs(it.ageSeconds(gpsWeek, tow)) }
            .filter { (_, age) -> age <= GpsEphemeris.FIT_INTERVAL_S }
            .minByOrNull { (_, age) -> age }
            ?.first
    }

    private val externalNav = HashMap<SatId, List<NavEphemeris>>()

    /**
     * Заменяет внешний набор всех систем (RINEX BRDM/BRDC). Эфемериды GPS идут в тот же
     * набор, что и [replaceExternal]; остальные хранятся по спутнику.
     */
    /** Коэффициенты Klobuchar из последнего навигационного файла. */
    public var ionosphere: KlobucharCoefficients? = null

    public fun replaceExternalNav(list: List<NavEphemeris>, ionosphere: KlobucharCoefficients? = this.ionosphere) {
        this.ionosphere = ionosphere
        replaceExternal(list.filterIsInstance<GpsEphemeris>())
        externalNav.clear()
        list.filter { it !is GpsEphemeris }.groupBy { it.sat }.forEach { (sat, items) ->
            externalNav[sat] = items.distinctBy { Triple(it.referenceWeek, it.referenceTow, (it as? KeplerEphemeris)?.dataSource) }
        }
    }

    /** Спутники всех систем, для которых есть эфемериды. */
    public val availableSats: Set<SatId>
        get() = availableSvids.map { SatId(GnssConstellation.GPS, it) }.toSet() + externalNav.keys

    /**
     * Эфемериды любой системы для момента (неделя GPS, секунды недели GPS): ближайшие по
     * опорному времени в окне применимости, здоровые раньше нездоровых, у Galileo I/NAV
     * раньше F/NAV (одночастотный E1). У ГЛОНАСС с неизвестным номером слота (Android
     * сообщает 93…106 = литера + 100) спутник ищется по литере, если она однозначна.
     */
    public fun select(sat: SatId, gpsWeek: Int, tow: Double): NavEphemeris? {
        if (sat.constellation == GnssConstellation.GPS) return select(sat.svid, gpsWeek, tow)
        val candidates = if (sat.constellation == GnssConstellation.GLONASS && sat.svid in GLONASS_FCN_SVIDS) {
            val k = sat.svid - GLONASS_FCN_SVID_OFFSET
            val byChannel = externalNav.filterKeys { it.constellation == GnssConstellation.GLONASS }.values
                .filter { list -> list.any { (it as GlonassEphemeris).frequencyChannel == k } }
            if (byChannel.size != 1) return null
            byChannel.single()
        } else {
            externalNav[sat].orEmpty()
        }
        return candidates
            .filter { it.isValidAt(gpsWeek, tow) }
            .minWithOrNull(
                compareBy<NavEphemeris> { if (it.isHealthy) 0 else 1 }
                    .thenBy { if ((it as? KeplerEphemeris)?.let { k -> k.dataSource != 0 && k.dataSource and 0x201 == 0 } == true) 1 else 0 }
                    .thenBy { abs(it.ageSeconds(gpsWeek, tow)) },
            )
    }

    private companion object {
        val GLONASS_FCN_SVIDS = 93..106
        const val GLONASS_FCN_SVID_OFFSET = 100
    }
}
