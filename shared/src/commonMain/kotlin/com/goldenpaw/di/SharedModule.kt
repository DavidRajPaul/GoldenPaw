package com.goldenpaw.di

import com.goldenpaw.config.AppConfig
import com.goldenpaw.core.AppClock
import com.goldenpaw.core.SystemAppClock
import com.goldenpaw.data.export.DataManager
import com.goldenpaw.data.local.GoldenPawDatabase
import com.goldenpaw.data.local.configure
import com.goldenpaw.data.remote.AiSummaryClient
import com.goldenpaw.data.remote.SupabaseAuthRepository
import com.goldenpaw.data.remote.SupabaseConfig
import com.goldenpaw.data.remote.SupabaseSyncRepository
import com.goldenpaw.data.remote.createSupabaseHttpClient
import com.goldenpaw.data.repository.AchievementRepositoryImpl
import com.goldenpaw.data.repository.CheckInRepositoryImpl
import com.goldenpaw.data.repository.DoseEventRepositoryImpl
import com.goldenpaw.data.repository.HouseholdRepositoryImpl
import com.goldenpaw.data.repository.MedicationRepositoryImpl
import com.goldenpaw.data.repository.PetRepositoryImpl
import com.goldenpaw.data.repository.SummaryRepositoryImpl
import com.goldenpaw.data.repository.SymptomRepositoryImpl
import com.goldenpaw.data.repository.VetVisitRepositoryImpl
import com.goldenpaw.data.repository.WeightRepositoryImpl
import com.goldenpaw.data.settings.KeyValueStore
import com.goldenpaw.data.settings.SettingsRepositoryImpl
import com.goldenpaw.domain.repository.AchievementRepository
import com.goldenpaw.domain.repository.CareSyncRepository
import com.goldenpaw.domain.repository.CheckInRepository
import com.goldenpaw.domain.repository.CloudAuthRepository
import com.goldenpaw.domain.repository.DoseEventRepository
import com.goldenpaw.domain.repository.HouseholdRepository
import com.goldenpaw.domain.repository.MedicationRepository
import com.goldenpaw.domain.repository.PetRepository
import com.goldenpaw.domain.repository.SettingsRepository
import com.goldenpaw.domain.repository.SummaryGenerator
import com.goldenpaw.domain.repository.SummaryRepository
import com.goldenpaw.domain.repository.SymptomRepository
import com.goldenpaw.domain.repository.VetVisitRepository
import com.goldenpaw.domain.repository.WeightRepository
import com.goldenpaw.domain.usecase.BuildWeeklyDigestUseCase
import com.goldenpaw.domain.usecase.CareTeamService
import com.goldenpaw.domain.usecase.GenerateWeeklySummaryUseCase
import com.goldenpaw.domain.usecase.LogDoseUseCase
import com.goldenpaw.domain.usecase.ObserveDoseSlotsUseCase
import com.goldenpaw.domain.usecase.ObserveGamificationUseCase
import com.goldenpaw.domain.usecase.UndoDoseUseCase
import com.goldenpaw.report.VetReportDataSource
import com.goldenpaw.report.VetReportService
import androidx.room.RoomDatabase
import org.koin.core.module.Module
import org.koin.dsl.bind
import org.koin.dsl.module

/**
 * Platform pieces the shared module needs:
 *  - RoomDatabase.Builder<GoldenPawDatabase> (file location / Context)
 *  - io.ktor.client.engine.HttpClientEngine
 *  - com.goldenpaw.domain.repository.AppFiles
 *  - com.goldenpaw.report.ReportCanvasFactory
 */
expect val platformSharedModule: Module

val sharedModule = module {
    single<AppClock> { SystemAppClock }

    // Local database
    single { get<RoomDatabase.Builder<GoldenPawDatabase>>().configure() }
    single { get<GoldenPawDatabase>().petDao() }
    single { get<GoldenPawDatabase>().medicationDao() }
    single { get<GoldenPawDatabase>().doseEventDao() }
    single { get<GoldenPawDatabase>().checkInDao() }
    single { get<GoldenPawDatabase>().weightDao() }
    single { get<GoldenPawDatabase>().symptomDao() }
    single { get<GoldenPawDatabase>().householdDao() }
    single { get<GoldenPawDatabase>().summaryDao() }
    single { get<GoldenPawDatabase>().settingsDao() }
    single { get<GoldenPawDatabase>().achievementDao() }
    single { get<GoldenPawDatabase>().vetVisitDao() }
    single { KeyValueStore(get()) }

    // Repositories
    single<PetRepository> { PetRepositoryImpl(get(), get(), get()) }
    single<MedicationRepository> { MedicationRepositoryImpl(get(), get()) }
    single<DoseEventRepository> { DoseEventRepositoryImpl(get(), get()) }
    single<CheckInRepository> { CheckInRepositoryImpl(get(), get()) }
    single<WeightRepository> { WeightRepositoryImpl(get(), get()) }
    single<SymptomRepository> { SymptomRepositoryImpl(get(), get()) }
    single<HouseholdRepository> { HouseholdRepositoryImpl(get(), get()) }
    single<SummaryRepository> { SummaryRepositoryImpl(get()) }
    single<AchievementRepository> { AchievementRepositoryImpl(get()) }
    single<VetVisitRepository> { VetVisitRepositoryImpl(get(), get()) }
    single<SettingsRepository> { SettingsRepositoryImpl(get()) }

    // Cloud (Supabase). With no keys configured these report isAvailable = false and the app stays local.
    single { SupabaseConfig(AppConfig.SUPABASE_URL, AppConfig.SUPABASE_ANON_KEY) }
    single { createSupabaseHttpClient(get()) }
    single { SupabaseAuthRepository(get(), get(), get(), get()) } bind CloudAuthRepository::class
    single { SupabaseSyncRepository(get(), get(), get(), get(), get(), get(), get(), get()) } bind CareSyncRepository::class
    single { AiSummaryClient(get(), get(), get(), get()) } bind SummaryGenerator::class

    // Domain services & use cases
    single { CareTeamService(get(), get(), get(), get(), get()) }
    factory { ObserveDoseSlotsUseCase(get(), get(), get()) }
    factory { LogDoseUseCase(get(), get(), get(), get(), get()) }
    factory { UndoDoseUseCase(get(), get(), get()) }
    factory { BuildWeeklyDigestUseCase(get(), get(), get(), get(), get(), get(), get()) }
    factory { GenerateWeeklySummaryUseCase(get(), get(), get(), get(), get()) }
    factory { ObserveGamificationUseCase(get(), get(), get(), get(), get(), get(), get(), get()) }

    // Data export, vet report
    single { DataManager(get(), get(), get(), get()) }
    single { VetReportDataSource(get(), get(), get(), get(), get(), get()) }
    single { VetReportService(get(), get(), get()) }

    single { AppInitializer(get(), get(), get(), get()) }
}

fun allSharedModules(): List<Module> = listOf(platformSharedModule, sharedModule)
