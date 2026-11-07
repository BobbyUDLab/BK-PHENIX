package ru.bkphenix.app.data.track

import ru.bkphenix.app.data.PhenixRepository

/**
 * Одна кнопка «Запись»: трек (GPX) и полный журнал (GnssLogger txt + JSONL с решением ядра и окружением)
 * пишутся вместе — по журналу потом разбирается каждая точка трека. Отдельно журнал можно включить
 * в «Диагностике»; остановка с карты останавливает оба.
 */
class RecordingController(private val recorder: TrackRecorder, private val repository: PhenixRepository) {
    val recording: Boolean get() = recorder.state.value.recording

    fun start() {
        recorder.start()
        if (!repository.logState.value.recording) repository.startLog()
    }

    fun stop() {
        recorder.stop()
        if (repository.logState.value.recording) repository.stopLog()
    }

    fun toggle() = if (recording) stop() else start()
}
