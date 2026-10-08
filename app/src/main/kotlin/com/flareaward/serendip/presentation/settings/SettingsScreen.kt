package com.flareaward.serendip.presentation.settings

import android.Manifest
import android.os.Build
import androidx.activity.compose.LocalActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Slider
import androidx.compose.material3.SnackbarHostState
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
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.core.app.ActivityCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.flareaward.serendip.BuildConfig
import com.flareaward.serendip.R
import com.flareaward.serendip.domain.model.AutoPhotoSettings
import com.flareaward.serendip.domain.model.BackgroundRestriction
import com.flareaward.serendip.domain.model.ScheduleMode
import com.flareaward.serendip.presentation.common.Formatters
import com.flareaward.serendip.presentation.common.HumanText
import com.flareaward.serendip.presentation.common.LocalAppGraph
import com.flareaward.serendip.presentation.common.startFirstAvailable
import com.flareaward.serendip.presentation.common.startSafely
import com.flareaward.serendip.presentation.components.KeyValue
import com.flareaward.serendip.presentation.components.LoadingState
import com.flareaward.serendip.presentation.components.SectionTitle
import com.flareaward.serendip.presentation.components.SerendipScreen
import com.flareaward.serendip.presentation.components.StatusKind
import com.flareaward.serendip.presentation.components.StatusPill
import com.flareaward.serendip.presentation.components.StatusRow
import com.flareaward.serendip.presentation.components.SurfaceCard
import com.flareaward.serendip.presentation.components.SwitchRow
import kotlin.math.roundToInt

@Composable
fun SettingsScreen(snackbarHostState: SnackbarHostState) {
    val graph = LocalAppGraph.current
    val viewModel: SettingsViewModel = viewModel { SettingsViewModel(graph) }
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val activity = LocalActivity.current

    LifecycleEventEffect(Lifecycle.Event.ON_RESUME) { viewModel.refreshSystemStatus() }

    val cameraPermissionMessage = stringResource(R.string.snackbar_camera_permission)
    val notAllowedMessage = stringResource(R.string.snackbar_service_not_allowed)
    LaunchedEffect(viewModel) {
        viewModel.messages.collect { message ->
            snackbarHostState.showSnackbar(
                when (message) {
                    SettingsMessage.CameraPermissionNeeded -> cameraPermissionMessage
                    SettingsMessage.ServiceStartNotAllowed -> notAllowedMessage
                },
            )
        }
    }

    // --- permission launchers -----------------------------------------------------------------
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
        if (cameraPermanentlyDenied) context.startSafely(graph.battery.appDetailsIntent()) else cameraLauncher.launch(Manifest.permission.CAMERA)
    }
    var notificationsAskedOnce by rememberSaveable { mutableStateOf(false) }
    val notificationsLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) {
        notificationsAskedOnce = true
        viewModel.refreshSystemStatus()
    }
    val requestNotifications: () -> Unit = {
        val canAsk = Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU && !notificationsAskedOnce
        if (canAsk) {
            notificationsLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
        } else {
            context.startFirstAvailable(listOf(graph.battery.appNotificationSettingsIntent(), graph.battery.appDetailsIntent()))
        }
    }

    SerendipScreen(title = stringResource(R.string.settings_title), snackbarHostState = snackbarHostState) { padding ->
        if (state.loading) {
            LoadingState(Modifier.padding(padding))
            return@SerendipScreen
        }
        val settings = state.settings
        LazyColumn(
            contentPadding = PaddingValues(
                start = 16.dp,
                end = 16.dp,
                top = padding.calculateTopPadding() + 4.dp,
                bottom = padding.calculateBottomPadding() + 24.dp,
            ),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            // ---------------------------------------------------------------- automatic photos
            item {
                SectionTitle(stringResource(R.string.settings_section_auto))
                SurfaceCard(contentPadding = PaddingValues(vertical = 8.dp)) {
                    SwitchRow(
                        title = stringResource(R.string.hero_title),
                        subtitle = stringResource(R.string.settings_auto_subtitle),
                        checked = settings.enabled,
                        onCheckedChange = viewModel::setEnabled,
                    )
                    HorizontalDivider(modifier = Modifier.padding(horizontal = 16.dp))
                    DailyLimitSetting(limit = settings.dailyPhotoLimit, onChange = viewModel::setDailyLimit)
                    HorizontalDivider(modifier = Modifier.padding(horizontal = 16.dp))
                    ScheduleModeSetting(settings = settings, viewModel = viewModel)
                    HorizontalDivider(modifier = Modifier.padding(horizontal = 16.dp))
                    SwitchRow(
                        title = stringResource(R.string.settings_skip_screen_off),
                        subtitle = stringResource(R.string.settings_skip_screen_off_subtitle),
                        checked = settings.skipWhenScreenOff,
                        onCheckedChange = viewModel::setSkipWhenScreenOff,
                    )
                }
            }

            // ---------------------------------------------------------------- notifications
            item {
                SectionTitle(stringResource(R.string.settings_section_notifications))
                SurfaceCard(contentPadding = PaddingValues(vertical = 8.dp)) {
                    SwitchRow(
                        title = stringResource(R.string.settings_notify_success),
                        subtitle = stringResource(R.string.settings_notify_success_subtitle),
                        checked = settings.notifyOnSuccess,
                        onCheckedChange = viewModel::setNotifyOnSuccess,
                    )
                    HorizontalDivider(modifier = Modifier.padding(horizontal = 16.dp))
                    StatusRow(
                        label = stringResource(R.string.settings_notifications_permission),
                        kind = if (state.system.notificationsAllowed) StatusKind.OK else StatusKind.WARNING,
                        status = stringResource(if (state.system.notificationsAllowed) R.string.status_notifications_on else R.string.status_notifications_off),
                        onClick = if (state.system.notificationsAllowed) null else requestNotifications,
                    )
                    if (!state.system.notificationsAllowed) {
                        Text(
                            stringResource(R.string.settings_notifications_hint),
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp),
                        )
                    }
                }
            }

            // ---------------------------------------------------------------- background work
            item {
                SectionTitle(stringResource(R.string.settings_section_background))
                BackgroundCard(
                    restriction = state.system.backgroundRestriction,
                    enabled = settings.enabled,
                    serviceRunning = state.serviceRunning,
                    pauseTitle = state.scheduler.pauseReason?.let { stringResource(HumanText.pauseReasonTitle(it)) },
                    onRequestUnrestricted = { context.startFirstAvailable(graph.battery.requestUnrestrictedIntents()) },
                    onOpenAppSettings = { context.startSafely(graph.battery.appDetailsIntent()) },
                    onRestartService = viewModel::restartService,
                )
            }

            // ---------------------------------------------------------------- permissions
            item {
                SectionTitle(stringResource(R.string.settings_section_permissions))
                SurfaceCard(contentPadding = PaddingValues(vertical = 8.dp)) {
                    StatusRow(
                        label = stringResource(R.string.state_camera),
                        kind = if (state.system.cameraPermissionGranted) StatusKind.OK else StatusKind.ERROR,
                        status = stringResource(if (state.system.cameraPermissionGranted) R.string.status_granted else R.string.status_denied),
                        onClick = if (state.system.cameraPermissionGranted) null else requestCamera,
                    )
                    if (!state.system.cameraPermissionGranted) {
                        Row(modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            FilledTonalButton(onClick = requestCamera) {
                                Text(stringResource(if (cameraPermanentlyDenied) R.string.action_open_settings else R.string.action_grant))
                            }
                            if (!cameraPermanentlyDenied) {
                                TextButton(onClick = { context.startSafely(graph.battery.appDetailsIntent()) }) {
                                    Text(stringResource(R.string.action_open_settings))
                                }
                            }
                        }
                    }
                    HorizontalDivider(modifier = Modifier.padding(horizontal = 16.dp))
                    StatusRow(
                        label = stringResource(R.string.state_notifications),
                        kind = if (state.system.notificationsAllowed) StatusKind.OK else StatusKind.NEUTRAL,
                        status = stringResource(if (state.system.notificationsAllowed) R.string.status_notifications_on else R.string.status_notifications_off),
                        onClick = if (state.system.notificationsAllowed) null else requestNotifications,
                    )
                    Text(
                        stringResource(R.string.settings_permissions_footer),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
                    )
                }
            }

            // ---------------------------------------------------------------- about
            item {
                SectionTitle(stringResource(R.string.settings_section_about))
                SurfaceCard {
                    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                        KeyValue(stringResource(R.string.about_version), BuildConfig.VERSION_NAME)
                        Text(
                            stringResource(R.string.about_description),
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        Text(
                            stringResource(R.string.about_privacy),
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
private fun DailyLimitSetting(limit: Int, onChange: (Int) -> Unit) {
    Column(modifier = Modifier.padding(horizontal = 16.dp, vertical = 10.dp)) {
        Row(verticalAlignment = Alignment.Bottom, horizontalArrangement = Arrangement.SpaceBetween, modifier = Modifier.fillMaxWidth()) {
            Column {
                Text(stringResource(R.string.settings_daily_limit), style = MaterialTheme.typography.bodyLarge)
                Text(
                    stringResource(R.string.settings_daily_limit_subtitle),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Text(limit.toString(), style = MaterialTheme.typography.displaySmall, color = MaterialTheme.colorScheme.primary)
        }
        Spacer(Modifier.height(4.dp))
        SingleChoiceSegmentedButtonRow(modifier = Modifier.fillMaxWidth()) {
            val presets = AutoPhotoSettings.DAILY_LIMIT_PRESETS
            presets.forEachIndexed { index, preset ->
                SegmentedButton(
                    selected = limit == preset,
                    onClick = { onChange(preset) },
                    shape = SegmentedButtonDefaults.itemShape(index = index, count = presets.size),
                    label = { Text(preset.toString()) },
                )
            }
        }
        Slider(
            value = limit.toFloat(),
            onValueChange = { onChange(it.roundToInt().coerceIn(AutoPhotoSettings.MIN_DAILY_LIMIT, AutoPhotoSettings.MAX_DAILY_LIMIT)) },
            valueRange = AutoPhotoSettings.MIN_DAILY_LIMIT.toFloat()..AutoPhotoSettings.MAX_DAILY_LIMIT.toFloat(),
            steps = AutoPhotoSettings.MAX_DAILY_LIMIT - AutoPhotoSettings.MIN_DAILY_LIMIT - 1,
        )
    }
}

@Composable
private fun ScheduleModeSetting(settings: AutoPhotoSettings, viewModel: SettingsViewModel) {
    val context = LocalContext.current
    Column(modifier = Modifier.padding(horizontal = 16.dp, vertical = 10.dp)) {
        Text(stringResource(R.string.settings_mode), style = MaterialTheme.typography.bodyLarge)
        Spacer(Modifier.height(8.dp))
        SingleChoiceSegmentedButtonRow(modifier = Modifier.fillMaxWidth()) {
            val modes = ScheduleMode.entries
            modes.forEachIndexed { index, mode ->
                SegmentedButton(
                    selected = settings.scheduleMode == mode,
                    onClick = { viewModel.setScheduleMode(mode) },
                    shape = SegmentedButtonDefaults.itemShape(index = index, count = modes.size),
                    label = { Text(stringResource(HumanText.scheduleMode(mode)), maxLines = 1) },
                )
            }
        }
        Spacer(Modifier.height(6.dp))
        Text(
            stringResource(
                when (settings.scheduleMode) {
                    ScheduleMode.FULLY_RANDOM -> R.string.settings_mode_fully_random_hint
                    ScheduleMode.RANDOM_INTERVAL -> R.string.settings_mode_interval_hint
                },
            ),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        AnimatedVisibility(visible = settings.scheduleMode == ScheduleMode.RANDOM_INTERVAL) {
            Column {
                Spacer(Modifier.height(12.dp))
                IntervalSlider(
                    label = stringResource(R.string.settings_interval_min),
                    minutes = settings.minIntervalMinutes,
                    onChange = viewModel::setMinInterval,
                )
                IntervalSlider(
                    label = stringResource(R.string.settings_interval_max),
                    minutes = settings.maxIntervalMinutes,
                    onChange = viewModel::setMaxInterval,
                )
                Text(
                    stringResource(
                        R.string.settings_interval_summary,
                        Formatters.intervalRange(context, settings.minIntervalMinutes, settings.maxIntervalMinutes),
                    ),
                    style = MaterialTheme.typography.bodyMedium,
                )
                Text(
                    stringResource(R.string.settings_interval_doze_hint),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

/** A slider over the discrete interval presets (5 min … 12 h). */
@Composable
private fun IntervalSlider(label: String, minutes: Int, onChange: (Int) -> Unit) {
    val context = LocalContext.current
    val presets = AutoPhotoSettings.INTERVAL_PRESETS_MINUTES
    val index = presets.indexOfFirst { it >= minutes }.let { if (it < 0) presets.lastIndex else it }
    Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
        Text(label, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Text(Formatters.minutes(context, minutes), style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.primary)
    }
    Slider(
        value = index.toFloat(),
        onValueChange = { onChange(presets[it.roundToInt().coerceIn(0, presets.lastIndex)]) },
        valueRange = 0f..presets.lastIndex.toFloat(),
        steps = presets.size - 2,
    )
}

@Composable
private fun BackgroundCard(
    restriction: BackgroundRestriction,
    enabled: Boolean,
    serviceRunning: Boolean,
    pauseTitle: String?,
    onRequestUnrestricted: () -> Unit,
    onOpenAppSettings: () -> Unit,
    onRestartService: () -> Unit,
) {
    SurfaceCard {
        Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
            val (kind, statusText) = when (restriction) {
                BackgroundRestriction.UNRESTRICTED -> StatusKind.OK to stringResource(R.string.status_unrestricted)
                BackgroundRestriction.OPTIMIZED -> StatusKind.WARNING to stringResource(R.string.status_optimized)
                BackgroundRestriction.RESTRICTED -> StatusKind.ERROR to stringResource(R.string.status_restricted)
            }
            StatusPill(kind, statusText)
            Text(
                stringResource(
                    when (restriction) {
                        BackgroundRestriction.UNRESTRICTED -> R.string.background_unrestricted_text
                        BackgroundRestriction.OPTIMIZED -> R.string.background_optimized_text
                        BackgroundRestriction.RESTRICTED -> R.string.background_restricted_text
                    },
                ),
                style = MaterialTheme.typography.bodyMedium,
            )
            if (restriction != BackgroundRestriction.UNRESTRICTED) {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    FilledTonalButton(onClick = onRequestUnrestricted) { Text(stringResource(R.string.action_allow_unrestricted)) }
                    TextButton(onClick = onOpenAppSettings) { Text(stringResource(R.string.action_open_settings)) }
                }
            }
            Text(
                stringResource(R.string.background_vendor_hint),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Text(
                stringResource(R.string.background_no_guarantee),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            HorizontalDivider()
            StatusRow(
                label = stringResource(R.string.state_service),
                kind = when {
                    !enabled -> StatusKind.NEUTRAL
                    serviceRunning && pauseTitle == null -> StatusKind.OK
                    else -> StatusKind.WARNING
                },
                status = when {
                    !enabled -> stringResource(R.string.status_service_off)
                    pauseTitle != null -> stringResource(R.string.status_service_paused)
                    serviceRunning -> stringResource(R.string.status_service_running)
                    else -> stringResource(R.string.status_service_stopped)
                },
                modifier = Modifier.padding(horizontal = 0.dp),
            )
            pauseTitle?.let {
                Text(it, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            if (enabled && (!serviceRunning || pauseTitle != null)) {
                FilledTonalButton(onClick = onRestartService) { Text(stringResource(R.string.action_resume)) }
            }
            Text(
                stringResource(R.string.background_android_rules),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}
