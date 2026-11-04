package ru.bkphenix.app.ui.diagnostics

import android.content.ClipData
import android.content.Context
import android.content.Intent
import android.os.Build
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material3.Button
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.core.content.FileProvider
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import ru.bkphenix.app.BuildConfig
import ru.bkphenix.app.data.model.SensorKind
import ru.bkphenix.app.data.source.SensorDataSource
import ru.bkphenix.app.ui.components.EmptyHint
import ru.bkphenix.app.ui.components.InfoRow
import ru.bkphenix.app.ui.components.SectionCard
import ru.bkphenix.app.ui.components.fmt
import ru.bkphenix.app.ui.components.label
import ru.bkphenix.app.ui.components.yesNo
import ru.bkphenix.app.ui.theme.ThemeCard
import java.io.File
import java.util.Locale

private const val MAX_ROWS = 15

@Composable
fun DiagnosticsScreen(viewModel: DiagnosticsViewModel) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val log by viewModel.logState.collectAsStateWithLifecycle()
    val rinex by viewModel.rinexInfo.collectAsStateWithLifecycle()
    val context = LocalContext.current
    LazyColumn(
        Modifier.fillMaxSize().padding(horizontal = 16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        item { ThemeCard() }
        item {
            SectionCard("О приложении") {
                InfoRow("Версия", "${BuildConfig.VERSION_NAME} (сборка ${BuildConfig.VERSION_CODE}, ${BuildConfig.BUILD_TYPE})")
                InfoRow("Android", "${Build.VERSION.RELEASE} (API ${Build.VERSION.SDK_INT})")
                InfoRow("Устройство", "${Build.MANUFACTURER} ${Build.MODEL}")
            }
        }
        item {
            SectionCard("Запись сырого лога") {
                InfoRow("Состояние", if (log.recording) "Идёт запись" else "Остановлена")
                InfoRow("Файлов", "${log.files.size} · ${formatBytes(log.bytes)}")
                log.error?.let { InfoRow("Ошибка", it) }
                EmptyHint("GnssLogger-совместимый txt (Raw/Fix/Status/Nav) и JSONL с сотами, датчиками и результатами ядра")
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Button(onClick = viewModel::toggleLog) { Text(if (log.recording) "Остановить" else "Начать запись") }
                    OutlinedButton(onClick = { shareLogs(context, log.files) }, enabled = log.files.isNotEmpty()) {
                        Text("Поделиться")
                    }
                    TextButton(onClick = viewModel::deleteLogs, enabled = !log.recording && log.files.isNotEmpty()) {
                        Text("Удалить")
                    }
                }
            }
        }
        item {
            SectionCard("Запасные эфемериды (RINEX)") {
                val r = rinex
                if (r == null) {
                    EmptyHint("Не загружены: нужна сеть. Используются, если чип не отдаёт навигационные сообщения")
                } else {
                    InfoRow("Спутников", "${r.satellites} (записей ${r.records})")
                    InfoRow("Системы", r.systems.ifEmpty { "—" })
                    InfoRow("Файл", "${formatUtc(r.fileTimeMillis)} · ${r.origin}")
                }
            }
        }
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

private fun formatBytes(bytes: Long): String = when {
    bytes >= 1_048_576 -> String.format(Locale.ROOT, "%.1f МБ", bytes / 1_048_576.0)
    bytes >= 1024 -> "${bytes / 1024} КБ"
    else -> "$bytes Б"
}

/** Отправляет файлы логов через системное «Поделиться» (почта, мессенджер, «Файлы»). */
private fun shareLogs(context: Context, files: List<File>) {
    val uris = ArrayList(files.map { FileProvider.getUriForFile(context, "${context.packageName}.logs", it) })
    val intent = Intent(Intent.ACTION_SEND_MULTIPLE).apply {
        type = "text/plain"
        putParcelableArrayListExtra(Intent.EXTRA_STREAM, uris)
        clipData = ClipData.newRawUri(null, uris.first()).also { clip ->
            uris.drop(1).forEach { clip.addItem(ClipData.Item(it)) }
        }
        addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
    }
    context.startActivity(Intent.createChooser(intent, "Логи BK-PHENIX"))
}

private fun formatUtc(millis: Long): String =
    java.time.format.DateTimeFormatter.ofPattern("dd.MM HH:mm 'UTC'", Locale.ROOT)
        .format(java.time.Instant.ofEpochMilli(millis).atOffset(java.time.ZoneOffset.UTC))
