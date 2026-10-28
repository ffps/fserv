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

    /** Запускать службу при включении планшета. */
    @get:Synchronized val autostart: Boolean get() = o.optBoolean("autostart", false)

    /** Желаемое состояние службы: true после START SERVICE, false после STOP SERVICE. */
    @get:Synchronized val serviceEnabled: Boolean get() = o.optBoolean("serviceEnabled", false)

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
        "autostart" to autostart
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
        for (k in listOf("appRestartCron", "rebootCron")) {
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
        // Пустой пароль в запросе означает «не менять».
        if (patch.has("password") && patch.optString("password").isNotEmpty() && patch.optString("password") != password) {
            o.put("password", patch.optString("password"))
            FLog.addSecret(password)
        }
        save()
        return Result(emptyList(), restart)
    }
}
