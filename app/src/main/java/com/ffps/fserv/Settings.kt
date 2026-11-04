package com.ffps.fserv

import android.content.Context
import org.json.JSONObject
import java.io.File

/**
 * Базовые настройки приложения (settings.json в приватной папке): порт и пароль веб-интерфейса,
 * автостарт, уровень лога и расписания автоперезапуска (cron).
 * Пароль не генерируется: его вводит пользователь в окне настроек.
 */
object Settings {
    private lateinit var file: File
    private var o = JSONObject()

    @Synchronized
    fun init(ctx: Context) {
        file = File(ctx.filesDir, "settings.json")
        o = try {
            if (file.exists()) JSONObject(file.readText()) else JSONObject()
        } catch (e: Exception) {
            JSONObject()
        }
    }

    /**
     * Восстановление из резервной копии: настройки из копии заменяют текущие, кроме состояния службы
     * и отметок последнего срабатывания расписаний (иначе после перезапуска расписание сработает повторно).
     */
    @Synchronized
    fun replaceAll(n: JSONObject) {
        for (k in listOf("serviceEnabled", "lastFired")) {
            if (o.has(k)) n.put(k, o.get(k)) else n.remove(k)
        }
        o = n
        save()
    }

    /**
     * Сброс к исходным: остаются только то, без чего администратор потеряет доступ к устройству
     * (пароль, порт, автозапуск, состояние службы) и служебные отметки расписаний.
     */
    @Synchronized
    fun resetUser() {
        val n = JSONObject()
        for (k in listOf("password", "webPort", "httpsPort", "autostart", "serviceEnabled", "lastFired")) if (o.has(k)) n.put(k, o.get(k))
        o = n
        save()
    }

    /** Содержимое настроек для резервной копии. */
    @Synchronized
    fun exportJson(): JSONObject = JSONObject(o.toString())

    private fun save() {
        val tmp = File(file.parentFile, "settings.json.tmp")
        tmp.writeText(o.toString(2))
        if (!tmp.renameTo(file)) {
            file.writeText(o.toString(2))
            tmp.delete()
        }
    }

    @get:Synchronized val webPort: Int get() = o.optInt("webPort", 8080)
    /** Порт HTTPS (самоподписанный сертификат): без защищённого соединения браузер не даёт странице камеру и микрофон (звонок). */
    @get:Synchronized val httpsPort: Int get() = o.optInt("httpsPort", 8443)
    @get:Synchronized val password: String get() = o.optString("password")
    @get:Synchronized val logLevel: String get() = o.optString("logLevel", "INFO")
    @get:Synchronized val appRestartCron: String get() = o.optString("appRestartCron", "")
    @get:Synchronized val rebootCron: String get() = o.optString("rebootCron", "")

    /** Запускать службу при включении устройства. */
    @get:Synchronized val autostart: Boolean get() = o.optBoolean("autostart", false)

    /** Желаемое состояние службы: true после START SERVICE, false после STOP SERVICE. */
    @get:Synchronized val serviceEnabled: Boolean get() = o.optBoolean("serviceEnabled", false)

    @get:Synchronized val telegramToken: String get() = o.optJSONObject("telegram")?.optString("token") ?: ""
    @get:Synchronized val telegramChatId: String get() = o.optJSONObject("telegram")?.optString("chatId") ?: ""
    @get:Synchronized val updateRepo: String get() = o.optJSONObject("update")?.optString("repo", "ffps/fserv") ?: "ffps/fserv"
    @get:Synchronized val updateCheckCron: String get() = o.optJSONObject("update")?.optString("cron", "0 4 * * *") ?: "0 4 * * *"

    // ---------- LLM и вкладка «Чат» ----------

    val LLM_PROVIDERS = listOf("gemini", "groq")
    private val LLM_DEFAULT_MODEL = mapOf("gemini" to "gemini-2.5-flash", "groq" to "llama-3.3-70b-versatile")

    @get:Synchronized val llmProvider: String get() = o.optJSONObject("llm")?.optString("provider")?.takeIf { it in LLM_PROVIDERS } ?: "gemini"
    @get:Synchronized val llmTimeoutSec: Int get() = (o.optJSONObject("llm")?.optInt("timeoutSec", 15) ?: 15).coerceIn(3, 60)

    @Synchronized
    fun llmKey(provider: String): String = o.optJSONObject("llm")?.optJSONObject(provider)?.optString("key") ?: ""

    @Synchronized
    fun llmModel(provider: String): String =
        o.optJSONObject("llm")?.optJSONObject(provider)?.optString("model")?.trim()?.ifEmpty { null } ?: LLM_DEFAULT_MODEL.getValue(provider)

    /** Вкладка «Чат»: rules — общие правила для модели (пусто = встроенные), topics — темы разговора (имя → что обсудить). */
    @Synchronized
    fun chat(): JSONObject = JSONObject((o.optJSONObject("chat") ?: JSONObject()).toString())

    private fun llmPublic(): JSONObject {
        val r = obj("provider" to llmProvider, "timeoutSec" to llmTimeoutSec)
        for (p in LLM_PROVIDERS) {
            val k = llmKey(p)
            r.put(p, obj("keySet" to k.isNotEmpty(), "keyHint" to if (k.length > 8) k.take(3) + "…" + k.takeLast(3) else "", "model" to llmModel(p)))
        }
        return r
    }

    val telegramConfigured: Boolean get() = telegramToken.isNotEmpty() && telegramChatId.isNotEmpty()

    /** Настройки голоса для задач (вкладка «Звук»): volume, lang, voice, rate, pitch (в процентах, 100 = обычные); отсутствующий ключ = значение по умолчанию. */
    @Synchronized
    fun voice(): JSONObject = JSONObject((o.optJSONObject("voice") ?: JSONObject()).toString())

    /** Параметры звонка (вкладка «Звонок»): разрешение камеры звонящего (adminRes) и камеры устройства (deviceRes). */
    @Synchronized
    fun call(): JSONObject = JSONObject().put("adminRes", o.optJSONObject("call")?.optString("adminRes")?.ifEmpty { null } ?: "640x480")
        .put("deviceRes", o.optJSONObject("call")?.optString("deviceRes")?.ifEmpty { null } ?: "640x480")

    /** Зоны детектора движения (вкладка «Камера»): имя → {rect | rects, exclude}. */
    @Synchronized
    fun zones(): JSONObject = JSONObject((o.optJSONObject("zones") ?: JSONObject()).toString())

    @Synchronized
    fun getStr(key: String): String = o.optJSONObject("state")?.optString(key, "") ?: ""

    @Synchronized
    fun putStr(key: String, v: String) {
        val st = o.optJSONObject("state") ?: JSONObject().also { o.put("state", it) }
        st.put(key, v)
        save()
    }

    /** Параметры камеры и детектора движения. */
    class CameraCfg(
        val intervalMs: Int, val cellThreshold: Int, val areaPercent: Double,
        val rotation: Int, val keepRunning: Boolean, val resolution: String
    )

    @Synchronized
    fun camera(): CameraCfg {
        val c = o.optJSONObject("camera")
        return CameraCfg(
            c?.optInt("intervalMs", 1000) ?: 1000,
            c?.optInt("cellThreshold", 10) ?: 10,
            c?.optDouble("areaPercent", 1.0) ?: 1.0,
            c?.optInt("rotation", -1) ?: -1,
            c?.optBoolean("keepRunning", false) ?: false,
            c?.optString("resolution", "640x480") ?: "640x480"
        )
    }

    /** Общий таймаут сетевых операций, секунд. */
    @get:Synchronized val netTimeoutSec: Int get() = o.optInt("netTimeoutSec", 10).coerceIn(2, 120)

    /** Один SOCKS5-прокси; флаги telegram/llm/updates говорят, для каких подсистем он используется. */
    class ProxyCfg(val host: String, val port: Int, val user: String, val password: String)

    /** Прокси для подсистемы (telegram, llm, updates) или null. Назначение "test" берёт прокси без учёта галочек. */
    @Synchronized
    fun proxyCfg(purpose: String): ProxyCfg? {
        val p = o.optJSONObject("proxy") ?: return null
        if (p.optString("host").isBlank()) return null
        if (purpose != "test" && !p.optBoolean(purpose)) return null
        return ProxyCfg(p.optString("host").trim(), p.optInt("port", 1080), p.optString("user"), p.optString("password"))
    }

    @Synchronized
    fun allProxies(): List<ProxyCfg> = listOfNotNull(proxyCfg("test"))

    val PURPOSES = listOf("telegram", "llm", "updates", "stt")

    /** Настройки заданы: без пароля веб-интерфейс не запускается. */
    val isConfigured: Boolean get() = password.isNotEmpty()

    @Synchronized
    fun setAutostart(v: Boolean) {
        o.put("autostart", v)
        save()
    }

    @Synchronized
    fun setServiceEnabled(v: Boolean) {
        o.put("serviceEnabled", v)
        save()
    }

    @Synchronized
    fun lastFired(kind: String): Long = o.optJSONObject("lastFired")?.optLong(kind, -1L) ?: -1L

    @Synchronized
    fun setLastFired(kind: String, minute: Long) {
        val lf = o.optJSONObject("lastFired") ?: JSONObject().also { o.put("lastFired", it) }
        lf.put(kind, minute)
        save()
    }

    /** Настройки без пароля (для веб-интерфейса). */
    @Synchronized
    fun publicJson(): JSONObject = obj(
        "webPort" to webPort,
        "httpsPort" to httpsPort,
        "logLevel" to logLevel,
        "appRestartCron" to appRestartCron,
        "rebootCron" to rebootCron,
        "passwordSet" to isConfigured,
        "autostart" to autostart,
        "telegram" to obj(
            "tokenSet" to telegramToken.isNotEmpty(),
            "tokenHint" to if (telegramToken.length > 8) telegramToken.take(3) + "…" + telegramToken.takeLast(3) else "",
            "chatId" to telegramChatId
        ),
        "netTimeoutSec" to netTimeoutSec,
        "camera" to camera().let { c ->
            obj("intervalMs" to c.intervalMs, "cellThreshold" to c.cellThreshold, "areaPercent" to c.areaPercent,
                "rotation" to c.rotation, "keepRunning" to c.keepRunning, "resolution" to c.resolution)
        },
        "proxy" to o.optJSONObject("proxy").let { p ->
            obj(
                "host" to (p?.optString("host") ?: ""),
                "port" to (p?.optInt("port", 1080) ?: 1080),
                "user" to (p?.optString("user") ?: ""),
                "passwordSet" to !(p?.optString("password").isNullOrEmpty()),
                "telegram" to (p?.optBoolean("telegram") ?: false),
                "llm" to (p?.optBoolean("llm") ?: false),
                "updates" to (p?.optBoolean("updates") ?: false),
                "stt" to (p?.optBoolean("stt") ?: false)
            )
        },
        "update" to obj("repo" to updateRepo, "cron" to updateCheckCron),
        "voice" to voice(),
        "zones" to zones(),
        "llm" to llmPublic(),
        "chat" to chat().put("defaultRules", Chat.DEFAULT_RULES),
        "call" to call()
    )

    class Result(val errors: List<String>, val restartRequired: Boolean)

    /** Применяет частичное изменение настроек; при любой ошибке не меняет ничего. */
    @Synchronized
    fun update(patch: JSONObject): Result {
        val errors = ArrayList<String>()
        var restart = false

        if (patch.has("logLevel")) {
            val v = patch.optString("logLevel").uppercase()
            if (v !in listOf("DEBUG", "INFO", "WARN", "ERROR")) errors.add("logLevel: допустимы DEBUG, INFO, WARN, ERROR")
        }
        if (patch.has("webPort")) {
            val v = patch.optInt("webPort", -1)
            if (v !in 1024..65535) errors.add("webPort: число от 1024 до 65535")
        }
        if (patch.has("httpsPort")) {
            val v = patch.optInt("httpsPort", -1)
            if (v !in 1024..65535) errors.add("httpsPort: число от 1024 до 65535")
            else if (v == (if (patch.has("webPort")) patch.optInt("webPort") else webPort)) errors.add("httpsPort: должен отличаться от webPort")
        }
        patch.optJSONObject("call")?.let { c ->
            for (k in listOf("adminRes", "deviceRes")) if (c.has(k) && !Regex("""\d{3,4}x\d{3,4}""").matches(c.optString(k))) errors.add("call.$k: вида 640x480")
        }
        val crons = listOf("appRestartCron" to patch.optString("appRestartCron"), "rebootCron" to patch.optString("rebootCron"), "update.cron" to patch.optJSONObject("update")?.optString("cron"))
        for ((k, raw) in crons) {
            if (raw != null && (patch.has(k) || k == "update.cron")) {
                val v = raw.trim()
                if (v.isNotEmpty()) {
                    try {
                        Cron.parse(v)
                    } catch (e: IllegalArgumentException) {
                        errors.add("$k: ${e.message}")
                    }
                }
            }
        }
        patch.optJSONObject("telegram")?.let { t ->
            if (t.optString("token").isNotEmpty() && !Regex("""\d{5,}:[A-Za-z0-9_-]{20,}""").matches(t.optString("token").trim())) {
                errors.add("telegram.token: ожидается вид 123456789:AAxxxxxxxx (от BotFather)")
            }
            if (t.has("chatId") && t.optString("chatId").isNotBlank() && !Regex("""-?\d+|@[A-Za-z0-9_]{4,}""").matches(t.optString("chatId").trim())) {
                errors.add("telegram.chatId: число (для группы со знаком минус) или @имя_канала")
            }
        }
        patch.optJSONObject("llm")?.let { l ->
            if (l.has("provider") && l.optString("provider") !in LLM_PROVIDERS) errors.add("llm.provider: gemini или groq")
            if (l.has("timeoutSec") && l.optInt("timeoutSec", -1) !in 3..60) errors.add("llm.timeoutSec: от 3 до 60 секунд")
            for (p in LLM_PROVIDERS) l.optJSONObject(p)?.let { x ->
                if (x.optString("key").isNotEmpty() && !Regex("""\S{8,300}""").matches(x.optString("key").trim())) errors.add("llm.$p.key: ключ без пробелов, 8–300 знаков")
                if (x.has("model") && x.optString("model").isNotBlank() && !Regex("""[\w.:/-]{1,80}""").matches(x.optString("model").trim())) errors.add("llm.$p.model: имя модели, например ${LLM_DEFAULT_MODEL.getValue(p)}")
            }
        }
        patch.optJSONObject("chat")?.let { c ->
            if (c.optString("rules").length > 4000) errors.add("chat.rules: не длиннее 4000 знаков")
            val t = c.optJSONObject("topics")
            if (c.has("topics") && t == null) errors.add("chat.topics: ожидается объект имя → текст")
            if (t != null) {
                if (t.length() > 30) errors.add("chat.topics: не больше 30 тем")
                for (n in t.keys()) {
                    if (!Regex("[A-Za-z_][A-Za-z0-9_]{0,30}").matches(n)) errors.add("chat.topics: имя «$n» — латинские буквы, цифры и _")
                    val x = t.opt(n)
                    if (x !is String || x.isBlank() || x.length > 1000) errors.add("chat.topics.$n: текст темы от 1 до 1000 знаков")
                }
            }
        }
        patch.optJSONObject("update")?.let { u ->
            if (u.has("repo") && !Regex("""[\w.-]+/[\w.-]+""").matches(u.optString("repo").trim())) errors.add("update.repo: ожидается владелец/репозиторий")
        }
        patch.optJSONObject("camera")?.let { c ->
            if (c.has("intervalMs") && c.optInt("intervalMs", -1) !in 200..60000) errors.add("camera.intervalMs: от 200 до 60000 мс")
            if (c.has("cellThreshold") && c.optInt("cellThreshold", -1) !in 1..100) errors.add("camera.cellThreshold: от 1 до 100")
            if (c.has("areaPercent") && (c.optDouble("areaPercent", -1.0) < 0.05 || c.optDouble("areaPercent", -1.0) > 100.0)) errors.add("camera.areaPercent: от 0.05 до 100")
            if (c.has("rotation") && c.optInt("rotation", -2) !in listOf(-1, 0, 90, 180, 270)) errors.add("camera.rotation: -1 (авто), 0, 90, 180 или 270")
            if (c.has("resolution") && !Regex("""\d{3,4}x\d{3,4}""").matches(c.optString("resolution"))) errors.add("camera.resolution: вида 640x480")
        }
        patch.optJSONObject("voice")?.let { v ->
            val bad = v.keys().asSequence().firstOrNull { it !in listOf("volume", "rate", "pitch", "lang", "voice") }
            if (bad != null) errors.add("voice.$bad: неизвестный параметр") else TaskSchema.checkVoice(v)?.let { errors.add("voice: $it") }
        }
        patch.optJSONObject("zones")?.let { z ->
            for (name in z.keys()) {
                if (!Regex("[A-Za-z_][A-Za-z0-9_]*").matches(name)) { errors.add("zones: имя «$name» — латинские буквы, цифры и _"); continue }
                val zo = z.optJSONObject(name)
                if (zo == null) errors.add("zones.$name: ожидается объект с rect, rects или exclude") else TaskSchema.checkZone(zo)?.let { errors.add("zones.$name: $it") }
            }
        }
        if (patch.has("netTimeoutSec") && patch.optInt("netTimeoutSec", -1) !in 2..120) errors.add("netTimeoutSec: число секунд от 2 до 120")
        patch.optJSONObject("proxy")?.let { p ->
            if (p.has("port") && p.optInt("port", -1) !in 1..65535) errors.add("proxy.port: число от 1 до 65535")
            val anyOn = PURPOSES.any { p.optBoolean(it) }
            val hostNow = if (p.has("host")) p.optString("host") else (o.optJSONObject("proxy")?.optString("host") ?: "")
            if (anyOn && hostNow.isBlank()) errors.add("proxy.host: адрес прокси пуст, а он нужен выбранным подсистемам")
        }
        if (patch.has("password") && patch.optString("password").isEmpty() && password.isEmpty()) {
            errors.add("password: пароль не может быть пустым")
        }
        if (errors.isNotEmpty()) return Result(errors, false)

        if (patch.has("logLevel")) {
            o.put("logLevel", patch.optString("logLevel").uppercase())
            FLog.minLevel = logLevel
        }
        if (patch.has("webPort") && patch.optInt("webPort") != webPort) {
            o.put("webPort", patch.optInt("webPort"))
            restart = true
        }
        if (patch.has("httpsPort") && patch.optInt("httpsPort") != httpsPort) {
            o.put("httpsPort", patch.optInt("httpsPort"))
            restart = true
        }
        patch.optJSONObject("call")?.let { c -> o.put("call", JSONObject(call().toString()).also { n -> for (k in listOf("adminRes", "deviceRes")) if (c.has(k)) n.put(k, c.optString(k)) }) }
        for (k in listOf("appRestartCron", "rebootCron")) {
            if (patch.has(k)) o.put(k, patch.optString(k).trim())
        }
        if (patch.has("autostart")) o.put("autostart", patch.optBoolean("autostart"))
        patch.optJSONObject("update")?.let { u ->
            val n = JSONObject((o.optJSONObject("update") ?: JSONObject()).toString())
            if (u.has("repo")) n.put("repo", u.optString("repo").trim())
            if (u.has("cron")) n.put("cron", u.optString("cron").trim())
            o.put("update", n)
        }
        patch.optJSONObject("llm")?.let { l ->
            val n = JSONObject((o.optJSONObject("llm") ?: JSONObject()).toString())
            if (l.has("provider")) n.put("provider", l.optString("provider"))
            if (l.has("timeoutSec")) n.put("timeoutSec", l.optInt("timeoutSec"))
            for (p in LLM_PROVIDERS) l.optJSONObject(p)?.let { x ->
                val pn = n.optJSONObject(p) ?: JSONObject().also { n.put(p, it) }
                if (x.has("model")) pn.put("model", x.optString("model").trim())
                if (x.optString("key").isNotEmpty()) { pn.put("key", x.optString("key").trim()); FLog.addSecret(x.optString("key").trim()) } // пустой ключ — «не менять»
            }
            o.put("llm", n)
        }
        // темы и правила чата заменяются целиком: пропущенная тема считается удалённой
        patch.optJSONObject("chat")?.let { c ->
            val n = JSONObject()
            n.put("rules", c.optString("rules").trim())
            n.put("topics", JSONObject((c.optJSONObject("topics") ?: JSONObject()).toString()))
            o.put("chat", n)
        }
        patch.optJSONObject("telegram")?.let { t ->
            val n = JSONObject((o.optJSONObject("telegram") ?: JSONObject()).toString())
            if (t.has("chatId")) n.put("chatId", t.optString("chatId").trim())
            if (t.optString("token").isNotEmpty()) n.put("token", t.optString("token").trim()) // пустой токен означает «не менять»
            o.put("telegram", n)
            FLog.addSecret(telegramToken)
        }
        if (patch.has("netTimeoutSec")) o.put("netTimeoutSec", patch.optInt("netTimeoutSec"))
        // голос и зоны заменяются целиком: пропущенный ключ значит «по умолчанию»
        patch.optJSONObject("voice")?.let { o.put("voice", JSONObject(it.toString())) }
        patch.optJSONObject("zones")?.let { o.put("zones", JSONObject(it.toString())) }
        patch.optJSONObject("camera")?.let { src ->
            val dst = o.optJSONObject("camera") ?: JSONObject().also { o.put("camera", it) }
            for (k in listOf("intervalMs", "cellThreshold", "rotation")) if (src.has(k)) dst.put(k, src.optInt(k))
            if (src.has("areaPercent")) dst.put("areaPercent", src.optDouble("areaPercent"))
            if (src.has("keepRunning")) dst.put("keepRunning", src.optBoolean("keepRunning"))
            if (src.has("resolution")) dst.put("resolution", src.optString("resolution"))
        }
        patch.optJSONObject("proxy")?.let { src ->
            val dst = o.optJSONObject("proxy") ?: JSONObject().also { o.put("proxy", it) }
            if (src.has("host")) dst.put("host", src.optString("host").trim())
            if (src.has("port")) dst.put("port", src.optInt("port"))
            if (src.has("user")) dst.put("user", src.optString("user"))
            for (n in PURPOSES) if (src.has(n)) dst.put(n, src.optBoolean(n))
            if (src.optString("password").isNotEmpty()) {
                dst.put("password", src.optString("password"))
                FLog.addSecret(src.optString("password"))
            }
            if (src.optBoolean("clearPassword")) dst.remove("password")
        }
        // Пустой пароль в запросе означает «не менять».
        if (patch.has("password") && patch.optString("password").isNotEmpty() && patch.optString("password") != password) {
            o.put("password", patch.optString("password"))
            FLog.addSecret(password)
            Sessions.clear() // со сменой пароля все выполненные входы теряют силу
        }
        save()
        return Result(emptyList(), restart)
    }
}
