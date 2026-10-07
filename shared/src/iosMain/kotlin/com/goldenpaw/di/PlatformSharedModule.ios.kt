package com.goldenpaw.di

import androidx.room.Room
import androidx.room.RoomDatabase
import com.goldenpaw.data.local.GoldenPawDatabase
import com.goldenpaw.domain.repository.AppFiles
import com.goldenpaw.platform.IosAppFiles
import com.goldenpaw.report.ReportCanvasFactory
import com.goldenpaw.report.SimplePdfCanvas
import io.ktor.client.engine.HttpClientEngine
import io.ktor.client.engine.darwin.Darwin
import org.koin.core.module.Module
import org.koin.dsl.module

actual val platformSharedModule: Module = module {
    single<AppFiles> { IosAppFiles() }
    single<RoomDatabase.Builder<GoldenPawDatabase>> {
        val files = get<AppFiles>()
        Room.databaseBuilder<GoldenPawDatabase>(name = "${files.dataDir}/${GoldenPawDatabase.NAME}")
    }
    single<HttpClientEngine> { Darwin.create() }
    single { ReportCanvasFactory { SimplePdfCanvas() } }
}
