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
        for (e in TaskEngine.voicePhrases()) ph.put(obj("task" to e.task, "phrase" to (e.full + if (e.wild) " *" else "")))
        return obj("state" to state, "text" to stateText, "hours" to Settings.listenHours, "prefix" to Settings.listenPrefix, "level" to Settings.listenLevel, "tail" to Settings.listenTail, "ack" to Settings.listenAck, "miss" to Settings.listenMiss, "peak" to (Math.round(peakPct * 10) / 10.0), "phrases" to ph)
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
        if (!Settings.serviceEnabled) return Block("off", "выключено: служба остановлена")
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
                val key = keyOf(phrases)
                if (rec == null || key != recKey) {
                    rec?.let { try { it.close() } catch (e: Throwable) {} }
                    rec = null
                    setState("loading", "готовлю распознавание команд…")
                    val t0 = System.currentTimeMillis()
                    rec = Stt.newRecognizer(phrases.map { it.full }).also { it.setWords(true); it.setPartialWords(true) } // метки времени слов нужны, чтобы найти начало хвоста
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

    @Volatile private var peakPct = 0.0   // самый громкий кусок за последние ~3 с, % полной шкалы (для настройки порога)
    @Volatile private var totalChunks = 0L // всего прочитано кусков по 100 мс
    @Volatile private var fedChunks = 0L   // из них ушло в распознаватель (остальное отсеял порог громкости)

    /** (всего прочитано, отдано распознавателю) кусков по 100 мс с начала работы; для записи о нагрузке. */
    fun chunkStats(): Pair<Long, Long> = totalChunks to fedChunks

    private fun threshold(): Int = (Settings.listenLevel * 327.68).toInt()

    /** Ключ набора фраз: меняется, когда изменились сами фразы или признак хвоста. */
    private fun keyOf(p: List<TaskEngine.VoiceEntry>): String = p.joinToString("|") { it.full + if (it.wild) "*" else "" }

    private const val CH = 0.1              // секунд в одном куске звука
    private const val END_SILENCE = 10      // столько кусков тишины (1 с) после речи — хвост закончен
    private const val NO_SPEECH_SEC = 3.0   // если после команды столько секунд молчат, хвоста нет
    private const val MIN_TAIL_WORDS = 2    // короче хвост считается случайным шумом
    private const val CUT_BACK = 4          // второй проход начинается за столько кусков (0,4 с) до конца команды
    private const val TAIL_SILENCE_FEED = 3 // тишины в конце хвоста второму проходу достаточно этого числа кусков (0,3 с): конец реплики мы ждём сами
    private const val TAIL_BEFORE_SEC = 0.25 // слово, начавшееся не раньше чем за столько до конца команды, относится к хвосту

    internal fun norm(w: String) = w.lowercase().replace('ё', 'е')

    /**
     * Второй проход: звук хвоста через свежий распознаватель свободной речи в отдельном потоке (время слов идёт от первого поданного куска).
     * Кормится кусками по мере дослушивания, поэтому распознавание почти готово к концу реплики; [finish] ждёт остаток.
     */
    private class TailDecoder {
        private val q = java.util.concurrent.LinkedBlockingQueue<ShortArray>()
        private val stop = ShortArray(0)
        val words = ArrayList<Triple<String, Double, Double>>()
        @Volatile var error: Throwable? = null
        @Volatile var createMs = 0L
        @Volatile var audioSamples = 0L   // сколько звука разобрано
        @Volatile var busyNs = 0L         // сколько времени на это ушло (без ожидания звука)
        private val th = Thread({ work() }, "voice-tail").apply { isDaemon = true; start() }

        fun feed(c: ShortArray) { q.put(c.copyOf()) }

        /** Бросает недослушанное (сбой или отмена). */
        fun abort() { q.clear(); q.put(stop) }

        /** Ждёт конца разбора не дольше [maxMs]; true, если уложился. */
        fun finish(maxMs: Long): Boolean { q.put(stop); th.join(maxMs); return !th.isAlive }

        private fun take(js: String) {
            val arr = JSONObject(js).optJSONArray("result") ?: return
            synchronized(words) { for (i in 0 until arr.length()) { val w = arr.getJSONObject(i); words.add(Triple(VoiceTrigger.norm(w.optString("word")), w.optDouble("start"), w.optDouble("end"))) } }
        }

        private fun work() {
            var fr: Recognizer? = null
            try {
                val t0 = System.currentTimeMillis()
                val r = Stt.newFreeRecognizer()
                fr = r
                createMs = System.currentTimeMillis() - t0
                while (true) {
                    val c = q.take()
                    if (c.isEmpty()) break
                    val t = System.nanoTime()
                    if (r.acceptWaveForm(c, c.size)) take(r.result)
                    busyNs += System.nanoTime() - t
                    audioSamples += c.size
                }
                val t = System.nanoTime()
                take(r.finalResult)
                busyNs += System.nanoTime() - t
            } catch (e: Throwable) {
                error = e
            } finally {
                try { fr?.close() } catch (e: Throwable) {}
            }
        }
    }

    /** Начатый заранее второй проход: распознаватель хвоста для [entry], звук подан с [cutSec] секунд от начала звука реплики. */
    private class Spec(val entry: TaskEngine.VoiceEntry, val dec: TailDecoder, val cutSec: Double)

    private fun wordsOf(arr: JSONArray?): List<Triple<String, Double, Double>> {
        if (arr == null) return emptyList()
        return (0 until arr.length()).map { val w = arr.getJSONObject(it); Triple(norm(w.optString("word")), w.optDouble("start"), w.optDouble("end")) }
    }

    /** Конец команды [e] среди слов (без «[unk]») в секундах потока распознавателя; -1.0, если её там нет. */
    private fun headEndOf(words: List<Triple<String, Double, Double>>, e: TaskEngine.VoiceEntry): Double {
        val real = words.filter { it.first != "[unk]" }
        val hw = e.full.split(' ').map { norm(it) }
        for (i in 0..real.size - hw.size) if (hw.indices.all { real[i + it].first == hw[it] }) return real[i + hw.size - 1].third
        return -1.0
    }

    /** Звук отклика (настройка «Звук отклика»): дослушали хвост команды, идёт разбор. Играет в стороне, ничего не ждёт. */
    private fun playAck() {
        val name = Settings.listenAck
        if (name.isEmpty() || !Sounds.exists(name)) return
        Thread({ Audio.play(app, name, null, 0L, 0L, null, false) { false }?.let { FLog.w("voice-cmd", "звук отклика не сыграл: $it") } }, "voice-ack").apply { isDaemon = true; start() }
    }

    /** Говорит [text] (фраза «не расслышала»): человек сказал что-то после команды, но разобрать это не удалось. Пусто — молчит. Синтезатор занимает слушателя, пока говорит. */
    private fun say(text: String) {
        if (text.isEmpty()) return
        Voice.speak(text, null, Settings.voice()) { false }?.let { FLog.w("voice-cmd", "фраза «не расслышала» не сказана: $it") }
    }

    /** Читает микрофон и отдаёт Vosk, пока слушать можно и набор фраз не изменился. Тише порога громкости звук не отдаётся. */
    private fun readLoop(a: AudioRecord, r: Recognizer, phrases: List<TaskEngine.VoiceEntry>, key: String) {
        val list = phrases.map { it.full }
        val hasWild = phrases.any { it.wild }
        val buf = ShortArray(Stt.RATE / 10)
        val gate = LevelGate()
        val utt = Utterance()
        var thr = threshold()
        var gain = 1f
        var muted = false
        var chunks = 0L
        var winMax = 0
        var lastTrigger = 0L
        var spec: Spec? = null
        val wildList = phrases.filter { it.wild }.map { it.full }
        fun dropSpec() { spec?.dec?.abort(); spec = null }

        /** Очередной кусок с усилением и его сырой пик; null — пока ничего нет или говорит синтезатор. */
        fun readChunk(): Pair<ShortArray, Int>? {
            val n = a.read(buf, 0, buf.size)
            if (n < 0) throw IllegalStateException("ошибка чтения микрофона ($n)")
            if (n == 0) return null
            // пока говорит синтезатор (и недолго после) звук выбрасывается, а начатая фраза сбрасывается
            if (Voice.msSinceSpeech() < ECHO_MS) {
                if (!muted) { r.reset(); gate.reset(); utt.clear(); dropSpec(); muted = true }
                return null
            }
            muted = false
            var peak = 0
            for (i in 0 until n) { val v = Math.abs(buf[i].toInt()); if (v > peak) peak = v }
            if (peak > winMax) winMax = peak
            if (chunks % 30 == 0L) { peakPct = winMax * 100.0 / 32768; winMax = 0 }
            if (peak > 150) gain = gain * 0.9f + (TARGET_PEAK.toFloat() / peak).coerceIn(1f, MAX_GAIN) * 0.1f
            val g = if (peak > 0) minOf(gain, 32000f / peak) else gain
            if (g > 1.05f) for (i in 0 until n) buf[i] = (buf[i] * g).toInt().coerceIn(-32768, 32767).toShort()
            return (if (n == buf.size) buf else buf.copyOf(n)) to peak
        }

        /**
         * Как только по промежуточному результату слышно начало команды со знаком «*», запускает второй проход заранее: он идёт параллельно
         * с тем, как человек договаривает, и после конца реплики остаётся доделать немного.
         */
        fun startEarly() {
            if (spec != null || wildList.isEmpty() || utt.size < 6) return
            val pj = JSONObject(r.partialResult)
            val text = pj.optString("partial").replace("[unk]", " ").trim().replace(Regex("\\s+"), " ")
            if (text.isEmpty()) return
            val full = VoiceCmd.match(text, wildList) ?: return
            val e = phrases.first { it.full == full && it.wild }
            val ws = wordsOf(pj.optJSONArray("partial_result"))
            val headEnd = headEndOf(ws, e)
            if (headEnd < 0 || ws.isEmpty() || !utt.trimToSpeech(2)) return
            val headEndSec = utt.firstLoud() * CH + (headEnd - ws[0].second)
            val cutIdx = maxOf(0, Math.ceil(headEndSec / CH).toInt() - CUT_BACK)
            val dec = TailDecoder()
            val all = utt.all()
            for (i in cutIdx until all.size) dec.feed(all[i])
            spec = Spec(e, dec, cutIdx * CH)
            FLog.d("voice-cmd", "услышано начало «$full *»: второй проход начат заранее")
        }

        /** Дослушивает реплику после начала команды со знаком «*» и возвращает хвост; null — хвоста нет или он слишком короткий. */
        fun captureTail(j: JSONObject, e: TaskEngine.VoiceEntry): String? {
            val tailSec = Settings.listenTail
            val words = wordsOf(j.optJSONArray("result"))
            val hw = e.full.split(' ').map { norm(it) }
            val headEnd = headEndOf(words, e)
            if (headEnd < 0 || words.isEmpty()) { dropSpec(); FLog.d("voice-cmd", "хвост «${e.full} *»: нет меток времени слов, не запускаю"); return null }
            if (!utt.trimToSpeech(2)) { dropSpec(); FLog.d("voice-cmd", "хвост «${e.full} *»: в звуке нет речи, не запускаю"); return null }
            val headEndSec = utt.firstLoud() * CH + (headEnd - words[0].second) // конец команды в секундах от начала звука
            val headEndChunk = Math.ceil(headEndSec / CH).toInt()
            var early = spec
            if (early != null && (early.entry !== e || early.cutSec > headEndSec - TAIL_BEFORE_SEC)) { dropSpec(); early = null } // заранее начатый не подходит
            spec = null // дальше декодером владеет эта функция
            val dec: TailDecoder
            val cutSec: Double
            if (early != null) {
                dec = early.dec; cutSec = early.cutSec
            } else {
                // второй проход идёт параллельно с дослушиванием, начиная чуть раньше конца команды
                val cutIdx = maxOf(0, headEndChunk - CUT_BACK)
                cutSec = cutIdx * CH
                dec = TailDecoder()
                val all = utt.all()
                for (i in cutIdx until all.size) dec.feed(all[i])
            }
            var ok = false
            var spoke = false
            val pending = ArrayList<ShortArray>()
            try {
                val deadline = System.currentTimeMillis() + ((tailSec + NO_SPEECH_SEC + 3) * 1000).toLong()
                while (true) {
                    if (Stt.micUsers.get() > 0 || Call.active || muted) return null
                    val after = (utt.size - headEndChunk) * CH
                    spoke = utt.loudAfter(headEndChunk + 1)
                    if (spoke && utt.trailingSilence() >= END_SILENCE) break // сказал и замолчал
                    if (!spoke && after >= NO_SPEECH_SEC) break              // после команды молчат
                    if (after >= tailSec + 0.5) break                        // предел длины хвоста
                    if (System.currentTimeMillis() > deadline) break
                    val c = readChunk() ?: continue
                    utt.add(c.first)
                    // длинную тишину в конце реплики второму проходу не отдаём (она нужна только нам, чтобы понять, что человек договорил);
                    // если речь продолжилась, отложенная тишина уходит в разбор перед новым звуком, и время слов не сбивается
                    val ts = utt.trailingSilence()
                    if (ts <= TAIL_SILENCE_FEED) { pending.forEach { dec.feed(it) }; pending.clear(); dec.feed(c.first) } else pending.add(c.first)
                }
                ok = true
            } finally {
                if (!ok) dec.abort()
            }
            if (spoke) playAck()
            val t0 = System.currentTimeMillis()
            val done = dec.finish(20_000)
            val waitMs = System.currentTimeMillis() - t0
            val shift = headEndSec - cutSec // конец команды в секундах от начала второго прохода
            val got = synchronized(dec.words) { dec.words.toList() }
            val tail = got.filter { it.second >= shift - TAIL_BEFORE_SEC && it.second <= shift + tailSec }.map { it.first }.toMutableList()
            if (tail.isNotEmpty() && tail[0] == hw.last()) tail.removeAt(0) // обрывок последнего слова самой команды
            val speed = if (dec.busyNs > 0) "%.1f".format(dec.audioSamples.toDouble() / Stt.RATE / (dec.busyNs / 1e9)) else "?"
            FLog.d("voice-cmd", "хвост «${tail.joinToString(" ")}»: звука ${"%.1f".format(utt.size * CH)} с, после конца речи ждали $waitMs мс" +
                (if (early != null) " (разбор начат заранее)" else "") + ", скорость разбора ×$speed реального времени, сборка распознавателя ${dec.createMs} мс, предел $tailSec с" +
                (if (!done) ", не уложились во время" else "") + (dec.error?.let { ", сбой: ${it.message}" } ?: ""))
            if (tail.size < MIN_TAIL_WORDS) {
                FLog.d("voice-cmd", "после «${e.full}» меньше $MIN_TAIL_WORDS слов, не запускаю; слова второго прохода (с, от начала разбора; конец команды на ${"%.2f".format(shift)} с): " +
                    (if (got.isEmpty()) "нет" else got.joinToString(", ") { "«${it.first}» ${"%.2f".format(it.second)}" }))
                if (spoke) say(Settings.listenMiss)
                return null
            }
            return tail.joinToString(" ")
        }

        /** Разбирает завершённую реплику (JSON результата первого распознавателя). */
        fun handle(json: String) {
            val j = JSONObject(json)
            val heard = j.optString("text").replace("[unk]", " ").trim().replace(Regex("\\s+"), " ")
            if (heard.isEmpty()) return
            val phrase = VoiceCmd.match(heard, list)
            FLog.d("voice-cmd", "услышано «$heard»" + if (phrase == null) ", это не команда" else " → «$phrase»")
            if (phrase == null) return
            if (System.currentTimeMillis() - lastTrigger <= COOLDOWN_MS) return
            val entry = phrases.first { it.full == phrase }
            val tail = if (entry.wild) (captureTail(j, entry) ?: return) else ""
            lastTrigger = System.currentTimeMillis()
            TaskEngine.runByVoice(entry, heard, tail)?.let { FLog.w("voice-cmd", "команда «$phrase» не выполнена: $it") }
        }

        fun resetAll() { r.reset(); gate.reset(); utt.clear(); dropSpec() }

        while (true) {
            if (Stt.micUsers.get() > 0 || Call.active) return
            if (++chunks % 10 == 0L) {
                if (blocker() != null) return
                if (keyOf(TaskEngine.voicePhrases()) != key) return
                thr = threshold()
            }
            val c = readChunk() ?: continue
            totalChunks++
            val feed = gate.push(c.first, c.second, thr)
            if (feed.isEmpty()) continue
            fedChunks += feed.size
            for (f in feed) {
                if (hasWild) { utt.add(f); spec?.dec?.feed(f) }
                if (r.acceptWaveForm(f, f.size)) { handle(r.result); resetAll() }
                else if (hasWild) startEarly()
            }
            // слишком долгая реплика без паузы: завершаем принудительно, чтобы не копить звук без конца
            if (hasWild && utt.size >= (Settings.listenTail + 5) * 10) { handle(r.finalResult); resetAll() }
            if (gate.closedNow) resetAll() // тишина после фразы без завершения: брошенное не копим
        }
    }
}
