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
        timeoutMs: Int = 20_000,
        saveTo: File? = null,
        onProgress: ((Long, Long) -> Unit)? = null
    ): Resp {
        val px = Settings.proxyCfg(purpose)
        val proxy = if (px != null) Proxy(Proxy.Type.SOCKS, InetSocketAddress.createUnresolved(px.host, px.port)) else Proxy.NO_PROXY
        val via = if (px != null) "SOCKS5 ${px.host}:${px.port}" else "напрямую"
        val c = URL(url).openConnection(proxy) as HttpURLConnection
        try {
            c.requestMethod = method
            c.connectTimeout = timeoutMs
            c.readTimeout = timeoutMs
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
            val text = stream?.use { String(it.readBytes(), Charsets.UTF_8) } ?: ""
            return Resp(code, text, via)
        } catch (e: java.io.IOException) {
            throw java.io.IOException("$via: ${e.javaClass.simpleName}: ${e.message}", e)
        } finally {
            c.disconnect()
        }
    }
}
