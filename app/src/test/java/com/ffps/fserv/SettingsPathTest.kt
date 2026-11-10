package com.ffps.fserv

import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class SettingsPathTest {
    private val cur = JSONObject("""{"webPort":8080,"voice":{"volume":90,"lang":"ru-RU"},"telegram":{"tokenSet":true,"chatId":"5"},"listen":{"hours":"8..22","level":1}}""")
    private val stored = JSONObject("""{"password":"secret","telegram":{"token":"1234567:AAAAAAAAAAAAAAAAAAAAAAAA"}}""")

    @Test fun lookupAndText() {
        assertEquals(90, SettingsPath.lookup(cur, "voice.volume"))
        assertEquals("8..22", SettingsPath.lookup(cur, "listen.hours"))
        assertNull(SettingsPath.lookup(cur, "voice.rate"))
        assertNull(SettingsPath.lookup(cur, "nope.x"))
        assertEquals("secret", SettingsPath.lookup(stored, "password"))
        assertEquals("true", SettingsPath.text(SettingsPath.lookup(cur, "telegram.tokenSet")))
        assertEquals("", SettingsPath.text(null))
        assertTrue(SettingsPath.text(SettingsPath.lookup(cur, "voice")).contains("\"volume\":90"))
    }

    @Test fun patchKeepsSectionKeys() {
        // voice заменяется целиком, поэтому остальные ключи раздела должны уйти вместе с изменённым
        val p = SettingsPath.patch(cur, stored, "voice.volume", 30)
        assertEquals(30, p.getJSONObject("voice").getInt("volume"))
        assertEquals("ru-RU", p.getJSONObject("voice").getString("lang"))
        assertEquals(90, cur.getJSONObject("voice").getInt("volume")) // исходное не тронуто
        // ключа ещё нет: добавляется
        assertEquals(120, SettingsPath.patch(cur, stored, "voice.rate", 120).getJSONObject("voice").getInt("rate"))
        // раздела нет в текущих: берётся сохранённый или создаётся пустой
        assertEquals("x", SettingsPath.patch(cur, stored, "telegram.token", "x").getJSONObject("telegram").getString("token"))
        assertEquals(1, SettingsPath.patch(cur, stored, "camera.rotation", 1).getJSONObject("camera").length())
        // верхний уровень
        assertEquals(9000, SettingsPath.patch(cur, stored, "webPort", 9000).getInt("webPort"))
        // глубокая вложенность
        assertEquals("m", SettingsPath.patch(cur, stored, "llm.groq.model", "m").getJSONObject("llm").getJSONObject("groq").getString("model"))
    }

    @Test fun pathSyntax() {
        assertTrue(SettingsPath.PATH_RE.matches("voice.volume"))
        assertTrue(SettingsPath.PATH_RE.matches("webPort"))
        assertTrue(SettingsPath.PATH_RE.matches("zones.door.rect"))
        assertFalse(SettingsPath.PATH_RE.matches("voice."))
        assertFalse(SettingsPath.PATH_RE.matches(".voice"))
        assertFalse(SettingsPath.PATH_RE.matches("a b"))
    }
}
