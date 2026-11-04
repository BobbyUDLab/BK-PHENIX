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

public data class AnchorEvaluation(
    val state: AnchorState,
    val anchor: Anchor?,
    /** Расстояние от якоря до решения, метры. */
    val deviationM: Double?,
    /** Допустимое отклонение с учётом погрешностей и возможного перемещения, метры. */
    val allowedDeviationM: Double?,
    val message: String,
)

/**
 * Якорь — приоритет №1. Человек задаёт точку, код проверяет вторым.
 *
 * Допустимое отклонение: `gateSigma·√(σa² + σe²) + maxSpeed·Δt`, где Δt — время с момента задания якоря.
 *
 * @param maxSpeedMps максимальная скорость перемещения для текущего режима (пешком ≈ 3 м/с).
 */
public class AnchorManager(
    private val gateSigma: Double = 3.0,
    private val maxSpeedMps: Double = WALK_MAX_SPEED_MPS,
) {
    public var anchor: Anchor? = null
        private set

    public fun setAnchor(anchor: Anchor) {
        this.anchor = anchor
    }

    /**
     * Якорь как наблюдение для компаратора: σ растёт на `maxSpeed·Δt`, источник — ориентир
     * (группа «человек»). `null`, если якоря нет.
     */
    public fun observation(nowMillis: Long): PositionObservation? {
        val a = anchor ?: return null
        val elapsedS = ((nowMillis - a.timestampMillis).coerceAtLeast(0)) / 1000.0
        return PositionObservation(PositionSource.LANDMARK, a.point, a.accuracyM + maxSpeedMps * elapsedS, nowMillis)
    }

    public fun clear() {
        anchor = null
    }

    public fun evaluate(estimate: GeoPoint?, estimateAccuracyM: Double?, nowMillis: Long): AnchorEvaluation {
        val a = anchor ?: return AnchorEvaluation(
            AnchorState.NO_ANCHOR, null, null, null,
            "Якорь не задан: подтвердите начальную точку",
        )
        if (estimate == null || estimateAccuracyM == null) {
            return AnchorEvaluation(AnchorState.HUMAN_ANCHOR, a, null, null, "Используется якорь пользователя")
        }
        val elapsedS = ((nowMillis - a.timestampMillis).coerceAtLeast(0)) / 1000.0
        val allowed = gateSigma * sqrt(a.accuracyM * a.accuracyM + estimateAccuracyM * estimateAccuracyM) +
            maxSpeedMps * elapsedS
        val deviation = a.point.distanceTo(estimate)
        return if (deviation > allowed) {
            AnchorEvaluation(
                AnchorState.ANCHOR_CONFLICT, a, deviation, allowed,
                "Решение отклоняется от якоря на ${deviation.toInt()} м (допустимо ${allowed.toInt()} м)",
            )
        } else {
            AnchorEvaluation(AnchorState.HUMAN_ANCHOR, a, deviation, allowed, "Решение согласуется с якорем")
        }
    }

    public companion object {
        public const val WALK_MAX_SPEED_MPS: Double = 3.0
        public const val CAR_MAX_SPEED_MPS: Double = 50.0
    }
}
