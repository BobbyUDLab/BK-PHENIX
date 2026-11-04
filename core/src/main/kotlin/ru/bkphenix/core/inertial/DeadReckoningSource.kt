package ru.bkphenix.core.inertial

import ru.bkphenix.core.geo.GeoPoint
import ru.bkphenix.core.integrity.IntegrityState
import ru.bkphenix.core.model.PositionObservation
import ru.bkphenix.core.model.PositionSource
import ru.bkphenix.core.track.MotionMode

/**
 * Счисление пути как источник компаратора (группа «Датчики/счисление»).
 *
 * Наблюдение берётся на момент эпохи **до** учёта её решения: это прогноз из прошлого по датчикам,
 * поэтому он независимо проверяет новое решение (скачок, увод). Навигатор подтягивается только
 * к итоговым решениям, которые компаратор не счёл противоречивыми или подменой, а выбросы
 * дополнительно отсекает χ²-гейт фильтра. Плавный увод медленнее гейта счисление не поймает —
 * для этого есть CUSUM в [ru.bkphenix.core.integrity].
 *
 * Время навигатора — монотонное (elapsedRealtime), время наблюдения — настенное.
 */
public class DeadReckoningSource(private val navigator: InertialNavigator = InertialNavigator(MotionMode.CAR)) {
    public var mode: MotionMode
        @Synchronized get() = navigator.mode
        @Synchronized set(value) { navigator.mode = value }

    /** Магнитное склонение в месте нахождения, градусы (восток положителен). */
    public var declinationDeg: Double
        @Synchronized get() = navigator.declinationDeg
        @Synchronized set(value) { navigator.declinationDeg = value }

    @Synchronized
    public fun onImu(sample: ImuSample): Unit = navigator.onImu(sample)

    @Synchronized
    public fun estimate(realtimeMillis: Long): InertialEstimate? = navigator.estimate(realtimeMillis)

    /** Наблюдение для компаратора или null, если счисление не инициализировано или σ вышла за предел режима. */
    @Synchronized
    public fun observation(realtimeMillis: Long, wallMillis: Long): PositionObservation? {
        val e = navigator.estimate(realtimeMillis) ?: return null
        if (e.sinceFixMillis == 0L) return null
        return PositionObservation(PositionSource.DEAD_RECKONING, e.point, e.sigmaM, wallMillis)
    }

    /** Подтягивает счисление к итоговому решению эпохи. Возвращает true, если решение принято фильтром. */
    @Synchronized
    public fun onSolution(point: GeoPoint?, accuracyM: Double?, state: IntegrityState, realtimeMillis: Long): Boolean {
        if (point == null || state in UNTRUSTED) return false
        return navigator.onFix(point, accuracyM ?: DEFAULT_SIGMA_M, realtimeMillis)
    }

    private companion object {
        val UNTRUSTED = setOf(IntegrityState.INCONSISTENT, IntegrityState.SPOOFING_SUSPECTED, IntegrityState.NO_DATA)
        const val DEFAULT_SIGMA_M = 20.0
    }
}
