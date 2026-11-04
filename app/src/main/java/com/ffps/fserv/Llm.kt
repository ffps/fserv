package com.ffps.fserv

import org.json.JSONObject

/** Запросы к языковой модели (Gemini или Groq, через прокси «LLM», если он включён). Все сбои приходят как [LlmError] с понятным текстом. */
object Llm {
    class LlmError(message: String) : RuntimeException(message)
    class Result(val reply: Chat.Reply, val ms: Long, val provider: String, val model: String)

    private const val GEMINI = "https://generativelanguage.googleapis.com/v1beta/"
    private const val GROQ = "https://api.groq.com/openai/v1/"

    fun configured(): Boolean = Settings.llmKey(Settings.llmProvider).isNotEmpty()

    /** Реплика модели на историю разговора по инструкции [system]. */
    fun ask(system: String, history: List<Chat.Msg>): Result {
        val provider = Settings.llmProvider
        val key = Settings.llmKey(provider)
        if (key.isEmpty()) throw LlmError("не задан ключ $provider: вкладка «Связь», раздел LLM")
        val model = Settings.llmModel(provider)
        val t0 = System.currentTimeMillis()
        val raw = try {
            if (provider == "groq") groq(key, model, system, history) else gemini(key, model, system, history)
        } catch (e: LlmError) {
            throw e
        } catch (e: java.io.IOException) {
            throw LlmError("$provider: нет связи: ${e.message}")
        } catch (e: org.json.JSONException) {
            throw LlmError("$provider: непонятный ответ сервиса: ${e.message}")
        } catch (e: IllegalStateException) {
            throw LlmError("$provider: ${e.message}")
        }
        val ms = System.currentTimeMillis() - t0
        FLog.d("llm", "$provider/$model: ответ за $ms мс")
        return Result(Chat.parseReply(raw), ms, provider, model)
    }

    private fun tmo() = Settings.llmTimeoutSec * 1000

    private fun gemini(key: String, model: String, system: String, history: List<Chat.Msg>): String {
        val headers = mapOf("x-goog-api-key" to key)
        val url = GEMINI + "models/$model:generateContent"
        // у моделей 2.5 flash «размышление» отключаем ради скорости; если модель такого поля не знает, повторяем без него
        val thinkingOff = model.contains("2.5") && model.contains("flash")
        var r = Net.request("llm", "POST", url, Chat.geminiBody(system, history, thinkingOff).toString().toByteArray(Charsets.UTF_8), "application/json", headers, tmo())
        if (r.code == 400 && thinkingOff && r.body.contains("hinking")) {
            r = Net.request("llm", "POST", url, Chat.geminiBody(system, history, false).toString().toByteArray(Charsets.UTF_8), "application/json", headers, tmo())
        }
        if (r.code !in 200..299) throw httpError("gemini", r)
        return Chat.geminiText(r.body)
    }

    private fun groq(key: String, model: String, system: String, history: List<Chat.Msg>): String {
        val r = Net.request("llm", "POST", GROQ + "chat/completions", Chat.groqBody(model, system, history).toString().toByteArray(Charsets.UTF_8),
            "application/json", mapOf("Authorization" to "Bearer $key"), tmo())
        if (r.code !in 200..299) throw httpError("groq", r)
        return Chat.groqText(r.body)
    }

    private fun httpError(provider: String, r: Net.Resp): LlmError {
        val msg = try {
            val j = JSONObject(r.body)
            (j.optJSONObject("error")?.optString("message") ?: j.optString("error")).take(240)
        } catch (e: Exception) { "" }
        val base = when (r.code) {
            400 -> "запрос отклонён"
            401, 403 -> "ключ не принят или доступ запрещён"
            404 -> "модель не найдена"
            429 -> "превышен лимит запросов"
            in 500..599 -> "сбой на стороне сервиса"
            else -> "ошибка"
        }
        return LlmError("$provider: $base (HTTP ${r.code})" + if (msg.isNotEmpty()) ": $msg" else "")
    }

    /** Список моделей провайдера (для подсказок в настройках). */
    fun models(provider: String): List<String> {
        val key = Settings.llmKey(provider)
        if (key.isEmpty()) throw LlmError("не задан ключ $provider")
        try {
            return if (provider == "groq") {
                val r = Net.request("llm", "GET", GROQ + "models", headers = mapOf("Authorization" to "Bearer $key"), timeoutMs = tmo())
                if (r.code !in 200..299) throw httpError(provider, r)
                val a = JSONObject(r.body).optJSONArray("data")
                (0 until (a?.length() ?: 0)).mapNotNull { a!!.optJSONObject(it)?.optString("id")?.takeIf { s -> s.isNotEmpty() } }.sorted()
            } else {
                val r = Net.request("llm", "GET", GEMINI + "models?pageSize=200", headers = mapOf("x-goog-api-key" to key), timeoutMs = tmo())
                if (r.code !in 200..299) throw httpError(provider, r)
                val a = JSONObject(r.body).optJSONArray("models")
                (0 until (a?.length() ?: 0)).mapNotNull {
                    val m = a!!.optJSONObject(it) ?: return@mapNotNull null
                    val gm = m.optJSONArray("supportedGenerationMethods")
                    val ok = gm != null && (0 until gm.length()).any { i -> gm.optString(i) == "generateContent" }
                    if (ok) m.optString("name").removePrefix("models/") else null
                }.sorted()
            }
        } catch (e: java.io.IOException) {
            throw LlmError("$provider: нет связи: ${e.message}")
        } catch (e: org.json.JSONException) {
            throw LlmError("$provider: непонятный ответ сервиса: ${e.message}")
        }
    }
}
