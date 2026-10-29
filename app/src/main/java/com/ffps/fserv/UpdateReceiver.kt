package com.ffps.fserv

import android.app.Notification
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageInstaller

/** Результат установки от PackageInstaller. Если нужно подтверждение, открывает его и дублирует уведомлением. */
class UpdateReceiver : BroadcastReceiver() {
    @Suppress("DEPRECATION")
    override fun onReceive(ctx: Context, intent: Intent) {
        val status = intent.getIntExtra(PackageInstaller.EXTRA_STATUS, -1)
        val msg = intent.getStringExtra(PackageInstaller.EXTRA_STATUS_MESSAGE) ?: ""
        when (status) {
            PackageInstaller.STATUS_PENDING_USER_ACTION -> {
                val confirm = intent.getParcelableExtra<Intent>(Intent.EXTRA_INTENT) ?: return
                confirm.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                FLog.i("update", "системе нужно подтверждение установки")
                Updater.state = "confirm"
                try {
                    ctx.startActivity(confirm)
                } catch (e: Exception) {
                    FLog.w("update", "не удалось открыть подтверждение: ${e.message}")
                }
                try {
                    val pi = PendingIntent.getActivity(ctx, 77, confirm, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
                    val n = Notification.Builder(ctx, ServerService.ALERT_CHANNEL)
                        .setSmallIcon(R.drawable.ic_launcher_foreground)
                        .setContentTitle("fServe: подтвердите обновление")
                        .setContentText("Нажмите, чтобы установить новую версию").setCategory(Notification.CATEGORY_ALARM)
                        .setContentIntent(pi).setAutoCancel(true).build()
                    ctx.getSystemService(NotificationManager::class.java).notify(2, n)
                } catch (e: Exception) {
                    FLog.w("update", "уведомление не показано: ${e.message}")
                }
            }
            PackageInstaller.STATUS_SUCCESS -> {
                FLog.i("update", "установлено")
                Updater.state = "idle"
            }
            else -> {
                Updater.lastError = "установка не удалась (код $status): $msg"
                Updater.state = "idle"
                FLog.w("update", Updater.lastError)
                Journal.add("update", Updater.lastError)
            }
        }
    }
}
