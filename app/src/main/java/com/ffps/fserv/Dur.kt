package com.ffps.fserv

import java.util.Random

/**
 * Длительности и счётчики из конфига задач.
 *
 * Длительность: число (секунды) или строка из частей «число + единица»: "500ms", "30s", "5m", "2h", "1d", "1m30s".
 * Диапазон "30s..2m" означает случайное значение между границами; оно выбирается заново при каждом использовании
 * (например, на каждой итерации повтора). Для счётчиков (times) работает такой же диапазон целых: "3..5".
 * Здесь нет зависимостей от Android: логика проверяется обычными тестами.
 */
object Dur {
    class Span(val minMs: Long, val maxMs: Long) {
        val isRange: Boolean get() = maxMs > minMs
        fun sample(r: Random): Long = if (maxMs <= minMs) minMs else minMs + (r.nextDouble() * (maxMs - minMs)).toLong()
        override fun toString(): String = if (isRange) "${minMs}..${maxMs} мс" else "$minMs мс"
    }

    private val PART = Regex("""(\d+(?:\.\d+)?)(ms|s|m|h|d)?""")

    private fun unit(u: String?): Long = when (u) {
        "ms" -> 1L
        "m" -> 60_000L
        "h" -> 3_600_000L
        "d" -> 86_400_000L
        else -> 1000L // "s" и число без единицы: секунды
    }

    private fun one(text: String, what: String): Long {
        val t = text.trim().lowercase()
        if (t.isEmpty()) throw IllegalArgumentException("$what: пустая длительность")
        // Одиночное число без единицы или составное значение вида 1m30s.
        val single = Regex("""\d+(?:\.\d+)?""").matches(t)
        if (single) return (t.toDouble() * 1000).toLong()
        var pos = 0
        var total = 0.0
        val parts = Regex("""(\d+(?:\.\d+)?)(ms|s|m|h|d)""")
        while (pos < t.length) {
            val m = parts.matchAt(t, pos) ?: throw IllegalArgumentException("$what: не понимаю длительность «$text» (примеры: 30s, 5m, 1h30m, 500ms)")
            total += m.groupValues[1].toDouble() * unit(m.groupValues[2])
            pos = m.range.last + 1
        }
        return total.toLong()
    }

    /** Разбор значения из JSON: число, строка или строка-диапазон. */
    fun parse(v: Any?, what: String): Span {
        return when (v) {
            null -> throw IllegalArgumentException("$what: значение не задано")
            is Number -> {
                val ms = (v.toDouble() * 1000).toLong()
                if (ms < 0) throw IllegalArgumentException("$what: длительность не может быть отрицательной")
                Span(ms, ms)
            }
            is String -> {
                val s = v.trim()
                if (s.contains("..")) {
                    val a = s.substringBefore("..")
                    val b = s.substringAfter("..")
                    var x = one(a, what)
                    var y = one(b, what)
                    if (x > y) { val t = x; x = y; y = t }
                    Span(x, y)
                } else {
                    val ms = one(s, what)
                    Span(ms, ms)
                }
            }
            else -> throw IllegalArgumentException("$what: ожидается число секунд или строка вроде \"30s\"")
        }
    }

    /**
     * Список длительностей для последовательных повторов: "3s,5s,10s,20s" или массив ["3s", "5s"].
     * Первый повтор берёт первое значение, второй второе и так далее, а после конца списка повторяется последнее.
     * Каждый элемент может быть диапазоном ("10s..20s").
     */
    fun parseList(v: Any?, what: String): List<Span> {
        val items: List<Any?> = when (v) {
            is org.json.JSONArray -> (0 until v.length()).map { v.opt(it) }
            is String -> v.split(",").map { it.trim() }
            else -> listOf(v)
        }
        if (items.isEmpty()) throw IllegalArgumentException("$what: список пуст")
        return items.map { parse(it, what) }
    }

    /** Значение для повтора номер [n] (с нуля): после конца списка берётся последнее. */
    fun pick(list: List<Span>, n: Int, r: Random): Long = list[minOf(n, list.size - 1)].sample(r)

    /** Целое или диапазон целых: 3 или "3..5". */
    fun parseCount(v: Any?, what: String): IntRange {
        return when (v) {
            is Int -> if (v >= 1) v..v else throw IllegalArgumentException("$what: должно быть не меньше 1")
            is Long -> parseCount(v.toInt(), what)
            is String -> {
                val s = v.trim()
                val re = Regex("""(\d+)(?:\.\.(\d+))?""").matchEntire(s)
                    ?: throw IllegalArgumentException("$what: ожидается число или диапазон вроде \"3..5\"")
                val a = re.groupValues[1].toInt()
                val b = if (re.groupValues[2].isEmpty()) a else re.groupValues[2].toInt()
                if (minOf(a, b) < 1) throw IllegalArgumentException("$what: должно быть не меньше 1")
                minOf(a, b)..maxOf(a, b)
            }
            else -> throw IllegalArgumentException("$what: ожидается число или диапазон вроде \"3..5\"")
        }
    }

    fun sampleCount(r: IntRange, rnd: Random): Int = if (r.first == r.last) r.first else r.first + rnd.nextInt(r.last - r.first + 1)

    /** Человекочитаемая запись: 90000 -> «1 мин 30 с». */
    fun human(ms: Long): String {
        if (ms < 1000) return "$ms мс"
        val s = ms / 1000
        val h = s / 3600
        val m = s % 3600 / 60
        val sec = s % 60
        return listOfNotNull(
            if (h > 0) "$h ч" else null,
            if (m > 0) "$m мин" else null,
            if (sec > 0 || (h == 0L && m == 0L)) "$sec с" else null
        ).joinToString(" ")
    }
}
