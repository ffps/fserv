package com.ffps.fserv

import android.content.Context
import org.json.JSONObject

/** Применение изменений настроек вместе с последствиями: веб-интерфейс (POST /api/settings) и шаг set в задачах. */
object SettingsApply {
    fun apply(ctx: Context, patch: JSONObject): Settings.Result {
        val r = Settings.update(patch)
        if (r.errors.isNotEmpty()) return r
        if (patch.has("camera")) {
            if (Settings.camera().keepRunning && CameraEngine.hasPermission(ctx)) CameraEngine.start("keep") else CameraEngine.stop("keep")
        }
        if (patch.has("voice") || patch.has("zones") || patch.has("chat")) {
            TaskStore.invalidate()
            Thread { TaskEngine.refreshStt() }.start()
        }
        if (r.restartRequired) {
            // Порт сменился: перезапускаем сервер чуть позже, чтобы успеть ответить на запрос.
            Thread {
                Thread.sleep(800)
                ServerService.instance?.softRestart()
            }.start()
        }
        return r
    }

    /** Один параметр по пути (voice.volume): null при успехе, иначе текст ошибки. */
    fun setOne(ctx: Context, path: String, value: Any?): String? {
        if (path in SettingsPath.READ_ONLY) return "«$path» только для чтения (производное значение)"
        val patch = SettingsPath.patch(Settings.publicJson(), Settings.exportJson(), path, value)
        val r = apply(ctx, patch)
        r.errors.firstOrNull()?.let { return if (r.errors.size > 1) it + " (и ещё ${r.errors.size - 1})" else it }
        // Settings.update молча пропускает неизвестные ключи; опечатку в пути нужно заметить
        if (SettingsPath.lookup(Settings.publicJson(), path) == null && SettingsPath.lookup(Settings.exportJson(), path) == null) {
            return "такого параметра нет (проверьте путь; ничего не изменено)"
        }
        return null
    }

    /** Значение по пути: сначала то, что видит веб-интерфейс (с умолчаниями), затем сохранённое как есть (пароль, токены, ключи). */
    fun read(path: String): String {
        val v = SettingsPath.lookup(Settings.publicJson(), path) ?: SettingsPath.lookup(Settings.exportJson(), path)
        return SettingsPath.text(v)
    }
}
