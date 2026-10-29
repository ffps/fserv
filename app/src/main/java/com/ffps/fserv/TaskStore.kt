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

    /**
     * Сохраняет одну задачу: [originalId] пуст для новой, иначе заменяет задачу с этим id (можно переименовать).
     * Проверяется весь конфиг; при ошибке ничего не меняется. Возвращает текст ошибки или null.
     */
    /** Android экранирует «/» как «\/»; в cron это некрасиво, поэтому возвращаем обычную косую черту. */
    private fun pretty(root: JSONObject): String = root.toString(2).replace("\\/", "/") + "\n"

    @Synchronized
    fun saveTask(taskText: String, originalId: String): String? {
        val task = try { JSONObject(taskText) } catch (e: JSONException) { return "задача: " + explain(taskText, e) }
        val id = task.optString("id")
        if (id.isBlank()) return "в задаче нужно поле id"
        val root = try { JSONObject(read()) } catch (e: Exception) { return "текущий конфиг не читается: ${e.message}" }
        val old = root.optJSONArray("tasks") ?: JSONArray()
        val out = JSONArray()
        var placed = false
        for (i in 0 until old.length()) {
            val t = old.optJSONObject(i)
            if (t == null) { out.put(old.opt(i)); continue }
            val tid = t.optString("id")
            if (originalId.isNotEmpty() && tid == originalId) { out.put(task); placed = true }
            else {
                if (tid == id) return "задача с id «$id» уже есть"
                out.put(t)
            }
        }
        if (!placed) {
            if (originalId.isNotEmpty()) return "задачи «$originalId» уже нет (её удалили или переименовали), обновите страницу"
            out.put(task)
        }
        root.put("tasks", out)
        return save(pretty(root))
    }

    @Synchronized
    fun deleteTask(id: String): String? {
        val root = try { JSONObject(read()) } catch (e: Exception) { return "текущий конфиг не читается: ${e.message}" }
        val old = root.optJSONArray("tasks") ?: JSONArray()
        val out = JSONArray()
        var found = false
        for (i in 0 until old.length()) {
            val t = old.optJSONObject(i)
            if (t != null && t.optString("id") == id) { found = true; continue }
            out.put(old.opt(i))
        }
        if (!found) return "задачи «$id» нет"
        root.put("tasks", out)
        return save(pretty(root))
    }

    /** Заменяет общие разделы voice и zones (отсутствующий в запросе раздел не трогается; null или {} убирает его). */
    @Synchronized
    fun saveGlobal(patch: JSONObject): String? {
        val root = try { JSONObject(read()) } catch (e: Exception) { return "текущий конфиг не читается: ${e.message}" }
        for (k in listOf("voice", "zones")) if (patch.has(k)) {
            val v = patch.opt(k)
            if (v is JSONObject && v.length() > 0) root.put(k, v) else root.remove(k)
        }
        return save(pretty(root))
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
