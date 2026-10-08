package com.goldenpaw.di

import com.goldenpaw.domain.repository.ReminderGateway
import com.goldenpaw.platform.IosPlatformServices
import com.goldenpaw.platform.IosReminderScheduler
import com.goldenpaw.platform.PlatformServices
import org.koin.core.module.Module
import org.koin.dsl.bind
import org.koin.dsl.module
import com.goldenpaw.domain.repository.DocumentTextReader
import com.goldenpaw.platform.IosPageImageProcessor
import com.goldenpaw.platform.PageImageProcessor
import com.goldenpaw.platform.VisionTextReader

actual val platformAppModule: Module = module {
    single { IosReminderScheduler(get(), get(), get()) } bind ReminderGateway::class
    single<PlatformServices> { IosPlatformServices(get()) }
    single<PageImageProcessor> { IosPageImageProcessor() }
    single<DocumentTextReader> { VisionTextReader() }
}
