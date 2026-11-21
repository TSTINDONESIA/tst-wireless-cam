package com.tst.wirelesscam

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.SharedPreferences
import android.content.pm.PackageManager
import android.graphics.Color
import android.graphics.Typeface
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CaptureRequest
import android.os.BatteryManager
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.text.InputType
import android.view.Gravity
import android.view.SurfaceHolder
import android.view.SurfaceView
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.widget.*
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.widget.SwitchCompat
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import com.pedro.common.ConnectChecker
import com.pedro.encoder.input.sources.video.Camera2Source
import com.pedro.library.srt.SrtStream
import java.util.Locale

/**
 * TST Wireless Cam
 * Android camera -> SRT -> vMix.
 *
 * UI is intentionally built in code so the project remains small and easy to edit.
 * Camera controls use RootEncoder Camera2Source where the Android device exposes them.
 */
class MainActivity : AppCompatActivity(), ConnectChecker, SurfaceHolder.Callback {

    companion object {
        private const val MATCH = ViewGroup.LayoutParams.MATCH_PARENT
        private const val WRAP = ViewGroup.LayoutParams.WRAP_CONTENT
        private val BG = Color.parseColor("#040914")
        private val PANEL = Color.parseColor("#101A2E")
        private val PANEL_2 = Color.parseColor("#16243F")
        private val BLUE = Color.parseColor("#1688FF")
        private val CYAN = Color.parseColor("#18D8FF")
        private val WHITE = Color.WHITE
        private val MUTED = Color.parseColor("#91A2BD")
        private val GREEN = Color.parseColor("#24D47B")
        private val RED = Color.parseColor("#FF5264")
        private val AMBER = Color.parseColor("#FFB547")
        private val STROKE = Color.parseColor("#263858")
    }

    private val resNames = listOf("720p", "1080p", "1440p", "4K")
    private val resSizes = listOf(1280 to 720, 1920 to 1080, 2560 to 1440, 3840 to 2160)
    private val fpsList = listOf(25, 30, 50, 60)
    private val modeNames = listOf("ULTRA LOW LATENCY", "BALANCED", "HIGH QUALITY")
    private val modeFactor = listOf(0.05, 0.08, 0.13)

    private lateinit var stream: SrtStream
    private lateinit var surface: SurfaceView
    private lateinit var prefs: SharedPreferences
    private val ui = Handler(Looper.getMainLooper())
    private val retryHandler = Handler(Looper.getMainLooper())

    private var surfaceReady = false
    private var prepared = false
    private var wantStreaming = false
    private var autoStartPending = false
    private var torchOn = false
    private var lastBitrateBps = 0L
    private var status = "READY"
    private var manualCamera = false
    private var manualIso = 400
    private var manualShutterNs = 10_000_000L
    private var manualFocus = 0f
    private var manualIris = 0f

    private lateinit var statusView: TextView
    private lateinit var infoView: TextView
    private lateinit var settingsPanel: ScrollView
    private lateinit var exposurePanel: LinearLayout
    private lateinit var tabTitle: TextView
    private lateinit var etName: EditText
    private lateinit var etIp: EditText
    private lateinit var etPort: EditText
    private lateinit var spRes: Spinner
    private lateinit var spFps: Spinner
    private lateinit var spMode: Spinner
    private lateinit var swAuto: SwitchCompat
    private lateinit var swAutoStart: SwitchCompat
    private lateinit var swManual: SwitchCompat
    private lateinit var isoValue: TextView
    private lateinit var shutterValue: TextView
    private lateinit var exposureValue: TextView
    private lateinit var focusValue: TextView
    private lateinit var irisValue: TextView
    private lateinit var irisSeek: SeekBar
    private lateinit var zoomSeek: SeekBar

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        prefs = getSharedPreferences("tst_cam", MODE_PRIVATE)
        surface = SurfaceView(this)
        surface.holder.addCallback(this)
        stream = SrtStream(this, this)
        setContentView(buildUi())
        loadConfig()
        autoStartPending = swAutoStart.isChecked
        ensurePermissions()
        setStatus("READY")
        ui.post(ticker)
    }

    override fun onDestroy() {
        ui.removeCallbacksAndMessages(null)
        retryHandler.removeCallbacksAndMessages(null)
        try {
            if (stream.isStreaming) stream.stopStream()
            if (stream.isOnPreview) stream.stopPreview()
            stream.release()
        } catch (_: Exception) {}
        super.onDestroy()
    }

    override fun surfaceCreated(holder: SurfaceHolder) {}
    override fun surfaceChanged(holder: SurfaceHolder, format: Int, width: Int, height: Int) {
        surfaceReady = true
        startPreviewIfPossible()
    }
    override fun surfaceDestroyed(holder: SurfaceHolder) {
        surfaceReady = false
        try { if (stream.isOnPreview) stream.stopPreview() } catch (_: Exception) {}
    }

    private fun hasPerms(): Boolean =
        ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED &&
        ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED

    private fun ensurePermissions() {
        if (!hasPerms()) ActivityCompat.requestPermissions(this, arrayOf(Manifest.permission.CAMERA, Manifest.permission.RECORD_AUDIO), 10)
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (hasPerms()) startPreviewIfPossible() else setStatus("CAMERA / MIC PERMISSION DENIED")
    }

    private fun selRes() = resSizes[spRes.selectedItemPosition.coerceAtLeast(0)]
    private fun selFps() = fpsList[spFps.selectedItemPosition.coerceAtLeast(0)]
    private fun selMode() = spMode.selectedItemPosition.coerceAtLeast(0)
    private fun targetBitrate(): Int {
        val (w, h) = selRes()
        return (w.toLong() * h * selFps() * modeFactor[selMode()]).toLong().coerceIn(1_500_000L, 25_000_000L).toInt()
    }

    private fun prepare(): Boolean {
        val (w, h) = selRes()
        return try {
            stream.prepareVideo(w, h, targetBitrate(), selFps()) && stream.prepareAudio(44100, true, 128 * 1000)
        } catch (_: Exception) { false }
    }

    private fun startPreviewIfPossible() {
        if (!surfaceReady || !hasPerms() || stream.isStreaming) return
        try {
            if (!prepared) {
                prepared = prepare()
                if (!prepared) { setStatus("RESOLUTION / FPS NOT SUPPORTED"); return }
            }
            if (!stream.isOnPreview) stream.startPreview(surface)
            applyCameraControls()
        } catch (e: Exception) {
            setStatus("CAMERA ERROR: ${e.message}")
            return
        }
        if (wantStreaming) startNow() else if (autoStartPending) { autoStartPending = false; clickStart() }
    }

    private fun clickStart() {
        if (!hasPerms()) { ensurePermissions(); return }
        val ip = etIp.text.toString().trim()
        val port = etPort.text.toString().trim().toIntOrNull()
        if (ip.isEmpty() || port == null) { toast("Isi IP PC vMix dan port di SETTINGS"); showSettings(true); return }
        saveConfig(); wantStreaming = true; startNow()
    }

    private fun startNow() {
        if (stream.isStreaming || !surfaceReady) return
        val ip = etIp.text.toString().trim()
        val port = etPort.text.toString().trim().toIntOrNull() ?: return
        if (!prepared) { startPreviewIfPossible(); if (!prepared) return }
        setStatus("CONNECTING  •  $ip:$port")
        try { stream.startStream("srt://$ip:$port/live") } catch (e: Exception) { setStatus("START FAILED: ${e.message}"); scheduleReconnect() }
    }

    private fun clickStop() {
        wantStreaming = false; retryHandler.removeCallbacksAndMessages(null)
        try { if (stream.isStreaming) stream.stopStream() } catch (_: Exception) {}
        lastBitrateBps = 0; setStatus("STOPPED")
    }

    private fun onReconnect() {
        retryHandler.removeCallbacksAndMessages(null)
        try { if (stream.isStreaming) stream.stopStream() } catch (_: Exception) {}
        wantStreaming = true; retryHandler.postDelayed({ startNow() }, 500)
    }

    private fun scheduleReconnect() {
        if (!wantStreaming || !swAuto.isChecked) return
        retryHandler.removeCallbacksAndMessages(null)
        retryHandler.postDelayed({ if (wantStreaming && !stream.isStreaming) startNow() }, 2000)
    }

    override fun onConnectionStarted(url: String) {}
    override fun onConnectionSuccess() = runOnUiThread { setStatus("LIVE  •  SENDING TO vMix") }
    override fun onConnectionFailed(reason: String) = runOnUiThread {
        try { if (stream.isStreaming) stream.stopStream() } catch (_: Exception) {}
        lastBitrateBps = 0; setStatus("FAILED: $reason"); scheduleReconnect()
    }
    override fun onNewBitrate(bitrate: Long) { lastBitrateBps = bitrate }
    override fun onDisconnect() = runOnUiThread {
        lastBitrateBps = 0
        if (wantStreaming && !status.startsWith("FAILED")) setStatus("DISCONNECTED")
        scheduleReconnect()
    }
    override fun onAuthError() = runOnUiThread { setStatus("AUTH ERROR") }
    override fun onAuthSuccess() {}

    private fun camSource(): Camera2Source? = stream.videoSource as? Camera2Source
    private fun flip() { try { camSource()?.switchCamera(); torchOn = false; resetCameraControlsUi() } catch (_: Exception) { toast("Ganti kamera gagal") } }
    private fun torch() { try { val src = camSource() ?: return; if (torchOn) src.disableLantern() else src.enableLantern(); torchOn = !torchOn } catch (_: Exception) { toast("Senter tidak tersedia") } }
    private fun zoom(level: Float) { try { camSource()?.setZoom(level) } catch (_: Exception) {} }

    /** Applies controls supported by Camera2/RootEncoder. Unsupported controls stay safely local. */
    private fun applyCameraControls() {
        val src = camSource() ?: return
        try {
            if (manualCamera) {
                src.disableAutoExposure()
                src.setExposure(exposureValue.tag as? Int ?: 0)
                src.setCustomRequest { builder ->
                    builder.set(CaptureRequest.CONTROL_AE_MODE, CaptureRequest.CONTROL_AE_MODE_OFF)
                    builder.set(CaptureRequest.SENSOR_SENSITIVITY, manualIso)
                    builder.set(CaptureRequest.SENSOR_EXPOSURE_TIME, manualShutterNs)
                    if (manualIris > 0f) builder.set(CaptureRequest.LENS_APERTURE, manualIris)
                }
                src.disableAutoFocus()
                src.setCustomRequest { builder -> builder.set(CaptureRequest.LENS_FOCUS_DISTANCE, manualFocus) }
            } else {
                src.enableAutoExposure()
                src.enableAutoFocus()
                src.setCustomRequest { builder -> builder.set(CaptureRequest.CONTROL_AE_MODE, CaptureRequest.CONTROL_AE_MODE_ON) }
            }
        } catch (_: Exception) {
            // Device-specific Camera2 capabilities vary; keep stream alive.
        }
    }

    private fun updateInfo() {
        val bat: Intent? = registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED))
        val level = bat?.getIntExtra(BatteryManager.EXTRA_LEVEL, -1) ?: -1
        val scale = bat?.getIntExtra(BatteryManager.EXTRA_SCALE, 100) ?: 100
        val pct = if (level >= 0 && scale > 0) level * 100 / scale else -1
        val temp = (bat?.getIntExtra(BatteryManager.EXTRA_TEMPERATURE, 0) ?: 0) / 10.0
        val (w, h) = selRes()
        val mbps = lastBitrateBps / 1_000_000.0
        infoView.text = "${resNames[spRes.selectedItemPosition.coerceAtLeast(0)]}  •  ${selFps()} FPS   |   ${String.format(Locale.US, "%.1f", mbps)} Mbps\n" +
                "BAT ${if (pct >= 0) "$pct%" else "?"}   •   TEMP ${String.format(Locale.US, "%.1f", temp)}°C   •   ${w}×${h}\n" +
                "PHONE ${phoneIp()}   →   ${etIp.text.toString().ifBlank { "-" }}:${etPort.text.toString().ifBlank { "-" }}"
        if (temp >= 43.0) setStatus("WARNING  •  PHONE HOT")
    }

    @Suppress("DEPRECATION")
    private fun phoneIp(): String = try {
        val wm = applicationContext.getSystemService(Context.WIFI_SERVICE) as android.net.wifi.WifiManager
        val ip = wm.connectionInfo.ipAddress
        if (ip == 0) "NO WI-FI" else "${ip and 0xff}.${(ip shr 8) and 0xff}.${(ip shr 16) and 0xff}.${(ip shr 24) and 0xff}"
    } catch (_: Exception) { "?" }

    private fun setStatus(s: String) { status = s; if (::statusView.isInitialized) statusView.text = "●  $s" }

    private fun saveConfig() {
        prefs.edit().putString("name", etName.text.toString()).putString("ip", etIp.text.toString().trim()).putString("port", etPort.text.toString().trim())
            .putInt("res", spRes.selectedItemPosition).putInt("fps", spFps.selectedItemPosition).putInt("mode", spMode.selectedItemPosition)
            .putBoolean("auto", swAuto.isChecked).putBoolean("autostart", swAutoStart.isChecked).apply()
    }

    private fun loadConfig() {
        etName.setText(prefs.getString("name", "TST CAM 01")); etIp.setText(prefs.getString("ip", "")); etPort.setText(prefs.getString("port", "9001"))
        spRes.setSelection(prefs.getInt("res", 1).coerceIn(0, resNames.lastIndex)); spFps.setSelection(prefs.getInt("fps", 1).coerceIn(0, fpsList.lastIndex)); spMode.setSelection(prefs.getInt("mode", 1).coerceIn(0, modeNames.lastIndex))
        swAuto.isChecked = prefs.getBoolean("auto", true); swAutoStart.isChecked = prefs.getBoolean("autostart", false)
    }

    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()
    private fun toast(msg: String) = Toast.makeText(this, msg, Toast.LENGTH_LONG).show()

    private fun text(value: String, size: Float, color: Int, bold: Boolean = false): TextView = TextView(this).apply {
        text = value; textSize = size; setTextColor(color); if (bold) typeface = Typeface.DEFAULT_BOLD
    }

    private fun rounded(color: Int, stroke: Int = 0): android.graphics.drawable.GradientDrawable = android.graphics.drawable.GradientDrawable().apply {
        setColor(color); cornerRadius = dp(12).toFloat(); if (stroke > 0) setStroke(dp(1), stroke)
    }

    private fun iconButton(symbol: String, label: String, click: () -> Unit): LinearLayout {
        return LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL; gravity = Gravity.CENTER; setPadding(dp(8), dp(10), dp(8), dp(8)); background = rounded(PANEL_2, STROKE)
            addView(text(symbol, 21f, CYAN, true)); addView(text(label, 10f, WHITE, true), LinearLayout.LayoutParams(MATCH, WRAP).apply { topMargin = dp(3) }); setOnClickListener { click() }
        }
    }

    private fun sliderCard(title: String, value: TextView, max: Int, progress: Int, onChange: (Int) -> Unit): LinearLayout {
        val card = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; background = rounded(PANEL, STROKE); setPadding(dp(12), dp(9), dp(12), dp(7)) }
        val head = LinearLayout(this).apply { gravity = Gravity.CENTER_VERTICAL }
        head.addView(text(title, 12f, WHITE, true), LinearLayout.LayoutParams(0, WRAP, 1f)); head.addView(value)
        card.addView(head)
        val bar = SeekBar(this).apply { this.max = max; this.progress = progress; setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(s: SeekBar?, p: Int, fromUser: Boolean) { if (fromUser) onChange(p) }
            override fun onStartTrackingTouch(s: SeekBar?) {}
            override fun onStopTrackingTouch(s: SeekBar?) {}
        }) }
        card.addView(bar, LinearLayout.LayoutParams(MATCH, WRAP))
        return card
    }

    private fun showSettings(show: Boolean) { settingsPanel.visibility = if (show) View.VISIBLE else View.GONE }

    private fun buildUi(): View {
        val root = FrameLayout(this).apply { setBackgroundColor(BG) }
        root.addView(surface, FrameLayout.LayoutParams(MATCH, MATCH))

        // dark overlay over preview, keeping camera image visible
        val top = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL; setPadding(dp(18), dp(12), dp(18), dp(10)); background = rounded(Color.argb(225, 5, 12, 28)) }
        val brand = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        brand.addView(text("TST CONTROL", 19f, WHITE, true)); brand.addView(text("PTZ • WIRELESS CAMERA", 10f, CYAN, true))
        top.addView(brand, LinearLayout.LayoutParams(0, WRAP, 1f))
        statusView = text("●  READY", 12f, CYAN, true); top.addView(statusView)
        val topLp = FrameLayout.LayoutParams(MATCH, WRAP, Gravity.TOP); topLp.setMargins(dp(10), dp(10), dp(10), 0); root.addView(top, topLp)

        infoView = text("", 10f, WHITE); infoView.typeface = Typeface.MONOSPACE; infoView.setPadding(dp(12), dp(8), dp(12), dp(8)); infoView.background = rounded(Color.argb(210, 8, 17, 34), STROKE)
        val infoLp = FrameLayout.LayoutParams(WRAP, WRAP, Gravity.TOP or Gravity.END); infoLp.setMargins(0, dp(70), dp(12), 0); root.addView(infoView, infoLp)

        // Right control rail
        val rail = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(dp(10), dp(10), dp(10), dp(10)); background = rounded(Color.argb(235, 8, 15, 30), STROKE) }
        val tabs = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        val tabNames = listOf("☀" to "Exposure", "◉" to "Image", "◎" to "Focus", "✣" to "PTZ", "⌁" to "Network")
        tabNames.forEachIndexed { i, pair ->
            val b = iconButton(pair.first, pair.second) { if (i == 0) { tabTitle.text = "EXPOSURE / CAMERA"; exposurePanel.visibility = View.VISIBLE } else { tabTitle.text = pair.second.uppercase(); exposurePanel.visibility = View.GONE; toast("Panel ${pair.second} siap dikembangkan") } }
            tabs.addView(b, LinearLayout.LayoutParams(0, WRAP, 1f).apply { setMargins(dp(3), 0, dp(3), 0) })
        }
        rail.addView(tabs)
        tabTitle = text("EXPOSURE / CAMERA", 13f, CYAN, true); tabTitle.setPadding(dp(6), dp(12), dp(6), dp(8)); rail.addView(tabTitle)

        exposurePanel = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        val manualRow = LinearLayout(this).apply { gravity = Gravity.CENTER_VERTICAL; background = rounded(PANEL_2, STROKE); setPadding(dp(12), dp(8), dp(12), dp(8)) }
        manualRow.addView(text("MANUAL CAMERA", 12f, WHITE, true), LinearLayout.LayoutParams(0, WRAP, 1f))
        swManual = SwitchCompat(this).apply { isChecked = false; setOnCheckedChangeListener { _, _ -> manualCamera = isChecked; applyCameraControls(); refreshManualUi() } }
        manualRow.addView(swManual); exposurePanel.addView(manualRow)

        exposureValue = text("0 EV", 11f, CYAN, true)
        exposureValue.tag = 0
        exposurePanel.addView(sliderCard("Exposure Compensation", exposureValue, 24, 12) { p -> val ev = p - 12; exposureValue.text = "${if (ev >= 0) "+" else ""}$ev EV"; exposureValue.tag = ev; if (manualCamera) try { camSource()?.setExposure(ev) } catch (_: Exception) {} })

        isoValue = text("ISO 400", 11f, CYAN, true)
        exposurePanel.addView(sliderCard("Gain / ISO", isoValue, 15, 5) { p -> manualIso = listOf(100, 125, 160, 200, 250, 320, 400, 500, 640, 800, 1000, 1250, 1600, 2000, 2500, 3200).getOrElse(p) { 400 }; isoValue.text = "ISO $manualIso"; applyCameraControls() })

        shutterValue = text("1/100", 11f, CYAN, true)
        exposurePanel.addView(sliderCard("Shutter Speed", shutterValue, 11, 6) { p -> val ns = listOf(1_000_000_000L, 500_000_000L, 250_000_000L, 125_000_000L, 66_666_666L, 33_333_333L, 10_000_000L, 5_000_000L, 2_500_000L, 1_000_000L, 500_000L)[p]; manualShutterNs = ns; shutterValue.text = shutterText(ns); applyCameraControls() })

        focusValue = text("AUTO", 11f, CYAN, true)
        exposurePanel.addView(sliderCard("Focus Distance", focusValue, 100, 0) { p -> manualFocus = p / 100f; focusValue.text = if (p == 0) "AUTO" else String.format(Locale.US, "%.2f", manualFocus); if (manualCamera) try { camSource()?.setCustomRequest { builder -> builder.set(CaptureRequest.LENS_FOCUS_DISTANCE, manualFocus) } } catch (_: Exception) {} })

        irisValue = text("AUTO", 11f, AMBER, true)
        irisSeek = SeekBar(this)
        val irisCard = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; background = rounded(PANEL, STROKE); setPadding(dp(12), dp(9), dp(12), dp(7)) }
        val irisHead = LinearLayout(this).apply { gravity = Gravity.CENTER_VERTICAL; addView(text("Iris / Aperture", 12f, WHITE, true), LinearLayout.LayoutParams(0, WRAP, 1f)); addView(irisValue) }
        irisCard.addView(irisHead); irisSeek.max = 100; irisCard.addView(irisSeek); exposurePanel.addView(irisCard)
        irisSeek.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener { override fun onProgressChanged(s: SeekBar?, p: Int, f: Boolean) { if (f) { manualIris = 1.4f + p / 100f * 7f; irisValue.text = String.format(Locale.US, "f/%.1f", manualIris); applyCameraControls() } }; override fun onStartTrackingTouch(s: SeekBar?) {}; override fun onStopTrackingTouch(s: SeekBar?) {} })

        exposurePanel.addView(toggleRow("Auto Exposure", false) { sw -> manualCamera = !sw; swManual.isChecked = !sw; applyCameraControls() })
        exposurePanel.addView(toggleRow("Auto Focus", true) { auto -> try { if (auto) camSource()?.enableAutoFocus() else camSource()?.disableAutoFocus() } catch (_: Exception) {} })
        rail.addView(exposurePanel)
        val railLp = FrameLayout.LayoutParams(dp(390), MATCH, Gravity.END); railLp.setMargins(0, dp(82), dp(10), dp(145)); root.addView(rail, railLp)

        // Bottom control deck
        val bottom = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; background = rounded(Color.argb(238, 7, 15, 30), STROKE); setPadding(dp(10), dp(8), dp(10), dp(10)) }
        val zoomRow = LinearLayout(this).apply { gravity = Gravity.CENTER_VERTICAL }
        zoomRow.addView(text("ZOOM", 10f, CYAN, true)); zoomSeek = SeekBar(this).apply { max = 40; progress = 0; setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener { override fun onProgressChanged(s: SeekBar?, p: Int, f: Boolean) { if (f) zoom(1f + p / 10f) }; override fun onStartTrackingTouch(s: SeekBar?) {}; override fun onStopTrackingTouch(s: SeekBar?) {} }) }; zoomRow.addView(zoomSeek, LinearLayout.LayoutParams(0, WRAP, 1f)); zoomRow.addView(text("1.0× → 5.0×", 9f, MUTED)); bottom.addView(zoomRow)
        val controls = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        fun deckButton(label: String, color: Int, action: () -> Unit) { val b = Button(this); b.text = label; b.setTextColor(WHITE); b.textSize = 11f; b.setTypeface(Typeface.DEFAULT, Typeface.BOLD); b.background = rounded(color, STROKE); b.setOnClickListener { action() }; controls.addView(b, LinearLayout.LayoutParams(0, dp(44), 1f).apply { setMargins(dp(3), dp(5), dp(3), 0) }) }
        deckButton("▶  START", Color.parseColor("#0B7B4A")) { clickStart() }; deckButton("■  STOP", Color.parseColor("#8D2434")) { clickStop() }; deckButton("↻  RECONNECT", Color.parseColor("#1455A6")) { onReconnect() }; deckButton("⚙  SETTINGS", Color.parseColor("#243653")) { showSettings(settingsPanel.visibility != View.VISIBLE) }; deckButton("↔  FLIP", Color.parseColor("#243653")) { flip() }; deckButton("☼  LIGHT", Color.parseColor("#243653")) { torch() }
        bottom.addView(controls)
        root.addView(bottom, FrameLayout.LayoutParams(MATCH, WRAP, Gravity.BOTTOM).apply { setMargins(dp(10), 0, dp(10), dp(10)) })

        // Settings drawer
        settingsPanel = ScrollView(this).apply { background = rounded(Color.argb(248, 8, 16, 31), STROKE); visibility = View.GONE }
        val form = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(dp(16), dp(16), dp(16), dp(16)) }
        form.addView(text("NETWORK & STREAM SETTINGS", 15f, CYAN, true)); form.addView(text("SRT output configuration", 10f, MUTED))
        etName = field(form, "Camera Name", "TST CAM 01", InputType.TYPE_CLASS_TEXT); etIp = field(form, "vMix PC IP", "192.168.1.10", InputType.TYPE_CLASS_TEXT); etPort = field(form, "SRT Port", "9001", InputType.TYPE_CLASS_NUMBER)
        spRes = spinner(form, "Resolution", resNames); spFps = spinner(form, "Frame Rate", fpsList.map { "$it FPS" }); spMode = spinner(form, "Latency / Quality", modeNames)
        swAuto = toggle(form, "Auto reconnect"); swAutoStart = toggle(form, "Auto start")
        val save = Button(this).apply {
            text = "SAVE & APPLY"
            setTextColor(WHITE)
            background = rounded(BLUE)
            setOnClickListener {
                saveConfig()
                if (stream.isStreaming) {
                    toast("STOP dulu untuk menerapkan resolusi/FPS baru")
                } else {
                    try {
                        if (stream.isOnPreview) stream.stopPreview()
                    } catch (_: Exception) {}
                    prepared = false
                    showSettings(false)
                    startPreviewIfPossible()
                }
            }
        }
        form.addView(save, LinearLayout.LayoutParams(MATCH, dp(48)).apply { topMargin = dp(12) }); settingsPanel.addView(form)
        root.addView(settingsPanel, FrameLayout.LayoutParams(dp(370), MATCH, Gravity.END).apply { setMargins(0, dp(82), dp(10), dp(150) })
        return root
    }

    private fun toggleRow(label: String, checked: Boolean, action: (Boolean) -> Unit): LinearLayout = LinearLayout(this).apply {
        gravity = Gravity.CENTER_VERTICAL; background = rounded(PANEL, STROKE); setPadding(dp(12), dp(4), dp(12), dp(4)); addView(text(label, 11f, WHITE, true), LinearLayout.LayoutParams(0, WRAP, 1f)); val sw = SwitchCompat(this@MainActivity).apply { isChecked = checked; setOnCheckedChangeListener { _, v -> action(v) } }; addView(sw)
    }

    private fun refreshManualUi() { val enabled = manualCamera; isoValue.alpha = if (enabled) 1f else .45f; shutterValue.alpha = if (enabled) 1f else .45f; focusValue.alpha = if (enabled) 1f else .45f }
    private fun resetCameraControlsUi() { manualFocus = 0f; focusValue.text = "AUTO" }
    private fun shutterText(ns: Long): String { val denom = (1_000_000_000L / ns.toDouble()).let { if (it >= 1) Math.round(it).toInt() else 0 }; return if (denom > 0) "1/$denom" else "${ns / 1_000_000}ms" }

    private fun field(parent: LinearLayout, title: String, hint: String, type: Int): EditText { parent.addView(text(title, 10f, CYAN, true).apply { setPadding(0, dp(12), 0, dp(4)) }); return EditText(this).apply { this.hint = hint; setHintTextColor(MUTED); setTextColor(WHITE); inputType = type; setSingleLine(); background = rounded(PANEL_2, STROKE); setPadding(dp(12), 0, dp(12), 0); parent.addView(this, LinearLayout.LayoutParams(MATCH, dp(44)).apply { bottomMargin = dp(3) }) } }
    private fun spinner(parent: LinearLayout, title: String, items: List<String>): Spinner { parent.addView(text(title, 10f, CYAN, true).apply { setPadding(0, dp(10), 0, dp(4)) }); return Spinner(this).apply { adapter = ArrayAdapter(this@MainActivity, android.R.layout.simple_spinner_dropdown_item, items); background = rounded(PANEL_2, STROKE); parent.addView(this, LinearLayout.LayoutParams(MATCH, dp(44)).apply { bottomMargin = dp(3) }) } }
    private fun toggle(parent: LinearLayout, title: String): SwitchCompat = SwitchCompat(this).apply { text = title; setTextColor(WHITE); parent.addView(this, LinearLayout.LayoutParams(MATCH, WRAP).apply { topMargin = dp(6) }) }

    private val ticker = object : Runnable { override fun run() { if (::infoView.isInitialized) updateInfo(); ui.postDelayed(this, 1500) } }
}
