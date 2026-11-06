package com.ffps.fserv

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.util.ArrayDeque
import java.util.Calendar
import java.util.Random
import java.util.concurrent.LinkedBlockingQueue

/**
 * Движок задач: запускает сценарии из файлов tasks/<id>.txt по cron или вручную и выполняет их шаги по порядку.
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
    private class SlotStopped : RuntimeException("фоновое действие остановлено")

    /** Фоновое действие (звук, речь или блок background): у каждого вида один слот, новое заменяет прежнее. */
    private class Slot(val name: String) {
        @Volatile var stop = false
        @Volatile var thread: Thread? = null
    }

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
        val scheduledAt: Long, val allowLateMs: Long,
        /** Услышанная фраза запуска голосом (подстановка {phrase}); пусто при другом запуске. */
        val phrase: String = "",
        /** Пометка для журнала (что именно услышано, если не точно фраза). */
        val note: String = ""
    ) {
        /** Приоритет в очереди (preempt): больше — раньше; при равном порядок поступления. */
        @Volatile var level = 0
        val seq = reqSeq.incrementAndGet()
    }

    private val reqSeq = java.util.concurrent.atomic.AtomicLong()

    private class Run(val ctx: Context, val root: JSONObject, val task: JSONObject, val info: RunInfo, val assume: Map<String, Any?>, deadlineMs: Long) {
        val vars = java.util.concurrent.ConcurrentHashMap<String, Any?>()
        val rnd = Random()
        val deadline = System.currentTimeMillis() + deadlineMs
        @Volatile var cancelled = false
        /** Название задачи, которая прервала этот запуск (preempt), или null. */
        @Volatile var preemptedBy: String? = null
        val bg = ArrayList<Thread>()
        /** Номера слайдов, показанных этим запуском: в конце запуска скрываются, если ещё на экране. */
        val slideTokens = java.util.concurrent.CopyOnWriteArrayList<Int>()
        val slots = java.util.concurrent.ConcurrentHashMap<String, Slot>()
        /** Слот, в потоке которого сейчас выполняется код (null в основном ходе задачи). */
        val slotOf = ThreadLocal<Slot?>()
        val voice: JSONObject? = root.optJSONObject("voice")
        /** Подстановка {phrase}: что человек сказал после имени помощника (запуск голосом) или assume=phrase:… в пробном запуске. */
        @Volatile var phrase = ""
        fun stopRequested(): Boolean = cancelled || System.currentTimeMillis() > deadline || slotOf.get()?.stop == true
        fun title(): String = task.optString("title").ifEmpty { task.optString("id") }
    }

    private lateinit var app: Context
    private val queueOrder = Comparator<Req> { a, b -> if (a.level != b.level) b.level - a.level else a.seq.compareTo(b.seq) }
    private val queue = java.util.concurrent.PriorityBlockingQueue<Req>(11, queueOrder)
    private var worker: Thread? = null
    @Volatile private var current: Run? = null
    private val recent = ArrayDeque<RunInfo>()
    private val nextCache = HashMap<String, Pair<String, Long>>()

    fun init(ctx: Context) {
        app = ctx.applicationContext
        Thread { refreshStt() }.start()
    }

    /** Виды ответов (yesno, number) всех шагов ask в исправных задачах. */
    private fun askKinds(node: Any?, out: MutableSet<String>) {
        when (node) {
            is JSONArray -> for (i in 0 until node.length()) askKinds(node.opt(i), out)
            is JSONObject -> {
                if (node.has("ask")) out.add(node.optString("kind", "yesno"))
                if (node.has("chat")) out.add("free")
                for (k in listOf("then", "else", "do", "background", "steps")) askKinds(node.opt(k), out)
            }
        }
    }

    private fun hasVoiceStep(node: Any?): Boolean = when (node) {
        is JSONArray -> (0 until node.length()).any { hasVoiceStep(node.opt(it)) }
        is JSONObject -> node.has("say") || node.has("ask") || node.has("chat") || listOf("then", "else", "do", "background", "steps").any { hasVoiceStep(node.opt(it)) }
        else -> false
    }

    /** Есть ли включённые задачи с речью (say, ask): ради них синтезатор прогревается (см. Voice.warmIfIdle). */
    fun usesVoice(): Boolean {
        val tasks = config().optJSONArray("tasks") ?: return false
        return (0 until tasks.length()).any { i -> tasks.optJSONObject(i)?.let { it.optBoolean("enabled", true) && hasVoiceStep(it.opt("steps")) } == true }
    }

    /** Если в задачах есть ask, держит модель распознавания речи в памяти; иначе позволяет выгрузить. Вызывать при старте и после правки задач. */
    fun refreshStt() {
        try {
            val kinds = HashSet<String>()
            askKinds(TaskStore.load().root.optJSONArray("tasks"), kinds)
            Stt.pin(kinds)
        } catch (e: Exception) {
            FLog.w("task", "подготовка распознавания речи не удалась: ${e.message}")
        }
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

    /** Голос, зоны и исправные задачи; задачи с ошибками сюда не попадают (они видны в редакторе). */
    private fun config(): JSONObject = TaskStore.load().root

    /** Задача выполняется или стоит в очереди (кнопка звонка на киоске в это время не нажимается). */
    fun isBusy(id: String): Boolean = current?.info?.id == id || queue.any { it.taskId == id }

    /** Есть ли задача с таким id, и включена ли она (для кнопки «звонок» на вкладке «Звонок»: задача ring). */
    fun taskEnabled(id: String): Boolean = findTask(config(), id)?.optBoolean("enabled", true) == true

    private fun findTask(root: JSONObject, id: String): JSONObject? {
        val a = root.optJSONArray("tasks") ?: return null
        for (i in 0 until a.length()) a.optJSONObject(i)?.let { if (it.optString("id") == id) return it }
        return null
    }

    // ---------------- запуск ----------------

    /** Ставит задачу в очередь вручную. Возвращает текст ошибки или null. */
    fun runNow(id: String, dry: Boolean, assume: Map<String, Any?>): String? {
        val root = config()
        if (findTask(root, id) == null) return TaskStore.load().errors[id]?.let { "в задаче «$id» ошибка, она не запускается: $it" } ?: "задачи «$id» нет"
        if (!dry && (current?.info?.id == id || queue.any { it.taskId == id })) return "задача «$id» уже выполняется или стоит в очереди"
        enqueue(Req(id, "manual", dry, assume, System.currentTimeMillis(), Long.MAX_VALUE / 4), findTask(root, id))
        start()
        return null
    }

    /** Фраза запуска голосом: [full] — что слушается (с префиксом), [rest] — то же без префикса (подстановка {phrase}). */
    class VoiceEntry(val task: String, val full: String, val rest: String, val wild: Boolean = false)

    private var vpKey = ""
    private var vpVal: List<VoiceEntry> = emptyList()
    private var vpWarned = ""

    /**
     * Фразы запуска голосом у включённых задач с настройкой voice, развёрнутые из шаблонов и дополненные префиксом из настроек.
     * Если префикс в настройках неверен, слушаются только фразы «=…». Одна фраза у двух задач достаётся первой по списку
     * (вторая попадает в журнал один раз). Результат кэшируется, пока не меняются задачи и настройки.
     */
    @Synchronized
    fun voicePhrases(): List<VoiceEntry> {
        val tasks = config().optJSONArray("tasks") ?: return emptyList()
        val prefixText = Settings.listenPrefix
        val key = StringBuilder(prefixText).append('\u0001')
        for (i in 0 until tasks.length()) {
            val t = tasks.optJSONObject(i) ?: continue
            if (t.optBoolean("enabled", true) && t.has("voice")) key.append(t.optString("id")).append('\u0002').append(t.optString("voice")).append('\u0003')
        }
        val k = key.toString()
        if (k == vpKey) return vpVal
        val prefixes = try { VoiceCmd.expandPrefixes(prefixText) } catch (e: IllegalArgumentException) {
            if (vpWarned != prefixText) { vpWarned = prefixText; FLog.w("task", "префикс голосовых команд неверен (${e.message}): слушаются только фразы «=…»") }
            null
        }
        val out = ArrayList<VoiceEntry>()
        for (i in 0 until tasks.length()) {
            val t = tasks.optJSONObject(i) ?: continue
            if (!t.optBoolean("enabled", true) || !t.has("voice")) continue
            val id = t.optString("id")
            val list = try { VoiceCmd.expandPhrases(t.optString("voice")) } catch (e: IllegalArgumentException) { continue }
            val fulls = if (prefixes == null) list.filter { it.raw && it.text.isNotEmpty() }.map { VoiceCmd.Full(it.text, it.text, it.wild) } else VoiceCmd.fullEntries(list, prefixes)
            for (f in fulls) {
                val prev = out.firstOrNull { it.full == f.full }
                if (prev == null) out.add(VoiceEntry(id, f.full, f.rest, f.wild))
                else if (prev.task != id && voiceDupWarned.add("$id:${f.full}")) FLog.w("task", "фраза «${f.full}» есть у задач «${prev.task}» и «$id»: сработает «${prev.task}»")
            }
        }
        vpKey = k; vpVal = out
        return out
    }

    private val voiceDupWarned = HashSet<String>()

    /**
     * Ставит задачу в очередь по голосовой команде ([heard] — что услышано целиком; [tail] — свободный хвост после фразы со знаком «*»).
     * Возвращает текст причины, если не запущена, или null.
     */
    fun runByVoice(e: VoiceEntry, heard: String, tail: String = ""): String? {
        val root = config()
        val task = findTask(root, e.task) ?: return "задачи «${e.task}» нет"
        if (current?.info?.id == e.task || queue.any { it.taskId == e.task }) return "задача «${e.task}» уже выполняется или стоит в очереди"
        val spoken = if (e.wild) "${e.full} $tail".trim() else heard
        val note = if (e.wild || VoiceCmd.words(heard).joinToString(" ") != e.full) " (услышано: «$spoken»)" else ""
        val phrase = if (e.wild) "${e.rest} $tail".trim() else e.rest
        enqueue(Req(e.task, "voice", false, emptyMap(), System.currentTimeMillis(), Long.MAX_VALUE / 4, phrase, note), task)
        start()
        return null
    }

    /** Приоритет задачи: preempt=false — 0, true — 1, число 0..9 как есть; у задачи call по умолчанию 9. */
    private fun level(task: JSONObject?): Int = TaskSchema.preemptLevel(task)

    /** Задача с приоритетом выше 0 встаёт в очереди перед задачами с меньшим приоритетом и прерывает выполняющуюся, если у той приоритет меньше. */
    private fun enqueue(req: Req, task: JSONObject?) {
        val lv = if (req.dry) 0 else level(task)
        req.level = lv
        queue.add(req)
        if (lv == 0) return
        val cur = current
        if (cur != null && lv > level(cur.task) && !cur.cancelled) {
            val who = task?.optString("title").orEmpty().ifEmpty { req.taskId }
            cur.preemptedBy = who
            cur.cancelled = true
            FLog.w("task", "задача «${cur.title()}» прервана: запускается «$who» с приоритетом (preempt)")
        }
    }

    fun cancelCurrent(): Boolean {
        val r = current ?: return false
        r.cancelled = true
        return true
    }

    /** Вызывается планировщиком раз в 10 секунд: ищет задачи, которым пора запускаться. */
    fun tick(now: Long) {
        val root = config()
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
                FLog.w("task", "запуск «${t.optString("title").ifEmpty { id }}» пропущен: предыдущий запуск ещё идёт")
                continue
            }
            enqueue(Req(id, "cron", false, emptyMap(), fire * 60000, lateMs + GRACE_MS), t)
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
            FLog.w("task", "задача «${req.taskId}» не запущена: её нет в конфиге")
            return
        }
        val title = task.optString("title").ifEmpty { req.taskId }
        val late = System.currentTimeMillis() - req.scheduledAt
        if (req.source == "cron" && late > req.allowLateMs) {
            val info = RunInfo(req.taskId, title, req.source, false)
            info.endedAt = System.currentTimeMillis(); info.status = "skipped"
            info.message = "опоздание ${Dur.human(late)} больше допустимого (late_ok)"
            record(info)
            FLog.w("task", "запуск «$title» пропущен: ${info.message}")
            return
        }
        val timeout = try { if (task.has("timeout")) Dur.parse(task.opt("timeout"), "timeout").sample(Random()) else 60 * 60_000L } catch (e: IllegalArgumentException) { 60 * 60_000L }
        val info = RunInfo(req.taskId, title, req.source, req.dry)
        val run = Run(app, root, task, info, req.assume, timeout)
        run.phrase = if (req.dry) req.assume["phrase"]?.toString() ?: "" else req.phrase
        current = run
        record(info)
        val srcText = when (req.source) { "cron" -> "по расписанию"; "voice" -> "по голосовой команде «${req.phrase}»${req.note}"; else -> "вручную" }
        log(run, "", "запуск $srcText${if (req.dry) " (пробный: без звука, камеры и Telegram)" else ""}, timeout ${Dur.human(timeout)}")
        FLog.i("task", "задача «$title» запущена $srcText${if (req.dry) " (пробный запуск)" else ""}")
        // синтезатор мог выгрузиться за время простоя: прогреваем его, пока идут первые шаги задачи
        if (!req.dry && hasVoiceStep(task.opt("steps"))) Thread { Voice.warmIfIdle(60_000, "старт задачи") }.also { it.isDaemon = true }.start()
        try {
            execSteps(run, task.optJSONArray("steps") ?: JSONArray(), "")
            info.status = "ok"
        } catch (e: Stopped) {
            info.status = "ok"; info.message = "остановлена шагом stop: ${e.reason}"
        } catch (e: Cancelled) {
            info.status = "cancelled"; info.message = run.preemptedBy?.let { "прервана задачей «$it» (preempt)" } ?: "остановлена вручную"
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
        val endMsg = "Задача «$title» завершена: ${info.status}${if (info.message.isNotEmpty()) " (${info.message})" else ""}"
        if (info.status == "error" || info.status == "timeout") FLog.e("task", endMsg) else FLog.i("task", endMsg)
        if (!req.dry && (req.source == "cron" || req.source == "voice") && (info.status == "error" || info.status == "timeout")) {
            Telegram.enqueue("fServe: задача «$title» не выполнена: ${info.message}")
        }
    }

    private fun cleanup(r: Run) {
        r.cancelled = true
        r.bg.forEach { try { it.join(2000) } catch (e: Exception) {} }
        for (t in r.slideTokens) Slide.release(t)
        Voice.stop()
        Stt.touch()
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
        FLog.d("task", "${r.info.id}: " + (if (path.isEmpty()) "" else "[$path] ") + text)
    }

    // ---------------- выполнение шагов ----------------

    private fun check(r: Run) {
        if (r.cancelled) throw Cancelled()
        if (System.currentTimeMillis() > r.deadline) throw TimedOut()
        if (r.slotOf.get()?.stop == true) throw SlotStopped()
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

    /** Текст шага: строка как есть, из списка фраз берётся случайная. */
    private fun pickText(r: Run, v: Any?): String = when (v) {
        is JSONArray -> if (v.length() == 0) "" else v.optString(r.rnd.nextInt(v.length()))
        else -> v?.toString() ?: ""
    }

    private fun fill(r: Run, text: String): String = Regex("""\{([A-Za-z_][A-Za-z0-9_]*)\}""").replace(text) { m ->
        val n = m.groupValues[1]
        val cal = Calendar.getInstance()
        when (n) {
            "time" -> String.format("%02d:%02d", cal.get(Calendar.HOUR_OF_DAY), cal.get(Calendar.MINUTE))
            "date" -> "${cal.get(Calendar.DAY_OF_MONTH)} ${MONTHS[cal.get(Calendar.MONTH)]}"
            "weekday" -> DAYS[cal.get(Calendar.DAY_OF_WEEK) - 1]
            "task" -> r.title()
            "phrase" -> r.phrase
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

    /** Слайды, показанные шагами текущего блока (в этом потоке): при выходе из блока скрываются, если ещё на экране. */
    private val slideScope = ThreadLocal<ArrayList<Int>?>()

    private fun execSteps(r: Run, a: JSONArray, path: String) {
        val outer = slideScope.get()
        val mine = ArrayList<Int>()
        slideScope.set(mine)
        try {
            for (i in 0 until a.length()) {
                check(r)
                val p = if (path.isEmpty()) "${i + 1}" else "$path.${i + 1}"
                val s = a.optJSONObject(i) ?: continue
                if (r.slotOf.get() == null) r.info.step = p
                exec(r, s, p)
            }
        } finally {
            slideScope.set(outer)
            for (t in mine) Slide.release(t)
        }
    }

    private fun varsFor(r: Run): Map<String, Any?> = r.vars

    private fun exec(r: Run, s: JSONObject, p: String) {
        val dry = r.info.dry
        when {
            s.has("say") -> {
                val text = fill(r, pickText(r, s.opt("say")))
                val wait = s.optBoolean("wait", true)
                log(r, p, "say: $text" + if (!wait) " (в фоне)" else "")
                if (!dry) {
                    val vol = if (s.has("volume")) s.optInt("volume") else r.voice?.takeIf { it.has("volume") }?.optInt("volume")
                    val speak = { Voice.speak(text, vol, r.voice) { r.stopRequested() }?.let { throw StepError(it) } }
                    if (wait) { speak(); check(r) } else startSlot(r, "say", p) { speak() }
                }
            }
            s.has("ask") -> ask(r, s, p)
            s.has("chat") -> chat(r, s, p)
            s.has("play") -> play(r, s, p)
            s.has("slide") -> slide(r, s, p)
            s.has("photo") -> photo(r, s, p)
            s.has("background") -> {
                val block = s.getJSONArray("background")
                log(r, p, "background: ${block.length()} шаг(ов) в фоне" + if (dry) " (в пробном запуске выполняются здесь же, без звука и пауз)" else "")
                if (dry) execSteps(r, block, "$p.bg") else startSlot(r, "block", p) { execSteps(r, block, "$p.bg") }
            }
            s.has("stop_background") -> {
                val was = if (dry) false else stopAllSlots(r)
                log(r, p, "stop_background: " + if (dry) "в пробном запуске пропущено" else if (was) "фоновые действия остановлены" else "фоновых действий не было")
            }
            s.has("telegram") -> {
                val text = fill(r, s.optString("telegram"))
                val names = when (val f = s.opt("files")) {
                    is String -> listOf(f)
                    is JSONArray -> (0 until f.length()).map { f.optString(it) }
                    else -> emptyList()
                }
                val paths = names.mapNotNull { n -> (r.vars[n] as? String)?.takeIf { it.isNotEmpty() } }
                log(r, p, "telegram: $text" + if (names.isNotEmpty()) " + фото: ${paths.size} из ${names.size}" + (if (paths.size < names.size) " (остальные переменные пусты: фото не снято)" else "") else "")
                val name = s.optString("assign")
                var id = 0L
                if (!dry) id = Telegram.enqueue(text, paths)
                if (s.has("confirm")) {
                    val ms = Dur.parse(s.opt("confirm"), "confirm").sample(r.rnd)
                    val ok = if (dry) r.assume[name] != false else confirmTelegram(r, id, ms)
                    log(r, p, if (dry) "пробный запуск: сообщение ${if (ok) "доставлено" else "не доставлено"} (задайте assume=$name:false, чтобы проверить другую ветку)"
                        else if (ok) "telegram: доставлено" else "telegram: не доставлено за ${Dur.human(ms)}, остаётся в очереди и уйдёт позже")
                    if (name.isNotEmpty()) r.vars[name] = ok
                }
            }
            s.has("delay") -> {
                val ms = Dur.parse(s.opt("delay"), "delay").sample(r.rnd)
                log(r, p, "delay: ${Dur.human(ms)}" + if (dry) " (в пробном запуске пропущено)" else "")
                if (!dry) sleep(r, ms)
            }
            s.has("watch") -> watch(r, s.getJSONObject("watch"), p)
            s.has("log") -> {
                val text = fill(r, s.optString("log"))
                log(r, p, "log: $text")
                FLog.i("task", "«${r.title()}»: $text")
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
        val every = if (cfg.has("every")) Dur.parseList(cfg.opt("every"), "every") else null
        val until = cfg.optString("until").takeIf { it.isNotEmpty() }
        val body = s.optJSONArray("do") ?: JSONArray()
        log(r, p, "repeat: $times раз" + (every?.let { l -> ", каждые " + l.joinToString(", ") { Dur.human(it.minMs) + if (it.isRange) "…${Dur.human(it.maxMs)}" else "" } + if (l.size > 1) " (дальше как последнее)" else "" } ?: "") + (until?.let { ", до «$it»" } ?: ""))
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
                val gap = Dur.pick(every, i - 1, r.rnd) - (System.currentTimeMillis() - started)
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
        val pauses = if (s.has("pause")) Dur.parseList(s.opt("pause"), "pause") else null
        val vol = if (s.has("volume")) s.optInt("volume") else r.voice?.takeIf { it.has("volume") }?.optInt("volume")
        log(r, p, "play: $name, громкость ${vol ?: "как есть"}, нарастание ${Dur.human(fadeIn)}, затухание ${Dur.human(fadeOut)}" +
            (forMs?.let { ", ${Dur.human(it)}" } ?: "") + (if (loop) ", по кругу" else "") + (pauses?.let { l -> ", паузы " + l.joinToString(", ") { Dur.human(it.minMs) + if (it.isRange) "…${Dur.human(it.maxMs)}" else "" } } ?: "") + (if (!wait) ", не ждать конца" else ""))
        if (r.info.dry) return
        val job = {
            Audio.play(r.ctx, name, vol, fadeIn, fadeOut, forMs, loop, pauses, r.rnd) { r.stopRequested() }?.let {
                if (wait) throw StepError(it) else log(r, p, "ошибка звука: $it")
            }
        }
        if (wait) {
            job()
            check(r)
        } else {
            startSlot(r, "play", p) { job() }
        }
    }

    /** Запускает [body] в фоне в слоте [name]; прежнее действие этого слота перед этим останавливается. */
    private fun startSlot(r: Run, name: String, p: String, body: () -> Unit) {
        stopSlot(r, name)
        val slot = Slot(name)
        val th = Thread({
            r.slotOf.set(slot)
            try {
                body()
            } catch (e: SlotStopped) {
            } catch (e: Cancelled) {
            } catch (e: TimedOut) {
            } catch (e: StepError) {
                log(r, p, "ошибка в фоне ($name): ${e.message}")
            } catch (e: Throwable) {
                log(r, p, "сбой в фоне ($name): ${e.javaClass.simpleName}: ${e.message}")
                FLog.e("task", "фоновое действие $name", e)
            }
        }, "task-bg-$name")
        slot.thread = th
        r.slots[name] = slot
        r.bg.add(th)
        th.start()
    }

    private fun stopSlot(r: Run, name: String): Boolean {
        val slot = r.slots.remove(name) ?: return false
        val th = slot.thread ?: return false
        val alive = th.isAlive
        slot.stop = true
        try { th.join(4000) } catch (e: InterruptedException) {}
        return alive
    }

    /** Останавливает все фоновые действия задачи. Возвращает true, если что-то ещё работало. */
    private fun stopAllSlots(r: Run): Boolean {
        var any = false
        for (n in r.slots.keys.toList()) if (stopSlot(r, n)) any = true
        return any
    }

    /** Вопрос голосом и ответ с микрофона: число или «да/нет» кладётся в переменную assign; не понято после переспросов — переменной нет. */
    private fun ask(r: Run, s: JSONObject, p: String) {
        val text = fill(r, pickText(r, s.opt("ask")))
        val name = s.optString("assign")
        val kind = s.optString("kind", "yesno")
        val listenMs = if (s.has("listen")) Dur.parse(s.opt("listen"), "listen").sample(r.rnd) else 15_000L
        val retries = s.optInt("retries", 1)
        val again: Any? = if (s.has("again")) s.opt("again") else "Повторите, пожалуйста."
        log(r, p, "ask: $text → $name ($kind, ждать ${Dur.human(listenMs)}, переспросов до $retries)")
        if (r.info.dry) {
            val v = r.assume[name]
            val value: Any? = if (kind == "number") (v as? Number)?.toInt() else v as? Boolean
            if (value != null) r.vars[name] = value
            log(r, p, "пробный запуск: ответ ${value ?: "не получен"} (задайте assume=$name:${if (kind == "number") "120" else "true"}, чтобы проверить другую ветку)")
            return
        }
        val vol = if (s.has("volume")) s.optInt("volume") else r.voice?.takeIf { it.has("volume") }?.optInt("volume")
        Stt.check(r.ctx)?.let { throw StepError(it) } // не спрашиваем, если слушать всё равно нечем
        Stt.prepareAsync(kind) // модель и распознаватель готовятся, пока звучит вопрос: слушание начнётся сразу после него
        var attempt = 0
        while (true) {
            val phrase = if (attempt == 0) text else fill(r, pickText(r, again))
            Voice.speak(phrase, vol, r.voice) { r.stopRequested() }?.let { throw StepError(it) }
            check(r)
            sleep(r, 150) // эхо синтезатора не должно попасть в микрофон
            val heard = try {
                Stt.listen(r.ctx, kind, listenMs) { r.stopRequested() }
            } catch (e: IllegalStateException) {
                throw StepError(e.message ?: "распознавание речи недоступно")
            }
            check(r)
            log(r, p, "услышано: «$heard»")
            val value = Speech.parse(kind, heard)
            if (value != null) {
                r.vars[name] = value
                log(r, p, "$name = $value")
                return
            }
            if (attempt++ >= retries) {
                log(r, p, "ответ не получен, переменной $name нет")
                return
            }
        }
    }

    private fun say(r: Run, text: String, vol: Int?) {
        Voice.speak(text, vol, r.voice) { r.stopRequested() }?.let { throw StepError(it) }
        check(r)
    }

    /**
     * Разговор с ИИ на заданную тему: модель говорит (синтезатор), человек отвечает (микрофон, свободная речь), по очереди.
     * Заканчивается по for, turns, прощанию, тревоге или двум молчаниям подряд. Сбой модели или сети шаг ошибкой не считает:
     * разговор просто заканчивается. assign получает краткий пересказ (пусто, если человек молчал), alert — была ли тревога, ok — отвечала ли модель без сбоев.
     */
    private fun chat(r: Run, s: JSONObject, p: String) {
        val topic = fill(r, s.optString("chat")).trim()
        val nameSum = s.optString("assign")
        val nameAlert = s.optString("alert")
        val nameOk = s.optString("ok")
        val fromUser = s.optString("from") == "user"
        val forMs = if (s.has("for")) Dur.parse(s.opt("for"), "for").sample(r.rnd) else 5 * 60_000L
        val turns = s.optInt("turns", 8)
        log(r, p, "chat: " + (if (fromUser) "слова человека" else "тема") + " «${topic.take(120)}», не дольше ${Dur.human(forMs)}, до $turns реплик" + (if (nameSum.isNotEmpty()) ", пересказ → $nameSum" else "") + (if (nameAlert.isNotEmpty()) ", тревога → $nameAlert" else "") + (if (nameOk.isNotEmpty()) ", успех → $nameOk" else ""))
        if (r.info.dry) {
            if (nameSum.isNotEmpty()) r.vars[nameSum] = r.assume[nameSum]?.toString() ?: ""
            if (nameAlert.isNotEmpty()) r.vars[nameAlert] = r.assume[nameAlert] == true
            if (nameOk.isNotEmpty()) r.vars[nameOk] = r.assume[nameOk] != false
            val hint = listOfNotNull(if (nameSum.isNotEmpty()) "$nameSum:текст" else null, if (nameAlert.isNotEmpty()) "$nameAlert:true" else null, if (nameOk.isNotEmpty()) "$nameOk:false" else null).joinToString(",")
            log(r, p, "пробный запуск: разговор не ведётся" + if (hint.isNotEmpty()) " (задайте assume=$hint, чтобы проверить ветки)" else "")
            return
        }
        val cfg = r.root.optJSONObject("chat")
        if (topic.isEmpty()) throw StepError("тема разговора пуста (после подстановок): напишите её в шаге chat")
        val rules = cfg?.optString("rules").orEmpty()
        Llm.problem()?.let {
            if (nameOk.isEmpty()) throw StepError(it)
            log(r, p, "модель не настроена, разговор не начат: $it")
            if (nameSum.isNotEmpty()) r.vars[nameSum] = ""
            if (nameAlert.isNotEmpty()) r.vars[nameAlert] = false
            r.vars[nameOk] = false
            return
        }
        Stt.check(r.ctx)?.let { throw StepError(it) }
        Stt.prepareAsync("free")
        val vol = if (s.has("volume")) s.optInt("volume") else r.voice?.takeIf { it.has("volume") }?.optInt("volume")
        val hist = ArrayList<Chat.Msg>()
        val said = ArrayList<String>()
        if (fromUser) { hist.add(Chat.Msg("user", topic)); said.add(topic) } // текст шага — первая реплика человека: модель отвечает на неё
        val end = System.currentTimeMillis() + forMs
        var alert = false
        var silences = 0
        var failed = false
        var n = 0
        while (true) {
            check(r)
            val last = n + 1 >= turns || end - System.currentTimeMillis() < 45_000 || silences >= 2
            val system = Chat.systemPrompt(rules, if (fromUser) "" else topic, Weather.chatContext(), hist.isEmpty()) + if (last) Chat.LAST else ""
            val res = try {
                Llm.ask(system, hist)
            } catch (e: Llm.LlmError) {
                FLog.w("llm", "разговор оборван: ${e.message}")
                log(r, p, "модель недоступна, разговор закончен: ${e.message}")
                failed = true
                if (n > 0 && nameOk.isEmpty()) say(r, "Извините, пропала связь. Поговорим в другой раз.", vol) // с ok= решает сама задача
                break
            }
            val rep = res.reply
            log(r, p, "модель (${res.ms} мс): «${rep.text}»" + (if (rep.alert) " [тревога]" else "") + (if (rep.end) " [конец]" else ""))
            if (rep.text.isNotEmpty()) say(r, rep.text, vol)
            hist.add(Chat.Msg("assistant", rep.text.ifEmpty { "…" }))
            n++
            if (rep.alert) {
                alert = true
                val lastSaid = said.lastOrNull() ?: ""
                FLog.w("task", "«${r.title()}»: тревога в разговоре: «$lastSaid»")
                Telegram.enqueue("<b>Тревога в разговоре с устройством.</b> Задача «${r.title()}». Человек сказал: «$lastSaid». Ответ помощника: «${rep.text}».")
                break
            }
            if (rep.end || last) break
            sleep(r, 150) // эхо синтезатора не должно попасть в микрофон
            val heard = try {
                Stt.listen(r.ctx, "free", minOf(30_000L, end - System.currentTimeMillis()).coerceAtLeast(3_000L)) { r.stopRequested() }
            } catch (e: IllegalStateException) {
                throw StepError(e.message ?: "распознавание речи недоступно")
            }
            check(r)
            log(r, p, "услышано: «$heard»")
            if (heard.isBlank()) {
                silences++
                hist.add(Chat.Msg("user", "(человек молчит и не ответил)"))
            } else {
                silences = 0
                said.add(heard)
                hist.add(Chat.Msg("user", heard))
            }
        }
        if (nameAlert.isNotEmpty()) r.vars[nameAlert] = alert
        if (nameOk.isNotEmpty()) {
            r.vars[nameOk] = !failed && n > 0
            log(r, p, "$nameOk = ${if (!failed && n > 0) "да" else "нет"}")
        }
        if (nameSum.isNotEmpty()) {
            val sum = if (said.isEmpty()) "" else summary(r, p, hist, said)
            r.vars[nameSum] = sum
            log(r, p, "$nameSum = «$sum»")
        }
    }

    /** Краткий пересказ разговора для родственника; если модель недоступна, то слова самого человека. */
    private fun summary(r: Run, p: String, hist: List<Chat.Msg>, said: List<String>): String {
        val transcript = hist.joinToString("\n") { (if (it.role == "assistant") "Помощник: " else "Человек: ") + it.text }
        return try {
            Llm.ask(
                "Ниже разговор голосового помощника с пожилым человеком. В одном-двух предложениях перескажи для его родственника, о чём человек говорил и как он себя чувствует. " +
                    "Пиши по-русски, от третьего лица. " + Chat.FORMAT,
                listOf(Chat.Msg("user", transcript))
            ).reply.text.ifEmpty { said.joinToString(" / ") }
        } catch (e: Llm.LlmError) {
            log(r, p, "пересказ не получен (${e.message}), беру слова человека")
            said.joinToString(" / ").take(500)
        }
    }

    /** Слайд на экране: slide файл [for= fit= bg= brightness= fade_in= fade_out= tap=] или slide off. Не блокирует: сценарий идёт дальше. */
    private fun slide(r: Run, s: JSONObject, p: String) {
        if (s.opt("slide") == "off") {
            log(r, p, "slide off" + if (r.info.dry) " (в пробном запуске пропущено)" else "")
            if (!r.info.dry) Slide.off()
            return
        }
        val name = pickText(r, s.opt("slide"))
        val forMs = if (s.has("for")) Dur.parse(s.opt("for"), "for").sample(r.rnd) else null
        val spec = Slide.Spec(
            name, s.optString("fit", "full"), pickText(r, s.opt("bg") ?: "auto"),
            if (s.has("brightness")) s.optInt("brightness") else null,
            if (s.has("fade_in")) Dur.parse(s.opt("fade_in"), "fade_in").sample(r.rnd) else 0L,
            if (s.has("fade_out")) Dur.parse(s.opt("fade_out"), "fade_out").sample(r.rnd) else 0L,
            s.optString("tap") == "hide"
        )
        log(r, p, "slide: $name" + (forMs?.let { ", скрыть через ${Dur.human(it)}" } ?: "") + if (r.info.dry) " (в пробном запуске не показывается)" else "")
        if (r.info.dry) return
        val token = Slide.show(spec, forMs)
        if (token == 0) { log(r, p, "слайд не показан: киоск не на переднем плане"); FLog.i("task", "«${r.title()}»: слайд не показан, киоск не на переднем плане"); return }
        slideScope.get()?.add(token)
        r.slideTokens.add(token)
    }

    /** Фото с камеры в PhotoStore; камера включается на время шага. */
    private fun photo(r: Run, s: JSONObject, p: String) {
        val name = s.optString("assign")
        log(r, p, "photo" + (if (s.has("size")) " ${s.optString("size")}" else "") + (if (name.isNotEmpty()) " → $name" else "") + if (r.info.dry) " (в пробном запуске пропущено)" else "")
        if (r.info.dry) {
            if (name.isNotEmpty()) r.vars[name] = "DCIM/fServ/пробный-запуск.jpg"
            return
        }
        try {
            val size = s.optString("size").takeIf { it.isNotEmpty() }
            val path = PhotoStore.shoot(r.ctx, "task", r.info.id, { r.stopRequested() }, size)
            check(r)
            if (name.isNotEmpty()) r.vars[name] = path
            log(r, p, "фото сохранено: $path")
        } catch (e: IllegalStateException) {
            throw StepError(e.message ?: "не удалось сделать фото")
        }
    }

    /** Ждёт, пока сообщение [id] будет доставлено, но не дольше [ms]. false: не успело, отброшено или Telegram не настроен. */
    private fun confirmTelegram(r: Run, id: Long, ms: Long): Boolean {
        val end = System.currentTimeMillis() + ms
        while (true) {
            check(r)
            if (Telegram.isDelivered(id)) return true
            if (!Telegram.isQueued(id) || !Settings.telegramConfigured) return false
            val left = end - System.currentTimeMillis()
            if (left <= 0) return false
            sleep(r, minOf(left, 100L))
        }
    }

    private fun watch(r: Run, w: JSONObject, p: String) {
        val forMs = Dur.parse(w.opt("for"), "for").sample(r.rnd)
        val saveAs = w.optString("assign")
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
                    if (Call.active) throw StepError("камера занята звонком")
                    if (System.currentTimeMillis() > end + 15_000) throw StepError("камера не заработала: ${CameraEngine.lastError.ifEmpty { "нет кадров" }}")
                    sleep(r, 100)
                }
                sleep(r, 1000) // дать автоэкспозиции устояться
                CameraEngine.awaitProcessedFrame(3000)
                CameraEngine.resetBaseline()
                val base = CameraEngine.motionHits
                while (System.currentTimeMillis() < end) {
                    if (Call.active) throw StepError("камера занята звонком")
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
        root.optJSONArray("tasks")?.let { a ->
            for (i in 0 until a.length()) {
                val t = a.optJSONObject(i) ?: continue
                val id = t.optString("id")
                val cronExpr = t.optString("cron")
                val next = if (t.optBoolean("enabled", true) && cronExpr.isNotEmpty()) nextRun(id, cronExpr) else null
                tasks.put(
                    obj(
                        "id" to id, "title" to t.optString("title"), "enabled" to t.optBoolean("enabled", true),
                        "cron" to cronExpr.ifEmpty { null }, "voice" to t.optString("voice").ifEmpty { null }, "next" to next?.let { Util.iso(it) },
                        "running" to (current?.info?.id == id), "queued" to queue.any { it.taskId == id }
                    )
                )
            }
        }
        val runs = JSONArray()
        synchronized(recent) { recent.forEachIndexed { n, r -> runs.put(runJson(r, n == 0)) } }
        return obj(
            "configOk" to true, "tasks" to tasks,
            "running" to current?.let { runJson(it.info, false) },
            "queue" to arr(queue.sortedWith(queueOrder).map { it.taskId + if (it.dry) " (пробный)" else "" }),
            "runs" to runs
        )
    }
}
