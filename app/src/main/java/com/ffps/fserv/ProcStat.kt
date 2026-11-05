package com.ffps.fserv

/**
 * Разбор статистики потоков процесса (/proc/self/task/<tid>/stat) для записи о нагрузке: чистая логика без обращения к системе.
 */
object ProcStat {
    /** Поток: имя (до 15 знаков, как в ядре) и накопленное процессорное время в «тиках» (пользовательское плюс системное). */
    class T(val name: String, val ticks: Long)

    /** Строка stat одного потока → [T]; null, если формат непонятен. Имя может содержать пробелы и скобки, поэтому ищем первую «(» и последнюю «)». */
    fun parse(line: String): T? {
        val b = line.indexOf('(')
        val a = line.lastIndexOf(')')
        if (b < 0 || a < b) return null
        val f = line.substring(a + 1).trim().split(' ')
        if (f.size < 13) return null // после имени: состояние, … utime — 12-е поле (индекс 11), stime — 13-е
        val u = f[11].toLongOrNull() ?: return null
        val s = f[12].toLongOrNull() ?: return null
        return T(line.substring(b + 1, a), u + s)
    }

    /** Нагрузка одной группы потоков с общим именем: [pct] — процент одного ядра за период, [count] — сколько потоков с этим именем. */
    class Load(val name: String, val pct: Double, val count: Int)

    /**
     * Самые нагруженные потоки за период [wallMs]: приращение времени каждого потока ([prev] — снимок в начале, [cur] — в конце), сложенное по
     * одинаковым именам; [hz] — тиков в секунду. Группы меньше [minPct] процента не берутся, всего не больше [n].
     */
    fun top(prev: Map<Int, T>, cur: Map<Int, T>, wallMs: Long, hz: Long, n: Int, minPct: Double = 0.5): List<Load> {
        if (wallMs <= 0 || hz <= 0) return emptyList()
        val sum = HashMap<String, Long>()
        val cnt = HashMap<String, Int>()
        for ((tid, t) in cur) {
            val d = t.ticks - (prev[tid]?.ticks ?: 0L)
            if (d <= 0) continue
            sum[t.name] = (sum[t.name] ?: 0L) + d
            cnt[t.name] = (cnt[t.name] ?: 0) + 1
        }
        return sum.map { (name, d) -> Load(name, d * 1000.0 / hz * 100.0 / wallMs, cnt.getValue(name)) }
            .filter { it.pct >= minPct }
            .sortedByDescending { it.pct }
            .take(n)
    }

    /** «voice-trigger 24%, OkHttp 3% (×4)»: строка для журнала. */
    fun format(list: List<Load>): String =
        if (list.isEmpty()) "нет заметной нагрузки" else list.joinToString(", ") { "${it.name} ${"%.0f".format(it.pct)}%" + if (it.count > 1) " (×${it.count})" else "" }
}
