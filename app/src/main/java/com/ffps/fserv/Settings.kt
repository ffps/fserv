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
        // До 0.4.1 было по прокси на назначение; оставляем первый заданный, галочки берём из «включён».
        if (o.has("proxies") && !o.has("proxy")) {
            val old = o.getJSONObject("proxies")
            var first: JSONObject? = null
            for (n in listOf("telegram", "llm", "updates")) {
                val x = old.optJSONObject(n) ?: continue
                if (first == null && x.optString("host").isNotBlank()) first = x
            }
            if (first != null) {
                val np = JSONObject(first.toString())
                np.remove("enabled")
                for (n in listOf("telegram", "llm", "updates")) {
                    val x = old.optJSONObject(n)
                    np.put(n, x != null && x.optBoolean("enabled") && x.optString("host").isNotBlank())
                }
                o.put("proxy", np)
            }
            o.remove("proxies")
            save()
        }
        if (o.optInt("schema", 0) < 2) {
            o.remove("password")
            o.put("serviceEnabled", false)
            o.put("schema", 2)
            save()
        }
    }

    /**
     * Восстановление из резервной копии: настройки из копии заменяют текущие, кроме состояния службы,
     * версии схемы и отметок последнего срабатывания расписаний (иначе после перезапуска расписание сработает повторно).
     */
    @Synchronized
    fun replaceAll(n: JSONObject) {
        for (k in listOf("serviceEnabled", "schema", "lastFired")) {
            if (o.has(k)) n.put(k, o.get(k)) else n.remove(k)
        }
        n.put("schema", 2)
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
        for (k in listOf("password", "webPort", "autostart", "serviceEnabled", "schema", "lastFired")) if (o.has(k)) n.put(k, o.get(k))
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
                "updates" to (p?.optBoolean("updates") ?: false)
            )
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
        patch.optJSONObject("camera")?.let { c ->
            if (c.has("intervalMs") && c.optInt("intervalMs", -1) !in 200..60000) errors.add("camera.intervalMs: от 200 до 60000 мс")
            if (c.has("cellThreshold") && c.optInt("cellThreshold", -1) !in 1..100) errors.add("camera.cellThreshold: от 1 до 100")
            if (c.has("areaPercent") && (c.optDouble("areaPercent", -1.0) < 0.05 || c.optDouble("areaPercent", -1.0) > 100.0)) errors.add("camera.areaPercent: от 0.05 до 100")
            if (c.has("rotation") && c.optInt("rotation", -2) !in listOf(-1, 0, 90, 180, 270)) errors.add("camera.rotation: -1 (авто), 0, 90, 180 или 270")
            if (c.has("resolution") && !Regex("""\d{3,4}x\d{3,4}""").matches(c.optString("resolution"))) errors.add("camera.resolution: вида 640x480")
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
        if (patch.has("netTimeoutSec")) o.put("netTimeoutSec", patch.optInt("netTimeoutSec"))
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
        }
        save()
        return Result(emptyList(), restart)
    }
}
