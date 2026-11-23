package com.example.gamepadhid.tvsearch

import android.content.ActivityNotFoundException
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.widget.ArrayAdapter
import android.widget.LinearLayout
import android.widget.Spinner
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.WindowCompat
import com.example.gamepadhid.R
import com.example.gamepadhid.ui.Fx
import com.example.gamepadhid.ui.applySystemBarsPadding
import com.google.android.material.textfield.TextInputEditText
import org.json.JSONObject

/**
 * BUSCADOR EXPERTO. El usuario elige la marca de su TV y, si quiere, el modelo (ej. AU8000). Lee
 * assets/tv_compatibility.json (15 marcas) y muestra qué métodos probar, los pasos y las apps oficiales
 * (el botón abre la búsqueda en Google Play por nombre, con alternativa web). Todo es orientativo.
 */
class ExpertTvSearchActivity : AppCompatActivity() {

    private class StoreApp(val name: String, val query: String)
    private class ModelHint(val pattern: String, val note: String)
    private class Brand(
        val id: String, val name: String, val protocols: List<String>, val recommendation: String,
        val steps: List<String>, val apps: List<StoreApp>, val models: List<ModelHint>
    )

    private var brands: List<Brand> = emptyList()
    private lateinit var spBrand: Spinner
    private lateinit var etModel: TextInputEditText

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        WindowCompat.setDecorFitsSystemWindows(window, false)
        setContentView(R.layout.activity_expert_search)
        com.example.gamepadhid.theme.ThemeApplier.apply(this)
        findViewById<View>(R.id.root).applySystemBarsPadding()

        spBrand = findViewById(R.id.spBrand)
        etModel = findViewById(R.id.etModel)

        brands = loadBrands()
        if (brands.isEmpty()) {
            Toast.makeText(this, R.string.expert_load_error, Toast.LENGTH_LONG).show()
        }
        spBrand.adapter = ArrayAdapter(this, android.R.layout.simple_spinner_dropdown_item, brands.map { it.name })

        findViewById<View>(R.id.btnBack).setOnClickListener { finish() }
        findViewById<View>(R.id.btnExpertBack).setOnClickListener { finish() }
        findViewById<View>(R.id.btnSearch).setOnClickListener { showResult() }
        listOf(R.id.btnSearch, R.id.btnExpertBack).forEach { Fx.attachPress(findViewById<View>(it)) }
    }

    private fun loadBrands(): List<Brand> = try {
        val text = assets.open("tv_compatibility.json").bufferedReader().use { it.readText() }
        val arr = JSONObject(text).getJSONArray("brands")
        (0 until arr.length()).map { i ->
            val b = arr.getJSONObject(i)
            Brand(
                id = b.getString("id"),
                name = b.getString("name"),
                protocols = strings(b.getJSONArray("protocols")),
                recommendation = b.getString("recommendation"),
                steps = strings(b.getJSONArray("steps")),
                apps = b.optJSONArray("apps")?.let { a ->
                    (0 until a.length()).map { StoreApp(a.getJSONObject(it).getString("name"), a.getJSONObject(it).getString("query")) }
                } ?: emptyList(),
                models = b.optJSONArray("models")?.let { a ->
                    (0 until a.length()).map { ModelHint(a.getJSONObject(it).getString("pattern"), a.getJSONObject(it).getString("note")) }
                } ?: emptyList()
            )
        }
    } catch (e: Exception) {
        emptyList()
    }

    private fun strings(a: org.json.JSONArray) = (0 until a.length()).map { a.getString(it) }

    private fun protocolLabel(key: String) = getString(
        when (key) {
            "cast" -> R.string.proto_cast
            "miracast" -> R.string.proto_miracast
            else -> R.string.proto_dlna
        }
    )

    private fun showResult() {
        val brand = brands.getOrNull(spBrand.selectedItemPosition) ?: return
        val model = etModel.text?.toString()?.trim().orEmpty()

        findViewById<TextView>(R.id.exBrandName).text = brand.name
        findViewById<TextView>(R.id.exProtocols).text =
            getString(R.string.expert_protocols, brand.protocols.joinToString(" → ") { protocolLabel(it) })
        findViewById<TextView>(R.id.exRecommendation).text = brand.recommendation

        // Pistas por modelo (expresiones regulares del JSON; una mal escrita se ignora)
        val notes = if (model.isEmpty()) emptyList() else brand.models.filter {
            try { Regex(it.pattern, RegexOption.IGNORE_CASE).containsMatchIn(model) } catch (e: Exception) { false }
        }.map { it.note }
        val notesView = findViewById<TextView>(R.id.exModelNotes)
        if (notes.isEmpty()) {
            notesView.visibility = View.GONE
        } else {
            notesView.visibility = View.VISIBLE
            notesView.text = getString(R.string.expert_model_notes) + "\n" + notes.joinToString("\n") { "• $it" }
        }

        findViewById<TextView>(R.id.exSteps).text = getString(R.string.expert_steps) + "\n" +
            brand.steps.withIndex().joinToString("\n") { "${it.index + 1}. ${it.value}" }

        val appsBox = findViewById<LinearLayout>(R.id.exApps)
        appsBox.removeAllViews()
        for (app in brand.apps) {
            val button = LayoutInflater.from(this).inflate(R.layout.item_store_button, appsBox, false) as TextView
            button.text = getString(R.string.expert_open_store, app.name)
            button.setOnClickListener { openStore(app.query) }
            Fx.attachPress(button)
            appsBox.addView(button)
        }
        findViewById<View>(R.id.exResult).visibility = View.VISIBLE
    }

    /** Abre la búsqueda en Google Play; sin Play, en el navegador. */
    private fun openStore(query: String) {
        val q = Uri.encode(query)
        try {
            startActivity(Intent(Intent.ACTION_VIEW, Uri.parse("market://search?q=$q&c=apps")))
        } catch (e: ActivityNotFoundException) {
            try {
                startActivity(Intent(Intent.ACTION_VIEW, Uri.parse("https://play.google.com/store/search?q=$q&c=apps")))
            } catch (e2: ActivityNotFoundException) {
                Toast.makeText(this, R.string.toast_open_cast_failed, Toast.LENGTH_LONG).show()
            }
        }
    }
}
