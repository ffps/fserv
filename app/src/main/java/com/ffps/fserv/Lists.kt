package com.ffps.fserv

/**
 * Общие списки (файл lists.txt, не в каталоге задач): `имя = ["а", "б", в]`. В задачах вместо списка-литерала пишется `@имя`
 * (`slide @mascots bg=@pastels`). Чистая логика без Android; хранение на диске — в [TaskStore], описание — в script.md.
 *
 * Формат файла: по одному списку на запись, запись может занимать несколько строк (пока не закрыта скобка), комментарии с `#`.
 * Результат разбора — имя → список в виде одной строки (литерал), который [Script] разбирает на месте ссылки.
 */
object Lists {
    val NAME_RE = Regex("[A-Za-z_][A-Za-z0-9_]{0,39}")
    private val DEF_RE = Regex("""^([A-Za-z_][A-Za-z0-9_]*)\s*=\s*(.*)$""")
    private val REF_RE = Regex("""@([A-Za-z_][A-Za-z0-9_]*)""")
    const val MAX_ITEMS = 300

    /** Разбирает файл списков; бросает [Script.ScriptError] с номером строки. */
    fun parse(text: String): Map<String, String> {
        val out = LinkedHashMap<String, String>()
        val raw = text.replace("\r\n", "\n").replace('\r', '\n').split("\n")
        var name = ""
        var startNo = 0
        val buf = StringBuilder()
        var depth = 0

        fun finish() {
            val lit = buf.toString().trim()
            val n = Script.listLiteral(lit, startNo)
            if (n == 0) throw Script.ScriptError(startNo, "список «$name» пуст")
            if (n > MAX_ITEMS) throw Script.ScriptError(startNo, "в списке «$name» $n элементов, не больше $MAX_ITEMS")
            out[name] = lit
            buf.setLength(0); depth = 0; name = ""
        }

        for ((i, r) in raw.withIndex()) {
            val no = i + 1
            val body = stripComment(r, no).trim()
            if (body.isEmpty()) continue
            if (name.isEmpty()) {
                val m = DEF_RE.matchEntire(body) ?: throw Script.ScriptError(no, "ожидалось «имя = [список]», например mascots = [\"a.gif\", \"b.gif\"]")
                name = m.groupValues[1]
                if (name.length > 40) throw Script.ScriptError(no, "имя списка не длиннее 40 знаков")
                if (out.containsKey(name)) throw Script.ScriptError(no, "список «$name» описан дважды")
                startNo = no
                buf.append(m.groupValues[2])
            } else {
                buf.append(' ').append(body)
            }
            depth = balance(buf.toString(), startNo)
            if (depth < 0) throw Script.ScriptError(no, "лишняя закрывающая скобка ]")
            if (depth == 0) finish()
        }
        if (name.isNotEmpty()) throw Script.ScriptError(startNo, "не закрыта скобка [ в списке «$name»")
        return out
    }

    /** Текст ошибки «строка N: …» или null. */
    fun check(text: String): String? = try { parse(text); null } catch (e: Script.ScriptError) { "строка ${e.line}: ${e.message}" }

    /** Строка ссылок вида `@имя` → литералы списков (для поиска имён файлов в тексте задач); неизвестные имена остаются как есть. */
    fun inline(line: String, table: Map<String, String>): String =
        if (table.isEmpty() || !line.contains('@')) line else REF_RE.replace(line) { table[it.groupValues[1]] ?: it.value }

    /** Имена списков, на которые ссылается [line] (вне кавычек не различаем: достаточно для подсказки «где используется»). */
    fun refs(line: String): Set<String> = REF_RE.findAll(line).map { it.groupValues[1] }.toSet()

    /** Баланс скобок [ ] вне кавычек; не закрытая кавычка в строке — ошибка. */
    private fun balance(s: String, no: Int): Int {
        var d = 0
        var inQ = false
        var i = 0
        while (i < s.length) {
            val c = s[i]
            if (inQ) { if (c == '\\') i++ else if (c == '"') inQ = false }
            else if (c == '"') inQ = true
            else if (c == '[') d++
            else if (c == ']') d--
            i++
        }
        if (inQ) throw Script.ScriptError(no, "не закрыта кавычка")
        return d
    }

    private fun stripComment(s: String, no: Int): String {
        var inQ = false
        var i = 0
        while (i < s.length) {
            val c = s[i]
            if (inQ) { if (c == '\\') i++ else if (c == '"') inQ = false }
            else if (c == '"') inQ = true
            else if (c == '#') return s.substring(0, i)
            i++
        }
        if (inQ) throw Script.ScriptError(no, "не закрыта кавычка")
        return s
    }
}
