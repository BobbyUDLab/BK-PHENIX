package ru.bkphenix.app.data.roads

import android.content.Context
import android.net.Uri
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext
import ru.bkphenix.core.roads.RoadGraph
import java.io.File
import java.io.IOException
import java.util.zip.GZIPInputStream

sealed interface RoadGraphState {
    data object Missing : RoadGraphState
    data class Importing(val copiedBytes: Long) : RoadGraphState
    data object Loading : RoadGraphState
    data class Ready(val nodes: Int, val segments: Int) : RoadGraphState
    data class Failed(val message: String) : RoadGraphState
}

/**
 * Дорожный граф для привязки к дорогам (`BK-PHENIX-roads-moscow.bin.gz`, OSM, ODbL). В APK и git не
 * кладётся: пользователь импортирует файл (например, из Nextcloud), как карту; копия хранится во
 * внутренней памяти и загружается в фоне при старте.
 */
class RoadGraphStore(context: Context) {
    private val app = context.applicationContext
    private val dir = File(app.filesDir, "roads")
    private val file = File(dir, "roads.bin.gz")
    private val _state = MutableStateFlow<RoadGraphState>(RoadGraphState.Missing)
    val state: StateFlow<RoadGraphState> = _state.asStateFlow()

    @Volatile var graph: RoadGraph? = null
        private set

    suspend fun load() = withContext(Dispatchers.IO) {
        if (graph != null || !file.isFile) return@withContext
        _state.value = RoadGraphState.Loading
        _state.value = try {
            val g = read(file)
            graph = g
            RoadGraphState.Ready(g.nodeCount, g.segmentCount)
        } catch (e: Exception) {
            Log.w("BkPhenix", "Дорожный граф не загружен", e)
            RoadGraphState.Failed(e.message ?: "файл повреждён")
        } catch (e: OutOfMemoryError) {
            RoadGraphState.Failed("не хватает памяти для графа")
        }
    }

    suspend fun import(uri: Uri) = withContext(Dispatchers.IO) {
        val tmp = File(dir, "import.tmp")
        try {
            dir.mkdirs()
            _state.value = RoadGraphState.Importing(0)
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
                        if (total % (4L shl 20) < n) _state.value = RoadGraphState.Importing(total)
                    }
                }
            }
            _state.value = RoadGraphState.Loading
            val g = try { read(tmp) } catch (e: IllegalArgumentException) { throw IOException("это не дорожный граф BKRG: ${e.message}") }
            if (!tmp.renameTo(file)) throw IOException("не удалось сохранить файл")
            graph = g
            _state.value = RoadGraphState.Ready(g.nodeCount, g.segmentCount)
        } catch (e: IOException) {
            tmp.delete()
            _state.value = graph?.let { RoadGraphState.Ready(it.nodeCount, it.segmentCount) } ?: RoadGraphState.Failed(e.message ?: "ошибка импорта")
        }
    }

    /** Принимает скачанный с сервера и проверенный файл: проверяет формат, загружает и перемещает на место. */
    suspend fun adopt(downloaded: File) = withContext(Dispatchers.IO) {
        _state.value = RoadGraphState.Loading
        val g = try { read(downloaded) } catch (e: Exception) {
            _state.value = graph?.let { g0 -> RoadGraphState.Ready(g0.nodeCount, g0.segmentCount) } ?: RoadGraphState.Missing
            throw IOException("это не дорожный граф BKRG: ${e.message}")
        }
        dir.mkdirs()
        if (!downloaded.renameTo(file)) throw IOException("не удалось сохранить файл")
        graph = g
        _state.value = RoadGraphState.Ready(g.nodeCount, g.segmentCount)
    }

    private fun read(f: File): RoadGraph = GZIPInputStream(f.inputStream().buffered(1 shl 16), 1 shl 16).use { RoadGraph.read(it.buffered(1 shl 16)) }

    companion object {
        const val SOURCE = "© участники OpenStreetMap (ODbL)"
    }
}
