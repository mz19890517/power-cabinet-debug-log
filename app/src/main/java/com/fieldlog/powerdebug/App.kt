package com.fieldlog.powerdebug

import android.app.Application
import androidx.room.Room
import com.fieldlog.powerdebug.data.Repository
import com.fieldlog.powerdebug.data.db.AppDatabase

class App : Application() {

    companion object {
        lateinit var db: AppDatabase
            private set
        lateinit var repo: Repository
            private set
    }

    override fun onCreate() {
        super.onCreate()
        db = Room.databaseBuilder(this, AppDatabase::class.java, AppDatabase.DB_NAME)
            .fallbackToDestructiveMigration()
            .build()
        repo = Repository(db)
    }
}
