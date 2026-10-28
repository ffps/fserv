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
        // До версии 0.3.0 пароль генерировался сам. Теперь его вводит пользователь,
        // поэтому сгенерированный пароль и состояние службы сбрасываются один раз.
        if (o.optInt("schema", 0) < 2) {
            o.remove("password")
            o.put("serviceEnabled", false)
            o.put("schema", 2)
            save()
        }
    }

    private fun save() {
        val tmp = File(file.parentFile, "settings.json.tmp")
        tmp.writeText(o.toString(2))
        if (!tmp.renameTo(file)) {
            file.writeText(o.toString(2))
            tmp.delete()
        }
    }

    @get:Synchronized val webPort: Int get() = o.optInt("webPort", 8080)
    @get:Synchronized val password: String get() = o.optString("password")
    @get:Synchronized val logLevel: String get() = o.optString("logLevel", "INFO")
    @get:Synchronized val appRestartCron: String get() = o.optString("appRestartCron", "")
    @get:Synchronized val rebootCron: String get() = o.optString("rebootCron", "")

    /** Запускать службу при включении устройства. */
    @get:Synchronized val autostart: Boolean get() = o.optBoolean("autostart", false)

    /** Желаемое состояние службы: true после START SERVICE, false после STOP SERVICE. */
    @get:Synchronized val serviceEnabled: Boolean get() = o.optBoolean("serviceEnabled", false)

    @get:Synchronized val telegramToken: String get() = o.optString("telegramToken")
    @get:Synchronized val telegramChatId: String get() = o.optString("telegramChatId")
    @get:Synchronized val updateRepo: String get() = o.optString("updateRepo", "ffps/fserv")
    @get:Synchronized val updateCheckCron: String get() = o.optString("updateCheckCron", "0 4 * * *")

    val telegramConfigured: Boolean get() = telegramToken.isNotEmpty() && telegramChatId.isNotEmpty()

    @Synchronized
    fun getStr(key: String): String = o.optJSONObject("state")?.optString(key, "") ?: ""

    @Synchronized
    fun putStr(key: String, v: String) {
        val st = o.optJSONObject("state") ?: JSONObject().also { o.put("state", it) }
        st.put(key, v)
        save()
    }

    /** SOCKS5-прокси для назначения (telegram, llm, updates) или null, если выключен. */
    class ProxyCfg(val host: String, val port: Int, val user: String, val password: String)

    @Synchronized
    fun proxyCfg(purpose: String): ProxyCfg? {
        val p = o.optJSONObject("proxies")?.optJSONObject(purpose) ?: return null
        if (!p.optBoolean("enabled") || p.optString("host").isBlank()) return null
        return ProxyCfg(p.optString("host").trim(), p.optInt("port", 1080), p.optString("user"), p.optString("password"))
    }

    @Synchronized
    fun allProxies(): List<ProxyCfg> = PURPOSES.mapNotNull { proxyCfg(it) }

    val PURPOSES = listOf("telegram", "llm", "updates")

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
        "proxies" to JSONObject().also { px ->
            for (n in PURPOSES) {
                val p = o.optJSONObject("proxies")?.optJSONObject(n)
                px.put(n, obj(
                    "enabled" to (p?.optBoolean("enabled") ?: false),
                    "host" to (p?.optString("host") ?: ""),
                    "port" to (p?.optInt("port", 1080) ?: 1080),
                    "user" to (p?.optString("user") ?: ""),
                    "passwordSet" to !(p?.optString("password").isNullOrEmpty())
                ))
            }
        },
        "updateRepo" to updateRepo,
        "updateCheckCron" to updateCheckCron
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
        for (k in listOf("appRestartCron", "rebootCron", "updateCheckCron")) {
            if (patch.has(k)) {
                val v = patch.optString(k).trim()
                if (v.isNotEmpty()) {
                    try {
                        Cron.parse(v)
                    } catch (e: IllegalArgumentException) {
                        errors.add("$k: ${e.message}")
                    }
                }
            }
        }
        if (patch.has("telegramToken") && patch.optString("telegramToken").isNotEmpty() &&
            !Regex("""\d{5,}:[A-Za-z0-9_-]{20,}""").matches(patch.optString("telegramToken").trim())) {
            errors.add("telegramToken: ожидается вид 123456789:AAxxxxxxxx (от BotFather)")
        }
        if (patch.has("telegramChatId") && patch.optString("telegramChatId").isNotBlank() &&
            !Regex("""-?\d+|@[A-Za-z0-9_]{4,}""").matches(patch.optString("telegramChatId").trim())) {
            errors.add("telegramChatId: число (для группы со знаком минус) или @имя_канала")
        }
        if (patch.has("updateRepo") && !Regex("""[\w.-]+/[\w.-]+""").matches(patch.optString("updateRepo").trim())) {
            errors.add("updateRepo: ожидается владелец/репозиторий")
        }
        patch.optJSONObject("proxies")?.let { px ->
            for (n in px.keys()) {
                if (n !in PURPOSES) { errors.add("proxies.$n: неизвестное назначение"); continue }
                val p = px.optJSONObject(n) ?: continue
                if (p.has("port") && p.optInt("port", -1) !in 1..65535) errors.add("proxies.$n.port: число от 1 до 65535")
                if (p.optBoolean("enabled") && p.has("host") && p.optString("host").isBlank()) errors.add("proxies.$n.host: пусто")
            }
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
        for (k in listOf("appRestartCron", "rebootCron")) {
            if (patch.has(k)) o.put(k, patch.optString(k).trim())
        }
        if (patch.has("autostart")) o.put("autostart", patch.optBoolean("autostart"))
        if (patch.has("updateCheckCron")) o.put("updateCheckCron", patch.optString("updateCheckCron").trim())
        if (patch.has("updateRepo")) o.put("updateRepo", patch.optString("updateRepo").trim())
        if (patch.has("telegramChatId")) o.put("telegramChatId", patch.optString("telegramChatId").trim())
        // Пустой токен означает «не менять».
        if (patch.optString("telegramToken").isNotEmpty()) {
            o.put("telegramToken", patch.optString("telegramToken").trim())
            FLog.addSecret(telegramToken)
        }
        patch.optJSONObject("proxies")?.let { px ->
            val all = o.optJSONObject("proxies") ?: JSONObject().also { o.put("proxies", it) }
            for (n in px.keys()) {
                val src = px.optJSONObject(n) ?: continue
                val dst = all.optJSONObject(n) ?: JSONObject().also { all.put(n, it) }
                if (src.has("enabled")) dst.put("enabled", src.optBoolean("enabled"))
                if (src.has("host")) dst.put("host", src.optString("host").trim())
                if (src.has("port")) dst.put("port", src.optInt("port"))
                if (src.has("user")) dst.put("user", src.optString("user"))
                if (src.optString("password").isNotEmpty()) {
                    dst.put("password", src.optString("password"))
                    FLog.addSecret(src.optString("password"))
                }
                if (src.optBoolean("clearPassword")) dst.remove("password")
            }
        }
        // Пустой пароль в запросе означает «не менять».
        if (patch.has("password") && patch.optString("password").isNotEmpty() && patch.optString("password") != password) {
            o.put("password", patch.optString("password"))
            FLog.addSecret(password)
        }
        save()
        return Result(emptyList(), restart)
    }
}
