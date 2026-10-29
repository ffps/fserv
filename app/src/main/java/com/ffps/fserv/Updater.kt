package com.ffps.fserv

import android.content.Context
import android.content.Intent
import android.content.pm.PackageInstaller
import android.app.PendingIntent
import android.os.Build
import org.json.JSONObject
import java.io.File
import java.security.MessageDigest

/**
 * Обновления из GitHub Releases: проверка последнего релиза (version.json), скачивание APK, сверка sha256
 * и пакета, установка через PackageInstaller. Ставится только более новый versionCode, подпись проверяет система.
 * Установка запускается вручную из веб-интерфейса; по расписанию выполняется только проверка и сообщение в Telegram.
 */
object Updater {
    @Volatile var state = "idle" // idle | checking | downloading | installing
    @Volatile var lastError = ""
    @Volatile var lastCheck = 0L
    @Volatile var latest: JSONObject? = null
    @Volatile private var done = 0L
    @Volatile private var total = 0L

    const val ACTION_RESULT = "com.ffps.fserv.INSTALL_RESULT"

    fun canInstall(ctx: Context): Boolean = ctx.packageManager.canRequestPackageInstalls()

    fun status(ctx: Context): JSONObject {
        val l = latest
        return obj(
            "current" to obj("versionName" to BuildConfig.VERSION_NAME, "versionCode" to BuildConfig.VERSION_CODE),
            "latest" to l,
            "available" to (l != null && l.optLong("versionCode") > BuildConfig.VERSION_CODE),
            "repo" to Settings.updateRepo,
            "lastCheck" to if (lastCheck > 0) Util.iso(lastCheck) else null,
            "lastError" to lastError,
            "state" to state,
            "confirm" to (state == "confirm"),
            "progress" to obj("bytes" to done, "total" to total),
            "canInstall" to canInstall(ctx)
        )
    }

    /** Запрашивает последний релиз. Бросает исключение с понятным текстом при ошибке. */
    @Synchronized
    fun check(): JSONObject {
        state = "checking"
        try {
            val repo = Settings.updateRepo
            val r = Net.request(
                "updates", "GET", "https://api.github.com/repos/$repo/releases/latest",
                headers = mapOf("Accept" to "application/vnd.github+json")
            )
            if (r.code != 200) throw java.io.IOException("GitHub: HTTP ${r.code} (${r.via}) ${r.body.take(120)}")
            val rel = JSONObject(r.body)
            val assets = HashMap<String, String>()
            val a = rel.optJSONArray("assets")
            if (a != null) for (i in 0 until a.length()) {
                val x = a.getJSONObject(i)
                assets[x.getString("name")] = x.getString("browser_download_url")
            }
            val vUrl = assets["version.json"] ?: throw java.io.IOException("в релизе ${rel.optString("tag_name")} нет version.json")
            val vr = Net.request("updates", "GET", vUrl)
            if (vr.code != 200) throw java.io.IOException("version.json: HTTP ${vr.code}")
            val v = JSONObject(vr.body)
            val apk = v.getString("apk")
            val res = obj(
                "tag" to rel.optString("tag_name"),
                "versionName" to v.getString("versionName"),
                "versionCode" to v.getLong("versionCode"),
                "apk" to apk,
                "apkUrl" to (assets[apk] ?: throw java.io.IOException("в релизе нет файла $apk")),
                "sha256" to v.getString("sha256").lowercase(),
                "notes" to rel.optString("body").take(1000)
            )
            latest = res
            lastCheck = System.currentTimeMillis()
            lastError = ""
            FLog.i("update", "проверка: последняя ${res.optString("versionName")} (${res.optLong("versionCode")}), установлена ${BuildConfig.VERSION_NAME}")
            return res
        } catch (e: Exception) {
            lastError = e.message ?: e.javaClass.simpleName
            lastCheck = System.currentTimeMillis()
            FLog.w("update", "проверка не удалась: $lastError")
            throw e
        } finally {
            state = "idle"
        }
    }

    /** Проверка по расписанию: при новой версии один раз пишет в Telegram. */
    fun scheduledCheck() {
        try {
            val l = check()
            if (l.optLong("versionCode") > BuildConfig.VERSION_CODE && Settings.getStr("notifiedVersion") != l.optString("versionName")) {
                Settings.putStr("notifiedVersion", l.optString("versionName"))
                Journal.add("update", "Доступно обновление ${l.optString("versionName")}")
                Telegram.enqueue("fServe: доступно обновление ${l.optString("versionName")} (установлена ${BuildConfig.VERSION_NAME}). Установить можно из веб-интерфейса, вкладка «Обновления».")
            }
        } catch (e: Exception) {
            // уже записано в лог
        }
    }

    /** Скачивает и ставит последнюю версию (фоновый поток). Возвращает текст ошибки, если запуск невозможен. */
    fun installAsync(ctx: Context): String? {
        if (state != "idle" && state != "confirm") return "уже выполняется: $state"
        if (!canInstall(ctx)) return "приложению не разрешена установка обновлений: на устройстве откройте окно настроек fServe и нажмите «Allow app updates»"
        Thread {
            try {
                val l = latest ?: check()
                if (l.optLong("versionCode") <= BuildConfig.VERSION_CODE) throw IllegalStateException("новой версии нет")
                download(ctx, l)
            } catch (e: Exception) {
                lastError = e.message ?: e.javaClass.simpleName
                FLog.e("update", "установка не удалась", e)
                Journal.add("update", "Обновление не удалось: $lastError")
                state = "idle"
            }
        }.start()
        return null
    }

    private fun download(ctx: Context, l: JSONObject) {
        state = "downloading"
        done = 0; total = 0
        val dir = File(ctx.filesDir, "update").also { it.mkdirs() }
        dir.listFiles()?.forEach { it.delete() }
        val f = File(dir, l.getString("apk"))
        FLog.i("update", "скачиваю ${l.getString("apk")}")
        val r = Net.request("updates", "GET", l.getString("apkUrl"), saveTo = f) { d, t -> done = d; total = t }
        if (r.code != 200) throw java.io.IOException("скачивание: HTTP ${r.code} (${r.via})")
        val md = MessageDigest.getInstance("SHA-256")
        f.inputStream().use { ins -> val b = ByteArray(65536); while (true) { val n = ins.read(b); if (n < 0) break; md.update(b, 0, n) } }
        val sha = md.digest().joinToString("") { "%02x".format(it) }
        if (sha != l.getString("sha256")) throw java.io.IOException("sha256 не совпал: получено $sha")
        val pi = ctx.packageManager.getPackageArchiveInfo(f.absolutePath, 0)
            ?: throw java.io.IOException("файл не является APK")
        if (pi.packageName != ctx.packageName) throw java.io.IOException("чужой пакет ${pi.packageName}")
        if (pi.longVersionCode != l.getLong("versionCode")) throw java.io.IOException("versionCode в APK ${pi.longVersionCode} не совпал с version.json")
        install(ctx, f)
    }

    private fun install(ctx: Context, f: File) {
        state = "installing"
        val inst = ctx.packageManager.packageInstaller
        val params = PackageInstaller.SessionParams(PackageInstaller.SessionParams.MODE_FULL_INSTALL)
        params.setAppPackageName(ctx.packageName)
        if (Build.VERSION.SDK_INT >= 31) params.setRequireUserAction(PackageInstaller.SessionParams.USER_ACTION_NOT_REQUIRED)
        val id = inst.createSession(params)
        inst.openSession(id).use { s ->
            f.inputStream().use { ins -> s.openWrite("fserv.apk", 0, f.length()).use { out -> ins.copyTo(out); s.fsync(out) } }
            val intent = Intent(ctx, UpdateReceiver::class.java).setAction(ACTION_RESULT)
            val pend = PendingIntent.getBroadcast(ctx, id, intent, PendingIntent.FLAG_MUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
            s.commit(pend.intentSender)
        }
        FLog.i("update", "установка передана системе (сессия $id)")
        Journal.add("update", "Установка версии ${latest?.optString("versionName")} передана системе")
    }
}
