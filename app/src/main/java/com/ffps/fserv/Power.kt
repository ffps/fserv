package com.ffps.fserv

import android.app.admin.DeviceAdminReceiver
import android.app.admin.DevicePolicyManager
import android.content.ComponentName
import android.content.Context
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
     * Перезапуск приложения без потери экрана: веб-сервер, планировщик и киоск-страница поднимаются заново
     * внутри того же процесса. Убивать процесс нельзя: если приложение не выбрано домашним экраном,
     * после этого на экране остаётся чужая программа (так и вышло в 0.2.0).
     * Жёсткий перезапуск процесса остаётся только запасным вариантом, когда службы нет.
     */
    fun restartApp(ctx: Context) {
        val s = ServerService.instance
        if (s != null) {
            FLog.i("power", "перезапуск приложения (мягкий)")
            s.softRestart()
            return
        }
        FLog.w("power", "службы нет, перезапускаю процесс")
        android.os.Process.killProcess(android.os.Process.myPid())
    }
}
