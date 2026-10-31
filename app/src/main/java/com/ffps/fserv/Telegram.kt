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
    @Volatile private var running = false
    private var loopFails = 0
    private val tf = SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.US)

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
        if (thread?.isAlive == true && running) return
        running = true
        thread = Thread({ loop() }, "telegram-outbox").also { it.isDaemon = true; it.start() }
    }

    @Synchronized
    fun stop() {
        running = false
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
        while (running && !Thread.currentThread().isInterrupted) {
            try {
                step()
                loopFails = 0
            } catch (e: InterruptedException) {
                return
            } catch (e: Throwable) {
                // Неизвестный сбой не должен превращаться в быстрый цикл повторов: пауза растёт до 5 минут.
                loopFails++
                FLog.e("telegram", "сбой цикла отправки (подряд $loopFails)", e)
                try { Thread.sleep(BACKOFF[minOf(loopFails - 1, BACKOFF.size - 1)]) } catch (ie: InterruptedException) { return }
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
        val text = sanitize(m.text) +
            if (delayed > DELAY_NOTE_MS) "\n\n<i>Отправлено в ${tf.format(Date(m.createdAt))}</i>" else ""
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
        } catch (e: InterruptedException) {
            throw e
        } catch (e: RuntimeException) {
            // Любой иной сбой при отправке: повтор только по расписанию отсрочек, а не сразу.
            FLog.e("telegram", "сбой при отправке", e)
            fail(m, "${e.javaClass.simpleName}: ${e.message}", 0, 0)
        }
    }

    private fun htmlEscape(t: String) = t.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;")

    private val TAGS = setOf("b", "strong", "i", "em", "u", "ins", "s", "strike", "del", "code", "pre", "a", "tg-spoiler", "blockquote")
    private val TAG_RE = Regex("""<(/?)([A-Za-z][A-Za-z0-9-]*)((?:\s+[^<>]*)?)>""")
    private val A_OPEN_RE = Regex("""\s+href\s*=\s*("[^"<>]*"|'[^'<>]*')\s*""")

    /** Экранирует текст, но оставляет поддерживаемые Telegram теги (b, i, u, s, code, pre, a href, blockquote, tg-spoiler); при неправильной вложенности экранирует всё. */
    fun sanitize(text: String): String {
        fun esc(part: String): String = part.replace(Regex("&(?!(amp|lt|gt|quot|#\\d+);)"), "&amp;").replace("<", "&lt;").replace(">", "&gt;")
        val sb = StringBuilder()
        val stack = ArrayList<String>()
        var pos = 0
        for (m in TAG_RE.findAll(text)) {
            val closing = m.groupValues[1] == "/"
            val name = m.groupValues[2].lowercase()
            val attrs = m.groupValues[3]
            val ok = name in TAGS && (if (closing) attrs.isBlank() else if (name == "a") A_OPEN_RE.matches(attrs) else attrs.isBlank())
            sb.append(esc(text.substring(pos, m.range.first)))
            pos = m.range.last + 1
            if (!ok) { sb.append(esc(m.value)); continue }
            if (closing) {
                if (stack.isEmpty() || stack.last() != name) return htmlEscape(text)
                stack.removeAt(stack.size - 1)
            } else stack.add(name)
            sb.append(m.value)
        }
        sb.append(esc(text.substring(pos)))
        return if (stack.isEmpty()) sb.toString() else htmlEscape(text)
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
        call("sendMessage", obj("chat_id" to Settings.telegramChatId, "text" to text, "parse_mode" to "HTML", "disable_web_page_preview" to true))
    }

    /**
     * Проверка связи: тестовое сообщение ставится в обычную очередь и ждёт доставки не дольше таймаута сети.
     * Если не ушло, остаётся в очереди и будет повторяться, как любое сообщение; тогда бросается IOException с причиной.
     */
    fun test(): String {
        require(Settings.telegramConfigured) { "не заданы токен бота и chat id" }
        val id = enqueue("fServe: проверка связи (${android.os.Build.MODEL}, версия ${BuildConfig.VERSION_NAME})")
        val deadline = System.currentTimeMillis() + Settings.netTimeoutSec * 1000L + 3000
        while (System.currentTimeMillis() < deadline) {
            val queued = synchronized(lock) { items.any { it.id == id } }
            if (!queued) return "тестовое сообщение доставлено через очередь"
            Thread.sleep(300)
        }
        val it = synchronized(lock) { items.firstOrNull { x -> x.id == id } }
            ?: return "тестовое сообщение доставлено через очередь"
        throw java.io.IOException(
            "не доставлено, сообщение осталось в очереди и будет отправляться повторно" +
                (if (it.lastError.isNotEmpty()) ": ${it.lastError}" else " (впереди есть другие сообщения)")
        )
    }
}
