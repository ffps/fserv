package com.ffps.fserv

/**
 * Яркость окна киоска. Чистая логика без Android: приоритеты и проверка значений настроек
 * `screen.brightness.kiosk` и `screen.brightness.call` (1..100 или пусто = не задано).
 */
object Brightness {
    /**
     * Какая яркость (%) должна быть у окна сейчас; null — системная (окну ничего не задаём).
     * Вспышка 100; звонок: своя яркость, иначе киоска; слайд с brightness; иначе яркость киоска.
     */
    fun effective(flash: Boolean, call: Boolean, slide: Int?, kiosk: Int?, callCfg: Int?): Int? = when {
        flash -> 100
        call -> callCfg ?: kiosk
        slide != null -> slide
        else -> kiosk
    }

    /** Значение из настроек: null, пусто или число 1..100 (числом или текстом). */
    fun valid(v: Any?): Boolean = isUnset(v) || toInt(v) != null

    /** Пусто или null: параметр не задан. */
    fun isUnset(v: Any?): Boolean = v == null || v == org.json.JSONObject.NULL || (v is String && v.trim().isEmpty())

    /** Целое 1..100 либо null (не задано или не годится). */
    fun toInt(v: Any?): Int? {
        val n: Double = when (v) {
            is Int -> v.toDouble()
            is Long -> v.toDouble()
            is Double -> v
            is Float -> v.toDouble()
            is String -> v.trim().toDoubleOrNull() ?: return null
            else -> return null
        }
        if (n != Math.floor(n) || n < 1 || n > 100) return null
        return n.toInt()
    }
}
