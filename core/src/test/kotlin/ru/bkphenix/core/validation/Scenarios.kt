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
import ru.bkphenix.core.model.TimeObservation
import ru.bkphenix.core.model.TimeSource
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
    COORD_JUMP("Согласованная подмена GNSS+сеть+Wi-Fi, скачок 800 м"),
    COORD_DRIFT("Согласованная подмена GNSS+сеть+Wi-Fi, увод 2 м/с"),
    COORD_KINEMATIC("Согласованная подмена с правдоподобной кинематикой"),
    TIME_STEP("Подмена времени: скачок +5 с"),
    TIME_DRIFT("Подмена времени: увод 50 мс/с"),
    TWO_BAD_SATELLITES("Два плохих спутника"),
    MULTIPATH("Многолучёвость у домов"),
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
    /** Метки времени GNSS и NITZ. */
    val time: List<TimeObservation> = emptyList(),
    /** Все спутники с ошибкой (два плохих, многолучёвость). */
    val faultySats: Set<SatId> = setOfNotNull(faultySat),
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
            var wifiAlso = false
            var gnssTimeShiftMs = 0L
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
                // Подмена сразу GNSS, сетевой позиции платформы и Wi-Fi (поддельные точки доступа);
                // честными остаются только собственные соты.
                ScenarioKind.COORD_JUMP -> if (t >= 60) {
                    gnssPoint = truth.offset(800.0, 90.0); attack = true; networkAlso = true; wifiAlso = true
                }
                ScenarioKind.COORD_DRIFT -> if (t >= 20) {
                    gnssPoint = truth.offset(2.0 * (t - 20), 90.0); attack = true; networkAlso = true; wifiAlso = true
                }
                // Правдоподобная кинематика: боковое ускорение 0,05 м/с² до скорости увода 3 м/с,
                // ни скачков, ни невозможных скоростей.
                ScenarioKind.COORD_KINEMATIC -> if (t >= 20) {
                    val s = (t - 20).toDouble()
                    val d = if (s <= 60) 0.025 * s * s else 90.0 + 3.0 * (s - 60)
                    gnssPoint = truth.offset(d, 90.0); attack = true; networkAlso = true; wifiAlso = true
                }
                ScenarioKind.TIME_STEP -> if (t >= 60) { gnssTimeShiftMs = 5_000; attack = true }
                ScenarioKind.TIME_DRIFT -> if (t >= 20) { gnssTimeShiftMs = 50L * (t - 20); attack = true }
                ScenarioKind.TWO_BAD_SATELLITES -> {
                    val visible = base.visibleAll()
                    val a = visible[t % visible.size].sat
                    val b = visible[(t + visible.size / 2) % visible.size].sat
                    faults = mapOf(a to badSatelliteBiasM, b to -badSatelliteBiasM * 0.8)
                    attack = true
                }
                // Многолучёвость: три самых низких спутника получают положительную задержку 10–60 м.
                ScenarioKind.MULTIPATH -> {
                    val low = base.visibleAll().sortedBy { base.elevationDeg(it) }.take(3)
                    faults = low.associate { it.sat to 10.0 + 50.0 * rnd.nextDouble() }
                }
                else -> Unit
            }
            val raw = SyntheticEpoch(truth = gnssPoint, noiseM = noiseM, seed = seed + t, multiGnss = multiGnss).epoch(faultsBySat = faults)
            val obs = buildList {
                add(PositionObservation(PositionSource.GNSS, noisy(gnssPoint, 3.0), 5.0, t * 1000L))
                when {
                    kind == ScenarioKind.COORDINATED ->
                        add(PositionObservation(PositionSource.NETWORK, noisy(gnssPoint, 15.0), 30.0, t * 1000L))
                    kind.coordinatedWithCell -> {
                        val netPoint = if (networkAlso) gnssPoint else truth
                        val wifiPoint = if (wifiAlso) gnssPoint else truth
                        add(PositionObservation(PositionSource.NETWORK, noisy(netPoint, 15.0), 30.0, t * 1000L))
                        add(PositionObservation(PositionSource.WIFI_RSSI, noisy(wifiPoint, 15.0), 25.0, t * 1000L))
                        add(PositionObservation(PositionSource.CELL, noisy(truth, 60.0), 100.0, t * 1000L))
                    }
                    else -> add(PositionObservation(PositionSource.CELL, noisy(truth, 60.0), 100.0, t * 1000L))
                }
            }
            val wall = T0 + t * 1000L
            val time = listOf(
                TimeObservation(TimeSource.GNSS, wall + gnssTimeShiftMs, 10),
                TimeObservation(TimeSource.NITZ, wall + (rnd.nextGaussian() * 200).toLong(), 500),
            )
            ScenarioEpoch(t, truth, obs, raw, attack, faultySat, time, faults.keys)
        }
    }
}

private val ScenarioKind.coordinatedWithCell: Boolean
    get() = this == ScenarioKind.COORD_JUMP || this == ScenarioKind.COORD_DRIFT || this == ScenarioKind.COORD_KINEMATIC

private const val T0 = 1_790_000_000_000L

/** Прогон сценария через ядро. */
internal fun run(core: PhenixCore, epochs: List<ScenarioEpoch>): List<Pair<ScenarioEpoch, PhenixSolution>> =
    epochs.map { e -> e to core.solve(e.observations, e.t * 1000L, timeObservations = e.time, rawGnss = e.raw) }
