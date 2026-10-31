package com.ffps.fserv

import android.content.Context
import org.json.JSONArray
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Файл задач (tasks.txt): текст на языке сценариев, см. [Script] и script.md. Редактируется из веб-интерфейса целиком,
 * по одной задаче и по общим строкам voice/zone. Правка одной задачи заменяет только её строки, поэтому комментарии
 * и порядок остальных задач сохраняются. Перед каждой записью предыдущая версия уходит в историю (последние 20).
 */
object TaskStore {
    private const val KEEP = 20
    const val DEFAULT = "# Задачи fServe. Язык описан в script.md в репозитории проекта.\n"
    private val NAME_RE = Regex("tasks-[0-9]{8}-[0-9]{6}(-[0-9]+)?\\.txt")

    private lateinit var file: File
    private lateinit var histDir: File

    fun init(ctx: Context) {
        file = File(ctx.filesDir, "tasks.txt")
        histDir = File(ctx.filesDir, "tasks-history").also { it.mkdirs() }
        if (!file.exists()) file.writeText(DEFAULT)
    }

    @Synchronized
    fun read(): String = file.readText()

    /** null, если текст верный; иначе «строка N: описание». */
    fun validate(text: String): String? {
        if (text.isBlank()) return null
        return Script.validate(text) { Sounds.exists(it) }
    }

    /** Для редактора: задачи с их текстом и общие строки; если файл не читается, поле error описывает проблему. */
    @Synchronized
    fun overview(): org.json.JSONObject {
        val text = read()
        val p = try { Script.parse(text) } catch (e: Script.ScriptError) {
            return obj("error" to "строка ${e.line}: ${e.message}", "tasks" to JSONArray(), "global" to "")
        }
        val all = ScriptEdit.lines(text)
        val arr = p.root.getJSONArray("tasks")
        val tasks = JSONArray()
        for ((i, sp) in p.tasks.withIndex()) {
            tasks.put(obj("id" to sp.id, "title" to arr.getJSONObject(i).optString("title"), "line" to sp.startLine, "text" to ScriptEdit.join(all.subList(sp.startLine - 1, sp.endLine))))
        }
        return obj("error" to null, "tasks" to tasks, "global" to ScriptEdit.globalText(text))
    }

    @Synchronized
    fun taskText(id: String): String? = ScriptEdit.taskText(read(), id)

    /**
     * Сохраняет одну задачу: [originalId] пуст для новой, иначе заменяются строки задачи с этим id (её можно переименовать).
     * Проверяется весь файл; при ошибке ничего не меняется. Возвращает текст ошибки или null.
     */
    @Synchronized
    fun saveTask(fragment: String, originalId: String): String? {
        val e = ScriptEdit.replaceTask(read(), originalId, fragment)
        if (e.error != null) return e.error
        return save(e.text!!)?.let { ScriptEdit.localize(it, e.start, e.count, "в другом месте файла") }
    }

    @Synchronized
    fun deleteTask(id: String): String? {
        val e = ScriptEdit.deleteTask(read(), id)
        if (e.error != null) return e.error
        return save(e.text!!.let { if (it.isBlank()) DEFAULT else it })
    }

    @Synchronized
    fun globalText(): String = ScriptEdit.globalText(read())

    /** Заменяет все строки voice и zone файла текстом [newText]. */
    @Synchronized
    fun saveGlobal(newText: String): String? {
        if (newText.isNotBlank()) Script.check(newText) { true }.first?.let { return it }
        val e = ScriptEdit.replaceGlobal(read(), newText)
        if (e.error != null) return e.error
        return save(e.text!!.let { if (it.isBlank()) DEFAULT else it })
    }

    // ---------------- файл целиком ----------------

    /** Полная замена файла и очистка истории версий (восстановление и сброс). */
    @Synchronized
    fun replaceAll(text: String?) {
        histDir.listFiles()?.forEach { it.delete() }
        file.writeText(text ?: DEFAULT)
    }

    /** null = сохранено; иначе текст ошибки (файл не изменён). */
    @Synchronized
    fun save(text: String): String? {
        val err = validate(text)
        if (err != null) return err
        if (file.exists() && file.readText() == text) return null
        archive()
        val tmp = File(file.parentFile, "tasks.txt.tmp")
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
        var f = File(histDir, "$base.txt")
        var n = 2
        while (f.exists()) f = File(histDir, "$base-${n++}.txt")
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
