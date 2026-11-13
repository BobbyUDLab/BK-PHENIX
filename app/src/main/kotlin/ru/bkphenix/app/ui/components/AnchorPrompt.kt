package ru.bkphenix.app.ui.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import ru.bkphenix.app.R
import ru.bkphenix.core.anchor.AnchorEvaluation
import ru.bkphenix.core.anchor.AnchorFreshness

/**
 * Предложение на остановке: «Поставить якорь здесь?». «Да» — ручной (доверенный) якорь в этой точке,
 * «Позже» — не спрашивать 10 минут. Показывается, когда ядро сочло остановку точной и без подозрения на подмену,
 * а текущий якорь автоматический, устарел или его нет.
 */
@Composable
fun AnchorPrompt(anchor: AnchorEvaluation?, onConfirm: () -> Unit, onDismiss: () -> Unit, modifier: Modifier = Modifier) {
    anchor?.suggestion ?: return
    val why = when {
        anchor.anchor == null -> stringResource(R.string.anchor_prompt_none)
        anchor.anchor!!.isAuto -> stringResource(R.string.anchor_prompt_auto)
        else -> stringResource(R.string.anchor_prompt_stale)
    }
    Card(modifier, colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.primaryContainer)) {
        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(stringResource(R.string.anchor_prompt_title), style = MaterialTheme.typography.titleSmall)
            Text(why, style = MaterialTheme.typography.bodySmall)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(onClick = onConfirm) { Text(stringResource(R.string.anchor_prompt_yes)) }
                TextButton(onClick = onDismiss) { Text(stringResource(R.string.anchor_prompt_later)) }
            }
        }
    }
}

/** Короткое состояние якоря для меток: подтверждён вами / авто, ждёт подтверждения / устарел. */
@Composable
fun anchorStatusText(anchor: AnchorEvaluation): String? {
    val a = anchor.anchor ?: return null
    return when {
        anchor.freshness == AnchorFreshness.MUST_UPDATE -> stringResource(R.string.anchor_status_stale)
        a.isAuto -> stringResource(R.string.anchor_status_auto)
        else -> stringResource(R.string.anchor_status_trusted)
    }
}
