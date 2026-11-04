package ru.bkphenix.core.gnss

import ru.bkphenix.core.model.GnssConstellation

/** Синтетическое созвездие GPS: 6 плоскостей по 6 спутников, наклонение 55°, круговые орбиты. */
internal object SyntheticConstellation {
    const val SQRT_A = 5153.7

    fun ephemeris(svid: Int, omega0: Double, m0: Double, toe: Double, inclination: Double = Math.toRadians(55.0)) =
        GpsEphemeris(
            svid = svid, weekMod1024 = 392, health = 0, uraIndex = 0, iodc = 1, iode = 1, tgd = 0.0, toc = toe,
            af0 = 0.0, af1 = 0.0, af2 = 0.0, toe = toe, sqrtA = SQRT_A, eccentricity = 0.0, i0 = inclination,
            omega0 = omega0, omega = 0.0, m0 = m0, deltaN = 0.0, omegaDot = 0.0, iDot = 0.0,
            cuc = 0.0, cus = 0.0, crc = 0.0, crs = 0.0, cic = 0.0, cis = 0.0,
        )

    fun all(toe: Double): List<GpsEphemeris> = (0 until 36).map { k ->
        val plane = k / 6
        val slot = k % 6
        ephemeris(
            svid = k + 1,
            omega0 = Math.toRadians(plane * 60.0),
            m0 = Math.toRadians(slot * 60.0 + plane * 25.0),
            toe = toe,
        ).copy(af0 = (k - 18) * 1e-5, af1 = 1e-12 * k)
    }

    /** Второе созвездие с метками Galileo: те же орбиты GPS, сдвинутые по долготе узла и аномалии. */
    fun galileo(toe: Double): List<NavEphemeris> = all(toe).map { e ->
        AsSystem(e.copy(omega0 = e.omega0 + Math.toRadians(30.0), m0 = e.m0 + Math.toRadians(30.0)), SatId(GnssConstellation.GALILEO, e.svid))
    }

    private class AsSystem(private val e: GpsEphemeris, override val sat: SatId) : NavEphemeris by e
}
