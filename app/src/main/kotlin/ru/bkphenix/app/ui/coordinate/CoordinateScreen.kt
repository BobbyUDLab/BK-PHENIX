package ru.bkphenix.app.ui.coordinate

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import ru.bkphenix.app.R
import ru.bkphenix.app.ui.theme.BkPhenixTheme
import ru.bkphenix.core.PhenixCore
import ru.bkphenix.core.PhenixSolution
import ru.bkphenix.core.anchor.AnchorState
import ru.bkphenix.core.geo.GeoPoint
import ru.bkphenix.core.model.PositionObservation
import ru.bkphenix.core.model.PositionSource
import java.util.Locale

/** Заглушка экрана «Координата»: показывает решение ядра на демонстрационных данных. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun CoordinateScreen(solution: PhenixSolution = remember { demoSolution() }) {
    Scaffold(topBar = { TopAppBar(title = { Text(stringResource(R.string.screen_coordinate)) }) }) { padding ->
        Column(
            modifier = Modifier.fillMaxSize().padding(padding).padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Card(modifier = Modifier.fillMaxWidth()) {
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    InfoRow(R.string.label_latitude, solution.point?.latitude?.format(6))
                    InfoRow(R.string.label_longitude, solution.point?.longitude?.format(6))
                    InfoRow(
                        R.string.label_accuracy,
                        solution.accuracyM?.let { stringResource(R.string.accuracy_meters, it.format(1)) },
                    )
                }
            }
            Card(modifier = Modifier.fillMaxWidth()) {
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    InfoRow(R.string.label_integrity, solution.integrity.state.description)
                    InfoRow(R.string.label_anchor, stringResource(solution.anchor.state.label()))
                    InfoRow(
                        R.string.label_sources,
                        solution.comparison.accepted.joinToString { it.source.displayName },
                    )
                }
            }
            Button(onClick = { /* Этап 4: якорь на карте */ }, enabled = false) {
                Text(stringResource(R.string.action_set_anchor))
            }
            Text(
                stringResource(R.string.stub_notice),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
private fun InfoRow(labelRes: Int, value: String?) {
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
        Text(stringResource(labelRes), style = MaterialTheme.typography.bodyMedium)
        Text(value ?: stringResource(R.string.value_unknown), style = MaterialTheme.typography.titleMedium)
    }
}

private fun AnchorState.label(): Int = when (this) {
    AnchorState.NO_ANCHOR -> R.string.anchor_none
    AnchorState.HUMAN_ANCHOR -> R.string.anchor_human
    AnchorState.ANCHOR_CONFLICT -> R.string.anchor_conflict
}

private fun Double.format(digits: Int): String = String.format(Locale.ROOT, "%.${digits}f", this)

private fun demoSolution(): PhenixSolution {
    val now = System.currentTimeMillis()
    val point = GeoPoint(55.753930, 37.620795)
    return PhenixCore().solve(
        listOf(
            PositionObservation(PositionSource.GNSS, point.offset(4.0, 90.0), 6.0, now),
            PositionObservation(PositionSource.WIFI_RSSI, point.offset(12.0, 200.0), 25.0, now),
            PositionObservation(PositionSource.CELL, point.offset(90.0, 10.0), 180.0, now),
        ),
        now,
    )
}

@Preview(showBackground = true, locale = "ru")
@Composable
private fun CoordinateScreenPreview() {
    BkPhenixTheme { CoordinateScreen() }
}
