package com.tst.wirelesscam

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.SharedPreferences
import android.content.pm.PackageManager
import android.graphics.Color
import android.graphics.Typeface
import android.net.wifi.WifiManager
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
import android.widget.ArrayAdapter
import android.widget.Button
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.SeekBar
import android.widget.Spinner
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.widget.SwitchCompat
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import com.pedro.common.ConnectChecker
import com.pedro.library.srt.SrtStream
import com.pedro.encoder.input.sources.video.Camera2Source

/**
 * TST Wireless Cam - kamera HP Android yang mengirim video + suara
 * langsung ke vMix lewat SRT di jaringan Wi-Fi lokal (tanpa internet).
 *
 * Di vMix: Add Input > Stream / SRT > mode Listener > port (mis. 9001).
 * Di aplikasi ini: isi IP PC vMix dan port yang sama, lalu START CAMERA.
 */
class MainActivity : AppCompatActivity(), ConnectChecker, SurfaceHolder.Callback {

    companion object {
        private const val MATCH = ViewGroup.LayoutParams.MATCH_PARENT
        private const val WRAP = ViewGroup.LayoutParams.WRAP_CONTENT
        private val NAVY = Color.parseColor("#050B1A")
        private val PANEL = Color.parseColor("#CC0A1630")
        private val CYAN = Color.parseColor("#00E5FF")
        private val GREEN = Color.parseColor("#13A35A")
        private val RED = Color.parseColor("#C62F3E")
        private val BLUE = Color.parseColor("#1565C0")
        private val GRAY = Color.parseColor("#2A3A5C")
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

    private var surfaceReady = false
    private var prepared = false
    private var wantStreaming = false
    private var autoStartPending = false
    private var torchOn = false
    private var lastBitrateBps = 0L
    private var status = "SIAP"

    private lateinit var statusView: TextView
    private lateinit var infoView: TextView
    private lateinit var settingsPanel: ScrollView
    private lateinit var etName: EditText
    private lateinit var etIp: EditText
    private lateinit var etPort: EditText
    private lateinit var spRes: Spinner
    private lateinit var spFps: Spinner
    private lateinit var spMode: Spinner
    private lateinit var swAuto: SwitchCompat
    private lateinit var swAutoStart: SwitchCompat

    // ---------------------------------------------------------------- lifecycle

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
        setStatus("SIAP")
        ui.post(ticker)
    }

    override fun onDestroy() {
        ui.removeCallbacksAndMessages(null)
        try {
            if (stream.isStreaming) stream.stopStream()
            if (stream.isOnPreview) stream.stopPreview()
            stream.release()
        } catch (_: Exception) {
        }
        super.onDestroy()
    }

    // ---------------------------------------------------------------- surface

    override fun surfaceCreated(holder: SurfaceHolder) {}

    override fun surfaceChanged(holder: SurfaceHolder, format: Int, width: Int, height: Int) {
        surfaceReady = true
        startPreviewIfPossible()
    }

    override fun surfaceDestroyed(holder: SurfaceHolder) {
        surfaceReady = false
        try {
            if (stream.isOnPreview) stream.stopPreview()
        } catch (_: Exception) {
        }
    }

    // ---------------------------------------------------------------- permissions

    private fun hasPerms(): Boolean =
        ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED &&
            ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED

    private fun ensurePermissions() {
        if (!hasPerms()) {
            ActivityCompat.requestPermissions(
                this,
                arrayOf(Manifest.permission.CAMERA, Manifest.permission.RECORD_AUDIO),
                10
            )
        }
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (hasPerms()) startPreviewIfPossible() else setStatus("IZIN KAMERA/MIKROFON DITOLAK")
    }

    // ---------------------------------------------------------------- video config

    private fun selRes() = resSizes[spRes.selectedItemPosition.coerceAtLeast(0)]
    private fun selFps() = fpsList[spFps.selectedItemPosition.coerceAtLeast(0)]
    private fun selMode() = spMode.selectedItemPosition.coerceAtLeast(0)

    private fun targetBitrate(): Int {
        val (w, h) = selRes()
        val bps = (w.toLong() * h * selFps() * modeFactor[selMode()]).toLong()
        return bps.coerceIn(1_500_000L, 25_000_000L).toInt()
    }

    /** prepareVideo/prepareAudio hanya boleh dipanggil saat preview & stream mati. */
    private fun prepare(): Boolean {
        val (w, h) = selRes()
        return try {
            stream.prepareVideo(w, h, targetBitrate(), selFps()) &&
                stream.prepareAudio(44100, true, 128 * 1000)
        } catch (e: Exception) {
            false
        }
    }

    private fun startPreviewIfPossible() {
        if (!surfaceReady || !hasPerms() || stream.isStreaming) return
        try {
            if (!prepared) {
                prepared = prepare()
                if (!prepared) {
                    setStatus("RESOLUSI/FPS TIDAK DIDUKUNG HP INI")
                    return
                }
            }
            if (!stream.isOnPreview) stream.startPreview(surface)
        } catch (e: Exception) {
            setStatus("KAMERA GAGAL: ${e.message}")
            return
        }
        if (wantStreaming) {
            startNow()
        } else if (autoStartPending) {
            autoStartPending = false
            clickStart()
        }
    }

    private fun applySettings() {
        if (stream.isStreaming) {
            toast("Tekan STOP CAMERA dulu supaya pengaturan berlaku")
            return
        }
        saveConfig()
        try {
            if (stream.isOnPreview) stream.stopPreview()
        } catch (_: Exception) {
        }
        prepared = false
        startPreviewIfPossible()
        settingsPanel.visibility = View.GONE
    }

    // ---------------------------------------------------------------- stream control

    private fun clickStart() {
        if (!hasPerms()) {
            ensurePermissions()
            return
        }
        val ip = etIp.text.toString().trim()
        val port = etPort.text.toString().trim().toIntOrNull()
        if (ip.isEmpty() || port == null) {
            toast("Isi IP PC vMix dan Port di SETTINGS dulu")
            settingsPanel.visibility = View.VISIBLE
            return
        }
        saveConfig()
        wantStreaming = true
        startNow()
    }

    private fun startNow() {
        if (stream.isStreaming || !surfaceReady) return
        val ip = etIp.text.toString().trim()
        val port = etPort.text.toString().trim().toIntOrNull() ?: return
        if (!prepared) {
            startPreviewIfPossible()
            if (!prepared) return
        }
        setStatus("MENGHUBUNGKAN KE $ip:$port ...")
        try {
            stream.startStream("srt://$ip:$port")
        } catch (e: Exception) {
            setStatus("GAGAL MULAI: ${e.message}")
            scheduleReconnect()
        }
    }

    private fun clickStop() {
        wantStreaming = false
        retryHandler.removeCallbacksAndMessages(null)
        try {
            if (stream.isStreaming) stream.stopStream()
        } catch (_: Exception) {
        }
        lastBitrateBps = 0
        setStatus("BERHENTI")
    }

    private fun onReconnect() {
        retryHandler.removeCallbacksAndMessages(null)
        try {
            if (stream.isStreaming) stream.stopStream()
        } catch (_: Exception) {
        }
        wantStreaming = true
        retryHandler.postDelayed({ startNow() }, 600)
    }

    private val retryHandler = Handler(Looper.getMainLooper())

    private fun scheduleReconnect() {
        if (!wantStreaming || !swAuto.isChecked) return
        retryHandler.removeCallbacksAndMessages(null)
        retryHandler.postDelayed({
            if (wantStreaming && !stream.isStreaming) startNow()
        }, 2000)
    }

    // ---------------------------------------------------------------- ConnectChecker (thread bukan UI)

    override fun onConnectionStarted(url: String) {}

    override fun onConnectionSuccess() {
        runOnUiThread { setStatus("MENGIRIM KE vMix") }
    }

    override fun onConnectionFailed(reason: String) {
        runOnUiThread {
            try {
                if (stream.isStreaming) stream.stopStream()
            } catch (_: Exception) {
            }
            lastBitrateBps = 0
            setStatus("GAGAL: $reason")
            scheduleReconnect()
        }
    }

    override fun onNewBitrate(bitrate: Long) {
        lastBitrateBps = bitrate
    }

    override fun onDisconnect() {
        runOnUiThread {
            lastBitrateBps = 0
            if (wantStreaming) setStatus("TERPUTUS")
            scheduleReconnect()
        }
    }

    override fun onAuthError() {
        runOnUiThread { setStatus("AUTH ERROR") }
    }

    override fun onAuthSuccess() {}

    // ---------------------------------------------------------------- camera controls

    private fun camSource(): Camera2Source? = stream.videoSource as? Camera2Source

    private fun flip() {
        try {
            camSource()?.switchCamera()
            torchOn = false
        } catch (e: Exception) {
            toast("Ganti kamera gagal")
        }
    }

    private fun torch() {
        try {
            val src = camSource() ?: return
            if (torchOn) src.disableLantern() else src.enableLantern()
            torchOn = !torchOn
        } catch (e: Exception) {
            toast("Senter tidak tersedia di kamera ini")
        }
    }

    private fun zoom(level: Float) {
        try {
            camSource()?.setZoom(level)
        } catch (_: Exception) {
        }
    }

    // ---------------------------------------------------------------- dashboard

    private val ticker = object : Runnable {
        override fun run() {
            updateInfo()
            ui.postDelayed(this, 1500)
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
        val sb = StringBuilder()
        sb.append("KAMERA   : ").append(etName.text.toString().ifBlank { "TST CAM" }).append('\n')
        sb.append("RESOLUSI : ").append(resNames[spRes.selectedItemPosition.coerceAtLeast(0)])
            .append(" (").append(w).append('x').append(h).append(")\n")
        sb.append("FPS      : ").append(selFps()).append(" (diatur)\n")
        sb.append("BITRATE  : ").append(String.format("%.1f Mbps (terukur)", mbps)).append('\n')
        sb.append("MODE     : ").append(modeNames[selMode()]).append('\n')
        sb.append("BATERAI  : ").append(if (pct >= 0) "$pct%" else "?")
            .append("   SUHU: ").append(String.format("%.1f", temp)).append(" C\n")
        sb.append("HP       : ").append(phoneIp()).append('\n')
        sb.append("TUJUAN   : ").append(etIp.text.toString().ifBlank { "-" }).append(':')
            .append(etPort.text.toString().ifBlank { "-" }).append('\n')
        sb.append("LATENCY  : belum terukur")
        if (temp >= 43.0) sb.append("\nPERINGATAN: HP PANAS")
        infoView.text = sb.toString()
    }

    @Suppress("DEPRECATION")
    private fun phoneIp(): String {
        return try {
            val wm = applicationContext.getSystemService(Context.WIFI_SERVICE) as WifiManager
            val ip = wm.connectionInfo.ipAddress
            if (ip == 0) "tidak terhubung Wi-Fi"
            else "${ip and 0xff}.${(ip shr 8) and 0xff}.${(ip shr 16) and 0xff}.${(ip shr 24) and 0xff}"
        } catch (e: Exception) {
            "?"
        }
    }

    private fun setStatus(s: String) {
        status = s
        if (::statusView.isInitialized) statusView.text = "●  $s"
    }

    // ---------------------------------------------------------------- config

    private fun saveConfig() {
        prefs.edit()
            .putString("name", etName.text.toString())
            .putString("ip", etIp.text.toString().trim())
            .putString("port", etPort.text.toString().trim())
            .putInt("res", spRes.selectedItemPosition)
            .putInt("fps", spFps.selectedItemPosition)
            .putInt("mode", spMode.selectedItemPosition)
            .putBoolean("auto", swAuto.isChecked)
            .putBoolean("autostart", swAutoStart.isChecked)
            .apply()
    }

    private fun loadConfig() {
        etName.setText(prefs.getString("name", "TST CAM 01"))
        etIp.setText(prefs.getString("ip", ""))
        etPort.setText(prefs.getString("port", "9001"))
        spRes.setSelection(prefs.getInt("res", 1))
        spFps.setSelection(prefs.getInt("fps", 1))
        spMode.setSelection(prefs.getInt("mode", 1))
        swAuto.isChecked = prefs.getBoolean("auto", true)
        swAutoStart.isChecked = prefs.getBoolean("autostart", false)
    }

    // ---------------------------------------------------------------- UI (dibuat lewat kode)

    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()

    private fun toast(msg: String) = Toast.makeText(this, msg, Toast.LENGTH_LONG).show()

    private fun btn(title: String, color: Int, onClick: () -> Unit): Button {
        val b = Button(this)
        b.text = title
        b.setTextColor(Color.WHITE)
        b.setTypeface(b.typeface, Typeface.BOLD)
        b.setBackgroundColor(color)
        b.setOnClickListener { onClick() }
        return b
    }

    private fun caption(parent: LinearLayout, title: String) {
        val t = TextView(this)
        t.text = title
        t.setTextColor(CYAN)
        t.textSize = 12f
        t.setPadding(0, dp(10), 0, 0)
        parent.addView(t)
    }

    private fun field(parent: LinearLayout, title: String, hint: String, type: Int): EditText {
        caption(parent, title)
        val e = EditText(this)
        e.hint = hint
        e.setHintTextColor(Color.GRAY)
        e.setTextColor(Color.WHITE)
        e.inputType = type
        e.setSingleLine()
        parent.addView(e)
        return e
    }

    private fun spinner(parent: LinearLayout, title: String, items: List<String>): Spinner {
        caption(parent, title)
        val s = Spinner(this)
        s.adapter = ArrayAdapter(this, android.R.layout.simple_spinner_dropdown_item, items)
        s.setBackgroundColor(Color.parseColor("#E8F7FF"))
        parent.addView(s, LinearLayout.LayoutParams(MATCH, WRAP))
        return s
    }

    private fun toggle(parent: LinearLayout, title: String): SwitchCompat {
        val s = SwitchCompat(this)
        s.text = title
        s.setTextColor(Color.WHITE)
        s.setPadding(0, dp(10), 0, 0)
        parent.addView(s)
        return s
    }

    private fun buildUi(): View {
        val root = FrameLayout(this)
        root.setBackgroundColor(NAVY)
        root.addView(surface, FrameLayout.LayoutParams(MATCH, MATCH))

        // status (kiri atas)
        statusView = TextView(this)
        statusView.textSize = 15f
        statusView.setTextColor(CYAN)
        statusView.setTypeface(statusView.typeface, Typeface.BOLD)
        statusView.setBackgroundColor(PANEL)
        statusView.setPadding(dp(12), dp(8), dp(12), dp(8))
        val stLp = FrameLayout.LayoutParams(WRAP, WRAP, Gravity.TOP or Gravity.START)
        stLp.setMargins(dp(12), dp(12), 0, 0)
        root.addView(statusView, stLp)

        // dashboard (kanan atas)
        infoView = TextView(this)
        infoView.textSize = 11f
        infoView.typeface = Typeface.MONOSPACE
        infoView.setTextColor(Color.WHITE)
        infoView.setBackgroundColor(PANEL)
        infoView.setPadding(dp(10), dp(8), dp(10), dp(8))
        val infoLp = FrameLayout.LayoutParams(WRAP, WRAP, Gravity.TOP or Gravity.END)
        infoLp.setMargins(0, dp(12), dp(12), 0)
        root.addView(infoView, infoLp)

        // pengaturan (panel kanan)
        settingsPanel = ScrollView(this)
        settingsPanel.setBackgroundColor(Color.parseColor("#F00A1630"))
        settingsPanel.visibility = View.GONE
        val form = LinearLayout(this)
        form.orientation = LinearLayout.VERTICAL
        form.setPadding(dp(16), dp(16), dp(16), dp(16))
        etName = field(form, "Nama kamera", "TST CAM 01", InputType.TYPE_CLASS_TEXT)
        etIp = field(form, "IP PC vMix (lihat ipconfig di PC)", "192.168.1.10", InputType.TYPE_CLASS_TEXT)
        etPort = field(form, "Port SRT (sama dengan di vMix)", "9001", InputType.TYPE_CLASS_NUMBER)
        spRes = spinner(form, "Resolusi", resNames)
        spFps = spinner(form, "Frame rate (FPS)", fpsList.map { "$it FPS" })
        spMode = spinner(form, "Mode latency / kualitas", modeNames)
        swAuto = toggle(form, "Auto reconnect")
        swAutoStart = toggle(form, "Auto start saat aplikasi dibuka")
        val save = btn("SIMPAN", BLUE) { applySettings() }
        val saveLp = LinearLayout.LayoutParams(MATCH, WRAP)
        saveLp.topMargin = dp(16)
        form.addView(save, saveLp)
        settingsPanel.addView(form)
        val spLp = FrameLayout.LayoutParams(dp(340), MATCH, Gravity.END)
        spLp.setMargins(0, dp(60), 0, dp(150))
        root.addView(settingsPanel, spLp)

        // kontrol bawah
        val bottom = LinearLayout(this)
        bottom.orientation = LinearLayout.VERTICAL
        bottom.setBackgroundColor(PANEL)
        bottom.setPadding(dp(12), dp(6), dp(12), dp(8))

        val zoomRow = LinearLayout(this)
        zoomRow.orientation = LinearLayout.HORIZONTAL
        zoomRow.gravity = Gravity.CENTER_VERTICAL
        val zoomLabel = TextView(this)
        zoomLabel.text = "ZOOM"
        zoomLabel.setTextColor(CYAN)
        zoomLabel.textSize = 12f
        zoomRow.addView(zoomLabel)
        val zoomBar = SeekBar(this)
        zoomBar.max = 40
        zoomBar.progress = 0
        zoomBar.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(sb: SeekBar?, p: Int, fromUser: Boolean) {
                if (fromUser) zoom(1f + p / 10f)
            }

            override fun onStartTrackingTouch(sb: SeekBar?) {}
            override fun onStopTrackingTouch(sb: SeekBar?) {}
        })
        zoomRow.addView(zoomBar, LinearLayout.LayoutParams(0, WRAP, 1f))
        bottom.addView(zoomRow, LinearLayout.LayoutParams(MATCH, WRAP))

        val row = LinearLayout(this)
        row.orientation = LinearLayout.HORIZONTAL
        fun add(b: Button) {
            val lp = LinearLayout.LayoutParams(0, WRAP, 1f)
            lp.setMargins(dp(3), dp(3), dp(3), dp(3))
            row.addView(b, lp)
        }
        add(btn("START CAMERA", GREEN) { clickStart() })
        add(btn("STOP CAMERA", RED) { clickStop() })
        add(btn("RECONNECT", BLUE) { onReconnect() })
        add(btn("SETTINGS", GRAY) {
            settingsPanel.visibility = if (settingsPanel.visibility == View.VISIBLE) View.GONE else View.VISIBLE
        })
        add(btn("DEPAN/BELAKANG", GRAY) { flip() })
        add(btn("SENTER", GRAY) { torch() })
        bottom.addView(row, LinearLayout.LayoutParams(MATCH, WRAP))

        root.addView(bottom, FrameLayout.LayoutParams(MATCH, WRAP, Gravity.BOTTOM))
        return root
    }
}
