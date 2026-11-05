package com.ffps.fserv

import android.os.Debug
import android.os.Process
import android.os.SystemClock
import android.system.Os
import android.system.OsConstants
import java.io.File

/**
 * Раз в 10 минут пишет в журнал (DEBUG) нагрузку, которую создаёт приложение: сколько процессорного времени оно съело, какие потоки
 * больше всех, какую долю времени слушает голосовые команды. Нужна, чтобы найти постоянную нагрузку и оценить цену прослушивания без adb.
 * Вызывается планировщиком каждые 10 секунд.
 */
object Load {
    private const val PERIOD_MS = 10 * 60_000L
    private var lastWall = 0L
    private var lastCpu = 0L
    private var lastListen = 0L
    private var lastThreads: Map<Int, ProcStat.T> = emptyMap()

    /** Доля [part] от [whole] в процентах (0, если [whole] не положительно). */
    fun percent(part: Long, whole: Long): Double = if (whole <= 0) 0.0 else part * 100.0 / whole

    /** Снимок потоков процесса: tid → имя и накопленное время; пусто, если /proc недоступен. */
    private fun threads(): Map<Int, ProcStat.T> = try {
        val out = HashMap<Int, ProcStat.T>()
        File("/proc/self/task").listFiles()?.forEach { d ->
            val tid = d.name.toIntOrNull() ?: return@forEach
            try { ProcStat.parse(File(d, "stat").readText())?.let { out[tid] = it } } catch (e: Exception) { /* поток уже завершился */ }
        }
        out
    } catch (e: Exception) {
        emptyMap()
    }

    private fun hz(): Long = try { Os.sysconf(OsConstants._SC_CLK_TCK).takeIf { it > 0 } ?: 100L } catch (e: Throwable) { 100L }

    @Synchronized
    fun tick() {
        val now = SystemClock.elapsedRealtime()
        if (lastWall == 0L) {
            lastWall = now; lastCpu = Process.getElapsedCpuTime(); lastListen = VoiceTrigger.listenMs(); lastThreads = threads()
            return
        }
        if (now - lastWall < PERIOD_MS) return
        val cpu = Process.getElapsedCpuTime()
        val listen = VoiceTrigger.listenMs()
        val wall = now - lastWall
        val cur = threads()
        val top = ProcStat.top(lastThreads, cur, wall, hz(), 5)
        val rt = Runtime.getRuntime()
        FLog.d("load", "за ${wall / 60000} мин: процессор приложения ${"%.1f".format(percent(cpu - lastCpu, wall))}% одного ядра (ядер: ${rt.availableProcessors()}), " +
            "слушание команд ${"%.0f".format(percent(listen - lastListen, wall))}% времени, " +
            "память: нативная ${Debug.getNativeHeapAllocatedSize() / 1048576} МБ, Java ${(rt.totalMemory() - rt.freeMemory()) / 1048576} МБ")
        FLog.d("load", "потоки (процент одного ядра): " + ProcStat.format(top))
        lastWall = now; lastCpu = cpu; lastListen = listen; lastThreads = cur
    }
}
