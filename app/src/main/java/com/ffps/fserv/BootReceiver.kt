package com.ffps.fserv

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent

/** Поднимает службу после включения планшета (если стоит галочка) и после обновления приложения. */
class BootReceiver : BroadcastReceiver() {
    override fun onReceive(ctx: Context, intent: Intent) {
        when (intent.action) {
            Intent.ACTION_BOOT_COMPLETED -> {
                if (Settings.autostart && Settings.isConfigured) {
                    Settings.setServiceEnabled(true)
                    FLog.i("boot", "автозапуск после включения")
                    Journal.add("service", "Служба запущена автоматически после включения планшета")
                    ServerService.start(ctx)
                }
            }
            Intent.ACTION_MY_PACKAGE_REPLACED -> {
                if (Settings.serviceEnabled && Settings.isConfigured) {
                    FLog.i("boot", "служба поднимается после обновления приложения")
                    ServerService.start(ctx)
                }
            }
        }
    }
}
