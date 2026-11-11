package com.ffps.fserv

import android.content.Context
import java.util.Calendar
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledExecutorService
import java.util.concurrent.TimeUnit

/**
 * Раз в 10 секунд проверяет cron-расписания автоперезапуска.
 * Номер последней сработавшей минуты хранится в настройках, поэтому после перезагрузки или
 * перезапуска в ту же минуту действие не повторяется.
 */
class Scheduler(private val ctx: Context) {
    private var exec: ScheduledExecutorService? = null
    private val parsed = HashMap<String, Cron>()
    private val bad = HashSet<String>()
    private var lastWarmCheck = 0L

    @Synchronized
    fun start() {
        if (exec != null) return
        exec = Executors.newSingleThreadScheduledExecutor().also {
            it.scheduleWithFixedDelay({ tickSafe() }, 5, 10, TimeUnit.SECONDS)
        }
    }

    @Synchronized
    fun stop() {
        exec?.shutdownNow()
        exec = null
    }

    private fun tickSafe() {
        try {
            tick()
        } catch (e: Throwable) {
            FLog.e("sched", "сбой проверки расписания", e)
        }
    }

    private fun tick() {
        TaskEngine.tick(System.currentTimeMillis())
        Load.tick()
        val minute = System.currentTimeMillis() / 60000
        if (due("reboot", Settings.rebootCron, minute)) {
            Settings.setLastFired("reboot", minute)
            FLog.i("power", "перезагрузка устройства по расписанию (${Settings.rebootCron})")
            val err = Power.reboot(ctx)
            if (err != null) {
                FLog.w("sched", "reboot по расписанию невозможен: $err")
            }
            return
        }
        if (!Call.active && System.currentTimeMillis() - lastWarmCheck >= 60_000) {
            lastWarmCheck = System.currentTimeMillis()
            // если синтезатор простаивал 8 минут, прогреваем синтезатор, пока есть включённые задачи с речью
            if (TaskEngine.usesVoice()) Thread { Voice.warmIfIdle(WARM_IDLE_MS, "таймер") }.also { it.isDaemon = true }.start()
        }
        if (due("updateCheck", Settings.updateCheckCron, minute)) {
            Settings.setLastFired("updateCheck", minute)
            Thread { Updater.scheduledCheck(ctx) }.start()
        }
        if (due("appRestart", Settings.appRestartCron, minute)) {
            Settings.setLastFired("appRestart", minute)
            FLog.i("power", "перезапуск приложения по расписанию (${Settings.appRestartCron})")
            Power.restartApp(ctx)
        }
    }

    private fun due(kind: String, expr: String, minute: Long): Boolean {
        val e = expr.trim()
        if (e.isEmpty() || e in bad) return false
        if (Settings.lastFired(kind) == minute) return false
        val cron = parsed[e] ?: try {
            Cron.parse(e).also { parsed[e] = it }
        } catch (ex: IllegalArgumentException) {
            bad.add(e)
            FLog.w("sched", "расписание «$e» не разобрано: ${ex.message}")
            return false
        }
        val cal = Calendar.getInstance().apply { timeInMillis = minute * 60000 }
        return cron.matches(cal)
    }

    private companion object {
        const val WARM_IDLE_MS = 8 * 60_000L // проверка раз в минуту: движок трогается не реже, чем раз в ~9 минут (пауза появлялась после 15 минут простоя)
    }
}
