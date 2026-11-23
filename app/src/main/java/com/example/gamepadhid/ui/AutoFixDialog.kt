package com.example.gamepadhid.ui

import android.animation.ObjectAnimator
import android.animation.ValueAnimator
import android.app.Activity
import android.os.Handler
import android.os.Looper
import android.view.LayoutInflater
import android.view.View
import android.widget.TextView
import androidx.appcompat.app.AlertDialog
import com.example.gamepadhid.R

/** «Optimizando GameCast para tu dispositivo…»: un orbe que late y frases que cambian mientras trabaja el Auto-Fix. */
class AutoFixDialog(private val activity: Activity) {

    private var dialog: AlertDialog? = null
    private val animators = ArrayList<ObjectAnimator>()
    private val handler = Handler(Looper.getMainLooper())
    private var shownAt = 0L

    fun show() {
        val v = LayoutInflater.from(activity).inflate(R.layout.dialog_autofix, null)
        val orb = v.findViewById<View>(R.id.afOrb)
        val ring = v.findViewById<View>(R.id.afRing)
        val step = v.findViewById<TextView>(R.id.afStep)

        fun pulse(target: View, from: Float, to: Float, duration: Long) {
            for (prop in arrayOf(View.SCALE_X, View.SCALE_Y)) {
                animators.add(ObjectAnimator.ofFloat(target, prop, from, to).apply {
                    this.duration = duration
                    repeatCount = ValueAnimator.INFINITE
                    repeatMode = ValueAnimator.REVERSE
                    start()
                })
            }
        }
        pulse(orb, 0.9f, 1.12f, 700)
        pulse(ring, 1f, 1.45f, 1100)
        animators.add(ObjectAnimator.ofFloat(ring, View.ALPHA, 0.7f, 0f).apply {
            duration = 1100; repeatCount = ValueAnimator.INFINITE; start()
        })

        val steps = intArrayOf(R.string.autofix_step1, R.string.autofix_step2, R.string.autofix_step3)
        var i = 0
        val rotate = object : Runnable {
            override fun run() {
                step.setText(steps[i % steps.size])
                i++
                handler.postDelayed(this, 900)
            }
        }
        handler.post(rotate)

        dialog = AlertDialog.Builder(activity).setView(v).setCancelable(false).create().also {
            it.show()
            it.window?.setBackgroundDrawableResource(android.R.color.transparent)
        }
        shownAt = System.currentTimeMillis()
    }

    /** Cierra la animación; si fue demasiado rápida se deja un mínimo para que se vea. */
    fun dismiss(minShownMs: Long = 1400, onDone: (() -> Unit)? = null) {
        val wait = (minShownMs - (System.currentTimeMillis() - shownAt)).coerceAtLeast(0)
        handler.postDelayed({
            handler.removeCallbacksAndMessages(null)
            animators.forEach { it.cancel() }
            animators.clear()
            try { dialog?.dismiss() } catch (e: IllegalArgumentException) { /* actividad cerrada */ }
            dialog = null
            onDone?.invoke()
        }, wait)
    }
}
