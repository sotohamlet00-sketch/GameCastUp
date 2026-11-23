package com.example.gamepadhid.overlay

import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.MotionEvent
import android.view.SurfaceHolder
import android.view.SurfaceView
import android.view.View
import android.view.WindowManager
import android.widget.TextView
import android.widget.Toast
import androidx.activity.OnBackPressedCallback
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import com.example.gamepadhid.Prefs
import com.example.gamepadhid.R
import com.example.gamepadhid.console.ConsoleService
import com.example.gamepadhid.console.ConsoleSession
import com.example.gamepadhid.hardware.HwActions
import com.example.gamepadhid.hardware.HwKeyConfigDialog
import com.example.gamepadhid.mapping.AutoMapper
import com.example.gamepadhid.mapping.ControlProfile
import com.example.gamepadhid.mapping.ControlIds
import com.example.gamepadhid.mapping.GameMapping
import com.example.gamepadhid.mapping.MappingStore
import com.example.gamepadhid.mapping.PadLayout
import com.example.gamepadhid.ui.AspectFrameLayout
import com.example.gamepadhid.ui.Fx
import com.example.gamepadhid.ui.applySystemBarsPadding
import com.example.gamepadhid.virtual.GameVirtualDisplay
import com.example.gamepadhid.virtual.TouchInjectorShizuku

/**
 * MAPEO OBLIGATORIO (fase MAPPING). No crea pantalla virtual: se engancha a la de la sesión con
 * GameVirtualDisplay.setSurface(preview) (la TV queda en pausa) y devuelve ConsoleSession.tvSurface al
 * terminar o si su Surface se destruye. «Guardar y Jugar» es la única vía a RUNNING.
 */
class ControlMapperActivity : AppCompatActivity(), SurfaceHolder.Callback {

    companion object {
        private const val PLAY_POINTER_ID = 20
    }

    private lateinit var bar: View
    private lateinit var surfaceView: SurfaceView
    private lateinit var mapper: MapperView
    private lateinit var tvStatus: TextView
    private lateinit var tvGame: TextView
    private lateinit var btnMode: TextView
    private lateinit var btnLayout: TextView
    private lateinit var mapping: GameMapping
    private var packageName = ""
    private var attached = false
    private var closing = false
    private var autoBusy = false
    private var autoScheduled = false
    private val handler = Handler(Looper.getMainLooper())

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val pkg = ConsoleSession.gamePackage
        if (ConsoleSession.phase != ConsoleSession.Phase.MAPPING || pkg == null || GameVirtualDisplay.displayId < 0) {
            Toast.makeText(this, R.string.mapper_no_session, Toast.LENGTH_LONG).show()
            closing = true
            finish()
            return
        }
        packageName = pkg
        mapping = MappingStore.loadOrDefault(this, pkg)

        WindowCompat.setDecorFitsSystemWindows(window, false)
        setContentView(R.layout.activity_mapper)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        WindowInsetsControllerCompat(window, window.decorView).apply {
            systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
            hide(WindowInsetsCompat.Type.systemBars())
        }

        bar = findViewById(R.id.bar)
        surfaceView = findViewById(R.id.previewSurface)
        mapper = findViewById(R.id.mapper)
        tvStatus = findViewById(R.id.tvMapperStatus)
        tvGame = findViewById(R.id.tvMapperGame)
        btnMode = findViewById(R.id.btnMapperMode)
        btnLayout = findViewById(R.id.btnMapperLayout)
        bar.applySystemBarsPadding(extraDp = 4)

        // Proporción exacta de la pantalla virtual: los marcadores son fracciones de ella
        val w = GameVirtualDisplay.width
        val h = GameVirtualDisplay.height
        findViewById<AspectFrameLayout>(R.id.stage).aspect = w.toFloat() / h
        surfaceView.holder.setFixedSize(w, h)
        surfaceView.holder.addCallback(this)

        mapper.mapping = mapping
        mapper.onPlayTouch = { action, fx, fy -> playTouch(action, fx * w, fy * h) }
        tvGame.text = PackagePicker.labelOf(this, pkg)
        tvStatus.setText(R.string.mapper_ready)
        updateTexts()

        btnMode.setOnClickListener { toggleMode() }
        btnLayout.setOnClickListener { chooseLayout() }
        findViewById<View>(R.id.btnMapperAuto).setOnClickListener { autoDetect() }
        findViewById<View>(R.id.btnMapperHw).setOnClickListener { configureHwKeys() }
        findViewById<View>(R.id.btnMapperSmaller).setOnClickListener { resizeSelected(-1) }
        findViewById<View>(R.id.btnMapperBigger).setOnClickListener { resizeSelected(+1) }
        findViewById<View>(R.id.btnMapperSavePlay).setOnClickListener { saveAndPlay() }
        findViewById<View>(R.id.btnMapperCancel).setOnClickListener { confirmCancel() }
        findViewById<View>(R.id.btnMapperBarToggle).setOnClickListener {
            bar.visibility = if (bar.visibility == View.VISIBLE) View.GONE else View.VISIBLE
        }
        listOf(
            R.id.btnMapperMode, R.id.btnMapperLayout, R.id.btnMapperAuto, R.id.btnMapperHw, R.id.btnMapperSmaller,
            R.id.btnMapperBigger, R.id.btnMapperSavePlay, R.id.btnMapperCancel
        ).forEach { Fx.attachPress(findViewById<View>(it)) }

        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() = confirmCancel()
        })
    }

    // ------------------------------------------------------------------ Surface

    override fun surfaceCreated(holder: SurfaceHolder) {
        if (closing) return
        GameVirtualDisplay.setSurface(holder.surface)
        attached = true
        // Perfil Automático sin mapa guardado: ML Kit lo intenta solo cuando el juego ya dibujó algo
        if (!autoScheduled && ConsoleSession.profile == ControlProfile.AUTO && !MappingStore.has(this, packageName)) {
            autoScheduled = true
            tvStatus.setText(R.string.mapper_auto_working)
            handler.postDelayed({ if (!closing) autoDetect() }, 2500)
        }
    }

    override fun surfaceChanged(holder: SurfaceHolder, format: Int, width: Int, height: Int) {}

    override fun surfaceDestroyed(holder: SurfaceHolder) = returnSurfaceToTv()

    private fun returnSurfaceToTv() {
        if (!attached) return
        attached = false
        ConsoleSession.tvSurface?.let { GameVirtualDisplay.setSurface(it) }
    }

    // ------------------------------------------------------------------ Edición

    private fun playTouch(action: Int, x: Float, y: Float) {
        val id = GameVirtualDisplay.displayId
        if (id < 0) return
        when (action) {
            MotionEvent.ACTION_DOWN -> TouchInjectorShizuku.holdDown(id, x, y, PLAY_POINTER_ID)
            MotionEvent.ACTION_MOVE -> TouchInjectorShizuku.moveTo(id, x, y, PLAY_POINTER_ID)
            else -> TouchInjectorShizuku.release(id, PLAY_POINTER_ID)
        }
    }

    private fun toggleMode() {
        mapper.mode = if (mapper.mode == MapperView.Mode.PLACE) MapperView.Mode.PLAY else MapperView.Mode.PLACE
        updateTexts()
    }

    private fun layoutName(l: PadLayout) = getString(
        when (l) {
            PadLayout.XBOX -> R.string.layout_xbox
            PadLayout.PSP -> R.string.layout_psp
            PadLayout.DS -> R.string.layout_ds
        }
    )

    /** Qué hace cada botón físico (Volumen, Encendido) solo con ESTA app. «Usar los globales» borra los suyos. */
    private fun configureHwKeys() {
        val title = getString(R.string.hw_dialog_game_title, PackagePicker.labelOf(this, packageName))
        HwKeyConfigDialog(
            context = this,
            title = title,
            initial = HwActions.effective(this, packageName),
            neutralLabel = getString(R.string.hw_use_global),
            onNeutral = { Prefs.setGameHwKeys(this, packageName, null) },
            onSave = { map -> Prefs.setGameHwKeys(this, packageName, map) }
        ).show()
    }

    private fun updateTexts() {
        btnMode.setText(if (mapper.mode == MapperView.Mode.PLACE) R.string.mapper_mode_place else R.string.mapper_mode_play)
        btnLayout.text = layoutName(mapping.layout)
    }

    private fun chooseLayout() {
        val all = PadLayout.values()
        AlertDialog.Builder(this)
            .setTitle(R.string.mapper_layout_title)
            .setSingleChoiceItems(all.map { layoutName(it) }.toTypedArray(), mapping.layout.ordinal) { d, which ->
                mapping.switchLayout(all[which])
                mapper.selectedId = null
                mapper.mapping = mapping
                updateTexts()
                d.dismiss()
            }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    private fun autoDetect() {
        if (autoBusy) return
        autoBusy = true
        tvStatus.setText(R.string.mapper_auto_working)
        AutoMapper.captureFrame(surfaceView) { bmp ->
            if (bmp == null) {
                autoBusy = false
                tvStatus.setText(R.string.mapper_auto_error)
                return@captureFrame
            }
            AutoMapper.detect(bmp, mapping) { res ->
                autoBusy = false
                bmp.recycle()
                res.onSuccess { list ->
                    for (s in list) mapping.controls[s.id]?.let { it.x = s.x; it.y = s.y }
                    mapper.mapping = mapping
                    tvStatus.text = if (list.isEmpty()) getString(R.string.mapper_auto_none)
                    else getString(R.string.mapper_auto_found, list.size)
                }.onFailure { tvStatus.setText(R.string.mapper_auto_error) }
            }
        }
    }

    /** «+» / «−»: tamaño del control (en el mousepad escala ancho y alto). */
    private fun resizeSelected(dir: Int) {
        val id = mapper.selectedId
        val p = id?.let { mapping.controls[it] }
        if (id == null || p == null) {
            Toast.makeText(this, R.string.mapper_select_first, Toast.LENGTH_SHORT).show()
            return
        }
        if (ControlIds.kind(id) == ControlIds.Kind.MOUSEPAD) {
            val f = if (dir > 0) 1.08f else 1f / 1.08f
            p.w = (p.w * f).coerceIn(0.10f, 0.95f)
            p.h = (p.h * f).coerceIn(0.10f, 0.95f)
        } else {
            p.size = (p.size + dir * 0.01f).coerceIn(0.03f, 0.20f)
        }
        mapper.invalidate()
    }

    // ------------------------------------------------------------------ Guardar y jugar / cancelar

    private fun saveAndPlay() {
        if (closing) return
        closing = true
        MappingStore.save(this, mapping)
        ConsoleSession.confirmMapping()   // única vía a RUNNING
        returnSurfaceToTv()
        FloatingPadService.start(this)
        moveTaskToBack(true)              // el mando queda sobre la pantalla del teléfono
        finish()
    }

    private fun confirmCancel() {
        AlertDialog.Builder(this)
            .setTitle(R.string.mapper_cancel_title)
            .setMessage(R.string.mapper_cancel_msg)
            .setPositiveButton(R.string.mapper_cancel_yes) { _, _ ->
                closing = true
                returnSurfaceToTv()
                ConsoleService.goHome(this)
                finish()
            }
            .setNegativeButton(R.string.mapper_cancel_no, null)
            .show()
    }

    override fun onDestroy() {
        handler.removeCallbacksAndMessages(null)
        if (isFinishing) returnSurfaceToTv()
        super.onDestroy()
    }
}
