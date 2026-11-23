package com.example.gamepadhid.theme

import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.WindowCompat
import com.example.gamepadhid.R
import com.example.gamepadhid.hardware.HwActions
import com.example.gamepadhid.mapping.ControlIds
import com.example.gamepadhid.ui.Fx
import com.example.gamepadhid.ui.applySystemBarsPadding

/**
 * PERSONALIZACIÓN: colores base, imagen propia para el fondo de cada botón y un sonido propio para cada uno.
 * Al subir una imagen, androidx.palette extrae su color dominante y pasa a ser el acento del Lobby, el mando y la app.
 */
class CustomizationActivity : AppCompatActivity() {

    private lateinit var swatches: LinearLayout
    private lateinit var tvSource: TextView
    private lateinit var list: LinearLayout
    private var pending: String? = null

    private val pickImage = registerForActivityResult(ActivityResultContracts.GetContent()) { uri ->
        val id = pending
        pending = null
        if (uri != null && id != null) onImage(id, uri)
    }
    private val pickSound = registerForActivityResult(ActivityResultContracts.GetContent()) { uri ->
        val id = pending
        pending = null
        if (uri != null && id != null) onSound(id, uri)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        WindowCompat.setDecorFitsSystemWindows(window, false)
        setContentView(R.layout.activity_customization)
        findViewById<View>(R.id.root).applySystemBarsPadding()
        SoundBank.init(this)

        swatches = findViewById(R.id.swatchRow)
        tvSource = findViewById(R.id.tvThemeSource)
        list = findViewById(R.id.buttonList)
        findViewById<View>(R.id.btnBack).setOnClickListener { finish() }
        findViewById<View>(R.id.btnResetTheme).setOnClickListener {
            AppTheme.reset(this)
            refreshAll()
        }
        Fx.attachPress(findViewById<View>(R.id.btnResetTheme))
        refreshAll()
    }

    private fun refreshAll() {
        ThemeApplier.apply(this)
        tvSource.text = AppTheme.sourceLabel(this)
        buildSwatches()
        buildRows()
    }

    private fun buildSwatches() {
        swatches.removeAllViews()
        val density = resources.displayMetrics.density
        for (preset in Preset.values()) {
            val dot = View(this)
            val size = (46 * density).toInt()
            dot.layoutParams = LinearLayout.LayoutParams(size, size).apply { setMargins((6 * density).toInt(), 0, (6 * density).toInt(), 0) }
            dot.background = GradientDrawable(GradientDrawable.Orientation.TL_BR, intArrayOf(preset.c1, preset.c2)).apply {
                shape = GradientDrawable.OVAL
                setStroke((2 * density).toInt(), 0x66FFFFFF)
            }
            dot.contentDescription = getString(preset.nameRes)
            dot.setOnClickListener {
                AppTheme.setPreset(this, preset)
                refreshAll()
            }
            swatches.addView(dot)
        }
    }

    private fun buildRows() {
        list.removeAllViews()
        for (id in HwActions.buttons) {
            val row = LayoutInflater.from(this).inflate(R.layout.item_custom_button, list, false)
            row.findViewById<TextView>(R.id.cbLabel).text = HwActions.label(this, id)
            val skin = ButtonSkins.load(this, id)
            val preview = row.findViewById<ImageView>(R.id.cbSkin)
            preview.setImageBitmap(skin)
            row.findViewById<TextView>(R.id.cbState).text = getString(
                if (SoundBank.has(this, id)) R.string.cust_sound_set else R.string.cust_sound_none
            )
            row.findViewById<View>(R.id.cbImage).setOnClickListener { pending = id; pickImage.launch("image/*") }
            row.findViewById<View>(R.id.cbSound).setOnClickListener { pending = id; pickSound.launch("audio/*") }
            row.findViewById<View>(R.id.cbClear).setOnClickListener {
                ButtonSkins.remove(this, id)
                SoundBank.remove(this, id)
                buildRows()
            }
            listOf(R.id.cbImage, R.id.cbSound, R.id.cbClear).forEach { Fx.attachPress(row.findViewById<View>(it)) }
            list.addView(row)
        }
    }

    private fun onImage(id: String, uri: android.net.Uri) {
        val bitmap = ButtonSkins.save(this, id, uri)
        if (bitmap == null) {
            Toast.makeText(this, R.string.cust_image_bad, Toast.LENGTH_LONG).show()
            return
        }
        // El color dominante de la imagen pasa a ser el acento de todo: Lobby, mando y app
        AppTheme.setFromImage(this, PaletteExtractor.accentFrom(bitmap))
        Toast.makeText(this, R.string.cust_palette_applied, Toast.LENGTH_SHORT).show()
        refreshAll()
    }

    private fun onSound(id: String, uri: android.net.Uri) {
        val error = SoundBank.save(this, id, uri)
        if (error != null) {
            Toast.makeText(this, error, Toast.LENGTH_LONG).show()
            return
        }
        // Para oírlo enseguida: el SoundPool tarda un instante en cargarlo
        list.postDelayed({ SoundBank.play(id) }, 400)
        buildRows()
    }
}
