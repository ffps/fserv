package com.ffps.fserv

import android.content.Context
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import org.json.JSONArray
import org.json.JSONObject
import org.vosk.Recognizer
import java.util.Calendar

/**
 * Запуск задач голосом: пока идут часы прослушивания ([Settings.listenHours]) и у включённых задач есть настройка voice,
 * постоянно слушает микрофон и узнаёт только их фразы (грамматика Vosk: фразы плюс «[unk]» для всего остального).
 * Распознавание идёт на устройстве, звук нигде не хранится и никуда не уходит. Микрофон слушатель отпускает, когда он нужен
 * шагу ask или chat либо звонку, а пока говорит синтезатор (и ещё [ECHO_MS] после), слух выключен: иначе устройство услышит само себя.
 */
object VoiceTrigger {
    private const val ECHO_MS = 800L // после речи синтезатора ещё столько не слушаем (эхо в комнате)
    private const val COOLDOWN_MS = 4000L // повторная команда сразу после запуска игнорируется
    private const val TARGET_PEAK = 20000
    private const val MAX_GAIN = 8f

    private lateinit var app: Context
    private var thread: Thread? = null

    /** Микрофон сейчас открыт слушателем (шаги задач ждут, пока он станет false). */
    @Volatile var holding = false
        private set

    private var listenAcc = 0L // сколько миллисекунд (по elapsedRealtime) слушатель уже слушал
    @Volatile private var listenSince = 0L // с какого момента слушает сейчас (0 — не слушает)

    /** Сколько всего миллисекунд слушатель слушал микрофон (для записи о нагрузке). */
    @Synchronized fun listenMs(): Long = listenAcc + (if (listenSince > 0) android.os.SystemClock.elapsedRealtime() - listenSince else 0L)

    @Synchronized private fun listening(on: Boolean) {
        val now = android.os.SystemClock.elapsedRealtime()
        if (on) { if (listenSince == 0L) listenSince = now }
        else if (listenSince > 0) { listenAcc += now - listenSince; listenSince = 0L }
    }

    @Volatile private var state = "off"
    @Volatile private var stateText = "выключено"

    private class Block(val code: String, val text: String)

    @Synchronized
    fun start(ctx: Context) {
        if (thread != null) return
        app = ctx.applicationContext
        thread = Thread({ loop() }, "voice-trigger").apply { isDaemon = true; start() }
    }

    /** Для вкладки «Звук»: что делает слушатель и какие фразы слушает. */
    fun status(): JSONObject {
        val ph = JSONArray()
        for (e in TaskEngine.voicePhrases()) ph.put(obj("task" to e.task, "phrase" to e.full))
        return obj("state" to state, "text" to stateText, "hours" to Settings.listenHours, "prefix" to Settings.listenPrefix, "phrases" to ph)
    }

    /** Ждёт, пока слушатель освободит микрофон (вызывается шагом, который собирается слушать сам). */
    fun waitReleased(maxMs: Long) {
        val end = System.currentTimeMillis() + maxMs
        while (holding && System.currentTimeMillis() < end) {
            try { Thread.sleep(20) } catch (e: InterruptedException) { return }
        }
    }

    private fun setState(code: String, text: String) {
        if (code == state && text == stateText) return
        val changed = code != state
        state = code; stateText = text
        if (!changed) return
        when (code) {
            "busy" -> FLog.d("voice-cmd", "пауза: $text")
            "problem" -> FLog.w("voice-cmd", "не слушаю: $text")
            else -> FLog.i("voice-cmd", text)
        }
    }

    /** Почему слушать нельзя сейчас, или null. */
    private fun blocker(): Block? {
        val hours = Settings.listenHours
        val w = try { VoiceCmd.parseWindow(hours) } catch (e: IllegalArgumentException) { null }
        if (w == null) return Block("off", "выключено: часы прослушивания не заданы")
        val c = Calendar.getInstance()
        if (!w.contains(c.get(Calendar.HOUR_OF_DAY) * 60 + c.get(Calendar.MINUTE))) return Block("hours", "не слушаю: вне часов прослушивания ($hours)")
        if (TaskEngine.voicePhrases().isEmpty()) return Block("nophrases", "не слушаю: нет включённых задач с настройкой voice")
        Stt.check(app)?.let { return Block("problem", it) }
        if (Call.active) return Block("busy", "идёт звонок")
        if (Stt.micUsers.get() > 0) return Block("busy", "микрофон занят шагом задачи")
        return null
    }

    private fun loop() {
        var rec: Recognizer? = null
        var recKey = ""
        var ar: AudioRecord? = null
        var lastErr = ""

        fun closeAudio() {
            ar?.let { try { it.stop() } catch (e: Exception) {}; try { it.release() } catch (e: Exception) {} }
            ar = null
            holding = false
        }
        fun closeRec() {
            rec?.let { try { it.close() } catch (e: Throwable) {} }
            rec = null; recKey = ""
            if (Stt.voiceHold) { Stt.voiceHold = false; Stt.touch() }
        }

        while (true) {
            try {
                val b = blocker()
                if (b != null) {
                    closeAudio()
                    // насовсем (выключено, вне часов, нет фраз или модели) отдаём и память; на время шага или звонка распознаватель остаётся
                    if (b.code != "busy") closeRec()
                    setState(b.code, b.text)
                    Thread.sleep(if (b.code == "busy") 300 else 2000)
                    continue
                }
                val phrases = TaskEngine.voicePhrases()
                val key = phrases.joinToString("|") { it.full }
                if (rec == null || key != recKey) {
                    rec?.let { try { it.close() } catch (e: Throwable) {} }
                    rec = null
                    setState("loading", "готовлю распознавание команд…")
                    val t0 = System.currentTimeMillis()
                    rec = Stt.newRecognizer(phrases.map { it.full })
                    recKey = key
                    Stt.voiceHold = true
                    FLog.d("voice-cmd", "распознаватель команд собран за ${System.currentTimeMillis() - t0} мс, фраз: ${phrases.size}")
                }
                val minBuf = AudioRecord.getMinBufferSize(Stt.RATE, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT)
                val a = AudioRecord(MediaRecorder.AudioSource.VOICE_RECOGNITION, Stt.RATE, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT, maxOf(minBuf, Stt.RATE))
                ar = a
                if (a.state != AudioRecord.STATE_INITIALIZED) throw IllegalStateException("микрофон не открылся (занят другим приложением?)")
                a.startRecording()
                holding = true
                if (Stt.micUsers.get() > 0) { closeAudio(); continue } // шаг успел занять микрофон, пока мы открывали
                lastErr = ""
                setState("listening", "слушаю команды (фраз: ${phrases.size})")
                rec!!.reset()
                listening(true)
                try { readLoop(a, rec!!, phrases, key) } finally { listening(false) }
                closeAudio()
            } catch (e: InterruptedException) {
                closeAudio(); closeRec()
                return
            } catch (e: Throwable) {
                closeAudio(); closeRec()
                val m = e.message ?: e.javaClass.simpleName
                if (m != lastErr) { lastErr = m; FLog.w("voice-cmd", "сбой прослушивания: $m") }
                setState("problem", m)
                try { Thread.sleep(10_000) } catch (x: InterruptedException) { return }
            }
        }
    }

    /** Читает микрофон и отдаёт Vosk, пока слушать можно и набор фраз не изменился. */
    private fun readLoop(a: AudioRecord, r: Recognizer, phrases: List<TaskEngine.VoiceEntry>, key: String) {
        val list = phrases.map { it.full }
        val buf = ShortArray(Stt.RATE / 10)
        var gain = 1f
        var muted = false
        var chunks = 0L
        var lastTrigger = 0L
        while (true) {
            if (Stt.micUsers.get() > 0 || Call.active) return
            if (++chunks % 10 == 0L) {
                if (blocker() != null) return
                if (TaskEngine.voicePhrases().joinToString("|") { it.full } != key) return
            }
            val n = a.read(buf, 0, buf.size)
            if (n < 0) throw IllegalStateException("ошибка чтения микрофона ($n)")
            if (n == 0) continue
            // пока говорит синтезатор (и недолго после) звук выбрасывается, а начатая фраза сбрасывается
            if (Voice.msSinceSpeech() < ECHO_MS) {
                if (!muted) { r.reset(); muted = true }
                continue
            }
            muted = false
            var peak = 0
            for (i in 0 until n) { val v = Math.abs(buf[i].toInt()); if (v > peak) peak = v }
            if (peak > 150) gain = gain * 0.9f + (TARGET_PEAK.toFloat() / peak).coerceIn(1f, MAX_GAIN) * 0.1f
            val g = if (peak > 0) minOf(gain, 32000f / peak) else gain
            if (g > 1.05f) for (i in 0 until n) buf[i] = (buf[i] * g).toInt().coerceIn(-32768, 32767).toShort()
            if (!r.acceptWaveForm(buf, n)) continue
            val heard = JSONObject(r.result).optString("text").replace("[unk]", " ").trim().replace(Regex("\\s+"), " ")
            if (heard.isEmpty()) continue
            val phrase = VoiceCmd.match(heard, list)
            FLog.d("voice-cmd", "услышано «$heard»" + if (phrase == null) ", это не команда" else " → «$phrase»")
            val now = System.currentTimeMillis()
            if (phrase != null && now - lastTrigger > COOLDOWN_MS) {
                lastTrigger = now
                val entry = phrases.first { it.full == phrase }
                TaskEngine.runByVoice(entry, heard)?.let { FLog.w("voice-cmd", "команда «$phrase» не выполнена: $it") }
                r.reset()
            }
        }
    }
}
