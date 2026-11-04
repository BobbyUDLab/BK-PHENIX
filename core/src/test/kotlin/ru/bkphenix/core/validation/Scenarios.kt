package ru.bkphenix.core.validation

import ru.bkphenix.core.PhenixCore
import ru.bkphenix.core.PhenixSolution
import ru.bkphenix.core.geo.GeoPoint
import ru.bkphenix.core.gnss.GnssIntegrityProcessor
import ru.bkphenix.core.gnss.RawGnssEpoch
import ru.bkphenix.core.gnss.SatId
import ru.bkphenix.core.gnss.SyntheticEpoch
import ru.bkphenix.core.model.PositionObservation
import ru.bkphenix.core.model.PositionSource
import java.util.Random

/** Тип сценария синтетического генератора. */
internal enum class ScenarioKind(val title: String) {
    CLEAN("Чистый приём"),
    NOISE("Шум 3 м"),
    BAD_SATELLITE("Один плохой спутник"),
    SPOOF_OFFSET("Спуфинг со смещением 600 м"),
    SLOW_DRIFT("Плавный увод 2 м/с"),
    JUMP("Скачок 500 м"),
    COORDINATED("Согласованная подмена GNSS и сети"),
}

/** Одна эпоха сценария: что подали в ядро и что было на самом деле. */
internal data class ScenarioEpoch(
    val t: Int,
    val truth: GeoPoint,
    val observations: List<PositionObservation>,
    val raw: RawGnssEpoch,
    /** Атака активна в этой эпохе (ожидается тревога). */
    val attack: Boolean,
    /** Спутник с ошибкой, если есть. */
    val faultySat: SatId? = null,
)

/**
 * Генератор сценариев поверх [SyntheticEpoch]: сырые измерения GPS L1, решение чипа и независимая
 * сота (σ 100 м) для кворума. С [multiGnss] — два созвездия (GPS + Galileo) с межсистемным смещением.
 * Детерминирован по [seed].
 */
internal class ScenarioGenerator(
    private val seed: Long = 42,
    private val badSatelliteBiasM: Double = 150.0,
    private val multiGnss: Boolean = false,
) {
    private val origin = GeoPoint(55.75393, 37.620795, 150.0)
    private val base = SyntheticEpoch(truth = origin, multiGnss = multiGnss)

    /** Процессор с эфемеридами синтетического созвездия. */
    fun core(): PhenixCore = PhenixCore(gnssProcessor = GnssIntegrityProcessor(ephemerides = base.store))

    fun generate(kind: ScenarioKind, epochs: Int = 200): List<ScenarioEpoch> {
        val rnd = Random(seed * 31 + kind.ordinal)
        fun noisy(p: GeoPoint, sigma: Double) = p.offset(kotlin.math.abs(rnd.nextGaussian()) * sigma, rnd.nextDouble() * 360)
        val noiseM = if (kind == ScenarioKind.CLEAN) 0.5 else 3.0
        return (0 until epochs).map { t ->
            // Медленное движение пешком на север, чтобы фильтр работал на живой траектории.
            val truth = origin.offset(1.2 * t, 0.0)
            var gnssPoint = truth
            var attack = false
            var faults = emptyMap<SatId, Double>()
            var faultySat: SatId? = null
            var networkAlso = false
            when (kind) {
                ScenarioKind.BAD_SATELLITE -> {
                    val visible = base.visibleAll()
                    val sat = visible[t % visible.size].sat
                    faults = mapOf(sat to badSatelliteBiasM)
                    faultySat = sat
                    attack = true
                }
                ScenarioKind.SPOOF_OFFSET -> if (t >= 20) { gnssPoint = truth.offset(600.0, 90.0); attack = true }
                ScenarioKind.SLOW_DRIFT -> if (t >= 20) { gnssPoint = truth.offset(2.0 * (t - 20), 90.0); attack = true }
                ScenarioKind.JUMP -> if (t >= 60) { gnssPoint = truth.offset(500.0, 90.0); attack = true }
                ScenarioKind.COORDINATED -> { gnssPoint = truth.offset(800.0, 90.0); attack = true; networkAlso = true }
                else -> Unit
            }
            val raw = SyntheticEpoch(truth = gnssPoint, noiseM = noiseM, seed = seed + t, multiGnss = multiGnss).epoch(faultsBySat = faults)
            val obs = buildList {
                add(PositionObservation(PositionSource.GNSS, noisy(gnssPoint, 3.0), 5.0, t * 1000L))
                if (networkAlso) {
                    add(PositionObservation(PositionSource.NETWORK, noisy(gnssPoint, 15.0), 30.0, t * 1000L))
                } else {
                    add(PositionObservation(PositionSource.CELL, noisy(truth, 60.0), 100.0, t * 1000L))
                }
            }
            ScenarioEpoch(t, truth, obs, raw, attack, faultySat)
        }
    }
}

/** Прогон сценария через ядро. */
internal fun run(core: PhenixCore, epochs: List<ScenarioEpoch>): List<Pair<ScenarioEpoch, PhenixSolution>> =
    epochs.map { e -> e to core.solve(e.observations, e.t * 1000L, rawGnss = e.raw) }
