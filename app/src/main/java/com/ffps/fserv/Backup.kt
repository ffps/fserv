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
 * Состав архива: settings.json (настройки приложения, включая токен бота и пароли), tasks.json (задачи),
 * sounds/ (загруженные звуки), pages/ (заменённые страницы index.html и kiosk.html). Очереди, журналы и логи в копию не входят.
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
            z.put("tasks.json", TaskStore.read().toByteArray(Charsets.UTF_8))
            for (n in Sounds.names()) {
                z.putNextEntry(ZipEntry("sounds/$n"))
                Sounds.file(n).inputStream().use { it.copyTo(z) }
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
            var settings: JSONObject? = null
            var tasks: String? = null
            for (e in entries) {
                val n = e.name
                if (n.contains("..") || n.startsWith("/") || n.contains("\\")) return "недопустимое имя в архиве: $n"
                total += maxOf(e.size, 0)
                when {
                    n == "settings.json" -> settings = try { JSONObject(readText(zf, e)) } catch (x: Exception) { return "settings.json: ${x.message}" }
                    n == "tasks.json" -> tasks = try { readText(zf, e) } catch (x: IllegalArgumentException) { return x.message }
                    n.startsWith("sounds/") && n.count { it == '/' } == 1 -> {
                        val name = n.removePrefix("sounds/")
                        try { Sounds.checkName(name) } catch (x: IllegalArgumentException) { return "звук «$name»: недопустимое имя" }
                        if (e.size !in 1..Sounds.MAX_FILE) return "звук «$name»: размер больше ${Sounds.MAX_FILE / 1048576} МБ"
                        sounds.add(name)
                    }
                    n.startsWith("pages/") && PAGES.any { n == "pages/$it.html" } -> if (e.size > 1_000_000) return "$n: слишком большая страница"
                    else -> return "лишний файл в архиве: $n (это не копия fServe?)"
                }
            }
            if (settings == null) return "в архиве нет settings.json (это не копия fServe?)"
            if (tasks == null) return "в архиве нет tasks.json"
            if (total > Sounds.MAX_TOTAL + 10_000_000) return "архив слишком большой"
            if (sounds.sumOf { s -> zf.getEntry("sounds/$s").size } > Sounds.MAX_TOTAL) return "звуки вместе больше ${Sounds.MAX_TOTAL / 1048576} МБ"
            val root = try { JSONObject(tasks) } catch (x: Exception) { return "tasks.json: ${x.message}" }
            val err = TaskSchema.validate(root) { name -> sounds.contains(name) }
            if (err != null) return "tasks.json: $err"
        }
        return null
    }

    private fun wipe(ctx: Context) {
        TaskEngine.cancelCurrent()
        Sounds.clearAll()
        File(ctx.filesDir, "web").listFiles()?.forEach { it.delete() }
        File(ctx.filesDir, "update").deleteRecursively()
        Telegram.clear()
    }

    /** Применяет проверенный архив: чистит пользовательские данные и раскладывает данные из копии. */
    fun restore(ctx: Context, file: File) {
        val zf = ZipFile(file)
        zf.use {
            val settings = JSONObject(readText(zf, zf.getEntry("settings.json")))
            val tasks = readText(zf, zf.getEntry("tasks.json"))
            wipe(ctx)
            for (e in zf.entries().asSequence().filter { !it.isDirectory }) {
                when {
                    e.name.startsWith("sounds/") -> Sounds.save(e.name.removePrefix("sounds/"), zf.getInputStream(e), e.size)
                    e.name.startsWith("pages/") -> {
                        val d = File(ctx.filesDir, "web").also { it.mkdirs() }
                        zf.getInputStream(e).use { i -> File(d, e.name.removePrefix("pages/")).outputStream().use { o -> i.copyTo(o) } }
                    }
                }
            }
            TaskStore.replaceAll(tasks)
            Settings.replaceAll(settings)
        }
    }

    /** Возврат к исходному состоянию: пользовательские файлы, очереди, кэши и настройки (кроме доступа) сбрасываются. */
    fun reset(ctx: Context) {
        wipe(ctx)
        TaskStore.replaceAll(null)
        Settings.resetUser()
        Journal.clear()
        File(ctx.filesDir, "backup-seq.txt").delete()
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
