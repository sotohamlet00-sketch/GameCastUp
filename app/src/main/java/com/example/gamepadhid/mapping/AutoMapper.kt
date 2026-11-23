package com.example.gamepadhid.mapping

import android.graphics.Bitmap
import android.os.Handler
import android.os.Looper
import android.view.PixelCopy
import android.view.SurfaceView
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.latin.TextRecognizerOptions
import kotlin.math.sqrt

/**
 * Detección automática con ML Kit (reconocimiento de texto, modelo incluido).
 * No existe un detector de "controles de juego": se buscan rótulos y se elige el
 * candidato más cercano a la posición actual. Lo no detectado no se toca.
 */
object AutoMapper {

    data class Suggestion(val id: String, val x: Float, val y: Float)

    private data class Cand(val x: Float, val y: Float)

    /** Rótulo normalizado (MAYÚSCULAS, sin símbolos) → control. */
    private val DICTIONARY: Map<String, String> = mapOf(
        "A" to ControlIds.A, "B" to ControlIds.B, "X" to ControlIds.X, "Y" to ControlIds.Y,
        "LB" to ControlIds.L1, "L1" to ControlIds.L1, "RB" to ControlIds.R1, "R1" to ControlIds.R1,
        "LT" to ControlIds.L2, "L2" to ControlIds.L2, "RT" to ControlIds.R2, "R2" to ControlIds.R2,
        "START" to ControlIds.START, "PAUSA" to ControlIds.START, "PAUSE" to ControlIds.START,
        "MENU" to ControlIds.START, "SELECT" to ControlIds.SELECT,
        "SALTAR" to ControlIds.A, "SALTO" to ControlIds.A, "JUMP" to ControlIds.A,
        "ATACAR" to ControlIds.X, "ATAQUE" to ControlIds.X, "ATTACK" to ControlIds.X,
        "DASH" to ControlIds.B, "ESQUIVAR" to ControlIds.B,
        "DISPARAR" to ControlIds.Y, "SHOOT" to ControlIds.Y
    )

    private val NON_ALNUM = Regex("[^A-Z0-9ÁÉÍÓÚÑ]")

    /** Copia un fotograma de la vista previa (PixelCopy, API 24+). */
    fun captureFrame(view: SurfaceView, onResult: (Bitmap?) -> Unit) {
        val w = view.width
        val h = view.height
        if (w <= 0 || h <= 0 || !view.holder.surface.isValid) { onResult(null); return }
        val bmp = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        try {
            PixelCopy.request(view, bmp, { code ->
                onResult(if (code == PixelCopy.SUCCESS) bmp else null)
            }, Handler(Looper.getMainLooper()))
        } catch (e: Exception) {
            onResult(null)
        }
    }

    /** Resultado en el hilo principal. Las coordenadas devueltas son fracciones 0..1. */
    fun detect(bitmap: Bitmap, current: GameMapping, onDone: (Result<List<Suggestion>>) -> Unit) {
        val recognizer = TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS)
        val bw = bitmap.width.toFloat()
        val bh = bitmap.height.toFloat()
        val allowed = ControlIds.idsFor(current.layout).toSet()

        recognizer.process(InputImage.fromBitmap(bitmap, 0))
            .addOnSuccessListener { text ->
                val cands = HashMap<String, MutableList<Cand>>()

                fun consider(raw: String, box: android.graphics.Rect?) {
                    box ?: return
                    val norm = NON_ALNUM.replace(raw.uppercase(), "")
                    val id = DICTIONARY[norm] ?: return
                    if (id !in allowed) return
                    // Un rótulo de control es pequeño; descarta texto grande (títulos).
                    if (box.height() > bh * 0.14f) return
                    val c = Cand(box.exactCenterX() / bw, box.exactCenterY() / bh)
                    cands.getOrPut(id) { mutableListOf() }.add(c)
                }

                for (block in text.textBlocks) for (line in block.lines) {
                    consider(line.text, line.boundingBox)
                    for (el in line.elements) consider(el.text, el.boundingBox)
                }

                val out = mutableListOf<Suggestion>()
                for ((id, list) in cands) {
                    val cur = current.controls[id] ?: continue
                    val best = list.minByOrNull { dist(it.x, it.y, cur.x, cur.y) } ?: continue
                    // Letras sueltas (A/B/X/Y) se confunden con texto normal: exige cercanía.
                    val isSingle = id in listOf(ControlIds.A, ControlIds.B, ControlIds.X, ControlIds.Y)
                    if (isSingle && dist(best.x, best.y, cur.x, cur.y) > 0.30f) continue
                    out.add(Suggestion(id, best.x.coerceIn(0f, 1f), best.y.coerceIn(0f, 1f)))
                }
                onDone(Result.success(out))
            }
            .addOnFailureListener { onDone(Result.failure(it)) }
            .addOnCompleteListener { recognizer.close() }
    }

    private fun dist(ax: Float, ay: Float, bx: Float, by: Float): Float {
        val dx = ax - bx
        val dy = ay - by
        return sqrt(dx * dx + dy * dy)
    }
}
