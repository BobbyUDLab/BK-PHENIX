package ru.bkphenix.core.geo

import kotlin.math.ln
import kotlin.math.sqrt

/**
 * Круг ошибки по горизонтальной σ. Для двумерной круговой нормальной ошибки радиус с
 * вероятностью p равен σ·√(−2·ln(1 − p)) (распределение Рэлея): 95 % — 2.448σ.
 * HPL — отдельная величина (уровень защиты RAIM) и в радиус не подмешивается.
 */
public object ErrorCircle {
    public const val P95: Double = 0.95

    public fun radius(sigmaM: Double, probability: Double = P95): Double {
        require(probability > 0.0 && probability < 1.0) { "Вероятность вне (0, 1): $probability" }
        return sigmaM * sqrt(-2.0 * ln(1.0 - probability))
    }
}
