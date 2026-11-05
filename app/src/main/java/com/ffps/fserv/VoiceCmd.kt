package com.ffps.fserv

/**
 * Запуск задач голосом: чистая логика без обращения к микрофону и настройкам (покрыта юнит-тестами).
 * Фразы задаются настройкой `voice="фраза1|фраза2"` в задаче, часы прослушивания — настройкой «Слушать команды, часы» (вкладка «Звук»).
 */
object VoiceCmd {
    private const val MAX_PHRASE = 80

    /** Слова текста: нижний регистр, «ё» как «е», всё, кроме букв и цифр, — разделитель. */
    fun words(s: String): List<String> =
        s.lowercase().replace('ё', 'е').split(Regex("[^\\p{L}\\p{N}]+")).filter { it.isNotEmpty() }

    /**
     * Разбор настройки voice: фразы через «|». Возвращает нормализованные фразы (слова через пробел).
     * IllegalArgumentException с понятной причиной, если фраза пуста, слишком коротка, содержит не русские буквы или повторяется.
     */
    fun parsePhrases(s: String): List<String> {
        val out = ArrayList<String>()
        for (raw in s.split('|')) {
            val p = raw.trim()
            if (p.isEmpty()) throw IllegalArgumentException("пустая фраза (фразы разделяются знаком |)")
            if (p.length > MAX_PHRASE) throw IllegalArgumentException("фраза «${p.take(20)}…» длиннее $MAX_PHRASE знаков")
            val w = words(p)
            if (w.isEmpty() || w.any { x -> x.any { it !in 'а'..'я' } }) throw IllegalArgumentException("фразу «$p» пишите русскими буквами, числа словами (распознаются только слова из словаря русской модели)")
            val n = w.joinToString(" ")
            if (n.replace(" ", "").length < 4) throw IllegalArgumentException("фраза «$p» слишком короткая: нужно хотя бы 4 буквы, лучше два-три слова, иначе будут ложные срабатывания")
            if (n in out) throw IllegalArgumentException("фраза «$p» повторяется")
            out.add(n)
        }
        return out
    }

    /** Какая из [phrases] (нормализованных) произнесена: её слова идут в услышанном подряд. Более длинная фраза предпочтительнее; null, если ни одной. */
    fun match(heard: String, phrases: List<String>): String? {
        val h = words(heard)
        if (h.isEmpty()) return null
        for (p in phrases.sortedByDescending { it.length }) {
            val pw = p.split(' ')
            if (pw.size > h.size) continue
            for (i in 0..h.size - pw.size) if (h.subList(i, i + pw.size) == pw) return p
        }
        return null
    }

    /** Часы прослушивания: от [from] до [to] минут от полуночи; если from > to, интервал переходит через полночь. */
    class Window(val from: Int, val to: Int) {
        fun contains(minuteOfDay: Int): Boolean = if (from < to) minuteOfDay in from until to else minuteOfDay >= from || minuteOfDay < to
    }

    private val WINDOW_RE = Regex("""\s*(\d{1,2})(?::(\d{2}))?\s*\.\.\s*(\d{1,2})(?::(\d{2}))?\s*""")

    /** «8..22», «8:30..22:00», «22..6» (через полночь), «0..24» (круглосуточно); пусто — null (не слушать). IllegalArgumentException при неверной записи. */
    fun parseWindow(s: String): Window? {
        if (s.isBlank()) return null
        val m = WINDOW_RE.matchEntire(s) ?: throw IllegalArgumentException("часы вида 8..22 или 8:30..22:00 (пусто — не слушать, 0..24 — круглосуточно)")
        fun t(h: String, min: String): Int {
            val hh = h.toInt()
            val mm = if (min.isEmpty()) 0 else min.toInt()
            if (mm > 59 || hh > 24 || (hh == 24 && mm != 0)) throw IllegalArgumentException("время «$h${if (min.isEmpty()) "" else ":$min"}» вне суток (часы 0..24, минуты 0..59)")
            return hh * 60 + mm
        }
        val a = t(m.groupValues[1], m.groupValues[2])
        val b = t(m.groupValues[3], m.groupValues[4])
        if (a == b) throw IllegalArgumentException("начало и конец совпадают: для круглосуточной работы напишите 0..24")
        return Window(a, b)
    }
}
