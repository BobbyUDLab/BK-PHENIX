package ru.bkphenix.core.integrity

import ru.bkphenix.core.geo.GeoPoint
import ru.bkphenix.core.model.PositionObservation
import ru.bkphenix.core.model.PositionSource
import ru.bkphenix.core.model.SourceGroup
import kotlin.math.atan2
import kotlin.math.hypot
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin
import kotlin.math.sqrt

/** Итог проверки непрерывности: что пускать в компаратор, что отсечь и почему. */
public data class ContinuityResult(
    val kept: List<PositionObservation>,
    val dropped: List<PositionObservation>,
    val report: IntegrityReport,
    /** Нормированная инновация GNSS против фильтра (χ², 2 степени свободы), если считалась. */
    val innovationChi2: Double? = null,
    /** Накопитель CUSUM расхождения GNSS с независимыми источниками. */
    val driftCusum: Double = 0.0,
)

/** Настройки контроля непрерывности. Значения по умолчанию рассчитаны на машину до 150 км/ч. */
public data class ContinuityConfig(
    /** Предельная скорость, м/с (≈ 250 км/ч с запасом). */
    val maxSpeedMps: Double = 70.0,
    /** Спектральная плотность ускорения в фильтре, м²/с³. */
    val accelPsd: Double = 4.0,
    /** Порог χ² инновации (2 ст. св.; 25 ≈ вероятность ложной тревоги 4·10⁻⁶). */
    val innovationGate: Double = 25.0,
    /** CUSUM: допуск k и порог h по нормированному расхождению. */
    val cusumK: Double = 1.5,
    val cusumH: Double = 15.0,
    /** Нижняя граница σ независимой оценки в CUSUM, м: заявленные точности сети оптимистичны. */
    val cusumMinIndependentSigmaM: Double = 20.0,
    /** Сколько держать тревогу после скачка, мс. */
    val holdMillis: Long = 60_000,
    /** Перерыв GNSS, после которого фильтр стартует заново (тоннель), мс. */
    val resetGapMillis: Long = 30_000,
    /** Минимальный шаг времени для оценки скорости, мс: при меньшем dt скорость не считается. */
    val minSpeedDtMillis: Long = 500,
    /** Подряд отсеянных, но согласованных между собой эпох, после которых фильтр перезапускается. */
    val recoveryEpochs: Int = 5,
    /** Допуск согласия GNSS с независимыми источниками для перезапуска, м (сверх их σ). */
    val recoveryAgreementM: Double = 200.0,
)

/**
 * Контроль непрерывности решения GNSS во времени.
 *
 * - **Кинематика и инновации:** фильтр Калмана «постоянная скорость» по осям восток/север
 *   предсказывает позицию; наблюдение GNSS с χ² инновации выше порога или с невозможной
 *   скоростью отсекается (вместе со всей группой GNSS-сигнала) и не обновляет фильтр.
 * - **Плавный увод:** CUSUM по нормированному расхождению GNSS с независимыми от GNSS
 *   источниками ловит смещение, которое в каждой эпохе укладывается в ворота компаратора.
 *
 * Объект хранит состояние между эпохами; один экземпляр на поток наблюдений.
 */
public class ContinuityMonitor(private val config: ContinuityConfig = ContinuityConfig()) {
    private var origin: GeoPoint? = null
    private val x = DoubleArray(4) // e, n, ve, vn
    private val p = Array(2) { DoubleArray(3) } // по оси: Ppp, Ppv, Pvv
    private var lastMillis = 0L
    private var cusum = 0.0
    private val cusums = mutableMapOf<SourceGroup, Double>()
    private var alarmUntil = Long.MIN_VALUE
    private var alarmReason: String? = null
    private var rejectStreak = 0
    private var lastRejected: PositionObservation? = null
    private var lastRejectedMillis = 0L
    private var unconfirmed = false

    /** GNSS восстановлена после отсева, но независимый источник её ещё не подтвердил. */
    public val unconfirmedRecovery: Boolean get() = unconfirmed

    public fun reset() {
        origin = null
        cusum = 0.0
        cusums.clear()
        alarmUntil = Long.MIN_VALUE
        alarmReason = null
        unconfirmed = false
        clearStreak()
    }

    private fun clearStreak() {
        rejectStreak = 0
        lastRejected = null
    }

    public fun check(observations: List<PositionObservation>, nowMillis: Long): ContinuityResult {
        val gnssGroup = observations.filter { SourceGroup.GNSS_SIGNAL in it.origin }
        // Свидетели против GNSS — только источники, не зависящие от неё ни прямо, ни косвенно:
        // сеть/Fused платформы и счисление от фикса GNSS при подмене уводятся вместе с ней.
        val independent = observations.filter { it.isGnssIndependent }
        val reasons = mutableListOf<String>()
        val gnss = gnssGroup.minByOrNull { it.accuracyM }
        var dropped = emptyList<PositionObservation>()
        var chi2: Double? = null

        if (gnss != null) {
            val o = origin
            val gapReset = o != null && nowMillis - lastMillis > config.resetGapMillis
            val blockedReset = if (gapReset) implausibleAfterGap(gnss, observations, nowMillis) else null
            if (blockedReset != null) {
                // Поездка 04.11.2026, 13:10:40 и 13:12:11 МСК: подмена у Шереметьево длилась > 30 с, все GNSS
                // отсеивались, lastMillis «старел», и перезапуск фильтра принимал поддельную точку в 20 км.
                dropped = gnssGroup
                raise(nowMillis, blockedReset)
                if (recover(gnss, independent, nowMillis) && !contradicted(gnss, observations)) {
                    init(gnss, nowMillis)
                    dropped = emptyList()
                    clearStreak()
                    unconfirmed = independent.isEmpty()
                }
            } else if (o == null && contradicted(gnss, observations)) {
                // Холодный старт (поездки 04–05.11.2026, Mate 70 и DOOGEE): первый фикс после включения
                // геолокации бывает поддельным или кэшированным в 13–20 км (Шереметьево). Фильтр не стартует
                // на точке, которую опровергают свежие другие источники, иначе она становится «прошлым»,
                // от которого отсекается всё честное.
                dropped = gnssGroup
                val d = observations.filter { isOtherWitness(it, gnss) }.minOf { gnss.point.distanceTo(it.point) }
                raise(nowMillis, "GNSS при старте в ${d.toInt()} м от других источников")
                rejectStreak = 0
            } else if (o == null || gapReset) {
                init(gnss, nowMillis)
            } else {
                val dt = (nowMillis - lastMillis) / 1000.0
                val (e, n) = enu(o, gnss.point)
                if (dt > 0) predict(dt)
                val r = gnss.accuracyM * gnss.accuracyM
                val de = e - x[0]
                val dn = n - x[1]
                val se = p[0][0] + r
                val sn = p[1][0] + r
                chi2 = de * de / se + dn * dn / sn
                val jumpM = sqrt(de * de + dn * dn)
                // При слишком малом dt (два решения в одной эпохе, дубли) скорость не оценивается:
                // 40 м расхождения за 1 мс давали «41 534 м/с» и отсекали всю группу GNSS.
                val speedDt = dt >= config.minSpeedDtMillis / 1000.0
                val speed = if (speedDt) jumpM / dt else 0.0
                val speedLimit = config.maxSpeedMps + 3 * sqrt(2 * r) / max(dt, 1.0)
                when {
                    chi2 > config.innovationGate -> {
                        dropped = gnssGroup
                        raise(nowMillis, "Скачок GNSS: ${jumpM.toInt()} м от прогноза (χ² ${chi2.toInt()})")
                    }
                    speedDt && speed > speedLimit + sqrt(p[0][2] + p[1][2]) -> {
                        dropped = gnssGroup
                        raise(nowMillis, "Невозможная скорость GNSS: ${speed.toInt()} м/с")
                    }
                    // После отсева ковариация прогноза разрастается, и подмена «пролезла» бы через
                    // χ²; пока GNSS не сходится с независимыми источниками, отсев продолжается.
                    rejectStreak > 0 && !agrees(gnss, independent) -> {
                        dropped = gnssGroup
                        raise(nowMillis, alarmReason ?: "GNSS расходится с независимыми источниками")
                    }
                    else -> update(de, dn, r)
                }
                if (dropped.isEmpty()) {
                    clearStreak()
                    lastMillis = nowMillis
                } else if (recover(gnss, independent, nowMillis) && !contradicted(gnss, observations)) {
                    // Фильтр застрял на старом состоянии: GNSS N эпох подряд движется правдоподобно
                    // и согласуется с независимыми источниками — перезапуск, тревога держится holdMillis.
                    init(gnss, nowMillis)
                    dropped = emptyList()
                    clearStreak()
                    // Без независимых свидетелей восстановление держится только на самой GNSS.
                    unconfirmed = independent.isEmpty()
                }
            }
            // CUSUM — только по абсолютным источникам, не связанным с GNSS: счисление дрейфует и
            // стартует от той же позиции, а «Сеть» платформы может подмешивать GNSS. На полевом
            // тесте 04.11 они давали ложный «плавный увод» почти всю прогулку.
            val absolute = independent.filter { SourceGroup.INERTIAL !in it.origin }
            if (dropped.isEmpty() && absolute.isNotEmpty()) updateCusum(gnss, absolute)
            if (unconfirmed && dropped.isEmpty() && independent.isNotEmpty() && agrees(gnss, independent)) unconfirmed = false
        }
        if (nowMillis <= alarmUntil) alarmReason?.let { reasons += it }
        val report = when {
            reasons.isNotEmpty() -> IntegrityReport(IntegrityState.SPOOFING_SUSPECTED, reasons)
            unconfirmed -> IntegrityReport(
                IntegrityState.UNCONFIRMED,
                listOf("ГНСС восстановлена после отсева без независимого подтверждения (нет сот, Wi-Fi, якоря)"),
            )
            else -> IntegrityReport.OK
        }
        return ContinuityResult(observations - dropped.toSet(), dropped, report, chi2, cusum)
    }

    private fun recover(gnss: PositionObservation, independent: List<PositionObservation>, nowMillis: Long): Boolean {
        val prev = lastRejected
        val consistent = prev != null && run {
            val dt = (nowMillis - lastRejectedMillis) / 1000.0
            dt > 0 && dt * 1000 <= config.resetGapMillis &&
                gnss.point.distanceTo(prev.point) <= config.maxSpeedMps * max(dt, 1.0) + 3 * (gnss.accuracyM + prev.accuracyM)
        }
        rejectStreak = if (consistent || prev == null) rejectStreak + 1 else 1
        lastRejected = gnss
        lastRejectedMillis = nowMillis
        if (rejectStreak < config.recoveryEpochs) return false
        return agrees(gnss, independent)
    }

    /** Согласие GNSS хотя бы с одним независимым источником (без них проверять не с чем — да). */
    /**
     * Перезапуск после перерыва допустим, только если новая точка GNSS достижима от последней принятой
     * (макс. скорость × перерыв + 3σ) и не противоречит ни одному другому источнику (сеть, соты, Wi-Fi, счисление,
     * якорь). Сеть здесь годится как свидетель против: она может опираться на GNSS, но не на поддельную.
     */
    private fun implausibleAfterGap(gnss: PositionObservation, observations: List<PositionObservation>, nowMillis: Long): String? {
        val o = origin ?: return null
        val gapS = (nowMillis - lastMillis) / 1000.0
        val last = o.offset(hypot(x[0], x[1]), Math.toDegrees(atan2(x[0], x[1])))
        val reach = config.maxSpeedMps * gapS + 3 * (gnss.accuracyM + sqrt(p[0][0]))
        val jump = gnss.point.distanceTo(last)
        if (jump > reach) return "Невозможный скачок GNSS после перерыва ${gapS.toInt()} с: ${jump.toInt()} м (достижимо ${reach.toInt()} м)"
        if (contradicted(gnss, observations)) {
            val d = observations.filter { isOtherWitness(it, gnss) }.minOf { gnss.point.distanceTo(it.point) }
            return "GNSS после перерыва в ${d.toInt()} м от других источников"
        }
        return null
    }

    /**
     * Другие источники опровергают GNSS. Независимый от GNSS свидетель (соты, Wi-Fi, якорь, счисление
     * от подтверждённой привязки) опровергает в одиночку. Зависимые (сеть платформы, счисление от
     * неподтверждённого фикса) — только если ни один из них GNSS не поддерживает: DOOGEE 05.11.2026,
     * счисление от кэшированной точки у Шереметьево 70 с отсекало GPS и сеть, которые совпадали.
     */
    private fun contradicted(gnss: PositionObservation, observations: List<PositionObservation>): Boolean {
        val witnesses = observations.filter { isOtherWitness(it, gnss) }
        if (witnesses.any { it.isGnssIndependent && !closeTo(gnss, it) }) return true
        val dependent = witnesses.filterNot { it.isGnssIndependent }
        return dependent.isNotEmpty() && dependent.none { closeTo(gnss, it) }
    }

    /** Свидетель — не-GNSS источник или сеть не старше 2 мин; допуск растёт на макс. скорость × возраст свидетеля. */
    private fun isOtherWitness(o: PositionObservation, gnss: PositionObservation) =
        (SourceGroup.GNSS_SIGNAL !in o.origin || o.source == PositionSource.NETWORK) &&
            gnss.timestampMillis - o.timestampMillis <= WITNESS_MAX_AGE_MILLIS

    private fun closeTo(gnss: PositionObservation, o: PositionObservation) =
        gnss.point.distanceTo(o.point) <= config.recoveryAgreementM + 3 * (gnss.accuracyM + o.accuracyM) +
            config.maxSpeedMps * ((gnss.timestampMillis - o.timestampMillis).coerceAtLeast(0) / 1000.0)

    private fun agrees(gnss: PositionObservation, independent: List<PositionObservation>): Boolean =
        if (independent.isEmpty()) true
        else independent.any { gnss.point.distanceTo(it.point) <= config.recoveryAgreementM + 3 * it.accuracyM }

    private fun raise(nowMillis: Long, reason: String) {
        alarmUntil = nowMillis + config.holdMillis
        alarmReason = reason
    }

    /**
     * CUSUM ведётся отдельно по каждой независимой группе: при согласованной подмене GNSS и Wi-Fi общее
     * среднее тянул бы поддельный Wi-Fi (у него σ меньше), и честные соты не были бы услышаны.
     * Итоговый накопитель — максимум по группам.
     */
    private fun updateCusum(gnss: PositionObservation, independent: List<PositionObservation>) {
        val byGroup = independent.groupBy { o -> o.origin.minBy { it.ordinal } }
        for ((group, obs) in byGroup) {
            val w = obs.sumOf { it.weight }
            val lat = obs.sumOf { it.point.latitude * it.weight } / w
            val lon = obs.sumOf { it.point.longitude * it.weight } / w
            val sigmaI = max(1.0 / sqrt(w), config.cusumMinIndependentSigmaM)
            val z = gnss.point.distanceTo(GeoPoint(lat, lon)) / sqrt(gnss.accuracyM * gnss.accuracyM + sigmaI * sigmaI)
            // Накопитель ограничен 2h и не сбрасывается при тревоге: пока расхождение держится, тревога
            // продлевается каждую эпоху, а после схода GNSS с источником гаснет за ~h/k эпох.
            val c = min(2 * config.cusumH, max(0.0, (cusums[group] ?: 0.0) + z - config.cusumK))
            cusums[group] = c
            if (c > config.cusumH) {
                raise(lastMillis, "Плавный увод GNSS от независимого источника «${group.displayName}» (CUSUM ${c.toInt()})")
            }
        }
        cusum = cusums.values.maxOrNull() ?: 0.0
    }

    private fun init(gnss: PositionObservation, nowMillis: Long) {
        origin = gnss.point
        x.fill(0.0)
        val r = gnss.accuracyM * gnss.accuracyM
        for (a in p) {
            a[0] = r; a[1] = 0.0; a[2] = INITIAL_SPEED_SIGMA * INITIAL_SPEED_SIGMA
        }
        lastMillis = nowMillis
    }

    private fun predict(dt: Double) {
        val q = config.accelPsd
        for (axis in 0..1) {
            x[axis] += x[axis + 2] * dt
            val a = p[axis]
            val pp = a[0] + 2 * dt * a[1] + dt * dt * a[2] + q * dt * dt * dt / 3
            val pv = a[1] + dt * a[2] + q * dt * dt / 2
            val vv = a[2] + q * dt
            a[0] = pp; a[1] = pv; a[2] = vv
        }
    }

    private fun update(de: Double, dn: Double, r: Double) {
        for ((axis, d) in listOf(0 to de, 1 to dn)) {
            val a = p[axis]
            val s = a[0] + r
            val kp = a[0] / s
            val kv = a[1] / s
            x[axis] += kp * d
            x[axis + 2] += kv * d
            val pp = (1 - kp) * a[0]
            val pv = (1 - kp) * a[1]
            val vv = a[2] - kv * a[1]
            a[0] = pp; a[1] = pv; a[2] = vv
        }
    }

    private fun enu(o: GeoPoint, q: GeoPoint): Pair<Double, Double> {
        val d = o.distanceTo(q)
        val b = Math.toRadians(o.bearingTo(q))
        return d * sin(b) to d * cos(b)
    }

    private companion object {
        const val INITIAL_SPEED_SIGMA = 30.0
        const val WITNESS_MAX_AGE_MILLIS = 120_000L
    }
}
