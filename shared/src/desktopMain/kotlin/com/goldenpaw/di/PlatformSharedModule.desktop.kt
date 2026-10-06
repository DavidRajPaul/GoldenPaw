package com.goldenpaw.di

import androidx.room.Room
import androidx.room.RoomDatabase
import com.goldenpaw.data.local.GoldenPawDatabase
import com.goldenpaw.domain.repository.AppFiles
import com.goldenpaw.platform.DesktopAppFiles
import com.goldenpaw.report.ReportCanvasFactory
import com.goldenpaw.report.SimplePdfCanvas
import io.ktor.client.engine.HttpClientEngine
import io.ktor.client.engine.okhttp.OkHttp
import org.koin.core.module.Module
import org.koin.dsl.module
import java.io.File

actual val platformSharedModule: Module = module {
    single<AppFiles> { DesktopAppFiles() }
    single<RoomDatabase.Builder<GoldenPawDatabase>> {
        val files = get<AppFiles>()
        Room.databaseBuilder<GoldenPawDatabase>(name = File(files.dataDir, GoldenPawDatabase.NAME).absolutePath)
    }
    single<HttpClientEngine> { OkHttp.create() }
    single { ReportCanvasFactory { SimplePdfCanvas() } }
}
