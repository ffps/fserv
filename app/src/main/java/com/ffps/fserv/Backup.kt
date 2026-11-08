package com.ffps.fserv

import android.content.Context
import org.json.JSONObject
import java.io.File
import java.io.InputStream
import java.io.OutputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipFile
import java.util.zip.ZipOutputStream

/**
 * Резервная копия настроек одним zip-файлом, восстановление из неё и сброс к исходному состоянию.
 *
 * Состав архива: lists.txt (общие списки, если есть), settings.json (настройки приложения, включая токен бота и пароли), tasks/ (по файлу на задачу: tasks/<id>.txt; голос и зоны лежат в settings.json),
 * sounds/ (загруженные звуки), slides/ (слайды), pages/ (заменённые страницы index.html и kiosk.html). Очереди, журналы и логи в копию не входят.
 * Восстановление сначала целиком проверяет архив и только потом что-либо удаляет.
 */
object Backup {
    private val PAGES = listOf("index", "kiosk")
    const val MAX_UPLOAD = 260L * 1024 * 1024
    private const val MAX_TEXT = 3L * 1024 * 1024

    /** Имя вида fserv.2026-10-29.01.zip; порядковый номер за день растёт при каждой выгрузке. */
    fun nextName(ctx: Context): String {
        val day = java.text.SimpleDateFormat("yyyy-MM-dd", java.util.Locale.US).format(java.util.Date())
        val st = File(ctx.filesDir, "backup-seq.txt")
        var n = 1
        try {
            val p = st.readText().trim().split(" ")
            if (p.size == 2 && p[0] == day) n = (p[1].toIntOrNull() ?: 0) + 1
        } catch (e: Exception) {}
        try { st.writeText("$day $n") } catch (e: Exception) {}
        return "fserv.$day.${n.toString().padStart(2, '0')}.zip"
    }

    private fun ZipOutputStream.put(name: String, bytes: ByteArray) {
        putNextEntry(ZipEntry(name)); write(bytes); closeEntry()
    }

    fun write(ctx: Context, out: OutputStream) {
        ZipOutputStream(out).use { z ->
            z.put("settings.json", Settings.exportJson().toString(2).toByteArray(Charsets.UTF_8))
            for ((n, t) in TaskStore.files()) z.put("tasks/$n", t.toByteArray(Charsets.UTF_8))
            TaskStore.listsText().takeIf { it.isNotBlank() }?.let { z.put("lists.txt", it.toByteArray(Charsets.UTF_8)) }
            for (n in Sounds.names()) {
                z.putNextEntry(ZipEntry("sounds/$n"))
                Sounds.file(n).inputStream().use { it.copyTo(z) }
                z.closeEntry()
            }
            for (n in Slides.names()) {
                z.putNextEntry(ZipEntry("slides/$n"))
                Slides.file(n).inputStream().use { it.copyTo(z) }
                z.closeEntry()
            }
            for (p in PAGES) {
                val f = File(File(ctx.filesDir, "web"), "$p.html")
                if (f.isFile && f.length() > 0) z.put("pages/$p.html", f.readBytes())
            }
        }
    }

    private fun readText(zf: ZipFile, e: ZipEntry): String {
        require(e.size in 0..MAX_TEXT) { "${e.name}: слишком большой файл" }
        return zf.getInputStream(e).use { String(it.readBytes(), Charsets.UTF_8) }
    }

    /** null, если архив годится для восстановления; иначе понятная причина. Ничего не изменяет. */
    fun validate(file: File): String? {
        val zf = try { ZipFile(file) } catch (e: Exception) { return "это не zip-архив" }
        zf.use {
            val entries = zf.entries().asSequence().filter { !it.isDirectory }.toList()
            if (entries.isEmpty()) return "архив пустой"
            var total = 0L
            val sounds = HashSet<String>()
            val slides = HashSet<String>()
            var settings: JSONObject? = null
            val tasks = LinkedHashMap<String, String>()
            var lists = ""
            for (e in entries) {
                val n = e.name
                if (n.contains("..") || n.startsWith("/") || n.contains("\\")) return "недопустимое имя в архиве: $n"
                total += maxOf(e.size, 0)
                when {
                    n == "settings.json" -> settings = try { JSONObject(readText(zf, e)) } catch (x: Exception) { return "settings.json: ${x.message}" }
                    n == "lists.txt" -> lists = try { readText(zf, e) } catch (x: IllegalArgumentException) { return x.message }
                    n.startsWith("tasks/") && n.count { it == '/' } == 1 -> tasks[n.removePrefix("tasks/")] = try { readText(zf, e) } catch (x: IllegalArgumentException) { return x.message }
                    n.startsWith("sounds/") && n.count { it == '/' } == 1 -> {
                        val name = n.removePrefix("sounds/")
                        try { Sounds.checkName(name) } catch (x: IllegalArgumentException) { return "звук «$name»: недопустимое имя" }
                        if (e.size !in 1..Sounds.MAX_FILE) return "звук «$name»: размер больше ${Sounds.MAX_FILE / 1048576} МБ"
                        sounds.add(name)
                    }
                    n.startsWith("slides/") && n.count { it == '/' } == 1 -> {
                        val name = n.removePrefix("slides/")
                        try { Slides.checkName(name) } catch (x: IllegalArgumentException) { return "слайд «$name»: недопустимое имя" }
                        if (e.size !in 1..Slides.MAX_FILE) return "слайд «$name»: размер больше ${Slides.MAX_FILE / 1048576} МБ"
                        slides.add(name)
                    }
                    n.startsWith("pages/") && PAGES.any { n == "pages/$it.html" } -> if (e.size > 1_000_000) return "$n: слишком большая страница"
                    else -> return "лишний файл в архиве: $n (это не копия fServe?)"
                }
            }
            if (settings == null) return "в архиве нет settings.json (это не копия fServe?)"
            if (total > Sounds.MAX_TOTAL + 10_000_000) return "архив слишком большой"
            if (sounds.sumOf { s -> zf.getEntry("sounds/$s").size } > Sounds.MAX_TOTAL) return "звуки вместе больше ${Sounds.MAX_TOTAL / 1048576} МБ"
            if (slides.sumOf { s -> zf.getEntry("slides/$s").size } > Slides.MAX_TOTAL) return "слайды вместе больше ${Slides.MAX_TOTAL / 1048576} МБ"
            val table = try { Lists.parse(lists) } catch (x: Script.ScriptError) { return "lists.txt: строка ${x.line}: ${x.message}" }
            TaskSet.validateSet(tasks, settings.optJSONObject("zones"), settings.optJSONObject("chat") ?: JSONObject(), table, { name -> slides.contains(name) }) { name -> sounds.contains(name) }?.let { return it }
        }
        return null
    }

    private fun wipe(ctx: Context) {
        TaskEngine.cancelCurrent()
        Sounds.clearAll()
        Slides.clearAll()
        File(ctx.filesDir, "web").listFiles()?.forEach { it.delete() }
        File(ctx.filesDir, "update").deleteRecursively()
        Telegram.clear()
    }

    /** Применяет проверенный архив: чистит пользовательские данные и раскладывает данные из копии. */
    fun restore(ctx: Context, file: File) {
        val zf = ZipFile(file)
        zf.use {
            val settings = JSONObject(readText(zf, zf.getEntry("settings.json")))
            val tasks = zf.entries().asSequence().filter { !it.isDirectory && it.name.startsWith("tasks/") }
                .associate { it.name.removePrefix("tasks/") to readText(zf, it) }
            val lists = zf.getEntry("lists.txt")?.let { readText(zf, it) }
            wipe(ctx)
            for (e in zf.entries().asSequence().filter { !it.isDirectory }) {
                when {
                    e.name.startsWith("sounds/") -> Sounds.save(e.name.removePrefix("sounds/"), zf.getInputStream(e), e.size)
                    e.name.startsWith("slides/") -> Slides.save(e.name.removePrefix("slides/"), zf.getInputStream(e), e.size)
                    e.name.startsWith("pages/") -> {
                        val d = File(ctx.filesDir, "web").also { it.mkdirs() }
                        zf.getInputStream(e).use { i -> File(d, e.name.removePrefix("pages/")).outputStream().use { o -> i.copyTo(o) } }
                    }
                }
            }
            TaskStore.setLists(lists)
            TaskStore.replaceAll(tasks)
            Settings.replaceAll(settings)
            Sessions.clear() // пароль в копии может быть другим
            TaskStore.invalidate()
        }
    }

    /** Возврат к исходному состоянию: пользовательские файлы, очереди, кэши и настройки (кроме доступа) сбрасываются. Фотографии в галерее (DCIM/fServ) не удаляются. */
    fun reset(ctx: Context, starter: Boolean = false) {
        wipe(ctx)
        TaskStore.replaceAll(null)
        TaskStore.setLists(null)
        Settings.resetUser()
        TaskStore.invalidate()
        File(ctx.filesDir, "backup-seq.txt").delete()
        if (starter) try { Starter.install(ctx) } catch (e: Exception) { FLog.w("backup", "стартовый набор не разложен: ${e.message}") }
    }

    /** Сохраняет тело запроса во временный файл не больше [MAX_UPLOAD] байт. */
    fun receive(ctx: Context, input: InputStream, len: Long): File {
        require(len in 1..MAX_UPLOAD) { "файл копии должен быть не больше ${MAX_UPLOAD / 1048576} МБ" }
        val tmp = File(ctx.cacheDir, "restore.zip")
        tmp.delete()
        tmp.outputStream().use { out ->
            val buf = ByteArray(64 * 1024)
            var left = len
            while (left > 0) {
                val k = input.read(buf, 0, minOf(buf.size.toLong(), left).toInt())
                if (k < 0) break
                out.write(buf, 0, k)
                left -= k
            }
            require(left == 0L) { "загрузка оборвалась, получено ${len - left} из $len байт" }
        }
        return tmp
    }
}
