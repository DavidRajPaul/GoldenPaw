package com.goldenpaw.di

import com.goldenpaw.data.export.DataManager
import com.goldenpaw.data.local.GoldenPawDatabase
import com.goldenpaw.data.prefs.SettingsRepositoryImpl
import com.goldenpaw.data.repository.CheckInRepositoryImpl
import com.goldenpaw.data.repository.DoseEventRepositoryImpl
import com.goldenpaw.data.repository.LocalOnlySyncRepository
import com.goldenpaw.data.repository.MedicationRepositoryImpl
import com.goldenpaw.data.repository.PetRepositoryImpl
import com.goldenpaw.data.repository.SymptomRepositoryImpl
import com.goldenpaw.data.repository.WeightRepositoryImpl
import com.goldenpaw.domain.repository.CheckInRepository
import com.goldenpaw.domain.repository.DoseEventRepository
import com.goldenpaw.domain.repository.MedicationRepository
import com.goldenpaw.domain.repository.PetRepository
import com.goldenpaw.domain.repository.SettingsRepository
import com.goldenpaw.domain.repository.SymptomRepository
import com.goldenpaw.domain.repository.SyncRepository
import com.goldenpaw.domain.repository.WeightRepository
import com.goldenpaw.domain.usecase.LogDoseUseCase
import com.goldenpaw.domain.usecase.ObserveDoseSlotsUseCase
import com.goldenpaw.domain.usecase.ReminderGateway
import com.goldenpaw.domain.usecase.UndoDoseUseCase
import com.goldenpaw.platform.media.ImageStorage
import com.goldenpaw.platform.reminders.NotificationHelper
import com.goldenpaw.platform.reminders.ReminderScheduler
import com.goldenpaw.platform.report.VetReportDataSource
import com.goldenpaw.platform.report.VetReportGenerator
import com.goldenpaw.ui.AppViewModel
import com.goldenpaw.ui.checkin.CheckInViewModel
import com.goldenpaw.ui.insights.InsightsViewModel
import com.goldenpaw.ui.journal.JournalViewModel
import com.goldenpaw.ui.journal.SymptomEditorViewModel
import com.goldenpaw.ui.meds.MedicationEditorViewModel
import com.goldenpaw.ui.meds.MedsViewModel
import com.goldenpaw.ui.onboarding.OnboardingViewModel
import com.goldenpaw.ui.pets.PetDetailViewModel
import com.goldenpaw.ui.pets.PetEditorViewModel
import com.goldenpaw.ui.pets.PetsViewModel
import com.goldenpaw.ui.settings.SettingsViewModel
import com.goldenpaw.ui.today.TodayViewModel
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import org.koin.android.ext.koin.androidContext
import org.koin.dsl.bind
import org.koin.dsl.module

val dataModule = module {
    single { GoldenPawDatabase.build(androidContext()) }
    single { get<GoldenPawDatabase>().petDao() }
    single { get<GoldenPawDatabase>().medicationDao() }
    single { get<GoldenPawDatabase>().doseEventDao() }
    single { get<GoldenPawDatabase>().checkInDao() }
    single { get<GoldenPawDatabase>().weightDao() }
    single { get<GoldenPawDatabase>().symptomDao() }

    single<PetRepository> { PetRepositoryImpl(get()) }
    single<MedicationRepository> { MedicationRepositoryImpl(get()) }
    single<DoseEventRepository> { DoseEventRepositoryImpl(get()) }
    single<CheckInRepository> { CheckInRepositoryImpl(get()) }
    single<WeightRepository> { WeightRepositoryImpl(get()) }
    single<SymptomRepository> { SymptomRepositoryImpl(get()) }
    single<SettingsRepository> { SettingsRepositoryImpl(androidContext()) }
    single<SyncRepository> { LocalOnlySyncRepository() }
    single { DataManager(androidContext(), get(), get(), get(), get()) }
}

val platformModule = module {
    single { CoroutineScope(SupervisorJob() + Dispatchers.Default) }
    single { NotificationHelper(androidContext()) }
    single { ReminderScheduler(androidContext(), get(), get(), get(), get()) } bind ReminderGateway::class
    single { ImageStorage(androidContext()) }
    single { VetReportGenerator(androidContext()) }
    single { VetReportDataSource(get(), get(), get(), get(), get()) }
}

val domainModule = module {
    factory { ObserveDoseSlotsUseCase(get(), get()) }
    factory { LogDoseUseCase(get(), get(), get(), get()) }
    factory { UndoDoseUseCase(get(), get(), get()) }
}

val uiModule = module {
    factory { AppViewModel(get(), get(), get()) }
    factory { OnboardingViewModel(get(), get()) }
    factory { TodayViewModel(get(), get(), get(), get(), get(), get(), get(), get(), get(), get()) }
    factory { CheckInViewModel(get(), get(), get()) }
    factory { PetsViewModel(get(), get()) }
    factory { PetDetailViewModel(get(), get(), get(), get(), get(), get(), get()) }
    factory { PetEditorViewModel(get(), get(), get(), get(), get()) }
    factory { MedsViewModel(get(), get(), get()) }
    factory { MedicationEditorViewModel(get(), get(), get(), get()) }
    factory { JournalViewModel(get(), get(), get(), get(), get()) }
    factory { SymptomEditorViewModel(get(), get(), get(), get()) }
    factory { InsightsViewModel(get(), get(), get(), get(), get(), get(), get(), get(), get()) }
    factory { SettingsViewModel(get(), get(), get(), get(), get(), get()) }
}

val appModules = listOf(dataModule, platformModule, domainModule, uiModule)
