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
                val loaded = context.assets.open(ASSET).use { PackedCellTowerDatabase.read(GZIPInputStream(it)) }
                db = loaded
                _info.value = CellDatabaseInfo(loaded.size, REGION, SOURCE)
            } catch (e: Exception) {
                Log.w("BkPhenix", "База вышек не загружена", e)
            }
        }
    }

    companion object {
        const val ASSET = "cells/cells-moscow.bin.gz"
        const val REGION = "Москва и Московская область"
        const val SOURCE = "Mozilla Location Service, финальная выгрузка 2024 (CC0 1.0)"
    }
}
