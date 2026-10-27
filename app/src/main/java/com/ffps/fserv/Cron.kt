package com.ffps.fserv

import java.util.Calendar

/**
 * Разбор cron-выражения из 5 полей: минута час день_месяца месяц день_недели.
 * Поддерживаются *, числа, списки (1,3), диапазоны (1-5), шаги (*&#47;15, 0-30/10).
 * День недели: 0 или 7 = воскресенье. Поля «день месяца» и «день недели» объединяются по И.
 */
class Cron private constructor(private val sets: List<BooleanArray>) {

    fun matches(c: Calendar): Boolean {
        val dow = c.get(Calendar.DAY_OF_WEEK) - 1 // 0 = воскресенье
        return sets[0][c.get(Calendar.MINUTE)] &&
            sets[1][c.get(Calendar.HOUR_OF_DAY)] &&
            sets[2][c.get(Calendar.DAY_OF_MONTH)] &&
            sets[3][c.get(Calendar.MONTH) + 1] &&
            sets[4][dow]
    }

    companion object {
        /** Бросает IllegalArgumentException с понятным текстом, если выражение неверно. */
        fun parse(expr: String): Cron {
            val parts = expr.trim().split(Regex("\\s+"))
            require(parts.size == 5) { "нужно 5 полей: минута час день месяц день_недели" }
            val dow = field(parts[4], 0, 7, "день недели")
            if (dow[7]) dow[0] = true
            return Cron(
                listOf(
                    field(parts[0], 0, 59, "минута"),
                    field(parts[1], 0, 23, "час"),
                    field(parts[2], 1, 31, "день месяца"),
                    field(parts[3], 1, 12, "месяц"),
                    dow
                )
            )
        }

        private fun num(s: String, name: String): Int =
            s.toIntOrNull() ?: throw IllegalArgumentException("поле «$name»: '$s' не число")

        private fun field(text: String, lo: Int, hi: Int, name: String): BooleanArray {
            val set = BooleanArray(hi + 1)
            for (part in text.split(',')) {
                val sp = part.split('/')
                require(sp.size <= 2) { "поле «$name»: неверный шаг в '$part'" }
                val step = if (sp.size == 2) num(sp[1], name) else 1
                require(step >= 1) { "поле «$name»: шаг должен быть не меньше 1" }
                val base = sp[0]
                var from: Int
                var to: Int
                if (base == "*") {
                    from = lo
                    to = hi
                } else if (base.contains('-')) {
                    val ab = base.split('-')
                    require(ab.size == 2) { "поле «$name»: неверный диапазон '$base'" }
                    from = num(ab[0], name)
                    to = num(ab[1], name)
                } else {
                    from = num(base, name)
                    to = if (sp.size == 2) hi else from
                }
                require(from in lo..hi && to in lo..hi && from <= to) {
                    "поле «$name»: значения должны быть в диапазоне $lo-$hi"
                }
                var v = from
                while (v <= to) {
                    set[v] = true
                    v += step
                }
            }
            return set
        }
    }
}
