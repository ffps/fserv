package com.ffps.fserv

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioTrack
import com.github.csukuangfj.piper.phonemize.piperPhonemizeInitialize
import com.github.csukuangfj.piper.phonemize.piperPhonemizeText
import com.k2fsa.sherpa.onnx.GenerationConfig
import com.k2fsa.sherpa.onnx.OfflineTts
import com.k2fsa.sherpa.onnx.OfflineTtsConfig
import com.k2fsa.sherpa.onnx.OfflineTtsModelConfig
import com.k2fsa.sherpa.onnx.OfflineTtsVitsModelConfig
import org.apache.commons.compress.archivers.tar.TarArchiveInputStream
import org.apache.commons.compress.compressors.bzip2.BZip2CompressorInputStream
import org.json.JSONObject
import java.io.File
import java.io.IOException
import java.util.zip.ZipInputStream

/**
 * Офлайн-нейроголос Piper (sherpa-onnx, русские модели irina/denis/dmitri/ruslan). Модель (около 65 МБ) не входит в APK:
 * скачивается один раз кнопкой на вкладке «Звук» (или загружается архивом с компьютера) и хранится в файлах приложения.
 * Включается строкой `voice engine=piper`; если модели нет или синтез сломался, [Voice] говорит системным голосом.
 * Скорость синтеза пишется в журнал (длительность речи, время синтеза, во сколько раз быстрее речи): по ней решаем, нужно ли кэшировать фразы.
 */
object Piper {
    val VOICES = listOf("irina", "denis", "dmitri", "ruslan")
    private const val THREADS = 4
    private const val ATTEMPTS = 8
    private const val MIN_ARCHIVE = 20L * 1024 * 1024
    private const val IDLE_MS = 5 * 60_000L

    fun url(voice: String) = "https://github.com/k2-fsa/sherpa-onnx/releases/download/tts-models/vits-piper-ru_RU-$voice-medium.tar.bz2"

    @Volatile var state = "none" // none | downloading | unpacking | ready | error
    @Volatile var done = 0L
    @Volatile var total = 0L
    @Volatile var lastError = ""
    @Volatile var voice = ""   // установленный голос
    /** «loading» — загружается модель, «speaking» — идёт синтез и воспроизведение, пусто — не занят. Для подсказки в веб-интерфейсе. */
    @Volatile var phase = ""

    private lateinit var app: Context
    private lateinit var root: File
    private var tts: OfflineTts? = null
    private var phonemizerReady = false
    @Volatile private var stopFlag = false

    fun init(ctx: Context) {
        app = ctx.applicationContext
        root = File(ctx.filesDir, "piper")
        File(root, "unpack").deleteRecursively()
        refreshState()
    }

    private fun modelDir() = File(root, "model")
    private fun dlDir() = File(root, "dl")
    private fun modelReady() = File(modelDir(), "model.onnx").isFile && File(modelDir(), "tokens.txt").isFile

    private fun refreshState() {
        voice = File(modelDir(), "voice.txt").takeIf { it.isFile }?.readText()?.trim().orEmpty()
        state = if (modelReady()) "ready" else "none"
    }

    fun ready() = state == "ready"

    fun status(): JSONObject = obj(
        "state" to state, "ready" to ready(), "voice" to voice.ifEmpty { null }, "voices" to arr(VOICES),
        "percent" to (if (total > 0) (done * 100 / total).toInt() else null), "doneMb" to done / 1048576, "totalMb" to (if (total > 0) total / 1048576 else null),
        "error" to lastError.ifEmpty { null }, "phase" to phase,
        "cacheFiles" to (cacheDir().listFiles()?.size ?: 0), "cacheMb" to (cacheDir().listFiles()?.sumOf { it.length() } ?: 0L) / 1048576, "pinned" to pinned
    )

    // ---------------- установка модели ----------------

    @Synchronized
    fun downloadAsync(name: String): String? {
        if (name !in VOICES) return "голос: ${VOICES.joinToString(", ")}"
        if (state == "downloading" || state == "unpacking") return "уже выполняется"
        state = "downloading"; done = 0; total = 0; lastError = ""
        Thread {
            try {
                download(name)
                lastError = ""
                refreshState()
                FLog.i("piper", "голос $name скачан и установлен")
            } catch (e: Exception) {
                lastError = e.message ?: e.javaClass.simpleName
                state = if (modelReady()) "ready" else "error"
                FLog.e("piper", "голос не скачан", e)
            }
        }.start()
        return null
    }

    private val BROWSER = mapOf(
        "User-Agent" to "Mozilla/5.0 (Linux; Android 8.0; SM-A320F) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/100.0 Mobile Safari/537.36",
        "Accept" to "*/*", "Accept-Encoding" to "identity"
    )

    /** Скачивание с докачкой, как у модели распознавания; прокси берётся из настройки «Модели речи» (вкладка «Связь»). */
    private fun download(name: String) {
        val dir = dlDir().also { it.mkdirs() }
        val f = File(dir, "$name.tar.bz2")
        // докачка чужого голоса недопустима
        dir.listFiles()?.filter { it.name != f.name }?.forEach { it.delete() }
        val u = url(name)
        FLog.i("piper", "скачиваю голос $u")
        var last = ""
        for (attempt in 1..ATTEMPTS) {
            try {
                val have = if (f.exists()) f.length() else 0L
                val headers = if (have > 0) mapOf("Range" to "bytes=$have-") else emptyMap()
                val t0 = System.currentTimeMillis()
                var nextLog = 0L
                FLog.d("piper", "попытка $attempt из $ATTEMPTS: ${if (have > 0) "продолжаю с $have байт" else "с начала"}")
                val r = Net.request("stt", "GET", u, headers = headers + BROWSER, saveTo = f, timeoutMs = 120_000) { d, t ->
                    done = d; total = t
                    val now = System.currentTimeMillis()
                    if (now >= nextLog) { nextLog = now + 10_000; FLog.d("piper", "получено ${d / 1024} КБ из ${if (t > 0) "${t / 1024} КБ" else "?"}") }
                }
                FLog.i("piper", "ответ HTTP ${r.code}, файл ${f.length()} байт, за ${(System.currentTimeMillis() - t0) / 1000} с")
                if (r.code == 416 && f.length() > MIN_ARCHIVE) break
                if (r.code != 200 && r.code != 206) throw IOException("HTTP ${r.code}")
                if (total > 0 && f.length() < total) throw IOException("получено ${f.length()} из $total байт")
                break
            } catch (e: IOException) {
                last = e.message ?: e.javaClass.simpleName
                if (attempt == ATTEMPTS) throw IOException("не удалось скачать за $ATTEMPTS попыток ($last). Включите прокси для моделей речи на вкладке «Связь» или загрузите архив с компьютера. Скачанное сохранено, повторное нажатие продолжит")
                lastError = "попытка $attempt из $ATTEMPTS не удалась ($last), повторяю"
                FLog.w("piper", lastError)
                try { Thread.sleep(3000) } catch (x: InterruptedException) {}
            }
        }
        lastError = ""
        if (f.length() < MIN_ARCHIVE) { f.delete(); throw IOException("файл модели слишком мал (${f.length()} байт)") }
        try { install(f, name) } catch (e: Exception) { dlDir().deleteRecursively(); throw e }
    }

    /**
     * Распаковывает архив голоса (tar.bz2 с сайта sherpa-onnx или zip) и ставит на место. Берутся только модель (*.onnx) и tokens.txt:
     * папка espeak-ng-data из архива не нужна (фонемайзер встроен в приложение) и занимала бы место зря.
     */
    private fun install(archive: File, name: String?) {
        state = "unpacking"
        val out = File(root, "unpack").also { it.deleteRecursively(); it.mkdirs() }
        try {
            val head = ByteArray(4)
            archive.inputStream().use { it.read(head) }
            val isZip = head[0] == 'P'.code.toByte() && head[1] == 'K'.code.toByte()
            val isBz = head[0] == 'B'.code.toByte() && head[1] == 'Z'.code.toByte() && head[2] == 'h'.code.toByte()
            if (!isZip && !isBz) throw IOException("неизвестный формат архива: нужен tar.bz2 (как на GitHub) или zip")
            var onnx: String? = null
            fun take(entryName: String, input: java.io.InputStream) {
                val base = entryName.substringAfterLast('/')
                val target = when {
                    base == "tokens.txt" -> "tokens.txt"
                    base.endsWith(".onnx") && onnx == null && !entryName.contains("espeak-ng-data") -> { onnx = base; "model.onnx" }
                    else -> return
                }
                File(out, target).outputStream().use { o -> input.copyTo(o) }
            }
            if (isZip) {
                ZipInputStream(archive.inputStream().buffered()).use { z ->
                    while (true) { val e = z.nextEntry ?: break; if (!e.isDirectory) take(e.name, z) }
                }
            } else {
                TarArchiveInputStream(BZip2CompressorInputStream(archive.inputStream().buffered())).use { t ->
                    while (true) { val e = t.nextEntry ?: break; if (e.isFile) take(e.name, t) }
                }
            }
            if (!File(out, "model.onnx").isFile || !File(out, "tokens.txt").isFile) throw IOException("в архиве нет модели голоса (нужны файл .onnx и tokens.txt)")
            // имя голоса: из параметра или из имени файла вида ru_RU-irina-medium.onnx
            val label = name ?: Regex("""ru_RU-([a-z]+)-""").find(onnx.orEmpty())?.groupValues?.get(1) ?: onnx.orEmpty().removeSuffix(".onnx")
            File(out, "voice.txt").writeText(label)
            release()
            cacheDir().deleteRecursively()
            modelDir().deleteRecursively()
            if (!out.renameTo(modelDir())) throw IOException("не удалось сохранить модель")
        } finally {
            out.deleteRecursively()
        }
        dlDir().deleteRecursively()
    }

    /** Сохраняет тело запроса (архив голоса с компьютера) во временный файл не больше 200 МБ. */
    fun receive(ctx: Context, input: java.io.InputStream, len: Long): File {
        require(len in 1..(200L * 1024 * 1024)) { "архив голоса должен быть не больше 200 МБ" }
        val tmp = File(ctx.cacheDir, "piper-upload.bin")
        tmp.delete()
        tmp.outputStream().use { out ->
            val buf = ByteArray(64 * 1024)
            var left = len
            while (left > 0) {
                val k = input.read(buf, 0, minOf(buf.size.toLong(), left).toInt())
                if (k < 0) break
                out.write(buf, 0, k)
                left -= k
            }
            require(left == 0L) { "загрузка оборвалась, получено ${len - left} из $len байт" }
        }
        return tmp
    }

    fun installFromFile(archive: File): String? {
        synchronized(this) {
            if (state == "downloading" || state == "unpacking") return "уже выполняется скачивание или распаковка"
            state = "unpacking"; lastError = ""
        }
        return try {
            install(archive, null)
            refreshState()
            FLog.i("piper", "голос $voice установлен из загруженного архива")
            TaskEngine.refreshStt()
            null
        } catch (e: Exception) {
            refreshState()
            lastError = e.message ?: e.javaClass.simpleName
            FLog.w("piper", "загруженный архив не подошёл: $lastError")
            lastError
        } finally {
            archive.delete()
        }
    }

    @Synchronized
    fun delete() {
        release()
        cacheDir().deleteRecursively()
        modelDir().deleteRecursively()
        dlDir().deleteRecursively()
        voice = ""; state = "none"; lastError = ""
    }

    // ---------------- синтез ----------------

    @Synchronized
    private fun engine(): OfflineTts {
        tts?.let { return it }
        if (!modelReady()) throw IllegalStateException("голос Piper не скачан: вкладка «Звук», раздел «Голос Piper»")
        phase = "loading"
        val t0 = System.currentTimeMillis()
        val mem0 = android.os.Debug.getNativeHeapAllocatedSize()
        try {
            if (!phonemizerReady) { piperPhonemizeInitialize(app); phonemizerReady = true }
            val cfg = OfflineTtsConfig(
                model = OfflineTtsModelConfig(
                    vits = OfflineTtsVitsModelConfig(model = File(modelDir(), "model.onnx").absolutePath, tokens = File(modelDir(), "tokens.txt").absolutePath),
                    numThreads = THREADS, debug = false, provider = "cpu"
                ),
                maxNumSentences = 1
            )
            val t = OfflineTts(null, cfg)
            tts = t
            FLog.i("piper", "голос $voice загружен за ${System.currentTimeMillis() - t0} мс (+${(android.os.Debug.getNativeHeapAllocatedSize() - mem0) / 1048576} МБ нативной памяти), частота ${t.sampleRate()} Гц, потоков $THREADS, процесс ${if (android.os.Process.is64Bit()) "64" else "32"}-разрядный, ABI ${android.os.Build.SUPPORTED_ABIS.joinToString()}")
            return t
        } catch (e: Throwable) {
            throw IllegalStateException("голос Piper не загрузился: ${e.message ?: e.javaClass.simpleName}")
        } finally {
            phase = ""
        }
    }

    // ---------------- кэш фраз ----------------

    private const val CACHE_MAX = 30L * 1024 * 1024
    private fun cacheDir() = File(root, "cache")

    private fun cacheFile(text: String, speed: Float): File {
        val h = java.security.MessageDigest.getInstance("SHA-1").digest("$voice|${"%.2f".format(java.util.Locale.US, speed)}|$text".toByteArray())
        return File(cacheDir(), h.joinToString("") { "%02x".format(it) } + ".pcm")
    }

    /** Готовая фраза: 4 байта частоты и 16-битный звук. Кэш не больше [CACHE_MAX]: лишнее (давно не звучавшее) удаляется. */
    private fun cachePut(f: File, rate: Int, pcm: ByteArray) {
        try {
            f.parentFile?.mkdirs()
            val tmp = File(f.parentFile, f.name + ".tmp")
            java.io.DataOutputStream(tmp.outputStream().buffered()).use { it.writeInt(rate); it.write(pcm) }
            if (!tmp.renameTo(f)) tmp.delete()
            val all = cacheDir().listFiles { x -> x.name.endsWith(".pcm") }?.sortedBy { it.lastModified() } ?: return
            var sum = all.sumOf { it.length() }
            for (old in all) { if (sum <= CACHE_MAX) break; sum -= old.length(); old.delete() }
        } catch (e: Exception) { FLog.w("piper", "кэш фразы не записан: ${e.message}") }
    }

    private fun newTrack(rate: Int, enc: Int): AudioTrack {
        val minBuf = AudioTrack.getMinBufferSize(rate, AudioFormat.CHANNEL_OUT_MONO, enc)
        return AudioTrack(
            AudioAttributes.Builder().setContentType(AudioAttributes.CONTENT_TYPE_SPEECH).setUsage(AudioAttributes.USAGE_MEDIA).build(),
            AudioFormat.Builder().setEncoding(enc).setSampleRate(rate).setChannelMask(AudioFormat.CHANNEL_OUT_MONO).build(),
            maxOf(minBuf, rate * 4), AudioTrack.MODE_STREAM, AudioManager.AUDIO_SESSION_ID_GENERATE
        )
    }

    /** Играет готовую фразу из кэша: модель для этого не нужна. */
    private fun playCached(f: File, text: String, cancel: () -> Boolean): String? {
        var track: AudioTrack? = null
        try {
            phase = "speaking"
            f.setLastModified(System.currentTimeMillis())
            java.io.DataInputStream(f.inputStream().buffered()).use { inp ->
                val rate = inp.readInt()
                val tr = newTrack(rate, AudioFormat.ENCODING_PCM_16BIT)
                track = tr
                tr.play()
                val buf = ByteArray(16384)
                var frames = 0L
                while (!stopFlag && !cancel()) {
                    val n = inp.read(buf)
                    if (n <= 0) break
                    val sh = ShortArray(n / 2)
                    java.nio.ByteBuffer.wrap(buf, 0, n - n % 2).order(java.nio.ByteOrder.LITTLE_ENDIAN).asShortBuffer().get(sh)
                    tr.write(sh, 0, sh.size, AudioTrack.WRITE_BLOCKING)
                    frames += sh.size
                }
                val end = System.currentTimeMillis() + frames * 1000 / rate + 3000
                while (!stopFlag && !cancel() && tr.playbackHeadPosition < frames && System.currentTimeMillis() < end) Thread.sleep(50)
                FLog.d("piper", "фраза из кэша (${text.length} симв., ${frames * 1000 / rate / 1000.0} с)")
            }
            return null
        } catch (e: Throwable) {
            return "голос Piper (кэш): ${e.message ?: e.javaClass.simpleName}"
        } finally {
            phase = ""
            try { track?.stop() } catch (e: Exception) {}
            try { track?.release() } catch (e: Exception) {}
        }
    }

    // ---------------- синтез ----------------

    private val liveReq = java.util.concurrent.atomic.AtomicInteger()

    /**
     * Произносит [text] и ждёт конца речи. [speed] — скорость (1 = обычная). Готовая фраза берётся из кэша (быстро и без модели),
     * иначе синтезируется: звук начинается, не дожидаясь конца синтеза, а результат сохраняется в кэш. Возвращает текст ошибки или null.
     */
    fun speak(text: String, speed: Float, cancel: () -> Boolean): String? {
        liveReq.incrementAndGet()
        try {
            // нативный синтез идёт в потоке с большим стеком: в обычном потоке (около 1 МБ) вычисления могли бы его переполнить
            var res: String? = null
            val th = Thread(null, { res = speakImpl(text, speed, cancel, true) }, "piper-synth", 64L * 1024 * 1024)
            th.start()
            th.join()
            return res
        } finally { liveReq.decrementAndGet() }
    }

    /** [play] = false: фраза только готовится в кэш (без звука). */
    @Synchronized
    private fun speakImpl(text: String, speed: Float, cancel: () -> Boolean, play: Boolean): String? {
        if (play) {
            stopFlag = false
            val cf = cacheFile(text, speed)
            if (cf.isFile && cf.length() > 8) return playCached(cf, text, cancel)
        }
        val t = try { engine() } catch (e: IllegalStateException) { return e.message }
        val t0 = System.currentTimeMillis()
        var track: AudioTrack? = null
        try {
            if (play) phase = "speaking"
            val lang = t.lang().ifEmpty { "ru" }
            val ph = piperPhonemizeText(text, lang) ?: return "не удалось разобрать текст для голоса Piper"
            val sentences = try { Array(ph.numSentences) { i -> ph.getPhonemes(i) } } finally { ph.close() }
            val tPh = System.currentTimeMillis() - t0
            val rate = t.sampleRate()
            if (play) { val tr = newTrack(rate, AudioFormat.ENCODING_PCM_FLOAT); track = tr; tr.play() }
            cbTrack = track; cbCancel = cancel; cbFrames = 0L; cbFirstMs = -1L; cbT0 = t0; cbPcm = java.io.ByteArrayOutputStream()
            val g = GenerationConfig(speed = speed, sid = 0, silenceScale = 0.2f, phonemeCodepoints = sentences)
            val tGen = System.currentTimeMillis()
            t.generateWithConfigAndCallback("", g, this::callback)
            val genMs = System.currentTimeMillis() - tGen
            val frames = cbFrames
            val firstMs = cbFirstMs
            val speechMs = frames * 1000 / rate
            val aborted = (play && stopFlag) || cancel()
            if (!aborted && frames > 0) cachePut(cacheFile(text, speed), rate, cbPcm!!.toByteArray())
            cbPcm = null; cbTrack = null
            if (play) {
                // дожидаемся, пока доиграет записанное в буфер
                val tr = track!!
                val end = System.currentTimeMillis() + speechMs + 3000
                while (!aborted && tr.playbackHeadPosition < frames && System.currentTimeMillis() < end) {
                    if (stopFlag || cancel()) break
                    Thread.sleep(50)
                }
            }
            val msg = "${if (play) "фраза" else "в кэш"} ${text.length} симв.: речь ${speechMs / 1000.0} с, синтез ${genMs / 1000.0} с (${if (genMs > 0) "%.2f".format(speechMs.toDouble() / genMs) else "?"}× быстрее речи), до первого звука ${firstMs} мс (разбор текста $tPh мс)"
            if (play) FLog.i("piper", msg + "; " + mem()) else FLog.d("piper", msg)
            return null
        } catch (e: Throwable) {
            return "голос Piper: ${e.message ?: e.javaClass.simpleName}"
        } finally {
            if (play) phase = ""
            try { track?.stop() } catch (e: Exception) {}
            try { track?.release() } catch (e: Exception) {}
            touch()
        }
    }

    // Состояние текущего синтеза для callback. Нативный код sherpa-onnx ищет у обратного вызова метод invoke([F)Ljava/lang/Integer;,
    // которого нет у лямбды Kotlin (она компилируется без типизированного invoke): с лямбдой процесс падал. Нужна ссылка на метод, как в примере sherpa-onnx.
    private var cbTrack: AudioTrack? = null
    private var cbCancel: () -> Boolean = { false }
    private var cbFrames = 0L
    private var cbFirstMs = -1L
    private var cbT0 = 0L
    private var cbPcm: java.io.ByteArrayOutputStream? = null

    /** Вызывается из нативного кода с очередным куском звука; 0 прерывает синтез. */
    private fun callback(samples: FloatArray): Int {
        if ((cbTrack != null && stopFlag) || cbCancel()) return 0
        if (cbFirstMs < 0) cbFirstMs = System.currentTimeMillis() - cbT0
        cbTrack?.write(samples, 0, samples.size, AudioTrack.WRITE_BLOCKING)
        cbFrames += samples.size
        val b = ByteArray(samples.size * 2)
        for (i in samples.indices) {
            val v = (samples[i] * 32767f).toInt().coerceIn(-32768, 32767)
            b[2 * i] = v.toByte(); b[2 * i + 1] = (v shr 8).toByte()
        }
        cbPcm?.write(b)
        return 1
    }

    fun stop() { stopFlag = true }

    /** Память для журнала: свободно в системе и занято нативной кучей приложения. */
    private fun mem(): String {
        val mi = android.app.ActivityManager.MemoryInfo()
        (app.getSystemService(Context.ACTIVITY_SERVICE) as android.app.ActivityManager).getMemoryInfo(mi)
        return "свободно ${mi.availMem / 1048576} МБ, нативная куча ${android.os.Debug.getNativeHeapAllocatedSize() / 1048576} МБ"
    }

    // ---------------- заготовка фраз и память ----------------

    /** true — голос держится в памяти постоянно (в задачах есть речь голосом Piper): загрузка модели занимает около 10 секунд на слабом планшете. */
    @Volatile var pinned = false
    @Volatile private var prepGen = 0

    /**
     * Вызывается при старте и после любой правки задач, голоса или модели. Если речь в задачах идёт голосом Piper ([pin]),
     * модель загружается в фоне и остаётся в памяти, а все фразы без переменных ([phrases]) заранее озвучиваются в кэш.
     * Живая речь важнее: заготовка уступает ей и продолжается после.
     */
    fun configure(pin: Boolean, phrases: List<String>, speed: Float) {
        val gen = ++prepGen
        pinned = pin && ready()
        if (!pinned) { touch(); return }
        Thread(null, { prepare(gen, phrases, speed) }, "piper-prep", 64L * 1024 * 1024).start()
    }

    private fun prepare(gen: Int, phrases: List<String>, speed: Float) {
        try {
            try { engine() } catch (e: IllegalStateException) { FLog.w("piper", e.message ?: "голос не загрузился"); return }
            val t0 = System.currentTimeMillis()
            var made = 0
            for (ph in phrases) {
                if (gen != prepGen) return
                if (cacheFile(ph, speed).isFile) continue
                var err: String? = null
                var tries = 0
                while (gen == prepGen && tries++ < 20) {
                    while (liveReq.get() > 0 && gen == prepGen) Thread.sleep(300)
                    if (gen != prepGen) return
                    err = speakImpl(ph, speed, { gen != prepGen || liveReq.get() > 0 }, false)
                    if (err != null || cacheFile(ph, speed).isFile) break // иначе прервала живая речь: повторим
                }
                if (err != null) { FLog.w("piper", "фраза не озвучена в кэш: $err"); return }
                made++
            }
            if (made > 0) FLog.i("piper", "в кэш добавлено фраз: $made, за ${(System.currentTimeMillis() - t0) / 1000} с; в кэше ${cacheDir().listFiles()?.size ?: 0}")
        } catch (e: Throwable) {
            FLog.w("piper", "заготовка фраз: ${e.message ?: e.javaClass.simpleName}")
        }
    }

    @Synchronized
    fun release() {
        idle?.cancel(); idle = null
        try { tts?.release() } catch (e: Throwable) {}
        tts = null
    }

    private val timer = java.util.Timer("piper-idle", true)
    private var idle: java.util.TimerTask? = null

    /** Без закрепления модель держится в памяти [IDLE_MS] после последней фразы, потом выгружается. */
    @Synchronized
    private fun touch() {
        idle?.cancel(); idle = null
        if (tts == null || pinned) return
        val t = object : java.util.TimerTask() { override fun run() { if (!pinned) { release(); FLog.d("piper", "голос выгружен из памяти (простой)") } } }
        idle = t
        timer.schedule(t, IDLE_MS)
    }
}
