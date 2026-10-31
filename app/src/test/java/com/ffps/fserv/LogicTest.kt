package com.ffps.fserv

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import java.util.Random

class LogicTest {
    private fun ms(v: Any?) = Dur.parse(v, "t").let { assertEquals(it.minMs, it.maxMs); it.minMs }

    @Test fun durations() {
        assertEquals(30_000L, ms("30s"))
        assertEquals(90_000L, ms("1m30s"))
        assertEquals(5_000L, ms(5))
        assertEquals(5_000L, ms("5"))
        assertEquals(500L, ms("500ms"))
        assertEquals(3_600_000L, ms("1h"))
        assertEquals(0L, ms(0))
        val r = Dur.parse("30s..2m", "t")
        assertEquals(30_000L, r.minMs); assertEquals(120_000L, r.maxMs); assertTrue(r.isRange)
        val rnd = Random(1)
        repeat(200) { val x = r.sample(rnd); assertTrue(x in 30_000L..120_000L) }
        val swapped = Dur.parse("2m..30s", "t")
        assertEquals(30_000L, swapped.minMs)
        for (bad in listOf("abc", "5x", "", "1m foo", -1, true)) {
            try { Dur.parse(bad, "t"); fail("должно быть ошибкой: $bad") } catch (e: IllegalArgumentException) {}
        }
    }

    @Test fun lists() {
        val l = Dur.parseList("3s, 5s,10s,20s", "t")
        val r = Random(1)
        assertEquals(listOf(3000L, 5000L, 10000L, 20000L, 20000L, 20000L), (0..5).map { Dur.pick(l, it, r) })
        assertEquals(2, Dur.parseList(org.json.JSONArray("[\"1m\", \"30s..40s\"]"), "t").size)
        assertEquals(1, Dur.parseList(7, "t").size)
    }

    @Test fun telegramHtml() {
        assertEquals("OK <b>да</b> &lt;5 &amp; <i>x</i>", Telegram.sanitize("OK <b>да</b> <5 & <i>x</i>"))
        assertEquals("&lt;b&gt;x", Telegram.sanitize("<b>x"))
        assertEquals("&lt;script&gt;a&lt;/script&gt;", Telegram.sanitize("<script>a</script>"))
        assertEquals("<a href=\"https://x.y\">s</a>", Telegram.sanitize("<a href=\"https://x.y\">s</a>"))
        assertEquals("&lt; уже &amp; ещё", Telegram.sanitize("&lt; уже & ещё"))
    }

    @Test fun counts() {
        assertEquals(6..6, Dur.parseCount(6, "t"))
        assertEquals(3..5, Dur.parseCount("3..5", "t"))
        assertEquals(3..5, Dur.parseCount("5..3", "t"))
        try { Dur.parseCount(0, "t"); fail() } catch (e: IllegalArgumentException) {}
        try { Dur.parseCount("x", "t"); fail() } catch (e: IllegalArgumentException) {}
    }

    @Test fun expressions() {
        val v = mapOf<String, Any?>("moved" to true, "still" to false, "bp" to 150, "name" to "a")
        assertTrue(Expr.eval("moved", v))
        assertTrue(Expr.eval("not still", v))
        assertFalse(Expr.eval("not moved", v))
        assertTrue(Expr.eval("moved and not still", v))
        assertTrue(Expr.eval("still or moved", v))
        assertTrue(Expr.eval("(still or moved) and bp > 140", v))
        assertFalse(Expr.eval("bp < 100", v))
        assertTrue(Expr.eval("bp >= 150 and bp <= 150", v))
        assertTrue(Expr.eval("name == 'a'", v))
        assertTrue(Expr.eval("name != \"b\"", v))
        assertFalse(Expr.eval("missing", v))
        assertFalse(Expr.eval("missing > 3", v))
        assertEquals(setOf("moved", "bp"), Expr.idents("moved and bp > 3"))
        for (bad in listOf("", "moved and", "(moved", "a = b", "moved moved", "and")) {
            try { Expr.parse(bad); fail("должно быть ошибкой: $bad") } catch (e: IllegalArgumentException) {}
        }
    }

    @Test fun zoneMaskMath() {
        // проверка выбора ячеек не требует Android: логика в maskFromZone использует только org.json
        val z = JSONObject("""{"rect": [0.0, 0.0, 0.5, 1.0]}""")
        val m = CameraEngine.maskFromZone(z)
        assertTrue(m[0]); assertFalse(m[CameraEngine.GW - 1])
    }
}
