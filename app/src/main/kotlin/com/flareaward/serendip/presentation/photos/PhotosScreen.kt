package com.flareaward.serendip.presentation.photos

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.BrokenImage
import androidx.compose.material.icons.rounded.PhotoLibrary
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.vector.rememberVectorPainter
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import coil3.compose.AsyncImage
import com.flareaward.serendip.R
import com.flareaward.serendip.presentation.common.Formatters
import com.flareaward.serendip.presentation.common.LocalAppGraph
import com.flareaward.serendip.presentation.components.EmptyState
import com.flareaward.serendip.presentation.components.LoadingState
import com.flareaward.serendip.presentation.components.SerendipScreen

@Composable
fun PhotosScreen(onOpenPhoto: (Long) -> Unit, onOpenDashboard: () -> Unit) {
    val graph = LocalAppGraph.current
    val viewModel: PhotosViewModel = viewModel { PhotosViewModel(graph) }
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    val context = LocalContext.current

    LifecycleEventEffect(Lifecycle.Event.ON_START) { viewModel.reconcileWithMediaStore() }

    SerendipScreen(title = stringResource(R.string.photos_title)) { padding ->
        when {
            state.loading -> LoadingState(Modifier.padding(padding))
            state.days.isEmpty() -> Box(Modifier.padding(padding)) {
                EmptyState(
                    icon = Icons.Rounded.PhotoLibrary,
                    title = stringResource(R.string.photos_empty_title),
                    text = stringResource(R.string.photos_empty_text),
                    action = stringResource(R.string.photos_empty_action) to onOpenDashboard,
                )
            }
            else -> LazyVerticalGrid(
                columns = GridCells.Adaptive(minSize = 108.dp),
                contentPadding = PaddingValues(
                    start = 12.dp,
                    end = 12.dp,
                    top = padding.calculateTopPadding() + 4.dp,
                    bottom = padding.calculateBottomPadding() + 24.dp,
                ),
                horizontalArrangement = Arrangement.spacedBy(6.dp),
                verticalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                state.days.forEach { day ->
                    item(key = "day-${day.date}", span = { GridItemSpan(maxLineSpan) }) {
                        Text(
                            text = Formatters.dayLabel(context, day.date),
                            style = MaterialTheme.typography.titleMedium,
                            color = MaterialTheme.colorScheme.primary,
                            modifier = Modifier.padding(start = 4.dp, top = 14.dp, bottom = 6.dp),
                        )
                    }
                    items(day.photos, key = { it.id }) { photo ->
                        PhotoTile(
                            uri = photo.photoUri,
                            time = Formatters.time(context, photo.timestamp),
                            onClick = { onOpenPhoto(photo.id) },
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun PhotoTile(uri: String?, time: String, onClick: () -> Unit) {
    Box(
        modifier = Modifier
            .aspectRatio(1f)
            .clip(MaterialTheme.shapes.small)
            .background(MaterialTheme.colorScheme.surfaceContainerHigh)
            .clickable(onClick = onClick),
    ) {
        AsyncImage(
            model = uri,
            contentDescription = time,
            contentScale = ContentScale.Crop,
            error = rememberVectorPainter(Icons.Rounded.BrokenImage),
            modifier = Modifier.fillMaxSize(),
        )
        Text(
            text = time,
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurface,
            modifier = Modifier
                .align(Alignment.BottomStart)
                .padding(6.dp)
                .clip(MaterialTheme.shapes.extraSmall)
                .background(MaterialTheme.colorScheme.scrim.copy(alpha = 0.55f))
                .padding(horizontal = 6.dp, vertical = 2.dp),
        )
    }
}
