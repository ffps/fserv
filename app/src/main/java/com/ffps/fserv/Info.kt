package com.ffps.fserv

import android.content.Context
import android.media.AudioManager
import android.os.Build
import android.os.SystemClock
import org.json.JSONObject
import java.io.File
import java.util.TimeZone

/** Сведения об устройстве и приложении: ответ /api/info и подстановка {info.путь} в задачах. */
object Info {
    /** Значение по пути (app.version, battery.level…) в виде текста для подстановки; пусто, если такого нет. */
    fun read(ctx: Context, path: String): String = SettingsPath.text(SettingsPath.lookup(build(ctx), path))

    /** Системная яркость экрана в процентах (1..100) либо null, если прочитать не удалось. Права не нужны. */
    fun systemBrightnessPct(ctx: Context): Int? = try {
        val v = android.provider.Settings.System.getInt(ctx.contentResolver, android.provider.Settings.System.SCREEN_BRIGHTNESS)
        (v * 100 / 255.0).let { Math.round(it).toInt() }.coerceIn(0, 100)
    } catch (e: Exception) { null }

    fun autoBrightness(ctx: Context): Boolean? = try {
        android.provider.Settings.System.getInt(ctx.contentResolver, android.provider.Settings.System.SCREEN_BRIGHTNESS_MODE) == android.provider.Settings.System.SCREEN_BRIGHTNESS_MODE_AUTOMATIC
    } catch (e: Exception) { null }

    /** Уровни громкости: мультимедиа в процентах от максимума и «Громкость по умолчанию» для задач (null, если не задана). */
    private fun audioInfo(ctx: Context): JSONObject {
        val pct = try {
            val am = ctx.getSystemService(Context.AUDIO_SERVICE) as AudioManager
            val max = am.getStreamMaxVolume(AudioManager.STREAM_MUSIC)
            if (max > 0) Math.round(am.getStreamVolume(AudioManager.STREAM_MUSIC) * 100.0 / max).toInt() else null
        } catch (e: Exception) { null }
        return obj("mediaVolume" to pct, "defaultVolume" to Audio.defaultVolume())
    }

    fun build(ctx: Context): JSONObject {
        val now = System.currentTimeMillis()
        return obj(
            "app" to obj(
                "name" to "fServe",
                "version" to BuildConfig.VERSION_NAME,
                "versionCode" to BuildConfig.VERSION_CODE,
                "package" to ctx.packageName
            ),
            "device" to obj(
                "manufacturer" to Build.MANUFACTURER,
                "model" to Build.MODEL,
                "android" to Build.VERSION.RELEASE,
                "sdk" to Build.VERSION.SDK_INT,
                "ips" to arr(Util.ips())
            ),
            "time" to obj(
                "now" to Util.iso(now),
                "epochMillis" to now,
                "timezone" to TimeZone.getDefault().id,
                "appUptimeSeconds" to (now - App.startedAt) / 1000,
                "deviceUptimeSeconds" to SystemClock.elapsedRealtime() / 1000
            ),
            "battery" to Util.battery(ctx),
            "storage" to obj("freeMegabytes" to ctx.filesDir.usableSpace / 1048576, "totalMegabytes" to ctx.filesDir.totalSpace / 1048576),
            "server" to obj(
                "port" to Settings.webPort,
                "startedAt" to Util.iso(ServerService.serverStartedAt),
                "softRestarts" to ServerService.restarts
            ),
            "paths" to obj(
                "files" to ctx.filesDir.absolutePath,
                "pages" to File(ctx.filesDir, "web").absolutePath
            ),
            "screen" to screenInfo(ctx),
            "audio" to audioInfo(ctx),
            "power" to obj(
                "rebootMethod" to Power.rebootMethod(ctx),
                "appRestartCron" to Settings.appRestartCron,
                "rebootCron" to Settings.rebootCron
            ),
            "logs" to obj(
                "level" to Settings.logLevel,
                "files" to FLog.files().size,
                "sizeBytes" to FLog.totalSize()
            )
        )
    }

    private fun screenInfo(ctx: Context): JSONObject {
        val pm = ctx.getSystemService(Context.POWER_SERVICE) as android.os.PowerManager
        val km = ctx.getSystemService(Context.KEYGUARD_SERVICE) as android.app.KeyguardManager
        val home = ctx.packageManager.resolveActivity(
            android.content.Intent(android.content.Intent.ACTION_MAIN).addCategory(android.content.Intent.CATEGORY_HOME),
            android.content.pm.PackageManager.MATCH_DEFAULT_ONLY
        )?.activityInfo?.packageName
        return obj(
            "on" to pm.isInteractive,
            "locked" to km.isKeyguardLocked,
            "kioskVisible" to MainActivity.isVisible(),
            "homeApp" to home,
            "isHome" to (home == ctx.packageName),
            "overlayAllowed" to (android.os.Build.VERSION.SDK_INT < 23 || android.provider.Settings.canDrawOverlays(ctx)),
            "brightness" to systemBrightnessPct(ctx),
            "autoBrightness" to autoBrightness(ctx),
            "slideBrightness" to MainActivity.slideBrightness
        )
    }
}
