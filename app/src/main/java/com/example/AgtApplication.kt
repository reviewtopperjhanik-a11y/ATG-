package com.example

import android.app.Application
import com.example.core.common.AgtCrashHandler
import com.example.di.AppContainer

class AgtApplication : Application() {
    lateinit var container: AppContainer
        private set

    override fun onCreate() {
        super.onCreate()
        container = AppContainer(this)
        AgtCrashHandler.install(this) {
            container.secretTokensSnapshot()
        }
    }
}
