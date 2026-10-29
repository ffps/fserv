package com.ffps.fserv

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Журнал событий для человека («встала в 9:12», «сообщение отправлено»): одна JSON-запись на строку.
 * Хранится не более 90 дней и не более 5 МБ.
 */
object Journal {
    private const val MAX_BYTES = 5L * 1024 * 1024
    private const val MAX_AGE_MS = 90L * 24 * 3600 * 1000
    private val iso = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US)
    private lateinit var file: File
    private var appends = 0

    fun init(ctx: Context) {
        file = File(ctx.filesDir, "events.jsonl")
    }

    @Synchronized
    fun add(type: String, text: String) {
        try {
            val now = System.currentTimeMillis()
            val o = obj("ts" to now, "time" to iso.format(Date(now)), "type" to type, "text" to text)
            file.appendText(o.toString() + "\n")
            appends++
            if (appends % 100 == 0 || file.length() > MAX_BYTES) trim(now)
        } catch (e: Exception) {
            FLog.e("journal", "append failed", e)
        }
    }

    private fun trim(now: Long) {
        val fresh = file.readLines().filter { l ->
            try {
                JSONObject(l).optLong("ts") > now - MAX_AGE_MS
            } catch (e: Exception) {
                false
            }
        }
        val keep = ArrayList<String>()
        var total = 0L
        for (l in fresh.asReversed()) {
            total += l.length + 1
            if (total > MAX_BYTES) break
            keep.add(l)
        }
        keep.reverse()
        file.writeText(if (keep.isEmpty()) "" else keep.joinToString("\n") + "\n")
    }

    /** Последние [limit] событий, новые первыми. */
    @Synchronized
    fun last(limit: Int): JSONArray {
        val res = JSONArray()
        if (!file.exists()) return res
        val lines = file.readLines()
        for (l in lines.asReversed()) {
            if (res.length() >= limit) break
            try {
                res.put(JSONObject(l))
            } catch (e: Exception) {
                // битая строка пропускается
            }
        }
        return res
    }

    @Synchronized
    fun clear() {
        file.delete()
    }
}
