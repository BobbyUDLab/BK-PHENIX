package ru.bkphenix.app.ui.server

import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.selection.selectable
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import ru.bkphenix.app.BuildConfig
import ru.bkphenix.app.PhenixApplication
import ru.bkphenix.app.data.server.LoginPhase
import ru.bkphenix.app.data.server.ResourceRow
import ru.bkphenix.app.data.server.ServerState
import ru.bkphenix.app.data.server.ServerSync
import ru.bkphenix.app.data.server.UpdatePhase
import ru.bkphenix.app.data.server.UploadContent
import ru.bkphenix.app.ui.components.EmptyHint
import ru.bkphenix.app.ui.components.InfoRow
import ru.bkphenix.app.ui.components.SectionCard
import java.util.Locale

/** Карточка «Сервер»: подключение, выгрузка журналов и треков, ресурсы, обновление приложения. */
@Composable
fun ServerCard() {
    val context = LocalContext.current
    val sync = (context.applicationContext as PhenixApplication).container.serverSync
    val state by sync.state.collectAsStateWithLifecycle()
    var showSetup by remember { mutableStateOf(false) }
    var confirmDisconnect by remember { mutableStateOf(false) }

    SectionCard("Сервер") {
        if (!state.connected) {
            LoginBlock(state, sync, context)
        } else {
            InfoRow("Сервер", state.server.removePrefix("https://"))
            InfoRow("Пользователь", state.loginName)
            HorizontalDivider()
            UploadBlock(state, sync) { showSetup = true }
            HorizontalDivider()
            ResourcesBlock(state, sync)
            HorizontalDivider()
            UpdateBlock(state, sync, context)
            HorizontalDivider()
            TextButton(onClick = { confirmDisconnect = true }) { Text("Отключить телефон от сервера") }
        }
    }

    if (state.needsSetup || showSetup) {
        SetupDialog(state, onDone = { content, trim, wifi ->
            sync.completeSetup(content, trim, wifi)
            showSetup = false
        }, onDismiss = { if (!state.needsSetup) showSetup = false })
    }
    if (confirmDisconnect) {
        AlertDialog(
            onDismissRequest = { confirmDisconnect = false },
            title = { Text("Отключить от сервера?") },
            text = { Text("Пароль приложения этого телефона будет отозван на сервере. Журналы на телефоне останутся; неотправленная очередь сохранится до нового подключения.") },
            confirmButton = { TextButton(onClick = { confirmDisconnect = false; sync.disconnect() }) { Text("Отключить") } },
            dismissButton = { TextButton(onClick = { confirmDisconnect = false }) { Text("Отмена") } },
        )
    }
}

@Composable
private fun LoginBlock(state: ServerState, sync: ServerSync, context: Context) {
    var url by rememberSaveable { mutableStateOf(state.server) }
    when (val phase = state.login) {
        is LoginPhase.Waiting -> {
            LaunchedEffect(phase.loginUrl) { openUrl(context, phase.loginUrl) }
            Text("Подтвердите вход в открывшемся браузере под пользователем для телефонов (bkphenix). Пароль приложения сервер выдаст этому телефону сам.")
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(onClick = { openUrl(context, phase.loginUrl) }) { Text("Открыть ещё раз") }
                TextButton(onClick = sync::cancelLogin) { Text("Отмена") }
            }
        }
        else -> {
            EmptyHint("Журналы и треки выгружаются на сервер владельца, оттуда же загружаются карта, ресурсы и обновления. Без сервера приложение работает полностью офлайн.")
            OutlinedTextField(
                value = url,
                onValueChange = { url = it },
                label = { Text("Адрес сервера") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
            )
            if (phase is LoginPhase.Failed) Text("Ошибка: ${phase.message}", color = MaterialTheme.colorScheme.error)
            Button(onClick = { sync.startLogin(url) }) { Text("Подключить") }
        }
    }
}

@Composable
private fun UploadBlock(state: ServerState, sync: ServerSync, onEditSettings: () -> Unit) {
    Text("Выгрузка", style = MaterialTheme.typography.titleSmall)
    InfoRow("Что", if (state.content == UploadContent.LOGS_AND_TRACKS) "журналы и треки" else "только треки")
    InfoRow("Начало и конец трека", if (state.trimTrackEnds) "скрываются (300 м)" else "полностью")
    InfoRow("Сеть", if (state.wifiOnly) "только Wi-Fi" else "любая")
    InfoRow("В очереди", if (state.uploading) "${state.pending} · выгрузка…" else "${state.pending}")
    state.uploadMessage?.let { EmptyHint(it) }
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        Button(onClick = sync::uploadNow, enabled = !state.uploading && state.pending > 0) { Text("Выгрузить") }
        OutlinedButton(onClick = sync::backupLearnedCells, enabled = !state.uploading) { Text("Копия базы вышек") }
    }
    TextButton(onClick = onEditSettings) { Text("Изменить настройки выгрузки") }
}

@Composable
private fun ResourcesBlock(state: ServerState, sync: ServerSync) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Text("Карта и ресурсы", style = MaterialTheme.typography.titleSmall, modifier = Modifier.weight(1f))
        TextButton(onClick = sync::refreshResources) { Text("Проверить") }
    }
    state.resources.forEach { row -> ResourceLine(row) { sync.downloadResource(row.kind) } }
    state.resourcesMessage?.let { EmptyHint(it) }
}

@Composable
private fun ResourceLine(row: ResourceRow, onDownload: () -> Unit) {
    val e = row.entry
    val status = when {
        row.progress != null -> "загрузка ${(row.progress * 100).toInt()} %"
        row.upToDate -> "актуально (${e!!.version})"
        e != null && row.presentLocally -> "на сервере ${e.version}, ${size(e.size)}"
        e != null -> "не установлено; на сервере ${size(e.size)}"
        row.presentLocally -> "установлено вручную"
        else -> "нет"
    }
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Text(row.title, style = MaterialTheme.typography.bodyMedium)
            Text(status, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        if (e != null && !row.upToDate && row.progress == null) {
            OutlinedButton(onClick = onDownload) { Text(if (row.presentLocally) "Обновить" else "Скачать") }
        }
    }
    row.progress?.let { p -> LinearProgressIndicator(progress = { p }, modifier = Modifier.fillMaxWidth()) }
}

@Composable
private fun UpdateBlock(state: ServerState, sync: ServerSync, context: Context) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Text("Обновление приложения", style = MaterialTheme.typography.titleSmall, modifier = Modifier.weight(1f))
        TextButton(onClick = sync::checkUpdate) { Text("Проверить") }
    }
    InfoRow("Установлено", "${BuildConfig.VERSION_NAME} (${BuildConfig.VERSION_CODE})")
    if (!state.installedIsRelease) {
        EmptyHint("Установлена сборка с отладочной подписью. Обновления по воздуху ставятся поверх только release-сборки: один раз удалите приложение и установите release-APK (перед этим выгрузите журналы и копию базы вышек).")
    }
    when (val u = state.update) {
        UpdatePhase.Unknown -> {}
        UpdatePhase.Checking -> EmptyHint("Проверка…")
        UpdatePhase.UpToDate -> EmptyHint("Установлена последняя версия")
        is UpdatePhase.Available -> {
            InfoRow("Доступно", "${u.manifest.versionName} (${u.manifest.versionCode}), ${size(u.manifest.size)}")
            if (u.manifest.notes.isNotBlank()) EmptyHint(u.manifest.notes)
            Button(onClick = sync::downloadUpdate) { Text("Скачать") }
        }
        is UpdatePhase.Downloading -> {
            InfoRow("Загрузка", "${(u.progress * 100).toInt()} %")
            LinearProgressIndicator(progress = { u.progress }, modifier = Modifier.fillMaxWidth())
        }
        is UpdatePhase.ReadyToInstall -> {
            InfoRow("Готово к установке", "${u.manifest.versionName}: подпись и контрольная сумма проверены")
            Button(onClick = { sync.installUpdate(context) }) { Text("Установить") }
        }
        is UpdatePhase.Failed -> Text("Ошибка: ${u.message}", color = MaterialTheme.colorScheme.error)
    }
    state.updateHint?.let { EmptyHint(it) }
}

@Composable
private fun SetupDialog(state: ServerState, onDone: (UploadContent, Boolean, Boolean) -> Unit, onDismiss: () -> Unit) {
    var content by remember { mutableStateOf(state.content) }
    var trim by remember { mutableStateOf(state.trimTrackEnds) }
    var wifi by remember { mutableStateOf(state.wifiOnly) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Настройка выгрузки") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Text("Что выгружать после записи", style = MaterialTheme.typography.titleSmall)
                Choice("Журналы и треки (полностью)", content == UploadContent.LOGS_AND_TRACKS) { content = UploadContent.LOGS_AND_TRACKS }
                Choice("Только треки", content == UploadContent.TRACKS_ONLY) { content = UploadContent.TRACKS_ONLY }
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("Скрывать начало и конец трека (300 м)", modifier = Modifier.weight(1f))
                    Switch(checked = trim, onCheckedChange = { trim = it })
                }
                Text("Сеть для выгрузки", style = MaterialTheme.typography.titleSmall)
                Choice("Только Wi-Fi", wifi) { wifi = true }
                Choice("Любая, включая мобильную", !wifi) { wifi = false }
                EmptyHint("Журналы содержат места пребывания. Они уходят только на сервер владельца по HTTPS.")
            }
        },
        confirmButton = { TextButton(onClick = { onDone(content, trim, wifi) }) { Text("Сохранить") } },
    )
}

@Composable
private fun Choice(label: String, selected: Boolean, onClick: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().selectable(selected = selected, onClick = onClick),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        RadioButton(selected = selected, onClick = onClick)
        Text(label)
    }
}

private fun openUrl(context: Context, url: String) {
    runCatching { context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }
}

private fun size(bytes: Long): String = when {
    bytes >= 1L shl 30 -> String.format(Locale.ROOT, "%.1f ГБ", bytes / (1L shl 30).toDouble())
    bytes >= 1L shl 20 -> String.format(Locale.ROOT, "%.0f МБ", bytes / (1L shl 20).toDouble())
    else -> "${bytes / 1024} КБ"
}
