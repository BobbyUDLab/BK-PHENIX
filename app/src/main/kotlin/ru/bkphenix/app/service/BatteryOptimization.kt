package ru.bkphenix.app.service

import android.annotation.SuppressLint
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.PowerManager
import android.provider.Settings
import android.util.Log

/**
 * Исключение из оптимизации батареи (0.8.3). Поездка 18.11.2026: при выключенном экране обработка эпох
 * встала на 24 минуты при живом процессе и удержании процессора — так ведут себя прошивки с агрессивным
 * энергосбережением. Системный запрос показывается один раз; при отказе больше не беспокоим.
 */
object BatteryOptimization {
    private const val PREFS = "battery"
    private const val ASKED = "asked"

    fun isIgnoring(context: Context): Boolean =
        context.getSystemService(PowerManager::class.java)?.isIgnoringBatteryOptimizations(context.packageName) ?: true

    @SuppressLint("BatteryLife")
    fun requestIfNeeded(context: Context) {
        if (isIgnoring(context)) return
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        if (prefs.getBoolean(ASKED, false)) return
        prefs.edit().putBoolean(ASKED, true).apply()
        try {
            context.startActivity(
                Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS, Uri.parse("package:${context.packageName}")),
            )
        } catch (e: RuntimeException) {
            Log.w("BkPhenix", "Запрос исключения из оптимизации батареи недоступен", e)
        }
    }
}
