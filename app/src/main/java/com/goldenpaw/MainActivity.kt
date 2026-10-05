package com.goldenpaw

import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import com.goldenpaw.platform.reminders.NotificationHelper
import com.goldenpaw.ui.GoldenPawRoot
import kotlinx.coroutines.flow.MutableStateFlow

/** Deep-link request coming from a notification ("today", "checkin", "meds"). */
data class LaunchRequest(val open: String, val petId: String?, val nonce: Long = System.nanoTime())

class MainActivity : ComponentActivity() {

    private val launchRequests = MutableStateFlow<LaunchRequest?>(null)

    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        handleIntent(intent)
        setContent {
            GoldenPawRoot(launchRequests = launchRequests, onLaunchRequestHandled = { launchRequests.value = null })
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        handleIntent(intent)
    }

    private fun handleIntent(intent: Intent?) {
        val open = intent?.getStringExtra(NotificationHelper.EXTRA_OPEN) ?: return
        launchRequests.value = LaunchRequest(open, intent.getStringExtra(NotificationHelper.EXTRA_PET_ID))
        intent.removeExtra(NotificationHelper.EXTRA_OPEN)
    }
}
