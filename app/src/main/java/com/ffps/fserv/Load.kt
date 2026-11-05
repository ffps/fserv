package com.ffps.fserv

import android.os.Debug
import android.os.Process
import android.os.SystemClock

/**
 * Раз в 10 минут пишет в журнал (DEBUG) нагрузку, которую создаёт приложение: сколько процессорного времени оно съело и какую долю
 * времени слушает голосовые команды. Нужна, чтобы оценить цену постоянного прослушивания без adb. Вызывается планировщиком каждые 10 секунд.
 */
object Load {
    private const val PERIOD_MS = 10 * 60_000L
    private var lastWall = 0L
    private var lastCpu = 0L
    private var lastListen = 0L

    /** Доля [part] от [whole] в процентах (0, если [whole] не положительно). */
    fun percent(part: Long, whole: Long): Double = if (whole <= 0) 0.0 else part * 100.0 / whole

    @Synchronized
    fun tick() {
        val now = SystemClock.elapsedRealtime()
        if (lastWall == 0L) { lastWall = now; lastCpu = Process.getElapsedCpuTime(); lastListen = VoiceTrigger.listenMs(); return }
        if (now - lastWall < PERIOD_MS) return
        val cpu = Process.getElapsedCpuTime()
        val listen = VoiceTrigger.listenMs()
        val wall = now - lastWall
        val rt = Runtime.getRuntime()
        FLog.d("load", "за ${wall / 60000} мин: процессор приложения ${"%.1f".format(percent(cpu - lastCpu, wall))}% одного ядра (ядер: ${rt.availableProcessors()}), " +
            "слушание команд ${"%.0f".format(percent(listen - lastListen, wall))}% времени, " +
            "память: нативная ${Debug.getNativeHeapAllocatedSize() / 1048576} МБ, Java ${(rt.totalMemory() - rt.freeMemory()) / 1048576} МБ")
        lastWall = now; lastCpu = cpu; lastListen = listen
    }
}
