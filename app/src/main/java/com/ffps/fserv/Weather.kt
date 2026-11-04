package com.ffps.fserv

import com.ffps.fserv.WeatherText.Snap
import com.ffps.fserv.WeatherText.icon
import com.ffps.fserv.WeatherText.nowLine
import com.ffps.fserv.WeatherText.parseCoords
import com.ffps.fserv.WeatherText.parseForecast
import com.ffps.fserv.WeatherText.promptText
import com.ffps.fserv.WeatherText.shortText
import com.ffps.fserv.WeatherText.temp
import org.json.JSONObject
import java.net.URLEncoder
import java.time.ZonedDateTime

/**
 * Погода (Open-Meteo, без ключа). Город задаётся в «Система → Параметры» названием или координатами «широта, долгота».
 * Данные обновляются в фоне (при запуске, при смене настроек и каждые N минут), хранятся на устройстве и никогда не запрашиваются
 * в момент вопроса. Старше трёх периодов (но не меньше трёх часов) данные считаются устаревшими и не показываются.
 * Чистые функции (разбор, описание, текст для модели) отделены от сети и покрыты тестами.
 */
object Weather {

    /** Дата, время и (если погода включена) погода для подсказки модели. */
    fun chatContext(now: ZonedDateTime = ZonedDateTime.now()): String {
        val sb = StringBuilder(nowLine(now.toLocalDateTime()))
        if (enabled()) sb.append("\n").append(promptText(current(), Settings.weatherCity))
        return sb.toString()
    }

    // ---------- состояние и фоновое обновление ----------

    @Volatile private var snap: Snap? = null
    @Volatile private var lastError = ""
    private var started = false
    private const val STATE_KEY = "weather"

    fun enabled(): Boolean = Settings.weatherEveryMin > 0 && Settings.weatherCity.isNotBlank()

    /** Свежие данные или null (выключено, ещё нет данных или устарели). */
    fun current(now: Long = System.currentTimeMillis()): Snap? {
        if (!enabled()) return null
        val s = snap ?: return null
        val maxAge = maxOf(3 * 3600_000L, 3 * Settings.weatherEveryMin * 60_000L)
        return if (now - s.updated > maxAge) null else s
    }

    fun lastError(): String = lastError

    /** Ответ для /api/weather и киоска. */
    fun apiJson(): JSONObject {
        val s = current()
        val j = JSONObject().put("enabled", enabled()).put("available", s != null)
        if (s != null) j.put("icon", icon(s.code, s.isDay)).put("temp", temp(s.temp)).put("text", shortText(s)).put("city", s.label).put("updated", s.updated)
        if (lastError.isNotEmpty()) j.put("error", lastError)
        return j
    }

    @Synchronized
    fun start() {
        if (started) return
        started = true
        try {
            val st = Settings.getStr(STATE_KEY)
            if (st.isNotEmpty()) snap = Snap.fromJson(JSONObject(st))
        } catch (e: Exception) {
            FLog.w("weather", "сохранённая погода не прочитана: ${e.message}")
        }
        Thread({
            var next = 0L
            while (true) {
                try {
                    val now = System.currentTimeMillis()
                    if (Settings.weatherChanged) { Settings.weatherChanged = false; snap = null; next = 0L }
                    if (enabled() && now >= next) {
                        next = now + (if (refresh()) Settings.weatherEveryMin * 60_000L else minOf(Settings.weatherEveryMin, 5) * 60_000L)
                    }
                    Thread.sleep(5_000)
                } catch (e: InterruptedException) {
                    return@Thread
                } catch (e: Throwable) {
                    FLog.w("weather", "сбой обновления: ${e.message}")
                    try { Thread.sleep(60_000) } catch (_: InterruptedException) { return@Thread }
                }
            }
        }, "weather").apply { isDaemon = true }.start()
    }

    /** Один цикл обновления; false при сбое (прежние данные остаются). */
    private fun refresh(): Boolean {
        val city = Settings.weatherCity
        try {
            val (lat, lon, label) = locate(city)
            val url = "https://api.open-meteo.com/v1/forecast?latitude=$lat&longitude=$lon" +
                "&current=temperature_2m,apparent_temperature,weather_code,cloud_cover,wind_speed_10m,is_day" +
                "&daily=weather_code,temperature_2m_max,temperature_2m_min,precipitation_probability_max" +
                "&wind_speed_unit=ms&timezone=auto&forecast_days=2"
            val r = Net.request("weather", "GET", url)
            if (r.code !in 200..299) throw IllegalStateException("HTTP ${r.code}")
            val s = parseForecast(r.body, label, System.currentTimeMillis())
            if (city != Settings.weatherCity) return true // город сменили во время запроса: данные чужие
            snap = s
            lastError = ""
            Settings.putStr(STATE_KEY, s.toJson().toString())
            FLog.d("weather", "обновлено: $label ${shortText(s)}")
            return true
        } catch (e: Exception) {
            lastError = e.message ?: e.javaClass.simpleName
            FLog.w("weather", "не обновлено: $lastError")
            return false
        }
    }

    private var geoFor = ""
    private var geoRes: Triple<Double, Double, String>? = null

    /** Координаты и название места для строки настройки (геокодер запрашивается один раз для каждого введённого названия). */
    private fun locate(city: String): Triple<Double, Double, String> {
        parseCoords(city)?.let { return Triple(it.first, it.second, "") }
        geoRes?.let { if (geoFor == city) return it }
        val url = "https://geocoding-api.open-meteo.com/v1/search?name=" + URLEncoder.encode(city, "UTF-8") + "&count=1&language=ru&format=json"
        val r = Net.request("weather", "GET", url)
        if (r.code !in 200..299) throw IllegalStateException("поиск города: HTTP ${r.code}")
        val a = JSONObject(r.body).optJSONArray("results")
        val o = a?.optJSONObject(0) ?: throw IllegalStateException("город «$city» не найден")
        val label = listOf(o.optString("name"), o.optString("admin1"), o.optString("country")).filter { it.isNotEmpty() }.distinct().joinToString(", ")
        val res = Triple(o.getDouble("latitude"), o.getDouble("longitude"), label)
        geoFor = city
        geoRes = res
        return res
    }

    /** Проверка настройки: что нашлось по введённому городу (для кнопки в настройках). Бросает IllegalStateException с причиной. */
    fun check(city: String): String {
        if (city.isBlank()) throw IllegalStateException("город не задан")
        val (lat, lon, label) = locate(city.trim())
        return (if (label.isEmpty()) "координаты $lat, $lon" else "$label ($lat, $lon)")
    }
}
