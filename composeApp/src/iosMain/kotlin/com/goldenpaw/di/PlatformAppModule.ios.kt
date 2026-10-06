package com.goldenpaw.di

import com.goldenpaw.domain.repository.ReminderGateway
import com.goldenpaw.platform.IosPlatformServices
import com.goldenpaw.platform.IosReminderScheduler
import com.goldenpaw.platform.PlatformServices
import org.koin.core.module.Module
import org.koin.dsl.bind
import org.koin.dsl.module

actual val platformAppModule: Module = module {
    single { IosReminderScheduler(get(), get(), get()) } bind ReminderGateway::class
    single<PlatformServices> { IosPlatformServices(get()) }
}
