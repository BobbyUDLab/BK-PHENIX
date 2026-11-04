package ru.bkphenix.app.ui.coordinate

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import ru.bkphenix.app.R
import ru.bkphenix.app.data.mapping.TrustLevel
import ru.bkphenix.app.ui.components.EmptyHint
import ru.bkphenix.app.ui.components.InfoRow
import ru.bkphenix.app.ui.components.SectionCard
import ru.bkphenix.app.ui.components.fmt
import ru.bkphenix.app.ui.track.TrackCard
import ru.bkphenix.core.anchor.AnchorState
import ru.bkphenix.core.integrity.IntegrityState

@Composable
fun CoordinateScreen(viewModel: CoordinateViewModel, locationGranted: Boolean, onRequestPermissions: () -> Unit) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    var showDialog by remember { mutableStateOf(false) }
    val solution = state.solution

    Column(
        Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        if (!locationGranted) {
            SectionCard(stringResource(R.string.permission_title)) {
                Text(stringResource(R.string.permission_rationale), style = MaterialTheme.typography.bodyMedium)
                Button(onClick = onRequestPermissions) { Text(stringResource(R.string.permission_grant)) }
            }
        }
        SectionCard(stringResource(R.string.section_position)) {
            InfoRow(stringResource(R.string.label_latitude), solution?.point?.latitude?.fmt(6))
            InfoRow(stringResource(R.string.label_longitude), solution?.point?.longitude?.fmt(6))
            InfoRow(
                stringResource(R.string.label_accuracy),
                solution?.accuracyM?.let { stringResource(R.string.accuracy_meters, it.fmt(1)) },
            )
            if (solution == null) EmptyHint(stringResource(if (state.running) R.string.waiting_data else R.string.not_running))
        }
        SectionCard(stringResource(R.string.label_integrity)) {
            val integrity = solution?.integrity
            InfoRow(
                stringResource(R.string.label_state),
                integrity?.state?.description,
                integrity?.state?.color() ?: Color.Unspecified,
            )
            integrity?.reasons?.forEach { EmptyHint("• $it") }
        }
        SectionCard(stringResource(R.string.label_anchor)) {
            val anchor = solution?.anchor
            InfoRow(stringResource(R.string.label_state), anchor?.state?.let { stringResource(it.label()) })
            anchor?.anchor?.let {
                InfoRow(stringResource(R.string.label_anchor_point), "${it.point.latitude.fmt(6)}, ${it.point.longitude.fmt(6)}")
            }
            anchor?.message?.let { EmptyHint(it) }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(onClick = { viewModel.anchorFromCurrent() }, enabled = solution?.comparison?.estimate != null) {
                    Text(stringResource(R.string.action_anchor_here))
                }
                OutlinedButton(onClick = { showDialog = true }) { Text(stringResource(R.string.action_anchor_manual)) }
            }
            if (anchor?.anchor != null) {
                TextButton(onClick = viewModel::clearAnchor) { Text(stringResource(R.string.action_anchor_clear)) }
            }
        }
        SectionCard(stringResource(R.string.label_sources)) {
            if (state.trust.isEmpty()) EmptyHint(stringResource(R.string.no_sources))
            state.solution?.let { sol ->
                val n = sol.comparison.independentConfirmations
                InfoRow(stringResource(R.string.label_independent), n.toString(), if (n >= 2) Ok else Warn)
            }
            state.trust.forEach { t ->
                InfoRow(
                    t.source.displayName,
                    "${t.level.displayName} · σ ${t.accuracyM.fmt(0)} м" + (t.deviationM?.let { " · Δ ${it.fmt(0)} м" } ?: "") +
                        "\n${t.groupsLabel}",
                    t.level.color(),
                )
            }
        }
        TrackCard()
    }

    if (showDialog) {
        ManualAnchorDialog(
            onDismiss = { showDialog = false },
            onSubmit = { lat, lon, acc ->
                when (val r = viewModel.anchorManual(lat, lon, acc)) {
                    is AnchorInputResult.Valid -> { showDialog = false; null }
                    is AnchorInputResult.Invalid -> r.message
                }
            },
        )
    }
}

@Composable
private fun ManualAnchorDialog(onDismiss: () -> Unit, onSubmit: (String, String, String) -> String?) {
    var lat by remember { mutableStateOf("") }
    var lon by remember { mutableStateOf("") }
    var acc by remember { mutableStateOf("") }
    var error by remember { mutableStateOf<String?>(null) }
    val keyboard = KeyboardOptions(keyboardType = KeyboardType.Decimal)
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.anchor_dialog_title)) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(lat, { lat = it }, label = { Text(stringResource(R.string.label_latitude)) }, singleLine = true, keyboardOptions = keyboard)
                OutlinedTextField(lon, { lon = it }, label = { Text(stringResource(R.string.label_longitude)) }, singleLine = true, keyboardOptions = keyboard)
                OutlinedTextField(acc, { acc = it }, label = { Text(stringResource(R.string.label_anchor_accuracy)) }, singleLine = true, keyboardOptions = keyboard)
                error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
            }
        },
        confirmButton = { TextButton(onClick = { error = onSubmit(lat, lon, acc) }) { Text(stringResource(R.string.action_save)) } },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.action_cancel)) } },
        modifier = Modifier.fillMaxWidth(),
    )
}

private fun AnchorState.label(): Int = when (this) {
    AnchorState.NO_ANCHOR -> R.string.anchor_none
    AnchorState.HUMAN_ANCHOR -> R.string.anchor_human
    AnchorState.ANCHOR_CONFLICT -> R.string.anchor_conflict
}

private val Ok = Color(0xFF2E9D5B)
private val Warn = Color(0xFFE0A000)
private val Bad = Color(0xFFD93A3A)

private fun IntegrityState.color(): Color = when (this) {
    IntegrityState.OK -> Ok
    IntegrityState.NO_DATA, IntegrityState.INCONSISTENT, IntegrityState.SUSPICIOUS -> Warn
    else -> Bad
}

private fun TrustLevel.color(): Color = when (this) {
    TrustLevel.HIGH -> Ok
    TrustLevel.MEDIUM -> Color.Unspecified
    TrustLevel.LOW -> Warn
    TrustLevel.REJECTED -> Bad
}
