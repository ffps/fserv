package com.ffps.fserv

import android.content.Context
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import org.json.JSONArray
import org.json.JSONObject
import java.io.IOException
import java.util.Locale
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/** Голос: системный синтезатор речи Android (русский). Хороший голос подключится на следующем этапе. */
object Speech : TextToSpeech.OnInitListener {
    private lateinit var app: Context
    @Volatile private var tts: TextToSpeech? = null
    @Volatile private var ready = false
    @Volatile private var ruOk = false
    @Volatile private var initDone: CountDownLatch? = null
    @Volatile private var gen = 0
    @Volatile var lastError = ""
    private val waits = ConcurrentHashMap<String, CountDownLatch>()
    private val errors = ConcurrentHashMap<String, Boolean>()
    private var counter = 0

    fun init(ctx: Context) {
        app = ctx.applicationContext
    }

    override fun onInit(status: Int) {
        val t = tts
        if (status == TextToSpeech.SUCCESS && t != null) {
            ready = true
            val r = t.setLanguage(Locale("ru", "RU"))
            ruOk = r >= TextToSpeech.LANG_AVAILABLE
            t.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
                override fun onStart(utteranceId: String?) {}
                override fun onDone(utteranceId: String?) { utteranceId?.let { waits[it]?.countDown() } }
                @Deprecated("Deprecated in Java")
                override fun onError(utteranceId: String?) { utteranceId?.let { errors[it] = true; waits[it]?.countDown() } }
            })
            FLog.i("tts", "синтезатор готов: ${t.defaultEngine}, русский доступен: $ruOk")
        } else {
            lastError = "синтезатор речи не запустился (код $status)"
            FLog.w("tts", lastError)
        }
        initDone?.countDown()
    }

    @Synchronized
    private fun ensure(): TextToSpeech {
        tts?.let { if (ready) return it }
        if (tts == null) {
            val latch = CountDownLatch(1)
            initDone = latch
            Handler(Looper.getMainLooper()).post { tts = TextToSpeech(app, this) }
            if (!latch.await(20, TimeUnit.SECONDS)) {
                lastError = "синтезатор речи не ответил за 20 с"
                throw IOException(lastError)
            }
        }
        val t = tts
        if (t == null || !ready) {
            tts = null
            throw IOException(lastError.ifEmpty { "синтезатор речи недоступен" })
        }
        return t
    }

    fun stopAll() {
        gen++
        try { tts?.stop() } catch (e: Exception) {}
    }

    /** Говорит и возвращается, когда фраза закончилась или [stopCheck] вернул true. */
    fun speak(text: String, volume: Int, rate: Float, pitch: Float, voiceName: String, stopCheck: () -> Boolean) {
        val t = ensure()
        if (!ruOk) {
            throw IOException("нет русского голоса: на устройстве откройте Настройки, Язык и ввод, Синтез речи и установите русский")
        }
        val g = gen
        Audio.setVolumePercent(volume)
        if (voiceName.isNotEmpty()) {
            val v = try { t.voices?.firstOrNull { it.name == voiceName } } catch (e: Exception) { null }
            if (v != null) t.voice = v else FLog.w("tts", "голос «$voiceName» не найден, использую голос по умолчанию")
        }
        t.setSpeechRate(rate)
        t.setPitch(pitch)
        val id = "u" + (++counter)
        val latch = CountDownLatch(1)
        waits[id] = latch
        try {
            val rc = t.speak(text, TextToSpeech.QUEUE_FLUSH, Bundle(), id)
            if (rc != TextToSpeech.SUCCESS) throw IOException("синтезатор не принял текст (код $rc)")
            val limit = System.currentTimeMillis() + 30_000 + text.length * 200L
            while (!latch.await(100, TimeUnit.MILLISECONDS)) {
                if (g != gen || stopCheck()) { t.stop(); return }
                if (System.currentTimeMillis() > limit) { t.stop(); throw IOException("фраза не договорилась за отведённое время") }
            }
            if (errors.remove(id) == true) throw IOException("синтезатор сообщил об ошибке при чтении фразы")
        } finally {
            waits.remove(id)
        }
    }

    /** Состояние и список русских голосов (имена нужны для поля voice.name в конфиге). */
    fun status(): JSONObject {
        return try {
            val t = ensure()
            val vs = JSONArray()
            for (v in (t.voices ?: emptySet()).sortedBy { it.name }) {
                if (v.locale.language != "ru") continue
                vs.put(obj("name" to v.name, "locale" to v.locale.toString(), "quality" to v.quality, "latency" to v.latency, "networkRequired" to v.isNetworkConnectionRequired))
            }
            obj("ready" to true, "engine" to t.defaultEngine, "russianAvailable" to ruOk, "voices" to vs, "lastError" to lastError)
        } catch (e: Exception) {
            obj("ready" to false, "russianAvailable" to false, "voices" to JSONArray(), "lastError" to (e.message ?: e.javaClass.simpleName))
        }
    }
}
