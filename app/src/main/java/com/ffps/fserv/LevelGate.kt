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
