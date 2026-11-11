package com.ffps.fserv

/** Тексты уведомлений в Telegram: типы, подстановки, проверка и сборка (чистая логика без Android). */
object NoticeText {
    class Type(
        val id: String,
        val title: String,
        /** Есть ли у уведомления галочка «Активно» (проверка связи уходит только по кнопке). */
        val hasActive: Boolean,
        val vars: List<String>,
        val hint: String,
        val sample: Map<String, String>,
        val default: String
    )

    /** Начало всех уведомлений по умолчанию: какое устройство пишет. */
    const val PREFIX = "fServe {info.device.model} {info.device.ips[0]}\n"

    /** Подстановки, доступные во всех уведомлениях; кроме них можно {info.путь} и {settings.путь}. */
    val GENERIC = listOf("time", "date")

    val TYPES: List<Type> = listOf(
        Type("ping", "Проверка связи", false, listOf("model", "version"),
            "По нажатию кнопки «Тест» на вкладке «Связь» (раздел Telegram).",
            mapOf("model" to "Galaxy A3", "version" to "1.9.0"),
            PREFIX + "Проверка связи (версия {version})."),
        Type("taskError", "Ошибка задачи", true, listOf("task", "message", "status", "source", "duration"),
            "Задача завершилась с ошибкой или по timeout. Только при запуске по расписанию (cron) или голосом (voice), не ручной и не пробный. Не отправляется, если у задачи notify=false.",
            mapOf("task" to "Обед", "message" to "не уложилась в timeout (1 ч)", "status" to "timeout", "source" to "cron", "duration" to "1 ч"),
            PREFIX + "Задача «{task}» не выполнена: {message}"),
        Type("speech", "Недоступность речи", true, listOf("task", "error"),
            "Речь недоступна (не работает синтезатор). Не более одного раза за запуск, при запуске по расписанию (cron) или голосом (voice), не ручной и не пробный. Не отправляется, если у задачи notify=false.",
            mapOf("task" to "Обед", "error" to "нет голоса ru-RU"),
            PREFIX + "Задача «{task}»: речь недоступна ({error}). Остальные шаги выполнены. Проверьте синтезатор речи на устройстве."),
        Type("alarm", "Тревога в разговоре", true, listOf("task", "said", "reply"),
            "Завершение chat с флагом alert от модели. Каждый раз, когда модель вернула тревогу (notify=false задачи на неё не действует).",
            mapOf("task" to "Разговор", "said" to "мне плохо", "reply" to "Сейчас сообщу вашим близким"),
            PREFIX + "<b>Тревога в разговоре с устройством.</b> Задача «{task}». Человек сказал: «{said}». Ответ помощника: «{reply}»."),
        Type("update", "Обновление приложения", true, listOf("version", "current"),
            "Вышла новая версия. Один раз при обнаружении новой версии.",
            mapOf("version" to "1.9.1", "current" to "1.9.0"),
            PREFIX + "Доступно обновление {version} (установлена {current}). Установить можно из веб-интерфейса, вкладка «Обновления».")
    )

    fun byId(id: String): Type? = TYPES.firstOrNull { it.id == id }

    const val MAX_LEN = 1000

    /** Имя в фигурных скобках: переменная либо путь settings./info. (допускается индекс: ips[0] или ips.0). */
    // Android (ICU) требует экранировать и закрывающие } и ]: на JVM без экранирования проходит, на устройстве — PatternSyntaxException
    val TPL = Regex("""\{((?:settings|info)(?:\.[A-Za-z0-9_]+|\[\d+\])+|[A-Za-z_][A-Za-z0-9_]*)\}""")

    /** Текст ошибки или null. */
    fun validate(t: Type, text: String): String? {
        if (text.length > MAX_LEN) return "${t.title}: текст не длиннее $MAX_LEN знаков"
        for (m in TPL.findAll(text)) {
            val n = m.groupValues[1]
            if (n.startsWith("settings.") || n.startsWith("info.") || n in t.vars || n in GENERIC) continue
            return "${t.title}: неизвестная подстановка {$n}; доступны: " + (t.vars + GENERIC).joinToString(", ") { "{$it}" } + ", {info.…}, {settings.…}"
        }
        return null
    }

    fun escape(s: String): String = s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;")

    /**
     * Собирает текст: значения подстановок экранируются (сообщение уходит как HTML), сам шаблон остаётся как есть,
     * чтобы можно было писать <b>…</b>. [lookup] отдаёт значение пути settings./info.; неизвестное имя остаётся без изменений.
     */
    fun fill(text: String, vars: Map<String, String>, lookup: (String) -> String): String =
        TPL.replace(text) { m ->
            val n = m.groupValues[1]
            when {
                n in vars -> escape(vars.getValue(n))
                n.startsWith("settings.") || n.startsWith("info.") -> escape(lookup(n))
                else -> m.value
            }
        }
}
