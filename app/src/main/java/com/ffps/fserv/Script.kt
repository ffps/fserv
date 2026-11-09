package com.ffps.fserv

import org.json.JSONArray
import org.json.JSONObject
import java.util.IdentityHashMap

/**
 * Текстовый формат сценариев: один файл на задачу (tasks/<id>.txt, id — имя файла, в тексте его нет).
 * Голос и зоны детектора движения в сценарии не пишутся: это настройки приложения (settings.json, вкладки «Звук» и «Камера»).
 * Разбирается в то же дерево JSON-объектов, которое проверяет
 * [TaskSchema] и выполняет [TaskEngine]. Номера строк запоминаются для каждого узла, поэтому ошибки проверки
 * называют строку так же, как её видно в редакторе.
 *
 * Формат (отступ 2 пробела, комментарии с #):
 *
 * Файл задачи:
 *   task "Подъём" cron="0 8 * * *"     (название можно и как title=; настройки можно и на строках ниже)
 *     timeout=60m
 *     repeat 10 until=moved
 *       watch 30s assign=moved
 *       if not moved
 *         background
 *           play system:alarm volume=80
 *     if moved
 *       say "Доброе утро!"
 *     else
 *       telegram "Не встал"
 *
 * Действие: слово, затем (если нужно) первый аргумент без имени, затем параметры ключ=значение и флаги (loop = loop=true).
 * Значения: слово без пробелов, "текст в кавычках" (экраны \" \\ \n \t), список [a,b,c]. Здесь нет зависимостей от Android.
 */
object Script {
    class ScriptError(val line: Int, message: String) : Exception(message)

    class TaskSpan(val id: String, val startLine: Int, val endLine: Int)

    /** Результат разбора: дерево, номера строк узлов и расположение задач в тексте. */
    class Parsed(val root: JSONObject, val lines: IdentityHashMap<Any, Int>, val tasks: List<TaskSpan>)

    val ACTIONS = listOf("say", "ask", "chat", "play", "slide", "photo", "telegram", "request", "kiosk", "delay", "watch", "background", "stop_background", "if", "repeat", "stop", "log")

    /** Параметры (после первого аргумента), допустимые у каждого действия. */
    val PARAMS = mapOf(
        "say" to listOf("volume", "wait"),
        "ask" to listOf("assign", "kind", "listen", "retries", "again", "volume"),
        "chat" to listOf("for", "turns", "assign", "alert", "ok", "from", "volume"),
        "play" to listOf("volume", "fade_in", "fade_out", "for", "loop", "wait", "pause"),
        "slide" to listOf("for", "fit", "bg", "brightness", "fade_in", "fade_out", "tap"),
        "telegram" to listOf("files", "confirm", "assign"),
        "photo" to listOf("assign", "size"),
        "request" to listOf("post", "method", "type", "header", "proxy", "timeout", "assign", "body", "code"),
        "kiosk" to emptyList(),
        "delay" to emptyList(),
        "watch" to listOf("poll", "assign", "threshold", "area", "zone"),
        "background" to emptyList(),
        "stop_background" to emptyList(),
        "if" to emptyList(),
        "repeat" to listOf("every", "until"),
        "stop" to emptyList(),
        "log" to emptyList()
    )
    val TASK_SETTINGS = listOf("title", "cron", "voice", "enabled", "late_ok", "timeout", "preempt")

    private val KEY_RE = Regex("""[A-Za-z_][A-Za-z0-9_]*=""")
    private val WORD_RE = Regex("""[A-Za-z_][A-Za-z0-9_]*""")
    private val INT_RE = Regex("""-?\d{1,9}""")
    private val DEC_RE = Regex("""-?\d+\.\d+""")

    /** Строка в кавычках: отличает «"10"» от 10, пока значение разбирается. */
    private class Q(val s: String)

    private class Line(val no: Int, val level: Int, val text: String)

    private class Args(val positional: Any?, val named: LinkedHashMap<String, Any?>, val extra: Any? = null)

    // ---------------- публичный вход ----------------

    /** Разбирает текст в дерево. Бросает [ScriptError] с номером строки. */
    fun parse(text: String, id: String = "task", lists: Map<String, String> = emptyMap()): Parsed = Parser(text, id, lists).run()

    /** Проверка списка из lists.txt, записанного одной строкой (`["a", b]`): бросает [ScriptError] с номером строки [no]; возвращает число элементов. */
    fun listLiteral(lit: String, no: Int): Int {
        val p = Parser("", "lists", emptyMap())
        val c = Cursor(lit, no, p)
        if (lit.isEmpty() || lit[0] != '[') p.fail(no, "значение должно быть списком в квадратных скобках")
        val v = c.list()
        c.skip()
        if (c.i < lit.length) p.fail(no, "после закрывающей скобки ] лишний текст")
        if (v.any { it is List<*> }) p.fail(no, "вложенные списки не допускаются")
        return v.size
    }

    /** Полная проверка файла задачи [id]: разбор и схема. [zones] — имена зон из настроек (вкладка «Камера»). Текст ошибки начинается с «строка N: » либо null. */
    fun validate(text: String, id: String = "task", zones: Set<String> = emptySet(), lists: Map<String, String> = emptyMap(), slideExists: (String) -> Boolean = { true }, fileExists: (String) -> Boolean = { true }): String? =
        check(text, id, zones, lists, slideExists, fileExists).first

    /** Как [validate], но возвращает ещё и номер строки ошибки (0, если ошибки нет или у неё нет строки). */
    fun check(text: String, id: String = "task", zones: Set<String> = emptySet(), lists: Map<String, String> = emptyMap(), slideExists: (String) -> Boolean = { true }, fileExists: (String) -> Boolean = { true }): Pair<String?, Int> {
        val p = try { parse(text, id, lists) } catch (e: ScriptError) { return "строка ${e.line}: ${e.message}" to e.line }
        val err = TaskSchema.validate(p.root, p.lines, slideExists, fileExists, zones) ?: return null to 0
        val n = Regex("""^строка (\d+): """).find(err)?.groupValues?.get(1)?.toIntOrNull() ?: 0
        return err to n
    }

    // ---------------- разбор ----------------

    /** Файл одной задачи с этим id. */
    private class Parser(val text: String, val taskId: String, val lists: Map<String, String> = emptyMap()) {
        val raw = text.replace("\r\n", "\n").replace('\r', '\n').split("\n")
        val lines = ArrayList<Line>()
        var pos = 0
        val map = IdentityHashMap<Any, Int>()

        fun fail(no: Int, msg: String): Nothing = throw ScriptError(no, msg)

        fun run(): Parsed {
            for ((i, r) in raw.withIndex()) {
                val no = i + 1
                var sp = 0
                while (sp < r.length && r[sp] == ' ') sp++
                if (sp < r.length && r[sp] == '\t') fail(no, "в отступе табуляция: используйте пробелы (2 на уровень)")
                val body = stripComment(r.substring(sp), no).trimEnd()
                if (body.isEmpty()) continue
                if (sp % 2 != 0) fail(no, "отступ должен быть кратен 2 пробелам (сейчас $sp)")
                lines.add(Line(no, sp / 2, body))
            }
            val root = JSONObject()
            map[root] = 1
            root.put("version", 1)
            val tasks = JSONArray()
            val spans = ArrayList<TaskSpan>()
            while (pos < lines.size) {
                val l = lines[pos]
                if (l.level > 0) fail(l.no, "лишний отступ: на верхнем уровне пишется task")
                val word = l.text.substringBefore(' ')
                when (word) {
                    "task" -> {
                        if (tasks.length() > 0) fail(l.no, "в файле одна задача: вторая строка task не нужна")
                        val t = parseTask(l)
                        val id = t.optString("id")
                        tasks.put(t)
                        spans.add(TaskSpan(id, l.no, taskEnd(l.no, pos)))
                    }
                    else -> fail(l.no, "на верхнем уровне допустима только task; встретилось «$word»" + if (word.contains('=')) " (настройки задачи пишутся в строке task или под ней, до шагов)" else "")
                }
            }
            if (tasks.length() == 0) fail(1, "в файле нет строки task (она должна быть первой, например task \"Название\" cron=\"0 8 * * *\")")
            root.put("tasks", tasks)
            return Parsed(root, map, spans)
        }

        /** Последняя строка задачи в исходном тексте: до следующей строки без отступа, без хвостовых пустых. */
        fun taskEnd(start: Int, idx: Int): Int {
            val nextNo = if (idx < lines.size) lines[idx].no else raw.size + 1
            var end = nextNo - 1
            // строка с отступом 0 (комментарий или пустая) принадлежит следующему блоку, а не задаче
            var i = start + 1
            var last = start
            while (i <= end) {
                val r = raw[i - 1]
                if (r.isNotBlank()) {
                    if (r[0] != ' ') break
                    last = i
                }
                i++
            }
            return last
        }

        fun parseTask(h: Line): JSONObject {
            val a = args(h, 4, false, true)
            val id = taskId
            val t = JSONObject()
            map[t] = h.no
            t.put("id", id)
            if (a.extra != null) t.put("title", (a.extra as Q).s)
            settings(a, h.no, t)
            pos++
            // настройки задачи на строках ниже (ключ=значение), до первого шага
            while (pos < lines.size && lines[pos].level >= 1) {
                val l = lines[pos]
                if (l.level > 1) fail(l.no, "лишний отступ")
                if (KEY_RE.matchAt(l.text, 0) == null) break
                settings(args(l, 0, false), l.no, t)
                pos++
            }
            t.put("steps", body(1))
            return t
        }

        /** Переносит настройки задачи; повтор уже заданной (в заголовке и ниже, или title дважды) — ошибка. */
        fun settings(a: Args, no: Int, t: JSONObject) {
            for (k in a.named.keys) if (k in TASK_SETTINGS && t.has(k)) fail(no, "настройка «$k» уже задана выше")
            named(a, TASK_SETTINGS, "task", no, t, "настройки")
        }

        /** Шаги на уровне [level]; на вложенные блоки уходят владельцы-заголовки. */
        fun body(level: Int): JSONArray {
            val out = JSONArray()
            while (pos < lines.size) {
                val l = lines[pos]
                if (l.level < level) break
                if (l.level > level) fail(l.no, "лишний отступ: здесь ожидается ${level * 2} пробелов")
                out.put(step(l, level))
            }
            return out
        }

        fun block(owner: Line, level: Int, what: String): JSONArray {
            val nxt = lines.getOrNull(pos)
            if (nxt == null || nxt.level <= level) fail(owner.no, "у $what нужны вложенные шаги (на следующих строках, с отступом на 2 пробела больше)")
            if (nxt.level > level + 1) fail(nxt.no, "лишний отступ: здесь ожидается ${(level + 1) * 2} пробелов")
            return body(level + 1)
        }

        fun noChildren(owner: Line, level: Int, action: String) {
            val nxt = lines.getOrNull(pos) ?: return
            if (nxt.level > level) fail(nxt.no, "у $action нет вложенных шагов, лишний отступ")
        }

        fun step(l: Line, level: Int): JSONObject {
            val word = l.text.substringBefore(' ')
            if (word.contains('=')) fail(l.no, "настройка «$word» пишется в строке task или сразу под ней, до шагов")
            if (word == "else") fail(l.no, "else без if: он пишется на том же уровне, что и if, сразу после его шагов")
            if (word !in ACTIONS) fail(l.no, "неизвестное действие «$word»; допустимы: ${ACTIONS.joinToString(", ")}")
            val s = JSONObject()
            map[s] = l.no
            val rest = word.length
            if (word == "if") {
                val cond = l.text.substring(rest).trim()
                if (cond.isEmpty()) fail(l.no, "if: нужно условие, например if not moved")
                pos++
                s.put("if", cond)
                s.put("then", block(l, level, "if"))
                val e = lines.getOrNull(pos)
                if (e != null && e.level == level && e.text == "else") {
                    pos++
                    s.put("else", block(e, level, "else"))
                }
                return s
            }
            val a = args(l, rest, word !in setOf("photo", "background", "stop_background"))
            if (a.extra != null) fail(l.no, "лишнее значение у $word: параметры пишутся как ключ=значение")
            pos++
            val pv = a.positional
            when (word) {
                "say", "ask", "log", "telegram" -> {
                    if (pv == null) fail(l.no, "$word: нужен текст в кавычках, например $word \"Привет\"")
                    if (pv is List<*> && (word == "say" || word == "ask")) {
                        // список фраз: при каждом запуске выбирается случайная
                        if (pv.isEmpty() || pv.any { it !is Q }) fail(l.no, "$word: в списке должны быть тексты в кавычках, например $word [\"Привет\", \"Здравствуйте\"]")
                        s.put(word, JSONArray().also { a -> pv.forEach { a.put((it as Q).s) } })
                    } else {
                        if (pv !is Q) fail(l.no, "$word: текст нужно написать в кавычках, например $word \"Привет\"" + if (word == "say" || word == "ask") " или список фраз $word [\"Привет\", \"Здравствуйте\"]" else "")
                        s.put(word, pv.s)
                    }
                }
                "request" -> {
                    if (pv == null) fail(l.no, "request: нужен адрес в кавычках, например request \"http://192.168.1.5/on\" или список адресов request @lights")
                    if (pv is List<*>) {
                        if (pv.isEmpty() || pv.any { it !is Q }) fail(l.no, "request: в списке должны быть адреса в кавычках, например request [\"http://a/on\", \"http://b/on\"]")
                        s.put("request", JSONArray().also { a -> pv.forEach { a.put((it as Q).s) } })
                    } else {
                        if (pv !is Q) fail(l.no, "request: адрес нужно написать в кавычках, например request \"http://192.168.1.5/on\"")
                        s.put("request", pv.s)
                    }
                }
                "kiosk" -> s.put("kiosk", unwrap(pv ?: fail(l.no, "kiosk: нужно действие, например kiosk show")))
                "stop" -> s.put("stop", if (pv == null) "" else (pv as? Q)?.s ?: fail(l.no, "stop: причину нужно написать в кавычках"))
                "play" -> {
                    if (pv == null) fail(l.no, "play: нужно имя файла или системный звук, например play system:alarm")
                    s.put("play", unwrap(pv))
                }
                "chat" -> {
                    if (pv == null) fail(l.no, "chat: нужен текст темы в кавычках, например chat \"Поговорить о погоде\" for=5m")
                    if (pv !is Q) fail(l.no, "chat: тему нужно написать текстом в кавычках, например chat \"Поговорить о погоде\" for=5m (имена тем с вкладки «Чат» больше не используются)")
                    s.put("chat", pv.s)
                }
                "slide" -> s.put("slide", unwrap(pv ?: fail(l.no, "slide: нужно имя файла со вкладки «Слайды» или off, например slide hello.svg for=5s")))
                "delay" -> s.put("delay", unwrap(pv ?: fail(l.no, "delay: нужна длительность, например delay 10s")))
                "photo" -> s.put("photo", true)
                "stop_background" -> s.put("stop_background", true)
                "background" -> {}
                "watch" -> {
                    val w = JSONObject()
                    w.put("motion", true)
                    w.put("for", unwrap(pv ?: fail(l.no, "watch: нужно, сколько наблюдать, например watch 30s")))
                    named(a, PARAMS.getValue("watch"), "watch", l.no, w)
                    s.put("watch", w)
                    noChildren(l, level, "watch")
                    return s
                }
                "repeat" -> {
                    val r = JSONObject()
                    r.put("times", unwrap(pv ?: fail(l.no, "repeat: нужно число повторов, например repeat 10 until=moved")))
                    named(a, PARAMS.getValue("repeat"), "repeat", l.no, r)
                    s.put("repeat", r)
                    s.put("do", block(l, level, "repeat"))
                    return s
                }
            }
            named(a, PARAMS.getValue(word), word, l.no, s)
            if (word == "background") s.put("background", block(l, level, "background")) else noChildren(l, level, word)
            return s
        }

        /** Переносит параметры в [dest], проверяя имена. */
        fun named(a: Args, allowed: List<String>, owner: String, no: Int, dest: JSONObject, kind: String = "параметра") {
            for ((k, v) in a.named) {
                if (k !in allowed) {
                    val tail = if (allowed.isEmpty()) "у него вообще нет параметров" else "допустимы: ${allowed.joinToString(", ")}"
                    fail(no, "у $owner нет $kind «$k»; $tail")
                }
                dest.put(k, unwrap(v))
            }
        }

        // ---------------- разбор значений ----------------

        fun stripComment(s: String, no: Int): String {
            var inQ = false
            var i = 0
            while (i < s.length) {
                val c = s[i]
                if (inQ) {
                    if (c == '\\') i++ else if (c == '"') inQ = false
                } else if (c == '"') inQ = true
                else if (c == '#') return s.substring(0, i)
                i++
            }
            if (inQ) fail(no, "не закрыта кавычка")
            return s
        }

        /** Разбирает аргументы строки с позиции [from]. positional: первый аргумент без имени (для zone и task — имя; у task второй, в кавычках, — название). */
        fun args(l: Line, from: Int, wantPositional: Boolean, titleSlot: Boolean = false): Args {
            val s = l.text
            var i = from
            var positional: Any? = null
            var extra: Any? = null
            var first = true
            val named = LinkedHashMap<String, Any?>()
            val cur = Cursor(s, l.no, this)
            cur.i = from
            while (true) {
                cur.skip()
                if (cur.i >= s.length) break
                val km = KEY_RE.matchAt(s, cur.i)
                if (km != null) {
                    val key = km.value.dropLast(1)
                    cur.i += km.value.length
                    if (cur.i >= s.length || s[cur.i] == ' ') fail(l.no, "у «$key» нет значения (пишите $key=значение)")
                    if (named.containsKey(key)) fail(l.no, "параметр «$key» указан дважды")
                    named[key] = cur.value(false)
                } else {
                    val v = cur.value(false)
                    if (first && wantPositional) positional = v
                    else if (titleSlot && extra == null && named.isEmpty() && v is Q) extra = v
                    else if (v is String && WORD_RE.matches(v) && v != "true" && v != "false") {
                        if (named.containsKey(v)) fail(l.no, "параметр «$v» указан дважды")
                        named[v] = true
                    } else fail(l.no, "лишнее значение «${show(v)}»: параметры пишутся как ключ=значение, флаги одним словом")
                }
                first = false
            }
            return Args(positional, named, extra)
        }

        fun show(v: Any?): String = when (v) { is Q -> "\"${v.s}\""; else -> v.toString() }
    }

    private class Cursor(val s: String, val no: Int, val p: Parser) {
        var i = 0
        fun skip() { while (i < s.length && s[i] == ' ') i++ }

        /** Значение: "текст", [список] или слово. Внутри списка слово кончается на запятой и скобке. */
        fun value(inList: Boolean): Any? {
            if (i >= s.length) p.fail(no, "ожидалось значение")
            val c = s[i]
            if (c == '"') return Q(quoted(inList))
            if (c == '[') return list()
            if (c == '@') return listRef(inList)
            val st = i
            while (i < s.length) {
                val ch = s[i]
                if (ch == ' ') break
                if (ch == '"' || ch == '[') p.fail(no, "кавычки и скобки внутри слова не допускаются: «${s.substring(st, i + 1)}»; значение с пробелами пишите в кавычках")
                if (inList && (ch == ',' || ch == ']')) break
                if (!inList && ch == ']') p.fail(no, "лишняя закрывающая скобка ]")
                i++
            }
            if (i == st) p.fail(no, "ожидалось значение")
            return typed(s.substring(st, i))
        }

        /** Ссылка `@имя` на общий список из lists.txt: подставляется весь список. */
        private fun listRef(inList: Boolean): List<Any?> {
            if (inList) p.fail(no, "ссылка @имя внутри списка не допускается: пишите её вместо всего списка (slide @имя)")
            val m = Regex("@([A-Za-z_][A-Za-z0-9_]*)").matchAt(s, i) ?: p.fail(no, "после @ нужно имя списка, например @mascots")
            val name = m.groupValues[1]
            i += m.value.length
            if (i < s.length && s[i] != ' ') p.fail(no, "после @$name нужен пробел")
            val lit = p.lists[name] ?: p.fail(no, "общий список @$name не найден: опишите его в файле lists.txt (вкладка «Задачи», «Общие списки»)")
            return Cursor(lit, no, p).list()
        }

        fun quoted(inList: Boolean): String {
            i++ // открывающая кавычка
            val sb = StringBuilder()
            while (i < s.length) {
                val c = s[i]
                if (c == '\\' && i + 1 < s.length) {
                    when (val n = s[i + 1]) {
                        'n' -> sb.append('\n'); 't' -> sb.append('\t'); '"' -> sb.append('"'); '\\' -> sb.append('\\')
                        else -> sb.append('\\').append(n)
                    }
                    i += 2
                } else if (c == '"') {
                    i++
                    if (i < s.length && s[i] != ' ' && !(inList && (s[i] == ',' || s[i] == ']'))) p.fail(no, "после закрывающей кавычки нужен пробел")
                    return sb.toString()
                } else { sb.append(c); i++ }
            }
            p.fail(no, "не закрыта кавычка")
        }

        fun list(): List<Any?> {
            i++ // [
            val out = ArrayList<Any?>()
            skip()
            if (i < s.length && s[i] == ']') { i++; endOfValue(); return out }
            while (true) {
                skip()
                out.add(value(true))
                skip()
                if (i >= s.length) p.fail(no, "не закрыта скобка [")
                if (s[i] == ',') { i++; continue }
                if (s[i] == ']') { i++; break }
                p.fail(no, "в списке между значениями нужна запятая")
            }
            endOfValue()
            return out
        }

        private fun endOfValue() {
            // внутренние списки закрываются внутри внешнего: после ] допустимы пробел, запятая, ] или конец строки
            if (i < s.length && s[i] != ' ' && s[i] != ',' && s[i] != ']') p.fail(no, "после ] нужен пробел или запятая")
        }
    }

    private fun typed(w: String): Any = when {
        w == "true" -> true
        w == "false" -> false
        INT_RE.matches(w) -> w.toInt()
        DEC_RE.matches(w) -> w.toDouble()
        else -> w
    }

    /** Заменяет Q на String и списки на JSONArray (рекурсивно). */
    private fun unwrap(v: Any?): Any? = when (v) {
        is Q -> v.s
        is List<*> -> JSONArray().also { a -> v.forEach { a.put(unwrap(it)) } }
        else -> v
    }
}
