package com.fieldlog.powerdebug.data.db

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase

@Database(
    entities = [
        Project::class,
        CabinetType::class,
        CandidateItem::class,
        CabinetInstance::class,
        DebugLog::class,
        FaultRecord::class
    ],
    version = 1,
    exportSchema = false
)
abstract class AppDatabase : RoomDatabase() {
    abstract fun projectDao(): ProjectDao
    abstract fun cabinetTypeDao(): CabinetTypeDao
    abstract fun candidateItemDao(): CandidateItemDao
    abstract fun instanceDao(): InstanceDao
    abstract fun debugLogDao(): DebugLogDao
    abstract fun faultRecordDao(): FaultRecordDao

    companion object {
        const val DB_NAME = "power_debug.db"

        fun build(context: Context): AppDatabase =
            Room.databaseBuilder(context, AppDatabase::class.java, DB_NAME)
                .fallbackToDestructiveMigration()
                .build()
    }
}
