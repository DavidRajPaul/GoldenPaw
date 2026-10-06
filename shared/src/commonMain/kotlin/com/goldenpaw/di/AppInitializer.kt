package com.goldenpaw.di

import com.goldenpaw.data.local.PetDao
import com.goldenpaw.data.remote.SupabaseAuthRepository
import com.goldenpaw.domain.repository.SettingsRepository
import com.goldenpaw.domain.usecase.CareTeamService
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * One-time startup work shared by every platform: restore the cloud session, make sure there is a
 * household with an owner, and move pets created before care teams existed into it.
 * The UI waits for [ready] before showing data.
 */
class AppInitializer(
    private val auth: SupabaseAuthRepository,
    private val careTeam: CareTeamService,
    private val petDao: PetDao,
    private val settings: SettingsRepository,
) {
    private val mutex = Mutex()
    private var done = false
    val ready = CompletableDeferred<Unit>()

    suspend fun initialize() = mutex.withLock {
        if (done) return@withLock
        runCatching { auth.restore() }
        val household = careTeam.ensureSetup()
        petDao.adoptOrphans(household.id)
        val s = settings.current()
        if (s.ownerName.isNotBlank()) careTeam.syncOwnerName(s.ownerName)
        done = true
        ready.complete(Unit)
    }
}
