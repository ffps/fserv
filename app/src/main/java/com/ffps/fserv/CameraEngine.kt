package com.ffps.fserv

import android.content.Context
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.ImageFormat
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.Rect
import android.graphics.YuvImage
import android.hardware.camera2.CameraAccessException
import android.hardware.camera2.CameraCaptureSession
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraDevice
import android.hardware.camera2.CameraManager
import android.hardware.camera2.CaptureRequest
import android.hardware.camera2.params.OutputConfiguration
import android.hardware.camera2.params.SessionConfiguration
import android.media.Image
import android.media.ImageReader
import android.os.Build
import android.os.Handler
import android.os.HandlerThread
import org.json.JSONArray
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.util.concurrent.Executor
import kotlin.math.abs

/**
 * Передняя камера и детектор движения.
 *
 * Камера открывается один раз и отдаёт кадры с низкой частотой; анализируется только яркость (плоскость Y):
 * кадр делится на сетку 64x48, из ячеек вычитается средняя яркость кадра (чтобы автоэкспозиция и смена освещения
 * не давали «движения»), затем ячейки сравниваются с предыдущим обработанным кадром. Оценка движения (score) —
 * доля ячеек, изменившихся больше порога, в процентах. Движение есть, если score не меньше заданной площади.
 *
 * Камерой могут пользоваться несколько «владельцев» (сценарии, тест): камера открыта, пока есть хотя бы один.
 * Все операции с камерой идут в одном потоке. Если кадры перестают приходить или камера отваливается,
 * она открывается заново с нарастающей паузой.
 */
object CameraEngine {
    enum class State { STOPPED, OPENING, RUNNING, ERROR }

    private const val GW = 64
    private const val GH = 48
    private const val HISTORY = 600

    private lateinit var app: Context
    private var thread: HandlerThread? = null
    private lateinit var h: Handler

    private val owners = LinkedHashSet<String>()

    // ---- состояние камеры (меняется только в потоке камеры) ----
    private var device: CameraDevice? = null
    private var session: CameraCaptureSession? = null
    private var reader: ImageReader? = null
    private var openedAt = 0L
    private var retryDelay = 2000L
    private var generation = 0 // номер попытки открытия; устаревшие обратные вызовы игнорируются

    @Volatile var state = State.STOPPED
    @Volatile var lastError = ""
    @Volatile var cameraId = ""
    @Volatile var width = 0
    @Volatile var height = 0
    @Volatile var sensorOrientation = 0

    // ---- статистика ----
    @Volatile var framesTotal = 0L
    @Volatile var framesProcessed = 0L
    @Volatile var opens = 0
    @Volatile var restarts = 0
    @Volatile var lastFrameAt = 0L
    @Volatile var firstFrameLatencyMs = 0L
    @Volatile var avgProcessMs = 0.0
    @Volatile var fps = 0.0
    private var fpsWindowStart = 0L
    private var fpsWindowFrames = 0

    // ---- детектор ----
    private var prev: FloatArray? = null
    private val changed = BooleanArray(GW * GH)
    @Volatile var lastScore = 0.0
    @Volatile var lastLuma = 0.0
    @Volatile var motion = false
    @Volatile var lastMotionAt = 0L
    private var lastProcessedAt = 0L

    private val histLock = Object()
    private val history = ArrayList<DoubleArray>() // [время, score, luma, движение 0/1]

    // ---- последний кадр для просмотра ----
    private val snapLock = Object()
    private var snapNv21: ByteArray? = null
    private var snapW = 0
    private var snapH = 0
    private var snapChanged: BooleanArray? = null

    fun init(ctx: Context) {
        app = ctx.applicationContext
    }

    fun hasPermission(ctx: Context): Boolean =
        ctx.checkSelfPermission(android.Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED

    private fun ensureThread() {
        synchronized(this) {
            if (thread == null) {
                val t = HandlerThread("camera")
                t.start()
                thread = t
                h = Handler(t.looper)
            }
        }
    }

    /** Есть ли у устройства передняя камера (или хоть какая-то). */
    fun hasCamera(): Boolean = try {
        (app.getSystemService(Context.CAMERA_SERVICE) as CameraManager).cameraIdList.isNotEmpty()
    } catch (e: Exception) {
        false
    }

    // ---------------- управление ----------------

    fun start(owner: String) {
        ensureThread()
        synchronized(owners) { owners.add(owner) }
        h.post { openIfNeeded() }
    }

    fun stop(owner: String) {
        ensureThread()
        val empty = synchronized(owners) { owners.remove(owner); owners.isEmpty() }
        if (empty) h.post { closeCamera(); state = State.STOPPED }
    }

    fun owners(): List<String> = synchronized(owners) { owners.toList() }

    /** Ждёт, пока камера не перейдёт в нужное состояние. Возвращает false по таймауту. */
    fun await(target: State, timeoutMs: Long): Boolean {
        val end = System.currentTimeMillis() + timeoutMs
        while (System.currentTimeMillis() < end) {
            if (state == target) return true
            if (state == State.ERROR && target == State.RUNNING) { /* ждём повторного открытия до таймаута */ }
            Thread.sleep(50)
        }
        return state == target
    }

    /** Ждёт нового обработанного кадра. */
    fun awaitProcessedFrame(timeoutMs: Long): Boolean {
        val n = framesProcessed
        val end = System.currentTimeMillis() + timeoutMs
        while (System.currentTimeMillis() < end) {
            if (framesProcessed > n) return true
            Thread.sleep(50)
        }
        return false
    }

    // ---------------- открытие ----------------

    private fun pickCamera(mgr: CameraManager): String? {
        var any: String? = null
        for (id in mgr.cameraIdList) {
            val ch = mgr.getCameraCharacteristics(id)
            if (any == null) any = id
            if (ch.get(CameraCharacteristics.LENS_FACING) == CameraCharacteristics.LENS_FACING_FRONT) return id
        }
        return any
    }

    private fun pickSize(ch: CameraCharacteristics, wish: String): android.util.Size {
        val sizes = ch.get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP)?.getOutputSizes(ImageFormat.YUV_420_888)
            ?: throw IllegalStateException("камера не отдаёт YUV_420_888")
        val parts = wish.split("x")
        val ww = parts.getOrNull(0)?.toIntOrNull() ?: 640
        val wh = parts.getOrNull(1)?.toIntOrNull() ?: 480
        return sizes.minByOrNull { abs(it.width - ww) + abs(it.height - wh) } ?: sizes[0]
    }

    private fun openIfNeeded() {
        if (synchronized(owners) { owners.isEmpty() }) return
        if (device != null || state == State.OPENING) return
        if (!hasPermission(app)) {
            fail("нет разрешения на камеру: на устройстве откройте окно настроек fServe и нажмите Allow camera", false)
            return
        }
        val gen = ++generation
        state = State.OPENING
        openedAt = System.currentTimeMillis()
        opens++
        h.removeCallbacks(watchdog)
        h.postDelayed(watchdog, 5000)
        try {
            val mgr = app.getSystemService(Context.CAMERA_SERVICE) as CameraManager
            val id = pickCamera(mgr) ?: run { fail("на устройстве нет камеры", false); return }
            val ch = mgr.getCameraCharacteristics(id)
            val size = pickSize(ch, Settings.camera().resolution)
            cameraId = id
            width = size.width
            height = size.height
            sensorOrientation = ch.get(CameraCharacteristics.SENSOR_ORIENTATION) ?: 0
            val r = ImageReader.newInstance(size.width, size.height, ImageFormat.YUV_420_888, 3)
            reader = r
            r.setOnImageAvailableListener({ rd -> onImage(rd, gen) }, h)
            FLog.i("camera", "открываю камеру $id, ${size.width}x${size.height}, ориентация сенсора $sensorOrientation")
            mgr.openCamera(id, object : CameraDevice.StateCallback() {
                override fun onOpened(d: CameraDevice) {
                    if (gen != generation) { d.close(); return }
                    device = d
                    startSession(d, ch, gen)
                }

                override fun onDisconnected(d: CameraDevice) {
                    FLog.w("camera", "камера отключена системой")
                    d.close()
                    if (gen == generation) { device = null; fail("камера отключена (её мог занять другой процесс)", true) }
                }

                override fun onError(d: CameraDevice, error: Int) {
                    FLog.w("camera", "ошибка камеры $error")
                    d.close()
                    if (gen == generation) { device = null; fail("ошибка камеры, код $error", true) }
                }
            }, h)
        } catch (e: CameraAccessException) {
            fail("доступ к камере: ${e.message}", true)
        } catch (e: SecurityException) {
            fail("нет разрешения на камеру: ${e.message}", false)
        } catch (e: Exception) {
            fail("${e.javaClass.simpleName}: ${e.message}", true)
        }
    }

    private fun startSession(d: CameraDevice, ch: CameraCharacteristics, gen: Int) {
        try {
            val surface = reader!!.surface
            val exec = Executor { r -> h.post(r) }
            val cb =
                object : CameraCaptureSession.StateCallback() {
                    override fun onConfigured(s: CameraCaptureSession) {
                        if (gen != generation) { s.close(); return }
                        session = s
                        try {
                            val req = d.createCaptureRequest(CameraDevice.TEMPLATE_PREVIEW)
                            req.addTarget(surface)
                            pickFps(ch)?.let { req.set(CaptureRequest.CONTROL_AE_TARGET_FPS_RANGE, it) }
                            s.setRepeatingRequest(req.build(), null, h)
                            FLog.i("camera", "поток кадров запущен")
                        } catch (e: Exception) {
                            fail("запуск потока: ${e.javaClass.simpleName}: ${e.message}", true)
                        }
                    }

                    override fun onConfigureFailed(s: CameraCaptureSession) {
                        if (gen == generation) fail("не удалось настроить поток камеры", true)
                    }
                }
            if (Build.VERSION.SDK_INT >= 28) {
                d.createCaptureSession(
                    SessionConfiguration(SessionConfiguration.SESSION_REGULAR, listOf(OutputConfiguration(surface)), exec, cb)
                )
            } else {
                @Suppress("DEPRECATION")
                d.createCaptureSession(listOf(surface), cb, h)
            }
        } catch (e: Exception) {
            fail("сессия камеры: ${e.javaClass.simpleName}: ${e.message}", true)
        }
    }

    /** Самый экономный диапазон частоты кадров около 10 к/с: меньше нагрев и дольше выдержка в темноте. */
    private fun pickFps(ch: CameraCharacteristics): android.util.Range<Int>? {
        val ranges = ch.get(CameraCharacteristics.CONTROL_AE_AVAILABLE_TARGET_FPS_RANGES) ?: return null
        return ranges.filter { it.upper >= 5 }.minWithOrNull(compareBy<android.util.Range<Int>>({ abs(it.upper - 10) }, { it.lower }))
    }

    private val watchdog = object : Runnable {
        override fun run() {
            if (synchronized(owners) { owners.isEmpty() }) return
            val now = System.currentTimeMillis()
            when {
                state == State.OPENING && now - openedAt > 15_000 -> {
                    FLog.w("camera", "камера не открылась за 15 с, перезапуск")
                    restart("не открылась за 15 с")
                }
                state == State.RUNNING && now - lastFrameAt > 10_000 -> {
                    FLog.w("camera", "нет кадров 10 с, перезапуск")
                    restart("нет кадров 10 с")
                }
            }
            h.postDelayed(this, 5000)
        }
    }

    private fun restart(reason: String) {
        restarts++
        closeCamera()
        fail(reason, true)
    }

    private fun fail(msg: String, retry: Boolean) {
        lastError = msg
        state = State.ERROR
        FLog.w("camera", msg)
        closeCamera()
        if (retry && synchronized(owners) { owners.isNotEmpty() }) {
            val d = retryDelay
            retryDelay = minOf(retryDelay * 2, 30_000L)
            FLog.i("camera", "повторное открытие через ${d / 1000} с")
            h.postDelayed({ if (state == State.ERROR) { state = State.STOPPED; openIfNeeded() } }, d)
        }
    }

    private fun closeCamera() {
        generation++ // отменяет ожидающие обратные вызовы прежней попытки
        h.removeCallbacks(watchdog)
        try { session?.close() } catch (e: Exception) {}
        try { device?.close() } catch (e: Exception) {}
        try { reader?.close() } catch (e: Exception) {}
        session = null
        device = null
        reader = null
        prev = null
        fpsWindowStart = 0
        fpsWindowFrames = 0
        if (state == State.RUNNING || state == State.OPENING) state = State.STOPPED
    }

    // ---------------- кадры ----------------

    private fun onImage(rd: ImageReader, gen: Int) {
        val got: Image? = try { rd.acquireLatestImage() } catch (e: Exception) { null }
        val img: Image = got ?: return
        try {
            if (gen != generation) return
            val now = System.currentTimeMillis()
            if (state != State.RUNNING) {
                state = State.RUNNING
                retryDelay = 2000L
                lastError = ""
                firstFrameLatencyMs = now - openedAt
                FLog.i("camera", "первый кадр через $firstFrameLatencyMs мс")
            }
            framesTotal++
            lastFrameAt = now
            if (fpsWindowStart == 0L) { fpsWindowStart = now; fpsWindowFrames = 0 }
            fpsWindowFrames++
            if (now - fpsWindowStart >= 5000) {
                fps = fpsWindowFrames * 1000.0 / (now - fpsWindowStart)
                fpsWindowStart = now
                fpsWindowFrames = 0
            }
            val interval = Settings.camera().intervalMs
            if (now - lastProcessedAt < interval) return
            lastProcessedAt = now
            val t0 = System.nanoTime()
            analyze(img, now)
            val ms = (System.nanoTime() - t0) / 1e6
            avgProcessMs = if (avgProcessMs == 0.0) ms else avgProcessMs * 0.9 + ms * 0.1
            framesProcessed++
        } catch (e: Throwable) {
            FLog.e("camera", "обработка кадра", e)
        } finally {
            img.close()
        }
    }

    private fun analyze(img: Image, now: Long) {
        val w = img.width
        val hh = img.height
        val p = img.planes[0]
        val buf = p.buffer
        val rs = p.rowStride
        val ps = p.pixelStride
        val lim = buf.limit()
        val cells = FloatArray(GW * GH)
        var total = 0.0
        for (gy in 0 until GH) {
            val y0 = gy * hh / GH
            val y1 = maxOf((gy + 1) * hh / GH, y0 + 1)
            for (gx in 0 until GW) {
                val x0 = gx * w / GW
                val x1 = maxOf((gx + 1) * w / GW, x0 + 1)
                var sum = 0L
                var n = 0
                var y = y0
                while (y < y1) {
                    var x = x0
                    while (x < x1) {
                        val idx = y * rs + x * ps
                        if (idx < lim) { sum += buf.get(idx).toInt() and 0xFF; n++ }
                        x += 2
                    }
                    y += 2
                }
                val v = if (n > 0) sum.toFloat() / n else 0f
                cells[gy * GW + gx] = v
                total += v
            }
        }
        val mean = (total / (GW * GH)).toFloat()
        for (i in cells.indices) cells[i] -= mean
        lastLuma = mean.toDouble()

        val cfg = Settings.camera()
        val pv = prev
        var score = 0.0
        if (pv != null) {
            var cnt = 0
            val thr = cfg.cellThreshold.toFloat()
            for (i in cells.indices) {
                val c = abs(cells[i] - pv[i]) > thr
                changed[i] = c
                if (c) cnt++
            }
            score = cnt * 100.0 / cells.size
        } else {
            java.util.Arrays.fill(changed, false)
        }
        prev = cells
        lastScore = score
        val mv = pv != null && score >= cfg.areaPercent
        motion = mv
        if (mv) lastMotionAt = now
        synchronized(histLock) {
            history.add(doubleArrayOf(now.toDouble(), score, mean.toDouble(), if (mv) 1.0 else 0.0))
            while (history.size > HISTORY) history.removeAt(0)
        }
        saveSnapshot(img)
    }

    /** Копия кадра в формате NV21 для просмотра из веб-интерфейса. */
    private fun saveSnapshot(img: Image) {
        val w = img.width
        val hh = img.height
        val out = ByteArray(w * hh * 3 / 2)
        val yp = img.planes[0]
        val yb = yp.buffer
        for (row in 0 until hh) {
            val base = row * yp.rowStride
            for (col in 0 until w) {
                val idx = base + col * yp.pixelStride
                out[row * w + col] = if (idx < yb.limit()) yb.get(idx) else 0
            }
        }
        val up = img.planes[1]
        val vp = img.planes[2]
        var o = w * hh
        for (row in 0 until hh / 2) {
            for (col in 0 until w / 2) {
                val ui = row * up.rowStride + col * up.pixelStride
                val vi = row * vp.rowStride + col * vp.pixelStride
                out[o++] = if (vi < vp.buffer.limit()) vp.buffer.get(vi) else 128.toByte()
                out[o++] = if (ui < up.buffer.limit()) up.buffer.get(ui) else 128.toByte()
            }
        }
        synchronized(snapLock) {
            snapNv21 = out
            snapW = w
            snapH = hh
            snapChanged = changed.copyOf()
        }
    }

    /** JPEG последнего кадра. С [overlay] изменившиеся ячейки закрашены красным. Поворот по настройке или по сенсору. */
    fun snapshotJpeg(overlay: Boolean, maxSide: Int = 320): ByteArray? {
        val nv: ByteArray
        val w: Int
        val hh: Int
        val ch: BooleanArray?
        synchronized(snapLock) {
            nv = snapNv21 ?: return null
            w = snapW
            hh = snapH
            ch = snapChanged
        }
        val bos = ByteArrayOutputStream()
        YuvImage(nv, ImageFormat.NV21, w, hh, null).compressToJpeg(Rect(0, 0, w, hh), 80, bos)
        var bmp = BitmapFactory.decodeByteArray(bos.toByteArray(), 0, bos.size()) ?: return null
        if (maxSide in 64 until maxOf(w, hh)) {
            val k = maxSide.toFloat() / maxOf(w, hh)
            val sm = Bitmap.createScaledBitmap(bmp, (w * k).toInt(), (hh * k).toInt(), true)
            bmp.recycle()
            bmp = sm
        }
        if (overlay && ch != null) {
            val m = bmp.copy(Bitmap.Config.ARGB_8888, true)
            val c = Canvas(m)
            val bw = m.width
            val bh = m.height
            val paint = Paint().apply { color = Color.argb(110, 255, 0, 60) }
            for (gy in 0 until GH) for (gx in 0 until GW) {
                if (ch[gy * GW + gx]) {
                    c.drawRect(
                        (gx * bw / GW).toFloat(), (gy * bh / GH).toFloat(),
                        ((gx + 1) * bw / GW).toFloat(), ((gy + 1) * bh / GH).toFloat(), paint
                    )
                }
            }
            bmp.recycle()
            bmp = m
        }
        val rot = Settings.camera().rotation.let { if (it < 0) sensorOrientation else it }
        if (rot != 0) {
            val mx = Matrix().apply { postRotate(rot.toFloat()) }
            val r = Bitmap.createBitmap(bmp, 0, 0, bmp.width, bmp.height, mx, true)
            bmp.recycle()
            bmp = r
        }
        // Итог всегда ровно 320x240 (или 240x320 после поворота): кадр вписывается с чёрными полями
        val bw = if (bmp.width >= bmp.height) 320 else 240
        val bh = if (bmp.width >= bmp.height) 240 else 320
        val box = Bitmap.createBitmap(bw, bh, Bitmap.Config.ARGB_8888)
        val cv = Canvas(box)
        cv.drawColor(Color.BLACK)
        val k = minOf(bw.toFloat() / bmp.width, bh.toFloat() / bmp.height)
        val dw = bmp.width * k
        val dh = bmp.height * k
        val dst = android.graphics.RectF((bw - dw) / 2, (bh - dh) / 2, (bw + dw) / 2, (bh + dh) / 2)
        cv.drawBitmap(bmp, null, dst, Paint(Paint.FILTER_BITMAP_FLAG))
        bmp.recycle()
        val o = ByteArrayOutputStream()
        box.compress(Bitmap.CompressFormat.JPEG, 80, o)
        box.recycle()
        return o.toByteArray()
    }

    /** Размеры кадра YUV, которые отдаёт камера (для выбора в веб-интерфейсе). */
    fun availableSizes(): List<String> = try {
        val mgr = app.getSystemService(Context.CAMERA_SERVICE) as CameraManager
        val id = pickCamera(mgr)
        if (id == null) emptyList() else {
            val sizes = mgr.getCameraCharacteristics(id).get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP)
                ?.getOutputSizes(ImageFormat.YUV_420_888) ?: emptyArray()
            sizes.filter { it.width * it.height in 19200..2_100_000 }
                .sortedBy { it.width * it.height }.map { "${it.width}x${it.height}" }.distinct()
        }
    } catch (e: Exception) {
        emptyList()
    }

    fun historyJson(limit: Int): JSONArray {
        val a = JSONArray()
        synchronized(histLock) {
            val from = maxOf(0, history.size - limit)
            for (i in from until history.size) {
                val s = history[i]
                a.put(obj("time" to Util.iso(s[0].toLong()), "score" to Math.round(s[1] * 100) / 100.0, "luma" to Math.round(s[2]), "motion" to (s[3] > 0)))
            }
        }
        return a
    }

    fun status(): JSONObject {
        val cfg = Settings.camera()
        return obj(
            "permission" to hasPermission(app),
            "hasCamera" to hasCamera(),
            "state" to state.name,
            "owners" to arr(owners()),
            "lastError" to lastError,
            "cameraId" to cameraId,
            "size" to if (width > 0) "${width}x$height" else "",
            "sensorOrientation" to sensorOrientation,
            "framesTotal" to framesTotal,
            "framesProcessed" to framesProcessed,
            "fps" to Math.round(fps * 10) / 10.0,
            "avgProcessMs" to Math.round(avgProcessMs * 10) / 10.0,
            "opens" to opens,
            "restarts" to restarts,
            "firstFrameLatencyMs" to firstFrameLatencyMs,
            "lastFrameAgoMs" to if (lastFrameAt > 0) System.currentTimeMillis() - lastFrameAt else null,
            "score" to Math.round(lastScore * 100) / 100.0,
            "luma" to Math.round(lastLuma),
            "motion" to motion,
            "lastMotion" to if (lastMotionAt > 0) Util.iso(lastMotionAt) else null,
            "config" to Settings.publicJson().optJSONObject("camera"),
            "resolutions" to arr(availableSizes()),
            "interval" to cfg.intervalMs
        )
    }
}
