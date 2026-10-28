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

/** Фоновая служба: веб-сервер, планировщик, блокировки CPU и Wi-Fi. Работает как foreground-служба. */
class ServerService : Service() {
    private var server: WebServer? = null
    private var scheduler: Scheduler? = null
    private var wake: PowerManager.WakeLock? = null
    private var wifi: WifiManager.WifiLock? = null
    private val handler = Handler(Looper.getMainLooper())

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        goForeground()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        startAll()
        return START_STICKY
    }

    private fun goForeground() {
        val nm = getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(NotificationChannel(CHANNEL, "fServe", NotificationManager.IMPORTANCE_LOW))
        val open = PendingIntent.getActivity(
            this, 0, Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        val n = Notification.Builder(this, CHANNEL)
            .setSmallIcon(R.drawable.ic_launcher_foreground)
            .setContentTitle("fServe")
            .setContentText("Работает, веб-интерфейс на порту ${Settings.webPort}")
            .setContentIntent(open)
            .setOngoing(true)
            .build()
        if (Build.VERSION.SDK_INT >= 34) {
            startForeground(NOTIF_ID, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE)
        } else {
            startForeground(NOTIF_ID, n)
        }
    }

    @Synchronized
    private fun startAll() {
        if (server == null) {
            try {
                val s = WebServer(applicationContext, Settings.webPort)
                s.start(NanoHTTPD.SOCKET_READ_TIMEOUT, false)
                server = s
                running = true
                FLog.i("web", "веб-сервер запущен на порту ${Settings.webPort}")
            } catch (e: IOException) {
                FLog.e("web", "не удалось запустить веб-сервер на порту ${Settings.webPort}, повтор через 10 с", e)
                handler.postDelayed({ startAll() }, 10_000)
            }
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
    }

    override fun onDestroy() {
        handler.removeCallbacksAndMessages(null)
        server?.stop()
        server = null
        running = false
        scheduler?.stop()
        scheduler = null
        wake?.let { if (it.isHeld) it.release() }
        wifi?.let { if (it.isHeld) it.release() }
        FLog.i("app", "служба остановлена")
        super.onDestroy()
    }

    companion object {
        private const val CHANNEL = "fserv"
        private const val NOTIF_ID = 1

        @Volatile
        var running = false
    }
}
