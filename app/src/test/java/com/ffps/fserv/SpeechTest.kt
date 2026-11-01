package com.ffps.fserv

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class SpeechTest {
    @Test fun numbers() {
        assertEquals(125, Speech.number("сто двадцать пять"))
        assertEquals(0, Speech.number("ноль"))
        assertEquals(7, Speech.number("семь"))
        assertEquals(15, Speech.number("пятнадцать"))
        assertEquals(90, Speech.number("девяносто"))
        assertEquals(212, Speech.number("двести двенадцать"))
        assertEquals(140, Speech.number("Сто сорок [unk]"))
        assertEquals(120, Speech.number("давление сто двадцать на восемьдесят"))
        assertEquals(120, Speech.number("120"))
        assertEquals(2, Speech.number("две"))
        assertEquals(20, Speech.number("двадцать тридцать"))
        assertEquals(null, Speech.number("привет"))
        assertEquals(null, Speech.number(""))
        assertEquals(null, Speech.number("1500"))
    }

    @Test fun yesNo() {
        assertEquals(true, Speech.yesNo("да"))
        assertEquals(true, Speech.yesNo("Да, конечно"))
        assertEquals(false, Speech.yesNo("нет"))
        assertEquals(false, Speech.yesNo("[unk] не [unk]"))
        assertEquals(true, Speech.yesNo("да нет"))
        assertNull(Speech.yesNo("может быть"))
        assertNull(Speech.yesNo(""))
    }

    @Test fun parseAndGrammar() {
        assertEquals(130, Speech.parse("number", "сто тридцать"))
        assertEquals(true, Speech.parse("yesno", "ага"))
        assertTrue(Speech.grammar("number").containsAll(listOf("сто", "двадцать", "пять", "[unk]")))
        assertTrue(Speech.grammar("yesno").containsAll(listOf("да", "нет", "[unk]")))
    }

    @Test fun askStepIsValidated() {
        fun ok(vararg steps: String) = Script.validate("task cron=\"0 9 * * *\"\n" + steps.joinToString("\n") { "  $it" })
        fun bad(part: String, vararg steps: String) { val e = ok(*steps); assertTrue("ожидалась ошибка с «$part»: $e", e != null && e.contains(part)) }
        assertNull(ok("ask \"Какое давление?\" assign=bp kind=number listen=20s retries=2 again=\"Ещё раз, {task}\"", "if bp > 140", "  say \"Высокое: {bp}\""))
        assertNull(ok("ask \"Вы приняли таблетки?\" assign=pills", "if not pills", "  say \"Примите таблетки\""))
        bad("assign", "ask \"Вопрос?\"")
        bad("kind", "ask \"Вопрос?\" assign=x kind=word")
        bad("listen", "ask \"Вопрос?\" assign=x listen=1s")
        bad("retries", "ask \"Вопрос?\" assign=x retries=9")
        bad("в кавычках", "ask Вопрос assign=x")
        bad("у ask нет параметра", "ask \"Вопрос?\" assign=x speed=3")
        bad("внутри background", "background", "  ask \"Вопрос?\" assign=x")
        bad("переменной «nope»", "if nope", "  say \"a\"")
        bad("занято", "ask \"Вопрос?\" assign=time")
    }
}
