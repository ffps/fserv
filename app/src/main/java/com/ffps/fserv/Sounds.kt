package com.ffps.fserv

import android.content.Context
import android.media.MediaMetadataRetriever
import org.json.JSONArray
import java.io.File
import java.io.InputStream

/** Звуковые файлы (mp3 и другие), загруженные через веб-интерфейс. Лежат во внутренней папке приложения. */
object Sounds {
    const val MAX_FILE = 30L * 1024 * 1024
    const val MAX_TOTAL = 200L * 1024 * 1024
    private val NAME = Regex("""[\p{L}0-9 _.()\-]{1,60}\.(mp3|wav|ogg|m4a|aac|flac)""", RegexOption.IGNORE_CASE)

    private lateinit var dir: File
    private val durations = HashMap<String, Pair<Long, Long>>() // имя -> (размер файла, длительность мс)

    fun init(ctx: Context) {
        dir = File(ctx.filesDir, "sounds").also { it.mkdirs() }
    }

    fun checkName(name: String?): String {
        val n = name?.trim().orEmpty()
        require(NAME.matches(n) && !n.contains("..")) { "имя файла: буквы, цифры, пробел, _ . ( ) -, расширение mp3, wav, ogg, m4a, aac или flac, до 60 знаков" }
        return n
    }

    fun exists(name: String): Boolean = NAME.matches(name) && File(dir, name).isFile

    fun file(name: String): File = File(dir, checkName(name))

    @Synchronized
    private fun duration(f: File): Long {
        val cached = durations[f.name]
        if (cached != null && cached.first == f.length()) return cached.second
        var d = -1L
        val r = MediaMetadataRetriever()
        try {
            r.setDataSource(f.absolutePath)
            d = r.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)?.toLongOrNull() ?: -1L
        } catch (e: Exception) {
            FLog.w("sounds", "длительность ${f.name} не определена: ${e.message}")
        } finally {
            try { r.release() } catch (e: Exception) {}
        }
        durations[f.name] = f.length() to d
        return d
    }

    fun list(): JSONArray {
        val files = dir.listFiles { f -> f.isFile && NAME.matches(f.name) }?.sortedBy { it.name.lowercase() } ?: emptyList()
        return arr(files.map { obj("name" to it.name, "size" to it.length(), "durationMs" to duration(it).takeIf { d -> d >= 0 }) })
    }

    fun totalSize(): Long = dir.listFiles()?.sumOf { it.length() } ?: 0L

    /** Сохраняет файл из потока (не более len байт). Бросает IllegalArgumentException с понятным текстом. */
    fun save(name: String, input: InputStream, len: Long) {
        val n = checkName(name)
        require(len in 1..MAX_FILE) { "файл должен быть не больше ${MAX_FILE / 1048576} МБ" }
        require(totalSize() - (File(dir, n).takeIf { it.exists() }?.length() ?: 0L) + len <= MAX_TOTAL) { "все звуки вместе не больше ${MAX_TOTAL / 1048576} МБ" }
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
            durations.remove(n)
        } finally {
            tmp.delete()
        }
    }

    fun delete(name: String): Boolean = file(name).delete()

    @Synchronized
    fun clearAll() {
        dir.listFiles()?.forEach { it.delete() }
        durations.clear()
    }

    fun names(): List<String> = dir.listFiles()?.filter { it.isFile && NAME.matches(it.name) }?.map { it.name }?.sorted() ?: emptyList()
}
