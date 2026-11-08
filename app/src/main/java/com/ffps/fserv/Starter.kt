package com.ffps.fserv

import android.content.Context
import java.io.File
import java.util.zip.ZipFile

/**
 * Стартовый набор (assets/starter.zip: задачи, общие списки, слайды, звуки) раскладывается один раз, при самой первой установке
 * приложения на чистое устройство. При обновлении, при установке поверх и после сброса настроек он не добавляется.
 * Настройки по умолчанию (имя помощника, правила чата) живут в коде ([Settings], [Chat]), а не в наборе.
 * Формат архива такой же, как у резервной копии, но settings.json не нужен.
 */
object Starter {
    private const val ASSET = "starter.zip"

    /** Начало работы приложения: проверяет, первая ли это установка, и при необходимости раскладывает набор. Любой сбой только пишется в журнал. */
    fun installIfFirst(ctx: Context) {
        val marker = File(ctx.filesDir, "starter.done")
        if (marker.exists()) return
        try {
            val pi = ctx.packageManager.getPackageInfo(ctx.packageName, 0)
            val fresh = pi.lastUpdateTime - pi.firstInstallTime < 60_000L // обновление приложения сдвигает время последнего обновления
            val empty = TaskStore.files().isEmpty() && TaskStore.listsText().isBlank() && Sounds.names().isEmpty() && Slides.names().isEmpty()
            if (fresh && empty) install(ctx) else FLog.d("app", "стартовый набор не нужен: ${if (!fresh) "это обновление" else "данные уже есть"}")
        } catch (e: Throwable) {
            FLog.w("app", "стартовый набор не разложен: ${e.message}")
        } finally {
            try { marker.writeText(Util.iso()) } catch (e: Exception) {}
        }
    }

    /** Раскладывает набор (при первой установке и по желанию при сбросе). Бросает исключение, если набор не годится. */
    fun install(ctx: Context) {
        val tmp = File(ctx.cacheDir, "starter.zip")
        ctx.assets.open(ASSET).use { i -> tmp.outputStream().use { o -> i.copyTo(o) } }
        try {
            ZipFile(tmp).use { zf ->
                val entries = zf.entries().asSequence().filter { !it.isDirectory }.toList()
                val tasks = LinkedHashMap<String, String>()
                var lists = ""
                val sounds = HashSet<String>()
                val slides = HashSet<String>()
                for (e in entries) {
                    val n = e.name
                    when {
                        n == "lists.txt" -> lists = zf.getInputStream(e).use { String(it.readBytes(), Charsets.UTF_8) }
                        n.startsWith("tasks/") -> tasks[n.removePrefix("tasks/")] = zf.getInputStream(e).use { String(it.readBytes(), Charsets.UTF_8) }
                        n.startsWith("sounds/") -> sounds.add(n.removePrefix("sounds/"))
                        n.startsWith("slides/") -> slides.add(n.removePrefix("slides/"))
                    }
                }
                val table = Lists.parse(lists)
                TaskSet.validateSet(tasks, org.json.JSONObject(), org.json.JSONObject(), table, { slides.contains(it) }) { sounds.contains(it) }?.let { throw IllegalStateException(it) }
                for (e in entries) {
                    when {
                        e.name.startsWith("sounds/") -> Sounds.save(e.name.removePrefix("sounds/"), zf.getInputStream(e), e.size)
                        e.name.startsWith("slides/") -> Slides.save(e.name.removePrefix("slides/"), zf.getInputStream(e), e.size)
                    }
                }
                TaskStore.setLists(lists.ifBlank { null })
                TaskStore.replaceAll(tasks)
                TaskStore.invalidate()
                FLog.i("app", "первая установка: разложен стартовый набор (задач ${tasks.size}, звуков ${sounds.size}, слайдов ${slides.size})")
            }
        } finally {
            tmp.delete()
        }
    }
}
