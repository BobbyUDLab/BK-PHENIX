package ru.bkphenix.app.ui.satellites

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import ru.bkphenix.app.data.mapping.GnssMapping
import ru.bkphenix.app.ui.components.EmptyHint
import ru.bkphenix.app.ui.components.InfoRow
import ru.bkphenix.app.ui.components.SectionCard
import ru.bkphenix.app.ui.components.fmt
import ru.bkphenix.app.ui.components.label
import ru.bkphenix.app.ui.components.yesNo

@Composable
fun SatellitesScreen(viewModel: SatellitesViewModel) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    LazyColumn(
        Modifier.fillMaxSize().padding(horizontal = 16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        item {
            SectionCard("ГНСС-чип") {
                val caps = state.capabilities
                InfoRow("Модель", caps?.hardwareModel ?: "Не сообщается")
                InfoRow("Год оборудования", caps?.yearOfHardware?.toString())
                InfoRow("Двухчастотный приём (L5)", if (state.satellites.isEmpty()) null else state.dualFrequency.yesNo())
                InfoRow("Сырые измерения", caps?.measurements.yesNo())
                InfoRow("Навигационные сообщения", caps?.navigationMessages.yesNo())
                InfoRow("Фаза несущей (ADR)", caps?.accumulatedDeltaRange.yesNo())
            }
        }
        item {
            SectionCard("Сырые данные") {
                InfoRow("Измерения", state.measurementsStatus.label())
                state.measurements?.let { m ->
                    InfoRow("Каналов в эпохе", m.count.toString())
                    InfoRow("С фазой несущей", m.withCarrierPhase.toString())
                    InfoRow("АРУ", m.agcDb?.let { "${it.fmt(1)} дБ" })
                }
                InfoRow("Навигационные сообщения", state.navigationStatus.label())
                InfoRow("Слов GPS LNAV", state.navigation.words.toString())
                InfoRow("Ошибок чётности", state.navigation.parityFailures.toString())
            }
        }
        item {
            SectionCard("Созвездия") {
                if (state.summary.isEmpty()) EmptyHint("Спутники пока не видны")
                state.summary.forEach { s ->
                    InfoRow(
                        s.constellation.displayName,
                        "${s.used}/${s.visible} · ${s.bandLabels.joinToString("/")}" +
                            (s.meanCn0DbHz?.let { " · ${it.fmt(0)} дБГц" } ?: ""),
                    )
                }
            }
        }
        item { Text("Спутники", style = MaterialTheme.typography.titleMedium) }
        items(state.satellites, key = { "${it.constellation}-${it.svid}-${it.band}" }) { sat ->
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                Text("${sat.constellation.displayName} ${sat.svid}", Modifier.weight(1.4f))
                Text(GnssMapping.bandLabel(sat.band, sat.constellation), Modifier.weight(0.6f))
                Text("${sat.cn0DbHz.fmt(0)} дБГц", Modifier.weight(1f))
                Text("${sat.elevationDeg.fmt(0)}°", Modifier.weight(0.6f))
                Text(if (sat.usedInFix) "✓" else "", Modifier.weight(0.3f))
            }
        }
    }
}
