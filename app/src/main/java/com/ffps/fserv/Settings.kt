package com.ffps.fserv

import android.content.Context
import org.json.JSONObject
import java.io.File
import java.security.SecureRandom

/**
 * Базовые настройки приложения (settings.json в приватной папке).
 * Токены, прокси и прочее добавятся на следующих этапах; сейчас: порт веб-интерфейса, пароль,
 * уровень лога и расписания автоперезапуска (cron).
 */
object Settings {
    private const val ALPHABET = "abcdefghjkmnpqrstuvwxyz23456789" // без похожих символов (l, 1, o, 0, i)
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
        if (o.optString("password").isEmpty()) {
            o.put("password", randomPassword())
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

    private fun randomPassword(): String {
        val r = SecureRandom()
        return (1..10).map { ALPHABET[r.nextInt(ALPHABET.length)] }.joinToString("")
    }

    @get:Synchronized val webPort: Int get() = o.optInt("webPort", 8080)
    @get:Synchronized val password: String get() = o.optString("password")
    @get:Synchronized val logLevel: String get() = o.optString("logLevel", "INFO")
    @get:Synchronized val appRestartCron: String get() = o.optString("appRestartCron", "")
    @get:Synchronized val rebootCron: String get() = o.optString("rebootCron", "")

    @Synchronized
    fun lastFired(kind: String): Long = o.optJSONObject("lastFired")?.optLong(kind, -1L) ?: -1L

    @Synchronized
    fun setLastFired(kind: String, minute: Long) {
        val lf = o.optJSONObject("lastFired") ?: JSONObject().also { o.put("lastFired", it) }
        lf.put(kind, minute)
        save()
    }

    /** Новый случайный пароль; возвращает его, чтобы показать пользователю один раз. */
    @Synchronized
    fun regeneratePassword(): String {
        val p = randomPassword()
        o.put("password", p)
        save()
        FLog.addSecret(p)
        return p
    }

    /** Настройки без пароля (для веб-интерфейса). */
    @Synchronized
    fun publicJson(): JSONObject = obj(
        "webPort" to webPort,
        "logLevel" to logLevel,
        "appRestartCron" to appRestartCron,
        "rebootCron" to rebootCron,
        "passwordSet" to true
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
        if (patch.has("password")) {
            val v = patch.optString("password")
            if (v.isNotEmpty() && v.length < 6) errors.add("password: не короче 6 символов")
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
        if (patch.has("password") && patch.optString("password").isNotEmpty()) {
            o.put("password", patch.optString("password"))
            FLog.addSecret(password)
        }
        save()
        return Result(emptyList(), restart)
    }
}
