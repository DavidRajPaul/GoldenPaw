package com.goldenpaw.di

import com.goldenpaw.domain.repository.ReminderGateway
import com.goldenpaw.platform.DesktopNotifier
import com.goldenpaw.platform.DesktopPlatformServices
import com.goldenpaw.platform.DesktopReminderScheduler
import com.goldenpaw.platform.PlatformServices
import org.koin.core.module.Module
import org.koin.dsl.bind
import org.koin.dsl.module

actual val platformAppModule: Module = module {
    single { DesktopNotifier() }
    single { DesktopReminderScheduler(get(), get(), get(), get(), get(), get()) } bind ReminderGateway::class
    single<PlatformServices> { DesktopPlatformServices(get()) }
}
