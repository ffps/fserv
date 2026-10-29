package com.ffps.fserv

import android.app.Application
import android.os.Build

class App : Application() {
    override fun onCreate() {
        super.onCreate()
        startedAt = System.currentTimeMillis()
        FLog.init(filesDir)
        installCrashHandler()
        Settings.init(this)
        Journal.init(this)
        TaskStore.init(this)
        FLog.minLevel = Settings.logLevel
        FLog.addSecret(Settings.password)
        FLog.addSecret(Settings.telegramToken)
        for (px in Settings.allProxies()) FLog.addSecret(px.password)
        Net.init()
        CameraEngine.init(this)
        Telegram.init(this)
        FLog.i("app", "start v${BuildConfig.VERSION_NAME} (${BuildConfig.VERSION_CODE}) sdk=${Build.VERSION.SDK_INT} ${Build.MANUFACTURER} ${Build.MODEL}")
        Journal.add("app", "Приложение запущено (версия ${BuildConfig.VERSION_NAME})")
    }

    /**
     * Сбой записывается в crash.txt (его показывает окно настроек и /api/info), служба выключается,
     * чтобы приложение не падало при каждом открытии.
     */
    private fun installCrashHandler() {
        val prev = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { t, e ->
            try {
                val sw = java.io.StringWriter()
                e.printStackTrace(java.io.PrintWriter(sw))
                java.io.File(filesDir, "crash.txt").writeText(
                    "${Util.iso()} v${BuildConfig.VERSION_NAME} sdk=${Build.VERSION.SDK_INT} ${Build.MANUFACTURER} ${Build.MODEL}\n" +
                        "поток: ${t.name}\n$sw"
                )
                FLog.e("crash", "необработанное исключение в потоке ${t.name}", e)
                Settings.setServiceEnabled(false)
            } catch (x: Throwable) {
            }
            prev?.uncaughtException(t, e)
        }
    }

    companion object {
        @JvmStatic
        @Volatile
        var startedAt: Long = 0L

        fun crashText(ctx: android.content.Context): String =
            try { java.io.File(ctx.filesDir, "crash.txt").takeIf { it.exists() }?.readText() ?: "" } catch (e: Exception) { "" }

        fun clearCrash(ctx: android.content.Context) {
            try { java.io.File(ctx.filesDir, "crash.txt").delete() } catch (e: Exception) {}
        }
    }
}
