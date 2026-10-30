package com.ffps.fserv

import java.io.File
import java.net.Authenticator
import java.net.HttpURLConnection
import java.net.InetSocketAddress
import java.net.PasswordAuthentication
import java.net.Proxy
import java.net.URL

/**
 * Исходящие HTTP(S)-запросы. Для каждого назначения (telegram, llm, updates) можно включить свой SOCKS5-прокси
 * с логином и паролем. Имя хоста передаётся прокси как есть, то есть DNS-запрос выполняет прокси, а не устройство.
 */
object Net {
    class Resp(val code: Int, val body: String, val via: String)

    fun init() {
        Authenticator.setDefault(object : Authenticator() {
            override fun getPasswordAuthentication(): PasswordAuthentication? {
                if (requestingProtocol?.startsWith("SOCKS", ignoreCase = true) != true) return null
                val all = Settings.allProxies()
                val p = all.firstOrNull { it.port == requestingPort && it.host.equals(requestingHost, true) }
                    ?: all.firstOrNull { it.port == requestingPort }
                    ?: return null
                if (p.user.isEmpty()) return null
                return PasswordAuthentication(p.user, p.password.toCharArray())
            }
        })
    }

    /**
     * Выполняет запрос. Если [saveTo] задан, тело ответа пишется в файл (для скачивания), [onProgress] получает
     * число скачанных байт и общий размер (или -1). Ошибки сети выбрасываются как исключения с понятным текстом.
     */
    fun request(
        purpose: String,
        method: String,
        url: String,
        body: ByteArray? = null,
        contentType: String? = null,
        headers: Map<String, String> = emptyMap(),
        timeoutMs: Int = 0,
        saveTo: File? = null,
        onProgress: ((Long, Long) -> Unit)? = null
    ): Resp {
        val tmo = if (timeoutMs > 0) timeoutMs else Settings.netTimeoutSec * 1000
        val px = Settings.proxyCfg(purpose)
        val proxy = if (px != null) Proxy(Proxy.Type.SOCKS, InetSocketAddress.createUnresolved(px.host, px.port)) else Proxy.NO_PROXY
        val via = if (px != null) "SOCKS5 ${px.host}:${px.port}" else "напрямую"
        val c = URL(url).openConnection(proxy) as HttpURLConnection
        val t0 = System.currentTimeMillis()
        FLog.d("net", "$method ${url.substringBefore("?").take(120)} [$purpose, $via, таймаут ${tmo / 1000} с]")
        try {
            c.setRequestProperty("Connection", "close")
            c.requestMethod = method
            c.connectTimeout = tmo
            c.readTimeout = tmo
            c.instanceFollowRedirects = true
            c.setRequestProperty("User-Agent", "fServe/${BuildConfig.VERSION_NAME}")
            for ((k, v) in headers) c.setRequestProperty(k, v)
            if (body != null) {
                c.doOutput = true
                if (contentType != null) c.setRequestProperty("Content-Type", contentType)
                c.setFixedLengthStreamingMode(body.size)
                c.outputStream.use { it.write(body) }
            }
            val code = c.responseCode
            FLog.d("net", "ответ HTTP $code за ${System.currentTimeMillis() - t0} мс ($purpose)")
            val stream = if (code in 200..399) c.inputStream else c.errorStream
            if (saveTo != null && code in 200..299) {
                val total = c.contentLengthLong
                var done = 0L
                saveTo.parentFile?.mkdirs()
                stream.use { ins ->
                    saveTo.outputStream().use { out ->
                        val buf = ByteArray(64 * 1024)
                        while (true) {
                            val n = ins.read(buf)
                            if (n < 0) break
                            out.write(buf, 0, n)
                            done += n
                            onProgress?.invoke(done, total)
                        }
                    }
                }
                return Resp(code, "", via)
            }
            val text = if (stream == null) "" else String(readAll(stream), Charsets.UTF_8)
            return Resp(code, text, via)
        } catch (e: java.io.IOException) {
            FLog.w("net", "$method ${url.substringBefore("?").take(120)} [$purpose, $via] не удалось за ${System.currentTimeMillis() - t0} мс: ${e.javaClass.simpleName}: ${e.message}")
            throw java.io.IOException("$via: ${e.javaClass.simpleName}: ${e.message}", e)
        } finally {
            c.disconnect()
        }
    }

    /**
     * Читает тело ответа. Платформенный OkHttp на старых Android (8.0) при закрытии потока после последнего
     * байта иногда бросает NullPointerException: данные к этому моменту уже получены, поэтому ошибка при закрытии
     * после успешного чтения игнорируется. Иначе запрос выглядел бы неудавшимся при уже доставленном сообщении.
     */
    private fun readAll(ins: java.io.InputStream): ByteArray {
        val out = java.io.ByteArrayOutputStream()
        val buf = ByteArray(8192)
        try {
            while (true) {
                val n = ins.read(buf)
                if (n < 0) break
                out.write(buf, 0, n)
            }
        } catch (e: java.io.IOException) {
            throw e
        } catch (e: RuntimeException) {
            if (out.size() == 0) throw java.io.IOException("сбой чтения ответа: ${e.javaClass.simpleName}", e)
            FLog.w("net", "ошибка платформы при завершении чтения ответа (данные получены): ${e.javaClass.simpleName}")
        }
        try { ins.close() } catch (e: Exception) {}
        return out.toByteArray()
    }
}
