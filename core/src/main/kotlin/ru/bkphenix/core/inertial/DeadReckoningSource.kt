package ru.bkphenix.core.inertial

import ru.bkphenix.core.PhenixSolution
import ru.bkphenix.core.compare.ComparisonResult
import ru.bkphenix.core.geo.GeoPoint
import ru.bkphenix.core.integrity.IntegrityState
import ru.bkphenix.core.model.PositionObservation
import ru.bkphenix.core.model.PositionSource
import ru.bkphenix.core.model.SourceGroup
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
        // Один фикс — не основа для счисления: первый фикс после включения бывает кэшированным или
        // поддельным (DOOGEE 05.11.2026: точка у Шереметьево, затем GNSS пропала, и счисление
        // от неё 70 с отсекало верную сеть).
        if (!anchorIndependent && acceptedFixes < MIN_SEED_FIXES) return null
        return PositionObservation(PositionSource.DEAD_RECKONING, e.point, e.sigmaM, wallMillis, gnssDerived = !anchorIndependent)
    }

    /**
     * Последняя привязка счисления подтверждена независимо от GNSS (≥ 2 сильных голоса без самого
     * счисления, например GNSS + соты, или якорь). Пока нет — счисление лишь продолжает GNSS и не
     * может её подтвердить ([PositionObservation.gnssDerived]).
     */
    public val isAnchorIndependent: Boolean
        @Synchronized get() = anchorIndependent

    private var anchorIndependent = false

    /**
     * Подтягивает счисление к итоговому решению эпохи. Возвращает true, если решение принято фильтром.
     *
     * @param independentlyConfirmed решение подтверждено независимо от GNSS и от самого счисления
     *   (кворум без группы «Датчики/счисление» или якорь). Без этого счисление помечается как
     *   производное от GNSS, иначе оно «подтверждало» бы GNSS её же прошлым решением.
     */
    @Synchronized
    public fun onSolution(
        point: GeoPoint?,
        accuracyM: Double?,
        state: IntegrityState,
        realtimeMillis: Long,
        independentlyConfirmed: Boolean = false,
    ): Boolean {
        if (point == null || state in UNTRUSTED) return false
        val accepted = navigator.onFix(point, accuracyM ?: DEFAULT_SIGMA_M, realtimeMillis)
        if (accepted) {
            anchorIndependent = independentlyConfirmed
            acceptedFixes++
        }
        return accepted
    }

    /**
     * Подтягивает счисление к итогу эпохи [solution]. Итог, который держится только на самом
     * счислении (GNSS нет, остальное отсеяно), не принимается: иначе счисление «подтверждало» бы
     * само себя и его σ не росла (DOOGEE 05.11.2026: σ 2–3 м на точке в 13 км от реальной).
     */
    @Synchronized
    public fun onSolution(solution: PhenixSolution, realtimeMillis: Long): Boolean {
        val accepted = solution.comparison.accepted
        if (accepted.isEmpty() || accepted.all { it.source == PositionSource.DEAD_RECKONING }) return false
        val confirmed = solution.comparison.strongConfirmationsExcluding(SourceGroup.INERTIAL) >= ComparisonResult.QUORUM ||
            accepted.any { it.source == PositionSource.LANDMARK }
        return onSolution(solution.point, solution.accuracyM, solution.integrity.state, realtimeMillis, confirmed)
    }

    /**
     * GNSS признана подменённой: счисление, привязанное к ней без независимого подтверждения,
     * сбрасывается — иначе оно продолжало бы вести подменённую траекторию. Подтверждённое (якорем
     * или кворумом без GNSS) остаётся. Возвращает true, если сброшено.
     */
    @Synchronized
    public fun resetIfUnconfirmed(): Boolean {
        if (anchorIndependent) return false
        navigator.reset()
        acceptedFixes = 0
        return true
    }

    /** Сколько решений принято с момента создания: до [MIN_SEED_FIXES] счисление не голосует. */
    private var acceptedFixes = 0

    private companion object {
        val UNTRUSTED = setOf(IntegrityState.INCONSISTENT, IntegrityState.SPOOFING_SUSPECTED, IntegrityState.NO_DATA)
        const val DEFAULT_SIGMA_M = 20.0
        const val MIN_SEED_FIXES = 3
    }
}
