package ru.bkphenix.app.ui.theme

import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow

/**
 * Палитра бренда, оттенки сняты пипеткой с docs/brand/logo.jpg:
 * циан прицела #14CFFE, оранжевый крыла #FD5A0A, золото #FB950F, тёмно-синий фон #001334, орбиты #375988.
 * Цвета хранятся как ARGB, чтобы контраст проверялся обычным JUnit без Compose.
 */
object PhenixPalette {
    const val CYAN = 0xFF14CFFEL
    const val ORANGE = 0xFFFD5A0AL
    const val GOLD = 0xFFFB950FL
    const val NAVY = 0xFF001334L
    const val ORBIT = 0xFF375988L

    /** Тёмная OLED: чистый чёрный фон. */
    object Oled {
        const val BACKGROUND = 0xFF000000L
        const val SURFACE_CONTAINER = 0xFF0A1222L
        const val ON_SURFACE = 0xFFE6EDF7L
        const val ON_SURFACE_VARIANT = 0xFFA9B8CCL
        const val PRIMARY = CYAN
        const val SECONDARY = ORANGE
        const val TERTIARY = GOLD
        const val STATUS_OK = 0xFF3DDC84L
        const val STATUS_WARN = 0xFFFFB020L
        const val STATUS_BAD = 0xFFFF6B6BL
    }

    object Light {
        const val BACKGROUND = 0xFFFFFFFFL
        const val SURFACE_CONTAINER = 0xFFEEF3F9L
        const val ON_SURFACE = 0xFF0B1424L
        const val ON_SURFACE_VARIANT = 0xFF3D4A5CL
        const val PRIMARY = 0xFF006A8AL
        const val SECONDARY = 0xFFB33F00L
        const val TERTIARY = 0xFF8A5200L
        const val STATUS_OK = 0xFF1B7A43L
        const val STATUS_WARN = 0xFF8A5A00L
        const val STATUS_BAD = 0xFFC62828L
    }

    /** Относительная яркость по WCAG 2.x. */
    fun luminance(argb: Long): Double {
        fun ch(shift: Int): Double {
            val c = ((argb shr shift) and 0xFF) / 255.0
            return if (c <= 0.03928) c / 12.92 else ((c + 0.055) / 1.055).pow(2.4)
        }
        return 0.2126 * ch(16) + 0.7152 * ch(8) + 0.0722 * ch(0)
    }

    /** Коэффициент контраста WCAG (1…21). AA для обычного текста — ≥ 4,5. */
    fun contrast(a: Long, b: Long): Double {
        val la = luminance(a)
        val lb = luminance(b)
        return (max(la, lb) + 0.05) / (min(la, lb) + 0.05)
    }

    /** Цвет в виде «#RRGGBB» для стилей MapLibre. */
    fun hex(argb: Long): String = "#%06X".format(argb and 0xFFFFFF)
}
