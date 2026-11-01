package com.ffps.fserv

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ScriptTest {
    private val example = """
        # общие настройки
        voice volume=70 lang=ru-RU rate=0.95
        zone bed rect=[0.1,0.2,0.9,0.9] exclude=[[0,0,0.2,0.2]]

        task wake "Подъём"
          cron="0 8 * * *"
          timeout=60m

          repeat 10 until=moved
            watch 30s poll=600ms assign=moved zone=bed
            if not moved
              background
                play system:alarm volume=80 fade_in=5s loop pause=[3s,5s,10s,20s]
                delay 10s
                say "Просыпайся! Пора вставать."
          if moved
            say "Доброе утро!"  # комментарий
            photo assign=photo1
            telegram "Встал в <b>{time}</b> #не комментарий" files=[photo1]
          else
            telegram "К {time} не встал"
    """.trimIndent()

    private fun err(text: String): String {
        val e = Script.validate(text)
        assertNotNull("ожидалась ошибка", e)
        return e!!
    }

    private fun expect(text: String, part: String) {
        val e = err(text)
        assertTrue("ошибка «$e» не содержит «$part»", e.contains(part))
    }

    private fun task(vararg steps: String) = "task t cron=\"0 9 * * *\"\n" + steps.joinToString("\n") { "  $it" }

    @Test fun exampleParsesAndIsValid() {
        assertNull(Script.validate(example))
        val p = Script.parse(example)
        val t = p.root.getJSONArray("tasks").getJSONObject(0)
        assertEquals("wake", t.getString("id"))
        assertEquals("Подъём", t.getString("title"))
        assertEquals("0 8 * * *", t.getString("cron"))
        assertEquals("60m", t.getString("timeout"))
        val rep = t.getJSONArray("steps").getJSONObject(0)
        assertEquals(10, rep.getJSONObject("repeat").getInt("times"))
        assertEquals("moved", rep.getJSONObject("repeat").getString("until"))
        val watch = rep.getJSONArray("do").getJSONObject(0).getJSONObject("watch")
        assertEquals("30s", watch.getString("for"))
        assertEquals("moved", watch.getString("assign"))
        assertTrue(watch.getBoolean("motion"))
        val play = rep.getJSONArray("do").getJSONObject(1).getJSONArray("then").getJSONObject(0).getJSONArray("background").getJSONObject(0)
        assertEquals(true, play.getBoolean("loop"))
        assertEquals(4, play.getJSONArray("pause").length())
        val iff = t.getJSONArray("steps").getJSONObject(1)
        assertEquals("Встал в <b>{time}</b> #не комментарий", iff.getJSONArray("then").getJSONObject(2).getString("telegram"))
        assertEquals(1, iff.getJSONArray("else").length())
        assertEquals("ru-RU", p.root.getJSONObject("voice").getString("lang"))
        assertEquals(2, p.globalLines.size)
        assertEquals(1, p.tasks.size)
        assertEquals(5, p.tasks[0].startLine)
        assertEquals(21, p.tasks[0].endLine)
    }

    @Test fun errorsHaveLineNumbers() {
        val text = "task t\n  cron=\"0 9 * * *\"\n  say \"a\"\n  play system:alarm fade=3s"
        val e = err(text)
        assertTrue(e, e.startsWith("строка 4: "))
        assertTrue(e, e.contains("у play нет параметра «fade»; допустимы: volume, fade_in"))
        expect(task("say \"a\"", "bogus 5"), "неизвестное действие «bogus»")
        expect(task("say hello"), "в кавычках")
        expect(task("say \"привет"), "не закрыта кавычка")
        expect(task("delay"), "нужна длительность")
        expect(task("delay 5x"), "длительность")
        expect(task("say \"a\"", "  say \"b\""), "лишний отступ")
        expect("task t\n   say \"a\"", "кратен 2")
        expect("task t\n\tsay \"a\"", "табуляц")
        expect(task("else"), "else без if")
        expect(task("if moved\n    say \"a\""), "moved")
        expect(task("if not x"), "вложенные шаги")
        expect(task("repeat 3"), "вложенные шаги")
        expect(task("photo assign=p\n  telegram \"x\" files=[q]"), "files")
        expect(task("say \"{nope}\""), "{nope}")
        expect(task("play system:alarm loop"), "for")
        expect(task("play system:alarm pause=3s"), "loop")
        expect(task("play a.mp3").replace("cron=\"0 9 * * *\"", "cron=\"61 * * * *\""), "cron")
        expect("hello", "на верхнем уровне")
        expect("task t\n  say \"a\"\ntask t\n  say \"b\"", "уже есть")
        expect("zone z rect=[0.5,0.5,0.2,0.2]\ntask t\n  say \"a\"", "x1 < x2")
        expect("voice volume=300\ntask t\n  say \"a\"", "volume")
        expect(task("watch 5s zone=nope"), "зона «nope»")
        expect(task("say \"a\" volume=1 volume=2"), "дважды")
        expect(task("ask \"?\""), "этапе 5")
        expect(task("background\n    watch 5s"), "внутри background")
        expect("task t\n  say \"a\"\n  cron=\"0 9 * * *\"", "в строке task или сразу под ней")
        expect("task t crn=1\n  say \"a\"", "у task нет настройки «crn»")
        expect("task t cron=\"0 9 * * *\"\n  cron=\"0 8 * * *\"\n  say \"a\"", "уже задана")
        expect("task t \"A\" title=\"B\"\n  say \"a\"", "уже задана")
        expect("task t", "нет шагов")
        expect(task("play a.mp3 b.mp3"), "параметры пишутся как ключ=значение")
    }

    @Test fun lineNumbersMatchEditor() {
        val text = "# заголовок\n\ntask a\n  say \"x\"\n\ntask b\n  say \"y\"\n  telegram \"z\" files=[q]\n"
        val e = err(text)
        assertTrue(e, e.startsWith("строка 8: "))
    }

    @Test fun spans() {
        val text = "voice volume=50\n\n# про a\ntask a\n  say \"x\"\n  # внутри\n\n  say \"y\"\n\n# про b\ntask b\n  say \"z\"\n"
        val p = Script.parse(text)
        assertEquals(listOf(4, 11), p.tasks.map { it.startLine })
        assertEquals(listOf(8, 12), p.tasks.map { it.endLine })
        assertEquals(listOf(1), p.globalLines)
    }

    @Test fun valuesAndEscapes() {
        val p = Script.parse(task("say \"a \\\"b\\\"\\nстрока2\" volume=40"))
        val s = p.root.getJSONArray("tasks").getJSONObject(0).getJSONArray("steps").getJSONObject(0)
        assertEquals("a \"b\"\nстрока2", s.getString("say"))
        assertEquals(40, s.getInt("volume"))
        val q = Script.parse(task("play [a.mp3, \"b.mp3\"] wait=false")).root
            .getJSONArray("tasks").getJSONObject(0).getJSONArray("steps").getJSONObject(0)
        assertEquals(2, q.getJSONArray("play").length())
        assertEquals(false, q.getBoolean("wait"))
        assertNull(Script.validate(task("stop"), { true }))
        assertNull(Script.validate(task("stop \"не нужно {time}\"")))
    }

    private val file = """
        # настройки
        voice volume=50

        # будильник
        task a title="Первая" cron="0 8 * * *"
          # внутренний комментарий
          say "а"

        # тонометр
        task b
          say "б"
    """.trimIndent() + "\n"

    @Test fun replaceOneTaskKeepsRestOfFile() {
        val e = ScriptEdit.replaceTask(file, "a", "task a title=\"Новая\"\n  say \"новый текст\"\n")
        assertNull(e.error)
        val t = e.text!!
        assertTrue(t, t.contains("# настройки\nvoice volume=50\n\n# будильник\ntask a title=\"Новая\"\n  say \"новый текст\"\n\n# тонометр\ntask b\n  say \"б\"\n"))
        assertEquals(5, e.start)
        assertNull(Script.validate(t))
    }

    @Test fun addRenameDeleteTask() {
        val added = ScriptEdit.replaceTask(file, "", "task c\n  say \"в\"\n").text!!
        assertTrue(added.endsWith("task b\n  say \"б\"\n\ntask c\n  say \"в\"\n"))
        val renamed = ScriptEdit.replaceTask(added, "b", "task b2\n  say \"б\"\n").text!!
        assertTrue(renamed.contains("# тонометр\ntask b2\n"))
        val deleted = ScriptEdit.deleteTask(renamed, "a").text!!
        assertTrue(deleted, deleted.startsWith("# настройки\nvoice volume=50\n\n# будильник\n\n# тонометр\ntask b2") || deleted.startsWith("# настройки\nvoice volume=50\n\n# будильник\n# тонометр"))
        assertNull(Script.validate(deleted))
        assertNotNull(ScriptEdit.deleteTask(deleted, "zzz").error)
        assertNotNull(ScriptEdit.replaceTask(file, "zzz", "task z\n  say \"x\"\n").error)
        assertNotNull(ScriptEdit.replaceTask(file, "a", "voice volume=1\n").error)
        assertNotNull(ScriptEdit.replaceTask(file, "a", "task a\n  say \"x\"\ntask q\n  say \"y\"\n").error)
    }

    @Test fun duplicateIdAndLocalizedErrors() {
        val e = ScriptEdit.replaceTask(file, "a", "task b\n  say \"x\"\n")
        val err = Script.validate(e.text!!)!!
        assertTrue(err, err.contains("уже есть"))
        val bad = ScriptEdit.replaceTask(file, "a", "task a\n  say \"x\"\n  play system:alarm loop\n")
        val msg = Script.validate(bad.text!!)!!
        val local = ScriptEdit.localize(msg, bad.start, bad.count, "в другом месте файла")
        assertTrue(local, local.startsWith("строка 3: "))
        val other = ScriptEdit.localize("строка 1: плохо", bad.start, bad.count, "в другом месте файла")
        assertTrue(other, other.startsWith("в другом месте файла, строка 1:"))
    }

    @Test fun globalLines() {
        assertEquals("voice volume=50\n", ScriptEdit.globalText(file))
        val t = ScriptEdit.replaceGlobal(file, "voice volume=70 lang=ru-RU\nzone z rect=[0,0,1,1]\n").text!!
        assertTrue(t, t.startsWith("# настройки\nvoice volume=70 lang=ru-RU\nzone z rect=[0,0,1,1]\n\n# будильник"))
        assertNull(Script.validate(t))
        val none = ScriptEdit.replaceGlobal(file, "").text!!
        assertFalse(none.contains("voice"))
        val fresh = ScriptEdit.replaceGlobal("task a\n  say \"x\"\n", "voice volume=10\n").text!!
        assertEquals("voice volume=10\n\ntask a\n  say \"x\"\n", fresh)
        assertNotNull(ScriptEdit.replaceGlobal(file, "task q\n  say \"y\"\n").error)
    }

    /** Примеры из script.md обязаны проходить проверку: описание не должно расходиться с разбором. */
    @Test fun examplesInDocsAreValid() {
        val f = listOf("../script.md", "script.md").map { java.io.File(it) }.firstOrNull { it.isFile } ?: return
        val blocks = Regex("```\\n(.*?)```", RegexOption.DOT_MATCHES_ALL).findAll(f.readText()).map { it.groupValues[1] }.toList()
        var checked = 0
        for (b in blocks) {
            val top = b.lines().filter { it.isNotEmpty() && !it.startsWith(" ") && !it.startsWith("#") }
            if (top.isEmpty() || !top.all { it.startsWith("task ") || it.startsWith("voice ") || it.startsWith("zone ") }) continue
            assertNull("пример из script.md не проходит проверку:\n$b", Script.validate(b))
            checked++
        }
        assertTrue("в script.md найдено слишком мало примеров ($checked)", checked >= 5)
    }

    @Test fun taskSettingsInHeaderOrBelow() {
        val one = Script.parse("task wake title=\"Подъём\" cron=\"0 8 * * *\" timeout=40m enabled=false\n  say \"a\"\n").root.getJSONArray("tasks").getJSONObject(0)
        val two = Script.parse("task wake \"Подъём\" cron=\"0 8 * * *\"\n  timeout=40m\n  enabled=false\n  say \"a\"\n").root.getJSONArray("tasks").getJSONObject(0)
        for (k in listOf("title", "cron", "timeout", "enabled")) assertEquals(one.get(k), two.get(k))
        assertEquals("Подъём", one.getString("title"))
        assertEquals(false, one.getBoolean("enabled"))
        assertEquals(1, two.getJSONArray("steps").length())
        assertNull(Script.validate("task t title=\"x\" cron=\"0 9 * * *\"\n  say \"a\"\n"))
        expect("task t title=5\n  say \"a\"", "title")
    }

    @Test fun pausesInSay() {
        assertNull(Script.validate(task("say \"Просыпайся! {pause:1s} Пора вставать. {pause:2s..5s} Сейчас {time}!\"")))
        assertNull(Script.validate(task("say \"{pause:500ms}привет\" wait=false")))
        expect(task("say \"а {pause:5x} б\""), "пауза")
        expect(task("say \"а {pause:3m} б\""), "не длиннее 2m")
        expect(task("telegram \"а {pause:1s} б\""), "только в say")
        expect(task("log \"а {pause:1s} б\""), "только в say")
    }

    @Test fun photoSize() {
        assertNull(Script.validate(task("photo size=1280x720 assign=p", "telegram \"x\" files=[p]")))
        expect(task("photo size=big"), "size")
        expect(task("photo size=1280"), "size")
        expect(task("photo resolution=1280x720"), "у photo нет параметра «resolution»")
    }
}
