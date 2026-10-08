package com.flareaward.serendip.presentation.onboarding

import android.Manifest
import android.os.Build
import androidx.activity.compose.BackHandler
import androidx.activity.compose.LocalActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.systemBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.AutoAwesome
import androidx.compose.material.icons.rounded.BatteryChargingFull
import androidx.compose.material.icons.rounded.CameraAlt
import androidx.compose.material.icons.rounded.Notifications
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import androidx.core.app.ActivityCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.flareaward.serendip.R
import com.flareaward.serendip.domain.model.BackgroundRestriction
import com.flareaward.serendip.presentation.common.LocalAppGraph
import com.flareaward.serendip.presentation.common.startFirstAvailable
import com.flareaward.serendip.presentation.common.startSafely
import com.flareaward.serendip.presentation.components.StatusKind
import com.flareaward.serendip.presentation.components.StatusPill
import com.flareaward.serendip.presentation.components.SurfaceCard

private enum class Step { WELCOME, CAMERA, NOTIFICATIONS, BACKGROUND, DONE }

/**
 * First-run flow. The user must explicitly confirm that the app may take
 * photos automatically; permissions are requested one at a time with an
 * explanation; nothing is enabled behind the user's back.
 */
@Composable
fun OnboardingScreen(onFinished: () -> Unit) {
    val graph = LocalAppGraph.current
    val viewModel: OnboardingViewModel = viewModel { OnboardingViewModel(graph) }
    val system by viewModel.system.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val activity = LocalActivity.current

    LifecycleEventEffect(Lifecycle.Event.ON_RESUME) { viewModel.refresh() }

    // POST_NOTIFICATIONS is a runtime permission only from Android 13; older versions get no extra step.
    val steps = remember {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) Step.entries else Step.entries.filter { it != Step.NOTIFICATIONS }
    }
    var stepIndex by rememberSaveable { mutableStateOf(0) }
    val step = steps[stepIndex.coerceIn(0, steps.lastIndex)]
    BackHandler(enabled = stepIndex > 0) { stepIndex-- }

    var cameraAsked by rememberSaveable { mutableStateOf(false) }
    val cameraLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) {
        cameraAsked = true
        viewModel.refresh()
    }
    val cameraPermanentlyDenied = !system.cameraPermissionGranted && cameraAsked && activity != null &&
        !ActivityCompat.shouldShowRequestPermissionRationale(activity, Manifest.permission.CAMERA)

    var notificationsAsked by rememberSaveable { mutableStateOf(false) }
    val notificationsLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) {
        notificationsAsked = true
        viewModel.refresh()
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background)
            .systemBarsPadding()
            .padding(horizontal = 24.dp),
    ) {
        StepIndicator(count = steps.size, current = stepIndex, modifier = Modifier.padding(top = 16.dp))
        AnimatedContent(
            targetState = step,
            transitionSpec = {
                val forward = targetState.ordinal >= initialState.ordinal
                (slideInHorizontally { if (forward) it / 4 else -it / 4 } + fadeIn()) togetherWith
                    (slideOutHorizontally { if (forward) -it / 4 else it / 4 } + fadeOut())
            },
            label = "onboarding-step",
            modifier = Modifier.weight(1f),
        ) { current ->
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .verticalScroll(rememberScrollState())
                    .padding(vertical = 24.dp),
                verticalArrangement = Arrangement.spacedBy(16.dp),
            ) {
                when (current) {
                    Step.WELCOME -> WelcomeStep(
                        confirmed = viewModel.consentGiven,
                        onConfirmedChange = { viewModel.consentGiven = it },
                    )

                    Step.CAMERA -> PermissionStep(
                        icon = Icons.Rounded.CameraAlt,
                        title = stringResource(R.string.onboarding_camera_title),
                        text = stringResource(R.string.onboarding_camera_text),
                        granted = system.cameraPermissionGranted,
                        grantedLabel = stringResource(R.string.status_granted),
                        missingLabel = stringResource(R.string.status_denied),
                        actionLabel = stringResource(if (cameraPermanentlyDenied) R.string.action_open_settings else R.string.action_grant_camera),
                        onAction = {
                            if (cameraPermanentlyDenied) {
                                context.startSafely(graph.battery.appDetailsIntent())
                            } else {
                                cameraLauncher.launch(Manifest.permission.CAMERA)
                            }
                        },
                        hint = if (cameraPermanentlyDenied) stringResource(R.string.onboarding_camera_denied_hint) else null,
                    )

                    Step.NOTIFICATIONS -> PermissionStep(
                        icon = Icons.Rounded.Notifications,
                        title = stringResource(R.string.onboarding_notifications_title),
                        text = stringResource(R.string.onboarding_notifications_text),
                        granted = system.notificationsAllowed,
                        grantedLabel = stringResource(R.string.status_notifications_on),
                        missingLabel = stringResource(R.string.status_notifications_off),
                        actionLabel = stringResource(if (notificationsAsked) R.string.action_open_settings else R.string.action_grant_notifications),
                        onAction = {
                            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU && !notificationsAsked) {
                                notificationsLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
                            } else {
                                context.startFirstAvailable(
                                    listOf(graph.battery.appNotificationSettingsIntent(), graph.battery.appDetailsIntent()),
                                )
                            }
                        },
                        hint = null,
                    )

                    Step.BACKGROUND -> BackgroundStep(
                        restriction = system.backgroundRestriction,
                        onRequest = { context.startFirstAvailable(graph.battery.requestUnrestrictedIntents()) },
                        onOpenSettings = { context.startSafely(graph.battery.appDetailsIntent()) },
                    )

                    Step.DONE -> DoneStep(cameraGranted = system.cameraPermissionGranted)
                }
            }
        }

        // ---------------------------------------------------------------- navigation buttons
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(bottom = 20.dp, top = 8.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            if (stepIndex > 0) {
                TextButton(onClick = { stepIndex-- }) { Text(stringResource(R.string.action_back)) }
            } else {
                Spacer(Modifier.width(1.dp))
            }
            when (step) {
                Step.DONE -> Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    TextButton(onClick = { viewModel.complete(enableNow = false, onDone = onFinished) }) {
                        Text(stringResource(R.string.onboarding_later))
                    }
                    Button(
                        onClick = { viewModel.complete(enableNow = true, onDone = onFinished) },
                        enabled = system.cameraPermissionGranted,
                    ) { Text(stringResource(R.string.onboarding_enable_now)) }
                }

                else -> Button(
                    onClick = { stepIndex = (stepIndex + 1).coerceAtMost(steps.lastIndex) },
                    enabled = when (step) {
                        Step.WELCOME -> viewModel.consentGiven
                        else -> true
                    },
                ) { Text(stringResource(R.string.action_continue)) }
            }
        }
    }
}

@Composable
private fun StepIndicator(count: Int, current: Int, modifier: Modifier = Modifier) {
    Row(modifier = modifier, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        repeat(count) { index ->
            Box(
                modifier = Modifier
                    .height(4.dp)
                    .weight(1f)
                    .clip(CircleShape)
                    .background(
                        if (index <= current) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.surfaceContainerHighest,
                    ),
            )
        }
    }
}

@Composable
private fun StepHeader(icon: ImageVector, title: String, text: String) {
    Box(
        modifier = Modifier
            .size(64.dp)
            .clip(CircleShape)
            .background(MaterialTheme.colorScheme.primaryContainer),
        contentAlignment = Alignment.Center,
    ) {
        Icon(icon, contentDescription = null, tint = MaterialTheme.colorScheme.onPrimaryContainer, modifier = Modifier.size(30.dp))
    }
    Text(title, style = MaterialTheme.typography.headlineMedium)
    Text(text, style = MaterialTheme.typography.bodyLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
}

@Composable
private fun WelcomeStep(confirmed: Boolean, onConfirmedChange: (Boolean) -> Unit) {
    StepHeader(
        icon = Icons.Rounded.AutoAwesome,
        title = stringResource(R.string.onboarding_welcome_title),
        text = stringResource(R.string.onboarding_welcome_text),
    )
    SurfaceCard(color = MaterialTheme.colorScheme.surfaceContainerHigh) {
        Text(stringResource(R.string.onboarding_how_title), style = MaterialTheme.typography.titleMedium)
        Spacer(Modifier.height(8.dp))
        Text(stringResource(R.string.onboarding_how_text), style = MaterialTheme.typography.bodyMedium)
        Spacer(Modifier.height(8.dp))
        Text(stringResource(R.string.onboarding_privacy_text), style = MaterialTheme.typography.bodyMedium)
    }
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(MaterialTheme.shapes.medium)
            .toggleable(value = confirmed, role = Role.Checkbox, onValueChange = onConfirmedChange)
            .padding(vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Checkbox(checked = confirmed, onCheckedChange = null)
        Spacer(Modifier.width(8.dp))
        Text(stringResource(R.string.onboarding_consent), style = MaterialTheme.typography.bodyMedium)
    }
}

@Composable
private fun PermissionStep(
    icon: ImageVector,
    title: String,
    text: String,
    granted: Boolean,
    grantedLabel: String,
    missingLabel: String,
    actionLabel: String,
    onAction: () -> Unit,
    hint: String?,
) {
    StepHeader(icon = icon, title = title, text = text)
    SurfaceCard {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.SpaceBetween, modifier = Modifier.fillMaxWidth()) {
            Text(stringResource(R.string.onboarding_status_label), style = MaterialTheme.typography.bodyLarge)
            StatusPill(if (granted) StatusKind.OK else StatusKind.WARNING, if (granted) grantedLabel else missingLabel)
        }
        if (!granted) {
            Spacer(Modifier.height(12.dp))
            FilledTonalButton(onClick = onAction) { Text(actionLabel) }
            hint?.let {
                Spacer(Modifier.height(8.dp))
                Text(it, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
    }
}

@Composable
private fun BackgroundStep(restriction: BackgroundRestriction, onRequest: () -> Unit, onOpenSettings: () -> Unit) {
    StepHeader(
        icon = Icons.Rounded.BatteryChargingFull,
        title = stringResource(R.string.onboarding_background_title),
        text = stringResource(R.string.onboarding_background_text),
    )
    SurfaceCard {
        val (kind, label) = when (restriction) {
            BackgroundRestriction.UNRESTRICTED -> StatusKind.OK to stringResource(R.string.status_unrestricted)
            BackgroundRestriction.OPTIMIZED -> StatusKind.WARNING to stringResource(R.string.status_optimized)
            BackgroundRestriction.RESTRICTED -> StatusKind.ERROR to stringResource(R.string.status_restricted)
        }
        StatusPill(kind, label)
        Spacer(Modifier.height(12.dp))
        if (restriction != BackgroundRestriction.UNRESTRICTED) {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                FilledTonalButton(onClick = onRequest) { Text(stringResource(R.string.action_allow_unrestricted)) }
                TextButton(onClick = onOpenSettings) { Text(stringResource(R.string.action_open_settings)) }
            }
            Spacer(Modifier.height(8.dp))
        }
        Text(stringResource(R.string.background_vendor_hint), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Spacer(Modifier.height(8.dp))
        Text(stringResource(R.string.background_no_guarantee), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

@Composable
private fun DoneStep(cameraGranted: Boolean) {
    StepHeader(
        icon = Icons.Rounded.AutoAwesome,
        title = stringResource(R.string.onboarding_done_title),
        text = stringResource(R.string.onboarding_done_text),
    )
    if (!cameraGranted) {
        SurfaceCard(color = MaterialTheme.colorScheme.errorContainer, contentColor = MaterialTheme.colorScheme.onErrorContainer) {
            Text(stringResource(R.string.onboarding_done_no_camera), style = MaterialTheme.typography.bodyMedium)
        }
    }
}
