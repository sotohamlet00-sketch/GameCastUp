package com.example.gamepadhid

import android.Manifest
import android.bluetooth.BluetoothManager
import android.content.Intent
import android.os.Bundle
import android.view.View
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.WindowCompat
import com.example.gamepadhid.cast.CastCapabilities
import com.example.gamepadhid.hardware.HwActions
import com.example.gamepadhid.hardware.HwKeyConfigDialog
import com.example.gamepadhid.overlay.PackagePicker
import com.example.gamepadhid.transport.TransportKind
import com.example.gamepadhid.transport.TvConnectActivity
import com.example.gamepadhid.hid.PairingDialog
import com.example.gamepadhid.ui.Fx
import com.example.gamepadhid.ui.applySystemBarsPadding
import com.google.android.material.textfield.TextInputEditText
import com.google.android.material.textfield.TextInputLayout

/** Ajustes mínimos: nombre Bluetooth, botón de emparejar y selección de la TV de proyección. */
class SettingsActivity : AppCompatActivity() {

    private lateinit var tilName: TextInputLayout
    private lateinit var etName: TextInputEditText
    private lateinit var tvTvCurrent: TextView
    private var pendingName: String? = null

    // BLUETOOTH_CONNECT hace falta para cambiar el nombre del adaptador (Android 12+)
    private val permissionLauncher =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
            val name = pendingName
            pendingName = null
            if (granted && name != null) applyAdapterName(name) else toast(R.string.toast_name_no_permission)
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        WindowCompat.setDecorFitsSystemWindows(window, false)
        setContentView(R.layout.activity_settings)
        com.example.gamepadhid.theme.ThemeApplier.apply(this)
        findViewById<View>(R.id.root).applySystemBarsPadding()

        tilName = findViewById(R.id.tilName)
        etName = findViewById(R.id.etName)
        tvTvCurrent = findViewById(R.id.tvTvCurrent)

        etName.setText(Prefs.btName(this))
        renderTv()
        findViewById<TextView>(R.id.tvCapability).setText(
            when (CastCapabilities.check(this).level) {
                CastCapabilities.Level.SUPPORTED -> R.string.cap_supported
                CastCapabilities.Level.LIMITED -> R.string.cap_limited
                CastCapabilities.Level.UNSUPPORTED -> R.string.cap_unsupported
            }
        )

        findViewById<View>(R.id.btnBack).setOnClickListener { finish() }
        findViewById<View>(R.id.btnSave).setOnClickListener { saveName() }
        findViewById<View>(R.id.btnPair).setOnClickListener { PairingDialog.show(this) }
        findViewById<View>(R.id.btnPickTv).setOnClickListener {
            startActivity(Intent(this, TvConnectActivity::class.java))
        }
        findViewById<View>(R.id.btnCustomize).setOnClickListener {
            startActivity(Intent(this, com.example.gamepadhid.theme.CustomizationActivity::class.java))
        }
        findViewById<View>(R.id.btnHwGlobal).setOnClickListener {
            HwKeyConfigDialog(
                context = this,
                title = getString(R.string.hw_dialog_global_title),
                initial = Prefs.hwKeyMap(this),
                onSave = { map -> Prefs.setHwKeyMap(this, map) }
            ).show()
        }
        findViewById<View>(R.id.btnHwGame).setOnClickListener {
            // PackagePicker: lista con la portada (icono real) de cada app instalada
            PackagePicker.show(this) { app ->
                HwKeyConfigDialog(
                    context = this,
                    title = getString(R.string.hw_dialog_game_title, app.label),
                    initial = HwActions.effective(this, app.packageName),
                    neutralLabel = getString(R.string.hw_use_global),
                    onNeutral = { Prefs.setGameHwKeys(this, app.packageName, null) },
                    onSave = { map -> Prefs.setGameHwKeys(this, app.packageName, map) }
                ).show()
            }
        }
        listOf(R.id.btnSave, R.id.btnPair, R.id.btnPickTv, R.id.btnHwGlobal, R.id.btnHwGame, R.id.btnCustomize).forEach { Fx.attachPress(findViewById<View>(it)) }
    }

    override fun onResume() {
        super.onResume()
        renderTv() // por si se eligió otra TV en «Conectar con la TV»
    }

    private fun renderTv() {
        val name = Prefs.tvName(this)
        tvTvCurrent.text = if (name == null) {
            getString(R.string.tv_target, getString(R.string.tv_none))
        } else {
            val kind = getString(
                when (Prefs.tvKind(this)) {
                    TransportKind.MIRACAST -> R.string.tv_kind_miracast
                    TransportKind.CAST -> R.string.tv_kind_cast
                    TransportKind.DLNA -> R.string.tv_kind_dlna
                }
            )
            getString(R.string.tv_target_kind, name, kind)
        }
    }

    private fun saveName() {
        val name = etName.text?.toString()?.trim().orEmpty()
        if (name.isEmpty()) {
            tilName.error = getString(R.string.err_name_empty)
            return
        }
        tilName.error = null
        Prefs.setBtName(this, name)
        if (Permissions.hasBluetoothConnect(this)) {
            applyAdapterName(name)
        } else {
            pendingName = name
            permissionLauncher.launch(Manifest.permission.BLUETOOTH_CONNECT)
        }
    }

    /** Es el nombre que verá la TV al buscar dispositivos. Cambia el nombre Bluetooth del teléfono. */
    private fun applyAdapterName(name: String) {
        val adapter = getSystemService(BluetoothManager::class.java)?.adapter
        val ok = try {
            adapter?.setName(name) == true
        } catch (e: SecurityException) {
            false
        }
        toast(if (ok) R.string.toast_name_saved else R.string.toast_name_saved_partial)
    }

    private fun toast(resId: Int) = Toast.makeText(this, resId, Toast.LENGTH_LONG).show()
}
