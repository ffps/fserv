package com.ffps.fserv

import android.content.Context
import org.json.JSONArray
import java.io.File
import java.io.InputStream

/** Файлы слайдов (картинки для шага slide): jpg, png, gif, webp, svg, в том числе анимированные. Лежат во внутренней папке slides/. */
object Slides {
    const val MAX_FILE = 5L * 1024 * 1024
    const val MAX_TOTAL = 30L * 1024 * 1024
    private val NAME = Regex("""[\p{L}0-9 _.()\-]{1,60}\.(jpg|jpeg|png|gif|webp|svg)""", RegexOption.IGNORE_CASE)

    private lateinit var dir: File

    fun init(ctx: Context) {
        dir = File(ctx.filesDir, "slides").also { it.mkdirs() }
    }

    fun checkName(name: String?): String {
        val n = name?.trim().orEmpty()
        require(NAME.matches(n) && !n.contains("..")) { "имя файла: буквы, цифры, пробел, _ . ( ) -, расширение jpg, png, gif, webp или svg, до 60 знаков" }
        return n
    }

    fun exists(name: String): Boolean = NAME.matches(name) && File(dir, name).isFile

    fun file(name: String): File = File(dir, checkName(name))

    fun mime(name: String): String = when (name.substringAfterLast('.').lowercase()) {
        "jpg", "jpeg" -> "image/jpeg"; "png" -> "image/png"; "gif" -> "image/gif"; "webp" -> "image/webp"; else -> "image/svg+xml"
    }

    fun list(): JSONArray {
        val files = dir.listFiles { f -> f.isFile && NAME.matches(f.name) }?.sortedBy { it.name.lowercase() } ?: emptyList()
        return arr(files.map { obj("name" to it.name, "size" to it.length()) })
    }

    fun totalSize(): Long = dir.listFiles()?.sumOf { it.length() } ?: 0L

    /** Сохраняет файл из потока (не более len байт). Бросает IllegalArgumentException с понятным текстом. */
    fun save(name: String, input: InputStream, len: Long) {
        val n = checkName(name)
        require(len in 1..MAX_FILE) { "слайд должен быть не больше ${MAX_FILE / 1048576} МБ" }
        require(totalSize() - (File(dir, n).takeIf { it.exists() }?.length() ?: 0L) + len <= MAX_TOTAL) { "все слайды вместе не больше ${MAX_TOTAL / 1048576} МБ" }
        val tmp = File(dir, "$n.part")
        try {
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
            val dst = File(dir, n)
            dst.delete()
            require(tmp.renameTo(dst)) { "не удалось сохранить файл" }
        } finally {
            tmp.delete()
        }
    }

    fun delete(name: String): Boolean = file(name).delete()

    fun clearAll() { dir.listFiles()?.forEach { it.delete() } }

    fun names(): List<String> = dir.listFiles()?.filter { it.isFile && NAME.matches(it.name) }?.map { it.name }?.sorted() ?: emptyList()
}
