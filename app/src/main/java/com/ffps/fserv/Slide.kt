package com.ffps.fserv

import android.os.Handler
import android.os.Looper
import java.util.concurrent.atomic.AtomicInteger

/**
 * Слайд на экране (шаг slide): один на всё устройство, показывается слоем поверх киоска и только пока киоск на переднем плане.
 * Каждый показ получает номер (token): скрытие по номеру ([release]) ничего не делает, если слайд уже заменён другим или скрыт.
 */
object Slide {
    class Spec(
        val file: String, val fit: String, val bg: String, val brightness: Int?,
        val fadeInMs: Long, val fadeOutMs: Long, val tapHide: Boolean
    )

    private val counter = AtomicInteger()
    @Volatile private var current = 0
    private val handler = Handler(Looper.getMainLooper())

    /** Показывает слайд; возвращает его номер или 0, если не показан (киоск не на переднем плане). [forMs] — скрыть через столько. */
    fun show(spec: Spec, forMs: Long?): Int {
        if (!MainActivity.showSlide(spec)) return 0
        val token = counter.incrementAndGet()
        current = token
        if (forMs != null) handler.postDelayed({ release(token) }, forMs)
        return token
    }

    /** Скрывает слайд с номером [token], если он ещё на экране. */
    fun release(token: Int) {
        if (token != 0 && current == token) { current = 0; MainActivity.hideSlide() }
    }

    /** Скрывает слайд, чей бы он ни был. */
    fun off() {
        current = 0
        MainActivity.hideSlide()
    }
}

/**
 * Вспышка (шаг flash): белый экран на полной яркости поверх всего, пока киоск на переднем плане. Гаснет сама по сроку; яркость возвращается.
 * Вспышка во время идущей заменяет её (срок отсчитывается заново); после погасшей следующая не раньше чем через [MIN_GAP_MS]: цикл не превращается в строб.
 */
object Flash {
    const val MIN_GAP_MS = 1000L
    private val counter = AtomicInteger()
    @Volatile private var current = 0
    @Volatile private var lastEnd = 0L
    private val handler = Handler(Looper.getMainLooper())

    /** 1 — показана; 0 — киоск не на переднем плане; -1 — слишком рано после прошлой вспышки. */
    @Synchronized fun show(forMs: Long): Int {
        if (current == 0 && System.currentTimeMillis() - lastEnd < MIN_GAP_MS) return -1
        if (!MainActivity.showFlash()) return 0
        val token = counter.incrementAndGet()
        current = token
        handler.postDelayed({ release(token) }, forMs)
        return 1
    }

    @Synchronized private fun release(token: Int) {
        if (current == token) { current = 0; lastEnd = System.currentTimeMillis(); MainActivity.hideFlash() }
    }

    @Synchronized fun off() {
        if (current != 0) { current = 0; lastEnd = System.currentTimeMillis() }
        MainActivity.hideFlash()
    }
}
