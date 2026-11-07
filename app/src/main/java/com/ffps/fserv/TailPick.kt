package com.ffps.fserv

/**
 * Выбор хвоста команды из слов второго прохода (распознавателя свободной речи). Чистая логика без Android.
 *
 * Привязка по времени (конец команды в потоке второго прохода) сбивается, если перед именем были слова, которых нет в результате первого
 * прохода: тогда имя и слова до него попадали в хвост («воды масяня в каком году…»). Поэтому команду сначала ищем во втором проходе по тексту,
 * а хвост — слова после неё. По времени выбираем, только если команды там нет (её обрывок мог не распознаться).
 */
object TailPick {
    class Result(val tail: List<String>, val how: String)

    /**
     * [got] — слова второго прохода (слово, начало в секундах от начала разбора), [head] — слова команды, [shift] — оценка конца команды
     * в секундах от начала разбора, [tailSec] — предел длины хвоста, [before] — на сколько раньше конца команды может начаться первое слово хвоста,
     * [fragmentSec] — слова, начавшиеся раньше, в запасном (временном) выборе считаются обрывком команды.
     */
    fun pick(got: List<Pair<String, Double>>, head: List<String>, shift: Double, tailSec: Int, before: Double, fragmentSec: Double): Result {
        var at = -1
        if (head.isNotEmpty()) for (i in 0..got.size - head.size) {
            if (head.indices.all { got[i + it].first == head[it] }) {
                if (at < 0 || Math.abs(got[i + head.size - 1].second - shift) < Math.abs(got[at + head.size - 1].second - shift)) at = i
            }
        }
        if (at >= 0) {
            val last = at + head.size - 1
            val lim = got[last].second + 0.6 + tailSec // предел отсчитывается от конца команды (слово команды длится около 0,6 с)
            return Result(got.drop(last + 1).filter { it.second <= lim }.map { it.first }, "по тексту, команда во втором проходе на ${"%.2f".format(got[last].second)} с")
        }
        val tail = got.filter { it.second >= maxOf(shift - before, fragmentSec) && it.second <= shift + tailSec }.map { it.first }.toMutableList()
        if (tail.isNotEmpty() && head.isNotEmpty() && tail[0] == head.last()) tail.removeAt(0) // обрывок последнего слова самой команды
        return Result(tail, "по времени, команды во втором проходе нет")
    }
}
