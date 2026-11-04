package com.ffps.fserv

import org.json.JSONObject
import java.time.LocalDateTime
import kotlin.math.roundToInt

/**
 * Чистая логика погоды без обращения к сети, настройкам и устройству (поэтому покрыта юнит-тестами): разбор ответа Open-Meteo,
 * описания и пиктограммы по кодам погоды, текст для киоска и для подсказки модели.
 */
object WeatherText {
    class Snap(
        val label: String, val updated: Long, val time: String,
        val temp: Double, val feels: Double, val code: Int, val cloud: Int, val wind: Double, val isDay: Boolean,
        val todayMin: Double, val todayMax: Double, val todayPop: Int, val todayCode: Int,
        val tomMin: Double, val tomMax: Double, val tomPop: Int, val tomCode: Int
    ) {
        fun toJson(): JSONObject = JSONObject().put("label", label).put("updated", updated).put("time", time)
            .put("temp", temp).put("feels", feels).put("code", code).put("cloud", cloud).put("wind", wind).put("isDay", isDay)
            .put("todayMin", todayMin).put("todayMax", todayMax).put("todayPop", todayPop).put("todayCode", todayCode)
            .put("tomMin", tomMin).put("tomMax", tomMax).put("tomPop", tomPop).put("tomCode", tomCode)

        companion object {
            fun fromJson(j: JSONObject) = Snap(
                j.optString("label"), j.optLong("updated"), j.optString("time"),
                j.optDouble("temp"), j.optDouble("feels"), j.optInt("code"), j.optInt("cloud"), j.optDouble("wind"), j.optBoolean("isDay", true),
                j.optDouble("todayMin"), j.optDouble("todayMax"), j.optInt("todayPop"), j.optInt("todayCode"),
                j.optDouble("tomMin"), j.optDouble("tomMax"), j.optInt("tomPop"), j.optInt("tomCode")
            )
        }
    }

    // ---------- чистая логика ----------

    /** «56.84, 60.61» (запятая или точка с запятой) → широта и долгота; null, если это не координаты. */
    fun parseCoords(s: String): Pair<Double, Double>? {
        val m = Regex("""\s*(-?\d{1,3}(?:\.\d+)?)\s*[,;]\s*(-?\d{1,3}(?:\.\d+)?)\s*""").matchEntire(s) ?: return null
        val lat = m.groupValues[1].toDouble()
        val lon = m.groupValues[2].toDouble()
        return if (lat in -90.0..90.0 && lon in -180.0..180.0) lat to lon else null
    }

    fun temp(t: Double): String {
        val r = t.roundToInt()
        return if (r > 0) "+$r°" else if (r == 0) "0°" else "$r°"
    }

    private fun cloudWord(cloud: Int) = when {
        cloud >= 80 -> "пасмурно"
        cloud >= 50 -> "облачно"
        cloud >= 20 -> "небольшая облачность"
        else -> ""
    }

    private fun codeText(code: Int) = when (code) {
        0 -> "ясно"
        1 -> "малооблачно"
        2 -> "переменная облачность"
        3 -> "пасмурно"
        45, 48 -> "туман"
        51 -> "слабая морось"
        53 -> "морось"
        55 -> "сильная морось"
        56, 57 -> "ледяная морось"
        61 -> "небольшой дождь"
        63 -> "дождь"
        65 -> "сильный дождь"
        66, 67 -> "ледяной дождь"
        71 -> "небольшой снег"
        73 -> "снег"
        75 -> "сильный снег"
        77 -> "снежная крупа"
        80 -> "небольшой ливень"
        81 -> "ливень"
        82 -> "сильный ливень"
        85 -> "небольшой снегопад"
        86 -> "сильный снегопад"
        95 -> "гроза"
        96, 99 -> "гроза с градом"
        else -> ""
    }

    /** Описание: для осадков, тумана и грозы к ним добавляется облачность («пасмурно, небольшой дождь»). */
    fun describe(code: Int, cloud: Int): String {
        val t = codeText(code)
        if (code <= 3) return t
        val c = cloudWord(cloud)
        return if (c.isEmpty()) t else if (t.isEmpty()) c else "$c, $t"
    }

    /** Пиктограмма (эмодзи) для кода погоды. */
    fun icon(code: Int, isDay: Boolean): String = when (code) {
        0 -> if (isDay) "☀️" else "🌙"
        1 -> if (isDay) "🌤️" else "🌙"
        2 -> if (isDay) "⛅" else "☁️"
        3 -> "☁️"
        45, 48 -> "🌫️"
        51, 53, 55, 56, 57, 80, 81 -> "🌦️"
        61, 63, 65, 66, 67, 82 -> "🌧️"
        71, 73, 75, 77, 85, 86 -> "🌨️"
        95, 96, 99 -> "⛈️"
        else -> "🌡️"
    }

    /** Строка для киоска: «+3°, пасмурно, небольшой дождь». */
    fun shortText(s: Snap): String = (temp(s.temp) + ", " + describe(s.code, s.cloud)).trimEnd(',', ' ')

    /** Разбор ответа `/v1/forecast` (поля current и daily за два дня). IllegalStateException, если данных нет. */
    fun parseForecast(body: String, label: String, now: Long): Snap {
        val j = JSONObject(body)
        val c = j.optJSONObject("current") ?: throw IllegalStateException("в ответе нет текущей погоды")
        val d = j.optJSONObject("daily") ?: throw IllegalStateException("в ответе нет прогноза")
        if (!c.has("temperature_2m")) throw IllegalStateException("в ответе нет температуры")
        fun day(key: String, i: Int) = d.optJSONArray(key)?.let { if (i < it.length() && !it.isNull(i)) it.optDouble(i) else Double.NaN } ?: Double.NaN
        val todayMax = day("temperature_2m_max", 0)
        val todayMin = day("temperature_2m_min", 0)
        return Snap(
            label, now, c.optString("time").substringAfter('T'),
            c.optDouble("temperature_2m"), c.optDouble("apparent_temperature", c.optDouble("temperature_2m")),
            c.optInt("weather_code"), c.optInt("cloud_cover"), c.optDouble("wind_speed_10m", 0.0), c.optInt("is_day", 1) == 1,
            todayMin, todayMax, day("precipitation_probability_max", 0).let { if (it.isNaN()) -1 else it.roundToInt() }, day("weather_code", 0).let { if (it.isNaN()) 0 else it.toInt() },
            day("temperature_2m_min", 1), day("temperature_2m_max", 1), day("precipitation_probability_max", 1).let { if (it.isNaN()) -1 else it.roundToInt() }, day("weather_code", 1).let { if (it.isNaN()) 0 else it.toInt() }
        )
    }

    /** Блок для подсказки модели. [s] == null: данных нет или они устарели. */
    fun promptText(s: Snap?, place: String): String {
        if (s == null) return "Данных о погоде сейчас нет: если спросят, скажи, что не знаешь, и ничего не выдумывай."
        fun range(a: Double, b: Double) = if (a.isNaN() || b.isNaN()) "" else "от ${temp(a)} до ${temp(b)}"
        fun pop(p: Int) = if (p >= 0) ", вероятность осадков $p%" else ""
        val sb = StringBuilder("Погода (${s.label.ifEmpty { place }}, данные на ${s.time}): сейчас ${temp(s.temp)}")
        if (kotlin.math.abs(s.feels - s.temp) >= 2) sb.append(", ощущается как ${temp(s.feels)}")
        sb.append(", ${describe(s.code, s.cloud)}, ветер ${s.wind.roundToInt()} м/с; ")
        sb.append("сегодня ${range(s.todayMin, s.todayMax)}${pop(s.todayPop)}, ${describe(s.todayCode, 0)}; ")
        sb.append("завтра ${range(s.tomMin, s.tomMax)}${pop(s.tomPop)}, ${describe(s.tomCode, 0)}. ")
        sb.append("О погоде говори только по этим данным.")
        return sb.toString().replace(" ,", ",").replace(", ,", ",").replace("  ", " ")
    }

    private val WEEKDAYS = listOf("понедельник", "вторник", "среда", "четверг", "пятница", "суббота", "воскресенье")
    private val MONTHS = listOf("января", "февраля", "марта", "апреля", "мая", "июня", "июля", "августа", "сентября", "октября", "ноября", "декабря")

    /** «Сейчас среда, 4 ноября 2026 года, 19:55.» */
    fun nowLine(t: LocalDateTime): String =
        "Сейчас ${WEEKDAYS[t.dayOfWeek.value - 1]}, ${t.dayOfMonth} ${MONTHS[t.monthValue - 1]} ${t.year} года, ${"%02d:%02d".format(t.hour, t.minute)}."
}
