package com.ffps.fserv

import org.junit.Assert.*
import org.junit.Test

class NoticeTextTest {
    private fun type(id: String) = NoticeText.byId(id)!!

    @Test fun defaultsAreValidAndStartWithPrefix() {
        for (t in NoticeText.TYPES) {
            assertNull(t.id, NoticeText.validate(t, t.default))
            assertTrue(t.id, t.default.startsWith("fServe {info.device.model} {info.device.ips[0]}\n"))
        }
        assertEquals(listOf("ping", "taskError", "speech", "alarm", "update"), NoticeText.TYPES.map { it.id })
    }

    @Test fun unknownVariableIsRejected() {
        val e = NoticeText.validate(type("taskError"), "Задача {taskk}")
        assertTrue(e, e != null && e.contains("{taskk}") && e.contains("{task}") && e.contains("{duration}"))
        assertNull(NoticeText.validate(type("taskError"), "{task} {message} {status} {source} {duration} {time} {date} {info.battery.level} {settings.voice.volume} {info.device.ips[0]}"))
        assertNotNull(NoticeText.validate(type("update"), "{task}")) // у обновления нет {task}
        assertNotNull(NoticeText.validate(type("ping"), "x".repeat(1001)))
    }

    @Test fun valuesAreEscapedButTemplateIsHtml() {
        val s = NoticeText.fill("<b>Тревога</b>: «{said}» & {reply}", mapOf("said" to "если a<b", "reply" to "<script>")) { "" }
        assertEquals("<b>Тревога</b>: «если a&lt;b» & &lt;script&gt;", s)
        assertEquals("a&amp;b", NoticeText.fill("{task}", mapOf("task" to "a&b")) { "" })
    }

    @Test fun pathsUseLookupAndIndex() {
        val seen = ArrayList<String>()
        val s = NoticeText.fill("fServe {info.device.model} {info.device.ips[0]}|{settings.voice.volume}|{unknown}", emptyMap()) { seen.add(it); "<" + it + ">" }
        assertEquals(listOf("info.device.model", "info.device.ips[0]", "settings.voice.volume"), seen)
        assertEquals("fServe &lt;info.device.model&gt; &lt;info.device.ips[0]&gt;|&lt;settings.voice.volume&gt;|{unknown}", s)
    }

    @Test fun indexPathLookup() {
        val j = org.json.JSONObject("""{"device":{"ips":["192.168.1.5","10.0.0.2"]}}""")
        assertEquals("192.168.1.5", SettingsPath.lookup(j, "device.ips[0]"))
        assertEquals("10.0.0.2", SettingsPath.lookup(j, "device.ips.1"))
        assertNull(SettingsPath.lookup(j, "device.ips[5]"))
    }
}
