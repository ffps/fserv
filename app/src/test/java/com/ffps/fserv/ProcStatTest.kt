package com.ffps.fserv

import org.junit.Assert.*
import org.junit.Test

class ProcStatTest {
    // поля после имени: state ppid pgrp session tty tpgid flags minflt cminflt majflt cmajflt utime stime …
    private fun line(name: String, u: Long, s: Long) = "1234 ($name) S 1 1 0 0 -1 4210944 100 0 0 0 $u $s 0 0 20 0 5 0 777 1000000 5000"

    @Test fun parse() {
        val t = ProcStat.parse(line("voice-trigger", 120, 30))!!
        assertEquals("voice-trigger", t.name)
        assertEquals(150L, t.ticks)
    }

    @Test fun parseNameWithSpacesAndParens() {
        val t = ProcStat.parse(line("OkHttp (api) x", 7, 3))!!
        assertEquals("OkHttp (api) x", t.name)
        assertEquals(10L, t.ticks)
    }

    @Test fun parseBroken() {
        assertNull(ProcStat.parse(""))
        assertNull(ProcStat.parse("1 (a) S 1 2"))
        assertNull(ProcStat.parse("1 a) S 1 2 3 4 5 6 7 8 9 10 11 12 13"))
    }

    @Test fun topGroupsByNameAndSorts() {
        val prev = mapOf(1 to ProcStat.T("voice-trigger", 100), 2 to ProcStat.T("OkHttp", 10), 3 to ProcStat.T("OkHttp", 20), 4 to ProcStat.T("idle", 5))
        val cur = mapOf(1 to ProcStat.T("voice-trigger", 160), 2 to ProcStat.T("OkHttp", 13), 3 to ProcStat.T("OkHttp", 22), 4 to ProcStat.T("idle", 5), 5 to ProcStat.T("new", 12))
        // период 10 с, 100 тиков в секунду: 1000 тиков = 100% одного ядра
        val top = ProcStat.top(prev, cur, 10_000, 100, 5)
        assertEquals(listOf("voice-trigger", "new", "OkHttp"), top.map { it.name })
        assertEquals(6.0, top[0].pct, 0.001)   // 60 тиков из 1000
        assertEquals(1.2, top[1].pct, 0.001)   // новый поток: всё его время за период
        assertEquals(0.5, top[2].pct, 0.001)   // (3+2) тиков
        assertEquals(2, top[2].count)
        assertEquals("voice-trigger 6%, new 1%, OkHttp 1% (×2)", ProcStat.format(top))
    }

    @Test fun topLimitsAndEmpty() {
        val cur = (1..10).associateWith { ProcStat.T("t$it", it * 100L) }
        assertEquals(3, ProcStat.top(emptyMap(), cur, 1000, 100, 3).size)
        assertEquals("нет заметной нагрузки", ProcStat.format(ProcStat.top(emptyMap(), emptyMap(), 1000, 100, 3)))
        assertTrue(ProcStat.top(emptyMap(), cur, 0, 100, 3).isEmpty())
        assertTrue(ProcStat.top(emptyMap(), cur, 1000, 0, 3).isEmpty())
    }
}
