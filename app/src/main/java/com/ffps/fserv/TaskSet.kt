package com.ffps.fserv

import org.json.JSONArray
import org.json.JSONObject

/** Чистая логика набора задач (без Android): разбор всех файлов и проверка имён. Хранение на диске — в [TaskStore]. */
object TaskSet {
    val ID_RE = Regex("[A-Za-z0-9][A-Za-z0-9_-]{0,39}")

    /** Результат чтения всех файлов: [root] с голосом, зонами (из настроек приложения) и исправными задачами, [errors] — ошибки по id. */
    class Loaded(val root: JSONObject, val errors: Map<String, String>)

    /** Текст ошибки, если [id] не годится в имя файла; иначе null. */
    fun checkId(id: String): String? =
        if (ID_RE.matches(id)) null else "id задачи: латинские буквы, цифры, _ и - (до 40 знаков), начинаться с буквы или цифры, например wakeup"

    /** Разбор набора файлов (имя файла → текст): чистая логика, без обращения к диску. [voice], [zones] и [chat] (правила разговора) — настройки приложения. */
    fun parseSet(files: Map<String, String>, voice: JSONObject? = null, zones: JSONObject? = null, chat: JSONObject? = null, lists: Map<String, String> = emptyMap(), slideExists: (String) -> Boolean = { true }, fileExists: (String) -> Boolean): Loaded {
        val root = JSONObject()
        root.put("version", 1)
        if (voice != null && voice.length() > 0) root.put("voice", voice)
        if (zones != null && zones.length() > 0) root.put("zones", zones)
        if (chat != null && chat.length() > 0) root.put("chat", chat)
        val zoneNames = zones?.keys()?.asSequence()?.toSet() ?: emptySet()
        val errors = LinkedHashMap<String, String>()
        val tasks = JSONArray()
        for (name in files.keys.sorted()) {
            val id = name.removeSuffix(".txt")
            if (!name.endsWith(".txt")) continue
            if (!ID_RE.matches(id)) { errors[id] = checkId(id)!!; continue }
            val text = files.getValue(name)
            val err = Script.validate(text, id, zoneNames, lists, slideExists, fileExists)
            if (err != null) errors[id] = err
            else tasks.put(Script.parse(text, id, lists).root.getJSONArray("tasks").getJSONObject(0))
        }
        root.put("tasks", tasks)
        return Loaded(root, errors)
    }

    /** Проверка набора из архива: null или «файл: строка N: …». */
    fun validateSet(files: Map<String, String>, zones: JSONObject? = null, chat: JSONObject? = null, lists: Map<String, String> = emptyMap(), slideExists: (String) -> Boolean = { true }, fileExists: (String) -> Boolean): String? {
        for (n in files.keys) if (!n.endsWith(".txt") || !ID_RE.matches(n.removeSuffix(".txt"))) return "tasks/$n: недопустимое имя файла"
        val l = parseSet(files, null, zones, chat, lists, slideExists, fileExists)
        l.errors.entries.firstOrNull()?.let { return "tasks/${it.key}.txt: ${it.value}" }
        return null
    }

}
