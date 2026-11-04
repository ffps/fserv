package com.ffps.fserv

import org.junit.Assert.*
import org.junit.Test
import java.time.LocalDateTime

class WeatherTest {
    private val body = """{"current":{"time":"2026-11-05T00:45","temperature_2m":2.6,"apparent_temperature":-0.4,"weather_code":61,"cloud_cover":90,"wind_speed_10m":4.2,"is_day":0},
        "daily":{"weather_code":[61,3],"temperature_2m_max":[4.1,1.0],"temperature_2m_min":[0.4,-3.2],"precipitation_probability_max":[40,null]}}"""

    @Test fun coords() {
        assertEquals(56.84 to 60.61, WeatherText.parseCoords("56.84, 60.61"))
        assertEquals(-33.9 to 151.2, WeatherText.parseCoords(" -33.9 ; 151.2 "))
        assertNull(WeatherText.parseCoords("Екатеринбург"))
        assertNull(WeatherText.parseCoords("95, 10"))
        assertNull(WeatherText.parseCoords("10, 200"))
        assertNull(WeatherText.parseCoords("56.84"))
    }

    @Test fun temperature() {
        assertEquals("+3°", WeatherText.temp(2.6))
        assertEquals("0°", WeatherText.temp(-0.4))
        assertEquals("-1°", WeatherText.temp(-0.6))
    }

    @Test fun describeAddsCloudsToPrecipitation() {
        assertEquals("пасмурно, небольшой дождь", WeatherText.describe(61, 90))
        assertEquals("небольшой дождь", WeatherText.describe(61, 5))
        assertEquals("облачно, снег", WeatherText.describe(73, 60))
        assertEquals("ясно", WeatherText.describe(0, 100)) // для кодов 0..3 облачность уже в самом описании
        assertEquals("пасмурно", WeatherText.describe(3, 100))
    }

    @Test fun icons() {
        assertEquals("☀️", WeatherText.icon(0, true))
        assertEquals("🌙", WeatherText.icon(0, false))
        assertEquals("🌧️", WeatherText.icon(61, false))
        assertEquals("⛈️", WeatherText.icon(95, true))
        assertEquals("🌡️", WeatherText.icon(1234, true))
    }

    @Test fun parseForecast() {
        val s = WeatherText.parseForecast(body, "Екатеринбург, Свердловская область, Россия", 1000)
        assertEquals("00:45", s.time)
        assertEquals(2.6, s.temp, 0.001)
        assertFalse(s.isDay)
        assertEquals(61, s.code); assertEquals(90, s.cloud)
        assertEquals(4.1, s.todayMax, 0.001); assertEquals(40, s.todayPop)
        assertEquals(-3.2, s.tomMin, 0.001); assertEquals(-1, s.tomPop) // нет данных об осадках: -1
        assertEquals("+3°, пасмурно, небольшой дождь", WeatherText.shortText(s))
    }

    @Test fun parseForecastErrors() {
        try { WeatherText.parseForecast("""{"daily":{}}""", "", 0); fail() } catch (e: IllegalStateException) { }
        try { WeatherText.parseForecast("""{"current":{"weather_code":1},"daily":{}}""", "", 0); fail() } catch (e: IllegalStateException) { }
    }

    @Test fun snapRoundTrip() {
        val s = WeatherText.parseForecast(body, "Город", 5000)
        val t = WeatherText.Snap.fromJson(s.toJson())
        assertEquals(s.label, t.label); assertEquals(s.updated, t.updated); assertEquals(s.temp, t.temp, 0.0001)
        assertEquals(s.tomPop, t.tomPop); assertEquals(s.isDay, t.isDay)
    }

    @Test fun promptText() {
        val s = WeatherText.parseForecast(body, "Екатеринбург", 0)
        val t = WeatherText.promptText(s, "")
        assertTrue(t, t.contains("Екатеринбург"))
        assertTrue(t, t.contains("сейчас +3°"))
        assertTrue(t, t.contains("ощущается как 0°")) // разница больше 2 градусов
        assertTrue(t, t.contains("ветер 4 м/с"))
        assertTrue(t, t.contains("сегодня от 0° до +4°, вероятность осадков 40%"))
        assertTrue(t, t.contains("завтра от -3° до +1°, пасмурно."))
        assertFalse(t, t.contains("-1%"))
        assertTrue(WeatherText.promptText(null, "Город").contains("нет"))
    }

    @Test fun nowLine() {
        assertEquals("Сейчас среда, 4 ноября 2026 года, 19:05.", WeatherText.nowLine(LocalDateTime.of(2026, 11, 4, 19, 5)))
        assertEquals("Сейчас воскресенье, 1 марта 2026 года, 00:00.", WeatherText.nowLine(LocalDateTime.of(2026, 3, 1, 0, 0)))
    }
}
