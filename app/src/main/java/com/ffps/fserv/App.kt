package com.ffps.fserv

import android.app.Application
import android.os.Build

class App : Application() {
    override fun onCreate() {
        super.onCreate()
        startedAt = System.currentTimeMillis()
        FLog.init(filesDir)
        Settings.init(this)
        Journal.init(this)
        TaskStore.init(this)
        FLog.minLevel = Settings.logLevel
        FLog.addSecret(Settings.password)
        FLog.i("app", "start v${BuildConfig.VERSION_NAME} (${BuildConfig.VERSION_CODE}) sdk=${Build.VERSION.SDK_INT} ${Build.MANUFACTURER} ${Build.MODEL}")
        Journal.add("app", "Приложение запущено (версия ${BuildConfig.VERSION_NAME})")
    }

    companion object {
        @JvmStatic
        @Volatile
        var startedAt: Long = 0L
    }
}
