package com.ffps.fserv

import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.math.BigInteger
import java.net.InetAddress
import java.security.PrivateKey
import java.security.SecureRandom
import java.security.Signature
import java.security.cert.CertificateFactory
import java.security.cert.X509Certificate

/** Самоподписанный сертификат X.509 v3, собранный вручную в DER (без сторонних библиотек); проверяется на JVM в CertBuilderTest. */
object CertBuilder {
    private fun len(n: Int): ByteArray = when {
        n < 0x80 -> byteArrayOf(n.toByte())
        n < 0x100 -> byteArrayOf(0x81.toByte(), n.toByte())
        else -> byteArrayOf(0x82.toByte(), (n shr 8).toByte(), n.toByte())
    }

    private fun tlv(tag: Int, content: ByteArray): ByteArray {
        val o = ByteArrayOutputStream()
        o.write(tag); o.write(len(content.size)); o.write(content)
        return o.toByteArray()
    }

    private fun cat(vararg parts: ByteArray): ByteArray {
        val o = ByteArrayOutputStream()
        for (p in parts) o.write(p)
        return o.toByteArray()
    }

    private fun seq(vararg parts: ByteArray) = tlv(0x30, cat(*parts))
    private fun set(vararg parts: ByteArray) = tlv(0x31, cat(*parts))
    private fun int(v: BigInteger) = tlv(0x02, v.toByteArray())
    private fun octets(b: ByteArray) = tlv(0x04, b)
    private fun bitString(b: ByteArray) = tlv(0x03, cat(byteArrayOf(0), b))
    private fun utf8(s: String) = tlv(0x0C, s.toByteArray(Charsets.UTF_8))
    private fun bool(v: Boolean) = tlv(0x01, byteArrayOf(if (v) 0xFF.toByte() else 0))

    private fun oid(s: String): ByteArray {
        val p = s.split(".").map { it.toLong() }
        val o = ByteArrayOutputStream()
        o.write((p[0] * 40 + p[1]).toInt())
        for (i in 2 until p.size) {
            var v = p[i]
            val stack = ArrayList<Int>()
            stack.add((v and 0x7F).toInt())
            v = v shr 7
            while (v > 0) { stack.add(((v and 0x7F) or 0x80).toInt()); v = v shr 7 }
            for (b in stack.reversed()) o.write(b)
        }
        return tlv(0x06, o.toByteArray())
    }

    private fun utcTime(ms: Long): ByteArray {
        val f = java.text.SimpleDateFormat("yyMMddHHmmss'Z'", java.util.Locale.US)
        f.timeZone = java.util.TimeZone.getTimeZone("UTC")
        return tlv(0x17, f.format(java.util.Date(ms)).toByteArray(Charsets.US_ASCII))
    }

    private fun extension(oid: String, critical: Boolean, value: ByteArray): ByteArray =
        if (critical) seq(oid(oid), bool(true), octets(value)) else seq(oid(oid), octets(value))

    fun selfSigned(publicKeyInfo: ByteArray, key: PrivateKey, ips: List<String>): X509Certificate {
        val sigAlg = seq(oid("1.2.840.113549.1.1.11"), byteArrayOf(0x05, 0x00)) // sha256WithRSAEncryption, параметры NULL
        val name = seq(set(seq(oid("2.5.4.3"), utf8("fServe"))))
        val now = System.currentTimeMillis()
        val validity = seq(utcTime(now - 24L * 3600_000), utcTime(now + 3650L * 24 * 3600_000))
        // SAN: адреса устройства (iPAddress, тег 7) и localhost (dNSName, тег 2)
        val names = ByteArrayOutputStream()
        for (ip in ips) {
            val a = try { InetAddress.getByName(ip).address } catch (e: Exception) { continue }
            if (a.size == 4) names.write(tlv(0x87, a))
        }
        names.write(tlv(0x82, "localhost".toByteArray(Charsets.US_ASCII)))
        val extensions = tlv(0xA3, seq(
            extension("2.5.29.17", false, tlv(0x30, names.toByteArray())),                 // subjectAltName
            extension("2.5.29.19", true, seq()),                                            // basicConstraints: не центр сертификации
            extension("2.5.29.15", true, tlv(0x03, byteArrayOf(0x05, 0xA0.toByte()))),            // keyUsage: digitalSignature, keyEncipherment
            extension("2.5.29.37", false, seq(oid("1.3.6.1.5.5.7.3.1")))                    // extKeyUsage: serverAuth
        ))
        val serial = BigInteger(64, SecureRandom()).abs().add(BigInteger.ONE)
        val tbs = seq(tlv(0xA0, int(BigInteger.valueOf(2))), int(serial), sigAlg, name, validity, name, publicKeyInfo, extensions)
        val sig = Signature.getInstance("SHA256withRSA").also { it.initSign(key); it.update(tbs) }.sign()
        val der = seq(tbs, sigAlg, bitString(sig))
        return CertificateFactory.getInstance("X.509").generateCertificate(ByteArrayInputStream(der)) as X509Certificate
    }
}
