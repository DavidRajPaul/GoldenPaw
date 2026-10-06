package com.goldenpaw

import androidx.compose.ui.window.ComposeUIViewController
import com.goldenpaw.di.allModules
import com.goldenpaw.platform.IosNotificationDelegate
import com.goldenpaw.platform.IosReminderScheduler
import com.goldenpaw.ui.LaunchRequest
import kotlinx.coroutines.flow.MutableStateFlow
import org.koin.core.context.startKoin
import org.koin.mp.KoinPlatform
import platform.Foundation.NSDate
import platform.Foundation.timeIntervalSince1970
import platform.UIKit.UIViewController
import platform.UserNotifications.UNUserNotificationCenter

private val launchRequests = MutableStateFlow<LaunchRequest?>(null)
private var notificationDelegate: IosNotificationDelegate? = null

/** Called once from the Swift App init, before any UI. */
fun initKoin() {
    val koin = startKoin { modules(allModules()) }.koin
    val reminders = koin.get<IosReminderScheduler>()
    reminders.registerCategories()
    val delegate = IosNotificationDelegate(koin.get(), koin.get()) { open, petId ->
        launchRequests.value = LaunchRequest(open, petId, (NSDate().timeIntervalSince1970 * 1000).toLong())
    }
    notificationDelegate = delegate // UNUserNotificationCenter holds its delegate weakly
    UNUserNotificationCenter.currentNotificationCenter().delegate = delegate
}

@Suppress("FunctionName", "unused")
fun MainViewController(): UIViewController {
    if (KoinPlatform.getKoinOrNull() == null) initKoin()
    return ComposeUIViewController {
        GoldenPawApp(launchRequests = launchRequests, onLaunchRequestHandled = { launchRequests.value = null })
    }
}
