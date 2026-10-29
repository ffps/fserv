package com.ffps.fserv

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/**
 * Стресс-тест камеры, запускается из веб-интерфейса.
 *  - continuous: камера работает непрерывно N минут (как будильник, который долго ждёт движения);
 *  - cycles: камера N раз открывается, ждёт кадр и закрывается (как сценарий, который включает камеру по расписанию).
 * Во время теста пишутся счётчики и показатели устройства: температура и заряд батареи, память, число
 * открытых файловых дескрипторов (рост говорит об утечке). Итог попадает в журнал и в лог.
 */
object CameraTest {
    @Volatile var running = false
    @Volatile private var stopFlag = false
    @Volatile private var thread: Thread? = null
    private val lines = ArrayList<String>()
    @Volatile private var mode = ""
    @Volatile private var startedAt = 0L
    @Volatile private var progress = ""
    @Volatile private var result: JSONObject? = null

    private class Snap(val temp: Double, val pct: Int, val heapMb: Long, val nativeMb: Long, val fds: Int)

    private fun snap(ctx: Context): Snap {
        val b = Util.battery(ctx)
        val rt = Runtime.getRuntime()
        return Snap(
            b.optDouble("temperatureC", -1.0), b.optInt("percent", -1),
            (rt.totalMemory() - rt.freeMemory()) / 1048576, android.os.Debug.getNativeHeapAllocatedSize() / 1048576,
            File("/proc/self/fd").list()?.size ?: -1
        )
    }

    private fun snapJson(s: Snap) = obj("temperatureC" to s.temp, "batteryPercent" to s.pct, "heapMb" to s.heapMb, "nativeMb" to s.nativeMb, "openFds" to s.fds)

    @Synchronized
    private fun log(s: String) {
        lines.add(Util.iso() + " " + s)
        while (lines.size > 200) lines.removeAt(0)
        FLog.i("camtest", s)
    }

    fun status(): JSONObject {
        val l = synchronized(this) { JSONArray(lines.takeLast(60)) }
        return obj(
            "running" to running, "mode" to mode, "progress" to progress,
            "startedAt" to if (startedAt > 0) Util.iso(startedAt) else null,
            "result" to result, "log" to l
        )
    }

    fun stop() { stopFlag = true }

    /** Запуск. Возвращает текст ошибки, если запустить нельзя. */
    fun start(ctx: Context, m: String, minutes: Int, count: Int, gapSec: Int): String? {
        if (running) return "тест уже идёт"
        if (!CameraEngine.hasPermission(ctx)) return "нет разрешения на камеру: на устройстве нажмите Allow camera"
        if (m != "continuous" && m != "cycles") return "mode: continuous или cycles"
        synchronized(this) { lines.clear() }
        result = null
        mode = m
        stopFlag = false
        running = true
        startedAt = System.currentTimeMillis()
        thread = Thread({
            try {
                if (m == "continuous") runContinuous(ctx, minutes) else runCycles(ctx, count, gapSec)
            } catch (e: Throwable) {
                log("тест прерван ошибкой: ${e.javaClass.simpleName}: ${e.message}")
                FLog.e("camtest", "сбой теста", e)
            } finally {
                CameraEngine.stop("test")
                running = false
            }
        }, "camera-test").also { it.start() }
        return null
    }

    private fun finish(ctx: Context, title: String, extra: JSONObject, before: Snap) {
        val after = snap(ctx)
        val res = obj(
            "test" to title,
            "durationSec" to (System.currentTimeMillis() - startedAt) / 1000,
            "stopped" to stopFlag,
            "before" to snapJson(before), "after" to snapJson(after),
            "fdGrowth" to (after.fds - before.fds),
            "nativeMbGrowth" to (after.nativeMb - before.nativeMb)
        )
        for (k in extra.keys()) res.put(k, extra.get(k))
        result = res
        val sum = "Стресс-тест камеры ($title) завершён: " + res.toString()
        Journal.add("camera", sum.take(900))
        log("итог: $res")
    }

    private fun runContinuous(ctx: Context, minutes: Int) {
        val before = snap(ctx)
        val total = minutes.coerceIn(1, 720) * 60_000L
        log("непрерывный тест на $minutes мин")
        val f0 = CameraEngine.framesProcessed
        val r0 = CameraEngine.restarts
        val o0 = CameraEngine.opens
        CameraEngine.start("test")
        if (!CameraEngine.await(CameraEngine.State.RUNNING, 20_000)) log("камера не вышла в работу за 20 с: ${CameraEngine.lastError}")
        val end = System.currentTimeMillis() + total
        var maxTemp = before.temp
        var motionSamples = 0
        var samples = 0
        var gaps = 0
        var lastFrames = CameraEngine.framesProcessed
        var tick = 0
        while (System.currentTimeMillis() < end && !stopFlag) {
            Thread.sleep(2000)
            tick++
            val s = snap(ctx)
            if (s.temp > maxTemp) maxTemp = s.temp
            samples++
            if (CameraEngine.motion) motionSamples++
            val fr = CameraEngine.framesProcessed
            if (fr == lastFrames) gaps++
            lastFrames = fr
            progress = "осталось ${(end - System.currentTimeMillis()) / 1000} с, кадров ${fr - f0}, t=${s.temp}°C, fd=${s.fds}"
            if (tick % 30 == 0) log(progress + ", состояние ${CameraEngine.state}, ошибок: ${CameraEngine.restarts - r0}")
        }
        finish(
            ctx, "непрерывный", obj(
                "framesProcessed" to (CameraEngine.framesProcessed - f0),
                "fps" to CameraEngine.fps, "avgProcessMs" to CameraEngine.avgProcessMs,
                "restartsDuringTest" to (CameraEngine.restarts - r0), "opensDuringTest" to (CameraEngine.opens - o0),
                "twoSecWindowsWithoutFrames" to gaps, "maxTemperatureC" to maxTemp,
                "motionSamplesPercent" to if (samples > 0) motionSamples * 100 / samples else 0,
                "lastError" to CameraEngine.lastError
            ), before
        )
    }

    private fun runCycles(ctx: Context, count: Int, gapSec: Int) {
        val before = snap(ctx)
        val n = count.coerceIn(1, 2000)
        val gap = gapSec.coerceIn(0, 600)
        log("циклов: $n, пауза $gap с")
        var ok = 0
        var fail = 0
        var sumMs = 0L
        var maxMs = 0L
        var maxTemp = before.temp
        var lastErr = ""
        for (i in 1..n) {
            if (stopFlag) break
            val t0 = System.currentTimeMillis()
            CameraEngine.start("test")
            val got = CameraEngine.await(CameraEngine.State.RUNNING, 15_000) && CameraEngine.awaitProcessedFrame(5_000)
            val ms = System.currentTimeMillis() - t0
            if (got) { ok++; sumMs += ms; if (ms > maxMs) maxMs = ms } else { fail++; lastErr = CameraEngine.lastError; log("цикл $i: кадр не получен, ${CameraEngine.state} $lastErr") }
            CameraEngine.stop("test")
            CameraEngine.await(CameraEngine.State.STOPPED, 5_000)
            val s = snap(ctx)
            if (s.temp > maxTemp) maxTemp = s.temp
            progress = "цикл $i из $n: успешно $ok, сбоев $fail, fd=${s.fds}, native=${s.nativeMb} МБ"
            if (i % 10 == 0 || i == n) log(progress)
            var w = gap * 10
            while (w > 0 && !stopFlag) { Thread.sleep(100); w-- }
        }
        finish(
            ctx, "циклы", obj(
                "cyclesDone" to (ok + fail), "ok" to ok, "failed" to fail,
                "avgOpenToFrameMs" to if (ok > 0) sumMs / ok else null, "maxOpenToFrameMs" to maxMs,
                "maxTemperatureC" to maxTemp, "lastError" to lastErr
            ), before
        )
    }
}
