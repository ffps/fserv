package com.ffps.fserv

import android.app.Activity
import android.app.AlertDialog
import android.content.Intent
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

/**
 * Киоск-экран: полноэкранный WebView. Страницу отдаёт встроенный сервер (http://127.0.0.1:порт/kiosk),
 * поэтому её можно заменить из веб-интерфейса. Пока сервер не поднялся, показывается страница из ресурсов.
 *
 * Скрытый вход в параметры: долгое нажатие в левом верхнем углу экрана (адрес, логин и пароль веб-интерфейса).
 */
class MainActivity : Activity() {

    private lateinit var web: WebView
    private val handler = Handler(Looper.getMainLooper())
    private val retry = Runnable { loadKiosk() }
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
                        view?.loadUrl(ASSET_URL)
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
                    showInfo()
                    true
                } else {
                    false
                }
            }
        }
        setContentView(web)
        startService()
        // Даём службе время поднять сервер; если не успеет, сработает запасная страница и повтор.
        web.loadUrl(ASSET_URL)
        handler.postDelayed(retry, 1500)
    }

    override fun onStart() {
        super.onStart()
        startService()
    }

    private fun startService() {
        try {
            startForegroundService(Intent(this, ServerService::class.java))
        } catch (e: Exception) {
            FLog.e("app", "не удалось запустить службу", e)
        }
    }

    private fun loadKiosk() {
        handler.removeCallbacks(retry)
        web.loadUrl("http://127.0.0.1:${Settings.webPort}/kiosk")
    }

    private fun showInfo() {
        val ips = Util.ips()
        val addr = if (ips.isEmpty()) "(нет сети)" else ips.joinToString("\n") { "http://$it:${Settings.webPort}" }
        val msg = "Адрес:\n$addr\n\nЛогин: admin\nПароль: ${Settings.password}\n\nВерсия ${BuildConfig.VERSION_NAME}"
        AlertDialog.Builder(this)
            .setTitle("fServe")
            .setMessage(msg)
            .setPositiveButton("Закрыть", null)
            .setNeutralButton("Новый пароль") { _, _ ->
                val p = Settings.regeneratePassword()
                FLog.i("app", "пароль изменён с экрана планшета")
                Journal.add("settings", "Пароль веб-интерфейса изменён с экрана планшета")
                AlertDialog.Builder(this).setTitle("Новый пароль").setMessage(p).setPositiveButton("Закрыть", null).show()
            }
            .show()
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

    @Deprecated("Back is ignored in kiosk mode")
    override fun onBackPressed() {
        // Кнопка «назад» в киоске ничего не делает.
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

        /** Перечитать киоск-страницу (после замены из веб-интерфейса). */
        fun reloadKiosk() {
            current?.let { a -> a.runOnUiThread { a.loadKiosk() } }
        }
    }
}
