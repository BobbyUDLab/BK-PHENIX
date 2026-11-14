package ru.bkphenix.app.data.map

import android.content.Context
import android.net.Uri
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext
import java.io.File
import java.io.IOException

sealed interface MapFileState {
    data object Missing : MapFileState
    data class Importing(val copiedBytes: Long) : MapFileState
    data class Ready(val file: File) : MapFileState
    data class Failed(val message: String) : MapFileState
}

/**
 * Офлайн-карта в формате PMTiles (вырезка Protomaps из OSM). Файл слишком велик для APK, поэтому
 * импортируется пользователем (например, из Nextcloud через системный выбор файла) и копируется
 * во внутреннее хранилище: MapLibre читает его локально, без сети.
 */
class MapFileStore(context: Context) {
    private val app = context.applicationContext
    private val dir = File(app.filesDir, "maps")
    val file = File(dir, "region.pmtiles")
    private val _state = MutableStateFlow(if (isValid(file)) MapFileState.Ready(file) else MapFileState.Missing)
    val state: StateFlow<MapFileState> = _state.asStateFlow()

    suspend fun import(uri: Uri) = withContext(Dispatchers.IO) {
        val tmp = File(dir, "import.tmp")
        try {
            dir.mkdirs()
            _state.value = MapFileState.Importing(0)
            val input = app.contentResolver.openInputStream(uri) ?: throw IOException("файл недоступен")
            input.use { src ->
                tmp.outputStream().use { dst ->
                    val buf = ByteArray(1 shl 16)
                    var total = 0L
                    var lastReport = 0L
                    while (true) {
                        val n = src.read(buf)
                        if (n < 0) break
                        dst.write(buf, 0, n)
                        total += n
                        if (total - lastReport > REPORT_STEP) {
                            lastReport = total
                            _state.value = MapFileState.Importing(total)
                        }
                    }
                }
            }
            if (!isValid(tmp)) throw IOException("это не файл PMTiles v3")
            if (!tmp.renameTo(file)) throw IOException("не удалось сохранить файл")
            _state.value = MapFileState.Ready(file)
        } catch (e: IOException) {
            tmp.delete()
            _state.value = if (isValid(file)) MapFileState.Ready(file) else MapFileState.Failed(e.message ?: "ошибка импорта")
        }
    }

    /** Принимает скачанный с сервера и проверенный файл: перемещает его на место карты без копирования. */
    suspend fun adopt(downloaded: File) = withContext(Dispatchers.IO) {
        if (!isValid(downloaded)) throw IOException("это не файл PMTiles v3")
        dir.mkdirs()
        if (!downloaded.renameTo(file)) throw IOException("не удалось сохранить файл карты")
        _state.value = MapFileState.Ready(file)
    }

    companion object {
        private const val REPORT_STEP = 8L shl 20

        /** Заголовок PMTiles v3: "PMTiles" и байт версии 3. */
        fun isValid(f: File): Boolean = f.isFile && f.length() > 127 && f.inputStream().use { s ->
            val h = ByteArray(8)
            s.read(h) == 8 && String(h, 0, 7, Charsets.US_ASCII) == "PMTiles" && h[7].toInt() == 3
        }
    }
}
