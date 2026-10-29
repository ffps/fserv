package com.ffps.fserv

import android.content.Context
import android.os.Build
import android.os.SystemClock
import android.util.Base64
import fi.iki.elonen.NanoHTTPD
import fi.iki.elonen.NanoHTTPD.IHTTPSession
import fi.iki.elonen.NanoHTTPD.Method
import fi.iki.elonen.NanoHTTPD.Response
import org.json.JSONArray
import org.json.JSONException
import org.json.JSONObject
import java.io.File
import java.security.MessageDigest
import java.util.TimeZone

/**
 * Встроенный веб-сервер: страница управления, JSON API и киоск-страница.
 *
 * Без пароля доступны только /health (для мониторинга) и /kiosk с самого устройства (127.0.0.1).
 * Остальное требует HTTP Basic (логин admin, пароль из настроек).
 * Все записи идут методом POST с телом в UTF-8; ответы JSON с отступами.
 */
class WebServer(private val ctx: Context, port: Int) : NanoHTTPD(port) {

    private var lastAuthWarn = 0L

    override fun serve(session: IHTTPSession): Response {
        return try {
            route(session)
        } catch (e: IllegalArgumentException) {
            fail(Response.Status.BAD_REQUEST, e.message ?: "неверный запрос")
        } catch (e: Exception) {
            FLog.e("web", "${session.method} ${session.uri} failed", e)
            fail(Response.Status.INTERNAL_ERROR, e.message ?: e.javaClass.simpleName)
        }
    }

    // ---------- маршрутизация ----------

    private fun route(s: IHTTPSession): Response {
        val uri = s.uri.trimEnd('/').ifEmpty { "/" }
        val get = s.method == Method.GET
        val post = s.method == Method.POST

        if (get && uri == "/health") return health()

        val ip = s.remoteIpAddress ?: ""
        val loopback = ip == "127.0.0.1" || ip == "::1" || ip == "0:0:0:0:0:0:0:1"
        if (get && uri == "/kiosk" && loopback) return page("kiosk", false)

        if (!authorized(s)) return unauthorized(ip)

        return when {
            get && uri == "/" -> page("index", false)
            get && uri == "/default" -> page("index", true) // встроенная страница, даже если подменена
            get && uri == "/kiosk" -> page("kiosk", false)

            get && uri == "/api" -> json(Response.Status.OK, apiIndex())

            get && uri == "/api/telegram" -> json(Response.Status.OK, Telegram.status())
            post && uri == "/api/telegram/test" -> try {
                val m = Telegram.test()
                FLog.i("telegram", "проверка связи: $m")
                ok(m)
            } catch (e: IllegalArgumentException) {
                FLog.w("telegram", "проверка связи: ${e.message}")
                fail(Response.Status.BAD_REQUEST, e.message ?: "ошибка")
            } catch (e: Exception) {
                FLog.w("telegram", "проверка связи не удалась: ${e.message}")
                fail(Response.Status.INTERNAL_ERROR, e.message ?: e.javaClass.simpleName)
            }
            post && uri == "/api/telegram/send" -> {
                val t = body(s).trim()
                require(t.isNotEmpty()) { "пустой текст" }
                val id = Telegram.enqueue(t)
                json(Response.Status.OK, obj("ok" to true, "id" to id, "pending" to Telegram.pending()))
            }
            get && uri == "/api/outbox" -> json(Response.Status.OK, Telegram.itemsJson())
            post && uri == "/api/outbox/clear" -> {
                val n = Telegram.clear()
                Journal.add("telegram", "Очередь отправки очищена вручную ($n сообщ.)")
                ok("удалено сообщений: $n")
            }
            post && uri == "/api/proxy/test" -> proxyTest(q(s, "name"))

            get && uri == "/api/update" -> json(Response.Status.OK, Updater.status(ctx))
            post && uri == "/api/update/check" -> try {
                Updater.check()
                json(Response.Status.OK, Updater.status(ctx))
            } catch (e: Exception) {
                fail(Response.Status.INTERNAL_ERROR, e.message ?: e.javaClass.simpleName)
            }
            post && uri == "/api/update/install" -> {
                val err = Updater.installAsync(ctx)
                if (err == null) ok("скачивание и установка запущены") else fail(Response.Status.CONFLICT, err)
            }

            get && uri == "/api/camera" -> json(Response.Status.OK, CameraEngine.status())
            post && uri == "/api/camera/start" -> {
                if (!CameraEngine.hasPermission(ctx)) fail(Response.Status.CONFLICT, "нет разрешения на камеру: на устройстве нажмите Allow camera в окне настроек")
                else { CameraEngine.start("manual"); Journal.add("camera", "Камера включена вручную"); ok("камера запускается") }
            }
            post && uri == "/api/camera/stop" -> { CameraEngine.stop("manual"); Journal.add("camera", "Камера выключена вручную"); ok("камера выключена (если её не держит что-то ещё)") }
            get && uri == "/api/camera/history" -> json(Response.Status.OK, CameraEngine.historyJson(q(s, "limit")?.toIntOrNull()?.coerceIn(1, 600) ?: 120))
            get && uri == "/api/camera/snapshot" -> {
                val jpg = CameraEngine.snapshotJpeg(q(s, "overlay") == "1", q(s, "size")?.toIntOrNull() ?: 320)
                if (jpg == null) fail(Response.Status.NOT_FOUND, "кадра пока нет: камера выключена или ещё не дала кадр")
                else NanoHTTPD.newFixedLengthResponse(Response.Status.OK, "image/jpeg", java.io.ByteArrayInputStream(jpg), jpg.size.toLong())
                    .also { it.addHeader("Cache-Control", "no-store") }
            }
            get && uri == "/api/camera/test" -> json(Response.Status.OK, CameraTest.status())
            post && uri == "/api/camera/test" -> {
                val err = CameraTest.start(
                    ctx, q(s, "mode") ?: "continuous", q(s, "minutes")?.toIntOrNull() ?: 10,
                    q(s, "count")?.toIntOrNull() ?: 50, q(s, "gap")?.toIntOrNull() ?: 3
                )
                if (err == null) ok("тест запущен") else fail(Response.Status.CONFLICT, err)
            }
            post && uri == "/api/camera/test/stop" -> { CameraTest.stop(); ok("остановка запрошена") }

            get && uri == "/api/info" -> json(Response.Status.OK, info())

            get && uri == "/api/settings" -> json(Response.Status.OK, Settings.publicJson())
            post && uri == "/api/settings" -> saveSettings(body(s))

            get && uri == "/api/logs" -> logs(s)
            get && uri == "/api/logs/download" -> logsDownload()
            post && uri == "/api/logs/clear" -> {
                FLog.clear()
                FLog.i("web", "лог очищен")
                Journal.add("web", "Технический лог очищен")
                ok("лог очищен")
            }

            get && uri == "/api/journal" -> json(Response.Status.OK, Journal.last(q(s, "limit")?.toIntOrNull()?.coerceIn(1, 1000) ?: 100))
            post && uri == "/api/journal/clear" -> {
                Journal.clear()
                FLog.i("web", "журнал событий очищен")
                ok("журнал очищен")
            }

            get && uri == "/api/config" -> text(Response.Status.OK, "application/json", TaskStore.read())
            post && uri == "/api/config" -> saveConfig(body(s))
            get && uri == "/api/config/history" -> json(Response.Status.OK, TaskStore.history())
            post && uri == "/api/config/restore" -> restoreConfig(q(s, "name") ?: "")

            get && uri == "/api/ui" -> uiGet(q(s, "name"))
            post && uri == "/api/ui" -> uiSave(q(s, "name"), body(s))
            post && uri == "/api/ui/reset" -> uiReset(q(s, "name"))
            post && uri == "/api/kiosk/show" -> {
                if (MainActivity.isVisible()) ok("киоск уже на экране устройства")
                else MainActivity.show(ctx).let { err ->
                    if (err == null) ok("киоск выведен на экран (если система разрешила, см. Allow overlay в окне настроек)")
                    else fail(Response.Status.CONFLICT, "не удалось: $err")
                }
            }
            post && uri == "/api/kiosk/reload" -> when (MainActivity.reloadKiosk()) {
                KioskState.VISIBLE -> ok("киоск перезагружен, он сейчас на экране устройства")
                KioskState.BACKGROUND ->
                    ok("киоск перезагружен, но на экране устройства сейчас другое приложение")
                KioskState.NONE ->
                    fail(Response.Status.NOT_FOUND, "экран киоска не создан: откройте его на устройстве кнопкой Open kiosk")
            }

            post && uri == "/api/restart-app" -> {
                FLog.i("web", "запрошен перезапуск приложения")
                Journal.add("power", "Перезапуск приложения (из веб-интерфейса)")
                Thread { Thread.sleep(700); Power.restartApp(ctx) }.start()
                ok("приложение перезапускается")
            }
            post && uri == "/api/reboot" -> reboot(s)

            else -> {
                FLog.d("web", "404 ${s.method} $uri")
                fail(Response.Status.NOT_FOUND, "не найдено: $uri")
            }
        }
    }

    // ---------- авторизация ----------

    private fun authorized(s: IHTTPSession): Boolean {
        if (!Settings.isConfigured) return false // пока пароль не задан, доступа нет
        val h = s.headers["authorization"] ?: return false
        if (!h.startsWith("Basic ", ignoreCase = true)) return false
        val decoded = try {
            String(Base64.decode(h.substring(6).trim(), Base64.DEFAULT), Charsets.UTF_8)
        } catch (e: Exception) {
            return false
        }
        val i = decoded.indexOf(':')
        if (i < 0) return false
        val user = decoded.substring(0, i)
        val pass = decoded.substring(i + 1)
        val userOk = MessageDigest.isEqual(user.toByteArray(), "admin".toByteArray())
        val passOk = MessageDigest.isEqual(pass.toByteArray(), Settings.password.toByteArray())
        return userOk && passOk
    }

    private fun unauthorized(ip: String): Response {
        val now = System.currentTimeMillis()
        if (now - lastAuthWarn > 10_000) {
            lastAuthWarn = now
            FLog.w("web", "неверный или отсутствующий пароль, клиент $ip")
        }
        Thread.sleep(600) // замедляет подбор пароля
        val r = text(Response.Status.UNAUTHORIZED, "text/plain", "Нужен пароль (логин admin)")
        r.addHeader("WWW-Authenticate", "Basic realm=\"fServe\", charset=\"UTF-8\"")
        return r
    }

    // ---------- обработчики ----------

    private fun health(): Response = json(
        Response.Status.OK,
        obj(
            "ok" to true,
            "app" to "fServe",
            "version" to BuildConfig.VERSION_NAME,
            "time" to Util.iso(),
            "uptimeSeconds" to (System.currentTimeMillis() - App.startedAt) / 1000,
            "battery" to Util.battery(ctx),
            "lastCrash" to App.crashText(ctx).ifEmpty { null },
            "checks" to obj(
                "web" to true,
                "service" to ServerService.state.name.lowercase(),
                "telegram" to Telegram.status(),
                "updateAvailable" to (Updater.latest?.optLong("versionCode", 0L) ?: 0L).let { it > BuildConfig.VERSION_CODE }
            )
        )
    )

    private fun info(): JSONObject {
        val now = System.currentTimeMillis()
        return obj(
            "app" to obj(
                "name" to "fServe",
                "version" to BuildConfig.VERSION_NAME,
                "versionCode" to BuildConfig.VERSION_CODE,
                "package" to ctx.packageName
            ),
            "device" to obj(
                "manufacturer" to Build.MANUFACTURER,
                "model" to Build.MODEL,
                "android" to Build.VERSION.RELEASE,
                "sdk" to Build.VERSION.SDK_INT,
                "ips" to arr(Util.ips())
            ),
            "time" to obj(
                "now" to Util.iso(now),
                "epochMillis" to now,
                "timezone" to TimeZone.getDefault().id,
                "appUptimeSeconds" to (now - App.startedAt) / 1000,
                "deviceUptimeSeconds" to SystemClock.elapsedRealtime() / 1000
            ),
            "battery" to Util.battery(ctx),
            "storage" to obj("freeMegabytes" to ctx.filesDir.usableSpace / 1048576),
            "server" to obj(
                "port" to Settings.webPort,
                "startedAt" to Util.iso(ServerService.serverStartedAt),
                "softRestarts" to ServerService.restarts
            ),
            "paths" to obj(
                "files" to ctx.filesDir.absolutePath,
                "pages" to File(ctx.filesDir, "web").absolutePath
            ),
            "screen" to screenInfo(),
            "power" to obj(
                "rebootMethod" to Power.rebootMethod(ctx),
                "appRestartCron" to Settings.appRestartCron,
                "rebootCron" to Settings.rebootCron
            ),
            "logs" to obj(
                "level" to Settings.logLevel,
                "files" to FLog.files().size,
                "sizeBytes" to FLog.totalSize()
            )
        )
    }

    /** Проверка прокси: запрос к каждому из трёх адресов через него, независимо от галочек. Любой HTTP-ответ означает, что связь есть. */
    private fun proxyTest(name: String?): Response {
        if (Settings.proxyCfg("test") == null) return fail(Response.Status.BAD_REQUEST, "прокси не задан: укажите адрес и порт и сохраните")
        val all = listOf("telegram" to ("Telegram" to "https://api.telegram.org/"), "llm" to ("LLM (Gemini)" to "https://generativelanguage.googleapis.com/"), "updates" to ("GitHub" to "https://api.github.com/"))
        require(name == null || all.any { it.first == name }) { "name: telegram, llm или updates" }
        val targets = all.filter { name == null || it.first == name }.map { it.second }
        val lines = ArrayList<String>()
        var bad = 0
        for ((title, url) in targets) {
            val t0 = System.currentTimeMillis()
            try {
                val r = Net.request("test", "GET", url)
                lines.add("$title: связь есть (HTTP ${r.code}, ${System.currentTimeMillis() - t0} мс)")
            } catch (e: Exception) {
                bad++
                lines.add("$title: НЕТ СВЯЗИ: ${e.message}")
            }
        }
        val text = lines.joinToString("\n")
        FLog.i("proxy", "проверка прокси: " + lines.joinToString("; "))
        return if (bad == 0) ok(text) else fail(Response.Status.INTERNAL_ERROR, text)
    }

    private fun screenInfo(): JSONObject {
        val pm = ctx.getSystemService(Context.POWER_SERVICE) as android.os.PowerManager
        val km = ctx.getSystemService(Context.KEYGUARD_SERVICE) as android.app.KeyguardManager
        val home = ctx.packageManager.resolveActivity(
            android.content.Intent(android.content.Intent.ACTION_MAIN).addCategory(android.content.Intent.CATEGORY_HOME),
            android.content.pm.PackageManager.MATCH_DEFAULT_ONLY
        )?.activityInfo?.packageName
        return obj(
            "on" to pm.isInteractive,
            "locked" to km.isKeyguardLocked,
            "kioskVisible" to MainActivity.isVisible(),
            "homeApp" to home,
            "isHome" to (home == ctx.packageName),
            "overlayAllowed" to (android.os.Build.VERSION.SDK_INT < 23 || android.provider.Settings.canDrawOverlays(ctx))
        )
    }

    private fun apiIndex(): JSONObject = obj(
        "GET /health" to "состояние для мониторинга, без пароля",
        "GET /api" to "этот список методов",
        "GET /api/info" to "версия, устройство, время, батарея, путь к файлам, питание, лог",
        "GET /api/settings" to "настройки без секретов (токен и пароли не отдаются)",
        "POST /api/settings" to "частичное изменение настроек, JSON; пустой пароль или токен означает «не менять»",
        "GET /api/logs" to "технический лог; ?lines=300&level=INFO",
        "GET /api/logs/download" to "весь лог файлом",
        "POST /api/logs/clear" to "очистить лог",
        "GET /api/journal" to "журнал событий, новые первыми; ?limit=100",
        "POST /api/journal/clear" to "очистить журнал",
        "GET /api/config" to "JSON-конфиг задач как текст",
        "POST /api/config" to "сохранить конфиг задач (тело: JSON-текст), с проверкой и историей версий",
        "GET /api/config/history" to "сохранённые версии конфига",
        "POST /api/config/restore" to "восстановить версию конфига; ?name=файл",
        "GET /api/ui" to "страница index или kiosk; ?name=kiosk",
        "POST /api/ui" to "заменить страницу (тело: HTML); ?name=kiosk",
        "POST /api/ui/reset" to "вернуть встроенную страницу; ?name=kiosk",
        "POST /api/kiosk/show" to "вывести киоск на передний план",
        "POST /api/kiosk/reload" to "перечитать киоск на экране устройства",
        "GET /api/telegram" to "состояние Telegram: настроен ли, сколько в очереди, последняя ошибка",
        "POST /api/telegram/test" to "поставить тестовое сообщение в очередь и дождаться доставки (до таймаута сети)",
        "POST /api/telegram/send" to "поставить в очередь сообщение (тело: текст)",
        "GET /api/outbox" to "очередь неотправленных сообщений",
        "POST /api/outbox/clear" to "очистить очередь",
        "POST /api/proxy/test" to "проверить прокси: запрос к Telegram, Gemini и GitHub через него; ?name=telegram|llm|updates проверяет один адрес",
        "GET /api/update" to "состояние обновлений: установленная и последняя версия, ход установки",
        "POST /api/update/check" to "проверить GitHub Releases сейчас",
        "POST /api/update/install" to "скачать, проверить и установить последнюю версию",
        "GET /api/camera" to "состояние камеры и детектора: кадры, частота, оценка движения, ошибки",
        "POST /api/camera/start" to "включить камеру вручную",
        "POST /api/camera/stop" to "выключить камеру, включённую вручную",
        "GET /api/camera/snapshot" to "последний кадр JPEG; ?overlay=1 закрашивает ячейки с движением",
        "GET /api/camera/history" to "история оценок движения; ?limit=120",
        "GET /api/camera/test" to "ход и итог стресс-теста камеры",
        "POST /api/camera/test" to "запустить стресс-тест; ?mode=continuous&minutes=10 или ?mode=cycles&count=50&gap=3",
        "POST /api/camera/test/stop" to "остановить стресс-тест",
        "POST /api/restart-app" to "перезапустить веб-сервер, планировщик и киоск без выхода из приложения",
        "POST /api/reboot" to "перезагрузить устройство (нужен Device Owner или root); ?confirm=1",
        "GET /" to "веб-интерфейс",
        "GET /default" to "встроенный веб-интерфейс, даже если страница подменена",
        "GET /kiosk" to "страница киоска"
    )

    private fun applyCameraKeep() {
        if (Settings.camera().keepRunning && CameraEngine.hasPermission(ctx)) CameraEngine.start("keep") else CameraEngine.stop("keep")
    }

    private fun saveSettings(bodyText: String): Response {
        val patch = try {
            JSONObject(bodyText)
        } catch (e: JSONException) {
            return fail(Response.Status.BAD_REQUEST, "тело запроса не JSON")
        }
        val r = Settings.update(patch)
        if (r.errors.isNotEmpty()) {
            return json(Response.Status.BAD_REQUEST, obj("ok" to false, "error" to r.errors.joinToString("; "), "errors" to arr(r.errors)))
        }
        FLog.i("web", "настройки изменены: ${patch.keys().asSequence().filter { it != "password" }.joinToString(",")}${if (patch.has("password")) ",password" else ""}")
        if (patch.has("camera")) applyCameraKeep()
        Journal.add("settings", "Изменены настройки" + if (patch.has("password")) " (в том числе пароль)" else "")
        if (r.restartRequired) {
            // Порт сменился: перезапускаем сервер чуть позже, чтобы успеть ответить на этот запрос.
            Thread {
                Thread.sleep(800)
                ServerService.instance?.softRestart()
            }.start()
        }
        return json(
            Response.Status.OK,
            obj("ok" to true, "restartRequired" to r.restartRequired, "settings" to Settings.publicJson())
        )
    }

    private fun logs(s: IHTTPSession): Response {
        val level = q(s, "level") ?: "INFO"
        val lines = q(s, "lines")?.toIntOrNull()?.coerceIn(10, 5000) ?: 300
        return text(Response.Status.OK, "text/plain", FLog.tail(lines, level))
    }

    private fun logsDownload(): Response {
        val sb = StringBuilder()
        for (f in FLog.files()) sb.append(f.readText())
        val r = text(Response.Status.OK, "text/plain", sb.toString())
        r.addHeader("Content-Disposition", "attachment; filename=\"fserv-log.txt\"")
        return r
    }

    private fun saveConfig(bodyText: String): Response {
        val err = TaskStore.save(bodyText)
        if (err != null) {
            return json(Response.Status.BAD_REQUEST, obj("ok" to false, "error" to err))
        }
        FLog.i("web", "конфиг задач сохранён (${bodyText.length} символов)")
        Journal.add("config", "Конфиг задач изменён")
        return json(Response.Status.OK, obj("ok" to true, "message" to "сохранено"))
    }

    private fun restoreConfig(name: String): Response {
        val err = TaskStore.restore(name)
        if (err != null) return json(Response.Status.BAD_REQUEST, obj("ok" to false, "error" to err))
        FLog.i("web", "конфиг задач восстановлен из $name")
        Journal.add("config", "Конфиг задач восстановлен из версии $name")
        return json(Response.Status.OK, obj("ok" to true, "message" to "восстановлено"))
    }

    private fun reboot(s: IHTTPSession): Response {
        if (q(s, "confirm") != "1") return fail(Response.Status.BAD_REQUEST, "нужен параметр confirm=1")
        FLog.i("web", "запрошена перезагрузка устройства")
        Journal.add("power", "Перезагрузка устройства (из веб-интерфейса)")
        val err = Power.reboot(ctx)
        return if (err == null) ok("перезагрузка запущена")
        else fail(Response.Status.NOT_IMPLEMENTED, err)
    }

    // ---------- подмена страниц (web/index.html и web/kiosk.html) ----------

    private fun overrideFile(name: String) = File(File(ctx.filesDir, "web").also { it.mkdirs() }, "$name.html")

    private fun checkName(name: String?): String {
        require(name == "index" || name == "kiosk") { "name должно быть index или kiosk" }
        return name
    }

    private fun defaultHtml(name: String): String =
        ctx.assets.open("$name.html").bufferedReader(Charsets.UTF_8).use { it.readText() }

    private fun page(name: String, forceDefault: Boolean): Response {
        val f = overrideFile(name)
        val html = if (!forceDefault && f.exists() && f.length() > 0) f.readText() else defaultHtml(name)
        return text(Response.Status.OK, "text/html", html)
    }

    private fun uiGet(nameRaw: String?): Response {
        val name = checkName(nameRaw)
        val f = overrideFile(name)
        val custom = f.exists() && f.length() > 0
        return json(Response.Status.OK, obj("name" to name, "custom" to custom, "path" to overrideFile(name).absolutePath, "html" to if (custom) f.readText() else defaultHtml(name)))
    }

    private fun uiSave(nameRaw: String?, html: String): Response {
        val name = checkName(nameRaw)
        require(html.isNotBlank()) { "страница пустая" }
        require(html.length <= 1_000_000) { "страница слишком большая" }
        overrideFile(name).writeText(html)
        FLog.i("web", "страница $name заменена (${html.length} символов)")
        Journal.add("web", "Заменена страница $name.html")
        return ok("страница сохранена")
    }

    private fun uiReset(nameRaw: String?): Response {
        val name = checkName(nameRaw)
        overrideFile(name).delete()
        FLog.i("web", "страница $name возвращена к встроенной")
        Journal.add("web", "Страница $name.html возвращена к встроенной")
        return ok("возвращена встроенная страница")
    }

    // ---------- вспомогательное ----------

    private fun q(s: IHTTPSession, key: String): String? = s.parameters[key]?.firstOrNull()

    /** Тело запроса в UTF-8; читаем сами, чтобы не зависеть от кодировки NanoHTTPD. */
    private fun body(s: IHTTPSession): String {
        val len = s.headers["content-length"]?.toIntOrNull() ?: 0
        if (len <= 0) return ""
        require(len <= 2_000_000) { "тело запроса слишком большое" }
        val buf = ByteArray(len)
        var off = 0
        val ins = s.inputStream
        while (off < len) {
            val n = ins.read(buf, off, len - off)
            if (n < 0) break
            off += n
        }
        return String(buf, 0, off, Charsets.UTF_8)
    }

    private fun text(status: Response.Status, mime: String, body: String): Response {
        val r = NanoHTTPD.newFixedLengthResponse(status, "$mime; charset=utf-8", body)
        r.addHeader("Cache-Control", "no-store")
        return r
    }

    private fun json(status: Response.Status, o: Any): Response {
        val s = when (o) {
            is JSONObject -> o.toString(2)
            is JSONArray -> o.toString(2)
            else -> o.toString()
        }
        return text(status, "application/json", s)
    }

    private fun ok(message: String) = json(Response.Status.OK, obj("ok" to true, "message" to message))

    private fun fail(status: Response.Status, message: String) = json(status, obj("ok" to false, "error" to message))
}
