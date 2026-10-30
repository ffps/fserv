package com.ffps.fserv

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioManager
import android.media.MediaPlayer
import android.media.RingtoneManager
import android.os.SystemClock

/** Воспроизведение звуковых файлов и системных сигналов с плавным нарастанием и затуханием. */
object Audio {
    /** Громкость потока медиа в процентах от максимума. */
    fun setVolume(ctx: Context, percent: Int) {
        try {
            val am = ctx.getSystemService(Context.AUDIO_SERVICE) as AudioManager
            val max = am.getStreamMaxVolume(AudioManager.STREAM_MUSIC)
            val idx = Math.round(max * percent.coerceIn(0, 100) / 100f)
            am.setStreamVolume(AudioManager.STREAM_MUSIC, idx, 0)
        } catch (e: Exception) {
            FLog.w("audio", "громкость не установлена: ${e.message}")
        }
    }

    /**
     * Проигрывает [source] (имя загруженного файла или system:alarm / system:notification / system:ringtone).
     * Возвращается, когда звук закончился, прошло [forMs] или [cancel] вернул true. Возвращает текст ошибки или null.
     * Нарастание отсчитывается от начала, затухание заканчивается ровно в момент остановки.
     */
    fun play(
        ctx: Context, source: String, volumePct: Int?, fadeInMs: Long, fadeOutMs: Long,
        forMs: Long?, loop: Boolean, cancel: () -> Boolean
    ): String? {
        if (volumePct != null) setVolume(ctx, volumePct)
        val mp = MediaPlayer()
        try {
            mp.setAudioAttributes(
                AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_MEDIA).setContentType(AudioAttributes.CONTENT_TYPE_MUSIC).build()
            )
            if (source.startsWith("system:")) {
                val type = when (source) {
                    "system:alarm" -> RingtoneManager.TYPE_ALARM
                    "system:notification" -> RingtoneManager.TYPE_NOTIFICATION
                    else -> RingtoneManager.TYPE_RINGTONE
                }
                val uri = RingtoneManager.getDefaultUri(type) ?: RingtoneManager.getDefaultUri(RingtoneManager.TYPE_RINGTONE)
                    ?: return "системный звук $source недоступен на этом устройстве"
                mp.setDataSource(ctx, uri)
            } else {
                val f = Sounds.file(source)
                if (!f.isFile) return "файл «$source» не найден"
                mp.setDataSource(f.absolutePath)
            }
            mp.isLooping = loop
            mp.prepare()
            val natural = mp.duration.toLong().coerceAtLeast(0)
            val total = when {
                forMs != null && loop -> forMs
                forMs != null -> minOf(forMs, if (natural > 0) natural else forMs)
                natural > 0 -> natural
                else -> 30_000L
            }
            var completed = false
            mp.setOnCompletionListener { completed = true }
            val fadeIn = minOf(fadeInMs, total)
            val fadeOut = minOf(fadeOutMs, total)
            mp.setVolume(if (fadeIn > 0) 0f else 1f, if (fadeIn > 0) 0f else 1f)
            mp.start()
            val t0 = SystemClock.elapsedRealtime()
            while (true) {
                val pos = SystemClock.elapsedRealtime() - t0
                if (pos >= total || completed || cancel()) break
                var g = 1f
                if (fadeIn > 0 && pos < fadeIn) g = pos.toFloat() / fadeIn
                if (fadeOut > 0 && pos > total - fadeOut) g = minOf(g, (total - pos).toFloat() / fadeOut)
                g = (g * g).coerceIn(0f, 1f) // квадрат ближе к восприятию громкости, чем линейная кривая
                mp.setVolume(g, g)
                Thread.sleep(40)
            }
            return null
        } catch (e: Exception) {
            return "воспроизведение: ${e.javaClass.simpleName}: ${e.message}"
        } finally {
            try { mp.stop() } catch (e: Exception) {}
            try { mp.release() } catch (e: Exception) {}
        }
    }

    // ---- пробное воспроизведение из веб-интерфейса ----
    @Volatile private var previewStop = false
    @Volatile private var previewThread: Thread? = null
    @Volatile private var previewName: String? = null

    /** Что сейчас звучит в пробном воспроизведении (имя или system:...), либо null. */
    fun previewing(): String? = previewName

    /** Поток для скачивания системного сигнала браузером: null, если сигнала нет. */
    fun systemSound(ctx: Context, source: String): Pair<java.io.InputStream, String>? {
        val type = when (source) {
            "system:alarm" -> RingtoneManager.TYPE_ALARM
            "system:notification" -> RingtoneManager.TYPE_NOTIFICATION
            "system:ringtone" -> RingtoneManager.TYPE_RINGTONE
            else -> return null
        }
        val uri = RingtoneManager.getDefaultUri(type) ?: RingtoneManager.getDefaultUri(RingtoneManager.TYPE_RINGTONE) ?: return null
        val ins = ctx.contentResolver.openInputStream(uri) ?: return null
        return ins to (ctx.contentResolver.getType(uri)?.takeIf { it.startsWith("audio/") || it == "application/ogg" } ?: "audio/mpeg")
    }

    fun preview(ctx: Context, name: String, volumePct: Int?) {
        stopPreview()
        previewStop = false
        previewName = name
        previewThread = Thread({
            try {
                val err = play(ctx, name, volumePct, 0, 0, 60_000L, false) { previewStop }
                if (err != null) FLog.w("audio", "пробное воспроизведение: $err")
            } finally {
                if (previewThread === Thread.currentThread()) previewName = null
            }
        }, "audio-preview").also { it.start() }
    }

    fun stopPreview() {
        previewStop = true
        previewThread?.join(1500)
    }
}
