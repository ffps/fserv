package com.ffps.fserv

import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import java.io.File
import java.util.zip.ZipFile

class StarterTest {
    private fun zip(): File = listOf("src/main/assets/starter.zip", "app/src/main/assets/starter.zip", "main/assets/starter.zip").map { File(it) }.first { it.isFile }

    @Test fun starterSetIsValid() {
        ZipFile(zip()).use { zf ->
            val entries = zf.entries().asSequence().filter { !it.isDirectory }.toList()
            assertTrue(entries.none { it.name == "settings.json" })
            fun text(n: String) = zf.getInputStream(zf.getEntry(n)).use { String(it.readBytes(), Charsets.UTF_8) }
            val tasks = entries.filter { it.name.startsWith("tasks/") }.associate { it.name.removePrefix("tasks/") to text(it.name) }
            val sounds = entries.filter { it.name.startsWith("sounds/") }.map { it.name.removePrefix("sounds/") }.toSet()
            val slides = entries.filter { it.name.startsWith("slides/") }.map { it.name.removePrefix("slides/") }.toSet()
            assertTrue(tasks.isNotEmpty())
            assertTrue("нужен sys.mp3 для звука отклика по умолчанию", "sys.mp3" in sounds)
            val lists = Lists.parse(text("lists.txt"))
            assertNull(TaskSet.validateSet(tasks, JSONObject(), JSONObject(), lists, { it in slides }) { it in sounds })
        }
    }

    @Test fun defaultRulesKeepLineBreaks() {
        assertTrue(Chat.DEFAULT_RULES.lines().size >= 7)
        assertTrue(Chat.DEFAULT_RULES.contains("не начинай разговор с них"))
    }
}
