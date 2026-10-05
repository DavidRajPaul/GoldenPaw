package com.goldenpaw.data.local

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase

@Database(
    entities = [
        PetEntity::class,
        MedicationEntity::class,
        DoseEventEntity::class,
        CheckInEntity::class,
        WeightEntryEntity::class,
        SymptomEntryEntity::class,
    ],
    version = 1,
    exportSchema = true,
)
abstract class GoldenPawDatabase : RoomDatabase() {
    abstract fun petDao(): PetDao
    abstract fun medicationDao(): MedicationDao
    abstract fun doseEventDao(): DoseEventDao
    abstract fun checkInDao(): CheckInDao
    abstract fun weightDao(): WeightDao
    abstract fun symptomDao(): SymptomDao

    companion object {
        const val NAME = "goldenpaw.db"

        fun build(context: Context): GoldenPawDatabase =
            Room.databaseBuilder(context, GoldenPawDatabase::class.java, NAME)
                // Future schema changes: add @AutoMigration entries + exported schemas in /app/schemas.
                .build()
    }
}
