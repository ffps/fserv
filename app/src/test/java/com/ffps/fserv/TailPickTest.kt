package com.ffps.fserv

import org.junit.Assert.*
import org.junit.Test

class TailPickTest {
    private fun w(vararg p: Pair<String, Double>) = p.toList()
    private fun pick(got: List<Pair<String, Double>>, head: String = "кузя", shift: Double, tailSec: Int = 8) =
        TailPick.pick(got, head.split(' '), shift, tailSec, 0.25, 0.1)

    @Test fun textAnchorDropsWordsBeforeName() {
        // 15:06:00 с A7: перед именем «воды», время привязки сбито на 2,3 с
        val r = pick(w("воды" to 0.42, "кузя" to 2.82, "в" to 3.48, "каком" to 3.54, "году" to 3.96, "родился" to 4.35, "ломоносов" to 4.89), shift = 0.47)
        assertEquals("в каком году родился ломоносов", r.tail.joinToString(" "))
        assertTrue(r.how.startsWith("по тексту"))
    }

    @Test fun textAnchorWhenFragmentMisheard() {
        // обрывок имени распознан как «тень» (A3), имя не найдено: по времени
        val r = pick(w("тень" to 0.0, "сколько" to 0.33, "дней" to 0.78, "в" to 1.11, "году" to 1.2), shift = 0.21)
        assertEquals("сколько дней в году", r.tail.joinToString(" "))
        assertTrue(r.how.startsWith("по времени"))
    }

    @Test fun fallbackKeepsHeadFragmentWordRule() {
        val r = pick(w("кузя" to 0.54, "как" to 1.14, "звали" to 1.35), head = "кузя", shift = 0.34)
        assertEquals("как звали", r.tail.joinToString(" ")) // найдено по тексту, имя убрано
        val r2 = pick(w("ня" to 0.5, "как" to 1.14, "звали" to 1.35), head = "кузя", shift = 0.34)
        assertEquals("ня как звали", r2.tail.joinToString(" ")) // не слово команды: остаётся в хвосте (его убирает только совпадающее слово)
    }

    @Test fun limitFromHeadEnd() {
        val got = w("кузя" to 0.2, "а" to 1.0, "б" to 5.0, "в" to 7.0, "г" to 9.5)
        assertEquals("а б в", pick(got, shift = 0.6, tailSec = 7).tail.joinToString(" ")) // 0,2 + 0,6 + 7 = 7,8
        assertEquals("а б в г", pick(got, shift = 0.6, tailSec = 9).tail.joinToString(" "))
    }

    @Test fun multiWordHeadAndNearestOccurrence() {
        val got = w("кузя" to 0.1, "расскажи" to 0.7, "про" to 1.4, "кузя" to 3.0, "расскажи" to 3.6, "шутку" to 4.2)
        val r = pick(got, head = "кузя расскажи", shift = 3.9)
        assertEquals("шутку", r.tail.joinToString(" ")) // ближе к оценке конца команды
        val r2 = pick(got, head = "кузя расскажи", shift = 1.0)
        assertEquals("про кузя расскажи шутку", r2.tail.joinToString(" "))
    }

    @Test fun fragmentsAtStartAreDropped() {
        // нет команды во втором проходе; слова в первые 0,1 с это обрывок
        val r = pick(w("тень" to 0.0, "сколько" to 0.33, "будет" to 0.8), head = "кузя", shift = 0.15)
        assertEquals("сколько будет", r.tail.joinToString(" "))
        assertEquals(emptyList<String>(), pick(emptyList(), shift = 0.4).tail)
    }
}
