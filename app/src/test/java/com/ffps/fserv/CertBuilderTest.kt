package com.ffps.fserv

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.security.KeyPairGenerator
import java.security.SecureRandom

class CertBuilderTest {
    @Test fun selfSignedCertificateIsValid() {
        val kp = KeyPairGenerator.getInstance("RSA").also { it.initialize(2048, SecureRandom()) }.generateKeyPair()
        val c = CertBuilder.selfSigned(kp.public.encoded, kp.private, listOf("192.168.1.50", "10.0.0.7", "127.0.0.1"))
        c.verify(kp.public) // подпись сходится с открытым ключом
        c.checkValidity()
        assertEquals(3, c.version)
        assertEquals("CN=fServe", c.subjectX500Principal.name)
        assertEquals(-1, c.basicConstraints) // не центр сертификации
        val san = c.subjectAlternativeNames.map { it[0] as Int to it[1].toString() }
        assertTrue(san.contains(7 to "192.168.1.50"))
        assertTrue(san.contains(7 to "10.0.0.7"))
        assertTrue(san.contains(2 to "localhost"))
        assertEquals(listOf("1.3.6.1.5.5.7.3.1"), c.extendedKeyUsage)
        assertTrue(c.keyUsage[0] && c.keyUsage[2]) // digitalSignature, keyEncipherment
        java.io.File(System.getProperty("java.io.tmpdir"), "fserv-test-cert.der").writeBytes(c.encoded) // для проверки openssl вручную
    }
}
