package ru.bkphenix.app.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import ru.bkphenix.app.data.log.GnssLoggerFormat
import ru.bkphenix.app.data.log.Json
import ru.bkphenix.app.data.log.RawLogRecorder
import ru.bkphenix.app.data.log.RawLogRow

class RawLogTest {
    @get:Rule
    val tmp = TemporaryFolder()

    private val row = RawLogRow(
        utcTimeMillis = 1_700_000_000_000, timeNanos = 123, leapSecond = 18, timeUncertaintyNanos = null,
        fullBiasNanos = -1_300_000_000_000_000_000, biasNanos = 0.5, biasUncertaintyNanos = 10.0,
        driftNanosPerSecond = 1.5, driftUncertaintyNanosPerSecond = null, hardwareClockDiscontinuityCount = 2,
        svid = 5, timeOffsetNanos = 0.0, state = 16431, receivedSvTimeNanos = 345_000_000_000,
        receivedSvTimeUncertaintyNanos = 12, cn0DbHz = 38.5, pseudorangeRateMps = -120.25,
        pseudorangeRateUncertaintyMps = 0.1, accumulatedDeltaRangeState = 0, accumulatedDeltaRangeMeters = 0.0,
        accumulatedDeltaRangeUncertaintyMeters = 0.0, carrierFrequencyHz = 1.57542e9, multipathIndicator = 0,
        snrInDb = null, constellationType = 1, agcDb = Double.NaN,
    )

    @Test
    fun `строка Raw соответствует заголовку GnssLogger по числу полей`() {
        val header = GnssLoggerFormat.header("0.3", "HUAWEI", "ELE-L29", "10")
        val fields = header.lines().first { it.startsWith("# Raw,") }.removePrefix("# ").split(",")
        val values = GnssLoggerFormat.raw(row).split(",")
        assertEquals(fields.size, values.size)
        assertEquals("Raw", values[0])
        assertEquals("5", values[fields.indexOf("Svid")])
        assertEquals("", values[fields.indexOf("TimeUncertaintyNanos")])
        assertEquals("", values[fields.indexOf("AgcDb")])
        assertEquals("1", values[fields.indexOf("ConstellationType")])
    }

    @Test
    fun `строка Nav содержит байты сообщения`() {
        val line = GnssLoggerFormat.nav(7, 257, 1, 3, 1, byteArrayOf(1, -2, 3))
        assertEquals("Nav,7,257,1,3,1,1,-2,3", line)
    }

    @Test
    fun `JSON экранирует строки и заменяет нечисла на null`() {
        val json = Json.encode(linkedMapOf("a" to "x\"y\n", "b" to Double.NaN, "c" to listOf(1, true, null)))
        assertEquals("""{"a":"x\"y\n","b":null,"c":[1,true,null]}""", json)
    }

    @Test
    fun `рекордер пишет оба файла и не пишет после остановки`() {
        val dir = tmp.newFolder("logs")
        val recorder = RawLogRecorder(dir) { "# header\n" }
        recorder.text("ignored")
        recorder.start()
        assertTrue(recorder.isRecording)
        recorder.text(GnssLoggerFormat.raw(row))
        recorder.json("epoch", 1, mapOf("k" to 2))
        recorder.stop()
        recorder.text("after stop")
        assertFalse(recorder.isRecording)
        val txt = dir.listFiles()!!.first { it.name.endsWith(".txt") }.readLines()
        assertEquals(listOf("# header", GnssLoggerFormat.raw(row)), txt)
        val jsonl = dir.listFiles()!!.first { it.name.endsWith(".jsonl") }.readText().trim()
        assertEquals("""{"type":"epoch","t":1,"k":2}""", jsonl)
        assertEquals(2, recorder.state.value.files.size)
        recorder.deleteFinished()
        assertEquals(0, dir.listFiles()!!.size)
    }
}
