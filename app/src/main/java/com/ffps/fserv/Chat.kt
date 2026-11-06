package com.ffps.fserv

import org.json.JSONArray
import org.json.JSONObject

/**
 * Разговор с ИИ (шаг chat и проверка на вкладке «Чат»): чистая логика без обращения к сети и устройству.
 * Собирает инструкцию модели из общих правил и темы, формирует тело запроса в формате OpenAI и разбирает ответы.
 * Модель отвечает JSON-объектом {reply, alert, end}: реплика для озвучивания, признак тревоги и признак конца разговора.
 */
object Chat {
    const val DEFAULT_RULES = "Ты голосовой помощник на устройстве в доме пожилого человека. Ты не человек и не родственник и не скрываешь этого. " +
        "Говори по-русски, тепло, просто и коротко, на «вы». Не ставь диагнозов: при жалобах на здоровье советуй позвать близких. " +
        "Не проси личных данных, паролей и денег."

    const val FORMAT = "Отвечай ТОЛЬКО одним JSON-объектом, без пояснений и без markdown: {\"reply\":\"…\",\"alert\":false,\"end\":false}. " +
        "reply — твоя реплика для озвучивания: одно-два коротких предложения простыми словами, без списков, смайликов, звёздочек и скобок, числа и время пиши цифрами (384 тысячи, 17:30), без сокращений. " +
        "Задавай не больше одного вопроса за раз. " +
        "alert — true, только если собеседник жалуется на боль или плохое самочувствие, говорит, что упал, ему страшно, или просит о помощи. " +
        "end — true, если собеседник прощается или не хочет разговаривать, либо разговор естественно закончен."

    /** Добавка к инструкции для последней реплики: без встречного вопроса (человеку после неё некому отвечать). */
    const val LAST = "\n\nЭто твоя последняя реплика. Если человек о чём-то спросил, ответь по существу; затем коротко попрощайся. " +
        "НЕ задавай никаких вопросов, не предлагай продолжить разговор и не спрашивай «а вы?». Поставь end=true."

    /** Токенов на ответ с запасом: у моделей с «размышлением» они тоже считаются, и короткий лимит обрезал бы ответ до пустого. */
    const val MAX_TOKENS = 400
    const val MAX_HISTORY = 24

    class Msg(val role: String, val text: String) // role: user или assistant
    class Reply(val text: String, val alert: Boolean, val end: Boolean)

    /** [context] — дополнительные сведения для модели (дата, время, погода); пусто — без них. */
    fun systemPrompt(rules: String, topic: String, context: String = "", first: Boolean = true): String =
        rules.trim().ifEmpty { DEFAULT_RULES } + (if (context.isBlank()) "" else "\n\n" + context.trim()) + "\n\n" + topicLine(topic, first) + "\n\n" + FORMAT

    /**
     * Строка о теме. Для первой реплики тема — одновременно задание: вопрос или просьба в ней требуют сразу ответа по существу,
     * а не приветствия и вопроса «чем помочь» (иначе модель принимает вопрос за предмет беседы).
     */
    fun topicLine(topic: String, first: Boolean): String {
        val t = topic.trim()
        if (t.isEmpty()) return "Тема разговора: свободная беседа"
        if (!first) return "Тема разговора: $t"
        return "Тема разговора (она же задание на первую реплику): $t. Если это вопрос или просьба, сразу ответь на неё по существу, " +
            "без приветствия и без вопроса «чем помочь»; если это тема, начни разговор по ней одной фразой."
    }

    /** История для запроса: начинается с реплики пользователя (модели Gemini этого требуют) и не длиннее [MAX_HISTORY]. */
    fun prepare(history: List<Msg>): List<Msg> {
        var h = history.takeLast(MAX_HISTORY)
        while (h.isNotEmpty() && h.first().role != "user") h = h.drop(1)
        return if (h.isEmpty()) listOf(Msg("user", "Начни разговор.")) else h
    }

    /** Тело запроса `/chat/completions`; [json] включает режим JSON-ответа, [reasoningEffort] (low…high) задаётся только моделям, которые его знают. */
    fun openAiBody(model: String, system: String, history: List<Msg>, json: Boolean = true, reasoningEffort: String? = null): JSONObject {
        val msgs = JSONArray().put(JSONObject().put("role", "system").put("content", system))
        for (m in prepare(history)) msgs.put(JSONObject().put("role", m.role).put("content", m.text))
        val o = JSONObject().put("model", model).put("messages", msgs).put("max_tokens", MAX_TOKENS).put("temperature", 0.7)
        if (json) o.put("response_format", JSONObject().put("type", "json_object"))
        if (reasoningEffort != null) o.put("reasoning_effort", reasoningEffort)
        return o
    }

    fun openAiText(resp: String): String {
        val j = JSONObject(resp)
        val c = j.optJSONArray("choices")?.optJSONObject(0)
        val t = c?.optJSONObject("message")?.optString("content").orEmpty()
        if (t.isBlank()) {
            val fin = c?.optString("finish_reason").orEmpty()
            throw IllegalStateException("модель не вернула текст" + if (fin.isNotEmpty()) " (причина: $fin)" else "")
        }
        return t
    }

    /** Имена моделей из ответа `/models` (поле data[].id), без префикса «models/», по алфавиту. */
    fun parseModels(resp: String): List<String> {
        val a = JSONObject(resp).optJSONArray("data")
        return (0 until (a?.length() ?: 0)).mapNotNull { a!!.optJSONObject(it)?.optString("id")?.removePrefix("models/")?.takeIf { s -> s.isNotEmpty() } }.distinct().sorted()
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
