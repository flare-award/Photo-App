package com.flareaward.serendip.presentation.history

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.DarkMode
import androidx.compose.material.icons.rounded.ErrorOutline
import androidx.compose.material.icons.rounded.History
import androidx.compose.material.icons.rounded.PhotoCamera
import androidx.compose.material.icons.rounded.Schedule
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.flareaward.serendip.R
import com.flareaward.serendip.domain.model.CaptureOutcome
import com.flareaward.serendip.presentation.common.Formatters
import com.flareaward.serendip.presentation.common.HumanText
import com.flareaward.serendip.presentation.common.LocalAppGraph
import com.flareaward.serendip.presentation.components.EmptyState
import com.flareaward.serendip.presentation.components.LoadingState
import com.flareaward.serendip.presentation.components.SerendipScreen
import com.flareaward.serendip.presentation.components.SurfaceCard
import com.flareaward.serendip.presentation.theme.Amber

@Composable
fun HistoryScreen(onOpenPhoto: (Long) -> Unit) {
    val graph = LocalAppGraph.current
    val viewModel: HistoryViewModel = viewModel { HistoryViewModel(graph) }
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    val context = LocalContext.current

    SerendipScreen(title = stringResource(R.string.history_title)) { padding ->
        when {
            state.loading -> LoadingState(Modifier.padding(padding))
            state.days.isEmpty() -> Box(Modifier.padding(padding)) {
                EmptyState(
                    icon = Icons.Rounded.History,
                    title = stringResource(R.string.history_empty_title),
                    text = stringResource(R.string.history_empty_text),
                )
            }
            else -> LazyColumn(
                contentPadding = PaddingValues(
                    start = 16.dp,
                    end = 16.dp,
                    top = padding.calculateTopPadding() + 4.dp,
                    bottom = padding.calculateBottomPadding() + 24.dp,
                ),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                state.days.forEach { day ->
                    item(key = "day-${day.date}") {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(start = 4.dp, top = 12.dp, bottom = 4.dp),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Text(
                                Formatters.dayLabel(context, day.date),
                                style = MaterialTheme.typography.titleMedium,
                                color = MaterialTheme.colorScheme.primary,
                            )
                            Text(
                                pluralStringResource(R.plurals.history_day_summary, day.successes, day.successes),
                                style = MaterialTheme.typography.labelMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }
                    items(day.items.size, key = { index -> day.items[index].id }) { index ->
                        HistoryRow(item = day.items[index], onOpenPhoto = onOpenPhoto)
                    }
                }
            }
        }
    }
}

@Composable
private fun HistoryRow(item: HistoryItem, onOpenPhoto: (Long) -> Unit) {
    val context = LocalContext.current
    val event = item.event
    val scheme = MaterialTheme.colorScheme
    val (icon, tint, title) = when (event.outcome) {
        CaptureOutcome.SUCCESS -> Triple(
            Icons.Rounded.PhotoCamera,
            scheme.tertiary,
            stringResource(if (event.photoUri != null) R.string.history_success else R.string.history_success_deleted),
        )
        CaptureOutcome.FAILED -> Triple(
            Icons.Rounded.ErrorOutline,
            scheme.error,
            stringResource(R.string.history_failed, stringResource(HumanText.failureReason(event.failureReason))),
        )
        CaptureOutcome.SKIPPED_SCREEN_OFF -> Triple(
            Icons.Rounded.DarkMode,
            scheme.onSurfaceVariant,
            if (item.count > 1) {
                pluralStringResource(R.plurals.history_skipped_screen_off_many, item.count, item.count)
            } else {
                stringResource(R.string.history_skipped_screen_off)
            },
        )
        CaptureOutcome.MISSED -> Triple(
            Icons.Rounded.Schedule,
            Amber,
            pluralStringResource(R.plurals.history_missed, event.missedCount, event.missedCount),
        )
    }
    val time = if (item.count > 1) {
        "${Formatters.time(context, item.firstTimestamp)} – ${Formatters.time(context, event.timestamp)}"
    } else {
        Formatters.time(context, event.timestamp)
    }
    val subtitle = when (event.outcome) {
        CaptureOutcome.MISSED -> stringResource(R.string.history_missed_hint, time)
        else -> time
    }
    val clickable = event.outcome == CaptureOutcome.SUCCESS && event.photoUri != null
    SurfaceCard(
        onClick = if (clickable) ({ onOpenPhoto(event.id) }) else null,
        contentPadding = PaddingValues(horizontal = 16.dp, vertical = 14.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            OutcomeIcon(icon = icon, tint = tint)
            Spacer(Modifier.width(14.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(title, style = MaterialTheme.typography.bodyLarge)
                Text(subtitle, style = MaterialTheme.typography.bodyMedium, color = scheme.onSurfaceVariant)
            }
        }
    }
}

@Composable
private fun OutcomeIcon(icon: ImageVector, tint: Color) {
    Surface(shape = CircleShape, color = tint.copy(alpha = 0.14f)) {
        Box(modifier = Modifier.size(40.dp), contentAlignment = Alignment.Center) {
            Icon(icon, contentDescription = null, tint = tint, modifier = Modifier.size(20.dp))
        }
    }
}
