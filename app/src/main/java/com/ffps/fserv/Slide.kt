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
