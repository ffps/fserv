package com.ffps.fserv

import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.BatteryManager
import org.json.JSONObject
import java.net.Inet4Address
import java.net.NetworkInterface
import java.text.SimpleDateFormat
import java.util.Collections
import java.util.Date
import java.util.Locale

object Util {
    fun iso(ms: Long = System.currentTimeMillis()): String =
        SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US).format(Date(ms))

    /** IPv4-адреса устройства в локальной сети. */
    fun ips(): List<String> {
        val res = ArrayList<String>()
        try {
            val all = NetworkInterface.getNetworkInterfaces() ?: return res
            for (ni in Collections.list(all)) {
                if (!ni.isUp || ni.isLoopback) continue
                for (a in Collections.list(ni.inetAddresses)) {
                    if (a is Inet4Address && !a.isLoopbackAddress && !a.isLinkLocalAddress) res.add(a.hostAddress ?: continue)
                }
            }
        } catch (e: Exception) {
            FLog.w("util", "ips failed", e)
        }
        return res
    }

    /** Самый «домашний» адрес: сначала 192.168.*, потом 10.*, 172.16-31.*, потом любой. */
    fun bestIp(): String? {
        val all = ips()
        fun rank(ip: String): Int = when {
            ip.startsWith("192.168.") -> 0
            ip.startsWith("10.") -> 1
            Regex("^172\\.(1[6-9]|2[0-9]|3[01])\\..*").matches(ip) -> 2
            else -> 3
        }
        return all.minByOrNull { rank(it) }
    }

    /** Адрес веб-интерфейса, например http://192.168.1.10:8080. */
    fun address(): String = "http://${bestIp() ?: "localhost"}:${Settings.webPort}"

    fun battery(ctx: Context): JSONObject {
        val i: Intent? = ctx.registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED))
        val level = i?.getIntExtra(BatteryManager.EXTRA_LEVEL, -1) ?: -1
        val scale = i?.getIntExtra(BatteryManager.EXTRA_SCALE, -1) ?: -1
        val status = i?.getIntExtra(BatteryManager.EXTRA_STATUS, -1) ?: -1
        val temp = i?.getIntExtra(BatteryManager.EXTRA_TEMPERATURE, Int.MIN_VALUE) ?: Int.MIN_VALUE
        val charging = status == BatteryManager.BATTERY_STATUS_CHARGING || status == BatteryManager.BATTERY_STATUS_FULL
        return obj(
            "percent" to (if (level >= 0 && scale > 0) level * 100 / scale else null),
            "charging" to charging,
            "temperatureC" to (if (temp != Int.MIN_VALUE) temp / 10.0 else null)
        )
    }
}
