package com.ffps.fserv

import android.content.Context
import android.media.AudioManager
import android.os.Handler
import android.os.Looper
import android.os.PowerManager
import android.webkit.JavascriptInterface
import org.json.JSONObject
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/**
 * Видеозвонок админка → устройство (WebRTC, точка-точка). Браузер администратора создаёт предложение (offer), устройство отвечает:
 * ответ формирует страница call.html во втором WebView поверх киоска (см. MainActivity). Подтверждение на устройстве не требуется.
 *
 * Правила: звонок только при киоске на переднем плане (при необходимости экран включается и киоск выводится вперёд); на время звонка
 * камера и микрофон принадлежат звонку: задачи, которым они нужны, получают ошибку (см. [active]); звонок другого администратора
 * прерывает текущий только с подтверждением; запись в лог ведётся с адресами. Если админка перестала присылать ping (закрыта страница,
 * пропала сеть) или киоск ушёл с переднего плана, звонок завершается сам.
 */
object Call {
    /** Идёт звонок (или он устанавливается): камера и микрофон заняты. */
    @Volatile var active = false
        private set

    class Session(val id: String, val addr: String, val startedAt: Long) {
        @Volatile var lastPing = startedAt
        @Volatile var connected = false
        @Volatile var hidden = 0 // сколько проверок подряд киоск был не на виду
    }

    class Result(val ok: Boolean, val id: String? = null, val sdp: String? = null, val error: String? = null, val busyFrom: String? = null)

    private lateinit var app: Context
    private val handler = Handler(Looper.getMainLooper())
    private val lock = Any()
    @Volatile private var session: Session? = null
    private val ended = ConcurrentHashMap<String, String>() // id завершённого звонка → причина (для админки, которая ещё не знает)
    @Volatile private var latch: CountDownLatch? = null
    @Volatile private var answer: String? = null
    @Volatile private var error: String? = null
    private var saved: Triple<Int, Boolean, Int>? = null

    fun init(ctx: Context) { app = ctx.applicationContext }

    private val watchdog = object : Runnable {
        override fun run() {
            val s = session ?: return
            val now = System.currentTimeMillis()
            if (now - s.lastPing > 12_000) { end(s.id, "админка перестала отвечать", null); return }
            if (MainActivity.isVisible()) s.hidden = 0 else if (++s.hidden >= 3) { end(s.id, "киоск ушёл с переднего плана", null); return }
            handler.postDelayed(this, 2_000)
        }
    }

    fun status(): JSONObject {
        val s = session
        val perms = CameraEngine.hasPermission(app) && app.checkSelfPermission(android.Manifest.permission.RECORD_AUDIO) == android.content.pm.PackageManager.PERMISSION_GRANTED
        return obj(
            "available" to perms,
            "reason" to if (perms) "" else "нет разрешений на камеру и микрофон (кнопки Allow в окне настроек устройства)",
            "active" to (s != null),
            "from" to s?.addr,
            "kioskVisible" to MainActivity.isVisible(),
            "httpsPort" to Settings.httpsPort,
            "ring" to TaskEngine.taskEnabled("ring"),
            "call" to Settings.call()
        )
    }

    /** Начинает звонок. Блокирует вызывающий поток до ответа устройства (до 25 с). */
    fun start(addr: String, offer: String, force: Boolean): Result {
        val s: Session
        synchronized(lock) {
            session?.let { old ->
                if (old.addr != addr && !force) return Result(false, error = "идёт звонок с адреса ${old.addr}", busyFrom = old.addr)
                if (old.addr != addr) FLog.w("call", "звонок прерван: шёл с адреса ${old.addr}, прервал адрес $addr")
                endLocked(old, if (old.addr != addr) "прерван звонком с адреса $addr" else "заменён новым вызовом с того же адреса")
            }
            if (!MainActivity.isVisible()) {
                wakeScreen()
                MainActivity.show(app)
                val end = System.currentTimeMillis() + 4_000
                while (!MainActivity.isVisible() && System.currentTimeMillis() < end) Thread.sleep(100)
            }
            if (!MainActivity.isVisible()) {
                FLog.w("call", "звонок с адреса $addr не начат: киоск не на переднем плане")
                return Result(false, error = "киоск не на переднем плане, звонок невозможен")
            }
            s = Session(UUID.randomUUID().toString().take(12), addr, System.currentTimeMillis())
            session = s
            active = true
            error = null
            answer = null
            latch = CountDownLatch(1)
            FLog.i("call", "звонок с адреса $addr: устанавливаю связь")
        }
        try {
            Slide.off()
            CameraEngine.suspendForCall()
            Thread.sleep(300) // камера и микрофон освобождаются не мгновенно
            val res = Settings.call().optString("deviceRes", "640x480")
            if (!MainActivity.callStart(offer, res)) {
                end(s.id, "не удалось показать окно звонка", null)
                return Result(false, error = "не удалось показать окно звонка на устройстве")
            }
            handler.removeCallbacks(watchdog)
            handler.postDelayed(watchdog, 2_000)
            latch?.await(25, TimeUnit.SECONDS)
            if (session !== s) return Result(false, error = ended[s.id] ?: "звонок отменён")
            beginAudio() // режим связи включаем, когда звук уже захвачен: до этого он мешал запуску микрофона
            val a = answer
            if (a == null) {
                val e = error ?: "устройство не ответило за 25 с"
                end(s.id, e, null)
                return Result(false, error = e)
            }
            return Result(true, id = s.id, sdp = a)
        } catch (e: Exception) {
            end(s.id, "ошибка: ${e.message ?: e.javaClass.simpleName}", null)
            return Result(false, error = e.message ?: e.javaClass.simpleName)
        }
    }

    /** Админка присылает ping; возвращает null, если звонок идёт, иначе причину, по которой он закончился. */
    fun ping(id: String): String? {
        val s = session
        if (s != null && s.id == id) { s.lastPing = System.currentTimeMillis(); return null }
        return ended[id] ?: "звонок не найден"
    }

    /** Завершение по просьбе админки (кнопка или закрытие страницы). */
    fun end(id: String?, reason: String, by: String?) {
        synchronized(lock) {
            val s = session ?: return
            if (id != null && s.id != id) return
            endLocked(s, if (by != null) "$reason (адрес $by)" else reason)
        }
    }

    /** Завершение из самого приложения (киоск закрыт и т. п.). */
    fun endExternal(reason: String) = end(null, reason, null)

    private fun endLocked(s: Session, reason: String) {
        if (session !== s) return
        session = null
        active = false
        ended[s.id] = reason
        if (ended.size > 20) ended.keys.firstOrNull()?.let { ended.remove(it) }
        handler.removeCallbacks(watchdog)
        MainActivity.callStop()
        endAudio()
        CameraEngine.callEnded()
        latch?.countDown()
        FLog.i("call", "звонок с адреса ${s.addr} завершён: $reason, длился ${(System.currentTimeMillis() - s.startedAt) / 1000} с")
    }

    // ---------------- из страницы звонка на устройстве ----------------

    fun onAnswer(sdp: String) { answer = sdp; latch?.countDown() }

    fun onWarn(msg: String) = FLog.w("call", "страница звонка на устройстве: $msg")

    fun onError(msg: String) {
        FLog.w("call", "страница звонка на устройстве: $msg")
        error = "устройство: $msg"
        val s = session
        if (s != null && !s.connected) latch?.countDown() else if (s != null) end(s.id, "устройство: $msg", null)
    }

    fun onState(st: String) {
        val s = session ?: return
        FLog.d("call", "состояние связи на устройстве: $st")
        when (st) {
            "connected" -> { s.connected = true; FLog.i("call", "связь с адресом ${s.addr} установлена") }
            "failed", "closed" -> end(s.id, "связь потеряна ($st)", null)
            "disconnected" -> handler.postDelayed({ if (session === s) end(s.id, "связь потеряна", null) }, 8_000)
        }
    }

    // ---------------- экран и звук ----------------

    @Suppress("DEPRECATION")
    private fun wakeScreen() {
        try {
            val pm = app.getSystemService(Context.POWER_SERVICE) as PowerManager
            pm.newWakeLock(PowerManager.SCREEN_BRIGHT_WAKE_LOCK or PowerManager.ACQUIRE_CAUSES_WAKEUP or PowerManager.ON_AFTER_RELEASE, "fserv:call").acquire(10_000)
        } catch (e: Exception) {
            FLog.w("call", "экран не включён: ${e.message}")
        }
    }

    /** Режим связи и громкая связь: иначе звук собеседника уходит в разговорный динамик или звучит тихо. Прежние значения запоминаются. */
    private fun beginAudio() {
        try {
            val am = app.getSystemService(Context.AUDIO_SERVICE) as AudioManager
            if (saved == null) saved = Triple(am.mode, am.isSpeakerphoneOn, am.getStreamVolume(AudioManager.STREAM_VOICE_CALL))
            am.mode = AudioManager.MODE_IN_COMMUNICATION
            am.isSpeakerphoneOn = true
            am.setStreamVolume(AudioManager.STREAM_VOICE_CALL, am.getStreamMaxVolume(AudioManager.STREAM_VOICE_CALL), 0)
        } catch (e: Exception) {
            FLog.w("call", "звук звонка не настроен: ${e.message}")
        }
    }

    private fun endAudio() {
        try {
            val am = app.getSystemService(Context.AUDIO_SERVICE) as AudioManager
            saved?.let { (mode, speaker, vol) ->
                am.mode = mode
                am.isSpeakerphoneOn = speaker
                am.setStreamVolume(AudioManager.STREAM_VOICE_CALL, vol, 0)
            }
        } catch (e: Exception) {
            FLog.w("call", "звук после звонка не восстановлен: ${e.message}")
        }
        saved = null
    }
}

/** Мост между страницей call.html (WebView на устройстве) и [Call]. */
class CallBridge {
    @JavascriptInterface fun answer(sdp: String) = Call.onAnswer(sdp)
    @JavascriptInterface fun error(msg: String) = Call.onError(msg)
    @JavascriptInterface fun warn(msg: String) = Call.onWarn(msg)
    @JavascriptInterface fun state(st: String) = Call.onState(st)
}
