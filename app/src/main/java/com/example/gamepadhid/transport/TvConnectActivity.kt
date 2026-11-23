package com.example.gamepadhid.transport

import android.content.Intent
import android.content.res.ColorStateList
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.LayoutInflater
import android.view.View
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.core.view.ViewCompat
import androidx.core.view.WindowCompat
import com.example.gamepadhid.Prefs
import com.example.gamepadhid.R
import com.example.gamepadhid.cast.CastNav
import com.example.gamepadhid.cast.CastTarget
import com.example.gamepadhid.cast.DisplayScanner
import com.example.gamepadhid.tvsearch.ExpertTvSearchActivity
import com.example.gamepadhid.ui.Fx
import com.example.gamepadhid.ui.applySystemBarsPadding

/**
 * CONECTAR CON LA TV. Busca a la vez por las tres vías:
 *  - Miracast: pantallas externas que el sistema ya conoce (la TV nueva se conecta desde el panel Cast del sistema,
 *    porque una app normal no puede buscar ni conectar Miracast).
 *  - Google Cast: MediaRouter + SDK oficial.
 *  - DLNA/UPnP: SSDP nativo.
 * Al elegir una TV se guarda (Prefs.setTvDevice) y la pantalla devuelve RESULT_OK. Si en ~12 s no aparece nada
 * se ofrece el Buscador experto.
 */
class TvConnectActivity : AppCompatActivity() {

    private lateinit var list: LinearLayout
    private lateinit var tvStatus: TextView
    private lateinit var btnExpert: View
    private lateinit var scanner: DisplayScanner
    private lateinit var castController: GoogleCastController
    private lateinit var dlnaController: DlnaController
    private val handler = Handler(Looper.getMainLooper())

    private var miracast: List<CastTarget> = emptyList()
    private var cast: List<TvDevice> = emptyList()
    private var dlna: List<TvDevice> = emptyList()
    private var castAvailable = true

    private val showExpert = Runnable { btnExpert.visibility = View.VISIBLE }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        WindowCompat.setDecorFitsSystemWindows(window, false)
        setContentView(R.layout.activity_tv_connect)
        com.example.gamepadhid.theme.ThemeApplier.apply(this)
        findViewById<View>(R.id.root).applySystemBarsPadding()

        list = findViewById(R.id.tvcList)
        tvStatus = findViewById(R.id.tvcStatus)
        btnExpert = findViewById(R.id.btnExpert)
        castController = GoogleCastController(this)
        dlnaController = DlnaController(this)
        scanner = DisplayScanner(this) { targets ->
            miracast = targets
            render()
        }

        findViewById<View>(R.id.btnBack).setOnClickListener { finish() }
        findViewById<View>(R.id.btnOpenCast).setOnClickListener { openCastPanel() }
        btnExpert.setOnClickListener { startActivity(Intent(this, ExpertTvSearchActivity::class.java)) }
        listOf(R.id.btnOpenCast, R.id.btnExpert).forEach { Fx.attachPress(findViewById<View>(it)) }
    }

    override fun onStart() {
        super.onStart()
        scanner.start()
        castAvailable = castController.startDiscovery { cast = it; render() }
        dlnaController.startDiscovery { dlna = it; render() }
        handler.postDelayed(showExpert, 12_000)
        render()
    }

    override fun onStop() {
        handler.removeCallbacks(showExpert)
        scanner.stop()
        castController.stopDiscovery()
        dlnaController.stopDiscovery()
        super.onStop()
    }

    // ------------------------------------------------------------------ Pintar

    private fun render() {
        list.removeAllViews()
        val total = miracast.size + cast.size + dlna.size
        tvStatus.text = if (total == 0) getString(R.string.tvc_searching) else getString(R.string.tvc_found, total)

        section(R.string.tvc_sec_miracast)
        if (miracast.isEmpty()) note(R.string.tvc_miracast_hint)
        for (t in miracast) {
            val detail = if (t.connected) getString(R.string.tvc_connected) else getString(R.string.tvc_not_connected)
            row(t.name, detail, t.connected) { pickMiracast(t) }
        }

        section(R.string.tvc_sec_cast)
        if (!castAvailable) note(R.string.tvc_cast_unavailable)
        else if (cast.isEmpty()) note(R.string.tvc_none)
        for (d in cast) row(d.name, d.detail, true) { pick(d) }

        section(R.string.tvc_sec_dlna)
        if (dlna.isEmpty()) note(R.string.tvc_none)
        for (d in dlna) row(d.name, d.detail, true) { pick(d) }
    }

    private fun section(titleRes: Int) {
        val v = TextView(this)
        v.setText(titleRes)
        v.setTextAppearance(R.style.TextAppearance_GC_Title)
        v.setPadding(dp(20), dp(12), dp(20), dp(4))
        list.addView(v)
    }

    private fun note(textRes: Int) {
        val v = TextView(this)
        v.setText(textRes)
        v.setTextAppearance(R.style.TextAppearance_GC_Caption)
        v.setPadding(dp(20), dp(4), dp(20), dp(8))
        list.addView(v)
    }

    private fun row(name: String, detail: String, active: Boolean, onClick: () -> Unit) {
        val v = LayoutInflater.from(this).inflate(R.layout.item_target, list, false)
        v.findViewById<TextView>(R.id.targetName).text = name
        v.findViewById<TextView>(R.id.targetDetail).text = detail
        val color = ContextCompat.getColor(this, if (active) R.color.gc_secondary else R.color.gc_text_muted)
        ViewCompat.setBackgroundTintList(v.findViewById(R.id.targetDot), ColorStateList.valueOf(color))
        val attrs = obtainStyledAttributes(intArrayOf(android.R.attr.selectableItemBackground))
        v.foreground = attrs.getDrawable(0)
        attrs.recycle()
        v.setOnClickListener { onClick() }
        list.addView(v)
    }

    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()

    // ------------------------------------------------------------------ Elegir

    private fun pickMiracast(target: CastTarget) {
        when {
            target.connected -> pick(TvDevice(TransportKind.MIRACAST, target.name, target.name))
            scanner.tryConnect(target) -> Toast.makeText(this, R.string.toast_connect_tried, Toast.LENGTH_LONG).show()
            else -> openCastPanel()
        }
    }

    private fun pick(device: TvDevice) {
        Prefs.setTvDevice(this, device)
        Toast.makeText(this, getString(R.string.tvc_picked, device.name), Toast.LENGTH_SHORT).show()
        setResult(RESULT_OK)
        finish()
    }

    private fun openCastPanel() {
        if (!CastNav.openCastSettings(this)) {
            Toast.makeText(this, R.string.toast_open_cast_failed, Toast.LENGTH_LONG).show()
        }
    }
}
