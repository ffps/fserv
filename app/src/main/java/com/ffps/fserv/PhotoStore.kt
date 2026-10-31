package com.ffps.fserv

import android.content.Context
import android.graphics.BitmapFactory
import android.os.Environment
import org.json.JSONArray
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Фотографии с камеры: DCIM/fServ/ГГГГ-ММ-ДД/ЧЧММСС.метка.jpg в папке приложения на накопителе
 * (Android/data/<пакет>/files). Туда можно писать без разрешений на любой версии Android.
 * Старейшие фотографии удаляются, когда их больше [MAX_PHOTOS].
 */
object PhotoStore {
    private const val MAX_PHOTOS = 500
    private val DAY_RE = Regex("""\d{4}-\d{2}-\d{2}""")
    private val NAME_RE = Regex("""[0-9A-Za-z._-]{1,80}\.jpg""")

    private lateinit var root: File

    fun init(ctx: Context) {
        val base = ctx.getExternalFilesDir(Environment.DIRECTORY_DCIM) ?: File(ctx.filesDir, "DCIM")
        root = File(base, "fServ").also { it.mkdirs() }
    }

    fun relative(f: File): String = "DCIM/fServ/" + f.parentFile?.name + "/" + f.name

    /** Сохраняет JPEG; метка (id задачи) попадает в имя файла. */
    @Synchronized
    fun save(jpg: ByteArray, label: String): File {
        val now = Date()
        val dir = File(root, SimpleDateFormat("yyyy-MM-dd", Locale.US).format(now)).also { it.mkdirs() }
        val clean = label.replace(Regex("[^0-9A-Za-z_-]"), "_").take(40).ifEmpty { "photo" }
        val base = SimpleDateFormat("HHmmss", Locale.US).format(now) + "." + clean
        var f = File(dir, "$base.jpg")
        var n = 2
        while (f.exists()) f = File(dir, "$base-${n++}.jpg")
        f.writeBytes(jpg)
        trim()
        FLog.i("photo", "фото сохранено: ${relative(f)} (${jpg.size / 1024} КБ)")
        return f
    }

    private fun allFiles(): List<File> =
        (root.listFiles { d -> d.isDirectory && DAY_RE.matches(d.name) } ?: emptyArray())
            .flatMap { d -> (d.listFiles { x -> x.isFile && NAME_RE.matches(x.name) } ?: emptyArray()).toList() }

    private fun trim() {
        val files = allFiles()
        if (files.size <= MAX_PHOTOS) return
        files.sortedBy { it.lastModified() }.take(files.size - MAX_PHOTOS).forEach { f ->
            f.delete()
            f.parentFile?.let { d -> if (d.list()?.isEmpty() == true) d.delete() }
        }
    }

    fun clearAll() {
        root.deleteRecursively()
        root.mkdirs()
    }

    /** Дни, в которые есть фотографии, новые сверху. */
    fun days(): JSONArray {
        val dirs = (root.listFiles { d -> d.isDirectory && DAY_RE.matches(d.name) } ?: emptyArray()).sortedByDescending { it.name }
        val out = JSONArray()
        for (d in dirs) {
            val n = d.listFiles { x -> x.isFile && NAME_RE.matches(x.name) }?.size ?: 0
            if (n > 0) out.put(obj("day" to d.name, "count" to n))
        }
        return out
    }

    fun list(day: String): JSONArray {
        require(DAY_RE.matches(day)) { "day: ГГГГ-ММ-ДД" }
        val files = File(root, day).listFiles { x -> x.isFile && NAME_RE.matches(x.name) }?.sortedByDescending { it.name } ?: emptyList()
        return JSONArray().also { a -> files.forEach { a.put(obj("name" to it.name, "size" to it.length(), "time" to Util.iso(it.lastModified()))) } }
    }

    fun file(day: String, name: String): File? {
        if (!DAY_RE.matches(day) || !NAME_RE.matches(name)) return null
        return File(File(root, day), name).takeIf { it.isFile }
    }

    fun delete(day: String, name: String): Boolean {
        val f = file(day, name) ?: return false
        val ok = f.delete()
        f.parentFile?.let { d -> if (d.list()?.isEmpty() == true) d.delete() }
        return ok
    }

    /** Уменьшенная копия для просмотра в списке. */
    fun thumb(f: File, side: Int = 320): ByteArray? {
        val o = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(f.absolutePath, o)
        var k = 1
        while (maxOf(o.outWidth, o.outHeight) / (k * 2) >= side) k *= 2
        val bmp = BitmapFactory.decodeFile(f.absolutePath, BitmapFactory.Options().apply { inSampleSize = k }) ?: return null
        val out = ByteArrayOutputStream()
        bmp.compress(android.graphics.Bitmap.CompressFormat.JPEG, 80, out)
        bmp.recycle()
        return out.toByteArray()
    }

    /**
     * Делает снимок: включает камеру, если она не работает, ждёт кадр и сохраняет. Бросает IllegalStateException
     * с понятным текстом. [cancel] прерывает ожидание.
     */
    fun shoot(ctx: Context, owner: String, label: String, cancel: () -> Boolean = { false }): File {
        if (!CameraEngine.hasPermission(ctx)) throw IllegalStateException("нет разрешения на камеру (кнопка Allow camera в окне настроек)")
        val wasRunning = CameraEngine.state == CameraEngine.State.RUNNING
        CameraEngine.start(owner)
        try {
            val end = System.currentTimeMillis() + 15_000
            while (CameraEngine.state != CameraEngine.State.RUNNING) {
                if (cancel()) throw IllegalStateException("остановлено")
                if (System.currentTimeMillis() > end) throw IllegalStateException("камера не заработала: ${CameraEngine.lastError.ifEmpty { "нет кадров" }}")
                Thread.sleep(100)
            }
            if (!wasRunning) {
                Thread.sleep(1000) // дать автоэкспозиции устояться
                CameraEngine.awaitProcessedFrame(3000)
            }
            val jpg = CameraEngine.photoJpeg() ?: throw IllegalStateException("камера не дала кадр")
            return save(jpg, label)
        } finally {
            CameraEngine.stop(owner)
        }
    }
}
