package com.ffps.fserv

import org.junit.Assert.*
import org.junit.Test

class ListsTest {
    private fun task(vararg steps: String) = "task\n" + steps.joinToString("") { "  $it\n" }

    @Test fun parsesLists() {
        val t = Lists.parse("# слайды\nmascots = [\"kuzya.hi.png\", kuzya.ok.png]   # комментарий\npastels = [\n  \"#77dd77\", \"#7fc7ff\",\n  \"#afdafc\"\n]\n\n")
        assertEquals(setOf("mascots", "pastels"), t.keys)
        assertEquals("[\"kuzya.hi.png\", kuzya.ok.png]", t["mascots"])
        assertTrue(t["pastels"]!!.contains("#afdafc"))
        assertTrue(Lists.parse("").isEmpty())
        assertTrue(Lists.parse("# только комментарии\n\n").isEmpty())
    }

    @Test fun listErrors() {
        fun err(text: String) = Lists.check(text)!!
        assertTrue(err("mascots\n").startsWith("строка 1:"))
        assertTrue(err("a = [\"x\"]\na = [\"y\"]\n").contains("дважды"))
        assertTrue(err("a = [\n \"x\"\n").contains("не закрыта скобка"))
        assertTrue(err("a = \"x\"\n").contains("списком"))
        assertTrue(err("a = []\n").contains("пуст"))
        assertTrue(err("a = [\"x\"] лишнее\n").contains("лишний"))
        assertTrue(err("a = [[\"x\"], \"y\"]\n").contains("вложенные"))
        assertTrue(err("a = [\"x]\n").contains("кавычка"))
        assertTrue(err("1a = [\"x\"]\n").startsWith("строка 1:"))
        assertTrue(err("a = [\"x\"]\n]\n").startsWith("строка 2:"))
        assertEquals("строка 3: список «b» пуст", err("a = [\"x\"]\n\nb = []\n"))
    }

    @Test fun tooManyItems() {
        val big = "a = [" + (1..(Lists.MAX_ITEMS + 1)).joinToString(",") { "x$it" } + "]"
        assertTrue(Lists.check(big)!!.contains("не больше"))
        assertNull(Lists.check("a = [" + (1..Lists.MAX_ITEMS).joinToString(",") { "x$it" } + "]"))
    }

    @Test fun refsInTasks() {
        val lists = Lists.parse("mascots = [\"kuzya.hi.png\", \"kuzya.ok.png\"]\npastels = [\"#77dd77\", \"#fc6\"]\nphrases = [\"Привет\", \"Здравствуйте\"]\n")
        assertNull(Script.validate(task("slide @mascots fade_in=1 fit=width bg=@pastels"), "x", emptySet(), lists))
        val st = Script.parse(task("slide @mascots bg=@pastels"), "x", lists).root.getJSONArray("tasks").getJSONObject(0).getJSONArray("steps").getJSONObject(0)
        assertEquals(2, st.getJSONArray("slide").length())
        assertEquals(2, st.getJSONArray("bg").length())
        assertNull(Script.validate(task("say @phrases volume=50"), "x", emptySet(), lists))
        assertNull(Script.validate(task("play @mascots wait=false"), "x", emptySet(), lists))
    }

    @Test fun refErrors() {
        val lists = Lists.parse("a = [\"x.gif\"]\n")
        assertTrue(Script.validate(task("slide @nope"), "x", emptySet(), lists)!!.contains("не найден"))
        assertTrue(Script.validate(task("slide @nope"))!!.contains("не найден"))
        assertTrue(Script.validate(task("slide [@a]"), "x", emptySet(), lists)!!.contains("внутри списка"))
        assertTrue(Script.validate(task("slide @"), "x", emptySet(), lists)!!.contains("имя списка"))
        assertTrue(Script.validate(task("slide @a\"x\""), "x", emptySet(), lists)!!.isNotEmpty())
        // собака в тексте и в кавычках — не ссылка
        assertNull(Script.validate(task("say \"пишите на a@b.ru и @a\"")))
        assertNull(Script.validate(task("telegram \"привет @user\"")))
    }

    @Test fun missingFilesStillChecked() {
        val lists = Lists.parse("a = [\"x.gif\", \"y.gif\"]\n")
        val e = Script.validate(task("slide @a"), "x", emptySet(), lists, slideExists = { it == "x.gif" })
        assertNotNull(e); assertTrue(e!!.contains("y.gif"))
    }

    @Test fun setWithLists() {
        val lists = Lists.parse("a = [\"x.gif\"]\n")
        val files = mapOf("t.txt" to task("slide @a"))
        assertTrue(TaskSet.parseSet(files) { true }.errors.containsKey("t"))
        assertTrue(TaskSet.parseSet(files, null, null, null, lists) { true }.errors.isEmpty())
        assertNull(TaskSet.validateSet(files, null, null, lists) { true })
        assertTrue(TaskSet.validateSet(files, null, null, emptyMap()) { true }!!.startsWith("tasks/t.txt: строка"))
    }

    @Test fun inlineAndRefs() {
        val t = Lists.parse("a = [\"x.gif\", y.gif]\n")
        assertEquals("slide [\"x.gif\", y.gif] fit=width", Lists.inline("slide @a fit=width", t))
        assertEquals("slide @zz", Lists.inline("slide @zz", t))
        assertEquals(setOf("a", "zz"), Lists.refs("slide @a bg=@zz"))
    }
}
