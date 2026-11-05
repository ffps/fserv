package com.ffps.fserv

import org.junit.Assert.*
import org.junit.Test

class VoiceCmdTest {
    private fun bad(s: String): String = try { VoiceCmd.parsePhrases(s); fail("ожидалась ошибка для «$s»"); "" } catch (e: IllegalArgumentException) { e.message!! }

    @Test fun parsePhrases() {
        assertEquals(listOf("кузя поболтай со мной", "кузя ты здесь"), VoiceCmd.parsePhrases("Кузя, поболтай со мной|  Кузя, ты здесь?  "))
        assertEquals(listOf("все хорошо"), VoiceCmd.parsePhrases("Всё хорошо")) // «ё» → «е»
    }

    @Test fun parsePhrasesErrors() {
        assertTrue(bad("Кузя, привет||ещё фраза").contains("пустая"))
        assertTrue(bad("").contains("пустая"))
        assertTrue(bad("Kuzya privet").contains("русскими буквами"))
        assertTrue(bad("позвони по 112").contains("русскими буквами"))
        assertTrue(bad("да").contains("короткая"))
        assertTrue(bad("Кузя привет|кузя, привет!").contains("повторяется"))
        assertTrue(bad("а".repeat(81)).contains("длиннее"))
    }

    @Test fun matchContiguousWords() {
        val p = VoiceCmd.parsePhrases("Кузя, поболтай со мной|Кузя, ты здесь")
        assertEquals("кузя поболтай со мной", VoiceCmd.match("кузя поболтай со мной", p))
        assertEquals("кузя поболтай со мной", VoiceCmd.match("ну кузя поболтай со мной пожалуйста", p))
        assertEquals("кузя ты здесь", VoiceCmd.match("Кузя, ты здесь?", p))
        assertNull(VoiceCmd.match("кузя поболтай", p))
        assertNull(VoiceCmd.match("поболтай со мной кузя", p)) // порядок слов важен
        assertNull(VoiceCmd.match("", p))
        assertNull(VoiceCmd.match("кузя", emptyList()))
    }

    @Test fun matchPrefersLongerPhrase() {
        val p = listOf("кузя привет", "кузя привет как дела")
        assertEquals("кузя привет как дела", VoiceCmd.match("кузя привет как дела", p))
        assertEquals("кузя привет", VoiceCmd.match("кузя привет", p))
    }

    @Test fun windowSameDay() {
        val w = VoiceCmd.parseWindow("8..22")!!
        assertFalse(w.contains(7 * 60 + 59))
        assertTrue(w.contains(8 * 60))
        assertTrue(w.contains(21 * 60 + 59))
        assertFalse(w.contains(22 * 60)) // конец не входит
        val h = VoiceCmd.parseWindow(" 8:30 .. 22:15 ")!!
        assertFalse(h.contains(8 * 60 + 29)); assertTrue(h.contains(8 * 60 + 30)); assertTrue(h.contains(22 * 60 + 14)); assertFalse(h.contains(22 * 60 + 15))
    }

    @Test fun windowOvernightAndAlways() {
        val n = VoiceCmd.parseWindow("22..6")!!
        assertTrue(n.contains(23 * 60)); assertTrue(n.contains(0)); assertTrue(n.contains(5 * 60 + 59))
        assertFalse(n.contains(6 * 60)); assertFalse(n.contains(12 * 60)); assertTrue(n.contains(22 * 60))
        val all = VoiceCmd.parseWindow("0..24")!!
        assertTrue(all.contains(0)); assertTrue(all.contains(1439))
    }

    @Test fun windowEmptyAndErrors() {
        assertNull(VoiceCmd.parseWindow(""))
        assertNull(VoiceCmd.parseWindow("   "))
        for (s in listOf("8", "8-22", "8..25", "8:60..9", "24:30..1", "10..10", "утро..вечер", "8..22..3")) {
            try { VoiceCmd.parseWindow(s); fail("ожидалась ошибка для «$s»") } catch (e: IllegalArgumentException) { }
        }
    }

    @Test fun voiceSettingInScript() {
        val ok = "task \"Поболтать\" voice=\"Кузя, поболтай со мной|Кузя, ты здесь\"\n  say \"Здравствуйте!\"\n"
        assertNull(Script.validate(ok, "chat"))
        assertNull(Script.validate("task \"Поболтать\" cron=\"0 11 * * *\"\n  voice=\"Кузя, поболтай со мной\"\n\n  say \"Здравствуйте!\"\n", "chat"))
        val e1 = Script.validate("task \"Т\" voice=\"Kuzya privet\"\n  say \"а\"\n", "t")
        assertTrue(e1.toString(), e1!!.contains("voice") && e1.contains("русскими буквами"))
        val e2 = Script.validate("task \"Т\" voice=5\n  say \"а\"\n", "t")
        assertTrue(e2.toString(), e2!!.contains("в кавычках"))
        val e3 = Script.validate("task \"Т\" voice=\"да\"\n  say \"а\"\n", "t")
        assertTrue(e3.toString(), e3!!.contains("короткая"))
        val e4 = Script.validate("task \"Т\" voice=\"Кузя привет\"\n  voice=\"Кузя пока\"\n  say \"а\"\n", "t")
        assertTrue(e4.toString(), e4!!.contains("уже задана"))
    }
}
