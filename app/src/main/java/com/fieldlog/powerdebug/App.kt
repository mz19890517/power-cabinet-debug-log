package com.fieldlog.powerdebug

import android.app.Application
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
        db = AppDatabase.build(this)
        repo = Repository(db)
    }
}
