package com.ffps.fserv

import android.app.AlarmManager
import android.app.PendingIntent
import android.app.admin.DeviceAdminReceiver
import android.app.admin.DevicePolicyManager
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import java.io.File

/** Нужен только для режима Device Owner: `adb shell dpm set-device-owner com.ffps.fserv/.AdminReceiver`. */
class AdminReceiver : DeviceAdminReceiver()

object Power {
    private val SU_PATHS = listOf(
        "/system/bin/su", "/system/xbin/su", "/sbin/su", "/su/bin/su",
        "/system/sbin/su", "/vendor/bin/su", "/system_ext/bin/su", "/data/adb/magisk/su"
    )

    /** "device-owner", "root" или null, если перезагрузить планшет программно нельзя. */
    fun rebootMethod(ctx: Context): String? {
        try {
            val dpm = ctx.getSystemService(DevicePolicyManager::class.java)
            if (dpm != null && dpm.isDeviceOwnerApp(ctx.packageName)) return "device-owner"
        } catch (e: Exception) {
            FLog.w("power", "device owner check failed", e)
        }
        if (SU_PATHS.any { File(it).exists() }) return "root"
        return null
    }

    /** null = перезагрузка запущена; иначе текст ошибки. */
    fun reboot(ctx: Context): String? {
        return when (rebootMethod(ctx)) {
            "device-owner" -> try {
                val dpm = ctx.getSystemService(DevicePolicyManager::class.java)
                dpm.reboot(ComponentName(ctx, AdminReceiver::class.java))
                null
            } catch (e: Exception) {
                FLog.e("power", "dpm.reboot failed", e)
                "не удалось перезагрузить через Device Owner: ${e.message}"
            }
            "root" -> try {
                Runtime.getRuntime().exec(arrayOf("su", "-c", "reboot"))
                null
            } catch (e: Exception) {
                FLog.e("power", "su reboot failed", e)
                "не удалось выполнить su -c reboot: ${e.message}"
            }
            else -> "нет прав на перезагрузку: нужен Device Owner " +
                "(adb shell dpm set-device-owner com.ffps.fserv/.AdminReceiver на сброшенном планшете) или root"
        }
    }

    /**
     * Перезапуск приложения: убиваем процесс. Система сама поднимает домашний экран (мы HOME-приложение),
     * а служба стартует из MainActivity. Запасной вариант: отложенный запуск активности через AlarmManager.
     */
    fun restartApp(ctx: Context) {
        try {
            val intent = Intent(ctx, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            val pi = PendingIntent.getActivity(ctx, 1, intent, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
            ctx.getSystemService(AlarmManager::class.java).set(AlarmManager.RTC, System.currentTimeMillis() + 2000, pi)
        } catch (e: Exception) {
            FLog.w("power", "alarm for restart failed", e)
        }
        FLog.i("power", "перезапуск приложения")
        android.os.Process.killProcess(android.os.Process.myPid())
    }
}
