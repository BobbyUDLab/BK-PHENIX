package ru.bkphenix.core.gnss

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import ru.bkphenix.core.integrity.IntegrityState
import ru.bkphenix.core.model.GnssBand
import ru.bkphenix.core.model.PositionObservation
import ru.bkphenix.core.model.PositionSource
import ru.bkphenix.core.model.TimeObservation
import ru.bkphenix.core.model.TimeSource

class SpoofingMonitorTest {
    private val solver = WlsSolver()
    private val s = SyntheticEpoch(noiseM = 1.0, seed = 11)

    private fun input(
        epoch: RawGnssEpoch = s.epoch(),
        chip: PositionObservation? = null,
        network: PositionObservation? = null,
        time: List<TimeObservation> = emptyList(),
    ): SpoofingInput {
        val pr = Pseudoranges.compute(epoch)
        return SpoofingInput(epoch, pr, solver.solve(solver.prepare(pr, s.store)), chip, network, time)
    }

    private fun obs(src: PositionSource, distM: Double, acc: Double) =
        PositionObservation(src, s.truth.offset(distM, 45.0), acc, 0)

    @Test
    fun `одинаковый C_N0 у всех спутников — тревога`() {
        val m = SpoofingMonitor()
        assertEquals(DetectorVerdict.ALERT, m.cn0Uniformity(input(s.epoch(cn0 = { 45.0 }))).verdict)
        assertEquals(DetectorVerdict.PASS, m.cn0Uniformity(input()).verdict)
    }

    @Test
    fun `L1 и L5 — ионосферная задержка проходит, нулевая или хаотичная — нет`() {
        val m = SpoofingMonitor()
        val l1 = s.epoch()
        fun withL5(delay: (Int) -> Double): RawGnssEpoch {
            val l5 = s.epoch(band = GnssBand.L5, extraDelayM = delay)
            return l1.copy(measurements = l1.measurements + l5.measurements)
        }
        assertEquals(DetectorVerdict.PASS, m.dualFrequency(input(withL5 { 8.0 + it % 5 })).verdict)
        assertEquals(DetectorVerdict.ALERT, m.dualFrequency(input(withL5 { if (it % 2 == 0) -80.0 else 120.0 })).verdict)
        assertEquals(DetectorVerdict.NOT_APPLICABLE, m.dualFrequency(input(l1)).verdict)
        // Один спутник с неоднозначностью кода L5 (~20 км, как в журнале 2026-11-04) — не подмена.
        var first: Int? = null
        val oneOutlier = withL5 { sv -> if ((first ?: sv.also { first = it }) == sv) 20_833.0 else 8.0 + sv % 5 }
        assertEquals(DetectorVerdict.PASS, m.dualFrequency(input(oneOutlier)).verdict)
    }

    @Test
    fun `скачок часов приёмника на 5 мкс — тревога, плавный дрейф — нет`() {
        val m = SpoofingMonitor()
        val base = s.epoch()
        // Подмена времени: шкала приёмника и времена излучения сдвинуты согласованно — дальности не меняются,
        // смещение часов МНК не поглощает скачок.
        fun at(sec: Int, extraNs: Long, spoofed: Boolean = true) = base.copy(
            clock = base.clock.copy(
                timeNanos = base.clock.timeNanos + sec * 1_000_000_000L,
                fullBiasNanos = base.clock.fullBiasNanos!! + sec * 100L + extraNs,
                driftNanosPerSecond = 100.0,
            ),
            measurements = base.measurements.map {
                it.copy(receivedSvTimeNanos = it.receivedSvTimeNanos + sec * 1_000_000_000L + sec * 100L - if (spoofed) extraNs else 0L)
            },
        )
        assertEquals(DetectorVerdict.NOT_APPLICABLE, m.clockJump(input(at(0, 0))).verdict)
        assertEquals(DetectorVerdict.PASS, m.clockJump(input(at(1, 0))).verdict)
        assertEquals(DetectorVerdict.ALERT, m.clockJump(input(at(2, 5_000))).verdict)
        // Переоценка FullBias чипом (Kirin): дальности сдвинулись одинаково, МНК поглотил — не подмена.
        val chip = SpoofingMonitor()
        chip.clockJump(input(at(0, 0)))
        chip.clockJump(input(at(1, 0)))
        assertEquals(DetectorVerdict.PASS, chip.clockJump(input(at(2, 5_000, spoofed = false))).verdict)
        val reset = at(3, 5_000).let { it.copy(clock = it.clock.copy(hardwareClockDiscontinuityCount = 1)) }
        assertEquals(DetectorVerdict.NOT_APPLICABLE, m.clockJump(input(reset)).verdict)
    }

    @Test
    fun `сетевая позиция далеко от решения ГНСС — тревога`() {
        val m = SpoofingMonitor()
        assertEquals(DetectorVerdict.PASS, m.network(input(network = obs(PositionSource.NETWORK, 300.0, 500.0))).verdict)
        assertEquals(DetectorVerdict.ALERT, m.network(input(network = obs(PositionSource.NETWORK, 20_000.0, 500.0))).verdict)
    }

    @Test
    fun `время ГНСС расходится с NITZ — тревога`() {
        val m = SpoofingMonitor()
        val ok = listOf(TimeObservation(TimeSource.GNSS, 1_000_000, 100), TimeObservation(TimeSource.NITZ, 1_001_000, 500))
        val bad = listOf(TimeObservation(TimeSource.GNSS, 1_000_000, 100), TimeObservation(TimeSource.NITZ, 1_600_000, 500))
        assertEquals(DetectorVerdict.PASS, m.nitz(input(time = ok)).verdict)
        assertEquals(DetectorVerdict.ALERT, m.nitz(input(time = bad)).verdict)
    }

    @Test
    fun `АРУ — падение усиления без потери C_N0 — подмена, с потерей — глушение`() {
        val spoof = SpoofingMonitor()
        repeat(10) { spoof.agc(input(s.epoch(agcDb = 20.0))) }
        assertEquals(DetectorVerdict.PASS, spoof.agc(input(s.epoch(agcDb = 19.0))).verdict)
        val r = spoof.agc(input(s.epoch(agcDb = 5.0)))
        assertEquals(DetectorVerdict.ALERT, r.verdict)
        assertEquals(IntegrityState.SPOOFING_SUSPECTED, r.alertState)

        val jam = SpoofingMonitor()
        repeat(10) { jam.agc(input(s.epoch(agcDb = 20.0))) }
        val j = jam.agc(input(s.epoch(agcDb = 5.0, cn0 = { 15.0 + it % 5 })))
        assertEquals(IntegrityState.GNSS_LOST, j.alertState)
    }

    @Test
    fun `решение чипа против собственного`() {
        val m = SpoofingMonitor()
        assertEquals(DetectorVerdict.PASS, m.chipVsOwn(input(chip = obs(PositionSource.GNSS, 5.0, 4.0))).verdict)
        val far = m.chipVsOwn(input(chip = obs(PositionSource.GNSS, 800.0, 4.0)))
        assertEquals(DetectorVerdict.ALERT, far.verdict)
        assertEquals(IntegrityState.INCONSISTENT, far.alertState)
    }

    @Test
    fun `отчёт берёт самое серьёзное состояние`() {
        val report = SpoofingMonitor.report(
            listOf(
                DetectorResult(DetectorKind.CHIP_VS_OWN, DetectorVerdict.ALERT, "x", IntegrityState.INCONSISTENT),
                DetectorResult(DetectorKind.NITZ_TIME, DetectorVerdict.ALERT, "y"),
                DetectorResult(DetectorKind.AGC, DetectorVerdict.PASS, "z"),
            ),
        )
        assertEquals(IntegrityState.SPOOFING_SUSPECTED, report.state)
        assertEquals(2, report.reasons.size)
    }

    @Test
    fun `время UTC по часам приёмника`() {
        val clock = RawGnssClock(timeNanos = 0, fullBiasNanos = -1_000_000_000_000L * 1_000, biasNanos = 0.0)
        assertEquals(Pseudoranges.GPS_EPOCH_UNIX_MILLIS + 1_000_000_000L - 18_000, Pseudoranges.utcMillis(clock))
    }
}
