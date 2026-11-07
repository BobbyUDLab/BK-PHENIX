package ru.bkphenix.app.data.cells

import android.content.Context
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext
import ru.bkphenix.core.radio.CellMeasurement
import ru.bkphenix.core.radio.CellTowerDatabase
import ru.bkphenix.core.radio.CellTowerRecord
import ru.bkphenix.core.radio.PackedCellTowerDatabase
import java.io.BufferedInputStream
import java.io.InputStream
import java.util.zip.GZIPInputStream

/** Что загружено в офлайн-базу вышек — для экрана диагностики. */
data class CellDatabaseInfo(val cells: Int, val region: String, val source: String)

/**
 * Офлайн-база вышек из ассета `cells/cells-moscow.bin.gz` (Москва и область, выгрузка Mozilla
 * Location Service, CC0 1.0). Пока база не загружена ([load]), вышки не находятся.
 */
class AssetCellTowerDatabase(private val context: Context) : CellTowerDatabase {
    @Volatile private var db: PackedCellTowerDatabase? = null
    private val _info = MutableStateFlow<CellDatabaseInfo?>(null)
    val info: StateFlow<CellDatabaseInfo?> = _info.asStateFlow()

    override fun find(measurement: CellMeasurement): CellTowerRecord? = db?.find(measurement)

    suspend fun load() {
        if (db != null) return
        withContext(Dispatchers.IO) {
            try {
                val loaded = openAsset().use { PackedCellTowerDatabase.read(it) }
                db = loaded
                _info.value = CellDatabaseInfo(loaded.size, REGION, SOURCE)
            } catch (e: Exception) {
                Log.w("BkPhenix", "База вышек не загружена", e)
            }
        }
    }

    /**
     * Android при упаковке распаковывает ассеты `*.gz` и убирает расширение: в APK лежит
     * `cells/cells-moscow.bin` без сжатия. До 0.8 код открывал только `.bin.gz`, исключение глушилось,
     * и база не загружалась ни разу (аудит 07.11.2026: 0 наблюдений CELL в 6587 полевых эпохах).
     * Пробуем оба имени и сами определяем сжатие по сигнатуре gzip.
     */
    private fun openAsset(): InputStream {
        val raw = ASSET_NAMES.firstNotNullOfOrNull { name ->
            try { context.assets.open(name) } catch (e: java.io.FileNotFoundException) { null }
        } ?: throw java.io.FileNotFoundException("Нет ассета базы вышек: ${ASSET_NAMES.joinToString()}")
        val input = BufferedInputStream(raw)
        input.mark(2)
        val gzip = input.read() == 0x1f && input.read() == 0x8b
        input.reset()
        return if (gzip) GZIPInputStream(input) else input
    }

    companion object {
        const val ASSET = "cells/cells-moscow.bin.gz"
        /** Имя в APK после распаковки aapt — первым, исходное — запасным. */
        val ASSET_NAMES = listOf("cells/cells-moscow.bin", ASSET)
        const val REGION = "Москва и Московская область"
        const val SOURCE = "OpenCelliD (CC BY-SA 4.0), 2025–2026 + Mozilla Location Service 2024 (CC0 1.0)"
    }
}
