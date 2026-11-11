package com.ffps.fserv

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** Уведомления в Telegram: включение и тексты настраиваются на вкладке «Связь». Хранятся в settings.json (раздел notices). */
object Notices {
    private fun stored(id: String): JSONObject? = Settings.noticesJson().optJSONObject(id)

    fun isOn(id: String): Boolean {
        val t = NoticeText.byId(id) ?: return false
        return !t.hasActive || stored(id)?.optBoolean("on", true) ?: true
    }

    fun text(id: String): String {
        val t = NoticeText.byId(id) ?: return ""
        return stored(id)?.optString("text", "")?.takeIf { it.isNotBlank() } ?: t.default
    }

    private fun lookup(ctx: Context): (String) -> String = { n ->
        if (n.startsWith(SettingsPath.PREFIX)) SettingsApply.read(n.removePrefix(SettingsPath.PREFIX)) else Info.read(ctx, n.removePrefix(SettingsPath.INFO_PREFIX))
    }

    fun render(ctx: Context, text: String, vars: Map<String, String>): String {
        val now = Date()
        val all = vars + mapOf("time" to SimpleDateFormat("HH:mm", Locale.US).format(now), "date" to SimpleDateFormat("yyyy-MM-dd", Locale.US).format(now))
        return NoticeText.fill(text, all, lookup(ctx))
    }

    /** Ставит уведомление в очередь Telegram, если оно включено; возвращает id или null. Не бросает исключений: уведомление не должно ронять задачу. */
    fun send(ctx: Context, id: String, vars: Map<String, String>, force: Boolean? = null): Long? {
        val t = NoticeText.byId(id) ?: return null
        // force — notify задачи, заданный явно: true отправляет даже при снятой галочке, false не отправляет даже при включённой; null — решает галочка
        val on = force ?: isOn(id)
        if (!on) { FLog.d("telegram", "уведомление «${t.title}» ${if (force == false) "выключено параметром notify=false задачи" else "выключено"}, не отправляю"); return null }
        return try { Telegram.enqueue(render(ctx, text(id), vars)) } catch (e: Exception) { FLog.w("telegram", "уведомление «${t.title}» не поставлено: ${e.message}"); null }
    }

    /** Для вкладки «Связь»: типы, текущие значения, тексты по умолчанию и подсказки. */
    fun listJson(): JSONArray {
        val a = JSONArray()
        for (t in NoticeText.TYPES) {
            val s = stored(t.id)
            a.put(obj(
                "id" to t.id, "title" to t.title, "hasActive" to t.hasActive,
                "on" to (!t.hasActive || s?.optBoolean("on", true) ?: true),
                "text" to (s?.optString("text", "")?.takeIf { it.isNotBlank() } ?: t.default),
                "default" to t.default, "hint" to t.hint,
                "vars" to JSONArray((t.vars + NoticeText.GENERIC).toList())
            ))
        }
        return a
    }

    /** Сохраняет все типы разом: {id: {on, text}}. Текст, совпадающий с умолчанием, не хранится (тогда новые версии приложения обновят его). Возвращает ошибки. */
    fun save(body: JSONObject): List<String> {
        val errors = ArrayList<String>()
        val out = JSONObject()
        for (t in NoticeText.TYPES) {
            val x = body.optJSONObject(t.id) ?: continue
            val text = x.optString("text", "").replace("\r\n", "\n")
            if (text.isNotBlank()) NoticeText.validate(t, text)?.let { errors.add(it) }
            val o = JSONObject()
            if (t.hasActive && x.has("on")) o.put("on", x.optBoolean("on", true))
            if (text.isNotBlank() && text.trim() != t.default.trim()) o.put("text", text)
            if (o.length() > 0) out.put(t.id, o)
        }
        if (errors.isEmpty()) Settings.setNotices(out)
        return errors
    }

    /** Кнопка «Тест»: отправляет уведомление с образцами значений. Текст берётся из запроса (как в поле, без сохранения), иначе сохранённый. */
    fun test(ctx: Context, id: String, textOverride: String?): Long {
        val t = requireNotNull(NoticeText.byId(id)) { "неизвестный тип уведомления" }
        require(Settings.telegramConfigured) { "не заданы токен бота и chat id" }
        val text = textOverride?.takeIf { it.isNotBlank() } ?: text(id)
        NoticeText.validate(t, text)?.let { throw IllegalArgumentException(it) }
        return Telegram.enqueue("(тест) " + render(ctx, text, t.sample))
    }
}
