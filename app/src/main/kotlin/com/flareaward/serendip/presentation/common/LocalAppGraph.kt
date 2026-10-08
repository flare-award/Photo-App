package com.flareaward.serendip.presentation.common

import androidx.compose.runtime.staticCompositionLocalOf
import com.flareaward.serendip.di.AppGraph

/** The process-wide dependency graph, provided once by MainActivity. */
val LocalAppGraph = staticCompositionLocalOf<AppGraph> {
    error("LocalAppGraph is not provided")
}
