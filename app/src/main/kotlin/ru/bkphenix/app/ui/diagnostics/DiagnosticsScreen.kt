package ru.bkphenix.app.ui.diagnostics

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import ru.bkphenix.app.data.model.SensorKind
import ru.bkphenix.app.data.source.SensorDataSource
import ru.bkphenix.app.ui.components.EmptyHint
import ru.bkphenix.app.ui.components.InfoRow
import ru.bkphenix.app.ui.components.SectionCard
import ru.bkphenix.app.ui.components.fmt
import ru.bkphenix.app.ui.components.label
import ru.bkphenix.app.ui.components.yesNo

private const val MAX_ROWS = 15

@Composable
fun DiagnosticsScreen(viewModel: DiagnosticsViewModel) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    LazyColumn(
        Modifier.fillMaxSize().padding(horizontal = 16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        item {
            SectionCard("Источники") {
                state.statuses.forEach { (name, status) -> InfoRow(name, status.label()) }
            }
        }
        item {
            SectionCard("GnssCapabilities") {
                val c = state.capabilities
                InfoRow("Сырые измерения", c?.measurements.yesNo())
                InfoRow("Навигационные сообщения", c?.navigationMessages.yesNo())
                InfoRow("Данные антенны", c?.antennaInfo.yesNo())
                InfoRow("Коррекции измерений", c?.measurementCorrections.yesNo())
                InfoRow("Фаза несущей (ADR)", c?.accumulatedDeltaRange.yesNo())
            }
        }
        item {
            SectionCard("Сотовые вышки (${state.cells.size})") {
                if (state.cells.isEmpty()) EmptyHint("Нет данных CellInfo")
                state.cells.take(MAX_ROWS).forEach { c ->
                    InfoRow(
                        "${c.technology}${if (c.isServing) " ★" else ""} PCI ${c.pci ?: "—"}",
                        listOfNotNull(
                            c.signalDbm?.let { "$it дБм" },
                            c.timingAdvance?.let { "TA $it" },
                            c.cellId?.let { "CI $it" },
                        ).joinToString(" · "),
                    )
                }
            }
        }
        item {
            SectionCard("Wi-Fi (${state.wifi.size})") {
                if (state.wifi.isEmpty()) EmptyHint("Нет результатов сканирования")
                state.wifi.take(MAX_ROWS).forEach { w ->
                    InfoRow(
                        w.ssid ?: w.bssid,
                        "${w.rssiDbm} дБм · ${w.frequencyMhz} МГц" +
                            (w.rttDistanceM?.let { " · RTT ${it.fmt(1)} м" } ?: if (w.supportsRtt) " · RTT" else ""),
                    )
                }
            }
        }
        item {
            SectionCard("BLE (${state.ble.size})") {
                if (state.ble.isEmpty()) EmptyHint("Маяки не найдены")
                state.ble.take(MAX_ROWS).forEach { b ->
                    InfoRow(b.name ?: b.address, "${b.rssiDbm} дБм" + (b.txPowerDbm?.let { " · Tx $it" } ?: ""))
                }
            }
        }
        item {
            SectionCard("Датчики") {
                SensorKind.entries.forEach { kind ->
                    val s = state.sensors[kind]
                    val value = s?.values?.let { v ->
                        if (kind == SensorKind.BAROMETER) {
                            val p = v.firstOrNull() ?: 0f
                            "${p.toDouble().fmt(1)} ${kind.unit} · ${SensorDataSource.pressureAltitudeM(p).toDouble().fmt(0)} м"
                        } else {
                            v.take(3).joinToString(" ") { it.toDouble().fmt(2) } + " ${kind.unit}"
                        }
                    }
                    InfoRow(kind.displayName, value ?: "Нет данных")
                }
            }
        }
        item {
            SectionCard("Время") {
                val t = state.networkTime
                InfoRow("Сетевое время − системное", t?.let { "${it.offsetMillis} ± ${it.uncertaintyMillis} мс" } ?: "Недоступно")
                t?.let { InfoRow("Способ", it.method) }
            }
        }
    }
}
