package com.ffps.fserv

import android.content.Context
import android.graphics.BitmapFactory
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
        return arr(files.map { f -> val d = dimensions(f); obj("name" to f.name, "size" to f.length(), "width" to d?.first, "height" to d?.second) })
    }

    /** Размер картинки в пикселях (для svg: из width/height или viewBox); null, если определить не удалось. */
    fun dimensions(f: File): Pair<Int, Int>? = try {
        if (f.extension.equals("svg", true)) {
            val head = f.inputStream().use { i -> val b = ByteArray(8192); val n = i.read(b); String(b, 0, maxOf(n, 0), Charsets.UTF_8) }
            val tag = Regex("<svg\\b[^>]*>", RegexOption.IGNORE_CASE).find(head)?.value ?: ""
            fun attr(n: String) = Regex("\\s$n\\s*=\\s*[\"']\\s*([0-9.]+)\\s*(px)?\\s*[\"']", RegexOption.IGNORE_CASE).find(tag)?.groupValues?.get(1)?.toDoubleOrNull()
            val w = attr("width"); val h = attr("height")
            val vb = Regex("viewBox\\s*=\\s*[\"']\\s*[-0-9.]+[ ,]+[-0-9.]+[ ,]+([0-9.]+)[ ,]+([0-9.]+)", RegexOption.IGNORE_CASE).find(tag)
            when {
                w != null && h != null -> w.toInt() to h.toInt()
                vb != null -> vb.groupValues[1].toDouble().toInt() to vb.groupValues[2].toDouble().toInt()
                else -> null
            }
        } else {
            val o = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            BitmapFactory.decodeFile(f.absolutePath, o)
            if (o.outWidth > 0 && o.outHeight > 0) o.outWidth to o.outHeight else null
        }
    } catch (e: Exception) { null }

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
