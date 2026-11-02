package com.ffps.fserv

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Хранилище задач: каталог tasks/ с файлом на каждую задачу (tasks/<id>.txt, id — имя файла, в тексте его нет).
 * Голос и зоны детектора движения хранятся в настройках приложения ([Settings]). Язык описан в [Script] и script.md.
 * Ошибка в одном файле отключает только его задачу: остальные работают. Перед каждым изменением набор файлов целиком
 * копируется в историю (последние 3 версии, каталоги tasks-history/tasks-ДАТА-ВРЕМЯ).
 */
object TaskStore {
    private const val KEEP = 3
    val ID_RE = TaskSet.ID_RE
    private val HIST_RE = Regex("tasks-[0-9]{8}-[0-9]{6}(-[0-9]+)?")

    private lateinit var dir: File
    private lateinit var histDir: File
    @Volatile private var gen = 0
    private var cache: TaskSet.Loaded? = null
    private var cacheGen = -1
    private var cacheAt = 0L

    fun init(ctx: Context) {
        dir = File(ctx.filesDir, "tasks").also { it.mkdirs() }
        histDir = File(ctx.filesDir, "tasks-history").also { it.mkdirs() }
        File(ctx.filesDir, "tasks.txt").delete() // файл прежнего формата
        File(dir, "_common.txt").delete()        // общий файл прежнего формата: голос и зоны теперь в настройках приложения
    }

    private fun file(id: String) = File(dir, "$id.txt")

    fun checkId(id: String): String? = TaskSet.checkId(id)

    @Synchronized
    fun ids(): List<String> =
        (dir.listFiles { f -> f.isFile && f.name.endsWith(".txt") } ?: emptyArray())
            .map { it.name.removeSuffix(".txt") }.filter { ID_RE.matches(it) }.sorted()

    @Synchronized
    fun taskText(id: String): String? = if (ID_RE.matches(id) && file(id).isFile) file(id).readText() else null

    /** Все файлы набора как имя файла → текст (для копии и истории). */
    @Synchronized
    fun files(): Map<String, String> =
        (dir.listFiles { f -> f.isFile && f.name.endsWith(".txt") } ?: emptyArray()).sortedBy { it.name }.associate { it.name to it.readText() }

    /** Разбор всего набора; результат кэшируется до следующего изменения файлов. */
    @Synchronized
    fun load(): TaskSet.Loaded {
        // кэш живёт до изменения файлов и не дольше 5 секунд: проверка ссылается на звуки, которые тоже меняются
        cache?.let { if (cacheGen == gen && System.currentTimeMillis() - cacheAt < 5000) return it }
        val l = TaskSet.parseSet(files(), Settings.voice(), Settings.zones(), { Slides.exists(it) }) { Sounds.exists(it) }
        cache = l; cacheGen = gen; cacheAt = System.currentTimeMillis()
        return l
    }

    /** Для редактора: список задач (id, название, текст, ошибка) и общие строки. */
    @Synchronized
    fun overview(): JSONObject {
        val l = load()
        val byId = HashMap<String, JSONObject>()
        val a = l.root.getJSONArray("tasks")
        for (i in 0 until a.length()) a.getJSONObject(i).let { byId[it.getString("id")] = it }
        val tasks = JSONArray()
        for (id in ids()) {
            tasks.put(obj("id" to id, "title" to (byId[id]?.optString("title") ?: titleGuess(taskText(id) ?: "")), "text" to (taskText(id) ?: ""), "error" to l.errors[id]))
        }
        return obj("tasks" to tasks)
    }

    /** Название для списка, если задача не читается: из заголовка `task "Название"`/`title=`. */
    private fun titleGuess(text: String): String {
        val line = text.lineSequence().firstOrNull { it.startsWith("task") } ?: return ""
        return Regex("""title="((?:[^"\\]|\\.)*)"""").find(line)?.groupValues?.get(1)
            ?: Regex("""^task\s+"((?:[^"\\]|\\.)*)"""").find(line)?.groupValues?.get(1) ?: ""
    }

    // ---------------- запись ----------------

    private fun touch() { gen++ }

    /** Настройки приложения (голос, зоны) изменились: разбор задач нужно повторить. */
    @Synchronized
    fun invalidate() { gen++ }

    private fun writeAtomic(f: File, text: String) {
        val tmp = File(f.parentFile, f.name + ".tmp")
        tmp.writeText(text)
        if (!tmp.renameTo(f)) { f.writeText(text); tmp.delete() }
    }

    /**
     * Сохраняет задачу: [originalId] пуст для новой, иначе файл этой задачи; если [newId] другой, файл переименовывается.
     * Номера строк в ошибках совпадают с номерами в редакторе. Возвращает текст ошибки или null.
     */
    @Synchronized
    fun saveTask(originalId: String, newId: String, text: String): String? {
        checkId(newId)?.let { return it }
        if (newId.startsWith("_")) return "id не может начинаться с _"
        if (text.isBlank()) return "текст задачи пуст"
        if (originalId.isNotEmpty() && !file(originalId).isFile) return "задачи «$originalId» уже нет (её удалили или переименовали), обновите страницу"
        if (newId != originalId && file(newId).exists()) return "задача с id «$newId» уже есть"
        val zones = Settings.zones().keys().asSequence().toSet()
        Script.validate(text, newId, zones, { Slides.exists(it) }) { Sounds.exists(it) }?.let { return it }
        if (originalId == newId && file(newId).readText() == text) return null
        archive()
        writeAtomic(file(newId), text)
        if (originalId.isNotEmpty() && originalId != newId) file(originalId).delete()
        touch()
        return null
    }

    @Synchronized
    fun deleteTask(id: String): String? {
        if (!ID_RE.matches(id) || !file(id).isFile) return "задачи «$id» нет"
        archive()
        file(id).delete()
        touch()
        return null
    }

    // ---------------- набор целиком: копия, сброс, история ----------------

    /** Полная замена набора и очистка истории (восстановление из копии и сброс). */
    @Synchronized
    fun replaceAll(files: Map<String, String>?) {
        histDir.listFiles()?.forEach { it.deleteRecursively() }
        dir.listFiles()?.forEach { it.delete() }
        for ((n, t) in files ?: emptyMap()) File(dir, n).writeText(t)
        touch()
    }

    private fun archive() {
        val list = dir.listFiles { f -> f.isFile } ?: return
        if (list.isEmpty()) return
        val base = "tasks-" + SimpleDateFormat("yyyyMMdd-HHmmss", Locale.US).format(Date())
        var d = File(histDir, base)
        var n = 2
        while (d.exists()) d = File(histDir, "$base-${n++}")
        d.mkdirs()
        for (f in list) f.copyTo(File(d, f.name))
        val all = histDir.listFiles { x -> x.isDirectory && HIST_RE.matches(x.name) }?.sortedBy { it.name } ?: return
        for (old in all.dropLast(KEEP)) old.deleteRecursively()
    }

    @Synchronized
    fun history(): JSONArray {
        val res = JSONArray()
        val all = histDir.listFiles { x -> x.isDirectory && HIST_RE.matches(x.name) }?.sortedByDescending { it.name } ?: return res
        for (d in all) {
            val fs = d.listFiles() ?: emptyArray()
            res.put(obj("name" to d.name, "size" to fs.sumOf { it.length() }, "tasks" to fs.size, "time" to Util.iso(d.lastModified())))
        }
        return res
    }

    /** Удаляет всю историю версий. */
    @Synchronized
    fun clearHistory() { histDir.listFiles()?.forEach { it.deleteRecursively() } }

    /** null = восстановлено; иначе ошибка. Текущий набор при этом сам попадает в историю. */
    @Synchronized
    fun restore(name: String): String? {
        if (!HIST_RE.matches(name)) return "неверное имя версии"
        val d = File(histDir, name)
        if (!d.isDirectory) return "такой версии нет"
        val files = (d.listFiles() ?: emptyArray()).associate { it.name to it.readText() }
        archive()
        dir.listFiles()?.forEach { it.delete() }
        for ((n, t) in files) File(dir, n).writeText(t)
        touch()
        return null
    }
}
