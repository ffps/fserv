package com.ffps.fserv

import android.util.Log
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.CopyOnWriteArrayList

/**
 * Технический лог в файл с ротацией: fserv.log + fserv.log.1..4, каждый до 2 МБ (итого около 10 МБ).
 * Известные секреты (пароли, токены) маскируются перед записью.
 */
object FLog {
    private const val MAX_FILE = 2L * 1024 * 1024
    private const val FILES = 5
    private val LEVELS = listOf("DEBUG", "INFO", "WARN", "ERROR")
    private val fmt = SimpleDateFormat("yyyy-MM-dd HH:mm:ss.SSS", Locale.US)
    private val secrets = CopyOnWriteArrayList<String>()

    @Volatile private var dir: File? = null
    @Volatile var minLevel: String = "INFO"

    fun init(filesDir: File) {
        dir = File(filesDir, "logs").also { it.mkdirs() }
    }

    fun addSecret(s: String?) {
        if (s != null && s.length >= 4 && !secrets.contains(s)) secrets.add(s)
    }

    fun d(tag: String, msg: String) = write("DEBUG", tag, msg, null)
    fun i(tag: String, msg: String) = write("INFO", tag, msg, null)
    fun w(tag: String, msg: String, t: Throwable? = null) = write("WARN", tag, msg, t)
    fun e(tag: String, msg: String, t: Throwable? = null) = write("ERROR", tag, msg, t)

    fun levelIndex(level: String): Int = LEVELS.indexOf(level.uppercase(Locale.US)).let { if (it < 0) 0 else it }

    @Synchronized
    private fun write(level: String, tag: String, msg: String, t: Throwable?) {
        if (levelIndex(level) < levelIndex(minLevel)) return
        var text = msg
        if (t != null) text += "\n" + Log.getStackTraceString(t)
        for (s in secrets) text = text.replace(s, "***")
        val prio = when (level) {
            "DEBUG" -> Log.DEBUG
            "INFO" -> Log.INFO
            "WARN" -> Log.WARN
            else -> Log.ERROR
        }
        Log.println(prio, "fserv.$tag", text)
        val d = dir ?: return
        try {
            val f = File(d, "fserv.log")
            if (f.length() > MAX_FILE) rotate(d)
            f.appendText("${fmt.format(Date())} ${level.padEnd(5)} $tag: $text\n")
        } catch (e: Exception) {
            Log.e("fserv.log", "log write failed", e)
        }
    }

    private fun rotate(d: File) {
        File(d, "fserv.log.${FILES - 1}").delete()
        for (n in FILES - 2 downTo 1) {
            val from = File(d, "fserv.log.$n")
            if (from.exists()) from.renameTo(File(d, "fserv.log.${n + 1}"))
        }
        File(d, "fserv.log").renameTo(File(d, "fserv.log.1"))
    }

    /** Файлы лога от старого к новому. */
    @Synchronized
    fun files(): List<File> {
        val d = dir ?: return emptyList()
        val res = ArrayList<File>()
        for (n in FILES - 1 downTo 1) File(d, "fserv.log.$n").let { if (it.exists()) res.add(it) }
        File(d, "fserv.log").let { if (it.exists()) res.add(it) }
        return res
    }

    @Synchronized
    fun totalSize(): Long = files().sumOf { it.length() }

    @Synchronized
    fun clear() {
        files().forEach { it.delete() }
    }

    /** Последние [lines] записей не ниже уровня [level]; строки стектрейса идут вместе с записью. */
    @Synchronized
    fun tail(lines: Int, level: String): String {
        val min = levelIndex(level)
        val out = ArrayList<String>()
        val all = files().reversed()
        for (f in all) {
            val part = f.readLines()
            val keep = ArrayList<String>()
            var include = true
            for (line in part) {
                val lv = lineLevel(line)
                if (lv != null) include = levelIndex(lv) >= min
                if (include) keep.add(line)
            }
            out.addAll(0, keep)
            if (out.size >= lines) break
        }
        return out.takeLast(lines).joinToString("\n")
    }

    // Формат строки: "2026-10-27 17:00:00.123 INFO  tag: text"
    private fun lineLevel(line: String): String? {
        if (line.length < 30 || line[4] != '-' || line[10] != ' ') return null
        val rest = line.substring(24).trimStart()
        val word = rest.substringBefore(' ')
        return if (word in LEVELS) word else null
    }
}
