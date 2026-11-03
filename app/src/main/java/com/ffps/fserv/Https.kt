package com.ffps.fserv

import android.content.Context
import java.io.File
import java.security.KeyPairGenerator
import java.security.KeyStore
import java.security.SecureRandom
import javax.net.ssl.KeyManagerFactory
import javax.net.ssl.SSLContext
import javax.net.ssl.SSLServerSocketFactory

/**
 * HTTPS для веб-интерфейса. Браузер даёт странице камеру и микрофон (звонок) только в защищённом контексте,
 * а страница по http://адрес-в-сети таким не является. Поэтому рядом с обычным портом поднимается HTTPS с самоподписанным
 * сертификатом на адреса устройства: браузер один раз предупредит, что сертификат не из доверенного центра, и после согласия работает.
 *
 * Сертификат (RSA 2048, SHA-256, срок 10 лет) собирается вручную в формате X.509 DER, без сторонних библиотек.
 * Он хранится в filesDir/https.p12 вместе со списком адресов (https.ips) и перевыпускается, если адреса устройства в нём не значатся.
 */
object Https {
    private const val PASS = "fserv-https"
    private const val ALIAS = "fserv"

    /** Фабрика серверных сокетов с сертификатом; создаёт сертификат при необходимости (несколько секунд на слабом устройстве). */
    @Synchronized
    fun socketFactory(ctx: Context): SSLServerSocketFactory {
        val p12 = File(ctx.filesDir, "https.p12")
        val meta = File(ctx.filesDir, "https.ips")
        val ips = (Util.ips() + "127.0.0.1").toSortedSet()
        var ks: KeyStore? = null
        try {
            if (p12.isFile && meta.isFile) {
                val known = meta.readText().split(",").filter { it.isNotBlank() }.toSet()
                if (known.containsAll(ips)) {
                    ks = KeyStore.getInstance("PKCS12").also { k -> p12.inputStream().use { k.load(it, PASS.toCharArray()) } }
                }
            }
        } catch (e: Exception) {
            FLog.w("web", "сертификат HTTPS не прочитан, выпускаю новый: ${e.message}")
            ks = null
        }
        if (ks == null) {
            val t0 = System.currentTimeMillis()
            // старые адреса сохраняем в списке, чтобы сертификат подходил и к ним
            val old = try { meta.readText().split(",").filter { it.isNotBlank() } } catch (e: Exception) { emptyList() }
            val all = (old + ips).toSortedSet()
            ks = KeyStore.getInstance("PKCS12")
            ks.load(null, null)
            val kp = KeyPairGenerator.getInstance("RSA").also { it.initialize(2048, SecureRandom()) }.generateKeyPair()
            val cert = CertBuilder.selfSigned(kp.public.encoded, kp.private, all.toList())
            ks.setKeyEntry(ALIAS, kp.private, PASS.toCharArray(), arrayOf(cert))
            val tmp = File(ctx.filesDir, "https.p12.tmp")
            tmp.outputStream().use { ks.store(it, PASS.toCharArray()) }
            tmp.renameTo(p12)
            meta.writeText(all.joinToString(","))
            FLog.i("web", "выпущен сертификат HTTPS для ${all.joinToString(", ")} за ${System.currentTimeMillis() - t0} мс")
        }
        val kmf = KeyManagerFactory.getInstance(KeyManagerFactory.getDefaultAlgorithm())
        kmf.init(ks, PASS.toCharArray())
        val sc = SSLContext.getInstance("TLS")
        sc.init(kmf.keyManagers, null, null)
        return sc.serverSocketFactory
    }
}
