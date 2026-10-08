package com.flareaward.serendip.presentation.dashboard

import android.Manifest
import androidx.activity.compose.LocalActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.spring
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.AutoAwesome
import androidx.compose.material.icons.rounded.ChevronRight
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.core.app.ActivityCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import coil3.compose.AsyncImage
import com.flareaward.serendip.R
import com.flareaward.serendip.domain.model.BackgroundRestriction
import com.flareaward.serendip.domain.model.PauseReason
import com.flareaward.serendip.domain.model.ScheduleMode
import com.flareaward.serendip.presentation.common.Formatters
import com.flareaward.serendip.presentation.common.HumanText
import com.flareaward.serendip.presentation.common.LocalAppGraph
import com.flareaward.serendip.presentation.common.startFirstAvailable
import com.flareaward.serendip.presentation.common.startSafely
import com.flareaward.serendip.presentation.components.InfoBanner
import com.flareaward.serendip.presentation.components.KeyValue
import com.flareaward.serendip.presentation.components.LoadingState
import com.flareaward.serendip.presentation.components.SectionTitle
import com.flareaward.serendip.presentation.components.SerendipScreen
import com.flareaward.serendip.presentation.components.StatusKind
import com.flareaward.serendip.presentation.components.StatusRow
import com.flareaward.serendip.presentation.components.SurfaceCard

@Composable
fun DashboardScreen(
    snackbarHostState: SnackbarHostState,
    onOpenSettings: () -> Unit,
    onOpenPhoto: (Long) -> Unit,
) {
    val graph = LocalAppGraph.current
    val viewModel: DashboardViewModel = viewModel { DashboardViewModel(graph) }
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val activity = LocalActivity.current

    LifecycleEventEffect(Lifecycle.Event.ON_RESUME) { viewModel.refreshSystemStatus() }

    val cameraPermissionMessage = stringResource(R.string.snackbar_camera_permission)
    val notAllowedMessage = stringResource(R.string.snackbar_service_not_allowed)
    LaunchedEffect(viewModel) {
        viewModel.messages.collect { message ->
            val text = when (message) {
                DashboardMessage.CameraPermissionNeeded -> cameraPermissionMessage
                DashboardMessage.ServiceStartNotAllowed -> notAllowedMessage
            }
            snackbarHostState.showSnackbar(text)
        }
    }

    var cameraAskedOnce by rememberSaveable { mutableStateOf(false) }
    val cameraLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) {
        cameraAskedOnce = true
        viewModel.refreshSystemStatus()
    }
    val cameraPermanentlyDenied = remember(state.system.cameraPermissionGranted, cameraAskedOnce, activity) {
        !state.system.cameraPermissionGranted && cameraAskedOnce && activity != null &&
            !ActivityCompat.shouldShowRequestPermissionRationale(activity, Manifest.permission.CAMERA)
    }
    val requestCamera: () -> Unit = {
        if (cameraPermanentlyDenied) {
            context.startSafely(graph.battery.appDetailsIntent())
        } else {
            cameraLauncher.launch(Manifest.permission.CAMERA)
        }
    }

    SerendipScreen(title = stringResource(R.string.app_name), snackbarHostState = snackbarHostState) { padding ->
        if (state.loading) {
            LoadingState(Modifier.padding(padding))
            return@SerendipScreen
        }
        LazyColumn(
            contentPadding = PaddingValues(
                start = 16.dp,
                end = 16.dp,
                top = padding.calculateTopPadding() + 4.dp,
                bottom = padding.calculateBottomPadding() + 24.dp,
            ),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            item { HeroCard(state = state, onToggle = viewModel::setEnabled) }

            // --- things that need the user's attention -------------------------------------
            val pause = state.scheduler.pauseReason
            if (state.settings.enabled && !state.system.cameraPermissionGranted) {
                item {
                    InfoBanner(
                        kind = StatusKind.ERROR,
                        title = stringResource(R.string.banner_camera_title),
                        text = stringResource(R.string.banner_camera_text),
                        primaryAction = stringResource(
                            if (cameraPermanentlyDenied) R.string.action_open_settings else R.string.action_grant,
                        ) to requestCamera,
                    )
                }
            } else if (state.settings.enabled && pause != null && pause != PauseReason.CAMERA_PERMISSION_MISSING) {
                item {
                    InfoBanner(
                        kind = StatusKind.WARNING,
                        title = stringResource(HumanText.pauseReasonTitle(pause)),
                        text = stringResource(HumanText.pauseReasonText(pause)),
                        primaryAction = if (pause.requiresUserAction && pause != PauseReason.NO_BACK_CAMERA) {
                            stringResource(R.string.action_resume) to viewModel::resume
                        } else {
                            null
                        },
                    )
                }
            }
            if (state.settings.enabled && state.system.backgroundRestriction != BackgroundRestriction.UNRESTRICTED) {
                item {
                    val restricted = state.system.backgroundRestriction == BackgroundRestriction.RESTRICTED
                    InfoBanner(
                        kind = if (restricted) StatusKind.ERROR else StatusKind.WARNING,
                        title = stringResource(if (restricted) R.string.banner_restricted_title else R.string.banner_background_title),
                        text = stringResource(if (restricted) R.string.banner_restricted_text else R.string.banner_background_text),
                        primaryAction = stringResource(R.string.action_allow_unrestricted) to {
                            context.startFirstAvailable(graph.battery.requestUnrestrictedIntents())
                        },
                        secondaryAction = stringResource(R.string.action_learn_more) to onOpenSettings,
                    )
                }
            }
            if (state.settings.enabled && state.settings.notifyOnSuccess && !state.system.notificationsAllowed) {
                item {
                    InfoBanner(
                        kind = StatusKind.INFO,
                        title = stringResource(R.string.banner_notifications_title),
                        text = stringResource(R.string.banner_notifications_text),
                        primaryAction = stringResource(R.string.action_open_settings) to {
                            context.startFirstAvailable(
                                listOf(graph.battery.appNotificationSettingsIntent(), graph.battery.appDetailsIntent()),
                            )
                        },
                    )
                }
            }

            // --- schedule --------------------------------------------------------------------
            item {
                SectionTitle(stringResource(R.string.schedule_card_title))
                SurfaceCard(onClick = onOpenSettings) {
                    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                        KeyValue(stringResource(R.string.schedule_mode_label), stringResource(HumanText.scheduleMode(state.settings.scheduleMode)))
                        KeyValue(stringResource(R.string.schedule_per_day_label), state.settings.dailyPhotoLimit.toString())
                        if (state.settings.scheduleMode == ScheduleMode.RANDOM_INTERVAL) {
                            KeyValue(
                                stringResource(R.string.schedule_interval_label),
                                Formatters.intervalRange(context, state.settings.minIntervalMinutes, state.settings.maxIntervalMinutes),
                            )
                        }
                        KeyValue(
                            stringResource(R.string.schedule_screen_off_label),
                            stringResource(if (state.settings.skipWhenScreenOff) R.string.schedule_screen_off_skip else R.string.schedule_screen_off_shoot),
                        )
                        KeyValue(
                            stringResource(R.string.schedule_notify_label),
                            stringResource(if (state.settings.notifyOnSuccess) R.string.value_on else R.string.value_off),
                        )
                    }
                }
            }

            // --- system state -----------------------------------------------------------------
            item {
                SectionTitle(stringResource(R.string.state_card_title))
                SurfaceCard(contentPadding = PaddingValues(vertical = 4.dp)) {
                    StatusRow(
                        label = stringResource(R.string.state_camera),
                        kind = if (state.system.cameraPermissionGranted) StatusKind.OK else StatusKind.ERROR,
                        status = stringResource(if (state.system.cameraPermissionGranted) R.string.status_granted else R.string.status_denied),
                        onClick = if (state.system.cameraPermissionGranted) null else requestCamera,
                    )
                    StatusRow(
                        label = stringResource(R.string.state_notifications),
                        kind = if (state.system.notificationsAllowed) StatusKind.OK else StatusKind.NEUTRAL,
                        status = stringResource(if (state.system.notificationsAllowed) R.string.status_notifications_on else R.string.status_notifications_off),
                    )
                    val background = state.system.backgroundRestriction
                    StatusRow(
                        label = stringResource(R.string.state_background),
                        kind = when (background) {
                            BackgroundRestriction.UNRESTRICTED -> StatusKind.OK
                            BackgroundRestriction.OPTIMIZED -> StatusKind.WARNING
                            BackgroundRestriction.RESTRICTED -> StatusKind.ERROR
                        },
                        status = stringResource(
                            when (background) {
                                BackgroundRestriction.UNRESTRICTED -> R.string.status_unrestricted_short
                                BackgroundRestriction.OPTIMIZED -> R.string.status_optimized_short
                                BackgroundRestriction.RESTRICTED -> R.string.status_restricted_short
                            },
                        ),
                        onClick = onOpenSettings,
                    )
                    StatusRow(
                        label = stringResource(R.string.state_service),
                        kind = when {
                            !state.settings.enabled -> StatusKind.NEUTRAL
                            state.serviceRunning -> StatusKind.OK
                            else -> StatusKind.WARNING
                        },
                        status = stringResource(
                            when {
                                !state.settings.enabled -> R.string.status_service_off
                                state.serviceRunning -> R.string.status_service_running
                                else -> R.string.status_service_stopped
                            },
                        ),
                    )
                }
            }

            // --- latest photo -----------------------------------------------------------------
            item {
                SectionTitle(stringResource(R.string.latest_photo_title))
                val latest = state.latestPhoto
                if (latest?.photoUri != null) {
                    SurfaceCard(onClick = { onOpenPhoto(latest.id) }, contentPadding = PaddingValues(12.dp)) {
                        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(14.dp)) {
                            AsyncImage(
                                model = latest.photoUri,
                                contentDescription = null,
                                contentScale = ContentScale.Crop,
                                modifier = Modifier
                                    .size(72.dp)
                                    .clip(MaterialTheme.shapes.medium),
                            )
                            Column(modifier = Modifier.weight(1f)) {
                                Text(Formatters.dateTime(context, latest.timestamp), style = MaterialTheme.typography.titleMedium)
                                Text(
                                    stringResource(R.string.latest_photo_hint),
                                    style = MaterialTheme.typography.bodyMedium,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                            Icon(Icons.Rounded.ChevronRight, contentDescription = null, tint = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                    }
                } else {
                    SurfaceCard {
                        Text(
                            stringResource(R.string.latest_photo_none),
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun HeroCard(state: DashboardUiState, onToggle: (Boolean) -> Unit) {
    val enabled = state.settings.enabled
    val scheme = MaterialTheme.colorScheme
    val container by animateColorAsState(
        if (enabled) scheme.primaryContainer else scheme.surfaceContainerHigh,
        spring(stiffness = Spring.StiffnessLow),
        label = "hero-container",
    )
    val content by animateColorAsState(
        if (enabled) scheme.onPrimaryContainer else scheme.onSurface,
        spring(stiffness = Spring.StiffnessLow),
        label = "hero-content",
    )
    val context = LocalContext.current

    SurfaceCard(color = container, contentColor = content, contentPadding = PaddingValues(22.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(
                Icons.Rounded.AutoAwesome,
                contentDescription = null,
                tint = if (enabled) scheme.primary else scheme.onSurfaceVariant,
                modifier = Modifier
                    .size(40.dp)
                    .clip(CircleShape)
                    .padding(4.dp),
            )
            Spacer(Modifier.width(10.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(stringResource(R.string.hero_title), style = MaterialTheme.typography.titleLarge)
                AnimatedContent(
                    targetState = statusLine(state),
                    transitionSpec = { fadeIn() togetherWith fadeOut() },
                    label = "status-line",
                ) { line ->
                    Text(line, style = MaterialTheme.typography.bodyMedium, color = content.copy(alpha = 0.85f))
                }
            }
            Spacer(Modifier.width(12.dp))
            Switch(checked = enabled, onCheckedChange = onToggle)
        }

        AnimatedVisibility(visible = enabled) {
            Column {
                Spacer(Modifier.height(20.dp))
                val taken = state.scheduler.photosTakenToday
                val limit = state.settings.dailyPhotoLimit
                Row(verticalAlignment = Alignment.Bottom) {
                    Text(
                        text = stringResource(R.string.today_progress, taken, limit),
                        style = MaterialTheme.typography.displayLarge,
                    )
                    Spacer(Modifier.width(12.dp))
                    Text(
                        text = stringResource(R.string.today_caption),
                        style = MaterialTheme.typography.titleMedium,
                        color = content.copy(alpha = 0.8f),
                        modifier = Modifier.padding(bottom = 10.dp),
                    )
                }
                Spacer(Modifier.height(8.dp))
                LinearProgressIndicator(
                    progress = { if (limit > 0) (taken.toFloat() / limit).coerceIn(0f, 1f) else 0f },
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(8.dp)
                        .clip(CircleShape),
                    color = scheme.primary,
                    trackColor = content.copy(alpha = 0.15f),
                )
                Spacer(Modifier.height(14.dp))
                val next = state.scheduler.nextCaptureAt
                val nextText = when {
                    state.scheduler.pauseReason != null -> stringResource(R.string.next_paused)
                    taken >= limit -> stringResource(R.string.next_done_today)
                    next != null && next > state.now -> stringResource(
                        R.string.next_photo_at_in,
                        Formatters.time(context, next),
                        Formatters.durationUntil(context, next, state.now),
                    )
                    next != null -> stringResource(R.string.next_photo_soon)
                    else -> stringResource(R.string.next_none_today)
                }
                Text(nextText, style = MaterialTheme.typography.titleMedium)
            }
        }
    }
}

@Composable
private fun statusLine(state: DashboardUiState): String {
    val pause = state.scheduler.pauseReason
    return when {
        !state.settings.enabled -> stringResource(R.string.status_disabled)
        !state.system.cameraPermissionGranted -> stringResource(R.string.status_no_camera_permission)
        pause != null -> stringResource(HumanText.pauseReasonTitle(pause))
        !state.serviceRunning -> stringResource(R.string.status_starting)
        state.scheduler.photosTakenToday >= state.settings.dailyPhotoLimit -> stringResource(R.string.status_done_today)
        state.scheduler.nextCaptureAt != null -> stringResource(R.string.status_active)
        else -> stringResource(R.string.status_waiting_tomorrow)
    }
}
