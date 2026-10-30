package com.ffps.fserv

import org.json.JSONArray
import org.json.JSONObject

/**
 * Проверка схемы tasks.json (версия 1). Возвращает понятное описание первой найденной проблемы
 * или null, если конфиг верный. Опечатки в именах полей считаются ошибками, чтобы задача не работала
 * молча не так, как задумано. Без зависимостей от Android.
 */
object TaskSchema {
    val BUILTINS = setOf("time", "date", "weekday", "task")
    val SYSTEM_SOUNDS = setOf("system:alarm", "system:notification", "system:ringtone")

    private val ROOT_KEYS = setOf("version", "voice", "zones", "tasks", "comment")
    private val TASK_KEYS = setOf("id", "title", "enabled", "cron", "late_ok", "timeout", "steps", "comment")
    private val VOICE_KEYS = setOf("volume", "rate", "pitch", "lang", "voice", "comment")
    private val ACTIONS = listOf("say", "play", "telegram", "wait", "watch", "if", "repeat", "stop", "stop_sound", "log", "ask", "chat")
    private val MODIFIERS = mapOf(
        "say" to setOf("volume"),
        "play" to setOf("volume", "fade_in", "fade_out", "for", "loop", "wait"),
        "telegram" to emptySet(),
        "wait" to emptySet(),
        "watch" to emptySet(),
        "if" to setOf("then", "else"),
        "repeat" to setOf("do"),
        "stop" to emptySet(),
        "stop_sound" to emptySet(),
        "log" to emptySet()
    )
    private val WATCH_KEYS = setOf("motion", "for", "poll", "save_as", "threshold", "area", "zone", "comment")
    private val REPEAT_KEYS = setOf("times", "every", "until", "comment")
    private val ID_RE = Regex("[A-Za-z0-9_-]{1,40}")
    private val VAR_RE = Regex("[A-Za-z_][A-Za-z0-9_]{0,30}")
    private val TPL_RE = Regex("""\{([A-Za-z_][A-Za-z0-9_]*)\}""")
    private const val MAX_DEPTH = 8
    private const val MAX_STEPS = 300

    private class Ctx(val zones: Set<String>, val fileExists: (String) -> Boolean, val taskVars: Set<String>) {
        var steps = 0
    }

    fun validate(root: JSONObject, fileExists: (String) -> Boolean = { true }): String? {
        for (k in root.keys()) if (k !in ROOT_KEYS) return "неизвестное поле «$k» (допустимы: ${ROOT_KEYS.joinToString(", ")})"
        if (root.has("version") && root.opt("version") != 1) return "version: поддерживается только 1"
        if (root.has("voice")) voice(root.opt("voice"))?.let { return "voice: $it" }
        val zones = HashSet<String>()
        if (root.has("zones")) {
            val z = root.opt("zones") as? JSONObject ?: return "zones: должен быть объектом {имя: {...}}"
            for (name in z.keys()) {
                if (!VAR_RE.matches(name)) return "zones: имя «$name» должно состоять из латинских букв, цифр и _"
                zone(z.opt(name))?.let { return "zones.$name: $it" }
                zones.add(name)
            }
        }
        val tasks = root.opt("tasks")
        if (root.has("tasks") && tasks !is JSONArray) return "поле «tasks» должно быть массивом [...]"
        if (tasks is JSONArray) {
            val ids = HashSet<String>()
            for (i in 0 until tasks.length()) {
                val t = tasks.opt(i) as? JSONObject ?: return "задача №${i + 1}: должна быть объектом {...}"
                val id = t.optString("id")
                if (id.isBlank()) return "задача №${i + 1}: нужно поле id"
                if (!ID_RE.matches(id)) return "задача №${i + 1}: id «$id» может содержать латинские буквы, цифры, _ и -, до 40 знаков"
                if (!ids.add(id)) return "id «$id» повторяется"
                task(id, t, Ctx(zones, fileExists, collectVars(t.opt("steps"))))?.let { return "задача «$id»: $it" }
            }
        }
        return null
    }

    private fun voice(v: Any?): String? {
        val o = v as? JSONObject ?: return "должно быть объектом"
        for (k in o.keys()) if (k !in VOICE_KEYS) return "неизвестное поле «$k» (допустимы: ${VOICE_KEYS.joinToString(", ")})"
        if (o.has("volume") && !(o.opt("volume") is Number && o.optInt("volume", -1) in 0..100)) return "volume: число от 0 до 100"
        if (o.has("rate") && !(o.opt("rate") is Number && o.optDouble("rate") in 0.3..3.0)) return "rate: число от 0.3 до 3 (1 = обычная скорость)"
        if (o.has("pitch") && !(o.opt("pitch") is Number && o.optDouble("pitch") in 0.3..2.0)) return "pitch: число от 0.3 до 2 (1 = обычный тон)"
        if (o.has("lang") && !Regex("[a-z]{2,3}([-_][A-Za-z]{2,4})?").matches(o.optString("lang"))) return "lang: например ru-RU"
        return null
    }

    private fun zone(v: Any?): String? {
        val o = v as? JSONObject ?: return "должно быть объектом {\"rect\": [x1, y1, x2, y2]}"
        for (k in o.keys()) if (k !in setOf("rect", "rects", "exclude", "comment")) return "неизвестное поле «$k» (допустимы: rect, rects, exclude)"
        if (!o.has("rect") && !o.has("rects") && !o.has("exclude")) return "нужен rect, rects или exclude"
        if (o.has("rect")) rect(o.opt("rect"))?.let { return "rect: $it" }
        for (key in listOf("rects", "exclude")) if (o.has(key)) {
            val a = o.opt(key) as? JSONArray ?: return "$key: должен быть массивом прямоугольников"
            for (i in 0 until a.length()) rect(a.opt(i))?.let { return "$key[${i + 1}]: $it" }
        }
        return null
    }

    private fun rect(v: Any?): String? {
        val a = v as? JSONArray ?: return "ожидается [x1, y1, x2, y2] (доли кадра от 0 до 1)"
        if (a.length() != 4) return "ожидается 4 числа [x1, y1, x2, y2]"
        val n = DoubleArray(4)
        for (i in 0..3) {
            if (a.opt(i) !is Number) return "ожидаются числа"
            n[i] = a.optDouble(i)
            if (n[i] < 0.0 || n[i] > 1.0) return "числа должны быть от 0 до 1"
        }
        if (n[0] >= n[2] || n[1] >= n[3]) return "должно быть x1 < x2 и y1 < y2"
        return null
    }

    private fun task(id: String, t: JSONObject, c: Ctx): String? {
        for (k in t.keys()) if (k !in TASK_KEYS) return "неизвестное поле «$k» (допустимы: ${TASK_KEYS.joinToString(", ")})"
        if (t.has("enabled") && t.opt("enabled") !is Boolean) return "enabled должно быть true или false"
        if (t.has("title") && t.opt("title") !is String) return "title должно быть строкой"
        if (t.has("cron")) {
            if (t.opt("cron") !is String) return "cron должно быть строкой"
            try { Cron.parse(t.optString("cron")) } catch (e: IllegalArgumentException) { return "cron: ${e.message}" }
        }
        for (k in listOf("late_ok", "timeout")) if (t.has(k)) {
            try { Dur.parse(t.opt(k), k) } catch (e: IllegalArgumentException) { return e.message }
        }
        if (!t.has("steps")) return "нужно поле steps (список шагов)"
        val steps = t.opt("steps") as? JSONArray ?: return "steps должно быть массивом [...]"
        return steps(steps, c, "", 1)
    }

    /** Имена всех переменных, которые задача создаёт через save_as. */
    private fun collectVars(steps: Any?): Set<String> {
        val out = HashSet<String>()
        fun walk(a: Any?) {
            when (a) {
                is JSONArray -> for (i in 0 until a.length()) walk(a.opt(i))
                is JSONObject -> {
                    (a.opt("watch") as? JSONObject)?.optString("save_as")?.takeIf { it.isNotEmpty() }?.let { out.add(it) }
                    for (k in listOf("then", "else", "do")) walk(a.opt(k))
                }
            }
        }
        walk(steps)
        return out
    }

    private fun steps(a: JSONArray, c: Ctx, path: String, depth: Int): String? {
        if (depth > MAX_DEPTH) return "слишком глубокая вложенность (больше $MAX_DEPTH)"
        for (i in 0 until a.length()) {
            val p = if (path.isEmpty()) "${i + 1}" else "$path.${i + 1}"
            val s = a.opt(i) as? JSONObject ?: return "шаг $p: должен быть объектом {...}"
            if (++c.steps > MAX_STEPS) return "слишком много шагов (больше $MAX_STEPS)"
            step(s, c, p, depth)?.let { return "шаг $p: $it" }
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
        if (text !is String) return "$what: ожидается строка-условие, например \"moved\""
        val ids = try { Expr.idents(text) } catch (e: IllegalArgumentException) { return "$what «$text»: ${e.message}" }
        for (n in ids) if (n !in BUILTINS && n !in c.taskVars) return "$what: переменной «$n» нет (задаётся в watch через save_as)"
        return null
    }

    private fun step(s: JSONObject, c: Ctx, p: String, depth: Int): String? {
        val present = ACTIONS.filter { s.has(it) }
        if (present.isEmpty()) {
            val unknown = s.keys().asSequence().filter { it != "comment" }.toList()
            return "не найдено действие (нужно одно из: ${ACTIONS.joinToString(", ")})" + if (unknown.isNotEmpty()) "; встретилось: ${unknown.joinToString(", ")}" else ""
        }
        if (present.size > 1) return "в одном шаге два действия (${present.joinToString(", ")}): разнесите по разным шагам"
        val a = present[0]
        if (a == "ask") return "ask (вопрос голосом) появится на этапе 5, вместе с распознаванием речи"
        if (a == "chat") return "chat (разговор с ИИ) появится на этапе 6"
        val allowed = (MODIFIERS[a] ?: emptySet()) + a + "comment"
        for (k in s.keys()) if (k !in allowed) return "лишнее поле «$k» у действия $a" + if ((MODIFIERS[a] ?: emptySet()).isNotEmpty()) " (допустимы: ${(MODIFIERS[a] ?: emptySet()).joinToString(", ")})" else ""
        val v = s.opt(a)
        when (a) {
            "say" -> {
                if (v !is String || v.isBlank()) return "say: нужен непустой текст"
                tpl(v, c)?.let { return "say: $it" }
                volume(s)?.let { return it }
            }
            "log", "telegram", "stop" -> {
                if (v !is String || (v.isBlank() && a != "stop")) return "$a: нужен непустой текст"
                tpl(v, c)?.let { return "$a: $it" }
            }
            "stop_sound" -> if (v != true) return "stop_sound: пишите true (останавливает звук, запущенный с \"wait\": false)"
            "wait" -> try { Dur.parse(v, "wait") } catch (e: IllegalArgumentException) { return e.message }
            "play" -> {
                val names = when (v) {
                    is String -> listOf(v)
                    is JSONArray -> (0 until v.length()).map { v.opt(it) as? String ?: return "play: в списке должны быть имена файлов" }
                    else -> return "play: имя файла или список имён"
                }
                if (names.isEmpty()) return "play: список пуст"
                for (n in names) {
                    if (n !in SYSTEM_SOUNDS && !c.fileExists(n)) return "play: файла «$n» нет (загрузите его на вкладке «Звук»; системные звуки: ${SYSTEM_SOUNDS.joinToString(", ")})"
                }
                volume(s)?.let { return it }
                for (k in listOf("fade_in", "fade_out", "for")) if (s.has(k)) {
                    try { Dur.parse(s.opt(k), k) } catch (e: IllegalArgumentException) { return e.message }
                }
                if (s.has("loop") && s.opt("loop") !is Boolean) return "loop: true или false"
                if (s.has("wait") && s.opt("wait") !is Boolean) return "wait: true или false"
                if (s.optBoolean("loop") && !s.has("for") && s.optBoolean("wait", true)) return "loop: true требует поля for либо \"wait\": false (иначе звук не остановится; в фоновом режиме его гасит шаг stop_sound или конец задачи)"
            }
            "watch" -> return watch(v, c)
            "if" -> {
                cond(v, c, "if")?.let { return it }
                val th = s.opt("then") as? JSONArray ?: return "if: нужно поле then (список шагов)"
                steps(th, c, "$p.then", depth + 1)?.let { return it }
                if (s.has("else")) {
                    val el = s.opt("else") as? JSONArray ?: return "else: должен быть массивом шагов"
                    steps(el, c, "$p.else", depth + 1)?.let { return it }
                }
            }
            "repeat" -> {
                val r = v as? JSONObject ?: return "repeat: ожидается объект {\"times\": 6, \"every\": \"5m\", \"until\": \"moved\"}"
                for (k in r.keys()) if (k !in REPEAT_KEYS) return "repeat: неизвестное поле «$k» (допустимы: ${REPEAT_KEYS.joinToString(", ")})"
                if (!r.has("times")) return "repeat: нужно поле times"
                try { Dur.parseCount(r.opt("times"), "times") } catch (e: IllegalArgumentException) { return "repeat: ${e.message}" }
                if (r.has("every")) try { Dur.parse(r.opt("every"), "every") } catch (e: IllegalArgumentException) { return "repeat: ${e.message}" }
                if (r.has("until")) cond(r.opt("until"), c, "until")?.let { return "repeat: $it" }
                val body = s.opt("do") as? JSONArray ?: return "repeat: нужно поле do (список шагов, снаружи объекта repeat)"
                steps(body, c, "$p.do", depth + 1)?.let { return it }
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

    private fun watch(v: Any?, c: Ctx): String? {
        val w = v as? JSONObject ?: return "watch: ожидается объект {\"motion\": true, \"for\": \"2m\", \"save_as\": \"moved\"}"
        for (k in w.keys()) if (k !in WATCH_KEYS) return "watch: неизвестное поле «$k» (допустимы: ${WATCH_KEYS.joinToString(", ")})"
        if (w.opt("motion") != true) return "watch: нужно \"motion\": true (другие виды наблюдения появятся позже)"
        if (!w.has("for")) return "watch: нужно поле for (сколько наблюдать)"
        try { Dur.parse(w.opt("for"), "for") } catch (e: IllegalArgumentException) { return "watch: ${e.message}" }
        if (w.has("poll")) try { Dur.parse(w.opt("poll"), "poll") } catch (e: IllegalArgumentException) { return "watch: ${e.message}" }
        if (w.has("save_as") && !(w.opt("save_as") is String && VAR_RE.matches(w.optString("save_as")))) return "watch: save_as: имя из латинских букв, цифр и _"
        if (w.has("threshold") && !(w.opt("threshold") is Number && w.optInt("threshold", -1) in 1..100)) return "watch: threshold: число от 1 до 100"
        if (w.has("area") && !(w.opt("area") is Number && w.optDouble("area") in 0.05..100.0)) return "watch: area: число процентов от 0.05 до 100"
        if (w.has("zone")) {
            val z = w.opt("zone")
            if (z !is String || z !in c.zones) return "watch: zone «$z» не описана в разделе zones (описаны: ${c.zones.joinToString(", ").ifEmpty { "нет" }})"
        }
        return null
    }
}
