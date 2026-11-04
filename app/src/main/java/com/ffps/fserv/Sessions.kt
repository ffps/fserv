package com.ffps.fserv

import org.json.JSONObject
import java.io.File
import java.security.MessageDigest
import java.security.SecureRandom

/**
 * Сессии веб-интерфейса после входа по паролю (cookie). Токен случайный, на диске хранится только его SHA-256 с временем окончания,
 * поэтому сессии переживают перезапуск приложения. Срок — год с момента входа. Смена пароля и восстановление из копии сбрасывают все сессии.
 */
object Sessions {
    const val MAX_AGE_SEC = 365L * 24 * 3600
    private const val MAX_SESSIONS = 50

    private var file: File? = null
    private val map = LinkedHashMap<String, Long>() // sha256(токен) → когда истекает (мс)
    private val rnd = SecureRandom()

    @Synchronized
    fun init(f: File) {
        file = f
        map.clear()
        try {
            if (f.exists()) {
                val o = JSONObject(f.readText())
                for (k in o.keys()) map[k] = o.getLong(k)
            }
        } catch (e: Exception) {
            map.clear()
        }
    }

    private fun hash(token: String): String =
        MessageDigest.getInstance("SHA-256").digest(token.toByteArray(Charsets.UTF_8)).joinToString("") { "%02x".format(it) }

    private fun save() {
        val f = file ?: return
        try {
            val o = JSONObject()
            for ((k, v) in map) o.put(k, v)
            val tmp = File(f.parentFile, f.name + ".tmp")
            tmp.writeText(o.toString())
            if (!tmp.renameTo(f)) f.writeText(o.toString())
        } catch (e: Exception) {
            // сессия останется только в памяти до перезапуска
        }
    }

    /** Создаёт сессию и возвращает токен для cookie. */
    @Synchronized
    fun create(now: Long = System.currentTimeMillis()): String {
        val b = ByteArray(32)
        rnd.nextBytes(b)
        val token = b.joinToString("") { "%02x".format(it) }
        map.entries.removeAll { it.value <= now }
        map[hash(token)] = now + MAX_AGE_SEC * 1000
        while (map.size > MAX_SESSIONS) map.remove(map.keys.first())
        save()
        return token
    }

    @Synchronized
    fun valid(token: String?, now: Long = System.currentTimeMillis()): Boolean {
        if (token.isNullOrEmpty() || token.length > 128) return false
        val exp = map[hash(token)] ?: return false
        if (exp <= now) { map.remove(hash(token)); save(); return false }
        return true
    }

    @Synchronized
    fun revoke(token: String?) {
        if (token.isNullOrEmpty()) return
        if (map.remove(hash(token)) != null) save()
    }

    @Synchronized
    fun clear() {
        if (map.isEmpty()) return
        map.clear()
        save()
    }
}
