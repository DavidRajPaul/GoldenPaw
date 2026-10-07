package com.goldenpaw

import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import com.goldenpaw.reminders.NotificationHelper
import com.goldenpaw.ui.LaunchRequest
import kotlinx.coroutines.flow.MutableStateFlow

class MainActivity : ComponentActivity() {

    private val launchRequests = MutableStateFlow<LaunchRequest?>(null)

    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        handleIntent(intent)
        setContent {
            GoldenPawApp(launchRequests = launchRequests, onLaunchRequestHandled = { launchRequests.value = null })
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        handleIntent(intent)
    }

    /** Deep links from notifications, the widget and app shortcuts. */
    private fun handleIntent(intent: Intent?) {
        val open = intent?.getStringExtra(NotificationHelper.EXTRA_OPEN) ?: return
        launchRequests.value = LaunchRequest(open, intent.getStringExtra(NotificationHelper.EXTRA_PET_ID), System.nanoTime())
        intent.removeExtra(NotificationHelper.EXTRA_OPEN)
    }
}
