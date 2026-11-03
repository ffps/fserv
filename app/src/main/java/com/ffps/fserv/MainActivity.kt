package com.ffps.fserv

import android.app.Activity
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Color
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.MotionEvent
import android.view.ViewGroup
import android.widget.FrameLayout
import android.webkit.WebResourceResponse
import android.view.View
import android.view.WindowInsets
import android.view.WindowInsetsController
import android.view.WindowManager
import android.webkit.PermissionRequest
import android.webkit.WebChromeClient
import android.webkit.WebResourceError
import android.webkit.WebResourceRequest
import android.webkit.WebView
import android.webkit.WebViewClient

/** Состояние экрана киоска для кнопки «Перезагрузить киоск». */
enum class KioskState { VISIBLE, BACKGROUND, NONE }

/**
 * Киоск-экран: полноэкранный WebView. Страницу отдаёт встроенный сервер (http://127.0.0.1:порт/kiosk),
 * поэтому её можно заменить из веб-интерфейса. Пока служба не запущена, показывается страница из ресурсов.
 *
 * Скрытый вход в настройки: долгое нажатие в левом верхнем углу экрана.
 * Кнопка «назад» блокируется только если приложение выбрано домашним экраном.
 */
class MainActivity : Activity() {

    private lateinit var web: WebView
    private lateinit var root: FrameLayout
    private var slideWeb: WebView? = null
    private var callWeb: WebView? = null
    private var slideSpec: Slide.Spec? = null
    private var savedBrightness = WindowManager.LayoutParams.BRIGHTNESS_OVERRIDE_NONE
    private val handler = Handler(Looper.getMainLooper())
    private val retry = Runnable { loadKiosk(false) }
    private var onAsset = false
    private var downX = 0f
    private var downY = 0f

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        current = this
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)

        web = WebView(this).apply {
            setBackgroundColor(Color.parseColor("#003366"))
            settings.javaScriptEnabled = true
            isVerticalScrollBarEnabled = false
            isHorizontalScrollBarEnabled = false
            overScrollMode = View.OVER_SCROLL_NEVER
            webViewClient = object : WebViewClient() {
                override fun onReceivedError(view: WebView?, request: WebResourceRequest?, error: WebResourceError?) {
                    if (request?.isForMainFrame == true) {
                        FLog.w("kiosk", "страница с сервера не загрузилась (${error?.description}), показываю встроенную")
                        showAsset()
                        handler.removeCallbacks(retry)
                        handler.postDelayed(retry, 15_000)
                    }
                }
            }
        }
        gestures(web)
        root = FrameLayout(this).apply { addView(web, ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT)) }
        setContentView(root)

        // Пока пароль не задан, сразу открываем окно настроек.
        if (!Settings.isConfigured) startActivity(Intent(this, SettingsActivity::class.java))

        showAsset()
        loadKiosk(false)
    }

    /** Скрытый вход в настройки (долгое нажатие в левом верхнем углу) и, если [onTap] задан, короткое касание. */
    private fun gestures(w: WebView, onTap: (() -> Unit)? = null) {
        var downAt = 0L
        w.setOnTouchListener { _, ev ->
            if (ev.actionMasked == MotionEvent.ACTION_DOWN) {
                downX = ev.x
                downY = ev.y
                downAt = System.currentTimeMillis()
            } else if (ev.actionMasked == MotionEvent.ACTION_UP && onTap != null && System.currentTimeMillis() - downAt < 400) {
                onTap()
            }
            false
        }
        w.setOnLongClickListener {
            if (downX < w.width * 0.25f && downY < w.height * 0.2f) {
                startActivity(Intent(this@MainActivity, SettingsActivity::class.java))
                true
            } else {
                false
            }
        }
    }

    // ---------------- слайд поверх киоска (шаг slide) ----------------

    private fun showSlideUi(spec: Slide.Spec) {
        var w = slideWeb
        if (w == null) {
            savedBrightness = window.attributes.screenBrightness
            w = WebView(this).apply {
                setBackgroundColor(Color.BLACK)
                settings.javaScriptEnabled = true
                settings.allowFileAccess = false
                isVerticalScrollBarEnabled = false
                isHorizontalScrollBarEnabled = false
                overScrollMode = View.OVER_SCROLL_NEVER
                webViewClient = object : WebViewClient() {
                    // всё, что нужно слайду, отдаётся из приложения: страница из ресурсов и файлы из slides/ (сети нет)
                    override fun shouldInterceptRequest(view: WebView?, request: WebResourceRequest?): WebResourceResponse? {
                        val u = request?.url ?: return null
                        val p = u.path ?: ""
                        try {
                            if (u.host == "slide.local" && p == "/slide.html") return WebResourceResponse("text/html", "utf-8", assets.open("slide.html"))
                            if (u.host == "slide.local" && p.startsWith("/f/")) {
                                val name = p.removePrefix("/f/")
                                if (Slides.exists(name)) return WebResourceResponse(Slides.mime(name), null, Slides.file(name).inputStream())
                            }
                        } catch (e: Exception) {
                            FLog.w("slide", "не отдан ${u}: ${e.message}")
                        }
                        return WebResourceResponse("text/plain", "utf-8", 404, "Not Found", emptyMap(), java.io.ByteArrayInputStream(ByteArray(0)))
                    }

                    override fun onPageFinished(view: WebView?, url: String?) {
                        val sp = slideSpec ?: return
                        if (sp.fadeInMs > 0) view?.animate()?.alpha(1f)?.setDuration(sp.fadeInMs)?.start() else view?.alpha = 1f
                    }
                }
            }
            slideWeb = w
            root.addView(w, ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
        }
        slideSpec = spec
        gestures(w, if (spec.tapHide) ({ Slide.off() }) else null)
        w.animate().cancel()
        w.alpha = 0f
        val lp = window.attributes
        lp.screenBrightness = spec.brightness?.let { (it / 100f).coerceIn(0.01f, 1f) } ?: savedBrightness
        window.attributes = lp
        w.loadUrl("https://slide.local/slide.html?f=" + java.net.URLEncoder.encode(spec.file, "UTF-8") + "&fit=" + spec.fit + "&bg=" + java.net.URLEncoder.encode(spec.bg, "UTF-8"))
    }

    private fun hideSlideUi() {
        val w = slideWeb ?: return
        val out = slideSpec?.fadeOutMs ?: 0L
        slideWeb = null
        // яркость возвращается сразу: затухание картинки её не касается
        val lp = window.attributes
        lp.screenBrightness = savedBrightness
        window.attributes = lp
        val finish = Runnable { try { root.removeView(w); w.destroy() } catch (e: Exception) {} }
        w.animate().cancel()
        if (out > 0) w.animate().alpha(0f).setDuration(out).withEndAction(finish).start() else finish.run()
    }

    // ---------------- видеозвонок (см. Call) ----------------

    /** Окно звонка поверх всего: страница call.html с WebRTC. Она отдаётся из приложения по «адресу» https://call.local (защищённый контекст, сети не нужно). */
    private fun callStartUi(offer: String, res: String) {
        callStopUi()
        val w = WebView(this).apply {
            setBackgroundColor(Color.BLACK)
            settings.javaScriptEnabled = true
            settings.mediaPlaybackRequiresUserGesture = false
            settings.allowFileAccess = false
            isVerticalScrollBarEnabled = false
            isHorizontalScrollBarEnabled = false
            overScrollMode = View.OVER_SCROLL_NEVER
            keepScreenOn = true
            addJavascriptInterface(CallBridge(), "Android")
            webChromeClient = object : WebChromeClient() {
                // камеру и микрофон получает только страница звонка из самого приложения
                override fun onPermissionRequest(request: PermissionRequest?) {
                    val r = request ?: return
                    if (r.origin.host == "call.local") r.grant(r.resources) else r.deny()
                }
            }
            webViewClient = object : WebViewClient() {
                override fun shouldInterceptRequest(view: WebView?, request: WebResourceRequest?): WebResourceResponse? {
                    val u = request?.url ?: return null
                    return if (u.host == "call.local" && u.path == "/call.html") {
                        WebResourceResponse("text/html", "utf-8", assets.open("call.html"))
                    } else {
                        WebResourceResponse("text/plain", "utf-8", 404, "Not Found", emptyMap(), java.io.ByteArrayInputStream(ByteArray(0)))
                    }
                }

                override fun onPageFinished(view: WebView?, url: String?) {
                    if (url?.startsWith("https://call.local") == true) {
                        view?.evaluateJavascript("callAccept(" + org.json.JSONObject.quote(offer) + "," + org.json.JSONObject.quote(res) + ")", null)
                    }
                }
            }
        }
        callWeb = w
        root.addView(w, ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
        w.loadUrl("https://call.local/call.html")
    }

    /** Закрывает окно звонка (камера и микрофон освобождаются, под ним снова киоск). */
    private fun callStopUi() {
        val w = callWeb ?: return
        callWeb = null
        try { w.loadUrl("about:blank"); root.removeView(w); w.destroy() } catch (e: Exception) {}
    }

    override fun onResume() {
        super.onResume()
        resumed = true
    }

    override fun onPause() {
        resumed = false
        super.onPause()
    }

    override fun onStart() {
        super.onStart()
        if (Settings.serviceEnabled && Settings.isConfigured) ServerService.start(this)
    }

    private fun showAsset() {
        if (!onAsset) {
            onAsset = true
            web.loadUrl(ASSET_URL)
        }
    }

    /** Показывает страницу с сервера, если служба работает; иначе остаётся встроенная и проверяет снова. */
    private fun loadKiosk(force: Boolean) {
        handler.removeCallbacks(retry)
        if (force) {
            // видимая реакция: экран гаснет и плавно возвращается, пока страница перечитывается
            web.animate().cancel()
            web.alpha = 0f
            web.animate().alpha(1f).setStartDelay(500).setDuration(500).start()
        }
        if (ServerService.state == ServerService.State.RUNNING) {
            if (onAsset || force) {
                onAsset = false
                web.loadUrl("http://127.0.0.1:${Settings.webPort}/kiosk")
            }
        } else {
            if (force) {
                onAsset = false
                showAsset()
            }
            handler.postDelayed(retry, 3_000)
        }
    }

    private fun isDefaultHome(): Boolean {
        val i = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME)
        val ri = packageManager.resolveActivity(i, PackageManager.MATCH_DEFAULT_ONLY)
        return ri?.activityInfo?.packageName == packageName
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        focused = hasFocus
        handler.removeCallbacks(collapseShade)
        if (hasFocus) hideSystemUi()
        else if (resumed) handler.postDelayed(collapseShade, 15_000) // шторку или окно над киоском через 15 с убираем
    }

    private val collapseShade = Runnable {
        if (resumed && !focused) {
            FLog.i("kiosk", "над киоском шторка или окно, закрываю")
            collapsePanels(this)
        }
    }

    private fun hideSystemUi() {
        if (Build.VERSION.SDK_INT >= 30) {
            window.insetsController?.let {
                it.hide(WindowInsets.Type.systemBars())
                it.systemBarsBehavior = WindowInsetsController.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
            }
        } else {
            @Suppress("DEPRECATION")
            window.decorView.systemUiVisibility = (View.SYSTEM_UI_FLAG_FULLSCREEN
                    or View.SYSTEM_UI_FLAG_HIDE_NAVIGATION
                    or View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY
                    or View.SYSTEM_UI_FLAG_LAYOUT_STABLE
                    or View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN
                    or View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION)
        }
    }

    @Deprecated("Back is ignored when the app is the home screen")
    override fun onBackPressed() {
        // Домашний экран «назад» не закрывает; если приложение запущено как обычное, работает как всегда.
        if (isDefaultHome()) return
        @Suppress("DEPRECATION")
        super.onBackPressed()
    }

    override fun onDestroy() {
        if (current === this) current = null
        handler.removeCallbacksAndMessages(null)
        slideWeb?.let { try { it.destroy() } catch (e: Exception) {} }
        slideWeb = null
        if (callWeb != null) { callStopUi(); Call.endExternal("киоск закрыт системой") }
        web.destroy()
        super.onDestroy()
    }

    companion object {
        private const val ASSET_URL = "file:///android_asset/kiosk.html"

        @Volatile
        private var current: MainActivity? = null

        @Volatile
        private var resumed = false

        @Volatile
        private var focused = false

        /** Закрывает шторку уведомлений. Работает без root, но способ зависит от версии Android. */
        fun collapsePanels(ctx: android.content.Context): Boolean {
            try {
                val sb = ctx.getSystemService("statusbar")
                if (sb != null) {
                    val m = try { sb.javaClass.getMethod("collapsePanels") } catch (e: NoSuchMethodException) { sb.javaClass.getMethod("collapse") }
                    m.invoke(sb)
                    return true
                }
            } catch (e: Throwable) {
                FLog.d("kiosk", "collapsePanels: ${e.javaClass.simpleName}: ${e.message}")
            }
            try {
                @Suppress("DEPRECATION")
                ctx.sendBroadcast(Intent(Intent.ACTION_CLOSE_SYSTEM_DIALOGS))
                return true
            } catch (e: Throwable) {
                FLog.d("kiosk", "CLOSE_SYSTEM_DIALOGS: ${e.message}")
            }
            return false
        }

        /**
         * Перечитать киоск-страницу. Результат: VISIBLE — экран киоска на виду; BACKGROUND — экран создан,
         * но закрыт другим приложением (страница всё равно перечитана); NONE — экрана нет (система его закрыла
         * или он не открывался), перечитывать нечего.
         */
        /** Киоск сейчас на экране. */
        fun isVisible(): Boolean = current != null && resumed && focused

        /**
         * Выводит киоск на передний план из фона. Android 10+ разрешает это только приложениям с правом
         * «Поверх других окон» (Allow overlay в окне настроек); на Android 8–9 запрета нет. Возвращает текст ошибки или null.
         */
        fun show(ctx: android.content.Context): String? {
            if (!Settings.isConfigured) return "пароль не задан"
            if (current != null && resumed && !focused) collapsePanels(ctx) // киоск открыт, но закрыт шторкой
            return try {
                ctx.startActivity(
                    Intent(ctx, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_REORDER_TO_FRONT)
                )
                null
            } catch (e: Exception) {
                FLog.w("kiosk", "не удалось показать киоск: ${e.message}")
                e.message ?: e.javaClass.simpleName
            }
        }

        /** Показать слайд поверх киоска (из любого потока). false — киоск не на переднем плане, слайд не показан. */
        fun showSlide(spec: Slide.Spec): Boolean {
            val a = current ?: return false
            if (!(resumed && focused)) return false
            a.runOnUiThread { a.showSlideUi(spec) }
            return true
        }

        /** Показать окно звонка с предложением [offer] администратора (из любого потока). false — киоск не на переднем плане. */
        fun callStart(offer: String, res: String): Boolean {
            val a = current ?: return false
            if (!(resumed && focused)) return false
            a.runOnUiThread { a.callStartUi(offer, res) }
            return true
        }

        fun callStop() { current?.let { a -> a.runOnUiThread { a.callStopUi() } } }

        fun hideSlide() { current?.let { a -> a.runOnUiThread { a.hideSlideUi() } } }

        fun reloadKiosk(): KioskState {
            val a = current ?: return KioskState.NONE
            a.runOnUiThread { a.loadKiosk(true) }
            return if (resumed && focused) KioskState.VISIBLE else KioskState.BACKGROUND
        }
    }
}
