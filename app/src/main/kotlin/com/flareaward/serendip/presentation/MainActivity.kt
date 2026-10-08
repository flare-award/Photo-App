package com.flareaward.serendip.presentation

import android.content.Intent
import android.graphics.Color
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.runtime.CompositionLocalProvider
import androidx.lifecycle.lifecycleScope
import com.flareaward.serendip.di.appGraph
import com.flareaward.serendip.presentation.common.LocalAppGraph
import com.flareaward.serendip.presentation.navigation.SerendipNavHost
import com.flareaward.serendip.presentation.theme.SerendipTheme
import com.flareaward.serendip.system.AppLog
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch

class MainActivity : ComponentActivity() {

    /** Event id from a tapped "photo saved" notification, waiting for the UI to open it. */
    private val pendingPhotoEventId = MutableStateFlow<Long?>(null)

    override fun onCreate(savedInstanceState: Bundle?) {
        // The app is dark-only, so both bars always use the dark style.
        enableEdgeToEdge(
            statusBarStyle = SystemBarStyle.dark(Color.TRANSPARENT),
            navigationBarStyle = SystemBarStyle.dark(Color.TRANSPARENT),
        )
        super.onCreate(savedInstanceState)
        consumeIntent(intent)
        val graph = appGraph
        setContent {
            CompositionLocalProvider(LocalAppGraph provides graph) {
                SerendipTheme {
                    SerendipNavHost(
                        pendingPhotoEventId = pendingPhotoEventId,
                        onPendingPhotoConsumed = { pendingPhotoEventId.value = null },
                    )
                }
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        consumeIntent(intent)
    }

    override fun onStart() {
        super.onStart()
        val graph = appGraph
        graph.systemStatus.refresh()
        // The app is visible now, which is the one state in which Android lets us
        // (re)start the camera foreground service. If automatic photos are enabled
        // but the service is not running (killed, rebooted, updated), bring it back.
        lifecycleScope.launch {
            val outcome = graph.serviceController.ensureRunningIfEnabled()
            if (outcome != null) AppLog.d("ensureRunningIfEnabled from MainActivity: $outcome")
        }
    }

    private fun consumeIntent(intent: Intent?) {
        if (intent?.action == ACTION_OPEN_PHOTO) {
            val id = intent.getLongExtra(EXTRA_EVENT_ID, -1L)
            if (id > 0) pendingPhotoEventId.value = id
            // Clear so a configuration change does not re-open the photo.
            intent.action = null
            intent.removeExtra(EXTRA_EVENT_ID)
        }
    }

    companion object {
        const val ACTION_OPEN_PHOTO = "com.flareaward.serendip.action.OPEN_PHOTO"
        const val EXTRA_EVENT_ID = "event_id"
    }
}
