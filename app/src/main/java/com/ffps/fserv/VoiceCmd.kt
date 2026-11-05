package com.ffps.fserv

/**
 * Запуск задач голосом: чистая логика без обращения к микрофону и настройкам (покрыта юнит-тестами).
 * Фразы задаются настройкой `voice="фраза1|фраза2"` в задаче, имя помощника (префикс) и часы прослушивания — на вкладке «Звук».
 *
 * Шаблон фразы (подмножество JSGF): `(а|б)` — обязательный выбор, `[а|б]` — необязательная часть, `|` вне скобок разделяет фразы,
 * `=` в начале фразы — слушать её без префикса. Шаблон разворачивается в обычный список фраз: Vosk принимает только его.
 */
object VoiceCmd {
    private const val MAX_PHRASE = 80      // длина одной развёрнутой фразы
    private const val MAX_EXPANSION = 500  // сколько фраз может дать настройка одной задачи (и один префикс)
    private const val MAX_SOURCE = 600     // длина текста настройки

    /** Развёрнутая фраза: [text] — слова через пробел; [raw] — слушать без префикса (запись «=…»). */
    class Phrase(val text: String, val raw: Boolean)

    /** Слова текста: нижний регистр, «ё» как «е», всё, кроме букв и цифр, — разделитель. */
    fun words(s: String): List<String> =
        s.lowercase().replace('ё', 'е').split(Regex("[^\\p{L}\\p{N}]+")).filter { it.isNotEmpty() }

    /**
     * Разбор настройки voice: фразы через «|» (шаблоны разворачиваются). Одинаковые варианты сливаются.
     * IllegalArgumentException с понятной причиной: пустая или слишком короткая фраза, не русские буквы, неверные скобки, слишком много вариантов.
     */
    fun expandPhrases(s: String): List<Phrase> {
        if (s.length > MAX_SOURCE) throw IllegalArgumentException("текст voice длиннее $MAX_SOURCE знаков")
        val out = LinkedHashMap<String, Phrase>()
        for (part in splitTop(s)) {
            var p = part.trim()
            val raw = p.startsWith("=")
            if (raw) p = p.substring(1).trim()
            if (p.isEmpty()) throw IllegalArgumentException("пустая фраза (фразы разделяются знаком |)")
            for (v in expand(p)) {
                checkVariant(v, p, 4)
                out.putIfAbsent("$raw:$v", Phrase(v, raw))
                if (out.size > MAX_EXPANSION) throw IllegalArgumentException("настройка voice даёт больше $MAX_EXPANSION вариантов фраз: упростите шаблоны")
            }
        }
        return out.values.toList()
    }

    /** Разбор префикса (имени помощника): варианты через «|», допустимы шаблоны. Пусто — без префикса. */
    fun expandPrefixes(s: String): List<String> {
        if (s.isBlank()) return emptyList()
        if (s.length > MAX_SOURCE) throw IllegalArgumentException("префикс длиннее $MAX_SOURCE знаков")
        val out = LinkedHashSet<String>()
        for (v in expand(s.trim())) {
            checkVariant(v, s.trim(), 3)
            out.add(v)
        }
        return out.toList()
    }

    /** Полные фразы для распознавания: пары (полная фраза, фраза без префикса). Фразы «=…» и все фразы при пустом списке префиксов идут как есть. */
    fun fullPhrases(phrases: List<Phrase>, prefixes: List<String>): List<Pair<String, String>> {
        val out = ArrayList<Pair<String, String>>()
        for (p in phrases) {
            if (p.raw || prefixes.isEmpty()) out.add(p.text to p.text)
            else for (pf in prefixes) out.add("$pf ${p.text}" to p.text)
        }
        return out
    }

    private fun checkVariant(v: String, src: String, minLetters: Int) {
        if (v.isEmpty()) throw IllegalArgumentException("в «$src» есть вариант без слов: проверьте скобки [ ] и ( )")
        if (v.any { it != ' ' && it !in 'а'..'я' }) throw IllegalArgumentException("«$v» пишите русскими буквами, числа словами (распознаются только слова из словаря русской модели)")
        if (v.length > MAX_PHRASE) throw IllegalArgumentException("фраза «${v.take(20)}…» длиннее $MAX_PHRASE знаков")
        if (v.replace(" ", "").length < minLetters) throw IllegalArgumentException("«$v» слишком короткая: нужно хотя бы $minLetters буквы" + if (minLetters == 4) ", лучше два-три слова, иначе будут ложные срабатывания" else "")
    }

    /** Режет [s] по «|» вне скобок. */
    private fun splitTop(s: String): List<String> {
        val parts = ArrayList<String>()
        var depth = 0
        var start = 0
        for ((i, c) in s.withIndex()) when (c) {
            '(', '[' -> depth++
            ')', ']' -> { depth--; if (depth < 0) throw IllegalArgumentException("лишняя скобка «$c» в «$s»") }
            '|' -> if (depth == 0) { parts.add(s.substring(start, i)); start = i + 1 }
        }
        if (depth != 0) throw IllegalArgumentException("не закрыта скобка в «$s»")
        parts.add(s.substring(start))
        return parts
    }

    /** Все варианты шаблона (слова нормализованы, через пробел). */
    private fun expand(src: String): List<String> {
        val p = intArrayOf(0)
        return parseAlts(src, p, null)
    }

    private fun parseAlts(s: String, p: IntArray, close: Char?): List<String> {
        val alts = LinkedHashSet<String>()
        var cur: List<String> = listOf("")
        fun flush() {
            alts.addAll(cur)
            if (alts.size > MAX_EXPANSION) throw IllegalArgumentException("шаблон даёт больше $MAX_EXPANSION вариантов фразы: упростите его")
        }
        while (p[0] < s.length) {
            val c = s[p[0]]
            when (c) {
                '|' -> { flush(); cur = listOf(""); p[0]++ }
                '(', '[' -> {
                    p[0]++
                    var g = parseAlts(s, p, if (c == '(') ')' else ']')
                    if (c == '[') g = g + ""
                    cur = product(cur, g)
                }
                ')', ']' -> {
                    if (c != close) throw IllegalArgumentException(if (close == null) "лишняя скобка «$c» в «$s»" else "скобки «(» и «[» перепутаны в «$s»")
                    p[0]++
                    flush()
                    return alts.toList()
                }
                else -> {
                    val st = p[0]
                    while (p[0] < s.length && s[p[0]] !in "()[]|") p[0]++
                    val w = words(s.substring(st, p[0])).joinToString(" ")
                    if (w.isNotEmpty()) cur = cur.map { if (it.isEmpty()) w else "$it $w" }
                }
            }
        }
        if (close != null) throw IllegalArgumentException("не закрыта скобка «${if (close == ')') '(' else '['}» в «$s»")
        flush()
        return alts.toList()
    }

    private fun product(a: List<String>, b: List<String>): List<String> {
        if (a.size.toLong() * b.size > MAX_EXPANSION) throw IllegalArgumentException("шаблон даёт больше $MAX_EXPANSION вариантов фразы: упростите его")
        val out = LinkedHashSet<String>()
        for (x in a) for (y in b) out.add(if (x.isEmpty()) y else if (y.isEmpty()) x else "$x $y")
        return out.toList()
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
