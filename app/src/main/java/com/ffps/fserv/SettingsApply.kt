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

    /** Один параметр по пути (voice.volume): null при успехе, иначе текст ошибки. Меняются только простые значения (число, true/false, текст). */
    fun setOne(ctx: Context, path: String, value: Any?): String? {
        if (path in SettingsPath.READ_ONLY) return "«$path» только для чтения (производное значение)"
        SettingsPath.checkSimple(Settings.publicJson(), Settings.exportJson(), path)?.let { return it }
        val patch = SettingsPath.patch(Settings.publicJson(), Settings.exportJson(), path, value)
        val r = apply(ctx, patch)
        r.errors.firstOrNull()?.let { return if (r.errors.size > 1) it + " (и ещё ${r.errors.size - 1})" else it }
        // Settings.update молча пропускает неизвестные ключи и значения не того типа: проверяем, что записалось именно то, что просили
        val now = SettingsPath.lookup(Settings.publicJson(), path) ?: SettingsPath.lookup(Settings.exportJson(), path)
            ?: return "такого параметра нет (проверьте путь; ничего не изменено)"
        if (!SettingsPath.same(now, value)) return "значение не принято: после записи там ${SettingsPath.text(now)} (ожидалось ${SettingsPath.text(value)}); проверьте тип и допустимые значения"
        return null
    }

    /** Значение по пути: сначала то, что видит веб-интерфейс (с умолчаниями), затем сохранённое как есть (пароль, токены, ключи). */
    fun read(path: String): String {
        val v = SettingsPath.lookup(Settings.publicJson(), path) ?: SettingsPath.lookup(Settings.exportJson(), path)
        return SettingsPath.text(v)
    }
}
