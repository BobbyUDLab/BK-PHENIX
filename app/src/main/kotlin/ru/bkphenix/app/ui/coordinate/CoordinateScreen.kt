package ru.bkphenix.app.ui.coordinate

import ru.bkphenix.app.ui.theme.LocalStatusColors
import android.widget.Toast
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Card
import androidx.compose.material3.Surface
import androidx.compose.ui.Alignment
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import ru.bkphenix.core.PhenixSolution
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
import ru.bkphenix.core.geo.ErrorCircle
import ru.bkphenix.core.anchor.AnchorState
import ru.bkphenix.core.anchor.AnchorFreshness
import ru.bkphenix.core.integrity.IntegrityState
import ru.bkphenix.core.integrity.PointTrust
import ru.bkphenix.core.integrity.PointTrustLevel

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
        solution?.anchor?.takeIf { it.freshness == AnchorFreshness.MUST_UPDATE || (it.freshness == AnchorFreshness.CAN_UPDATE && it.anchor?.isAuto != true) }?.let { a ->
            // Заметная плашка без звука: якорь пора подтвердить заново.
            val must = a.freshness == AnchorFreshness.MUST_UPDATE
            SectionCard(a.freshness!!.label) {
                Text(a.freshnessReason ?: "", color = if (must) Bad else Warn, style = MaterialTheme.typography.bodyMedium)
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Button(onClick = { viewModel.anchorFromCurrent() }) { Text(stringResource(R.string.action_anchor_confirm_here)) }
                    OutlinedButton(onClick = { showDialog = true }) { Text(stringResource(R.string.action_anchor_manual)) }
                }
            }
        }
        PositionHero(solution, state.running)
        SectionCard(stringResource(R.string.section_decision)) {
            // Почему итог такой: состояние целостности, причины, кто подтвердил и кто отброшен.
            val integrity = solution?.integrity
            InfoRow(
                stringResource(R.string.label_state),
                integrity?.state?.description,
                integrity?.state?.color() ?: Color.Unspecified,
            )
            integrity?.reasons?.forEach { EmptyHint("• $it") }
            solution?.comparison?.let { c ->
                if (c.accepted.isNotEmpty()) InfoRow(stringResource(R.string.label_accepted), c.accepted.joinToString { it.source.displayName }, Ok)
                if (c.rejected.isNotEmpty()) InfoRow(stringResource(R.string.label_rejected), c.rejected.joinToString { it.source.displayName }, Bad)
            }
            InfoRow(
                stringResource(R.string.label_accuracy),
                solution?.accuracyM?.let { stringResource(R.string.accuracy_r95, ErrorCircle.radius(it).fmt(1), it.fmt(1)) },
            )
            InfoRow(
                stringResource(R.string.label_hpl),
                solution?.gnss?.raim?.horizontalProtectionLevelM?.let { stringResource(R.string.accuracy_meters, it.fmt(0)) },
            )
            solution?.gnss?.detectors?.filter { it.verdict == ru.bkphenix.core.gnss.DetectorVerdict.ALERT }?.forEach { d ->
                EmptyHint("• ${d.kind.name}: ${d.detail}")
            }
        }
        SectionCard(stringResource(R.string.label_anchor)) {
            val anchor = solution?.anchor
            InfoRow(stringResource(R.string.label_state), anchor?.state?.let { stringResource(it.label()) })
            anchor?.anchor?.takeIf { it.isAuto }?.let {
                // Автоякорь поставлен без участия человека — это видно сразу.
                val hhmm = java.text.SimpleDateFormat("HH:mm", java.util.Locale.ROOT).format(java.util.Date(it.timestampMillis))
                InfoRow("Тип", "автоякорь, $hhmm, по: ${it.basis ?: "источники"}", Warn)
                anchor.lastHumanAnchor?.let { h ->
                    val t = java.text.SimpleDateFormat("HH:mm", java.util.Locale.ROOT).format(java.util.Date(h.timestampMillis))
                    InfoRow("Ручной якорь", "$t, ${h.point.latitude.fmt(5)}, ${h.point.longitude.fmt(5)}")
                }
            }
            anchor?.anchor?.let {
                InfoRow(stringResource(R.string.label_anchor_point), "${it.point.latitude.fmt(6)}, ${it.point.longitude.fmt(6)}")
            }
            anchor?.freshness?.let { f ->
                InfoRow(
                    stringResource(R.string.label_anchor_freshness), f.label,
                    when (f) { AnchorFreshness.ACTUAL -> Ok; AnchorFreshness.CAN_UPDATE -> Warn; AnchorFreshness.MUST_UPDATE -> Bad },
                )
                anchor.ageSeconds?.let { InfoRow(stringResource(R.string.label_anchor_age), "${(it / 60).toInt()} мин ${(it % 60).toInt()} с") }
                anchor.traveledM?.let { InfoRow(stringResource(R.string.label_anchor_traveled), if (it >= 1000) "${(it / 1000).fmt(2)} км" else "${it.fmt(0)} м") }
                anchor.propagatedSigmaM?.let { InfoRow(stringResource(R.string.label_anchor_sigma), "σ ${it.fmt(0)} м") }
                anchor.deviationM?.let { d ->
                    InfoRow(stringResource(R.string.label_anchor_deviation), "${d.fmt(0)} м (допуск ${anchor.allowedDeviationM?.fmt(0)} м)")
                }
            }
            anchor?.message?.let { EmptyHint(it) }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(onClick = { viewModel.anchorFromCurrent() }, enabled = solution?.comparison?.let { it.estimate != null || it.accepted.isNotEmpty() || it.rejected.isNotEmpty() } == true) {
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

/**
 * Главная карточка: цвет полосы и значок — доверие к точке, крупные координаты (приглушены без кворума),
 * точность, причина и короткие метки: подтверждения, якорь, HPL. Нажатие — копировать координаты.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun PositionHero(solution: PhenixSolution?, running: Boolean) {
    val trust = PointTrust.of(solution)
    val color = trust.level.color().takeIf { it != Color.Unspecified } ?: MaterialTheme.colorScheme.onSurfaceVariant
    val clipboard = LocalClipboardManager.current
    val context = LocalContext.current
    val p = solution?.point
    Card(
        modifier = Modifier.fillMaxWidth(),
        onClick = {
            if (p != null) {
                clipboard.setText(AnnotatedString("${p.latitude.fmt(6)}, ${p.longitude.fmt(6)}"))
                Toast.makeText(context, R.string.coords_copied, Toast.LENGTH_SHORT).show()
            }
        },
    ) {
        Row(Modifier.height(IntrinsicSize.Min)) {
            Box(Modifier.width(6.dp).fillMaxHeight().background(color))
            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Box(Modifier.size(10.dp).clip(CircleShape).background(color))
                    Text("  " + trust.level.displayName, style = MaterialTheme.typography.titleMedium, color = color, fontWeight = FontWeight.SemiBold)
                    Spacer(Modifier.weight(1f))
                    solution?.accuracyM?.let {
                        Text("±${ErrorCircle.radius(it).fmt(0)} м", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.SemiBold)
                    }
                }
                if (p == null) {
                    Text(
                        stringResource(if (running) R.string.waiting_data else R.string.not_running),
                        style = MaterialTheme.typography.headlineSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                } else {
                    val coordColor = if (trust.level == PointTrustLevel.RELIABLE) MaterialTheme.colorScheme.onSurface else Muted
                    Text(p.latitude.fmt(6) + "°", style = MaterialTheme.typography.headlineMedium, fontFamily = FontFamily.Monospace, color = coordColor)
                    Text(p.longitude.fmt(6) + "°", style = MaterialTheme.typography.headlineMedium, fontFamily = FontFamily.Monospace, color = coordColor)
                }
                Text(trust.reason, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    solution?.comparison?.let { c ->
                        val n = c.independentConfirmations
                        Tag(stringResource(R.string.tag_confirmations, n), if (n >= 2) Ok else Warn)
                        Tag(stringResource(R.string.tag_sources, c.accepted.size, c.accepted.size + c.rejected.size), null)
                    }
                    solution?.anchor?.let { a ->
                        if (a.anchor != null) Tag(
                            stringResource(if (a.anchor!!.isAuto) R.string.tag_anchor_auto else R.string.tag_anchor) +
                                (a.deviationM?.let { " · Δ ${it.fmt(0)} м" } ?: ""),
                            if (a.state == AnchorState.ANCHOR_CONFLICT) Bad else Ok,
                        )
                    }
                    solution?.gnss?.raim?.horizontalProtectionLevelM?.let { Tag("HPL ${it.fmt(0)} м", null) }
                    p?.altitude?.let { Tag("h ${it.fmt(0)} м", null) }
                }
                if (p != null) EmptyHint(stringResource(R.string.coords_tap_to_copy))
            }
        }
    }
}

@Composable
private fun Tag(text: String, color: Color?) {
    val c = color ?: MaterialTheme.colorScheme.onSurfaceVariant
    Surface(shape = CircleShape, color = c.copy(alpha = 0.12f), border = BorderStroke(1.dp, c.copy(alpha = 0.5f))) {
        Text(text, Modifier.padding(horizontal = 10.dp, vertical = 3.dp), style = MaterialTheme.typography.labelMedium, color = c)
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

private val Ok: Color @Composable get() = LocalStatusColors.current.ok
private val Warn: Color @Composable get() = LocalStatusColors.current.warn
private val Bad: Color @Composable get() = LocalStatusColors.current.bad

@Composable
private fun IntegrityState.color(): Color = when (this) {
    IntegrityState.OK -> Ok
    IntegrityState.NO_DATA, IntegrityState.INCONSISTENT, IntegrityState.SUSPICIOUS, IntegrityState.UNCONFIRMED -> Warn
    else -> Bad
}

private val Muted: Color @Composable get() = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f)

@Composable
private fun PointTrustLevel.color(): Color = when (this) {
    PointTrustLevel.RELIABLE -> Ok
    PointTrustLevel.LIMITED, PointTrustLevel.UNRELIABLE -> Warn
    PointTrustLevel.ALARM -> Bad
    PointTrustLevel.NONE -> Color.Unspecified
}

@Composable
private fun TrustLevel.color(): Color = when (this) {
    TrustLevel.HIGH -> Ok
    TrustLevel.MEDIUM -> Color.Unspecified
    TrustLevel.LOW -> Warn
    TrustLevel.REJECTED -> Bad
}
