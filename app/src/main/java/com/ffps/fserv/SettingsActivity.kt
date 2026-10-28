package com.ffps.fserv

import android.app.Activity
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.text.InputType
import android.text.TextUtils
import android.text.method.HideReturnsTransformationMethod
import android.text.method.PasswordTransformationMethod
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.CheckBox
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast

/**
 * Окно настроек (по образцу fSSH): строка состояния, порт, пароль, автозапуск и кнопка START/STOP SERVICE.
 * Открывается иконкой приложения, нажатием на уведомление, скрытым жестом в киоске
 * и автоматически, пока пароль не задан.
 */
class SettingsActivity : Activity() {
    private lateinit var status: TextView
    private lateinit var port: EditText
    private lateinit var pass: EditText
    private lateinit var toggle: Button
    private val handler = Handler(Looper.getMainLooper())
    private val tick = object : Runnable {
        override fun run() {
            refresh()
            handler.postDelayed(this, 1000)
        }
    }

    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        title = "fServe"

        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(20), dp(16), dp(20), dp(24))
        }

        status = TextView(this).apply {
            textSize = 18f
            setTextColor(Color.GRAY)
            setSingleLine(true)
            ellipsize = TextUtils.TruncateAt.END
        }
        root.addView(status, full())

        root.addView(label("Port"), full(top = 20))
        port = EditText(this).apply {
            inputType = InputType.TYPE_CLASS_NUMBER
            setSingleLine(true)
            setText(Settings.webPort.toString())
        }
        root.addView(port, full())

        root.addView(label("Password"), full(top = 12))
        pass = EditText(this).apply {
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD
            setSingleLine(true)
            setText(Settings.password)
        }
        root.addView(pass, full())

        val show = CheckBox(this).apply {
            text = "Show password"
            setOnCheckedChangeListener { _, checked ->
                val pos = pass.selectionStart
                pass.transformationMethod =
                    if (checked) HideReturnsTransformationMethod.getInstance() else PasswordTransformationMethod.getInstance()
                if (pos >= 0) pass.setSelection(pos)
            }
        }
        root.addView(show, full(top = 4))

        val boot = CheckBox(this).apply {
            text = "Start on device boot"
            isChecked = Settings.autostart
            setOnCheckedChangeListener { _, checked -> Settings.setAutostart(checked) }
        }
        root.addView(boot, full(top = 12))

        toggle = Button(this).apply {
            setTextColor(Color.WHITE)
            isAllCaps = true
            textSize = 16f
            setOnClickListener { onToggle() }
        }
        root.addView(toggle, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, dp(56)).apply {
            gravity = Gravity.CENTER_HORIZONTAL
            topMargin = dp(28)
        })
        toggle.minimumWidth = dp(240)

        val kiosk = Button(this, null, android.R.attr.borderlessButtonStyle).apply {
            text = "Open kiosk"
            setOnClickListener { startActivity(Intent(this@SettingsActivity, MainActivity::class.java)) }
        }
        root.addView(kiosk, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
            gravity = Gravity.CENTER_HORIZONTAL
            topMargin = dp(8)
        })

        val ver = TextView(this).apply {
            text = "fServe ${BuildConfig.VERSION_NAME}"
            setTextColor(Color.GRAY)
            gravity = Gravity.CENTER_HORIZONTAL
        }
        root.addView(ver, full(top = 24))

        setContentView(ScrollView(this).apply { addView(root) })

        // Без разрешения на уведомления (Android 13+) уведомление «Running: ...» не будет видно.
        if (Build.VERSION.SDK_INT >= 33 &&
            checkSelfPermission("android.permission.POST_NOTIFICATIONS") != PackageManager.PERMISSION_GRANTED
        ) {
            requestPermissions(arrayOf("android.permission.POST_NOTIFICATIONS"), 1)
        }
    }

    private fun label(t: String) = TextView(this).apply {
        text = t
        textSize = 14f
        setTextColor(Color.GRAY)
    }

    private fun full(top: Int = 0) = LinearLayout.LayoutParams(
        ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
    ).apply { topMargin = dp(top) }

    private fun toast(t: String) = Toast.makeText(this, t, Toast.LENGTH_LONG).show()

    /** Проверяет и сохраняет порт и пароль. false, если ввод неверный. */
    private fun apply(quiet: Boolean): Boolean {
        val p = port.text.toString().trim().toIntOrNull()
        if (p == null || p !in 1024..65535) {
            if (!quiet) toast("Port must be a number from 1024 to 65535")
            return false
        }
        val pw = pass.text.toString()
        if (pw.isEmpty()) {
            if (!quiet) toast("Enter a password")
            return false
        }
        val r = Settings.update(obj("webPort" to p, "password" to pw))
        if (r.errors.isNotEmpty()) {
            if (!quiet) toast(r.errors.joinToString("; "))
            return false
        }
        if (r.restartRequired) ServerService.instance?.softRestart()
        return true
    }

    private fun onToggle() {
        if (Settings.serviceEnabled) {
            Settings.setServiceEnabled(false)
            ServerService.stop(this)
            Journal.add("service", "Служба остановлена из окна настроек")
        } else {
            if (!apply(false)) return
            Settings.setServiceEnabled(true)
            ServerService.start(this)
            Journal.add("service", "Служба запущена из окна настроек")
        }
        refresh()
    }

    private fun refresh() {
        val st = ServerService.state
        status.text = ServerService.statusLine()
        status.setTextColor(
            when (st) {
                ServerService.State.RUNNING -> Color.parseColor("#2E7D32")
                ServerService.State.FAIL -> Color.parseColor("#C62828")
                ServerService.State.STOPPED -> Color.GRAY
            }
        )
        val enabled = Settings.serviceEnabled
        toggle.text = if (enabled) "Stop service" else "Start service"
        toggle.background = GradientDrawable().apply {
            cornerRadius = dp(28).toFloat()
            setColor(Color.parseColor(if (enabled) "#D32F2F" else "#0F9688"))
        }
    }

    override fun onResume() {
        super.onResume()
        handler.post(tick)
    }

    override fun onPause() {
        handler.removeCallbacks(tick)
        apply(true) // тихо сохраняем, если ввод верный
        super.onPause()
    }
}
