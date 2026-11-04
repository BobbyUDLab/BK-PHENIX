package ru.bkphenix.app.ui.track

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn
import ru.bkphenix.app.data.track.TrackRecorder
import ru.bkphenix.app.data.track.TrackRecordingState
import ru.bkphenix.app.data.track.TrackSummary
import java.io.File

class TrackViewModel(private val recorder: TrackRecorder) : ViewModel() {
    val recording: StateFlow<TrackRecordingState> = recorder.state
    val tracks: StateFlow<List<TrackSummary>> =
        recorder.tracks.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    fun toggle() = if (recorder.state.value.recording) recorder.stop() else recorder.start()

    fun delete(id: Long) = recorder.delete(id)

    suspend fun export(id: Long): File? = recorder.exportGpx(id)
}
