package com.ffps.fserv

import android.content.Context
import android.media.AudioManager
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import org.json.JSONObject
import java.util.Locale
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/**
 * Голос: системный синтезатор речи Android (TextToSpeech). Хороший автономный женский голос появится позже;
 * этот слой остаётся запасным. Язык, голос, скорость и тон берутся из строки voice в общем файле задач (tasks/_common.txt).
 */
object Voice {
    private lateinit var app: Context
    @Volatile private var tts: TextToSpeech? = null
    @Volatile private var initStatus = -2 // -2 не запускали, -1 ошибка, 0 готово
    private val waiting = ConcurrentHashMap<String, CountDownLatch>()
    private val lock = Any()
    private var counter = 0

    fun init(ctx: Context) {
        app = ctx.applicationContext
    }

    /** Создаёт синтезатор (в главном потоке) и ждёт готовности. Возвращает текст ошибки или null. */
    private fun ensure(): String? {
        synchronized(lock) {
            if (tts != null && initStatus == 0) return null
            if (tts == null || initStatus != -2) {
                try { tts?.shutdown() } catch (e: Exception) {}
                tts = null
                initStatus = -2
                val latch = CountDownLatch(1)
                Handler(Looper.getMainLooper()).post {
                    try {
                        tts = TextToSpeech(app) { status ->
                            initStatus = if (status == TextToSpeech.SUCCESS) 0 else -1
                            latch.countDown()
                        }
                    } catch (e: Exception) {
                        initStatus = -1
                        latch.countDown()
                    }
                }
                if (!latch.await(15, TimeUnit.SECONDS)) return "синтезатор речи не запустился за 15 с"
                if (initStatus != 0) {
                    tts = null
                    return "синтезатор речи недоступен (на устройстве не установлен движок TextToSpeech)"
                }
                tts!!.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
                    override fun onStart(id: String?) {}
                    override fun onDone(id: String?) { id?.let { waiting.remove(it)?.countDown() } }
                    @Deprecated("Deprecated in Java")
                    override fun onError(id: String?) { id?.let { waiting.remove(it)?.countDown() } }
                    override fun onError(id: String?, code: Int) { id?.let { waiting.remove(it)?.countDown() } }
                })
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
        t.setSpeechRate((cfg?.optDouble("rate", 1.0) ?: 1.0).toFloat())
        t.setPitch((cfg?.optDouble("pitch", 1.0) ?: 1.0).toFloat())
        return null
    }

    /**
     * Произносит текст и ждёт конца. В тексте можно писать паузы {pause:5s} (длительность или диапазон «2s..5s»):
     * текст режется на куски, между ними выдерживается пауза (её отмеряем сами, поэтому она одинакова у любого движка
     * речи и обрывается по [cancel]). Возвращает текст ошибки или null.
     */
    fun speak(text: String, volumePct: Int?, cfg: JSONObject?, cancel: () -> Boolean): String? {
        val wantPiper = cfg?.optString("engine") == "piper"
        if (wantPiper && !Piper.ready()) FLog.w("voice", "voice engine=piper, но голос Piper не скачан (вкладка «Звук»): говорю системным голосом")
        val usePiper = wantPiper && Piper.ready()
        var t: TextToSpeech? = null
        if (!usePiper) {
            ensure()?.let { return it }
            t = tts ?: return "синтезатор не готов"
            apply(t, cfg)?.let { return it }
        }
        if (volumePct != null) Audio.setVolume(app, volumePct)
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
                if (usePiper) {
                    if (cancel()) return null
                    val err = Piper.speak(part, (cfg?.optDouble("rate", 1.0) ?: 1.0).toFloat(), cancel)
                    if (err != null) {
                        // сбой нейроголоса не должен оставлять человека без речи: эта фраза уходит системному голосу
                        FLog.w("voice", "$err; говорю системным голосом")
                        ensure()?.let { return it }
                        val st = tts ?: return "синтезатор не готов"
                        apply(st, cfg)?.let { return it }
                        return speakSystem(st, part, cancel)
                    }
                    continue
                }
                val t = t!!
                val id: String
                synchronized(lock) { id = "u${++counter}" }
                val latch = CountDownLatch(1)
                waiting[id] = latch
                val params = Bundle().apply { putInt(TextToSpeech.Engine.KEY_PARAM_STREAM, AudioManager.STREAM_MUSIC) }
                if (t.speak(part, TextToSpeech.QUEUE_FLUSH, params, id) != TextToSpeech.SUCCESS) {
                    waiting.remove(id)
                    return "синтезатор отказался говорить"
                }
                val end = System.currentTimeMillis() + 5 * 60_000L
                while (!latch.await(100, TimeUnit.MILLISECONDS)) {
                    if (cancel()) { try { t.stop() } catch (e: Exception) {}; waiting.remove(id); return null }
                    if (System.currentTimeMillis() > end) { try { t.stop() } catch (e: Exception) {}; waiting.remove(id); return "речь не закончилась за 5 минут" }
                }
            }
        }
        return null
    }

    /** Запасной путь: одна фраза системным голосом. */
    private fun speakSystem(t: TextToSpeech, part: String, cancel: () -> Boolean): String? {
        val id: String
        synchronized(lock) { id = "u${++counter}" }
        val latch = CountDownLatch(1)
        waiting[id] = latch
        val params = Bundle().apply { putInt(TextToSpeech.Engine.KEY_PARAM_STREAM, AudioManager.STREAM_MUSIC) }
        if (t.speak(part, TextToSpeech.QUEUE_FLUSH, params, id) != TextToSpeech.SUCCESS) { waiting.remove(id); return "синтезатор отказался говорить" }
        val end = System.currentTimeMillis() + 5 * 60_000L
        while (!latch.await(100, TimeUnit.MILLISECONDS)) {
            if (cancel() || System.currentTimeMillis() > end) { try { t.stop() } catch (e: Exception) {}; waiting.remove(id); return null }
        }
        return null
    }

    fun stop() {
        Piper.stop()
        try { tts?.stop() } catch (e: Exception) {}
    }

    fun info(cfg: JSONObject?): JSONObject {
        val err = ensure()
        val t = tts
        if (err != null || t == null) return obj("ready" to false, "error" to (err ?: "не готов"))
        val lang = cfg?.optString("lang").orEmpty().ifEmpty { "ru-RU" }
        val avail = t.isLanguageAvailable(Locale.forLanguageTag(lang))
        val voices = try {
            t.voices?.filter { it.locale.language == Locale.forLanguageTag(lang).language }
                ?.sortedBy { it.name }?.take(60)?.map {
                    obj("name" to it.name, "locale" to it.locale.toLanguageTag(), "quality" to it.quality, "network" to it.isNetworkConnectionRequired)
                } ?: emptyList()
        } catch (e: Throwable) { emptyList() }
        return obj(
            "ready" to true,
            "engine" to (try { t.defaultEngine } catch (e: Throwable) { null }),
            "lang" to lang,
            "languageAvailable" to (avail >= TextToSpeech.LANG_AVAILABLE),
            "voices" to arr(voices)
        )
    }
}
