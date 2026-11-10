package com.ffps.fserv

import org.json.JSONArray
import org.json.JSONObject

/**
 * Пути к настройкам для задач: `set voice.volume=30` и подстановка `{settings.voice.volume}`.
 * Чистая логика без Android: разбор пути, чтение значения и заплатка для [Settings.update].
 */
object SettingsPath {
    /** Путь: ключи из букв, цифр и _ через точку (voice.volume, llm.groq.model, webPort). */
    val PATH_RE = Regex("""[A-Za-z_][A-Za-z0-9_]*(\.[A-Za-z0-9_]+)*""")

    /** Значения, которые приложение вычисляет само: читаются, но не записываются. */
    val READ_ONLY = setOf("passwordSet", "telegram.tokenSet", "telegram.tokenHint", "chat.defaultRules")

    /** Подстановка в тексте: `{settings.путь}`. */
    const val PREFIX = "settings."

    /** Значение по пути [path] или null, если такого нет. Числа в путях — номера в списках. */
    fun lookup(root: JSONObject, path: String): Any? {
        var cur: Any? = root
        for (seg in path.split('.')) {
            cur = when (cur) {
                is JSONObject -> if (cur.has(seg)) cur.opt(seg) else return null
                is JSONArray -> seg.toIntOrNull()?.takeIf { it in 0 until cur.length() }?.let { cur.opt(it) } ?: return null
                else -> return null
            }
        }
        return if (cur == JSONObject.NULL) null else cur
    }

    /** Текст значения для подстановки: логические значения — true/false (чтобы `set x={settings.x}` вернул то же самое), объекты и списки — JSON. */
    fun text(v: Any?): String = when (v) {
        null -> ""
        is JSONObject, is JSONArray -> v.toString()
        else -> v.toString()
    }

    /**
     * Заплатка для [Settings.update], которая меняет один параметр. Раздел верхнего уровня передаётся целиком (некоторые разделы,
     * например voice и zones, заменяются полностью), поэтому берётся его текущее содержимое [current] (то, что отдаёт веб-интерфейс)
     * либо [stored] (сохранённое как есть), и в нём заменяется нужный ключ.
     */
    fun patch(current: JSONObject, stored: JSONObject, path: String, value: Any?): JSONObject {
        val segs = path.split('.')
        val top = segs[0]
        if (segs.size == 1) return JSONObject().put(top, value)
        val base = JSONObject((current.optJSONObject(top) ?: stored.optJSONObject(top) ?: JSONObject()).toString())
        var cur = base
        for (i in 1 until segs.size - 1) {
            cur = cur.optJSONObject(segs[i]) ?: JSONObject().also { cur.put(segs[i], it) }
        }
        cur.put(segs.last(), value)
        return JSONObject().put(top, base)
    }
}
