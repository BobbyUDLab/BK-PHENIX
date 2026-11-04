package ru.bkphenix.app.data.terrain

import android.content.Context
import android.net.Uri
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext
import ru.bkphenix.core.terrain.TerrainModel
import java.io.File
import java.io.IOException
import java.util.zip.GZIPInputStream

sealed interface TerrainState {
    data object Missing : TerrainState
    data class Importing(val copiedBytes: Long) : TerrainState
    data object Loading : TerrainState
    data class Ready(val rows: Int, val cols: Int) : TerrainState
    data class Failed(val message: String) : TerrainState
}

/**
 * Рельеф и геоид для проверки высоты (`BK-PHENIX-dem-moscow.bin.gz`: Copernicus DEM GLO-90 и EGM2008).
 * В APK и git не кладётся: импортируется пользователем, как карта, и загружается в фоне при старте.
 */
class TerrainStore(context: Context) {
    private val app = context.applicationContext
    private val dir = File(app.filesDir, "terrain")
    private val file = File(dir, "dem.bin.gz")
    private val _state = MutableStateFlow<TerrainState>(TerrainState.Missing)
    val state: StateFlow<TerrainState> = _state.asStateFlow()

    @Volatile var model: TerrainModel? = null
        private set

    suspend fun load() = withContext(Dispatchers.IO) {
        if (model != null || !file.isFile) return@withContext
        _state.value = TerrainState.Loading
        _state.value = try {
            val g = read(file)
            model = g
            TerrainState.Ready(g.rows, g.cols)
        } catch (e: Exception) {
            Log.w("BkPhenix", "Рельеф не загружен", e)
            TerrainState.Failed(e.message ?: "файл повреждён")
        } catch (e: OutOfMemoryError) {
            TerrainState.Failed("не хватает памяти для рельефа")
        }
    }

    suspend fun import(uri: Uri) = withContext(Dispatchers.IO) {
        val tmp = File(dir, "import.tmp")
        try {
            dir.mkdirs()
            _state.value = TerrainState.Importing(0)
            val input = app.contentResolver.openInputStream(uri) ?: throw IOException("файл недоступен")
            input.use { src ->
                tmp.outputStream().use { dst ->
                    val buf = ByteArray(1 shl 16)
                    var total = 0L
                    while (true) {
                        val n = src.read(buf)
                        if (n < 0) break
                        dst.write(buf, 0, n)
                        total += n
                        if (total % (4L shl 20) < n) _state.value = TerrainState.Importing(total)
                    }
                }
            }
            _state.value = TerrainState.Loading
            val g = try { read(tmp) } catch (e: IllegalArgumentException) { throw IOException("это не файл рельефа BKDM: ${e.message}") }
            if (!tmp.renameTo(file)) throw IOException("не удалось сохранить файл")
            model = g
            _state.value = TerrainState.Ready(g.rows, g.cols)
        } catch (e: IOException) {
            tmp.delete()
            _state.value = model?.let { TerrainState.Ready(it.rows, it.cols) } ?: TerrainState.Failed(e.message ?: "ошибка импорта")
        }
    }

    private fun read(f: File): TerrainModel = GZIPInputStream(f.inputStream().buffered(1 shl 16), 1 shl 16).use { TerrainModel.read(it.buffered(1 shl 16)) }

    companion object {
        const val SOURCE = "Copernicus DEM GLO-90 © DLR, Airbus (ЕС, ESA) · геоид EGM2008"
    }
}
