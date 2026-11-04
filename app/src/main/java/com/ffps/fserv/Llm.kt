package com.ffps.fserv

import org.json.JSONObject

/**
 * Запросы к языковой модели через API, совместимый с OpenAI (`/chat/completions` и `/models`): Groq, Gemini (его совместимый
 * интерфейс), Pollinations и любой другой сервис. Идут через прокси «LLM», если он включён. Все сбои приходят как [LlmError]
 * с понятным текстом.
 */
object Llm {
    class LlmError(message: String) : RuntimeException(message)
    class Result(val reply: Chat.Reply, val ms: Long, val provider: String, val model: String)

    /** Что настроено у пресета: адрес, ключ и модель (у пресета custom ключ не обязателен). */
    private class Cfg(val preset: String, val url: String, val key: String, val model: String) {
        val label: String get() = if (preset != "custom") preset else (url.substringAfter("://").substringBefore('/').ifEmpty { "custom" })
        val missing: String? get() = when {
            url.isEmpty() -> "не задан адрес: вкладка «Связь», раздел LLM"
            model.isEmpty() -> "не задана модель: вкладка «Связь», раздел LLM"
            key.isEmpty() && preset != "custom" -> "не задан ключ $preset: вкладка «Связь», раздел LLM"
            else -> null
        }
    }

    private fun cfg(preset: String = Settings.llmPreset) = Cfg(preset, Settings.llmUrl(preset), Settings.llmKey(preset), Settings.llmModel(preset))

    fun configured(): Boolean = cfg().missing == null

    /** Почему модель не настроена (текст для ошибки) или null. */
    fun problem(): String? = cfg().missing

    /** Название выбранного провайдера для сообщений. */
    fun label(): String = cfg().label

    /** Реплика модели на историю разговора по инструкции [system]. */
    fun ask(system: String, history: List<Chat.Msg>): Result {
        val c = cfg()
        c.missing?.let { throw LlmError(it) }
        val t0 = System.currentTimeMillis()
        val raw = try {
            complete(c, system, history)
        } catch (e: LlmError) {
            throw e
        } catch (e: java.io.IOException) {
            throw LlmError("${c.label}: нет связи: ${e.message}")
        } catch (e: org.json.JSONException) {
            throw LlmError("${c.label}: непонятный ответ сервиса: ${e.message}")
        } catch (e: IllegalStateException) {
            throw LlmError("${c.label}: ${e.message}")
        }
        val ms = System.currentTimeMillis() - t0
        FLog.d("llm", "${c.label}/${c.model}: ответ за $ms мс")
        return Result(Chat.parseReply(raw), ms, c.label, c.model)
    }

    private fun tmo() = Settings.llmTimeoutSec * 1000

    private fun headers(c: Cfg): Map<String, String> = if (c.key.isEmpty()) emptyMap() else mapOf("Authorization" to "Bearer ${c.key}")

    /**
     * Запрос к `/chat/completions`. У моделей gpt-oss просим малые «размышления» (`reasoning_effort`), а режим JSON
     * включаем у всех; если сервис отклоняет (HTTP 400) один из этих параметров, повторяем без него.
     */
    private fun complete(c: Cfg, system: String, history: List<Chat.Msg>): String {
        var reasoning = c.model.contains("gpt-oss", ignoreCase = true)
        var json = true
        fun send(): Net.Resp {
            val body = Chat.openAiBody(c.model, system, history, json, if (reasoning) "low" else null)
            return Net.request("llm", "POST", c.url + "/chat/completions", body.toString().toByteArray(Charsets.UTF_8), "application/json", headers(c), tmo())
        }
        var r = send()
        while (r.code == 400) {
            val m = r.body.lowercase()
            if (reasoning && m.contains("reasoning")) reasoning = false
            else if (json && (m.contains("response_format") || m.contains("json"))) json = false
            else break
            r = send()
        }
        if (r.code !in 200..299) throw httpError(c.label, r)
        return Chat.openAiText(r.body)
    }

    private fun httpError(label: String, r: Net.Resp): LlmError {
        val msg = try {
            val j = JSONObject(r.body)
            (j.optJSONObject("error")?.optString("message") ?: j.optString("error")).take(240)
        } catch (e: Exception) { "" }
        val base = when (r.code) {
            400 -> "запрос отклонён"
            401, 403 -> "ключ не принят или доступ запрещён"
            404 -> "модель или адрес не найдены"
            429 -> "превышен лимит запросов"
            in 500..599 -> "сбой на стороне сервиса"
            else -> "ошибка"
        }
        return LlmError("$label: $base (HTTP ${r.code})" + if (msg.isNotEmpty()) ": $msg" else "")
    }

    /** Список моделей пресета (для подсказок в настройках); у Gemini имена приходят с префиксом «models/», его убираем. */
    fun models(preset: String): List<String> {
        val c = cfg(preset)
        if (c.url.isEmpty()) throw LlmError("не задан адрес")
        if (c.key.isEmpty() && preset != "custom") throw LlmError("не задан ключ $preset")
        try {
            val r = Net.request("llm", "GET", c.url + "/models", headers = headers(c), timeoutMs = tmo())
            if (r.code !in 200..299) throw httpError(c.label, r)
            return Chat.parseModels(r.body)
        } catch (e: java.io.IOException) {
            throw LlmError("${c.label}: нет связи: ${e.message}")
        } catch (e: org.json.JSONException) {
            throw LlmError("${c.label}: непонятный ответ сервиса: ${e.message}")
        }
    }
}
