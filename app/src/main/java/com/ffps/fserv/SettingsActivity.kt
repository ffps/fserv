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
    private lateinit var allow: Button
    private lateinit var allowCam: Button
    private lateinit var allowOv: Button
    private lateinit var crash: EditText
    private lateinit var crashLabel: TextView
    private lateinit var crashRow: LinearLayout
    private var crashShown = ""
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
        // На Android 8 фокус сразу попадал в поле порта, и выезжала экранная клавиатура
        window.setSoftInputMode(android.view.WindowManager.LayoutParams.SOFT_INPUT_STATE_ALWAYS_HIDDEN)

        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(20), dp(16), dp(20), dp(24))
        }

        status = TextView(this).apply {
            textSize = 14f
            setTextColor(Color.GRAY)
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
            // setSingleLine заменяет способ отображения текста, поэтому маскирование задаём после него
            transformationMethod = PasswordTransformationMethod.getInstance()
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

        allow = Button(this, null, android.R.attr.borderlessButtonStyle).apply {
            text = "Allow app updates"
            setOnClickListener {
                startActivity(
                    Intent(android.provider.Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, android.net.Uri.parse("package:$packageName"))
                )
            }
        }
        root.addView(allow, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
            gravity = Gravity.CENTER_HORIZONTAL
        })

        allowCam = Button(this, null, android.R.attr.borderlessButtonStyle).apply {
            text = "Allow camera"
            setOnClickListener { requestPermissions(arrayOf("android.permission.CAMERA"), 2) }
        }
        root.addView(allowCam, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
            gravity = Gravity.CENTER_HORIZONTAL
        })

        allowOv = Button(this, null, android.R.attr.borderlessButtonStyle).apply {
            text = "Allow overlay"
            setOnClickListener {
                startActivity(
                    Intent(android.provider.Settings.ACTION_MANAGE_OVERLAY_PERMISSION, android.net.Uri.parse("package:$packageName"))
                )
            }
        }
        root.addView(allowOv, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
            gravity = Gravity.CENTER_HORIZONTAL
        })

        crashLabel = label("Last crash").apply { visibility = android.view.View.GONE }
        root.addView(crashLabel, full(top = 12))
        crash = EditText(this).apply {
            // только для чтения, но текст можно выделять и копировать; длинный отчёт прокручивается
            keyListener = null
            setTextIsSelectable(true)
            isSingleLine = false
            setHorizontallyScrolling(false)
            gravity = Gravity.TOP
            textSize = 11f
            setTextColor(Color.parseColor("#C62828"))
            movementMethod = android.text.method.ScrollingMovementMethod.getInstance()
            isVerticalScrollBarEnabled = true
            minLines = 6
            maxLines = 10
            setOnTouchListener { v, e ->
                v.parent.requestDisallowInterceptTouchEvent(true)
                if (e.action == android.view.MotionEvent.ACTION_UP) v.parent.requestDisallowInterceptTouchEvent(false)
                false
            }
            visibility = android.view.View.GONE
        }
        root.addView(crash, full())
        val crashRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_HORIZONTAL
            visibility = android.view.View.GONE
        }
        crashRow.addView(Button(this, null, android.R.attr.borderlessButtonStyle).apply {
            text = "Copy"
            setOnClickListener {
                val cm = getSystemService(android.content.ClipboardManager::class.java)
                cm.setPrimaryClip(android.content.ClipData.newPlainText("fServe crash", crash.text.toString()))
                Toast.makeText(this@SettingsActivity, "Copied", Toast.LENGTH_SHORT).show()
            }
        })
        crashRow.addView(Button(this, null, android.R.attr.borderlessButtonStyle).apply {
            text = "Clear crash report"
            setOnClickListener { App.clearCrash(this@SettingsActivity); refresh() }
        })
        this.crashRow = crashRow
        root.addView(crashRow, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
            gravity = Gravity.CENTER_HORIZONTAL
        })

        val ver = TextView(this).apply {
            text = "fServe ${BuildConfig.VERSION_NAME}"
            setTextColor(Color.GRAY)
            gravity = Gravity.CENTER_HORIZONTAL
        }
        root.addView(ver, full(top = 24))

        setContentView(ScrollView(this).apply { addView(root) })
        root.isFocusableInTouchMode = true
        root.requestFocus() // фокус на контейнер, а не на поле порта

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
        val ct = App.crashText(this)
        if (ct != crashShown) { // не трогаем текст каждую секунду: иначе сбрасываются прокрутка и выделение
            crashShown = ct
            crash.setText(ct)
        }
        val vis = if (ct.isEmpty()) android.view.View.GONE else android.view.View.VISIBLE
        crash.visibility = vis
        crashLabel.visibility = vis
        crashRow.visibility = vis
        // «Поверх других окон» нужно с Android 10, чтобы киоск можно было вывести на экран из фона
        allowOv.visibility = if (Build.VERSION.SDK_INT < 29 || android.provider.Settings.canDrawOverlays(this)) android.view.View.GONE else android.view.View.VISIBLE
        allowCam.visibility = if (CameraEngine.hasPermission(this)) android.view.View.GONE else android.view.View.VISIBLE
        allow.visibility = if (Updater.canInstall(this)) android.view.View.GONE else android.view.View.VISIBLE
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

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        // Разрешение камеры выдано: служба переоформляется с типом camera.
        if (requestCode == 2 && Settings.serviceEnabled && Settings.isConfigured) ServerService.start(this)
    }

    override fun onResume() {
        super.onResume()
        // Служба должна работать, но не работает (например, после обновления): поднимаем её.
        if (Settings.serviceEnabled && Settings.isConfigured && ServerService.state == ServerService.State.STOPPED) ServerService.start(this)
        handler.post(tick)
    }

    override fun onPause() {
        handler.removeCallbacks(tick)
        apply(true) // тихо сохраняем, если ввод верный
        super.onPause()
    }
}
