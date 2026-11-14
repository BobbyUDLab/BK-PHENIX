package ru.bkphenix.app.data.track

import ru.bkphenix.app.data.PhenixRepository
import java.io.File

/**
 * Одна кнопка «Запись»: трек (GPX) и полный журнал (GnssLogger txt + JSONL с решением ядра и окружением)
 * пишутся вместе — по журналу потом разбирается каждая точка трека. Отдельно журнал можно включить
 * в «Диагностике»; остановка с карты останавливает оба. По окончании записи [onFinished] получает
 * завершённый трек и файлы журнала (для выгрузки на сервер владельца).
 */
class RecordingController(
    private val recorder: TrackRecorder,
    private val repository: PhenixRepository,
    private val onFinished: (trackId: Long?, logFiles: List<File>) -> Unit = { _, _ -> },
) {
    val recording: Boolean get() = recorder.state.value.recording

    fun start() {
        recorder.start()
        if (!repository.logState.value.recording) repository.startLog()
    }

    fun stop() {
        val logWasRecording = repository.logState.value.recording
        if (logWasRecording) repository.stopLog()
        val logs = if (logWasRecording) repository.lastLogFiles else emptyList()
        // GPX пишется после того, как трек закрыт в базе, поэтому передача — из колбэка остановки.
        val id = recorder.stop { trackId -> onFinished(trackId, logs) }
        if (id == null && logs.isNotEmpty()) onFinished(null, logs)
    }

    fun toggle() = if (recording) stop() else start()
}
