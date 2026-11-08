package com.ffps.fserv

/**
 * Порог громкости для слушателя голосовых команд: тишину распознавателю не отдаём, это экономит процессор.
 * Звук громче порога открывает «ворота»: распознавателю уходят и последние [pre] кусков до этого (чтобы не обрезать начало слова),
 * и [hang] кусков после (чтобы он успел дослушать фразу и увидеть паузу). Порог 0 — ворота всегда открыты.
 * Чистая логика без Android (покрыта юнит-тестами).
 */
class LevelGate(private val hang: Int = 12, private val pre: Int = 2) {
    private val ring = ArrayDeque<ShortArray>()
    private var left = 0

    /** Ворота только что закрылись (после этого куска): начатую фразу, если она не завершилась, нужно сбросить. */
    var closedNow = false
        private set

    val isOpen: Boolean get() = left > 0

    /**
     * Что отдать распознавателю для очередного куска [chunk] с пиком [peak] при пороге [threshold] (в единицах пика, 0 — всегда).
     * Пустой список — ничего. Куски в списке идут по порядку, последним — сам [chunk] (если ворота открыты).
     */
    fun push(chunk: ShortArray, peak: Int, threshold: Int): List<ShortArray> {
        closedNow = false
        if (threshold <= 0) { ring.clear(); left = 0; return listOf(chunk) }
        if (peak >= threshold) {
            val out = ArrayList<ShortArray>(pre + 1)
            if (left == 0) out.addAll(ring)
            ring.clear()
            out.add(chunk)
            left = hang
            return out
        }
        if (left > 0) {
            left--
            if (left == 0) closedNow = true
            return listOf(chunk)
        }
        ring.addLast(chunk.copyOf())
        while (ring.size > pre) ring.removeFirst()
        return emptyList()
    }

    /** Забыть всё (после паузы слушателя, речи синтезатора и т. п.). */
    fun reset() { ring.clear(); left = 0; closedNow = false }
}

/**
 * Звук текущей реплики (всё, что слушатель отдал распознавателю после его последнего завершения) для второго прохода: после начала команды
 * со свободным хвостом («кузя, расскажи про *») хвост нужно распознать другой моделью, а звук к этому моменту уже ушёл первой.
 * Лежит только в памяти и не дольше реплики. Куски по [chunkSec] секунд; тишина и речь различаются по пику куска относительно фона.
 * Чистая логика без Android (покрыта юнит-тестами).
 */
class Utterance(private val maxChunks: Int = 250, val chunkSec: Double = 0.1) {
    private val chunks = ArrayDeque<ShortArray>()
    private val peaks = ArrayDeque<Int>()

    val size: Int get() = chunks.size

    /** Сколько кусков выброшено с начала реплики (переполнение и [trimToSpeech]): номер куска в потоке = [dropped] + номер в [all]. */
    var dropped = 0
        private set

    fun add(c: ShortArray) {
        var p = 0
        for (v in c) { val a = Math.abs(v.toInt()); if (a > p) p = a }
        chunks.addLast(c.copyOf()); peaks.addLast(p)
        while (chunks.size > maxChunks) { chunks.removeFirst(); peaks.removeFirst(); dropped++ }
    }

    fun clear() { chunks.clear(); peaks.clear(); dropped = 0 }

    fun all(): List<ShortArray> = chunks.toList()

    /** Порог «это речь»: заметно выше фона (фон — низкий дециль пиков). */
    private fun limit(): Int {
        if (peaks.isEmpty()) return Int.MAX_VALUE
        val s = peaks.sorted()
        return s[s.size / 10] * 3 + 150
    }

    /** Номер первого куска с речью или -1. */
    fun firstLoud(): Int { val l = limit(); return peaks.indexOfFirst { it > l } }

    /** Есть ли речь в кусках с номера [from]. */
    fun loudAfter(from: Int): Boolean { val l = limit(); for (i in maxOf(from, 0) until peaks.size) if (peaks[i] > l) return true; return false }

    /** Сколько последних кусков подряд тихие. */
    fun trailingSilence(): Int { val l = limit(); var n = 0; var i = peaks.size - 1; while (i >= 0 && peaks[i] <= l) { n++; i-- }; return n }

    /** Выбрасывает тишину до начала речи, оставляя [pre] кусков до неё. Возвращает false, если речи нет. */
    fun trimToSpeech(pre: Int = 2): Boolean {
        val f = firstLoud()
        if (f < 0) return false
        val drop = maxOf(0, f - pre)
        repeat(drop) { chunks.removeFirst(); peaks.removeFirst() }
        dropped += drop
        return true
    }
}
