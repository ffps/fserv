package com.ffps.fserv

import org.json.JSONArray
import org.json.JSONObject
import java.util.IdentityHashMap

/**
 * Проверка дерева сценария, которое строит [Script]. Возвращает описание первой найденной проблемы в виде
 * «строка N: …» (N совпадает с номером строки в редакторе) или null, если всё верно. Опечатки в именах параметров
 * отвергает уже [Script]; здесь проверяются значения, ссылки на переменные, зоны и файлы. Без зависимостей от Android.
 */
object TaskSchema {
    val BUILTINS = setOf("time", "date", "weekday", "task", "phrase")
    val SYSTEM_SOUNDS = setOf("system:alarm", "system:notification", "system:ringtone")

    private val VAR_RE = Regex("[A-Za-z_][A-Za-z0-9_]{0,30}")
    private val TPL_RE = Regex("""\{([A-Za-z_][A-Za-z0-9_]*)\}""")
    private const val MAX_DEPTH = 8
    private const val MAX_STEPS = 300
    private const val MAX_FILES = 10

    private class Ctx(
        val lines: IdentityHashMap<Any, Int>, val zones: Set<String>, val fileExists: (String) -> Boolean, val slideExists: (String) -> Boolean,
        val taskVars: Set<String>, val photoVars: Set<String>
    ) {
        var steps = 0
        var inBg = false
        fun err(node: Any?, msg: String): String = "строка ${lines[node] ?: 0}: $msg"
    }

    fun validate(root: JSONObject, lines: IdentityHashMap<Any, Int>, slideExists: (String) -> Boolean = { true }, fileExists: (String) -> Boolean = { true }, extraZones: Set<String> = emptySet()): String? {
        fun err(node: Any?, msg: String) = "строка ${lines[node] ?: 0}: $msg"
        val zones = HashSet<String>(extraZones)
        val tasks = root.optJSONArray("tasks") ?: JSONArray()
        for (i in 0 until tasks.length()) {
            val t = tasks.getJSONObject(i)
            val vars = HashSet<String>()
            val photos = HashSet<String>()
            collectVars(t.opt("steps"), vars, photos)
            task(t, Ctx(lines, zones, fileExists, slideExists, vars, photos))?.let { return it }
        }
        return null
    }

    /** Целое число в пределах [min]..[max] (проценты скорости и тона речи). */
    fun validPct(x: Any?, min: Int, max: Int): Boolean = x is Number && x.toDouble() == Math.floor(x.toDouble()) && x.toDouble() in min.toDouble()..max.toDouble()

    /** Проверка настроек голоса (settings.json, вкладка «Звук»); null или текст ошибки. */
    fun checkVoice(o: JSONObject): String? {
        if (o.has("volume") && !(o.opt("volume") is Number && o.optInt("volume", -1) in 0..100)) return "volume — число от 0 до 100"
        if (o.has("rate") && !validPct(o.opt("rate"), 30, 300)) return "rate — целое число процентов от 30 до 300 (100 = обычная скорость)"
        if (o.has("pitch") && !validPct(o.opt("pitch"), 30, 200)) return "pitch — целое число процентов от 30 до 200 (100 = обычный тон)"
        if (o.has("lang") && !Regex("[a-z]{2,3}([-_][A-Za-z]{2,4})?").matches(o.optString("lang"))) return "lang — например ru-RU"
        return null
    }

    /** Проверка одной зоны детектора движения (settings.json, вкладка «Камера»); null или текст ошибки. */
    fun checkZone(o: JSONObject): String? {
        if (!o.has("rect") && !o.has("rects") && !o.has("exclude")) return "нужен rect, rects или exclude"
        if (o.has("rect")) rect(o.opt("rect"))?.let { return "rect: $it" }
        for (key in listOf("rects", "exclude")) if (o.has(key)) {
            val a = o.opt(key) as? JSONArray ?: return "$key: нужен список прямоугольников, например $key=[[0,0,0.5,0.5],[0.5,0,1,0.5]]"
            for (i in 0 until a.length()) rect(a.opt(i))?.let { return "$key[${i + 1}]: $it" }
        }
        return null
    }

    private fun rect(v: Any?): String? {
        val a = v as? JSONArray ?: return "ожидается [x1,y1,x2,y2] (доли кадра от 0 до 1)"
        if (a.length() != 4) return "ожидается 4 числа [x1,y1,x2,y2]"
        val n = DoubleArray(4)
        for (i in 0..3) {
            if (a.opt(i) !is Number) return "ожидаются числа"
            n[i] = a.optDouble(i)
            if (n[i] < 0.0 || n[i] > 1.0) return "числа должны быть от 0 до 1"
        }
        if (n[0] >= n[2] || n[1] >= n[3]) return "должно быть x1 < x2 и y1 < y2"
        return null
    }

    private fun task(t: JSONObject, c: Ctx): String? {
        val id = t.optString("id")
        if (t.has("title") && t.opt("title") !is String) return c.err(t, "task $id: title — текст, например title=\"Подъём\"")
        if (t.has("enabled") && t.opt("enabled") !is Boolean) return c.err(t, "task $id: enabled — true или false")
        if (t.has("preempt") && t.opt("preempt") !is Boolean) return c.err(t, "task $id: preempt — true или false")
        if (t.has("cron")) {
            if (t.opt("cron") !is String) return c.err(t, "task $id: cron пишется в кавычках, например cron=\"0 8 * * *\"")
            try { Cron.parse(t.optString("cron")) } catch (e: IllegalArgumentException) { return c.err(t, "task $id: cron: ${e.message}") }
        }
        if (t.has("voice")) {
            if (t.opt("voice") !is String) return c.err(t, "task $id: voice пишется в кавычках, например voice=\"поболтай со мной|ты здесь\"")
            try { VoiceCmd.expandPhrases(t.optString("voice")) } catch (e: IllegalArgumentException) { return c.err(t, "task $id: voice: ${e.message}") }
        }
        for (k in listOf("late_ok", "timeout")) if (t.has(k)) {
            try { Dur.parse(t.opt(k), k) } catch (e: IllegalArgumentException) { return c.err(t, "task $id: ${e.message}") }
        }
        val steps = t.optJSONArray("steps") ?: JSONArray()
        if (steps.length() == 0) return c.err(t, "task $id: нет шагов (напишите их ниже, с отступом)")
        return steps(steps, c, 1)
    }

    /** Текст say и ask: строка либо непустой список строк (случайный выбор). Возвращает описание ошибки или null. */
    private fun phrases(v: Any?, what: String, c: Ctx): String? {
        val list = when (v) {
            is String -> listOf(v)
            is JSONArray -> (0 until v.length()).map { v.opt(it) }
            else -> return "$what: нужен текст в кавычках или список текстов"
        }
        if (list.isEmpty()) return "$what: список фраз пуст"
        for (t in list) {
            if (t !is String || t.isBlank()) return "$what: нужен непустой текст"
            tpl(t, c)?.let { return "$what: $it" }
            pauses(t)?.let { return "$what: $it" }
        }
        return null
    }

    /** Переменные, которые задача создаёт через assign: [vars] — все, [photos] — только пути фотографий. */
    private fun collectVars(steps: Any?, vars: MutableSet<String>, photos: MutableSet<String>) {
        when (steps) {
            is JSONArray -> for (i in 0 until steps.length()) collectVars(steps.opt(i), vars, photos)
            is JSONObject -> {
                steps.optJSONObject("watch")?.optString("assign")?.takeIf { it.isNotEmpty() }?.let { vars.add(it) }
                if (steps.has("telegram")) steps.optString("assign").takeIf { it.isNotEmpty() }?.let { vars.add(it) }
                if (steps.has("ask")) steps.optString("assign").takeIf { it.isNotEmpty() }?.let { vars.add(it) }
                if (steps.has("chat")) for (k in listOf("assign", "alert", "ok")) steps.optString(k).takeIf { it.isNotEmpty() }?.let { vars.add(it) }
                if (steps.has("photo")) steps.optString("assign").takeIf { it.isNotEmpty() }?.let { vars.add(it); photos.add(it) }
                for (k in listOf("then", "else", "do", "background")) collectVars(steps.opt(k), vars, photos)
            }
        }
    }

    private fun steps(a: JSONArray, c: Ctx, depth: Int): String? {
        if (depth > MAX_DEPTH) return c.err(a.optJSONObject(0), "слишком глубокая вложенность (больше $MAX_DEPTH уровней)")
        for (i in 0 until a.length()) {
            val s = a.getJSONObject(i)
            if (++c.steps > MAX_STEPS) return c.err(s, "слишком много шагов в задаче (больше $MAX_STEPS)")
            step(s, c, depth)?.let { return it }
        }
        return null
    }

    val SLIDE_FITS = listOf("full", "contain", "cover", "width", "height", "none")

    /** Паузы в речи: {pause:5s}, {pause:500ms}, {pause:2s..5s}; каждая не длиннее 2 минут. */
    val PAUSE_RE = Regex("""\{pause:([^}]*)\}""")

    private fun pauses(text: String): String? {
        for (m in PAUSE_RE.findAll(text)) {
            val sp = try { Dur.parse(m.groupValues[1].trim(), "пауза") } catch (ex: IllegalArgumentException) { return "{pause:${m.groupValues[1]}}: ${ex.message}" }
            if (sp.maxMs > 120_000) return "{pause:${m.groupValues[1]}}: пауза не длиннее 2m"
        }
        return null
    }

    private fun tpl(text: String, c: Ctx): String? {
        for (m in TPL_RE.findAll(text)) {
            val n = m.groupValues[1]
            if (n !in BUILTINS && n !in c.taskVars) return "в тексте есть {$n}, но такой переменной нет (есть: ${(BUILTINS + c.taskVars).joinToString(", ")})"
        }
        return null
    }

    private fun cond(text: Any?, c: Ctx, what: String): String? {
        if (text !is String) return "$what: ожидается условие, например not moved"
        val ids = try { Expr.idents(text) } catch (e: IllegalArgumentException) { return "$what «$text»: ${e.message}" }
        for (n in ids) if (n !in BUILTINS && n !in c.taskVars) return "$what: переменной «$n» нет (её задаёт assign у watch, ask, chat, telegram или photo)"
        return null
    }

    private fun step(s: JSONObject, c: Ctx, depth: Int): String? {
        val a = Script.ACTIONS.first { s.has(it) }
        fun e(msg: String) = c.err(s, msg)
        if (c.inBg) {
            if (a in setOf("watch", "background", "stop_background", "photo", "stop", "ask", "chat")) return e("$a: внутри background не допускается (камера, микрофон и остановка задачи принадлежат основному ходу)")
            if ((a == "say" || a == "play") && s.has("wait")) return e("$a: внутри background параметр wait не нужен (блок и так идёт в фоне)")
        }
        val v = s.opt(a)
        when (a) {
            "say" -> {
                phrases(v, "say", c)?.let { return e(it) }
                volume(s)?.let { return e(it) }
                if (s.has("wait") && s.opt("wait") !is Boolean) return e("wait: true или false (или флаг wait)")
            }
            "ask" -> {
                phrases(v, "ask", c)?.let { return e(it) }
                val n = s.opt("assign")
                if (n !is String || !VAR_RE.matches(n)) return e("ask: нужен assign=имя (латинские буквы, цифры и _), куда попадёт ответ, например ask \"Какое давление?\" assign=bp kind=number")
                if (n in BUILTINS) return e("ask: имя «$n» занято встроенной подстановкой")
                if (s.has("kind") && s.opt("kind") !in listOf("yesno", "number")) return e("ask: kind — yesno (да или нет, по умолчанию) или number (число от 0 до 999)")
                if (s.has("listen")) {
                    val ms = try { Dur.parse(s.opt("listen"), "listen") } catch (ex: IllegalArgumentException) { return e(ex.message ?: "listen") }
                    if (ms.minMs < 2_000 || ms.maxMs > 120_000) return e("ask: listen — от 2s до 2m (сколько ждать ответ)")
                }
                if (s.has("retries") && !(s.opt("retries") is Number && s.optInt("retries", -1) in 0..5)) return e("ask: retries — число от 0 до 5 (сколько раз переспросить)")
                if (s.has("again")) phrases(s.opt("again"), "ask: again", c)?.let { return e(it) }
                volume(s)?.let { return e(it) }
            }
            "chat" -> {
                if (v !is String || v.isBlank()) return e("chat: нужен непустой текст темы, например chat \"Поговорить о погоде\"")
                if (v.length > 1000) return e("chat: тема не длиннее 1000 знаков")
                tpl(v, c)?.let { return e("chat: $it") }
                if (PAUSE_RE.containsMatchIn(v)) return e("chat: {pause:…} допускается только в say")
                if (s.has("for")) {
                    val d = try { Dur.parse(s.opt("for"), "for") } catch (ex: IllegalArgumentException) { return e(ex.message ?: "for") }
                    if (d.minMs < 30_000 || d.maxMs > 30 * 60_000) return e("chat: for — от 30s до 30m (сколько длится разговор самое большее)")
                }
                if (s.has("from") && s.opt("from") != "user" && s.opt("from") != "topic") return e("chat: from — user (текст шага — слова человека, модель отвечает на них) или topic (текст — тема, по умолчанию)")
                if (s.has("turns") && !(s.opt("turns") is Number && s.optInt("turns", -1) in 1..30)) return e("chat: turns — число от 1 до 30 (сколько реплик ИИ самое большее)")
                for (k in listOf("assign", "alert", "ok")) if (s.has(k)) {
                    val n = s.opt(k)
                    if (n !is String || !VAR_RE.matches(n)) return e("chat: $k — имя из латинских букв, цифр и _")
                    if (n in BUILTINS) return e("chat: имя «$n» занято встроенной подстановкой")
                }
                val names = listOf("assign", "alert", "ok").filter { s.has(it) }.map { s.opt(it) }
                if (names.size != names.toSet().size) return e("chat: assign, alert и ok должны называться по-разному")
                volume(s)?.let { return e(it) }
            }
            "log", "telegram", "stop" -> {
                if (v !is String || (v.isBlank() && a != "stop")) return e("$a: нужен непустой текст")
                tpl(v, c)?.let { return e("$a: $it") }
                if (PAUSE_RE.containsMatchIn(v)) return e("$a: {pause:…} допускается только в say")
                if (a == "telegram" && s.has("confirm")) {
                    val d = try { Dur.parse(s.opt("confirm"), "confirm") } catch (ex: IllegalArgumentException) { return e(ex.message ?: "confirm") }
                    if (d.minMs < 1_000 || d.maxMs > 120_000) return e("telegram: confirm — от 1s до 2m (сколько ждать подтверждения доставки)")
                }
                if (a == "telegram" && s.has("assign")) {
                    if (!s.has("confirm")) return e("telegram: assign работает только вместе с confirm, например telegram \"Вызов\" confirm=10s assign=sent")
                    val n = s.opt("assign")
                    if (n !is String || !VAR_RE.matches(n)) return e("telegram: assign — имя из латинских букв, цифр и _")
                    if (n in BUILTINS) return e("telegram: имя «$n» занято встроенной подстановкой")
                }
                if (a == "telegram" && s.has("files")) {
                    val names = when (val f = s.opt("files")) {
                        is String -> listOf(f)
                        is JSONArray -> (0 until f.length()).map { f.opt(it) as? String ?: return e("files: в списке должны быть имена переменных, например files=[photo1]") }
                        else -> return e("files: список переменных с фото, например files=[photo1]")
                    }
                    if (names.isEmpty()) return e("files: список пуст")
                    if (names.size > MAX_FILES) return e("files: не больше $MAX_FILES файлов в одном сообщении")
                    for (n in names) if (n !in c.photoVars) {
                        return e("files: «$n» не переменная с фото (её задаёт photo assign=$n; есть: ${c.photoVars.joinToString(", ").ifEmpty { "нет" }})")
                    }
                }
            }
            "stop_background", "photo" -> {
                if (a == "photo" && s.has("assign")) {
                    val n = s.opt("assign")
                    if (n !is String || !VAR_RE.matches(n)) return e("photo: assign — имя из латинских букв, цифр и _")
                    if (n in BUILTINS) return e("photo: имя «$n» занято встроенной подстановкой")
                }
                if (a == "photo" && s.has("size")) {
                    val z = s.opt("size")
                    val m = z as? String
                    if (m == null || !Regex("""\d{3,4}x\d{3,4}""").matches(m)) return e("photo: size — размер кадра вида 1280x720")
                }
            }
            "background" -> {
                val body = v as JSONArray
                val was = c.inBg
                c.inBg = true
                try { steps(body, c, depth + 1)?.let { return it } } finally { c.inBg = was }
            }
            "delay" -> try { Dur.parse(v, "delay") } catch (ex: IllegalArgumentException) { return e(ex.message ?: "delay") }
            "play" -> {
                val names = when (v) {
                    is String -> listOf(v)
                    is JSONArray -> (0 until v.length()).map { v.opt(it) as? String ?: return e("play: в списке должны быть имена файлов") }
                    else -> return e("play: имя файла или список имён в скобках, например play [a.mp3,b.mp3]")
                }
                if (names.isEmpty()) return e("play: список пуст")
                for (n in names) {
                    if (n !in SYSTEM_SOUNDS && !c.fileExists(n)) return e("play: файла «$n» нет (загрузите его на вкладке «Звук»; системные звуки: ${SYSTEM_SOUNDS.joinToString(", ")})")
                }
                volume(s)?.let { return e(it) }
                for (k in listOf("fade_in", "fade_out", "for")) if (s.has(k)) {
                    try { Dur.parse(s.opt(k), k) } catch (ex: IllegalArgumentException) { return e(ex.message ?: k) }
                }
                if (s.has("loop") && s.opt("loop") !is Boolean) return e("loop: флаг loop или loop=false")
                if (s.has("pause")) {
                    if (s.opt("loop") != true) return e("pause: работает только вместе с loop (пауза между повторами звука); для паузы после звука используйте delay 3s")
                    try { Dur.parseList(s.opt("pause"), "pause") } catch (ex: IllegalArgumentException) { return e(ex.message ?: "pause") }
                }
                if (s.has("wait") && s.opt("wait") !is Boolean) return e("wait: wait=true или wait=false")
                if (s.opt("loop") == true && !s.has("for") && s.optBoolean("wait", true) && !c.inBg) return e("loop требует for=… либо wait=false (иначе звук не остановится; в фоне его гасит stop_background или конец задачи)")
            }
            "slide" -> {
                if (v == "off") {
                    if (s.keys().asSequence().any { it != "slide" }) return e("slide off: параметров у него нет")
                } else {
                    val names = when (v) {
                        is String -> listOf(v)
                        is JSONArray -> (0 until v.length()).map { v.opt(it) as? String ?: return e("slide: в списке должны быть имена файлов") }
                        else -> return e("slide: имя файла, список имён в скобках или off, например slide [a.svg,b.gif]")
                    }
                    if (names.isEmpty()) return e("slide: список пуст")
                    for (n in names) if (!c.slideExists(n)) return e("slide: файла «$n» нет (загрузите его на вкладке «Слайды»)")
                    if (s.has("for")) {
                        val d = try { Dur.parse(s.opt("for"), "for") } catch (ex: IllegalArgumentException) { return e(ex.message ?: "for") }
                        if (d.minMs <= 0) return e("slide: for — длительность больше нуля, например for=5s")
                    }
                    if (s.has("fit") && s.opt("fit") !in SLIDE_FITS) return e("slide: fit — ${SLIDE_FITS.joinToString(", ")}")
                    if (s.has("bg")) {
                        val b = s.opt("bg")
                        if (!(b == "auto" || (b is String && Regex("#([0-9a-fA-F]{3}|[0-9a-fA-F]{6})").matches(b)))) return e("slide: bg — auto или цвет в кавычках, например bg=\"#d81b60\"")
                    }
                    if (s.has("brightness") && !(s.opt("brightness") is Number && s.optInt("brightness", -1) in 1..100)) return e("slide: brightness — число от 1 до 100 (яркость экрана, %)")
                    for (k in listOf("fade_in", "fade_out")) if (s.has(k)) {
                        val d = try { Dur.parse(s.opt(k), k) } catch (ex: IllegalArgumentException) { return e(ex.message ?: k) }
                        if (d.maxMs > 10_000) return e("slide: $k — не больше 10s")
                    }
                    if (s.has("tap") && s.opt("tap") != "hide") return e("slide: tap — hide (скрыть слайд касанием)")
                }
            }
            "watch" -> return watch(s, v as JSONObject, c)
            "if" -> {
                cond(v, c, "if")?.let { return e(it) }
                steps(s.getJSONArray("then"), c, depth + 1)?.let { return it }
                if (s.has("else")) steps(s.getJSONArray("else"), c, depth + 1)?.let { return it }
            }
            "repeat" -> {
                val r = v as JSONObject
                try { Dur.parseCount(r.opt("times"), "times") } catch (ex: IllegalArgumentException) { return e("repeat: ${ex.message}") }
                if (r.has("every")) try { Dur.parseList(r.opt("every"), "every") } catch (ex: IllegalArgumentException) { return e("repeat: ${ex.message}") }
                if (r.has("until")) cond(r.opt("until"), c, "until")?.let { return e("repeat: $it") }
                steps(s.getJSONArray("do"), c, depth + 1)?.let { return it }
            }
        }
        return null
    }

    private fun volume(s: JSONObject): String? {
        if (!s.has("volume")) return null
        val v = s.opt("volume")
        if (v !is Number || v.toInt() !in 0..100) return "volume: число от 0 до 100"
        return null
    }

    private fun watch(s: JSONObject, w: JSONObject, c: Ctx): String? {
        fun e(msg: String) = c.err(s, "watch: $msg")
        try { Dur.parse(w.opt("for"), "длительность") } catch (ex: IllegalArgumentException) { return e(ex.message ?: "for") }
        if (w.has("poll")) try { Dur.parse(w.opt("poll"), "poll") } catch (ex: IllegalArgumentException) { return e(ex.message ?: "poll") }
        if (w.has("assign")) {
            val n = w.opt("assign")
            if (n !is String || !VAR_RE.matches(n)) return e("assign — имя из латинских букв, цифр и _")
            if (n in BUILTINS) return e("имя «$n» занято встроенной подстановкой")
        }
        if (w.has("threshold") && !(w.opt("threshold") is Number && w.optInt("threshold", -1) in 1..100)) return e("threshold — число от 1 до 100")
        if (w.has("area") && !(w.opt("area") is Number && w.optDouble("area") in 0.05..100.0)) return e("area — число процентов от 0.05 до 100")
        if (w.has("zone")) {
            val z = w.opt("zone")
            if (z !is String || z !in c.zones) return e("зона «$z» не описана (зоны создаются на вкладке «Камера»; есть: ${c.zones.joinToString(", ").ifEmpty { "нет" }})")
        }
        return null
    }
}
