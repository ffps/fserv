package com.ffps.fserv

/**
 * Разбор распознанных ответов (без Android): числа словами по-русски («сто двадцать пять» → 125) и «да/нет».
 * Распознавание ограничено словарём [grammar], поэтому в ответе бывают только эти слова и «[unk]».
 */
object Speech {
    private val UNITS = mapOf(
        "ноль" to 0, "один" to 1, "одна" to 1, "одно" to 1, "два" to 2, "две" to 2, "три" to 3, "четыре" to 4, "пять" to 5,
        "шесть" to 6, "семь" to 7, "восемь" to 8, "девять" to 9
    )
    private val TEENS = mapOf(
        "десять" to 10, "одиннадцать" to 11, "двенадцать" to 12, "тринадцать" to 13, "четырнадцать" to 14, "пятнадцать" to 15,
        "шестнадцать" to 16, "семнадцать" to 17, "восемнадцать" to 18, "девятнадцать" to 19
    )
    private val TENS = mapOf(
        "двадцать" to 20, "тридцать" to 30, "сорок" to 40, "пятьдесят" to 50, "шестьдесят" to 60, "семьдесят" to 70,
        "восемьдесят" to 80, "девяносто" to 90
    )
    private val HUNDREDS = mapOf(
        "сто" to 100, "двести" to 200, "триста" to 300, "четыреста" to 400, "пятьсот" to 500, "шестьсот" to 600,
        "семьсот" to 700, "восемьсот" to 800, "девятьсот" to 900
    )
    private val YES = setOf("да", "ага", "угу", "конечно", "разумеется", "так")
    private val NO = setOf("нет", "неа", "не")

    private fun words(text: String): List<String> =
        text.lowercase().replace('ё', 'е').split(Regex("[^а-яa-z0-9\\[\\]]+")).filter { it.isNotEmpty() }

    /** Первое число в ответе (0..999) или null. Цифры тоже принимаются («120»). */
    fun number(text: String): Int? {
        var sum = 0
        var last = 4 // 4 = число ещё не начато; 3 сотни, 2 десятки и «-надцать», 1 единицы
        var started = false
        for (w in words(text)) {
            val digits = w.toIntOrNull()
            if (digits != null) return if (!started && digits in 0..999) digits else if (started) sum else null
            val cat: Int
            val v: Int
            when {
                w in HUNDREDS -> { cat = 3; v = HUNDREDS.getValue(w) }
                w in TENS -> { cat = 2; v = TENS.getValue(w) }
                w in TEENS -> { cat = 1; v = TEENS.getValue(w) }
                w in UNITS -> { cat = 1; v = UNITS.getValue(w) }
                else -> { if (started) break else continue }
            }
            if (started && cat >= last) break // «двадцать тридцать» — это уже другое число
            if (started && v == 0) break
            sum += v; last = if (w in TEENS) 1 else cat; started = true
        }
        return if (started) sum else null
    }

    /** true — «да», false — «нет», null — не понято. При обоих словах решает первое. */
    fun yesNo(text: String): Boolean? {
        for (w in words(text)) {
            if (w in YES) return true
            if (w in NO) return false
        }
        return null
    }

    /** Слова, из которых состоит словарь распознавания для вида ответа [kind] (number или yesno). */
    fun grammar(kind: String): List<String> =
        (if (kind == "number") (UNITS.keys + TEENS.keys + TENS.keys + HUNDREDS.keys).toList() else (YES + NO).toList()) + "[unk]"

    /** Значение ответа по виду: Int для number, Boolean для yesno; null, если ничего не понято. */
    fun parse(kind: String, text: String): Any? = if (kind == "number") number(text) else yesNo(text)
}
