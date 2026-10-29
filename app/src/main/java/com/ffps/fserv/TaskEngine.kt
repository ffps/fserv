package com.ffps.fserv

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.util.ArrayDeque
import java.util.Calendar
import java.util.Random
import java.util.concurrent.LinkedBlockingQueue

/**
 * Движок задач: запускает сценарии из tasks.json по cron или вручную и выполняет их шаги по порядку.
 * Задачи идут по одной (камера и звук общие), остальные ждут в очереди. Запуск, опоздавший больше чем на late_ok
 * (по умолчанию 0: «всегда пропускать»), не выполняется и записывается в журнал. Если задача оборвалась ошибкой или
 * не уложилась в timeout, сообщение уходит в Telegram.
 */
object TaskEngine {
    private const val GRACE_MS = 60_000L // запас на то, что очередь освободилась через несколько секунд после начала минуты
    private const val MAX_LINES = 400
    private const val KEEP_RUNS = 12

    private class Stopped(val reason: String) : RuntimeException(reason)
    private class Cancelled : RuntimeException("остановлено вручную")
    private class TimedOut : RuntimeException("превышен timeout")
    private class StepError(msg: String) : RuntimeException(msg)

    class RunInfo(val id: String, val title: String, val source: String, val dry: Boolean) {
        val startedAt = System.currentTimeMillis()
        @Volatile var endedAt = 0L
        @Volatile var status = "running"
        @Volatile var message = ""
        @Volatile var step = ""
        val lines = ArrayList<String>()
    }

    private class Req(
        val taskId: String, val source: String, val dry: Boolean, val assume: Map<String, Any?>,
        val scheduledAt: Long, val allowLateMs: Long
    )

    private class Run(val ctx: Context, val root: JSONObject, val task: JSONObject, val info: RunInfo, val assume: Map<String, Any?>, deadlineMs: Long) {
        val vars = HashMap<String, Any?>()
        val rnd = Random()
        val deadline = System.currentTimeMillis() + deadlineMs
        @Volatile var cancelled = false
        val bg = ArrayList<Thread>()
        val voice: JSONObject? = root.optJSONObject("voice")
        fun stopRequested(): Boolean = cancelled || System.currentTimeMillis() > deadline
        fun title(): String = task.optString("title").ifEmpty { task.optString("id") }
    }

    private lateinit var app: Context
    private val queue = LinkedBlockingQueue<Req>()
    private var worker: Thread? = null
    @Volatile private var current: Run? = null
    private val recent = ArrayDeque<RunInfo>()
    private val nextCache = HashMap<String, Pair<String, Long>>()
    @Volatile private var cachedText = ""
    @Volatile private var cachedRoot: JSONObject? = null

    fun init(ctx: Context) {
        app = ctx.applicationContext
    }

    @Synchronized
    fun start() {
        if (worker?.isAlive == true) return
        worker = Thread({ loop() }, "task-engine").also { it.isDaemon = true; it.start() }
    }

    @Synchronized
    fun stop() {
        current?.cancelled = true
        worker?.interrupt()
        worker = null
        queue.clear()
    }

    // ---------------- конфиг ----------------

    @Synchronized
    private fun config(): JSONObject? {
        val text = try { TaskStore.read() } catch (e: Exception) { return null }
        if (text == cachedText) return cachedRoot
        cachedText = text
        cachedRoot = try { JSONObject(text) } catch (e: Exception) { null }
        return cachedRoot
    }

    private fun findTask(root: JSONObject, id: String): JSONObject? {
        val a = root.optJSONArray("tasks") ?: return null
        for (i in 0 until a.length()) a.optJSONObject(i)?.let { if (it.optString("id") == id) return it }
        return null
    }

    // ---------------- запуск ----------------

    /** Ставит задачу в очередь вручную. Возвращает текст ошибки или null. */
    fun runNow(id: String, dry: Boolean, assume: Map<String, Any?>): String? {
        val root = config() ?: return "tasks.json не читается"
        if (findTask(root, id) == null) return "задачи «$id» нет"
        if (!dry && (current?.info?.id == id || queue.any { it.taskId == id })) return "задача «$id» уже выполняется или стоит в очереди"
        queue.add(Req(id, "manual", dry, assume, System.currentTimeMillis(), Long.MAX_VALUE / 4))
        start()
        return null
    }

    fun cancelCurrent(): Boolean {
        val r = current ?: return false
        r.cancelled = true
        return true
    }

    /** Вызывается планировщиком раз в 10 секунд: ищет задачи, которым пора запускаться. */
    fun tick(now: Long) {
        val root = config() ?: return
        val tasks = root.optJSONArray("tasks") ?: return
        val nowMin = now / 60000
        for (i in 0 until tasks.length()) {
            val t = tasks.optJSONObject(i) ?: continue
            val id = t.optString("id")
            if (!t.optBoolean("enabled", true) || !t.has("cron")) continue
            val cron = try { Cron.parse(t.optString("cron")) } catch (e: IllegalArgumentException) { continue }
            val lateMs = try { if (t.has("late_ok")) Dur.parse(t.opt("late_ok"), "late_ok").maxMs else 0L } catch (e: IllegalArgumentException) { 0L }
            val lateMin = (lateMs / 60000).coerceIn(0, 720)
            var fire = -1L
            var m = nowMin
            while (m >= nowMin - lateMin) {
                if (cron.matches(Calendar.getInstance().apply { timeInMillis = m * 60000 })) { fire = m; break }
                m--
            }
            if (fire < 0 || Settings.lastFired("task:$id") >= fire) continue
            Settings.setLastFired("task:$id", fire)
            if (current?.info?.id == id || queue.any { it.taskId == id }) {
                Journal.add("task", "Запуск «${t.optString("title").ifEmpty { id }}» пропущен: предыдущий запуск ещё идёт")
                continue
            }
            queue.add(Req(id, "cron", false, emptyMap(), fire * 60000, lateMs + GRACE_MS))
            start()
        }
    }

    private fun loop() {
        while (!Thread.currentThread().isInterrupted) {
            val req = try { queue.take() } catch (e: InterruptedException) { return }
            try {
                execute(req)
            } catch (e: Throwable) {
                FLog.e("task", "сбой движка задач", e)
            }
        }
    }

    private fun record(info: RunInfo) {
        synchronized(recent) {
            recent.addFirst(info)
            while (recent.size > KEEP_RUNS) recent.removeLast()
        }
    }

    private fun execute(req: Req) {
        val root = config()
        val task = root?.let { findTask(it, req.taskId) }
        if (root == null || task == null) {
            Journal.add("task", "Задача «${req.taskId}» не запущена: её нет в конфиге")
            return
        }
        val title = task.optString("title").ifEmpty { req.taskId }
        val late = System.currentTimeMillis() - req.scheduledAt
        if (req.source == "cron" && late > req.allowLateMs) {
            val info = RunInfo(req.taskId, title, req.source, false)
            info.endedAt = System.currentTimeMillis(); info.status = "skipped"
            info.message = "опоздание ${Dur.human(late)} больше допустимого (late_ok)"
            record(info)
            Journal.add("task", "Запуск «$title» пропущен: ${info.message}")
            return
        }
        val timeout = try { if (task.has("timeout")) Dur.parse(task.opt("timeout"), "timeout").sample(Random()) else 60 * 60_000L } catch (e: IllegalArgumentException) { 60 * 60_000L }
        val info = RunInfo(req.taskId, title, req.source, req.dry)
        val run = Run(app, root, task, info, req.assume, timeout)
        current = run
        record(info)
        val srcText = if (req.source == "cron") "по расписанию" else "вручную"
        log(run, "", "запуск $srcText${if (req.dry) " (пробный: без звука, камеры и Telegram)" else ""}, timeout ${Dur.human(timeout)}")
        Journal.add("task", "Задача «$title» запущена $srcText${if (req.dry) " (пробный запуск)" else ""}")
        try {
            execSteps(run, task.optJSONArray("steps") ?: JSONArray(), "")
            info.status = "ok"
        } catch (e: Stopped) {
            info.status = "ok"; info.message = "остановлена шагом stop: ${e.reason}"
        } catch (e: Cancelled) {
            info.status = "cancelled"; info.message = "остановлена вручную"
        } catch (e: TimedOut) {
            info.status = "timeout"; info.message = "не уложилась в timeout (${Dur.human(timeout)})"
        } catch (e: StepError) {
            info.status = "error"; info.message = "шаг ${info.step}: ${e.message}"
        } catch (e: Throwable) {
            FLog.e("task", "задача $title", e)
            info.status = "error"; info.message = "шаг ${info.step}: ${e.javaClass.simpleName}: ${e.message}"
        } finally {
            cleanup(run)
            info.endedAt = System.currentTimeMillis()
            current = null
        }
        log(run, "", "итог: ${info.status}${if (info.message.isNotEmpty()) ", " + info.message else ""}")
        Journal.add("task", "Задача «$title» завершена: ${info.status}${if (info.message.isNotEmpty()) " (${info.message})" else ""}")
        if (!req.dry && req.source == "cron" && (info.status == "error" || info.status == "timeout")) {
            Telegram.enqueue("fServe: задача «$title» не выполнена: ${info.message}")
        }
    }

    private fun cleanup(r: Run) {
        r.cancelled = true
        r.bg.forEach { try { it.join(2000) } catch (e: Exception) {} }
        Voice.stop()
        CameraEngine.setOverride(null)
        CameraEngine.stop("task")
    }

    // ---------------- журнал выполнения ----------------

    private fun log(r: Run, path: String, text: String) {
        val line = "${Util.iso()} " + (if (path.isEmpty()) "" else "[$path] ") + text
        synchronized(r.info.lines) {
            r.info.lines.add(line)
            while (r.info.lines.size > MAX_LINES) r.info.lines.removeAt(0)
        }
        FLog.i("task", "${r.info.id}: " + (if (path.isEmpty()) "" else "[$path] ") + text)
    }

    // ---------------- выполнение шагов ----------------

    private fun check(r: Run) {
        if (r.cancelled) throw Cancelled()
        if (System.currentTimeMillis() > r.deadline) throw TimedOut()
    }

    private fun sleep(r: Run, ms: Long) {
        var left = ms
        while (left > 0) {
            check(r)
            val chunk = minOf(left, 100L)
            Thread.sleep(chunk)
            left -= chunk
        }
        check(r)
    }

    private fun fill(r: Run, text: String): String = Regex("""\{([A-Za-z_][A-Za-z0-9_]*)\}""").replace(text) { m ->
        val n = m.groupValues[1]
        val cal = Calendar.getInstance()
        when (n) {
            "time" -> String.format("%02d:%02d", cal.get(Calendar.HOUR_OF_DAY), cal.get(Calendar.MINUTE))
            "date" -> "${cal.get(Calendar.DAY_OF_MONTH)} ${MONTHS[cal.get(Calendar.MONTH)]}"
            "weekday" -> DAYS[cal.get(Calendar.DAY_OF_WEEK) - 1]
            "task" -> r.title()
            else -> when (val v = r.vars[n]) {
                null -> ""
                true -> "да"
                false -> "нет"
                else -> v.toString()
            }
        }
    }

    private val MONTHS = listOf("января", "февраля", "марта", "апреля", "мая", "июня", "июля", "августа", "сентября", "октября", "ноября", "декабря")
    private val DAYS = listOf("воскресенье", "понедельник", "вторник", "среда", "четверг", "пятница", "суббота")

    private fun execSteps(r: Run, a: JSONArray, path: String) {
        for (i in 0 until a.length()) {
            check(r)
            val p = if (path.isEmpty()) "${i + 1}" else "$path.${i + 1}"
            val s = a.optJSONObject(i) ?: continue
            r.info.step = p
            exec(r, s, p)
        }
    }

    private fun varsFor(r: Run): Map<String, Any?> = r.vars

    private fun exec(r: Run, s: JSONObject, p: String) {
        val dry = r.info.dry
        when {
            s.has("say") -> {
                val text = fill(r, s.optString("say"))
                log(r, p, "say: $text")
                if (!dry) {
                    val vol = if (s.has("volume")) s.optInt("volume") else r.voice?.takeIf { it.has("volume") }?.optInt("volume")
                    Voice.speak(text, vol, r.voice) { r.stopRequested() }?.let { throw StepError(it) }
                    check(r)
                }
            }
            s.has("play") -> play(r, s, p)
            s.has("telegram") -> {
                val text = fill(r, s.optString("telegram"))
                log(r, p, "telegram: $text")
                if (!dry) Telegram.enqueue(text)
            }
            s.has("wait") -> {
                val ms = Dur.parse(s.opt("wait"), "wait").sample(r.rnd)
                log(r, p, "wait: ${Dur.human(ms)}" + if (dry) " (в пробном запуске пропущено)" else "")
                if (!dry) sleep(r, ms)
            }
            s.has("watch") -> watch(r, s.getJSONObject("watch"), p)
            s.has("log") -> {
                val text = fill(r, s.optString("log"))
                log(r, p, "log: $text")
                Journal.add("task", "«${r.title()}»: $text")
            }
            s.has("stop") -> {
                val reason = fill(r, s.optString("stop")).ifEmpty { "без причины" }
                log(r, p, "stop: $reason")
                throw Stopped(reason)
            }
            s.has("if") -> {
                val cond = s.optString("if")
                val value = Expr.eval(cond, varsFor(r))
                log(r, p, "if $cond → ${if (value) "да" else "нет"}")
                val branch = if (value) s.optJSONArray("then") else s.optJSONArray("else")
                if (branch != null) execSteps(r, branch, p + if (value) ".then" else ".else")
            }
            s.has("repeat") -> repeat(r, s, p)
        }
    }

    private fun repeat(r: Run, s: JSONObject, p: String) {
        val cfg = s.getJSONObject("repeat")
        val times = Dur.sampleCount(Dur.parseCount(cfg.opt("times"), "times"), r.rnd)
        val every = if (cfg.has("every")) Dur.parse(cfg.opt("every"), "every") else null
        val until = cfg.optString("until").takeIf { it.isNotEmpty() }
        val body = s.optJSONArray("do") ?: JSONArray()
        log(r, p, "repeat: $times раз" + (every?.let { ", каждые ${Dur.human(it.minMs)}${if (it.isRange) "…${Dur.human(it.maxMs)}" else ""}" } ?: "") + (until?.let { ", до «$it»" } ?: ""))
        for (i in 1..times) {
            check(r)
            val started = System.currentTimeMillis()
            log(r, p, "итерация $i из $times")
            execSteps(r, body, "$p.do")
            if (until != null && Expr.eval(until, varsFor(r))) {
                log(r, p, "условие «$until» выполнено, повтор завершён")
                return
            }
            if (i < times && every != null) {
                val gap = every.sample(r.rnd) - (System.currentTimeMillis() - started)
                if (gap > 0) {
                    log(r, p, "до следующей итерации ${Dur.human(gap)}" + if (r.info.dry) " (пропущено в пробном запуске)" else "")
                    if (!r.info.dry) sleep(r, gap)
                }
            }
        }
    }

    private fun play(r: Run, s: JSONObject, p: String) {
        val v = s.opt("play")
        val names = if (v is JSONArray) (0 until v.length()).map { v.optString(it) } else listOf(s.optString("play"))
        val name = names[r.rnd.nextInt(names.size)]
        val fadeIn = if (s.has("fade_in")) Dur.parse(s.opt("fade_in"), "fade_in").sample(r.rnd) else 0L
        val fadeOut = if (s.has("fade_out")) Dur.parse(s.opt("fade_out"), "fade_out").sample(r.rnd) else 0L
        val forMs = if (s.has("for")) Dur.parse(s.opt("for"), "for").sample(r.rnd) else null
        val loop = s.optBoolean("loop", false)
        val wait = s.optBoolean("wait", true)
        val vol = if (s.has("volume")) s.optInt("volume") else r.voice?.takeIf { it.has("volume") }?.optInt("volume")
        log(r, p, "play: $name, громкость ${vol ?: "как есть"}, нарастание ${Dur.human(fadeIn)}, затухание ${Dur.human(fadeOut)}" +
            (forMs?.let { ", ${Dur.human(it)}" } ?: "") + (if (loop) ", по кругу" else "") + (if (!wait) ", не ждать конца" else ""))
        if (r.info.dry) return
        val job = Runnable {
            Audio.play(r.ctx, name, vol, fadeIn, fadeOut, forMs, loop) { r.stopRequested() }?.let {
                log(r, p, "ошибка звука: $it")
                if (wait) throw StepError(it)
            }
        }
        if (wait) {
            job.run()
            check(r)
        } else {
            val th = Thread({ try { job.run() } catch (e: Exception) {} }, "task-audio")
            r.bg.add(th)
            th.start()
        }
    }

    private fun watch(r: Run, w: JSONObject, p: String) {
        val forMs = Dur.parse(w.opt("for"), "for").sample(r.rnd)
        val saveAs = w.optString("save_as")
        val poll = if (w.has("poll")) Dur.parse(w.opt("poll"), "poll").sample(r.rnd).toInt() else null
        val zoneName = w.optString("zone").takeIf { it.isNotEmpty() }
        log(r, p, "watch: движение до ${Dur.human(forMs)}" + (poll?.let { ", кадр раз в ${Dur.human(it.toLong())}" } ?: "") +
            (w.optInt("threshold", 0).takeIf { it > 0 }?.let { ", порог $it" } ?: "") +
            (if (w.has("area")) ", площадь ${w.optDouble("area")}%" else "") + (zoneName?.let { ", зона $it" } ?: ""))
        var moved: Boolean
        if (r.info.dry) {
            moved = r.assume[saveAs] == true
            log(r, p, "пробный запуск: движение ${if (moved) "есть" else "нет"} (задайте assume=$saveAs:true, чтобы проверить другую ветку)")
        } else {
            val mask = zoneName?.let { z -> r.root.optJSONObject("zones")?.optJSONObject(z)?.let { CameraEngine.maskFromZone(it) } }
            CameraEngine.setOverride(
                CameraEngine.Override(
                    if (w.has("threshold")) w.optInt("threshold") else null,
                    if (w.has("area")) w.optDouble("area") else null,
                    poll, mask
                )
            )
            val end = System.currentTimeMillis() + forMs
            moved = false
            try {
                CameraEngine.start("task")
                while (CameraEngine.state != CameraEngine.State.RUNNING) {
                    if (System.currentTimeMillis() > end + 15_000) throw StepError("камера не заработала: ${CameraEngine.lastError.ifEmpty { "нет кадров" }}")
                    sleep(r, 100)
                }
                sleep(r, 1000) // дать автоэкспозиции устояться
                CameraEngine.awaitProcessedFrame(3000)
                CameraEngine.resetBaseline()
                val base = CameraEngine.motionHits
                while (System.currentTimeMillis() < end) {
                    if (CameraEngine.motionHits > base) { moved = true; break }
                    sleep(r, 100)
                }
            } finally {
                CameraEngine.stop("task")
                CameraEngine.setOverride(null)
            }
            log(r, p, "движение ${if (moved) "обнаружено" else "не обнаружено"}")
        }
        if (saveAs.isNotEmpty()) r.vars[saveAs] = moved
    }

    // ---------------- состояние для веб-интерфейса ----------------

    @Synchronized
    private fun nextRun(id: String, cronExpr: String): Long? {
        val cached = nextCache[id]
        val now = System.currentTimeMillis()
        if (cached != null && cached.first == cronExpr && cached.second > now) return cached.second
        val cron = try { Cron.parse(cronExpr) } catch (e: IllegalArgumentException) { return null }
        var m = now / 60000 + 1
        val limit = m + 8 * 1440
        while (m < limit) {
            if (cron.matches(Calendar.getInstance().apply { timeInMillis = m * 60000 })) {
                nextCache[id] = cronExpr to m * 60000
                return m * 60000
            }
            m++
        }
        return null
    }

    private fun runJson(i: RunInfo, withLines: Boolean): JSONObject {
        val o = obj(
            "task" to i.id, "title" to i.title, "source" to i.source, "dry" to i.dry,
            "started" to Util.iso(i.startedAt), "ended" to if (i.endedAt > 0) Util.iso(i.endedAt) else null,
            "status" to i.status, "message" to i.message, "step" to i.step
        )
        if (withLines) synchronized(i.lines) { o.put("lines", arr(i.lines)) }
        return o
    }

    fun status(): JSONObject {
        val root = config()
        val tasks = JSONArray()
        root?.optJSONArray("tasks")?.let { a ->
            for (i in 0 until a.length()) {
                val t = a.optJSONObject(i) ?: continue
                val id = t.optString("id")
                val cronExpr = t.optString("cron")
                val next = if (t.optBoolean("enabled", true) && cronExpr.isNotEmpty()) nextRun(id, cronExpr) else null
                tasks.put(
                    obj(
                        "id" to id, "title" to t.optString("title"), "enabled" to t.optBoolean("enabled", true),
                        "cron" to cronExpr.ifEmpty { null }, "next" to next?.let { Util.iso(it) },
                        "running" to (current?.info?.id == id), "queued" to queue.any { it.taskId == id }
                    )
                )
            }
        }
        val runs = JSONArray()
        synchronized(recent) { recent.forEachIndexed { n, r -> runs.put(runJson(r, n == 0)) } }
        return obj(
            "configOk" to (root != null), "tasks" to tasks,
            "running" to current?.let { runJson(it.info, false) },
            "queue" to arr(queue.map { it.taskId + if (it.dry) " (пробный)" else "" }),
            "runs" to runs
        )
    }
}
