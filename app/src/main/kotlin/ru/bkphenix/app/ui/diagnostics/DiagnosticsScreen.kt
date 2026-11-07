package ru.bkphenix.app.ui.diagnostics

import android.content.ClipData
import android.content.Context
import android.content.Intent
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
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
import ru.bkphenix.app.data.EpochDiagnostics
import ru.bkphenix.app.data.NavigationStats
import ru.bkphenix.app.data.model.MeasurementSummary
import ru.bkphenix.core.PhenixSolution
import ru.bkphenix.core.roads.MatchResult
import ru.bkphenix.core.terrain.VerticalCheck
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

private const val MAX_ROWS = 30

@Composable
fun DiagnosticsScreen(viewModel: DiagnosticsViewModel) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val log by viewModel.logState.collectAsStateWithLifecycle()
    val rinex by viewModel.rinexInfo.collectAsStateWithLifecycle()
    val cellDb by viewModel.cellDatabase.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val solution by viewModel.solution.collectAsStateWithLifecycle()
    val epoch by viewModel.epoch.collectAsStateWithLifecycle()
    val vertical by viewModel.vertical.collectAsStateWithLifecycle()
    val road by viewModel.road.collectAsStateWithLifecycle()
    val measurements by viewModel.measurements.collectAsStateWithLifecycle()
    val navigation by viewModel.navigation.collectAsStateWithLifecycle()
    val learned by viewModel.learnedCells.collectAsStateWithLifecycle()
    val learnedMessage by viewModel.learnedMessage.collectAsStateWithLifecycle()
    val exportLearned = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/octet-stream")) { uri ->
        uri?.let { u -> viewModel.exportLearned { context.contentResolver.openOutputStream(u) } }
    }
    val importLearned = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        uri?.let { u -> viewModel.importLearned { context.contentResolver.openInputStream(u) } }
    }
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
        item { CoreDecisionCard(solution) }
        item { GnssIntegrityCard(solution, measurements, navigation) }
        item { MotionCard(epoch) }
        item { ObservationsCard(solution, epoch) }
        item { HeightRoadCard(vertical, road) }
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
            SectionCard("База сотовых вышек") {
                val c = cellDb
                if (c == null) {
                    EmptyHint("Не загружена")
                } else {
                    InfoRow("Сот", c.cells.toString())
                    InfoRow("Регион", c.region)
                    InfoRow("Данные", c.source)
                }
                // Своя база: учится на надёжных эпохах, раньше офлайн-базы при поиске.
                InfoRow("Своя база, сот", learned.toString())
                val fix = epoch?.cellFix
                InfoRow(
                    "Позиция по вышкам",
                    fix?.let { f ->
                        "σ ${f.accuracyM.fmt(0)} м · ${ageS(f.timestampMillis, epoch!!.timeMillis)}" +
                            (solution?.point?.let { " · до итога ${it.distanceTo(f.point).fmt(0)} м" } ?: "")
                    } ?: "нет (соты не найдены в базах)",
                )
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedButton(onClick = { exportLearned.launch("bk-phenix-cells.bklc") }) { Text("Экспорт") }
                    OutlinedButton(onClick = { importLearned.launch(arrayOf("*/*")) }) { Text("Импорт") }
                }
                learnedMessage?.let { Text(it) }
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
                            c.mnc?.let { "MNC $it" },
                            c.areaCode?.let { "TAC/LAC $it" },
                            c.cellId?.let { "CI $it" },
                            c.ageMillis?.let { "возраст ${(it / 1000)} с" },
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


private fun ageS(t: Long, now: Long): String = "${((now - t) / 1000).coerceAtLeast(0)} с назад"

private fun Double?.m(): String = this?.let { "${it.fmt(0)} м" } ?: "—"

/** Решение ядра целиком: состояние и все причины, итог, принятые и отброшенные, независимые голоса, якорь. */
@Composable
private fun CoreDecisionCard(s: PhenixSolution?) {
    SectionCard("Решение ядра") {
        if (s == null) { EmptyHint("Нет решения"); return@SectionCard }
        InfoRow("Целостность", "${s.integrity.state.name}\n${s.integrity.state.description}")
        s.integrity.reasons.forEach { EmptyHint("• $it") }
        InfoRow("Итог", s.point?.let { "${it.latitude.fmt(6)}, ${it.longitude.fmt(6)}" + (it.altitude?.let { h -> " · h ${h.fmt(0)} м" } ?: "") })
        InfoRow("σ итога", s.accuracyM.m())
        val c = s.comparison
        InfoRow("Оценка компаратора", c.estimate?.let { "${it.latitude.fmt(6)}, ${it.longitude.fmt(6)} · σ ${c.accuracyM.m()}" })
        InfoRow("Независимых голосов", "${c.independentConfirmations}: " + c.votes.joinToString("; ") { v -> v.groups.joinToString("+") { it.displayName } })
        c.accepted.forEach { o ->
            InfoRow("✓ ${o.source.displayName}", "σ ${o.accuracyM.fmt(0)} м" + (s.point?.let { " · Δ ${it.distanceTo(o.point).fmt(0)} м" } ?: "") + if (o.gnssDerived) " · от ГНСС" else "")
        }
        c.rejected.forEach { o ->
            InfoRow("✗ ${o.source.displayName}", "σ ${o.accuracyM.fmt(0)} м" + (s.point?.let { " · Δ ${it.distanceTo(o.point).fmt(0)} м" } ?: "") + if (o.gnssDerived) " · от ГНСС" else "")
        }
        val a = s.anchor
        InfoRow("Якорь", a.state.name + (a.anchor?.let { if (it.isAuto) " · авто (${it.basis ?: "—"})" else " · ручной" } ?: ""))
        a.anchor?.let { InfoRow("Точка якоря", "${it.point.latitude.fmt(6)}, ${it.point.longitude.fmt(6)} · σ ${it.accuracyM.fmt(0)} м") }
        InfoRow("Отклонение / допуск", "${a.deviationM.m()} / ${a.allowedDeviationM.m()}")
        InfoRow("Свежесть", listOfNotNull(a.freshness?.label, a.ageSeconds?.let { "${(it / 60).toInt()} мин" }, a.traveledM?.let { "путь ${it.fmt(0)} м" }, a.propagatedSigmaM?.let { "σ ${it.fmt(0)} м" }).joinToString(" · ").ifEmpty { null })
        a.message?.let { EmptyHint(it) }
    }
}

/** ГНСС: RAIM (статистика, порог, HPL, исключённые), собственное решение против итога, эфемериды, все детекторы подмены. */
@Composable
private fun GnssIntegrityCard(s: PhenixSolution?, m: MeasurementSummary?, nav: NavigationStats) {
    SectionCard("ГНСС: RAIM и детекторы подмены") {
        InfoRow("Сырые измерения", m?.let { "${it.count} (фаза ${it.withCarrierPhase}) · ${it.bands.joinToString { b -> b.name }}" } ?: "нет")
        InfoRow("АРУ", m?.agcDb?.let { "${it.fmt(1)} дБ" })
        InfoRow("Навигационные слова", "${nav.words} · ошибок чётности ${nav.parityFailures}")
        val g = s?.gnss
        if (g == null) { EmptyHint("Нет контроля по сырым измерениям в этой эпохе"); return@SectionCard }
        InfoRow("Эфемерид", "${g.ephemerisCount} (RINEX ${g.externalEphemerisCount}) · " + g.ephemerisBySystem.entries.joinToString { "${it.key.displayName} ${it.value}" })
        InfoRow("Псевдодальностей", g.pseudorangeCount.toString())
        val r = g.raim
        InfoRow("RAIM", r?.let { "${it.status.name} · χ² ${it.testStatistic?.fmt(1) ?: "—"} / ${it.threshold?.fmt(1) ?: "—"}" })
        InfoRow("HPL", r?.horizontalProtectionLevelM.m())
        r?.excluded?.forEach { InfoRow("Исключён ${it.sat.label}", "невязка ${it.residualM.fmt(0)} м") }
        g.ownSolution?.let { w ->
            InfoRow("Своё решение WLS", "${w.point.latitude.fmt(6)}, ${w.point.longitude.fmt(6)} · спутников ${w.used.size}" +
                (s?.point?.let { " · до итога ${it.distanceTo(w.point).fmt(0)} м" } ?: ""))
            if (w.interSystemBiasesM.isNotEmpty()) InfoRow("Межсистемные смещения", w.interSystemBiasesM.entries.joinToString { "${it.key.displayName} ${it.value.fmt(0)} м" })
        }
        g.detectors.forEach { d ->
            InfoRow(d.kind.name, "${d.verdict.name}\n${d.detail}")
        }
    }
}

/** Режим, запреты ГНСС (высота, движение), датчики движения, компас и счисление пути. */
@Composable
private fun MotionCard(e: EpochDiagnostics?) {
    SectionCard("Движение, запреты ГНСС, счисление") {
        if (e == null) { EmptyHint("Нет эпох"); return@SectionCard }
        InfoRow("Режим", e.motionMode.displayName)
        InfoRow("Запрет ГНСС по высоте", e.altitudeVeto.yesNo())
        InfoRow("Запрет ГНСС по движению", e.motionVeto.yesNo())
        e.motionReasons.forEach { EmptyHint("• $it") }
        InfoRow("Скорость ГНСС за окно", e.gnssSpeedMps?.let { "${(it * 3.6).fmt(1)} км/ч" })
        e.motion?.let { m ->
            InfoRow("Шаги за 10 с", "${m.steps} · ${m.cadenceHz.fmt(2)} Гц · ритм ${m.stepIntervalCv?.fmt(2) ?: "—"}" + if (m.walking) " · ходьба" else "")
            InfoRow("Покой", "${m.stationaryS.fmt(0)} с")
            InfoRow("Поворот гироскопа", m.yawChangeDeg?.let { "${it.fmt(0)}°" })
        }
        InfoRow("Компас (истинный курс)", e.compassHeadingDeg?.let { "${it.fmt(0)}°" } ?: "нет магнитометра")
        val d = e.deadReckoning
        InfoRow("Счисление", d?.let {
            "σ ${it.sigmaM.fmt(0)} м · ${(it.speedMps * 3.6).fmt(1)} км/ч · курс ${it.bearingDeg?.fmt(0) ?: "—"}° · " +
                "без фикса ${it.sinceFixMillis / 1000} с" + (if (it.stationary) " · покой" else "") +
                if (e.deadReckoningIndependent) " · привязка независима" else " · привязка от ГНСС"
        } ?: "не инициализировано")
    }
}

/** Свежие наблюдения всех источников перед сравнением: σ, возраст и расстояние до итога. */
@Composable
private fun ObservationsCard(s: PhenixSolution?, e: EpochDiagnostics?) {
    SectionCard("Наблюдения источников (${e?.observations?.size ?: 0})") {
        val obs = e?.observations.orEmpty()
        if (obs.isEmpty()) EmptyHint("Нет свежих наблюдений")
        obs.forEach { o ->
            InfoRow(
                o.source.displayName,
                "σ ${o.accuracyM.fmt(0)} м · ${ageS(o.timestampMillis, e!!.timeMillis)}" +
                    (s?.point?.let { " · Δ ${it.distanceTo(o.point).fmt(0)} м" } ?: "") +
                    "\n${o.point.latitude.fmt(5)}, ${o.point.longitude.fmt(5)}",
            )
        }
    }
}

@Composable
private fun HeightRoadCard(v: VerticalCheck?, r: MatchResult?) {
    SectionCard("Высота и дороги") {
        InfoRow("ГНСС над геоидом", v?.gnssOrthometricM.m())
        InfoRow("Барометр", v?.barometric?.let { "${it.heightM.fmt(0)} м ± ${it.sigmaM.fmt(0)} (${it.source.displayName})" })
        InfoRow("Рельеф (DSM)", v?.terrainM.m())
        InfoRow("Геоид N", v?.geoidM.m())
        InfoRow("Опора", v?.referenceM?.let { "${it.fmt(0)} м ± ${v?.referenceSigmaM?.fmt(0) ?: "—"}" })
        InfoRow("Расхождение / порог", "${v?.residualM.m()} / ${v?.thresholdM.m()}")
        InfoRow("До дороги", r?.let { (it.distanceToRoadM?.let { d -> "${d.fmt(0)} м" } ?: "дальше поиска") + " (порог ${it.offRoadThresholdM.fmt(0)} м)" + if (it.offRoad) " · ВНЕ ДОРОГ" else "" })
    }
}
