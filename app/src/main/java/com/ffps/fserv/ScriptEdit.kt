package com.ffps.fserv

/**
 * Правка файла задач по частям: заменяется только нужный фрагмент текста, остальные строки (комментарии, порядок,
 * пустые строки) остаются как были. Чистые функции над текстом, без Android.
 */
object ScriptEdit {
    /** Результат правки: новый текст либо ошибка; [start] и [count] — где в новом тексте лежит заменённый фрагмент. */
    class Edit(val text: String?, val error: String?, val start: Int = 0, val count: Int = 0)

    fun lines(text: String): MutableList<String> = text.replace("\r\n", "\n").replace('\r', '\n').trimEnd('\n').split("\n").toMutableList()

    fun join(l: List<String>): String = l.joinToString("\n").trimEnd('\n') + "\n"

    private fun unreadable(e: Script.ScriptError) = "текущий файл задач не читается (строка ${e.line}: ${e.message}); исправьте его целиком"

    /** Строки задачи [id] как они лежат в файле, либо null. */
    fun taskText(text: String, id: String): String? {
        val p = try { Script.parse(text) } catch (e: Script.ScriptError) { return null }
        val sp = p.tasks.firstOrNull { it.id == id } ?: return null
        return join(lines(text).subList(sp.startLine - 1, sp.endLine))
    }

    /** Общие строки voice и zone, подряд, как в файле. */
    fun globalText(text: String): String {
        val p = try { Script.parse(text) } catch (e: Script.ScriptError) { return "" }
        if (p.globalLines.isEmpty()) return ""
        val all = lines(text)
        return join(p.globalLines.map { all[it - 1] })
    }

    /** Заменяет задачу [originalId] фрагментом [fragment] либо (при пустом [originalId]) дописывает его в конец. */
    fun replaceTask(text: String, originalId: String, fragment: String): Edit {
        if (fragment.isBlank()) return Edit(null, "текст задачи пуст")
        val fl = lines(fragment)
        val own = try { Script.parse(fragment) } catch (e: Script.ScriptError) { return Edit(null, "строка ${e.line}: ${e.message}") }
        if (own.tasks.size != 1 || own.globalLines.isNotEmpty()) return Edit(null, "здесь должна быть одна задача (строка task …); строки voice и zone правятся в общих настройках")
        val cur = try { Script.parse(text) } catch (e: Script.ScriptError) { return Edit(null, unreadable(e)) }
        val all = lines(text)
        val start: Int
        if (originalId.isEmpty()) {
            if (all.size == 1 && all[0].isBlank()) all.clear()
            if (all.isNotEmpty()) all.add("")
            start = all.size + 1
            all.addAll(fl)
        } else {
            val sp = cur.tasks.firstOrNull { it.id == originalId } ?: return Edit(null, "задачи «$originalId» уже нет (её удалили или переименовали), обновите страницу")
            start = sp.startLine
            for (i in sp.startLine..sp.endLine) all.removeAt(sp.startLine - 1)
            all.addAll(sp.startLine - 1, fl)
        }
        return Edit(join(all), null, start, fl.size)
    }

    fun deleteTask(text: String, id: String): Edit {
        val p = try { Script.parse(text) } catch (e: Script.ScriptError) { return Edit(null, unreadable(e)) }
        val sp = p.tasks.firstOrNull { it.id == id } ?: return Edit(null, "задачи «$id» нет")
        val all = lines(text)
        for (i in sp.startLine..sp.endLine) all.removeAt(sp.startLine - 1)
        // не оставляем двойную пустую строку на месте задачи
        val at = sp.startLine - 1
        if (at in 1 until all.size && all[at].isBlank() && all[at - 1].isBlank()) all.removeAt(at)
        return Edit(join(all), null)
    }

    /** Заменяет все строки voice и zone текстом [newText] (на месте первой такой строки либо в начале файла). */
    fun replaceGlobal(text: String, newText: String): Edit {
        if (newText.isNotBlank()) {
            val own = try { Script.parse(newText) } catch (e: Script.ScriptError) { return Edit(null, "строка ${e.line}: ${e.message}") }
            if (own.tasks.isNotEmpty()) return Edit(null, "здесь допустимы только строки voice и zone; задачи правятся на своих вкладках")
        }
        val cur = try { Script.parse(text) } catch (e: Script.ScriptError) { return Edit(null, unreadable(e)) }
        val all = lines(text)
        val first = cur.globalLines.minOrNull()?.minus(1)
        for (n in cur.globalLines.sortedDescending()) all.removeAt(n - 1)
        val ins = if (newText.isBlank()) emptyList() else lines(newText)
        val at = first ?: 0
        all.addAll(at, ins + if (first == null && all.isNotEmpty() && ins.isNotEmpty()) listOf("") else emptyList())
        return Edit(join(all), null)
    }

    /** Переводит номер строки из сообщения [err] в строки фрагмента, начавшегося со строки [start] и занимающего [count] строк. */
    fun localize(err: String, start: Int, count: Int, elsewhere: String): String {
        val n = Regex("""^строка (\d+): """).find(err)?.groupValues?.get(1)?.toIntOrNull() ?: return err
        val msg = err.replace(Regex("""^строка \d+: """), "")
        return if (n in start until start + count) "строка ${n - start + 1}: $msg" else "$elsewhere, строка $n: $msg"
    }
}
