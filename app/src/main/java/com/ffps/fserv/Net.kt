package com.ffps.fserv

import java.io.File
import java.net.Authenticator
import java.net.InetSocketAddress
import java.net.PasswordAuthentication
import java.net.Proxy
import okhttp3.ConnectionPool
import okhttp3.MediaType.Companion.toMediaTypeOrNull
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.util.concurrent.TimeUnit

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
     * Выполняет запрос через встроенный в приложение OkHttp (а не через платформенный: в Android 8.0 он бросает
     * NullPointerException при закрытии соединения, из-за чего удачные запросы выглядели как сбои).
     * Если [saveTo] задан, тело ответа пишется в файл (для скачивания), [onProgress] получает число скачанных байт
     * и общий размер (или -1). Ошибки сети выбрасываются как IOException с понятным текстом.
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
        val t0 = System.currentTimeMillis()
        FLog.d("net", "$method ${url.substringBefore("?").take(120)} [$purpose, $via, таймаут ${tmo / 1000} с]")
        val client = OkHttpClient.Builder()
            .proxy(proxy)
            .connectTimeout(tmo.toLong(), TimeUnit.MILLISECONDS)
            .readTimeout(tmo.toLong(), TimeUnit.MILLISECONDS)
            .writeTimeout(tmo.toLong(), TimeUnit.MILLISECONDS)
            .retryOnConnectionFailure(false)
            .connectionPool(ConnectionPool(0, 1, TimeUnit.SECONDS))
            .build()
        try {
            val rb = Request.Builder().url(url)
                .header("Connection", "close")
                .header("User-Agent", "fServe/${BuildConfig.VERSION_NAME}")
            for ((k, v) in headers) rb.header(k, v)
            val reqBody = if (body != null) body.toRequestBody(contentType?.toMediaTypeOrNull())
                else if (method == "POST" || method == "PUT") ByteArray(0).toRequestBody(null) else null
            rb.method(method, reqBody)
            client.newCall(rb.build()).execute().use { resp ->
                val code = resp.code
                FLog.d("net", "ответ HTTP $code за ${System.currentTimeMillis() - t0} мс ($purpose)")
                val rbody = resp.body
                if (saveTo != null && code in 200..299 && rbody != null) {
                    val total = rbody.contentLength()
                    var done = 0L
                    saveTo.parentFile?.mkdirs()
                    rbody.byteStream().use { ins ->
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
                return Resp(code, rbody?.string() ?: "", via)
            }
        } catch (e: java.io.IOException) {
            FLog.w("net", "$method ${url.substringBefore("?").take(120)} [$purpose, $via] не удалось за ${System.currentTimeMillis() - t0} мс: ${e.javaClass.simpleName}: ${e.message}")
            throw java.io.IOException("$via: ${e.javaClass.simpleName}: ${e.message}", e)
        } catch (e: RuntimeException) {
            FLog.w("net", "$method ${url.substringBefore("?").take(120)} [$purpose, $via] сбой: ${e.javaClass.simpleName}: ${e.message}")
            throw java.io.IOException("$via: ${e.javaClass.simpleName}: ${e.message}", e)
        } finally {
            client.dispatcher.executorService.shutdown()
            client.connectionPool.evictAll()
        }
    }
}
