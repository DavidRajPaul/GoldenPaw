package com.goldenpaw.di

import com.goldenpaw.ui.AppViewModel
import com.goldenpaw.ui.GamificationViewModel
import com.goldenpaw.ui.care.CareTeamViewModel
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
import com.goldenpaw.ui.pets.VetVisitEditorViewModel
import com.goldenpaw.ui.quicklog.QuickLogViewModel
import com.goldenpaw.ui.settings.SettingsViewModel
import com.goldenpaw.ui.today.TodayViewModel
import org.koin.core.KoinApplication
import org.koin.core.module.Module
import org.koin.core.module.dsl.viewModelOf
import org.koin.dsl.module
import com.goldenpaw.ui.records.PetRecordsViewModel
import com.goldenpaw.ui.records.RecordDetailViewModel
import com.goldenpaw.ui.records.RecordEditorViewModel

/**
 * Platform pieces the UI layer needs:
 *  - com.goldenpaw.domain.repository.ReminderGateway (AlarmManager / UNUserNotificationCenter / tray timer)
 *  - com.goldenpaw.platform.PlatformServices
 *  - com.goldenpaw.platform.PageImageProcessor
 *  - com.goldenpaw.domain.repository.DocumentTextReader
 */
expect val platformAppModule: Module

val appModule = module {
    viewModelOf(::AppViewModel)
    viewModelOf(::GamificationViewModel)
    viewModelOf(::OnboardingViewModel)
    viewModelOf(::TodayViewModel)
    viewModelOf(::CheckInViewModel)
    viewModelOf(::JournalViewModel)
    viewModelOf(::SymptomEditorViewModel)
    viewModelOf(::InsightsViewModel)
    viewModelOf(::MedsViewModel)
    viewModelOf(::MedicationEditorViewModel)
    viewModelOf(::PetsViewModel)
    viewModelOf(::PetDetailViewModel)
    viewModelOf(::PetEditorViewModel)
    viewModelOf(::VetVisitEditorViewModel)
    viewModelOf(::RecordEditorViewModel)
    viewModelOf(::RecordDetailViewModel)
    viewModelOf(::PetRecordsViewModel)
    viewModelOf(::CareTeamViewModel)
    viewModelOf(::QuickLogViewModel)
    viewModelOf(::SettingsViewModel)
}

/** Every module, in the order Koin should load them. */
fun allModules(): List<Module> = allSharedModules() + listOf(platformAppModule, appModule)

/** Shared Koin start used by Desktop and iOS (Android adds androidContext first). */
fun KoinApplication.goldenPawModules(): KoinApplication = modules(allModules())
