@file:OptIn(ExperimentalMaterial3Api::class)

package com.flareaward.serendip.presentation.photos

import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.rememberTransformableState
import androidx.compose.foundation.gestures.transformable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.rounded.BrokenImage
import androidx.compose.material.icons.rounded.Delete
import androidx.compose.material.icons.rounded.Share
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.rememberVectorPainter
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import coil3.compose.AsyncImage
import com.flareaward.serendip.R
import com.flareaward.serendip.presentation.common.Formatters
import com.flareaward.serendip.presentation.common.LocalAppGraph
import com.flareaward.serendip.presentation.common.startSafely
import com.flareaward.serendip.presentation.components.EmptyState
import com.flareaward.serendip.presentation.components.LoadingState

@Composable
fun PhotoViewerScreen(eventId: Long, onBack: () -> Unit) {
    val graph = LocalAppGraph.current
    val viewModel: PhotoViewerViewModel = viewModel(key = "photo-$eventId") { PhotoViewerViewModel(graph, eventId) }
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    val deleteFailed by viewModel.deleteFailed.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val snackbarHostState = remember { SnackbarHostState() }
    var confirmDelete by rememberSaveable { mutableStateOf(false) }

    LaunchedEffect(state) {
        if (state is PhotoViewerUiState.Deleted) onBack()
    }
    val deleteFailedText = stringResource(R.string.viewer_delete_failed)
    LaunchedEffect(deleteFailed) {
        if (deleteFailed) {
            snackbarHostState.showSnackbar(deleteFailedText)
            viewModel.consumeDeleteFailed()
        }
    }

    val ready = state as? PhotoViewerUiState.Ready
    Scaffold(
        containerColor = Color.Black,
        contentColor = MaterialTheme.colorScheme.onSurface,
        snackbarHost = { SnackbarHost(snackbarHostState) },
        topBar = {
            TopAppBar(
                title = {
                    Column {
                        Text(
                            ready?.let { Formatters.dateTime(context, it.event.timestamp) } ?: "",
                            style = MaterialTheme.typography.titleMedium,
                        )
                        Text(
                            stringResource(R.string.viewer_subtitle),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Rounded.ArrowBack, contentDescription = stringResource(R.string.action_back))
                    }
                },
                actions = {
                    if (ready != null) {
                        IconButton(onClick = { viewModel.shareIntent()?.let { context.startSafely(it) } }) {
                            Icon(Icons.Rounded.Share, contentDescription = stringResource(R.string.action_share))
                        }
                        IconButton(onClick = { confirmDelete = true }) {
                            Icon(Icons.Rounded.Delete, contentDescription = stringResource(R.string.action_delete))
                        }
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = Color.Black.copy(alpha = 0.6f)),
            )
        },
    ) { padding ->
        when (val s = state) {
            PhotoViewerUiState.Loading -> LoadingState(Modifier.padding(padding))
            PhotoViewerUiState.NotFound, PhotoViewerUiState.Deleted -> Box(Modifier.padding(padding)) {
                EmptyState(
                    icon = Icons.Rounded.BrokenImage,
                    title = stringResource(R.string.viewer_missing_title),
                    text = stringResource(R.string.viewer_missing_text),
                    action = stringResource(R.string.action_back) to onBack,
                )
            }
            is PhotoViewerUiState.Ready -> ZoomableImage(
                uri = s.event.photoUri,
                modifier = Modifier
                    .fillMaxSize()
                    .background(Color.Black)
                    .navigationBarsPadding(),
            )
        }
    }

    if (confirmDelete) {
        AlertDialog(
            onDismissRequest = { confirmDelete = false },
            title = { Text(stringResource(R.string.viewer_delete_title)) },
            text = { Text(stringResource(R.string.viewer_delete_text)) },
            confirmButton = {
                TextButton(onClick = {
                    confirmDelete = false
                    viewModel.delete()
                }) { Text(stringResource(R.string.action_delete), color = MaterialTheme.colorScheme.error) }
            },
            dismissButton = {
                TextButton(onClick = { confirmDelete = false }) { Text(stringResource(R.string.action_cancel)) }
            },
        )
    }
}

/** Pinch-to-zoom + pan, double tap resets. */
@Composable
private fun ZoomableImage(uri: String?, modifier: Modifier = Modifier) {
    var scale by remember { mutableFloatStateOf(1f) }
    var offset by remember { mutableStateOf(Offset.Zero) }
    val transformState = rememberTransformableState { zoomChange, panChange, _ ->
        scale = (scale * zoomChange).coerceIn(1f, 6f)
        offset = if (scale > 1f) offset + panChange else Offset.Zero
    }
    Box(
        modifier = modifier
            .pointerInput(Unit) {
                detectTapGestures(onDoubleTap = {
                    scale = if (scale > 1f) 1f else 2.5f
                    offset = Offset.Zero
                })
            }
            .transformable(transformState),
        contentAlignment = Alignment.Center,
    ) {
        AsyncImage(
            model = uri,
            contentDescription = null,
            contentScale = ContentScale.Fit,
            error = rememberVectorPainter(Icons.Rounded.BrokenImage),
            modifier = Modifier
                .fillMaxWidth()
                .fillMaxSize()
                .graphicsLayer {
                    scaleX = scale
                    scaleY = scale
                    translationX = offset.x
                    translationY = offset.y
                },
        )
    }
}
