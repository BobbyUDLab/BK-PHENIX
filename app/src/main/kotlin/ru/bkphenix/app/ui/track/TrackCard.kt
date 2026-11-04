package ru.bkphenix.app.ui.track

import android.content.Context
import android.content.Intent
import android.widget.Toast
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.material3.Button
import androidx.compose.material3.TextButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.core.content.FileProvider
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import kotlinx.coroutines.launch
import ru.bkphenix.app.R
import ru.bkphenix.app.ui.ViewModelFactory
import ru.bkphenix.app.ui.components.EmptyHint
import ru.bkphenix.app.ui.components.InfoRow
import ru.bkphenix.app.ui.components.SectionCard
import java.io.File

/** Запись трека и список сохранённых треков с экспортом в GPX. */
@Composable
fun TrackCard(viewModel: TrackViewModel = viewModel(factory = ViewModelFactory.instance)) {
    val recording by viewModel.recording.collectAsStateWithLifecycle()
    val tracks by viewModel.tracks.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    SectionCard(stringResource(R.string.section_track)) {
        if (recording.recording) InfoRow(stringResource(R.string.track_recording), stringResource(R.string.track_points, recording.points))
        Button(onClick = viewModel::toggle) {
            Text(stringResource(if (recording.recording) R.string.action_track_stop else R.string.action_track_start))
        }
        if (tracks.isEmpty()) EmptyHint(stringResource(R.string.track_empty))
        tracks.take(MAX_SHOWN).forEach { t ->
            InfoRow(t.name, stringResource(R.string.track_points, t.pointCount))
            if (t.id != recording.trackId) {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    TextButton(onClick = {
                        scope.launch {
                            val file = viewModel.export(t.id)
                            if (file == null) Toast.makeText(context, R.string.track_export_empty, Toast.LENGTH_SHORT).show()
                            else shareGpx(context, file)
                        }
                    }) { Text(stringResource(R.string.action_track_export)) }
                    TextButton(onClick = { viewModel.delete(t.id) }) { Text(stringResource(R.string.action_delete)) }
                }
            }
        }
    }
}

private const val MAX_SHOWN = 10

private fun shareGpx(context: Context, file: File) {
    val uri = FileProvider.getUriForFile(context, "${context.packageName}.logs", file)
    val intent = Intent(Intent.ACTION_SEND).apply {
        type = "application/gpx+xml"
        putExtra(Intent.EXTRA_STREAM, uri)
        addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
    }
    context.startActivity(Intent.createChooser(intent, file.name))
}
