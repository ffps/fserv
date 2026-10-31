package com.ffps.fserv

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
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

    private val example = """
    {
      "version": 1,
      "voice": {"lang": "ru-RU", "rate": 0.95, "volume": 80},
      "zones": {"bed": {"rect": [0.1, 0.2, 0.9, 0.9], "exclude": [[0.0, 0.0, 0.2, 0.2]]}},
      "tasks": [{
        "id": "wakeup", "title": "Подъём", "enabled": true, "cron": "0 9 * * *", "late_ok": "0", "timeout": "60m",
        "steps": [
          {"repeat": {"times": 6, "every": "5m..6m", "until": "moved"}, "do": [
            {"watch": {"motion": true, "for": "60s", "poll": "5s", "save_as": "moved", "threshold": 8, "area": 0.5, "zone": "bed"}},
            {"if": "not moved", "then": [
              {"play": ["alarm.mp3", "system:alarm"], "volume": 80, "fade_in": "5s", "fade_out": 0, "for": "20s"},
              {"say": "Доброе утро! Пора вставать."}
            ]}
          ]},
          {"if": "moved",
           "then": [{"say": "Доброе утро!"}, {"telegram": "Встала в {time}"}],
           "else": [{"telegram": "К {time} движения нет"}]},
          {"delay": "30s..2m"},
          {"log": "готово {weekday}, {date}"}
        ]
      }]
    }""".trimIndent()

    private fun check(json: String, files: (String) -> Boolean = { true }): String? = TaskSchema.validate(JSONObject(json), files)

    @Test fun exampleIsValid() {
        assertNull(check(example))
    }

    private fun expectError(json: String, part: String, files: (String) -> Boolean = { true }) {
        val e = check(json, files)
        assertNotNull("ожидалась ошибка с «$part»", e)
        assertTrue("ошибка «$e» не содержит «$part»", e!!.contains(part))
    }

    private fun task(steps: String, extra: String = "") = """{"tasks": [{"id": "t", "cron": "0 9 * * *", "steps": $steps $extra}]}"""

    @Test fun schemaErrors() {
        expectError("""{"tsks": []}""", "неизвестное поле")
        expectError("""{"tasks": [{"id": "a"}]}""", "steps")
        expectError("""{"tasks": [{"id": "a", "steps": [], "crn": "x"}]}""", "crn")
        expectError("""{"tasks": [{"id": "a", "cron": "61 * * * *", "steps": []}]}""", "cron")
        expectError("""{"tasks": [{"id": "a", "steps": []}, {"id": "a", "steps": []}]}""", "повторяется")
        expectError(task("""[{"say": "x", "telegram": "y"}]"""), "два действия")
        expectError(task("""[{"sai": "x"}]"""), "не найдено действие")
        expectError(task("""[{"say": "{nope}"}]"""), "{nope}")
        expectError(task("""[{"if": "ghost", "then": []}]"""), "ghost")
        expectError(task("""[{"delay": "5x"}]"""), "длительность")
        expectError(task("""[{"play": "a.mp3"}]"""), "a.mp3", files = { false })
        expectError(task("""[{"play": "system:alarm", "loop": true}]"""), "for")
        // в фоновом режиме loop без for допустим, звук гасит stop_background
        assertNull(check(task("""[{"play": "system:alarm", "loop": true, "wait": false}, {"stop_background": true}]""")))
        expectError(task("""[{"stop_background": "yes"}]"""), "stop_background")
        assertNull(check(task("""[{"say": "привет", "wait": false}, {"photo": true}]""")))
        assertNull(check(task("""[{"background": [{"play": "system:alarm", "loop": true, "pause": "3s,5s"}, {"delay": "10s"}, {"say": "вставай"}]}, {"delay": "1s"}]""")))
        expectError(task("""[{"background": [{"watch": {"motion": true, "for": "5s"}}]}]"""), "внутри background")
        expectError(task("""[{"background": [{"say": "x", "wait": false}]}]"""), "внутри background")
        expectError(task("""[{"background": []}]"""), "пуст")
        expectError(task("""[{"wait": "5s"}]"""), "не найдено действие")
        assertNull(check(task("""[{"play": "system:alarm", "loop": true, "for": "1m", "pause": "3s,5s,10s..20s"}]""")))
        assertNull(check(task("""[{"repeat": {"times": 4, "every": ["3m", "1m"]}, "do": [{"log": "x"}]}]""")))
        expectError(task("""[{"play": "system:alarm", "pause": "3s"}]"""), "loop")
        expectError(task("""[{"play": "system:alarm", "loop": true, "for": "1m", "pause": "3s,xx"}]"""), "длительность")
        expectError(task("""[{"play": "system:alarm", "fade": 1}]"""), "лишнее поле")
        expectError(task("""[{"repeat": {"every": "1m"}, "do": []}]"""), "times")
        expectError(task("""[{"repeat": {"times": 2}}]"""), "do")
        expectError(task("""[{"watch": {"motion": true, "save_as": "m"}}]"""), "for")
        expectError(task("""[{"watch": {"motion": true, "for": "5s", "zone": "z"}}]"""), "zones")
        expectError(task("""[{"watch": {"motion": true, "for": "5s", "threshold": 500}}]"""), "threshold")
        expectError(task("""[{"ask": {"say": "?"}}]"""), "этапе 5")
        expectError(task("""[{"say": "x", "volume": 300}]"""), "volume")
        expectError("""{"voice": {"volume": 300}}""", "volume")
        expectError("""{"zones": {"z": {"rect": [0.5, 0.5, 0.2, 0.2]}}}""", "x1 < x2")
    }

    @Test fun emptyConfigIsValid() {
        assertNull(check("""{"version": 1, "tasks": []}"""))
        assertNull(check("{}"))
    }

    @Test fun zoneMaskMath() {
        // проверка выбора ячеек не требует Android: логика в maskFromZone использует только org.json
        val z = JSONObject("""{"rect": [0.0, 0.0, 0.5, 1.0]}""")
        val m = CameraEngine.maskFromZone(z)
        assertTrue(m[0]); assertFalse(m[CameraEngine.GW - 1])
    }
}
