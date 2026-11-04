package com.ffps.fserv

import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class ChatTest {
    @Test fun parseReplyJson() {
        val r = Chat.parseReply("""{"reply":"Здравствуйте! Как ваши дела?","alert":false,"end":false}""")
        assertEquals("Здравствуйте! Как ваши дела?", r.text)
        assertFalse(r.alert); assertFalse(r.end)
        val a = Chat.parseReply("""{"reply":"Позову ваших близких.","alert":true,"end":true}""")
        assertTrue(a.alert); assertTrue(a.end)
    }

    @Test fun parseReplyFencedAndNoisy() {
        val r = Chat.parseReply("```json\n{\"reply\":\"Привет *дорогая*\",\"alert\":false,\"end\":true}\n```")
        assertEquals("Привет дорогая", r.text)
        assertTrue(r.end)
        assertEquals("Да, конечно", Chat.parseReply("Результат: {\"reply\":\"Да, конечно\"} готово").text)
    }

    @Test fun parseReplyPlainTextFallback() {
        val r = Chat.parseReply("Просто текст без JSON")
        assertEquals("Просто текст без JSON", r.text)
        assertFalse(r.alert)
        assertEquals("{сломанный", Chat.parseReply("{сломанный").text)
    }

    @Test fun prepareStartsWithUser() {
        assertEquals("user", Chat.prepare(emptyList())[0].role)
        val h = Chat.prepare(listOf(Chat.Msg("assistant", "a"), Chat.Msg("user", "u"), Chat.Msg("assistant", "b")))
        assertEquals("user", h[0].role)
        assertEquals(2, h.size)
        val long = (1..60).map { Chat.Msg(if (it % 2 == 1) "user" else "assistant", "m$it") }
        val p = Chat.prepare(long)
        assertTrue(p.size <= Chat.MAX_HISTORY)
        assertEquals("user", p[0].role)
    }

    @Test fun systemPrompt() {
        val s = Chat.systemPrompt("", "поболтать о внуках")
        assertTrue(s.contains(Chat.DEFAULT_RULES))
        assertTrue(s.contains("поболтать о внуках"))
        assertTrue(s.contains("\"reply\""))
        assertTrue(Chat.systemPrompt("Мои правила", "").contains("Мои правила"))
        assertFalse(Chat.systemPrompt("Мои правила", "").contains(Chat.DEFAULT_RULES))
    }

    @Test fun systemPromptContext() {
        val s = Chat.systemPrompt("Правила", "тема", "Сейчас среда, 4 ноября 2026 года, 19:55.")
        assertTrue(s.indexOf("Правила") < s.indexOf("Сейчас среда"))
        assertTrue(s.indexOf("Сейчас среда") < s.indexOf("Тема разговора"))
        assertFalse(Chat.systemPrompt("Правила", "тема", "  ").contains("\n\n\n"))
    }

    @Test fun openAiBody() {
        val b = Chat.openAiBody("m1", "sys", listOf(Chat.Msg("user", "привет"), Chat.Msg("assistant", "здравствуйте")))
        assertEquals("m1", b.getString("model"))
        val m = b.getJSONArray("messages")
        assertEquals("system", m.getJSONObject(0).getString("role"))
        assertEquals("sys", m.getJSONObject(0).getString("content"))
        assertEquals("user", m.getJSONObject(1).getString("role"))
        assertEquals("assistant", m.getJSONObject(2).getString("role"))
        assertEquals("json_object", b.getJSONObject("response_format").getString("type"))
        assertFalse(b.has("reasoning_effort"))
        assertEquals(Chat.MAX_TOKENS, b.getInt("max_tokens"))
    }

    @Test fun openAiBodyOptions() {
        val b = Chat.openAiBody("openai/gpt-oss-120b", "s", emptyList(), json = false, reasoningEffort = "low")
        assertFalse(b.has("response_format"))
        assertEquals("low", b.getString("reasoning_effort"))
        assertEquals("user", b.getJSONArray("messages").getJSONObject(1).getString("role")) // пустая история: модель просят начать разговор
    }

    @Test fun openAiResponses() {
        val q = """{"choices":[{"message":{"role":"assistant","content":"{\"reply\":\"хорошо\",\"end\":true}"}}]}"""
        val r = Chat.parseReply(Chat.openAiText(q))
        assertEquals("хорошо", r.text); assertTrue(r.end)
        try { Chat.openAiText("""{"choices":[]}"""); fail() } catch (e: IllegalStateException) { }
        try { Chat.openAiText("""{"choices":[{"message":{"content":""},"finish_reason":"length"}]}"""); fail() } catch (e: IllegalStateException) { assertTrue(e.message!!.contains("length")) }
    }

    @Test fun parseModels() {
        val groq = """{"object":"list","data":[{"id":"openai/gpt-oss-120b"},{"id":"llama-3.3-70b-versatile"},{"id":"openai/gpt-oss-120b"}]}"""
        assertEquals(listOf("llama-3.3-70b-versatile", "openai/gpt-oss-120b"), Chat.parseModels(groq))
        val gemini = """{"data":[{"id":"models/gemini-3.1-flash-lite"},{"id":"models/gemini-2.5-pro"},{"id":""}]}"""
        assertEquals(listOf("gemini-2.5-pro", "gemini-3.1-flash-lite"), Chat.parseModels(gemini))
        assertEquals(emptyList<String>(), Chat.parseModels("""{"data":[]}"""))
    }
}
