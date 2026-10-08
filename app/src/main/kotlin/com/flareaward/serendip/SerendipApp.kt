package com.flareaward.serendip

import android.app.Application
import com.flareaward.serendip.di.AppGraph
import com.flareaward.serendip.system.AppLog
import kotlinx.coroutines.launch

class SerendipApp : Application() {

    lateinit var graph: AppGraph
        private set

    override fun onCreate() {
        super.onCreate()
        graph = AppGraph(this)
        graph.notifications.ensureChannels()
        graph.appScope.launch {
            try {
                // Keep the history bounded (failed / skipped / missed rows only);
                // successful captures and the photos themselves are never touched here.
                val pruned = graph.events.pruneOlderThan(System.currentTimeMillis() - HISTORY_RETENTION_MS)
                if (pruned > 0) AppLog.d("Pruned $pruned old history entries")
            } catch (error: Exception) {
                AppLog.w("History pruning failed", error)
            }
        }
    }

    private companion object {
        const val HISTORY_RETENTION_MS = 365L * 24 * 60 * 60 * 1000
    }
}
