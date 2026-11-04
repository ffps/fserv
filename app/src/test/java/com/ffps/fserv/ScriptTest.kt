package com.ffps.fserv

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ScriptTest {
    private val example = """
        task "Подъём"
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

    private val zonesJson = JSONObject("""{"bed":{"rect":[0.1,0.2,0.9,0.9],"exclude":[[0,0,0.2,0.2]]}}""")
    private val voiceJson = JSONObject("""{"volume":70,"lang":"ru-RU","rate":95}""")
    private val bed = setOf("bed")

    private fun err(text: String): String {
        val e = Script.validate(text)
        assertNotNull("ожидалась ошибка", e)
        return e!!
    }

    private fun expect(text: String, part: String) {
        val e = err(text)
        assertTrue("ошибка «$e» не содержит «$part»", e.contains(part))
    }

    private fun task(vararg steps: String) = "task cron=\"0 9 * * *\"\n" + steps.joinToString("\n") { "  $it" }

    @Test fun exampleParsesAndIsValid() {
        assertNull(Script.validate(example, "wake", bed))
        assertNotNull(Script.validate(example, "wake"))
        val p = Script.parse(example, "wake")
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
        assertEquals(1, p.tasks.size)
        assertEquals("wake", p.tasks[0].id)
        assertEquals(1, p.tasks[0].startLine)
        assertEquals(17, p.tasks[0].endLine)
    }

    @Test fun errorsHaveLineNumbers() {
        val text = "task\n  cron=\"0 9 * * *\"\n  say \"a\"\n  play system:alarm fade=3s"
        val e = err(text)
        assertTrue(e, e.startsWith("строка 4: "))
        assertTrue(e, e.contains("у play нет параметра «fade»; допустимы: volume, fade_in"))
        expect(task("say \"a\"", "bogus 5"), "неизвестное действие «bogus»")
        expect(task("say hello"), "в кавычках")
        expect(task("say \"привет"), "не закрыта кавычка")
        expect(task("delay"), "нужна длительность")
        expect(task("delay 5x"), "длительность")
        expect(task("say \"a\"", "  say \"b\""), "лишний отступ")
        expect("task\n   say \"a\"", "кратен 2")
        expect("task\n\tsay \"a\"", "табуляц")
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
        expect("task\n  say \"a\"\ntask\n  say \"b\"", "одна задача")
        expect("voice volume=300\ntask\n  say \"a\"", "на верхнем уровне")
        expect("# только комментарий", "нет строки task")
        expect("task wake\n  say \"a\"", "у task нет настройки «wake»")
        expect(task("watch 5s zone=nope"), "зона «nope»")
        expect(task("say \"a\" volume=1 volume=2"), "дважды")
        expect(task("chat \"?\""), "имя темы")
        expect(task("background\n    watch 5s"), "внутри background")
        expect("task\n  say \"a\"\n  cron=\"0 9 * * *\"", "в строке task или сразу под ней")
        expect("task crn=1\n  say \"a\"", "у task нет настройки «crn»")
        expect("task cron=\"0 9 * * *\"\n  cron=\"0 8 * * *\"\n  say \"a\"", "уже задана")
        expect("task \"A\" title=\"B\"\n  say \"a\"", "уже задана")
        expect("task", "нет шагов")
        expect(task("play a.mp3 b.mp3"), "параметры пишутся как ключ=значение")
    }

    @Test fun lineNumbersMatchEditor() {
        val text = "# заголовок\n\ntask\n  say \"x\"\n\n  say \"y\"\n  telegram \"z\" files=[q]\n"
        val e = err(text)
        assertTrue(e, e.startsWith("строка 7: "))
    }

    @Test fun spans() {
        val text = "# про a\ntask\n  say \"x\"\n  # внутри\n\n  say \"y\"\n\n# конец\n"
        val p = Script.parse(text, "a")
        assertEquals(listOf(2), p.tasks.map { it.startLine })
        assertEquals(listOf(6), p.tasks.map { it.endLine })
        assertEquals("a", p.tasks[0].id)
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
        assertNull(Script.validate(task("stop")))
        assertNull(Script.validate(task("stop \"не нужно {time}\"")))
    }

    @Test fun voiceAndZoneSettings() {
        assertNull(TaskSchema.checkVoice(voiceJson))
        assertNull(TaskSchema.checkVoice(JSONObject("""{"rate":95,"pitch":100,"volume":70}""")))
        assertTrue(TaskSchema.checkVoice(JSONObject("""{"rate":0.95}"""))!!.contains("rate")) // с 0.19.0 проценты, дробные не годятся
        assertTrue(TaskSchema.checkVoice(JSONObject("""{"rate":1}"""))!!.contains("rate"))
        assertTrue(TaskSchema.checkVoice(JSONObject("""{"pitch":250}"""))!!.contains("pitch"))
        assertTrue(TaskSchema.validPct(100, 30, 300) && !TaskSchema.validPct(100.5, 30, 300) && !TaskSchema.validPct("100", 30, 300))
        assertTrue(TaskSchema.checkVoice(JSONObject("""{"volume":300}"""))!!.contains("volume"))
        assertNull(TaskSchema.checkZone(zonesJson.getJSONObject("bed")))
        assertTrue(TaskSchema.checkZone(JSONObject("""{"rect":[0.5,0.5,0.2,0.2]}"""))!!.contains("x1 < x2"))
        assertTrue(TaskSchema.checkZone(JSONObject("""{}"""))!!.contains("нужен rect"))
    }

    @Test fun askAgainList() {
        assertNull(Script.validate(task("ask [\"Назови число\", \"Скажи число\"] assign=p kind=number listen=5s retries=2 again=[\"Не расслышал!\", \"Не понял, скажи снова\"]")))
        assertNull(Script.validate(task("ask \"Число?\" assign=p kind=number again=\"Ещё раз\"")))
        expect(task("ask \"Число?\" assign=p again=[]"), "again")
        expect(task("ask \"Число?\" assign=p again=5"), "again")
    }

    @Test fun taskSetIsolatesErrors() {
        val ok = "task \"Хорошая\" cron=\"0 8 * * *\"\n  watch 5s zone=bed assign=m\n  say \"a\"\n"
        val l = TaskSet.parseSet(mapOf(
            "good.txt" to ok,
            "broken.txt" to "task\n  say \"a\"\n  bogus\n", "nozone.txt" to "task\n  watch 5s zone=zzz\n  say \"a\"\n",
            "bad id.txt" to "task\n  say \"a\"\n"
        ), voiceJson, zonesJson) { true }
        assertEquals(listOf("good"), (0 until l.root.getJSONArray("tasks").length()).map { l.root.getJSONArray("tasks").getJSONObject(it).getString("id") })
        assertEquals(setOf("broken", "nozone", "bad id"), l.errors.keys)
        assertTrue(l.errors.getValue("broken"), l.errors.getValue("broken").startsWith("строка 3: "))
        assertTrue(l.errors.getValue("nozone").contains("зона «zzz»"))
        assertEquals(70, l.root.getJSONObject("voice").getInt("volume"))
        // без зон в настройках задача с зоной становится ошибочной
        assertTrue(TaskSet.parseSet(mapOf("good.txt" to ok)) { true }.errors.containsKey("good"))
    }

    @Test fun taskSetValidation() {
        assertNull(TaskSet.validateSet(mapOf("a.txt" to "task\n  say \"x\"\n")) { true })
        assertTrue(TaskSet.validateSet(mapOf("_common.txt" to "voice volume=1\n")) { true }!!.contains("недопустимое имя"))
        assertTrue(TaskSet.validateSet(mapOf("a.txt" to "task\n  oops\n")) { true }!!.startsWith("tasks/a.txt: строка 2: "))
        assertTrue(TaskSet.validateSet(mapOf("../x.txt" to "task\n  say \"x\"\n")) { true }!!.contains("недопустимое имя"))
        assertTrue(TaskSet.validateSet(mapOf("a.json" to "{}")) { true }!!.contains("недопустимое имя"))
        assertNull(TaskSet.checkId("wake-up_2"))
        for (bad in listOf("", "_x", "-x", "a b", "а", "x".repeat(41), "a.b")) assertNotNull(bad, TaskSet.checkId(bad))
    }

    /** Примеры из script.md обязаны проходить проверку: описание не должно расходиться с разбором. */
    @Test fun examplesInDocsAreValid() {
        val f = listOf("../script.md", "script.md").map { java.io.File(it) }.firstOrNull { it.isFile } ?: return
        val blocks = Regex("```\\n(.*?)```", RegexOption.DOT_MATCHES_ALL).findAll(f.readText()).map { it.groupValues[1] }.toList()
        val zones = setOf("bed", "door", "room") // имена зон, которые в примерах считаются созданными на вкладке «Камера»
        var checked = 0
        for (b in blocks) {
            val top = b.lines().filter { it.isNotEmpty() && !it.startsWith(" ") && !it.startsWith("#") }
            if (top.isEmpty()) continue
            val isTask = top.all { it == "task" || it.startsWith("task ") }
            if (isTask) assertNull("пример задачи из script.md не проходит проверку:\n$b", Script.validate(b, "x", zones))
            else continue
            checked++
        }
        assertTrue("в script.md найдено слишком мало примеров ($checked)", checked >= 5)
    }

    @Test fun taskSettingsInHeaderOrBelow() {
        val one = Script.parse("task title=\"Подъём\" cron=\"0 8 * * *\" timeout=40m enabled=false\n  say \"a\"\n").root.getJSONArray("tasks").getJSONObject(0)
        val two = Script.parse("task \"Подъём\" cron=\"0 8 * * *\"\n  timeout=40m\n  enabled=false\n  say \"a\"\n").root.getJSONArray("tasks").getJSONObject(0)
        for (k in listOf("title", "cron", "timeout", "enabled")) assertEquals(one.get(k), two.get(k))
        assertEquals("Подъём", one.getString("title"))
        assertEquals(false, one.getBoolean("enabled"))
        assertEquals(1, two.getJSONArray("steps").length())
        assertNull(Script.validate("task title=\"x\" cron=\"0 9 * * *\"\n  say \"a\"\n"))
        expect("task title=5\n  say \"a\"", "title")
    }

    @Test fun pausesInSay() {
        assertNull(Script.validate(task("say \"Просыпайся! {pause:1s} Пора вставать. {pause:2s..5s} Сейчас {time}!\"")))
        assertNull(Script.validate(task("say \"{pause:500ms}привет\" wait=false")))
        expect(task("say \"а {pause:5x} б\""), "пауза")
        expect(task("say \"а {pause:3m} б\""), "не длиннее 2m")
        expect(task("telegram \"а {pause:1s} б\""), "только в say")
        expect(task("log \"а {pause:1s} б\""), "только в say")
    }

    @Test fun phraseLists() {
        assertNull(Script.validate(task("say [\"Привет\", \"Здравствуйте, {time}\"]", "ask [\"Как дела?\", \"Вы здоровы?\"] assign=ok")))
        val st = Script.parse(task("say [\"а\", \"б\"] volume=50")).root.getJSONArray("tasks").getJSONObject(0).getJSONArray("steps").getJSONObject(0)
        assertEquals(2, st.getJSONArray("say").length())
        assertEquals("б", st.getJSONArray("say").getString(1))
        expect(task("say []"), "в списке")
        expect(task("say [привет]"), "в кавычках")
        expect(task("say [\"{nope}\"]"), "{nope}")
        expect(task("say [\"а {pause:5x} б\"]"), "пауза")
        expect(task("telegram [\"а\", \"б\"]"), "в кавычках")
        expect(task("log [\"а\"]"), "в кавычках")
    }

    @Test fun photoSize() {
        assertNull(Script.validate(task("photo size=1280x720 assign=p", "telegram \"x\" files=[p]")))
        expect(task("photo size=big"), "size")
        expect(task("photo size=1280"), "size")
        expect(task("photo resolution=1280x720"), "у photo нет параметра «resolution»")
    }

    @Test fun preemptSetting() {
        assertNull(Script.validate("task preempt=true\n  say \"a\"\n"))
        assertNull(Script.validate("task \"Звонок\" preempt=false\n  say \"a\"\n"))
        assertTrue(Script.validate("task preempt=yes\n  say \"a\"\n")!!.contains("preempt"))
        assertEquals(true, Script.parse("task preempt=true\n  say \"a\"\n").root.getJSONArray("tasks").getJSONObject(0).getBoolean("preempt"))
    }

    @Test fun telegramConfirm() {
        assertNull(Script.validate(task("telegram \"CALL {time}\" confirm=10s assign=sent", "if sent", "  say \"Я сообщил\"", "else", "  say \"Нет связи\"")))
        assertNull(Script.validate(task("telegram \"x\" confirm=5s")))
        assertEquals(true, Script.parse(task("telegram \"x\" confirm=5s assign=ok")).root.getJSONArray("tasks").getJSONObject(0).getJSONArray("steps").getJSONObject(0).has("confirm"))
        expect(task("telegram \"x\" assign=sent"), "только вместе с confirm")
        expect(task("telegram \"x\" confirm=500ms"), "confirm")
        expect(task("telegram \"x\" confirm=5m"), "confirm")
        expect(task("telegram \"x\" confirm=5s assign=time"), "занято")
        expect(task("telegram \"x\" confirm=5s assign=a-b"), "assign")
        expect(task("telegram \"x\" confirm=5s", "say \"{sent}\""), "sent")
        expect(task("telegram \"x\" wait=true"), "нет параметра")
    }

    @Test fun chatStep() {
        val topics = setOf("walk", "weather")
        fun v(vararg steps: String) = Script.validate(task(*steps), "x", emptySet(), topics)
        assertNull(v("chat walk"))
        assertNull(v("chat weather for=5m turns=8 assign=talk alert=sos volume=70", "if sos", "  say \"тревога\"", "telegram \"Поговорили: {talk}\""))
        assertNull(Script.validate(task("chat anything"))) // без списка тем имя не проверяется
        assertTrue(v("chat unknown")!!.contains("темы «unknown» нет"))
        assertTrue(v("chat")!!.contains("имя темы"))
        assertTrue(v("chat walk for=10s")!!.contains("for"))
        assertTrue(v("chat walk for=1h")!!.contains("for"))
        assertTrue(v("chat walk turns=0")!!.contains("turns"))
        assertTrue(v("chat walk turns=31")!!.contains("turns"))
        assertTrue(v("chat walk assign=time")!!.contains("занято"))
        assertTrue(v("chat walk assign=a alert=a")!!.contains("по-разному"))
        assertTrue(v("chat walk volume=101")!!.contains("volume"))
        assertTrue(v("chat walk loop")!!.contains("нет параметра"))
        assertTrue(v("background", "  chat walk")!!.contains("внутри background"))
        assertTrue(v("say \"{talk}\"")!!.contains("talk"))
        val p = Script.parse(task("chat walk for=5m turns=8 assign=talk")).root.getJSONArray("tasks").getJSONObject(0).getJSONArray("steps").getJSONObject(0)
        assertEquals("walk", p.getString("chat"))
        assertEquals(8, p.getInt("turns"))
        // набор задач: темы берутся из настроек «Чат»
        val chat = JSONObject("""{"rules":"","topics":{"walk":"прогулка"}}""")
        val ok = TaskSet.parseSet(mapOf("a.txt" to task("chat walk"), "b.txt" to task("chat nope")), null, null, chat) { true }
        assertEquals(setOf("b"), ok.errors.keys)
        assertEquals("прогулка", ok.root.getJSONObject("chat").getJSONObject("topics").getString("walk"))
    }

    @Test fun slideStep() {
        assertNull(Script.validate(task("slide hello.svg")))
        assertNull(Script.validate(task("slide [\"hello.svg\",\"hi.gif\"] for=5s fit=contain bg=auto brightness=90 fade_in=1s fade_out=2s tap=hide")))
        assertNull(Script.validate(task("slide a.png bg=\"#d81b60\" fit=cover")))
        assertNull(Script.validate(task("slide off")))
        for (f in listOf("full", "contain", "cover", "width", "height", "none")) assertNull(Script.validate(task("slide a.png fit=$f")))
        expect(task("slide a.png fit=zoom"), "fit")
        expect(task("slide a.png bg=red"), "bg")
        expect(task("slide a.png brightness=0"), "brightness")
        expect(task("slide a.png brightness=101"), "brightness")
        expect(task("slide a.png tap=show"), "tap")
        expect(task("slide a.png for=0s"), "for")
        expect(task("slide off for=5s"), "параметров")
        expect(task("slide"), "slide")
        expect(task("slide a.png hide=5s"), "у slide нет параметра")
        val e = Script.validate(task("slide missing.png"), "x", emptySet(), null, { false })
        assertNotNull(e); assertTrue(e!!.contains("Слайды"))
    }
}
