package com.ffps.fserv

import org.json.JSONArray
import org.json.JSONObject

/** Чистая логика набора задач (без Android): разбор всех файлов и проверка имён. Хранение на диске — в [TaskStore]. */
object TaskSet {
    const val COMMON = "_common"
    val ID_RE = Regex("[A-Za-z0-9][A-Za-z0-9_-]{0,39}")

    /** Результат чтения всех файлов: [root] с голосом, зонами и исправными задачами, [errors] — ошибки по id, [commonError] — ошибка общего файла. */
    class Loaded(val root: JSONObject, val errors: Map<String, String>, val commonError: String?)

    /** Текст ошибки, если [id] не годится в имя файла; иначе null. */
    fun checkId(id: String): String? =
        if (ID_RE.matches(id)) null else "id задачи: латинские буквы, цифры, _ и - (до 40 знаков), начинаться с буквы или цифры, например wakeup"

    /** Разбор набора файлов (имя файла → текст): чистая логика, без обращения к диску. */
    fun parseSet(files: Map<String, String>, fileExists: (String) -> Boolean): Loaded {
        val root = JSONObject()
        root.put("version", 1)
        var commonError: String? = null
        var zones = emptySet<String>()
        files["$COMMON.txt"]?.let { text ->
            val (err, _) = Script.checkCommon(text)
            if (err != null) commonError = err
            else {
                val c = Script.parseCommon(text).root
                c.optJSONObject("voice")?.let { root.put("voice", it) }
                c.optJSONObject("zones")?.let { root.put("zones", it); zones = it.keys().asSequence().toSet() }
            }
        }
        val errors = LinkedHashMap<String, String>()
        val tasks = JSONArray()
        for (name in files.keys.sorted()) {
            val id = name.removeSuffix(".txt")
            if (!name.endsWith(".txt") || id == COMMON) continue
            if (!ID_RE.matches(id)) { errors[id] = checkId(id)!!; continue }
            val text = files.getValue(name)
            val err = Script.validate(text, id, zones, fileExists)
            if (err != null) errors[id] = err
            else tasks.put(Script.parse(text, id).root.getJSONArray("tasks").getJSONObject(0))
        }
        root.put("tasks", tasks)
        return Loaded(root, errors, commonError)
    }

    /** Проверка набора из архива: null или «файл: строка N: …». */
    fun validateSet(files: Map<String, String>, fileExists: (String) -> Boolean): String? {
        for (n in files.keys) if (!n.endsWith(".txt") || !(n == "$COMMON.txt" || ID_RE.matches(n.removeSuffix(".txt")))) return "tasks/$n: недопустимое имя файла"
        val l = parseSet(files, fileExists)
        l.commonError?.let { return "tasks/$COMMON.txt: $it" }
        l.errors.entries.firstOrNull()?.let { return "tasks/${it.key}.txt: ${it.value}" }
        return null
    }

}
