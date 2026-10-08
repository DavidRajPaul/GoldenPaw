package com.goldenpaw.di

import com.goldenpaw.domain.repository.ReminderGateway
import com.goldenpaw.platform.AndroidPlatformServices
import com.goldenpaw.platform.LegacySettingsMigrator
import com.goldenpaw.platform.PlatformServices
import com.goldenpaw.reminders.AndroidReminderScheduler
import com.goldenpaw.reminders.NotificationHelper
import org.koin.core.module.Module
import org.koin.dsl.bind
import org.koin.dsl.module
import com.goldenpaw.domain.repository.DocumentTextReader
import com.goldenpaw.platform.AndroidPageImageProcessor
import com.goldenpaw.platform.MlKitTextReader
import com.goldenpaw.platform.PageImageProcessor

actual val platformAppModule: Module = module {
    single { NotificationHelper(get()) }
    single { AndroidReminderScheduler(get(), get(), get(), get(), get(), get()) } bind ReminderGateway::class
    single<PlatformServices> { AndroidPlatformServices(get(), get(), get()) }
    single { LegacySettingsMigrator(get(), get()) }
    single<PageImageProcessor> { AndroidPageImageProcessor() }
    single<DocumentTextReader> { MlKitTextReader(get()) }
}
