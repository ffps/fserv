package com.ffps.fserv

import org.json.JSONArray
import org.json.JSONObject

/**
 * Разговор с ИИ (шаг chat и проверка на вкладке «Чат»): чистая логика без обращения к сети и устройству.
 * Собирает инструкцию модели из общих правил и темы, формирует тела запросов Gemini и Groq и разбирает ответы.
 * Модель отвечает JSON-объектом {reply, alert, end}: реплика для озвучивания, признак тревоги и признак конца разговора.
 */
object Chat {
    const val DEFAULT_RULES = "Ты голосовой помощник на устройстве в доме пожилого человека. Ты не человек и не родственник и не скрываешь этого. " +
        "Говори по-русски, тепло, просто и коротко, на «вы». Не давай медицинских советов и не ставь диагнозов: при жалобах на здоровье советуй позвать близких. " +
        "Не обсуждай политику и не проси личных данных, паролей и денег."

    const val FORMAT = "Отвечай ТОЛЬКО одним JSON-объектом, без пояснений и без markdown: {\"reply\":\"…\",\"alert\":false,\"end\":false}. " +
        "reply — твоя реплика для озвучивания: одно-два коротких предложения простыми словами, без списков, смайликов, звёздочек и скобок, числа и время пиши словами. " +
        "Задавай не больше одного вопроса за раз. " +
        "alert — true, только если собеседник жалуется на боль или плохое самочувствие, говорит, что упал, ему страшно, или просит о помощи. " +
        "end — true, если собеседник прощается или не хочет разговаривать, либо разговор естественно закончен."

    /** Токенов на ответ с запасом: у моделей с «размышлением» они тоже считаются, и короткий лимит обрезал бы ответ до пустого. */
    const val MAX_TOKENS = 400
    const val MAX_HISTORY = 24

    class Msg(val role: String, val text: String) // role: user или assistant
    class Reply(val text: String, val alert: Boolean, val end: Boolean)

    fun systemPrompt(rules: String, topic: String): String =
        rules.trim().ifEmpty { DEFAULT_RULES } + "\n\nТема разговора: " + topic.trim().ifEmpty { "свободная беседа" } + "\n\n" + FORMAT

    /** История для запроса: начинается с реплики пользователя (модели Gemini этого требуют) и не длиннее [MAX_HISTORY]. */
    fun prepare(history: List<Msg>): List<Msg> {
        var h = history.takeLast(MAX_HISTORY)
        while (h.isNotEmpty() && h.first().role != "user") h = h.drop(1)
        return if (h.isEmpty()) listOf(Msg("user", "Начни разговор.")) else h
    }

    fun geminiBody(system: String, history: List<Msg>, thinkingOff: Boolean): JSONObject {
        val contents = JSONArray()
        for (m in prepare(history)) {
            contents.put(JSONObject().put("role", if (m.role == "assistant") "model" else "user")
                .put("parts", JSONArray().put(JSONObject().put("text", m.text))))
        }
        val gen = JSONObject().put("maxOutputTokens", MAX_TOKENS).put("temperature", 0.7).put("responseMimeType", "application/json")
        if (thinkingOff) gen.put("thinkingConfig", JSONObject().put("thinkingBudget", 0))
        return JSONObject()
            .put("systemInstruction", JSONObject().put("parts", JSONArray().put(JSONObject().put("text", system))))
            .put("contents", contents)
            .put("generationConfig", gen)
    }

    fun groqBody(model: String, system: String, history: List<Msg>): JSONObject {
        val msgs = JSONArray().put(JSONObject().put("role", "system").put("content", system))
        for (m in prepare(history)) msgs.put(JSONObject().put("role", m.role).put("content", m.text))
        return JSONObject().put("model", model).put("messages", msgs).put("max_tokens", MAX_TOKENS).put("temperature", 0.7)
            .put("response_format", JSONObject().put("type", "json_object"))
    }

    /** Текст ответа Gemini; IllegalStateException с понятной причиной, если текста нет. */
    fun geminiText(resp: String): String {
        val j = JSONObject(resp)
        val c = j.optJSONArray("candidates")?.optJSONObject(0)
        val parts = c?.optJSONObject("content")?.optJSONArray("parts")
        val sb = StringBuilder()
        if (parts != null) for (i in 0 until parts.length()) sb.append(parts.optJSONObject(i)?.optString("text") ?: "")
        if (sb.isBlank()) {
            val block = j.optJSONObject("promptFeedback")?.optString("blockReason").orEmpty()
            val fin = c?.optString("finishReason").orEmpty()
            throw IllegalStateException("модель не вернула текст" + if (block.isNotEmpty()) " (запрос заблокирован: $block)" else if (fin.isNotEmpty()) " (причина: $fin)" else "")
        }
        return sb.toString()
    }

    fun groqText(resp: String): String {
        val t = JSONObject(resp).optJSONArray("choices")?.optJSONObject(0)?.optJSONObject("message")?.optString("content").orEmpty()
        if (t.isBlank()) throw IllegalStateException("модель не вернула текст")
        return t
    }

    /** Разбор ответа модели: JSON {reply, alert, end}; если JSON не получился, весь текст считается репликой. */
    fun parseReply(raw: String): Reply {
        val s = raw.trim().removePrefix("```json").removePrefix("```").removeSuffix("```").trim()
        val a = s.indexOf('{')
        val b = s.lastIndexOf('}')
        if (a >= 0 && b > a) {
            try {
                val o = JSONObject(s.substring(a, b + 1))
                return Reply(clean(o.optString("reply")), o.optBoolean("alert", false), o.optBoolean("end", false))
            } catch (e: org.json.JSONException) {
                // ниже: считаем текстом
            }
        }
        return Reply(clean(s), false, false)
    }

    private fun clean(t: String) = t.replace("*", "").replace("#", "").replace(Regex("\\s+"), " ").trim()
}
