package com.ffps.fserv

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.net.wifi.WifiManager
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.PowerManager
import fi.iki.elonen.NanoHTTPD
import java.io.IOException

/**
 * Фоновая служба: веб-сервер, планировщик, блокировки CPU и Wi-Fi. Работает как foreground-служба
 * с уведомлением «Running: http://адрес:порт», по нажатию на него открываются настройки.
 */
class ServerService : Service() {
    enum class State { STOPPED, RUNNING, FAIL }

    private var server: WebServer? = null
    private var scheduler: Scheduler? = null
    private var wake: PowerManager.WakeLock? = null
    private var wifi: WifiManager.WifiLock? = null
    private val handler = Handler(Looper.getMainLooper())
    private val retry = Runnable { startAll() }
    private val ticker = object : Runnable {
        override fun run() {
            updateNotification()
            handler.postDelayed(this, 20_000)
        }
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        instance = this
        goForeground()
        handler.postDelayed(ticker, 20_000)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        startAll()
        return START_STICKY
    }

    private fun goForeground() {
        val nm = getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(NotificationChannel(CHANNEL, "fServe", NotificationManager.IMPORTANCE_LOW))
        val n = buildNotification()
        if (Build.VERSION.SDK_INT >= 34) {
            startForeground(NOTIF_ID, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE)
        } else {
            startForeground(NOTIF_ID, n)
        }
    }

    private fun buildNotification(): Notification {
        val open = PendingIntent.getActivity(
            this, 0, Intent(this, SettingsActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        return Notification.Builder(this, CHANNEL)
            .setSmallIcon(R.drawable.ic_launcher_foreground)
            .setContentTitle("fServe")
            .setContentText(statusLine())
            .setContentIntent(open)
            .setOngoing(true)
            .build()
    }

    private fun updateNotification() {
        try {
            getSystemService(NotificationManager::class.java).notify(NOTIF_ID, buildNotification())
        } catch (e: Exception) {
            FLog.w("service", "не удалось обновить уведомление", e)
        }
    }

    private fun fail(message: String) {
        state = State.FAIL
        error = message
        FLog.w("service", "Fail: $message")
        updateNotification()
    }

    @Synchronized
    private fun startAll() {
        handler.removeCallbacks(retry)
        if (!Settings.isConfigured) {
            fail("password is not set")
            return
        }
        if (server == null) {
            try {
                val s = WebServer(applicationContext, Settings.webPort)
                s.start(NanoHTTPD.SOCKET_READ_TIMEOUT, false)
                server = s
                serverStartedAt = System.currentTimeMillis()
                FLog.i("web", "веб-сервер запущен на порту ${Settings.webPort}")
            } catch (e: IOException) {
                fail(e.message ?: "cannot open port ${Settings.webPort}")
                handler.postDelayed(retry, 10_000)
            }
        }
        if (server != null) {
            state = State.RUNNING
            error = ""
        }
        if (scheduler == null) scheduler = Scheduler(applicationContext).also { it.start() }
        if (wake == null) {
            wake = (getSystemService(Context.POWER_SERVICE) as PowerManager)
                .newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "fserv:service").apply {
                    setReferenceCounted(false)
                    acquire()
                }
        }
        if (wifi == null) {
            wifi = (applicationContext.getSystemService(Context.WIFI_SERVICE) as WifiManager)
                .createWifiLock(WifiManager.WIFI_MODE_FULL_LOW_LATENCY, "fserv:wifi").apply {
                    setReferenceCounted(false)
                    acquire()
                }
        }
        updateNotification()
    }

    @Synchronized
    private fun stopServer() {
        handler.removeCallbacks(retry)
        try {
            server?.stop()
        } catch (e: Exception) {
            FLog.w("web", "ошибка при остановке сервера", e)
        }
        server = null
        scheduler?.stop()
        scheduler = null
    }

    /** Мягкий перезапуск: сервер и планировщик поднимаются заново (с новым портом), процесс остаётся. */
    fun softRestart() {
        Thread {
            restarts++
            FLog.i("service", "мягкий перезапуск №$restarts")
            stopServer()
            startAll()
            MainActivity.reloadKiosk()
        }.start()
    }

    override fun onDestroy() {
        handler.removeCallbacksAndMessages(null)
        stopServer()
        state = State.STOPPED
        error = ""
        instance = null
        wake?.let { if (it.isHeld) it.release() }
        wifi?.let { if (it.isHeld) it.release() }
        wake = null
        wifi = null
        FLog.i("service", "служба остановлена")
        super.onDestroy()
    }

    companion object {
        private const val CHANNEL = "fserv"
        private const val NOTIF_ID = 1

        @Volatile
        var state: State = State.STOPPED

        @Volatile
        var error: String = ""

        @Volatile
        var instance: ServerService? = null

        /** Когда последний раз поднимался веб-сервер, и сколько было мягких перезапусков с начала процесса. */
        @Volatile
        var serverStartedAt: Long = 0L

        @Volatile
        var restarts: Int = 0

        /** Одна строка статуса: Stopped / Running: http://... / Fail: ... */
        fun statusLine(): String = when (state) {
            State.RUNNING -> "Running: ${Util.address()}"
            State.FAIL -> "Fail: $error"
            State.STOPPED -> "Stopped"
        }

        fun start(ctx: Context) {
            try {
                ctx.startForegroundService(Intent(ctx, ServerService::class.java))
            } catch (e: Exception) {
                FLog.e("service", "не удалось запустить службу", e)
            }
        }

        fun stop(ctx: Context) {
            ctx.stopService(Intent(ctx, ServerService::class.java))
        }
    }
}
