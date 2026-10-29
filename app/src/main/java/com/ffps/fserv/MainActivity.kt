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
import android.view.View
import android.view.WindowInsets
import android.view.WindowInsetsController
import android.view.WindowManager
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
            setOnTouchListener { _, ev ->
                if (ev.actionMasked == MotionEvent.ACTION_DOWN) {
                    downX = ev.x
                    downY = ev.y
                }
                false
            }
            setOnLongClickListener {
                if (downX < width * 0.25f && downY < height * 0.2f) {
                    startActivity(Intent(this@MainActivity, SettingsActivity::class.java))
                    true
                } else {
                    false
                }
            }
        }
        setContentView(web)

        // Пока пароль не задан, сразу открываем окно настроек.
        if (!Settings.isConfigured) startActivity(Intent(this, SettingsActivity::class.java))

        showAsset()
        loadKiosk(false)
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
        if (hasFocus) hideSystemUi()
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
        web.destroy()
        super.onDestroy()
    }

    companion object {
        private const val ASSET_URL = "file:///android_asset/kiosk.html"

        @Volatile
        private var current: MainActivity? = null

        @Volatile
        private var resumed = false

        /**
         * Перечитать киоск-страницу. Результат: VISIBLE — экран киоска на виду; BACKGROUND — экран создан,
         * но закрыт другим приложением (страница всё равно перечитана); NONE — экрана нет (система его закрыла
         * или он не открывался), перечитывать нечего.
         */
        /** Киоск сейчас на экране. */
        fun isVisible(): Boolean = current != null && resumed

        /**
         * Выводит киоск на передний план из фона. Android 10+ разрешает это только приложениям с правом
         * «Поверх других окон» (Allow overlay в окне настроек); на Android 8–9 запрета нет. Возвращает текст ошибки или null.
         */
        fun show(ctx: android.content.Context): String? {
            if (!Settings.isConfigured) return "пароль не задан"
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

        fun reloadKiosk(): KioskState {
            val a = current ?: return KioskState.NONE
            a.runOnUiThread { a.loadKiosk(true) }
            return if (resumed) KioskState.VISIBLE else KioskState.BACKGROUND
        }
    }
}
