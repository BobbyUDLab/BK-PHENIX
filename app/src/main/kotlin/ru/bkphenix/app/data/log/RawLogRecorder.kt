package ru.bkphenix.app.data.log

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.io.BufferedWriter
import java.io.File
import java.io.IOException
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** Состояние записи лога для UI. */
data class RawLogState(
    val recording: Boolean = false,
    val files: List<File> = emptyList(),
    val bytes: Long = 0,
    val error: String? = null,
)

/**
 * Пишет два файла на сессию: `gnss_log_*.txt` (формат GnssLogger) и `phenix_*.jsonl`
 * (наши данные: соты, датчики, результаты ядра). Методы вызываются из колбэков ГНСС
 * и из потока ядра, поэтому синхронизированы.
 */
class RawLogRecorder(private val directory: File, private val header: () -> String) {
    private var txt: BufferedWriter? = null
    private var jsonl: BufferedWriter? = null
    private var sessionFiles: List<File> = emptyList()

    /** Файлы последней завершённой сессии (для выгрузки на сервер). */
    @Volatile
    var lastFinished: List<File> = emptyList()
        private set
    private var bytes = 0L
    private var lastPublishedBytes = 0L
    private val _state = MutableStateFlow(RawLogState(files = existingFiles()))
    val state: StateFlow<RawLogState> = _state.asStateFlow()

    @Volatile
    var isRecording: Boolean = false
        private set

    @Synchronized
    fun start() {
        if (isRecording) return
        try {
            directory.mkdirs()
            val stamp = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.ROOT).format(Date())
            val txtFile = File(directory, "gnss_log_$stamp.txt")
            val jsonlFile = File(directory, "phenix_$stamp.jsonl")
            txt = txtFile.bufferedWriter().also { it.write(header()) }
            jsonl = jsonlFile.bufferedWriter()
            sessionFiles = listOf(txtFile, jsonlFile)
            bytes = 0
            isRecording = true
            publish(null)
        } catch (e: IOException) {
            closeAll()
            publish("Не удалось открыть файл: ${e.message}")
        }
    }

    @Synchronized
    fun stop() {
        if (!isRecording) return
        isRecording = false
        closeAll()
        lastFinished = sessionFiles
        publish(null)
    }

    /** Строка формата GnssLogger без перевода строки. */
    @Synchronized
    fun text(line: String) = write(txt, line)

    /** Запись JSONL: тип и поля. */
    @Synchronized
    fun json(type: String, timeMillis: Long, fields: Map<String, Any?>) =
        write(jsonl, Json.encode(linkedMapOf<String, Any?>("type" to type, "t" to timeMillis) + fields))

    /** Сбрасывает буферы на диск: если процесс убьют, потеряется не больше последних секунд. */
    @Synchronized
    fun flush() {
        if (!isRecording) return
        try {
            txt?.flush()
            jsonl?.flush()
        } catch (e: IOException) {
            isRecording = false
            closeAll()
            publish("Ошибка записи: ${e.message}")
        }
    }

    /** Удаляет файлы прошлых сессий (текущая запись не трогается). */
    @Synchronized
    fun deleteFinished() {
        if (isRecording) return
        existingFiles().forEach { it.delete() }
        publish(null)
    }

    private fun write(writer: BufferedWriter?, line: String) {
        if (!isRecording || writer == null) return
        try {
            writer.write(line)
            writer.newLine()
            bytes += line.length + 1
            if (bytes - lastPublishedBytes > PUBLISH_STEP_BYTES) publish(null)
        } catch (e: IOException) {
            isRecording = false
            closeAll()
            publish("Ошибка записи: ${e.message}")
        }
    }

    private fun closeAll() {
        listOf(txt, jsonl).forEach { runCatching { it?.close() } }
        txt = null
        jsonl = null
    }

    private fun existingFiles(): List<File> =
        directory.listFiles()?.filter { it.isFile }?.sortedByDescending { it.name }.orEmpty()

    private fun publish(error: String?) {
        txt?.flush()
        jsonl?.flush()
        lastPublishedBytes = bytes
        val files = existingFiles()
        _state.value = RawLogState(isRecording, files, files.sumOf { it.length() }, error)
    }

    private companion object {
        const val PUBLISH_STEP_BYTES = 64 * 1024L
    }
}
