package com.goldenpaw.di

import android.content.Context
import androidx.room.Room
import androidx.room.RoomDatabase
import com.goldenpaw.data.local.GoldenPawDatabase
import com.goldenpaw.domain.repository.AppFiles
import com.goldenpaw.platform.AndroidAppFiles
import com.goldenpaw.platform.AndroidPdfCanvas
import com.goldenpaw.report.ReportCanvasFactory
import io.ktor.client.engine.HttpClientEngine
import io.ktor.client.engine.okhttp.OkHttp
import org.koin.core.module.Module
import org.koin.dsl.module
import com.goldenpaw.report.DocumentCanvasFactory
import com.goldenpaw.report.SimplePdfCanvas

actual val platformSharedModule: Module = module {
    single<RoomDatabase.Builder<GoldenPawDatabase>> {
        val context = get<Context>()
        Room.databaseBuilder<GoldenPawDatabase>(
            context = context,
            name = context.getDatabasePath(GoldenPawDatabase.NAME).absolutePath,
        )
    }
    single<HttpClientEngine> { OkHttp.create() }
    single<AppFiles> { AndroidAppFiles(get()) }
    single { ReportCanvasFactory { AndroidPdfCanvas() } }
    // Scans: the pure-Kotlin writer embeds JPEGs as-is (small files); PdfDocument only when the
    // text needs fonts beyond WinAnsi (e.g. a Tamil or Hindi pet name).
    single { DocumentCanvasFactory { unicode -> if (unicode) AndroidPdfCanvas() else SimplePdfCanvas() } }
}
