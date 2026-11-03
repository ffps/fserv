package com.ffps.fserv

import android.annotation.SuppressLint
import android.content.ContentUris
import android.content.ContentValues
import android.content.Context
import android.content.pm.PackageManager
import android.graphics.BitmapFactory
import android.media.MediaScannerConnection
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import org.json.JSONArray
import java.io.ByteArrayOutputStream
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Фотографии с камеры лежат в общей папке DCIM/fServ/ГГГГ-ММ-ДД/ЧЧММСС.метка.jpg и видны в галерее.
 * Android 10 и новее: запись и чтение собственных снимков через MediaStore, разрешения не нужны.
 * Android 8–9: обычные файлы в общей DCIM, нужно разрешение на запись (кнопка в окне настроек).
 * Путь снимка (DCIM/fServ/…) — это значение переменной после `photo assign=имя`. Старейшие снимки удаляются,
 * когда их больше [MAX_PHOTOS].
 */
@SuppressLint("NewApi") // обращения к MediaStore (API 29+) защищены проверкой modern
object PhotoStore {
    private const val MAX_PHOTOS = 500
    const val DIR = "DCIM/fServ"
    private val DAY_RE = Regex("""\d{4}-\d{2}-\d{2}""")
    private val NAME_RE = Regex("""[0-9A-Za-z._-]{1,80}\.jpg""")
    const val PERMISSION = "android.permission.WRITE_EXTERNAL_STORAGE"

    private class Entry(val day: String, val name: String, val size: Long, val time: Long, val uri: Uri?, val file: File?)

    private lateinit var app: Context

    fun init(ctx: Context) {
        app = ctx.applicationContext
    }

    private val modern: Boolean get() = Build.VERSION.SDK_INT >= 29

    /** На Android 8–9 для записи нужно разрешение; на 10+ оно не требуется. */
    fun needsPermission(ctx: Context): Boolean =
        !modern && ctx.checkSelfPermission(PERMISSION) != PackageManager.PERMISSION_GRANTED

    private fun legacyRoot(): File = File(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DCIM), "fServ")

    private fun entries(): List<Entry> {
        val out = ArrayList<Entry>()
        if (modern) {
            val cols = arrayOf(MediaStore.Images.Media._ID, MediaStore.Images.Media.DISPLAY_NAME, MediaStore.Images.Media.RELATIVE_PATH,
                MediaStore.Images.Media.SIZE, MediaStore.Images.Media.DATE_ADDED)
            try {
                app.contentResolver.query(
                    MediaStore.Images.Media.EXTERNAL_CONTENT_URI, cols, "${MediaStore.Images.Media.RELATIVE_PATH} LIKE ?", arrayOf("$DIR/%"), null
                )?.use { c ->
                    while (c.moveToNext()) {
                        val name = c.getString(1) ?: continue
                        val day = (c.getString(2) ?: "").trim('/').substringAfterLast('/')
                        if (!DAY_RE.matches(day) || !NAME_RE.matches(name)) continue
                        out.add(Entry(day, name, c.getLong(3), c.getLong(4) * 1000, ContentUris.withAppendedId(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, c.getLong(0)), null))
                    }
                }
            } catch (e: Exception) {
                FLog.w("photo", "список фото не прочитан: ${e.javaClass.simpleName}: ${e.message}")
            }
        } else {
            val dirs = legacyRoot().listFiles { d -> d.isDirectory && DAY_RE.matches(d.name) } ?: emptyArray()
            for (d in dirs) for (f in d.listFiles { x -> x.isFile && NAME_RE.matches(x.name) } ?: emptyArray()) {
                out.add(Entry(d.name, f.name, f.length(), f.lastModified(), null, f))
            }
        }
        return out
    }

    private fun find(day: String, name: String): Entry? {
        if (!DAY_RE.matches(day) || !NAME_RE.matches(name)) return null
        return entries().firstOrNull { it.day == day && it.name == name }
    }

    fun relative(day: String, name: String) = "$DIR/$day/$name"

    /** Сохраняет JPEG; метка (id задачи) попадает в имя файла. Возвращает путь вида DCIM/fServ/ГГГГ-ММ-ДД/ЧЧММСС.метка.jpg. */
    @Synchronized
    fun save(jpg: ByteArray, label: String): String {
        val now = Date()
        val day = SimpleDateFormat("yyyy-MM-dd", Locale.US).format(now)
        val clean = label.replace(Regex("[^0-9A-Za-z_-]"), "_").take(40).ifEmpty { "photo" }
        val base = SimpleDateFormat("HHmmss", Locale.US).format(now) + "." + clean
        val existing = entries().filter { it.day == day }.map { it.name }.toSet()
        var name = "$base.jpg"
        var n = 2
        while (name in existing) name = "$base-${n++}.jpg"
        if (modern) {
            val values = ContentValues().apply {
                put(MediaStore.Images.Media.DISPLAY_NAME, name)
                put(MediaStore.Images.Media.MIME_TYPE, "image/jpeg")
                put(MediaStore.Images.Media.RELATIVE_PATH, "$DIR/$day")
                put(MediaStore.Images.Media.DATE_TAKEN, now.time)
                put(MediaStore.Images.Media.IS_PENDING, 1)
            }
            val res = app.contentResolver
            val uri = res.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, values) ?: throw IllegalStateException("не удалось создать файл в галерее")
            try {
                (res.openOutputStream(uri) ?: throw IllegalStateException("не удалось открыть файл для записи")).use { it.write(jpg) }
                res.update(uri, ContentValues().apply { put(MediaStore.Images.Media.IS_PENDING, 0) }, null, null)
            } catch (e: Exception) {
                try { res.delete(uri, null, null) } catch (x: Exception) {}
                throw if (e is IllegalStateException) e else IllegalStateException("не удалось записать фото: ${e.message}")
            }
            // система может изменить имя, если такое уже было: берём фактическое
            res.query(uri, arrayOf(MediaStore.Images.Media.DISPLAY_NAME), null, null, null)?.use { c -> if (c.moveToFirst()) c.getString(0)?.let { name = it } }
        } else {
            if (needsPermission(app)) throw IllegalStateException("нет разрешения на запись фото (кнопка Allow photo storage в окне настроек)")
            val dir = File(legacyRoot(), day)
            if (!dir.isDirectory && !dir.mkdirs()) throw IllegalStateException("не удалось создать папку ${dir.path}")
            val f = File(dir, name)
            f.writeBytes(jpg)
            MediaScannerConnection.scanFile(app, arrayOf(f.path), arrayOf("image/jpeg"), null)
        }
        trim()
        val rel = relative(day, name)
        FLog.i("photo", "фото сохранено: $rel (${jpg.size / 1024} КБ)")
        return rel
    }

    private fun trim() {
        val all = entries()
        if (all.size <= MAX_PHOTOS) return
        all.sortedBy { it.time }.take(all.size - MAX_PHOTOS).forEach { remove(it) }
    }

    private fun remove(e: Entry): Boolean {
        return try {
            if (e.uri != null) app.contentResolver.delete(e.uri, null, null) > 0
            else {
                val f = e.file!!
                val ok = f.delete()
                f.parentFile?.let { d -> if (d.list()?.isEmpty() == true) d.delete() }
                MediaScannerConnection.scanFile(app, arrayOf(f.path), null, null)
                ok
            }
        } catch (x: Exception) {
            FLog.w("photo", "не удалось удалить ${e.day}/${e.name}: ${x.javaClass.simpleName}: ${x.message}")
            false
        }
    }

    /** Удаляет все фото дня [day] (папка исчезает вместе с последним снимком); возвращает число удалённых. */
    fun deleteDay(day: String): Int {
        require(DAY_RE.matches(day)) { "day: ГГГГ-ММ-ДД" }
        val l = entries().filter { it.day == day }
        val n = l.count { remove(it) }
        if (!modern) File(legacyRoot(), day).let { if (it.isDirectory && it.list()?.isEmpty() != false) it.delete() }
        return n
    }

    fun clearAll() {
        entries().forEach { remove(it) }
    }

    /** Дни, в которые есть фотографии, новые сверху. */
    fun days(): JSONArray {
        val out = JSONArray()
        for ((d, l) in entries().groupBy { it.day }.toSortedMap(compareByDescending { it })) out.put(obj("day" to d, "count" to l.size))
        return out
    }

    fun list(day: String): JSONArray {
        require(DAY_RE.matches(day)) { "day: ГГГГ-ММ-ДД" }
        val a = JSONArray()
        entries().filter { it.day == day }.sortedByDescending { it.name }.forEach { a.put(obj("name" to it.name, "size" to it.size, "time" to Util.iso(it.time))) }
        return a
    }

    private fun bytes(e: Entry): ByteArray? = try {
        if (e.uri != null) app.contentResolver.openInputStream(e.uri)?.use { it.readBytes() } else e.file?.readBytes()
    } catch (x: Exception) {
        FLog.w("photo", "не удалось прочитать ${e.day}/${e.name}: ${x.javaClass.simpleName}: ${x.message}")
        null
    }

    fun read(day: String, name: String): ByteArray? = find(day, name)?.let { bytes(it) }

    /** Содержимое снимка по пути вида DCIM/fServ/ГГГГ-ММ-ДД/имя.jpg (для отправки в Telegram). */
    fun readPath(path: String): ByteArray? {
        val m = Regex("""^$DIR/(\d{4}-\d{2}-\d{2})/([0-9A-Za-z._-]{1,80}\.jpg)$""").matchEntire(path) ?: return null
        return read(m.groupValues[1], m.groupValues[2])
    }

    fun delete(day: String, name: String): Boolean = find(day, name)?.let { remove(it) } ?: false

    /** Уменьшенная копия для просмотра в списке. */
    fun thumb(data: ByteArray, side: Int = 320): ByteArray? {
        val o = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeByteArray(data, 0, data.size, o)
        var k = 1
        while (maxOf(o.outWidth, o.outHeight) / (k * 2) >= side) k *= 2
        val bmp = BitmapFactory.decodeByteArray(data, 0, data.size, BitmapFactory.Options().apply { inSampleSize = k }) ?: return null
        val out = ByteArrayOutputStream()
        bmp.compress(android.graphics.Bitmap.CompressFormat.JPEG, 80, out)
        bmp.recycle()
        return out.toByteArray()
    }

    /**
     * Делает снимок: включает камеру, если она не работает, ждёт кадр и сохраняет. Возвращает путь снимка.
     * Бросает IllegalStateException с понятным текстом. [cancel] прерывает ожидание.
     */
    fun shoot(ctx: Context, owner: String, label: String, cancel: () -> Boolean = { false }, size: String? = null): String {
        if (Call.active) throw IllegalStateException("камера занята звонком")
        if (!CameraEngine.hasPermission(ctx)) throw IllegalStateException("нет разрешения на камеру (кнопка Allow camera в окне настроек)")
        if (needsPermission(ctx)) throw IllegalStateException("нет разрешения на запись фото (кнопка Allow photo storage в окне настроек)")
        val wasRunning = CameraEngine.state == CameraEngine.State.RUNNING
        // Свой размер кадра: если камеру никто не держит, она открывается сразу с ним; если уже открыта для другого дела,
        // её не трогаем, а снимок только уменьшаем до нужного размера (увеличить кадр нельзя)
        val alone = size != null && CameraEngine.owners().isEmpty() && !wasRunning
        if (alone) CameraEngine.sizeOverride = size
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
            var scale = 1.0
            if (size != null && !alone) {
                val p = size.split("x")
                val fw = CameraEngine.width
                val fh = CameraEngine.height
                if (p.size == 2 && fw > 0 && fh > 0) scale = minOf(1.0, p[0].toInt().toDouble() / fw, p[1].toInt().toDouble() / fh)
            }
            val jpg = CameraEngine.photoJpeg(scale) ?: throw IllegalStateException("камера не дала кадр")
            return save(jpg, label)
        } finally {
            CameraEngine.stop(owner)
            if (alone) {
                CameraEngine.sizeOverride = null
                // пока снимали, камеру мог занять кто-то ещё: пусть откроется с обычным размером
                if (CameraEngine.owners().isNotEmpty()) CameraEngine.reopen()
            }
        }
    }
}
