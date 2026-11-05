package com.ffps.fserv

import org.junit.Assert.*
import org.junit.Test

class VoiceCmdTest {
    private fun bad(s: String): String = try { VoiceCmd.expandPhrases(s); fail("ожидалась ошибка для «$s»"); "" } catch (e: IllegalArgumentException) { e.message!! }
    private fun texts(s: String) = VoiceCmd.expandPhrases(s).map { it.text }

    @Test fun parsePhrases() {
        assertEquals(listOf("поболтай со мной", "ты здесь"), texts("Поболтай со мной|  Ты здесь?  "))
        assertEquals(listOf("все хорошо"), texts("Всё хорошо")) // «ё» → «е»
        assertEquals(listOf("кузя поболтай со мной"), texts("Кузя, поболтай со мной"))
    }

    @Test fun choiceAndOptional() {
        assertEquals(listOf("скажи сколько время", "скажи сколько времени"), texts("скажи сколько (время|времени)"))
        // необязательная часть даёт и вариант без неё
        assertEquals(setOf("скажи сколько времени", "скажи сколько сейчас времени", "скажи сколько у нас времени"),
            texts("скажи сколько [сейчас|у нас] времени").toSet())
    }

    @Test fun userExampleFromChat() {
        // из обсуждения: (|у нас|сейчас) — пустой вариант делает часть необязательной; два варианта слова «время»
        val v = texts("скажи сколько (|у нас|сейчас) (время|времени)")
        assertEquals(6, v.size)
        assertTrue(v.contains("скажи сколько время"))
        assertTrue(v.contains("скажи сколько у нас времени"))
        assertTrue(v.contains("скажи сколько сейчас время"))
        assertEquals(v.size, v.toSet().size)
    }

    @Test fun nestedGroups() {
        val v = texts("(привет|добрый (день|вечер)) помощник")
        assertEquals(setOf("привет помощник", "добрый день помощник", "добрый вечер помощник"), v.toSet())
    }

    @Test fun topLevelAlternativesVsGroups() {
        // | внутри скобок — вариант, вне скобок — отдельная фраза
        assertEquals(setOf("включи свет", "включи лампу", "выключи свет"), texts("включи (свет|лампу)|выключи свет").toSet())
    }

    @Test fun rawPhrases() {
        val p = VoiceCmd.expandPhrases("=на помощь|поболтай со мной")
        assertEquals(listOf("на помощь" to true, "поболтай со мной" to false), p.map { it.text to it.raw })
        // одинаковый текст как обычная и как «=» фраза — две разные записи
        assertEquals(2, VoiceCmd.expandPhrases("=ты здесь|ты здесь").size)
        // одинаковые варианты сливаются
        assertEquals(1, VoiceCmd.expandPhrases("ты здесь|ты здесь").size)
    }

    @Test fun prefixes() {
        assertEquals(emptyList<String>(), VoiceCmd.expandPrefixes(""))
        assertEquals(listOf("кузя", "кузьма"), VoiceCmd.expandPrefixes("Кузя|Кузьма"))
        assertEquals(setOf("эй кузя", "привет кузя"), VoiceCmd.expandPrefixes("(эй|привет) Кузя").toSet())
        try { VoiceCmd.expandPrefixes("Kuzya"); fail() } catch (e: IllegalArgumentException) { assertTrue(e.message!!.contains("русскими")) }
        try { VoiceCmd.expandPrefixes("ку"); fail() } catch (e: IllegalArgumentException) { assertTrue(e.message!!.contains("короткая")) }
    }

    @Test fun fullPhrasesWithPrefix() {
        val p = VoiceCmd.expandPhrases("=на помощь|поболтай со мной")
        val withPrefix = VoiceCmd.fullPhrases(p, listOf("кузя", "кузьма"))
        assertEquals(listOf("на помощь" to "на помощь", "кузя поболтай со мной" to "поболтай со мной", "кузьма поболтай со мной" to "поболтай со мной"), withPrefix)
        // без префикса все фразы как есть
        assertEquals(listOf("на помощь" to "на помощь", "поболтай со мной" to "поболтай со мной"), VoiceCmd.fullPhrases(p, emptyList()))
    }

    @Test fun parsePhrasesErrors() {
        assertTrue(bad("привет всем||ещё фраза").contains("пустая"))
        assertTrue(bad("").contains("пустая"))
        assertTrue(bad("=").contains("пустая"))
        assertTrue(bad("Kuzya privet").contains("русскими буквами"))
        assertTrue(bad("позвони по 112").contains("русскими буквами"))
        assertTrue(bad("да").contains("короткая"))
        assertTrue(bad("а".repeat(81)).contains("длиннее"))
        assertTrue(bad("скажи [время").contains("не закрыта"))
        assertTrue(bad("скажи время)").contains("лишняя"))
        assertTrue(bad("скажи (время]").contains("перепутаны"))
        assertTrue(bad("[]").contains("без слов"))
        assertTrue(bad("[скажи|пожалуйста]").contains("без слов")) // единственная необязательная часть даёт пустой вариант
    }

    @Test fun tooManyVariants() {
        val five = (1..5).joinToString(" ") { "(раз|два|три)" }   // 3^5 = 243 варианта: допустимо
        assertEquals(243, texts(five).size)
        val eight = (1..8).joinToString(" ") { "(раз|два|три)" }  // 3^8 = 6561 > 500
        assertTrue(bad(eight).contains("больше"))
    }

    @Test fun matchContiguousWords() {
        val p = texts("Кузя, поболтай со мной|Кузя, ты здесь")
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
        val ok = "task \"Поболтать\" voice=\"поболтай со мной|ты здесь\"\n  say \"Здравствуйте!\"\n"
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
