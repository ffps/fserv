package com.ffps.fserv

import org.junit.Assert.*
import org.junit.Test
import java.io.File

class SessionsTest {
    private fun tmp() = File.createTempFile("sessions", ".json").also { it.delete() }

    @Test fun createValidRevoke() {
        val f = tmp()
        Sessions.init(f)
        val t = Sessions.create(1000)
        assertTrue(Sessions.valid(t, 2000))
        assertFalse(Sessions.valid("не тот", 2000))
        assertFalse(Sessions.valid(null, 2000))
        assertFalse(Sessions.valid("", 2000))
        Sessions.revoke(t)
        assertFalse(Sessions.valid(t, 2000))
        f.delete()
    }

    @Test fun expiresAfterYearAndSurvivesRestart() {
        val f = tmp()
        Sessions.init(f)
        val t = Sessions.create(0)
        Sessions.init(f) // перезапуск приложения: читаем с диска
        assertTrue(Sessions.valid(t, Sessions.MAX_AGE_SEC * 1000 - 1))
        assertFalse(Sessions.valid(t, Sessions.MAX_AGE_SEC * 1000 + 1))
        f.delete()
    }

    @Test fun tokenNotStoredInClear() {
        val f = tmp()
        Sessions.init(f)
        val t = Sessions.create(0)
        assertFalse(f.readText().contains(t))
        Sessions.clear()
        assertFalse(Sessions.valid(t, 1))
        Sessions.init(f)
        assertFalse(Sessions.valid(t, 1))
        f.delete()
    }

    @Test fun limitsCount() {
        val f = tmp()
        Sessions.init(f)
        val first = Sessions.create(0)
        repeat(60) { Sessions.create(0) }
        assertFalse(Sessions.valid(first, 1))
        f.delete()
    }
}
