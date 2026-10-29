package com.ffps.fserv

import android.content.Context
import org.json.JSONArray
import org.json.JSONException
import org.json.JSONObject
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Конфиг задач (tasks.json): редактируется как JSON-текст из веб-интерфейса.
 * Перед каждой записью предыдущая версия уходит в историю (последние 20).
 * Здесь проверяется только общая форма; схема самих задач появится вместе с движком сценариев.
 */
object TaskStore {
    private const val KEEP = 20
    const val DEFAULT = "{\n  \"version\": 1,\n  \"tasks\": []\n}\n"
    private val NAME_RE = Regex("tasks-[0-9]{8}-[0-9]{6}(-[0-9]+)?\\.json")

    private lateinit var file: File
    private lateinit var histDir: File

    fun init(ctx: Context) {
        file = File(ctx.filesDir, "tasks.json")
        histDir = File(ctx.filesDir, "tasks-history").also { it.mkdirs() }
        if (!file.exists()) file.writeText(DEFAULT)
    }

    @Synchronized
    fun read(): String = file.readText()

    /** null, если конфиг верный; иначе понятное описание проблемы. */
    fun validate(text: String): String? {
        if (text.isBlank()) return "конфиг пустой"
        val root = try {
            JSONObject(text)
        } catch (e: JSONException) {
            return explain(text, e)
        }
        if (!text.trimEnd().endsWith("}")) return "после закрывающей } есть лишний текст"
        return TaskSchema.validate(root) { Sounds.exists(it) }
    }

    private fun explain(text: String, e: JSONException): String {
        val m = e.message ?: "ошибка JSON"
        val head = m.substringBefore(" of ").take(160)
        val pos = Regex("at character (\\d+)").find(m)?.groupValues?.get(1)?.toIntOrNull()
        if (pos != null) {
            val p = pos.coerceIn(0, text.length)
            val line = text.substring(0, p).count { it == '\n' } + 1
            val col = p - (text.lastIndexOf('\n', p - 1) + 1) + 1
            return "$head (строка $line, столбец $col)"
        }
        return head
    }

    /** null = сохранено; иначе текст ошибки (файл не изменён). */
    @Synchronized
    fun save(text: String): String? {
        val err = validate(text)
        if (err != null) return err
        if (file.exists() && file.readText() == text) return null
        archive()
        val tmp = File(file.parentFile, "tasks.json.tmp")
        tmp.writeText(text)
        if (!tmp.renameTo(file)) {
            file.writeText(text)
            tmp.delete()
        }
        return null
    }

    private fun archive() {
        if (!file.exists()) return
        val base = "tasks-" + SimpleDateFormat("yyyyMMdd-HHmmss", Locale.US).format(Date())
        var f = File(histDir, "$base.json")
        var n = 2
        while (f.exists()) f = File(histDir, "$base-${n++}.json")
        f.writeText(file.readText())
        val all = histDir.listFiles { x -> NAME_RE.matches(x.name) }?.sortedBy { it.name } ?: return
        for (old in all.dropLast(KEEP)) old.delete()
    }

    @Synchronized
    fun history(): JSONArray {
        val res = JSONArray()
        val all = histDir.listFiles { x -> NAME_RE.matches(x.name) }?.sortedByDescending { it.name } ?: return res
        for (f in all) res.put(obj("name" to f.name, "size" to f.length(), "time" to Util.iso(f.lastModified())))
        return res
    }

    /** null = восстановлено; иначе ошибка. Текущая версия при этом сама попадает в историю. */
    @Synchronized
    fun restore(name: String): String? {
        if (!NAME_RE.matches(name)) return "неверное имя версии"
        val f = File(histDir, name)
        if (!f.exists()) return "такой версии нет"
        return save(f.readText())
    }
}
