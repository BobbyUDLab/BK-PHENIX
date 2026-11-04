package ru.bkphenix.app.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import ru.bkphenix.app.ui.satellites.RaimPresentation
import ru.bkphenix.app.ui.satellites.Severity
import ru.bkphenix.core.gnss.DetectorKind
import ru.bkphenix.core.gnss.DetectorResult
import ru.bkphenix.core.gnss.DetectorVerdict
import ru.bkphenix.core.gnss.ExcludedSatellite
import ru.bkphenix.core.gnss.GnssIntegrityResult
import ru.bkphenix.core.gnss.RaimResult
import ru.bkphenix.core.gnss.RaimStatus
import ru.bkphenix.core.gnss.SatId
import ru.bkphenix.core.integrity.IntegrityReport
import ru.bkphenix.core.model.GnssConstellation

class RaimPresentationTest {
    @Test
    fun `исключённые спутники и тревоги детекторов отображаются`() {
        val result = GnssIntegrityResult(
            raim = RaimResult(
                RaimStatus.EXCLUDED, null,
                listOf(ExcludedSatellite(SatId(GnssConstellation.GPS, 7), 312.4)),
                5.2, 18.4, 21.0,
            ),
            detectors = listOf(
                DetectorResult(DetectorKind.CN0_UNIFORMITY, DetectorVerdict.ALERT, "СКО 0.3"),
                DetectorResult(DetectorKind.AGC, DetectorVerdict.NOT_APPLICABLE, "нет АРУ"),
            ),
            ephemerisCount = 9,
            ephemerisBySystem = mapOf(GnssConstellation.GALILEO to 4, GnssConstellation.GPS to 5),
            pseudorangeCount = 11,
            ownSolution = null,
            report = IntegrityReport.OK,
        )
        val ui = RaimPresentation.from(result)!!
        assertEquals(Severity.WARNING, ui.severity)
        assertEquals("G07", ui.excluded.single().label)
        assertEquals("невязка 312.4 м", ui.excluded.single().residual)
        assertEquals("21.0 м", ui.protectionLevel)
        assertEquals("5.2 из 18.4", ui.statistic)
        assertEquals("9 сп. (G 5 · E 4) · псевдодальностей 11", ui.ephemerides)
        assertEquals(listOf("Тревога", "Нет данных"), ui.detectors.map { it.verdict })
        assertEquals(Severity.ALERT, ui.detectors.first().severity)
    }

    @Test
    fun `без сырых данных карточка пустая`() {
        assertNull(RaimPresentation.from(null))
    }
}
