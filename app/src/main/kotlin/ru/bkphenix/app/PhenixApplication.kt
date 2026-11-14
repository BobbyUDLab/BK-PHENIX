package ru.bkphenix.app

import android.app.Application

class PhenixApplication : Application() {
    lateinit var container: AppContainer
        private set

    override fun onCreate() {
        super.onCreate()
        container = AppContainer(this)
        // Незавершённая очередь выгрузки продолжится, когда появится подходящая сеть.
        container.serverSync.scheduleUpload()
    }
}
