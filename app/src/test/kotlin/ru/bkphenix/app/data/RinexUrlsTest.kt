package ru.bkphenix.app.data

import org.junit.Assert.assertEquals
import org.junit.Test
import ru.bkphenix.app.data.source.RinexUrls
import java.time.OffsetDateTime
import java.time.ZoneOffset

class RinexUrlsTest {
    @Test
    fun `адреса BRDC на сегодня и вчера по UTC, через границу года`() {
        // 1 января 00:30 UTC (03:30 по Москве): сегодня — день 001, вчера — 365 прошлого года.
        val now = OffsetDateTime.of(2027, 1, 1, 0, 30, 0, 0, ZoneOffset.UTC).toInstant().toEpochMilli()
        assertEquals(
            listOf(
                "https://igs.bkg.bund.de/root_ftp/IGS/BRDC/2027/001/BRDM00DLR_S_20270010000_01D_MN.rnx.gz",
                "https://igs.bkg.bund.de/root_ftp/IGS/BRDC/2027/001/BRDC00WRD_S_20270010000_01D_MN.rnx.gz",
                "https://igs.bkg.bund.de/root_ftp/IGS/BRDC/2026/365/BRDM00DLR_S_20263650000_01D_MN.rnx.gz",
                "https://igs.bkg.bund.de/root_ftp/IGS/BRDC/2026/365/BRDC00WRD_S_20263650000_01D_MN.rnx.gz",
            ),
            RinexUrls.candidates(now),
        )
    }
}
