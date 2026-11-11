package com.ffps.fserv

import android.content.Context
import android.media.AudioManager
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import org.json.JSONObject
import java.io.File
import java.util.Locale
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/**
 * Голос: системный синтезатор речи Android (TextToSpeech). Какой именно движок и голос звучат, определяет система
 * (настройка «Синтез речи» устройства); язык, голос, скорость и тон берутся из настроек приложения (вкладка «Звук»).
 */
object Voice {
    private lateinit var app: Context
    @Volatile private var tts: TextToSpeech? = null
    @Volatile private var initStatus = -2 // -2 не запускали, -1 ошибка, 0 готово
    private val waiting = ConcurrentHashMap<String, CountDownLatch>()
    private val lock = Any()
    private var counter = 0
    private val startedAt = ConcurrentHashMap<String, Long>() // номер фразы → когда движок начал её произносить (для замера задержки)
    @Volatile private var speaking = 0 // сколько фраз сейчас произносится
    @Volatile private var lastUse = 0L // когда синтезатор в последний раз работал (речь или прогрев)
    private val warming = java.util.concurrent.atomic.AtomicBoolean(false)

    private const val START_TIMEOUT_MS = 20_000L // столько ждём начала звука после команды говорить

    fun init(ctx: Context) {
        app = ctx.applicationContext
    }

    private const val INIT_WAIT_SEC = 15L // столько ждёт готовности один вызов
    private const val INIT_ABANDON_MS = 90_000L // дольше этого запуск считается зависшим: синтезатор пересоздаём

    @Volatile private var initLatch = CountDownLatch(0)
    @Volatile private var initStartedAt = 0L
    @Volatile private var initDoneAfterMs = -1L
    private val gaveUp = java.util.concurrent.ConcurrentHashMap<String, Long>() // фразы, которых не дождались: id -> время вызова
    @Volatile private var listenerOwner: TextToSpeech? = null

    private val progress = object : UtteranceProgressListener() {
        override fun onStart(id: String?) {
            if (id == null) return
            val gave = gaveUp.remove(id)
            if (gave != null) { FLog.d("voice", "речь началась с опозданием на ${(System.currentTimeMillis() - gave) / 1000} с (мы уже сдались)"); return }
            startedAt[id] = System.currentTimeMillis()
        }
        override fun onDone(id: String?) { id?.let { waiting.remove(it)?.countDown() } }
        override fun onStop(id: String?, interrupted: Boolean) { id?.let { waiting.remove(it)?.countDown() } }
        @Deprecated("Deprecated in Java")
        override fun onError(id: String?) { id?.let { waiting.remove(it)?.countDown() } }
        override fun onError(id: String?, code: Int) { id?.let { waiting.remove(it)?.countDown() } }
    }

    /** Прослушиватель событий речи должен стоять на любом готовом синтезаторе, иначе не приходят «началась» и «закончилась». */
    private fun attachListener(t: TextToSpeech) {
        if (listenerOwner === t) return
        t.setOnUtteranceProgressListener(progress)
        listenerOwner = t
    }

    /** Сбрасывает синтезатор: при следующей речи создаётся заново. */
    private fun reset(why: String) {
        synchronized(lock) {
            FLog.d("voice", "синтезатор сброшен: $why")
            try { tts?.shutdown() } catch (e: Exception) {}
            tts = null; initStatus = -2; listenerOwner = null
        }
    }

    /** Создаёт синтезатор (в главном потоке) и ждёт готовности. Возвращает текст ошибки или null. */
    private fun ensure(): String? {
        synchronized(lock) {
            val cur = tts
            if (cur != null && initStatus == 0) { attachListener(cur); return null }
            if (cur != null && initStatus == -2) {
                // запуск идёт (движок отвечает медленно, например сразу после обновления приложения): дожидаемся, а не создаём второй
                val age = System.currentTimeMillis() - initStartedAt
                if (age < INIT_ABANDON_MS) {
                    initLatch.await(INIT_WAIT_SEC, TimeUnit.SECONDS)
                    if (initStatus == 0) { tts?.let { attachListener(it) }; return null }
                    if (initStatus == -2) return "синтезатор речи ещё запускается (${(System.currentTimeMillis() - initStartedAt) / 1000} с)"
                } else {
                    FLog.d("voice", "синтезатор не запустился за ${age / 1000} с, создаю заново")
                    try { cur.shutdown() } catch (e: Exception) {}
                    tts = null
                }
            }
            if (tts == null || initStatus == -1) {
                try { tts?.shutdown() } catch (e: Exception) {}
                tts = null; listenerOwner = null
                initStatus = -2
                initDoneAfterMs = -1L
                val latch = CountDownLatch(1)
                initLatch = latch
                initStartedAt = System.currentTimeMillis()
                val started = initStartedAt
                val holder = arrayOfNulls<TextToSpeech>(1)
                Handler(Looper.getMainLooper()).post {
                    try {
                        holder[0] = TextToSpeech(app) { status ->
                            val ms = System.currentTimeMillis() - started
                            if (status == TextToSpeech.SUCCESS) {
                                // слушатель ставим сразу в обратном вызове: он мог прийти уже после того, как вызывающий перестал ждать
                                (holder[0] ?: tts)?.let { attachListener(it) }
                                initDoneAfterMs = ms
                                initStatus = 0
                                FLog.d("voice", "синтезатор готов за $ms мс" + if (ms > INIT_WAIT_SEC * 1000) " (инициализация закончилась после ожидания, слушатель установлен)" else "")
                            } else {
                                initStatus = -1
                                FLog.d("voice", "синтезатор: ошибка запуска, код $status, через $ms мс")
                            }
                            latch.countDown()
                        }
                        tts = holder[0]
                    } catch (e: Exception) {
                        initStatus = -1
                        FLog.d("voice", "синтезатор: не удалось создать: ${e.javaClass.simpleName}: ${e.message}")
                        latch.countDown()
                    }
                }
                if (!latch.await(INIT_WAIT_SEC, TimeUnit.SECONDS)) return "синтезатор речи не запустился за ${INIT_WAIT_SEC} с (запуск продолжается, повторите позже)"
                if (initStatus != 0) {
                    tts = null
                    return "синтезатор речи недоступен (на устройстве не установлен движок TextToSpeech)"
                }
            }
            return null
        }
    }

    private fun apply(t: TextToSpeech, cfg: JSONObject?): String? {
        val lang = cfg?.optString("lang").orEmpty().ifEmpty { "ru-RU" }
        val r = t.setLanguage(Locale.forLanguageTag(lang.replace('_', '-')))
        if (r == TextToSpeech.LANG_MISSING_DATA || r == TextToSpeech.LANG_NOT_SUPPORTED) {
            return "для языка $lang нет голоса: установите его в настройках устройства (Язык и ввод, Синтез речи) или выберите другой язык в voice.lang"
        }
        val name = cfg?.optString("voice").orEmpty()
        if (name.isNotEmpty()) {
            val v = t.voices?.firstOrNull { it.name == name } ?: return "голос «$name» не найден (список на вкладке «Звук»)"
            t.voice = v
        }
        t.setSpeechRate((cfg?.optInt("rate", 100) ?: 100) / 100f)
        t.setPitch((cfg?.optInt("pitch", 100) ?: 100) / 100f)
        return null
    }

    /** Синтезатор сейчас говорит. */
    fun isSpeaking(): Boolean = speaking > 0

    /** Сколько миллисекунд прошло с конца последней речи (0, пока говорит): слушатель команд ждёт, пока смолкнет эхо. */
    fun msSinceSpeech(): Long = if (speaking > 0) 0L else System.currentTimeMillis() - lastUse

    /**
     * Произносит текст и ждёт конца. В тексте можно писать паузы {pause:5s} (длительность или диапазон «2s..5s»):
     * текст режется на куски, между ними выдерживается пауза (её отмеряем сами, поэтому она одинакова у любого движка
     * речи и обрывается по [cancel]). Возвращает текст ошибки или null.
     */
    fun speak(text: String, volumePct: Int?, cfg: JSONObject?, cancel: () -> Boolean): String? {
        speaking++
        try {
            return Audio.withVolume(app, volumePct) { speakInner(text, cfg, cancel) }
        } finally {
            speaking--
            lastUse = System.currentTimeMillis()
        }
    }

    private fun speakInner(text: String, cfg: JSONObject?, cancel: () -> Boolean): String? {
        val t0 = System.currentTimeMillis()
        ensure()?.let { return it }
        val t = tts ?: return "синтезатор не готов"
        apply(t, cfg)?.let { return it }
        val prepMs = System.currentTimeMillis() - t0
        var firstDelay = -1L
        val max = try { TextToSpeech.getMaxSpeechInputLength() } catch (e: Throwable) { 3900 }
        val rnd = java.util.Random()
        // куски: текст (pause = -1) и паузы (text = null)
        val parts = ArrayList<Pair<String?, Long>>()
        var pos = 0
        for (m in TaskSchema.PAUSE_RE.findAll(text)) {
            parts.add(text.substring(pos, m.range.first) to -1L)
            val ms = try { Dur.parse(m.groupValues[1].trim(), "пауза").sample(rnd) } catch (e: IllegalArgumentException) { 0L }
            parts.add(null to ms.coerceIn(0L, 120_000L))
            pos = m.range.last + 1
        }
        parts.add(text.substring(pos) to -1L)
        for ((chunkText, pauseMs) in parts) {
            if (chunkText == null) {
                var left = pauseMs
                while (left > 0) {
                    if (cancel()) return null
                    val step = minOf(left, 100L)
                    Thread.sleep(step)
                    left -= step
                }
                continue
            }
            val chunks = ArrayList<String>()
            var rest = chunkText.trim()
            while (rest.length > max) {
                var cut = rest.lastIndexOf(". ", max - 1)
                if (cut < max / 2) cut = max - 1
                chunks.add(rest.substring(0, cut + 1))
                rest = rest.substring(cut + 1).trim()
            }
            if (rest.isNotEmpty()) chunks.add(rest)
            for (part in chunks) {
                val id: String
                synchronized(lock) { id = "u${++counter}" }
                val latch = CountDownLatch(1)
                waiting[id] = latch
                val params = Bundle().apply { putInt(TextToSpeech.Engine.KEY_PARAM_STREAM, AudioManager.STREAM_MUSIC) }
                val tCall = System.currentTimeMillis()
                if (t.speak(part, TextToSpeech.QUEUE_FLUSH, params, id) != TextToSpeech.SUCCESS) {
                    waiting.remove(id)
                    return "синтезатор отказался говорить"
                }
                val end = System.currentTimeMillis() + 5 * 60_000L
                while (!latch.await(100, TimeUnit.MILLISECONDS)) {
                    if (cancel()) { try { t.stop() } catch (e: Exception) {}; waiting.remove(id); return null }
                    if (!startedAt.containsKey(id) && System.currentTimeMillis() - tCall > START_TIMEOUT_MS) {
                        // движок принял фразу, но не начал говорить (завис, нет голоса): не ждём, при следующей речи синтезатор создаётся заново
                        try { t.stop() } catch (e: Exception) {}
                        waiting.remove(id)
                        gaveUp[id] = tCall
                        if (gaveUp.size > 20) gaveUp.clear()
                        FLog.d("voice", "синтезатор не начал говорить: слушатель событий ${if (listenerOwner === t) "стоит" else "НЕ стоял"}, запуск занял ${initDoneAfterMs} мс")
                        reset("не начал говорить за ${START_TIMEOUT_MS / 1000} с")
                        return "синтезатор не начал говорить за ${START_TIMEOUT_MS / 1000} с (движок речи не отвечает)"
                    }
                    if (System.currentTimeMillis() > end) { try { t.stop() } catch (e: Exception) {}; waiting.remove(id); return "речь не закончилась за 5 минут" }
                }
                val st = startedAt.remove(id)
                if (firstDelay < 0 && st != null) {
                    firstDelay = st - tCall
                    FLog.d("voice", "задержка речи: подготовка синтезатора $prepMs мс, от команды до начала звука $firstDelay мс")
                }
            }
        }
        return null
    }

    fun stop() {
        try { tts?.stop() } catch (e: Exception) {}
    }

    /**
     * Прогрев: после долгого простоя движок речи (процесс Google TTS или его голос) выгружается, и первая фраза начинается с паузой
     * в несколько секунд. Беззвучный синтез короткой фразы в файл удерживает движок и голос в памяти. Выполняется, только если
     * синтезатор простаивал не меньше [idleMs] и сейчас ничего не говорит. Любой сбой (нет сети для сетевого голоса и т. п.) только пишется в лог.
     */
    fun warmIfIdle(idleMs: Long, reason: String) {
        try {
            if (!::app.isInitialized || speaking > 0) return
            if (System.currentTimeMillis() - lastUse < idleMs) return
            if (!warming.compareAndSet(false, true)) return
            try { warm(reason) } finally { warming.set(false) }
        } catch (e: Throwable) {
            FLog.d("voice", "прогрев ($reason) не удался: ${e.javaClass.simpleName}: ${e.message}")
        }
    }

    private fun warm(reason: String) {
        val t0 = System.currentTimeMillis()
        ensure()?.let { FLog.d("voice", "прогрев ($reason): $it"); return }
        val t = tts ?: return
        apply(t, Settings.voice())?.let { FLog.d("voice", "прогрев ($reason): $it"); return }
        val f = File(app.cacheDir, "tts-warm.wav")
        val id: String
        synchronized(lock) { id = "w${++counter}" }
        val latch = CountDownLatch(1)
        waiting[id] = latch
        if (t.synthesizeToFile("а", Bundle(), f, id) != TextToSpeech.SUCCESS) {
            waiting.remove(id)
            FLog.d("voice", "прогрев ($reason): синтезатор отказался работать")
            return
        }
        val done = latch.await(20, TimeUnit.SECONDS)
        if (!done) {
            waiting.remove(id)
            // сильнее простого повтора: зависший синтезатор сбрасываем, следующая попытка (прогрев или речь) создаёт его заново
            FLog.d("voice", "прогрев ($reason): слушатель событий ${if (listenerOwner === t) "стоит" else "НЕ стоял"}, запуск занял ${initDoneAfterMs} мс")
            reset("прогрев не получил ответа за 20 с")
        }
        startedAt.remove(id)
        f.delete()
        lastUse = System.currentTimeMillis()
        FLog.d("voice", "прогрев ($reason): ${lastUse - t0} мс" + if (done) "" else " (синтезатор не ответил за 20 с)")
    }

    fun info(cfg: JSONObject?): JSONObject {
        val err = ensure()
        val t = tts
        if (err != null || t == null) return obj("ready" to false, "error" to (err ?: "не готов"))
        val lang = cfg?.optString("lang").orEmpty().ifEmpty { "ru-RU" }
        val loc = Locale.forLanguageTag(lang.replace('_', '-')) // как и при речи: ru_RU и ru-RU одно и то же
        val avail = t.isLanguageAvailable(loc)
        val voices = try {
            t.voices?.filter { it.locale.language == loc.language }
                ?.sortedBy { it.name }?.take(60)?.map {
                    obj("name" to it.name, "locale" to it.locale.toLanguageTag(), "quality" to it.quality, "latency" to it.latency, "network" to it.isNetworkConnectionRequired, "notInstalled" to (it.features?.contains("notInstalled") == true))
                } ?: emptyList()
        } catch (e: Throwable) { emptyList() }
        // для разбора «нет голоса»: что движок отдаёт вообще, а не только для выбранного языка
        val engines = try { t.engines.map { obj("name" to it.name, "label" to it.label) } } catch (e: Throwable) { emptyList() }
        val allVoices = try { t.voices } catch (e: Throwable) { null }
        val byLang = allVoices?.groupingBy { it.locale.toLanguageTag() }?.eachCount()?.toSortedMap()
        val checks = JSONObject()
        for (tag in listOf("ru-RU", "ru", "en-US")) checks.put(tag, try { t.isLanguageAvailable(Locale.forLanguageTag(tag)) } catch (e: Throwable) { null })
        return obj(
            "ready" to true,
            "engines" to arr(engines),
            "voiceCount" to (allVoices?.size ?: -1),
            "voicesByLocale" to JSONObject(byLang?.mapKeys { it.key } ?: emptyMap<String, Int>()),
            "isLanguageAvailable" to checks, // коды Android: 0 язык, 1 язык и страна, 2 язык, страна и вариант; -1 нет данных; -2 не поддерживается
            "defaultVoice" to (try { t.defaultVoice?.name } catch (e: Throwable) { null }),
            "engine" to (try { t.defaultEngine } catch (e: Throwable) { null }),
            "lang" to lang,
            "languageAvailable" to (avail >= TextToSpeech.LANG_AVAILABLE),
            "voices" to arr(voices)
        )
    }
}
