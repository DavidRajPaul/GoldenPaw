package com.goldenpaw.data.local

import androidx.room.ConstructedBy
import androidx.room.Database
import androidx.room.RoomDatabase
import androidx.room.RoomDatabaseConstructor
import androidx.room.migration.Migration
import androidx.sqlite.SQLiteConnection
import androidx.sqlite.driver.bundled.BundledSQLiteDriver
import androidx.sqlite.execSQL
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.IO

@Database(
    entities = [
        PetEntity::class,
        MedicationEntity::class,
        DoseEventEntity::class,
        CheckInEntity::class,
        WeightEntryEntity::class,
        SymptomEntryEntity::class,
        HouseholdEntity::class,
        CaregiverEntity::class,
        WeeklySummaryEntity::class,
        SettingEntity::class,
        AchievementEntity::class,
        VetVisitEntity::class,
    ],
    version = 2,
    exportSchema = true,
)
@ConstructedBy(GoldenPawDatabaseConstructor::class)
abstract class GoldenPawDatabase : RoomDatabase() {
    abstract fun petDao(): PetDao
    abstract fun medicationDao(): MedicationDao
    abstract fun doseEventDao(): DoseEventDao
    abstract fun checkInDao(): CheckInDao
    abstract fun weightDao(): WeightDao
    abstract fun symptomDao(): SymptomDao
    abstract fun householdDao(): HouseholdDao
    abstract fun summaryDao(): SummaryDao
    abstract fun settingsDao(): SettingsDao
    abstract fun achievementDao(): AchievementDao
    abstract fun maintenanceDao(): MaintenanceDao
    abstract fun vetVisitDao(): VetVisitDao

    companion object {
        const val NAME = "goldenpaw.db"
    }
}

/** Room generates the actual implementations for each platform. */
@Suppress("NO_ACTUAL_FOR_EXPECT", "KotlinNoActualForExpect")
expect object GoldenPawDatabaseConstructor : RoomDatabaseConstructor<GoldenPawDatabase> {
    override fun initialize(): GoldenPawDatabase
}

/** Shared builder configuration. The platform supplies the builder (file location / Context). */
fun RoomDatabase.Builder<GoldenPawDatabase>.configure(): GoldenPawDatabase = this
    .setDriver(BundledSQLiteDriver())
    .setQueryCoroutineContext(Dispatchers.IO)
    .addMigrations(Migrations.MIGRATION_1_2)
    .build()

object Migrations {
    /**
     * v1 (MVP, Android only) → v2 (care teams, weekly summaries, settings in Room, achievements).
     * Statements mirror exactly what Room generates for the v2 entities so schema validation passes.
     */
    val MIGRATION_1_2: Migration = object : Migration(1, 2) {
        override fun migrate(connection: SQLiteConnection) {
            // Attribution & households on existing tables. NOT NULL columns need a default for ALTER.
            connection.execSQL("ALTER TABLE `pets` ADD COLUMN `householdId` TEXT NOT NULL DEFAULT ''")
            connection.execSQL("CREATE INDEX IF NOT EXISTS `index_pets_householdId` ON `pets` (`householdId`)")
            connection.execSQL("ALTER TABLE `dose_events` ADD COLUMN `givenById` TEXT")
            connection.execSQL("ALTER TABLE `check_ins` ADD COLUMN `loggedBy` TEXT NOT NULL DEFAULT ''")
            connection.execSQL("ALTER TABLE `check_ins` ADD COLUMN `loggedById` TEXT")
            connection.execSQL("ALTER TABLE `weight_entries` ADD COLUMN `loggedBy` TEXT NOT NULL DEFAULT ''")
            connection.execSQL("ALTER TABLE `weight_entries` ADD COLUMN `loggedById` TEXT")
            connection.execSQL("ALTER TABLE `symptom_entries` ADD COLUMN `loggedBy` TEXT NOT NULL DEFAULT ''")
            connection.execSQL("ALTER TABLE `symptom_entries` ADD COLUMN `loggedById` TEXT")

            // New v2 tables.
            connection.execSQL(
                "CREATE TABLE IF NOT EXISTS `households` (`id` TEXT NOT NULL, `name` TEXT NOT NULL, " +
                    "`ownerUserId` TEXT, `cloudEnabled` INTEGER NOT NULL, `createdAt` INTEGER NOT NULL, " +
                    "`updatedAt` INTEGER NOT NULL, `deletedAt` INTEGER, `syncState` TEXT NOT NULL, PRIMARY KEY(`id`))",
            )
            connection.execSQL(
                "CREATE TABLE IF NOT EXISTS `caregivers` (`id` TEXT NOT NULL, `householdId` TEXT NOT NULL, " +
                    "`userId` TEXT, `displayName` TEXT NOT NULL, `role` TEXT NOT NULL, `colorIndex` INTEGER NOT NULL, " +
                    "`accessUntil` INTEGER, `status` TEXT NOT NULL, `createdAt` INTEGER NOT NULL, " +
                    "`updatedAt` INTEGER NOT NULL, `syncState` TEXT NOT NULL, PRIMARY KEY(`id`))",
            )
            connection.execSQL("CREATE INDEX IF NOT EXISTS `index_caregivers_householdId` ON `caregivers` (`householdId`)")
            connection.execSQL(
                "CREATE TABLE IF NOT EXISTS `weekly_summaries` (`id` TEXT NOT NULL, `petId` TEXT NOT NULL, " +
                    "`weekStartEpochDay` INTEGER NOT NULL, `headline` TEXT NOT NULL, `summary` TEXT NOT NULL, " +
                    "`highlights` TEXT NOT NULL, `watchItems` TEXT NOT NULL, `vetQuestions` TEXT NOT NULL, " +
                    "`source` TEXT NOT NULL, `createdAt` INTEGER NOT NULL, PRIMARY KEY(`id`), " +
                    "FOREIGN KEY(`petId`) REFERENCES `pets`(`id`) ON UPDATE NO ACTION ON DELETE CASCADE )",
            )
            connection.execSQL(
                "CREATE UNIQUE INDEX IF NOT EXISTS `index_weekly_summaries_petId_weekStartEpochDay` " +
                    "ON `weekly_summaries` (`petId`, `weekStartEpochDay`)",
            )
            connection.execSQL(
                "CREATE TABLE IF NOT EXISTS `app_settings` (`key` TEXT NOT NULL, `value` TEXT NOT NULL, PRIMARY KEY(`key`))",
            )
            connection.execSQL(
                "CREATE TABLE IF NOT EXISTS `achievements` (`id` TEXT NOT NULL, `unlockedAt` INTEGER NOT NULL, " +
                    "`seen` INTEGER NOT NULL, PRIMARY KEY(`id`))",
            )
            connection.execSQL(
                "CREATE TABLE IF NOT EXISTS `vet_visits` (`id` TEXT NOT NULL, `petId` TEXT NOT NULL, " +
                    "`title` TEXT NOT NULL, `clinic` TEXT NOT NULL, `at` INTEGER NOT NULL, `notes` TEXT NOT NULL, " +
                    "`completed` INTEGER NOT NULL, `loggedBy` TEXT NOT NULL, `loggedById` TEXT, " +
                    "`createdAt` INTEGER NOT NULL, `updatedAt` INTEGER NOT NULL, `deletedAt` INTEGER, " +
                    "`syncState` TEXT NOT NULL, PRIMARY KEY(`id`), " +
                    "FOREIGN KEY(`petId`) REFERENCES `pets`(`id`) ON UPDATE NO ACTION ON DELETE CASCADE )",
            )
            connection.execSQL("CREATE INDEX IF NOT EXISTS `index_vet_visits_petId` ON `vet_visits` (`petId`)")
            // Everything from v1 is local-only; make sure nothing claims to be synced.
            connection.execSQL("UPDATE `pets` SET `syncState` = 'pending'")
        }
    }
}
