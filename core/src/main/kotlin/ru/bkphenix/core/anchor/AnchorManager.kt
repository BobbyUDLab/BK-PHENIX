package ru.bkphenix.core.anchor

import ru.bkphenix.core.geo.GeoPoint
import ru.bkphenix.core.model.PositionObservation
import ru.bkphenix.core.model.PositionSource
import kotlin.math.sqrt

/** Состояние якоря пользователя. */
public enum class AnchorState {
    /** Якорь не задан: приложение предупреждает пользователя. */
    NO_ANCHOR,

    /** Якорь задан человеком и согласуется с решением. */
    HUMAN_ANCHOR,

    /** Решение противоречит якорю. Якорь не переписывается, пользователь получает предупреждение. */
    ANCHOR_CONFLICT,
}

/** Режим задания якоря. */
public enum class AnchorMode { MANUAL, AUTOMATIC }

/** Точка, заданная или подтверждённая человеком. */
public data class Anchor(
    val point: GeoPoint,
    val accuracyM: Double,
    val timestampMillis: Long,
    val mode: AnchorMode = AnchorMode.MANUAL,
) {
    init {
        require(accuracyM > 0.0) { "Погрешность якоря должна быть положительной" }
    }
}

/** Насколько якорь ещё годится как опора. */
public enum class AnchorFreshness(public val label: String) {
    ACTUAL("Якорь актуален"),
    CAN_UPDATE("Якорь можно обновить"),
    MUST_UPDATE("Якорь нужно обновить"),
}

public data class AnchorEvaluation(
    val state: AnchorState,
    val anchor: Anchor?,
    /** Расстояние от положения по якорю до решения других источников, метры. */
    val deviationM: Double?,
    /** Допустимое отклонение с учётом погрешностей и возможного перемещения, метры. */
    val allowedDeviationM: Double?,
    val message: String,
    val freshness: AnchorFreshness? = null,
    /** Положение по якорю: исходная точка + перемещение, подтверждённое согласованными источниками. */
    val propagatedPoint: GeoPoint? = null,
    /** Накопленная погрешность положения по якорю (1σ), метры. */
    val propagatedSigmaM: Double? = null,
    val ageSeconds: Double? = null,
    /** Путь от якоря, метры. */
    val traveledM: Double? = null,
    /** Почему якорь можно или нужно обновить. */
    val freshnessReason: String? = null,
)

/**
 * Якорь — исходная точка с растущей неопределённостью (0.6.1).
 *
 * Положение по якорю P = якорь + перемещение. Пока решение других источников согласуется с P (≤ gateSigma·√(σP² + σe²)),
 * P следует за ним, а σP растёт: `σP = √(σa² + (kт·t)² + (kп·путь)²)`. Если источников нет, σP растёт ещё на
 * maxSpeed·Δt (неизвестное перемещение). Расхождение больше допуска — конфликт: якорь не переписывается.
 *
 * Пороги свежести: «можно обновить» — σP больше σ согласованных источников (они точнее якоря) или прошло 30 мин;
 * «нужно обновить» — конфликт с источниками, σP > 300 м или прошло 2 ч. Устаревший якорь не приоритетен
 * и не отсеивает другие источники (см. [isPriority]).
 */
public class AnchorManager(
    private val gateSigma: Double = 3.0,
    private val maxSpeedMps: Double = WALK_MAX_SPEED_MPS,
) {
    public var anchor: Anchor? = null
        private set
    private var point: GeoPoint? = null
    private var unknownMotionM = 0.0
    private var traveledM = 0.0
    private var lastMillis = 0L
    private var last: AnchorEvaluation? = null
    private var conflictStreak = 0
    private var conflictWithQuorum = false

    public fun setAnchor(anchor: Anchor) {
        this.anchor = anchor
        point = anchor.point
        unknownMotionM = 0.0
        traveledM = 0.0
        lastMillis = anchor.timestampMillis
        last = null
    }

    private fun sigma(a: Anchor, nowMillis: Long): Double {
        val t = ((nowMillis - a.timestampMillis).coerceAtLeast(0)) / 1000.0
        val growth = TIME_SIGMA_MPS * t
        val path = PATH_SIGMA_FRACTION * traveledM
        val gap = unknownMotionM + maxSpeedMps * ((nowMillis - lastMillis).coerceAtLeast(0) / 1000.0)
        return sqrt(a.accuracyM * a.accuracyM + growth * growth + path * path + gap * gap)
    }

    /**
     * Приоритет в компараторе. Свежий конфликт якорь выигрывает (защита от подмены). Но если ≥ [CONFLICT_EPOCHS]
     * эпох подряд с ним расходятся источники с кворумом, или якорь устарел по σ/возрасту, приоритет снимается:
     * старый якорь не должен отсеивать всё и замораживать позицию.
     */
    public val isPriority: Boolean get() = when {
        last?.state == AnchorState.ANCHOR_CONFLICT -> !(conflictWithQuorum && conflictStreak >= CONFLICT_EPOCHS)
        else -> last?.freshness != AnchorFreshness.MUST_UPDATE
    }

    /** Якорь как наблюдение: положение по якорю с накопленной σ. null — якоря нет или он устарел по σ/возрасту. */
    public fun observation(nowMillis: Long): PositionObservation? {
        val a = anchor ?: return null
        if (last?.freshness == AnchorFreshness.MUST_UPDATE && last?.state != AnchorState.ANCHOR_CONFLICT) return null
        return PositionObservation(PositionSource.LANDMARK, point ?: a.point, sigma(a, nowMillis), nowMillis)
    }

    public fun clear() {
        anchor = null
        point = null
        last = null
    }

    /** @param sourcesHaveQuorum решение без якоря подтверждено кворумом независимых источников. */
    public fun evaluate(
        estimate: GeoPoint?,
        estimateAccuracyM: Double?,
        nowMillis: Long,
        sourcesHaveQuorum: Boolean = false,
    ): AnchorEvaluation {
        val a = anchor ?: return AnchorEvaluation(
            AnchorState.NO_ANCHOR, null, null, null,
            "Якорь не задан: подтвердите начальную точку",
        ).also { last = it }
        val p = point ?: a.point
        val ageS = ((nowMillis - a.timestampMillis).coerceAtLeast(0)) / 1000.0
        val sigmaP = sigma(a, nowMillis)
        if (estimate == null || estimateAccuracyM == null) {
            val freshness = freshness(sigmaP, null, ageS, conflict = false)
            return AnchorEvaluation(
                AnchorState.HUMAN_ANCHOR, a, null, null, "Других источников нет: положение по якорю, σ ${sigmaP.toInt()} м",
                freshness.first, p, sigmaP, ageS, traveledM, freshness.second,
            ).also { last = it }
        }
        val allowed = gateSigma * sqrt(sigmaP * sigmaP + estimateAccuracyM * estimateAccuracyM)
        val deviation = p.distanceTo(estimate)
        val conflict = deviation > allowed
        conflictStreak = if (conflict) conflictStreak + 1 else 0
        conflictWithQuorum = conflict && sourcesHaveQuorum
        if (!conflict) {
            // Перемещение подтверждено согласованными источниками: положение по якорю следует за ними.
            traveledM += deviation
            point = estimate
            unknownMotionM = 0.0
        } else {
            unknownMotionM += maxSpeedMps * ((nowMillis - lastMillis).coerceAtLeast(0) / 1000.0)
        }
        lastMillis = nowMillis
        val sigmaNow = sigma(a, nowMillis)
        val freshness = freshness(sigmaNow, estimateAccuracyM, ageS, conflict)
        return if (conflict) {
            AnchorEvaluation(
                AnchorState.ANCHOR_CONFLICT, a, deviation, allowed,
                "Решение отклоняется от положения по якорю на ${deviation.toInt()} м (допустимо ${allowed.toInt()} м)",
                freshness.first, p, sigmaNow, ageS, traveledM, freshness.second,
            )
        } else {
            AnchorEvaluation(
                AnchorState.HUMAN_ANCHOR, a, deviation, allowed, "Решение согласуется с якорем",
                freshness.first, point, sigmaNow, ageS, traveledM, freshness.second,
            )
        }.also { last = it }
    }

    private fun freshness(sigmaP: Double, sourcesSigma: Double?, ageS: Double, conflict: Boolean): Pair<AnchorFreshness, String?> = when {
        conflict -> AnchorFreshness.MUST_UPDATE to "согласованные источники расходятся с положением по якорю"
        sigmaP > MUST_UPDATE_SIGMA_M -> AnchorFreshness.MUST_UPDATE to "накопленная погрешность ${sigmaP.toInt()} м > ${MUST_UPDATE_SIGMA_M.toInt()} м"
        ageS > MUST_UPDATE_AGE_S -> AnchorFreshness.MUST_UPDATE to "якорю больше ${(MUST_UPDATE_AGE_S / 3600).toInt()} ч"
        sourcesSigma != null && sigmaP > sourcesSigma * 2 && sigmaP > CAN_UPDATE_MIN_SIGMA_M ->
            AnchorFreshness.CAN_UPDATE to "источники точнее якоря: σ ${sourcesSigma.toInt()} м против ${sigmaP.toInt()} м"
        ageS > CAN_UPDATE_AGE_S -> AnchorFreshness.CAN_UPDATE to "якорю больше ${(CAN_UPDATE_AGE_S / 60).toInt()} мин"
        else -> AnchorFreshness.ACTUAL to null
    }

    public companion object {
        public const val WALK_MAX_SPEED_MPS: Double = 3.0
        public const val CAR_MAX_SPEED_MPS: Double = 50.0
        /** Рост σ со временем, м/с: дрейф опоры без новых сведений. */
        public const val TIME_SIGMA_MPS: Double = 0.02
        /** Рост σ с путём: 3 % пройденного. */
        public const val PATH_SIGMA_FRACTION: Double = 0.03
        public const val CAN_UPDATE_MIN_SIGMA_M: Double = 30.0
        public const val MUST_UPDATE_SIGMA_M: Double = 300.0
        public const val CAN_UPDATE_AGE_S: Double = 30 * 60.0
        public const val MUST_UPDATE_AGE_S: Double = 2 * 3600.0
        /** Эпох подряд конфликта с кворумом источников, после которых якорь теряет приоритет. */
        public const val CONFLICT_EPOCHS: Int = 10
    }
}
