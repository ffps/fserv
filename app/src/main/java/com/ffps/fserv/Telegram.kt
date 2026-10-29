package com.ffps.fserv

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Отправка сообщений в Telegram напрямую с устройства (токен бота и chat id берутся из настроек).
 * Все сообщения идут через очередь на диске: если сети или прокси нет, сообщение ждёт и уходит позже,
 * с пометкой времени исходного события. Порядок сохраняется. Сообщение пропадает только если оно старше
 * 7 дней, очередь переполнена (200) или Telegram упорно отвергает именно это сообщение (HTTP 400).
 */
object Telegram {
    private const val MAX_ITEMS = 200
    private const val MAX_AGE_MS = 7L * 24 * 3600 * 1000
    private const val DELAY_NOTE_MS = 120_000L
    private val BACKOFF = longArrayOf(10, 30, 60, 120, 300).map { it * 1000 }

    private class Item(val id: Long, val text: String, val createdAt: Long) {
        var attempts = 0
        var nextTry = 0L
        var lastError = ""
        var rejected = 0
    }

    class TgError(message: String, val code: Int, val retryAfterSec: Int = 0) : Exception(message)

    private lateinit var file: File
    private val lock = Object()
    private val items = ArrayList<Item>()
    private var thread: Thread? = null
    private val tf = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US)

    @Volatile var lastError = ""
    @Volatile var lastSentAt = 0L

    fun init(ctx: Context) {
        file = File(ctx.filesDir, "outbox.json")
        try {
            if (file.exists()) {
                val a = JSONArray(file.readText())
                for (i in 0 until a.length()) {
                    val o = a.getJSONObject(i)
                    items.add(Item(o.getLong("id"), o.getString("text"), o.getLong("createdAt")).also {
                        it.attempts = o.optInt("attempts")
                        it.lastError = o.optString("lastError")
                    })
                }
            }
        } catch (e: Exception) {
            FLog.w("telegram", "очередь не прочитана: ${e.message}")
        }
    }

    private fun saveLocked() {
        val a = JSONArray()
        for (i in items) a.put(obj("id" to i.id, "text" to i.text, "createdAt" to i.createdAt, "attempts" to i.attempts, "lastError" to i.lastError))
        try {
            val tmp = File(file.parentFile, "outbox.json.tmp")
            tmp.writeText(a.toString())
            if (!tmp.renameTo(file)) file.writeText(a.toString())
        } catch (e: Exception) {
            FLog.e("telegram", "очередь не сохранена", e)
        }
    }

    @Synchronized
    fun start() {
        if (thread?.isAlive == true) return
        thread = Thread({ loop() }, "telegram-outbox").also { it.isDaemon = true; it.start() }
    }

    @Synchronized
    fun stop() {
        thread?.interrupt()
        thread = null
    }

    /** Ставит сообщение в очередь. Возвращает его номер. */
    fun enqueue(text: String): Long {
        val t = if (text.length > 4000) text.take(4000) + "…" else text
        synchronized(lock) {
            val id = System.currentTimeMillis() * 1000 + (items.size % 1000)
            items.add(Item(id, t, System.currentTimeMillis()))
            while (items.size > MAX_ITEMS) {
                val d = items.removeAt(0)
                FLog.w("telegram", "очередь переполнена, удалено сообщение ${d.id}")
                Journal.add("telegram", "Очередь переполнена, удалено старое сообщение")
            }
            saveLocked()
            lock.notifyAll()
            FLog.i("telegram", "в очередь: ${t.take(80).replace('\n', ' ')} (в очереди ${items.size})")
            return id
        }
    }

    fun pending(): Int = synchronized(lock) { items.size }

    fun itemsJson(): JSONArray = synchronized(lock) {
        arr(items.map {
            obj(
                "id" to it.id, "text" to it.text, "created" to Util.iso(it.createdAt), "attempts" to it.attempts,
                "nextTry" to if (it.nextTry > 0) Util.iso(it.nextTry) else null, "lastError" to it.lastError
            )
        })
    }

    fun clear(): Int = synchronized(lock) {
        val n = items.size
        items.clear()
        saveLocked()
        n
    }

    fun status(): JSONObject = obj(
        "configured" to Settings.telegramConfigured,
        "pending" to pending(),
        "lastError" to lastError,
        "lastSentAt" to if (lastSentAt > 0) Util.iso(lastSentAt) else null
    )

    private fun loop() {
        while (!Thread.currentThread().isInterrupted) {
            try {
                step()
            } catch (e: InterruptedException) {
                return
            } catch (e: Throwable) {
                FLog.e("telegram", "сбой цикла отправки", e)
                try { Thread.sleep(5000) } catch (ie: InterruptedException) { return }
            }
        }
    }

    private fun step() {
        val m: Item
        synchronized(lock) {
            val now = System.currentTimeMillis()
            while (items.isNotEmpty() && now - items[0].createdAt > MAX_AGE_MS) {
                val d = items.removeAt(0)
                FLog.w("telegram", "сообщение ${d.id} старше 7 дней, удалено")
                Journal.add("telegram", "Сообщение не доставлено за 7 дней и удалено")
                saveLocked()
            }
            if (items.isEmpty()) { lock.wait(30_000); return }
            m = items[0]
            if (m.nextTry > now) { lock.wait(minOf(m.nextTry - now, 30_000L).coerceAtLeast(200)); return }
        }
        if (!Settings.telegramConfigured) {
            lastError = "не заданы токен бота или chat id"
            synchronized(lock) { lock.wait(15_000) }
            return
        }
        val delayed = System.currentTimeMillis() - m.createdAt
        val text = if (delayed > DELAY_NOTE_MS)
            m.text + "\n\n(событие: ${tf.format(Date(m.createdAt))}, отправлено с задержкой ${delayed / 60000} мин)"
        else m.text
        try {
            send(text)
            synchronized(lock) { items.remove(m); saveLocked() }
            lastError = ""
            lastSentAt = System.currentTimeMillis()
            FLog.i("telegram", "отправлено (попыток ${m.attempts + 1})")
            if (m.attempts > 0) Journal.add("telegram", "Сообщение доставлено после ${m.attempts} неудачных попыток")
        } catch (e: TgError) {
            fail(m, e.message ?: "ошибка", e.code, e.retryAfterSec)
        } catch (e: java.io.IOException) {
            fail(m, e.message ?: "сеть", 0, 0)
        }
    }

    private fun fail(m: Item, msg: String, code: Int, retryAfterSec: Int) {
        m.attempts++
        m.lastError = msg
        lastError = msg
        if (code == 400) m.rejected++
        val wait = if (retryAfterSec > 0) retryAfterSec * 1000L + 500 else BACKOFF[minOf(m.attempts - 1, BACKOFF.size - 1)]
        m.nextTry = System.currentTimeMillis() + wait
        FLog.w("telegram", "не отправлено (попытка ${m.attempts}, повтор через ${wait / 1000} с): $msg")
        synchronized(lock) {
            if (m.rejected >= 10) {
                items.remove(m)
                Journal.add("telegram", "Telegram отверг сообщение 10 раз подряд, оно удалено: $msg")
            }
            saveLocked()
        }
    }

    /** Один вызов Bot API. Бросает TgError при ответе с ошибкой и IOException при сбое сети. */
    private fun call(method: String, payload: JSONObject?): JSONObject {
        val url = "https://api.telegram.org/bot${Settings.telegramToken}/$method"
        val r = Net.request(
            "telegram", "POST", url, (payload ?: JSONObject()).toString().toByteArray(Charsets.UTF_8),
            "application/json; charset=utf-8"
        )
        val j = try { JSONObject(r.body) } catch (e: Exception) { null }
        if (j == null || !j.optBoolean("ok")) {
            val desc = j?.optString("description")?.ifEmpty { null } ?: "HTTP ${r.code}"
            val ra = j?.optJSONObject("parameters")?.optInt("retry_after", 0) ?: 0
            throw TgError("Telegram: $desc (HTTP ${r.code}, ${r.via})", r.code, ra)
        }
        return j
    }

    private fun send(text: String) {
        call("sendMessage", obj("chat_id" to Settings.telegramChatId, "text" to text, "disable_web_page_preview" to true))
    }

    /** Проверка связи: getMe и тестовое сообщение напрямую, минуя очередь. Возвращает описание результата. */
    fun test(): String {
        require(Settings.telegramConfigured) { "не заданы токен бота и chat id" }
        val me = call("getMe", null).optJSONObject("result")?.optString("username") ?: "?"
        send("fServe: проверка связи (${android.os.Build.MODEL}, версия ${BuildConfig.VERSION_NAME})")
        return "бот @$me, тестовое сообщение отправлено"
    }
}
