package com.example.gamepadhid

import android.content.Intent
import android.os.Bundle
import android.view.WindowManager
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import com.example.gamepadhid.theme.AppTheme
import com.example.gamepadhid.ui.SplashView

/** Pantalla de carga: el teléfono se transforma en gamepad, late y da paso a la pantalla principal. */
class SplashActivity : AppCompatActivity() {

    private var done = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        WindowCompat.setDecorFitsSystemWindows(window, false)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        WindowInsetsControllerCompat(window, window.decorView).hide(WindowInsetsCompat.Type.systemBars())

        val splash = SplashView(this, AppTheme.current(this))
        setContentView(splash)
        splash.onFinished = { goNext() }
        splash.start()
    }

    private fun goNext() {
        if (done || isFinishing) return
        done = true
        startActivity(Intent(this, MainActivity::class.java))
        @Suppress("DEPRECATION")
        overridePendingTransition(android.R.anim.fade_in, android.R.anim.fade_out)
        finish()
    }
}
