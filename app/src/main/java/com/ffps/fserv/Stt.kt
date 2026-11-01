package com.ffps.fserv

import android.content.Context
import android.content.pm.PackageManager
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import org.json.JSONArray
import org.json.JSONObject
import org.vosk.Model
import org.vosk.Recognizer
import java.io.File
import java.util.zip.ZipInputStream

/**
 * Распознавание коротких ответов офлайн (Vosk, малая русская модель). Модель (около 45 МБ) не входит в APK:
 * скачивается один раз кнопкой на вкладке «Звук» и хранится в файлах приложения. Слушает микрофон
 * (разрешение «Allow microphone» в окне настроек) и распознаёт только слова из словаря вида ответа ([Speech.grammar]).
 */
object Stt {
    const val MODEL_URL = "https://alphacephei.com/vosk/models/vosk-model-small-ru-0.22.zip"
    private const val RATE = 16000

    @Volatile var state = "none" // none | downloading | unpacking | ready | error
    @Volatile var done = 0L
    @Volatile var total = 0L
    @Volatile var lastError = ""

    private lateinit var root: File
    private var model: Model? = null

    fun init(ctx: Context) {
        root = File(ctx.filesDir, "vosk")
        File(root, "tmp").deleteRecursively()
        state = if (modelReady()) "ready" else "none"
    }

    private fun modelDir() = File(root, "model")

    private fun modelReady(): Boolean = File(modelDir(), "am").isDirectory && File(modelDir(), "conf").isDirectory

    fun hasPermission(ctx: Context): Boolean = ctx.checkSelfPermission("android.permission.RECORD_AUDIO") == PackageManager.PERMISSION_GRANTED

    fun status(ctx: Context): JSONObject = obj(
        "state" to state, "ready" to (state == "ready"), "permission" to hasPermission(ctx),
        "percent" to (if (total > 0) (done * 100 / total).toInt() else null), "doneMb" to done / 1048576, "totalMb" to (if (total > 0) total / 1048576 else null),
        "error" to lastError.ifEmpty { null }, "url" to MODEL_URL
    )

    /** Запускает скачивание в фоне; возвращает текст ошибки, если запуск невозможен. */
    @Synchronized
    fun downloadAsync(): String? {
        if (state == "downloading" || state == "unpacking") return "уже выполняется"
        if (state == "ready") return "модель уже скачана"
        state = "downloading"; done = 0; total = 0; lastError = ""
        Thread {
            try {
                download()
                state = "ready"
                FLog.i("stt", "модель распознавания скачана и распакована")
            } catch (e: Exception) {
                lastError = e.message ?: e.javaClass.simpleName
                state = "error"
                FLog.e("stt", "модель не скачана", e)
                File(root, "tmp").deleteRecursively()
            }
        }.start()
        return null
    }

    private fun download() {
        val tmp = File(root, "tmp").also { it.deleteRecursively(); it.mkdirs() }
        val zip = File(tmp, "model.zip")
        FLog.i("stt", "скачиваю модель $MODEL_URL")
        val r = Net.request("updates", "GET", MODEL_URL, saveTo = zip, timeoutMs = 60_000) { d, t -> done = d; total = t }
        if (r.code != 200) throw java.io.IOException("скачивание: HTTP ${r.code} (${r.via})")
        if (zip.length() < 10L * 1024 * 1024) throw java.io.IOException("файл модели слишком мал (${zip.length()} байт)")
        state = "unpacking"
        val out = File(tmp, "out").also { it.mkdirs() }
        val base = out.canonicalPath + File.separator
        ZipInputStream(zip.inputStream().buffered()).use { z ->
            while (true) {
                val e = z.nextEntry ?: break
                val f = File(out, e.name)
                if (!f.canonicalPath.startsWith(base)) throw java.io.IOException("недопустимое имя в архиве: ${e.name}")
                if (e.isDirectory) f.mkdirs() else {
                    f.parentFile?.mkdirs()
                    f.outputStream().use { o -> z.copyTo(o) }
                }
            }
        }
        // в архиве одна папка верхнего уровня (vosk-model-small-ru-0.22), её содержимое и есть модель
        val top = out.listFiles()?.singleOrNull { it.isDirectory } ?: out
        if (!File(top, "am").isDirectory) throw java.io.IOException("в архиве нет модели (нет папки am)")
        modelDir().deleteRecursively()
        if (!top.renameTo(modelDir())) throw java.io.IOException("не удалось сохранить модель")
        tmp.deleteRecursively()
    }

    @Synchronized
    fun delete() {
        release()
        modelDir().deleteRecursively()
        state = "none"; lastError = ""
    }

    @Synchronized
    private fun loadModel(): Model {
        model?.let { return it }
        if (!modelReady()) throw IllegalStateException("модель распознавания речи не скачана: вкладка «Звук», раздел «Распознавание речи»")
        val m = try { Model(modelDir().absolutePath) } catch (e: Throwable) { throw IllegalStateException("модель распознавания не открылась: ${e.message}") }
        model = m
        return m
    }

    /** Освобождает память модели (вызывается в конце задачи и после проверки). */
    @Synchronized
    fun release() {
        try { model?.close() } catch (e: Throwable) {}
        model = null
    }

    /**
     * Слушает микрофон до ответа или [maxMs]; возвращает распознанный текст (пусто, если ничего не сказано).
     * [kind] — number или yesno: распознаются только слова из словаря этого вида. Ошибки условий (нет разрешения, нет модели)
     * приходят как IllegalStateException с понятным текстом.
     */
    fun listen(ctx: Context, kind: String, maxMs: Long, cancel: () -> Boolean): String {
        if (!hasPermission(ctx)) throw IllegalStateException("нет разрешения на микрофон: на устройстве нажмите Allow microphone в окне настроек")
        val m = loadModel()
        val grammar = JSONArray(Speech.grammar(kind)).toString()
        val rec = Recognizer(m, RATE.toFloat(), grammar)
        val minBuf = AudioRecord.getMinBufferSize(RATE, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT)
        val ar = AudioRecord(MediaRecorder.AudioSource.VOICE_RECOGNITION, RATE, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT, maxOf(minBuf, RATE))
        try {
            if (ar.state != AudioRecord.STATE_INITIALIZED) throw IllegalStateException("микрофон не открылся (занят другим приложением?)")
            ar.startRecording()
            val buf = ShortArray(RATE / 4)
            val end = System.currentTimeMillis() + maxMs
            var text = ""
            while (System.currentTimeMillis() < end && !cancel()) {
                val n = ar.read(buf, 0, buf.size)
                if (n < 0) throw IllegalStateException("ошибка чтения микрофона ($n)")
                if (n == 0) continue
                if (rec.acceptWaveForm(buf, n)) {
                    val t = clean(JSONObject(rec.result).optString("text"))
                    if (t.isNotEmpty()) { text = t; break }
                }
            }
            if (text.isEmpty()) text = clean(JSONObject(rec.finalResult).optString("text"))
            return text
        } finally {
            try { ar.stop() } catch (e: Exception) {}
            ar.release()
            rec.close()
        }
    }

    private fun clean(t: String) = t.replace("[unk]", " ").trim().replace(Regex("\\s+"), " ")

    /** Проверка из веб-интерфейса: слушает [maxMs] и возвращает услышанное и разобранное значение. */
    fun test(ctx: Context, kind: String, maxMs: Long, cancel: () -> Boolean): JSONObject {
        try {
            val heard = listen(ctx, kind, maxMs, cancel)
            return obj("heard" to heard, "value" to Speech.parse(kind, heard))
        } finally {
            release()
        }
    }
}
