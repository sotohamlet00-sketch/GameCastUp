package com.example.gamepadhid.shizuku;

/** Teclas físicas que el servicio de usuario (proceso shell) lee con getevent y reenvía a la app. */
oneway interface IKeyListener {
    /** keyCode = código de android.view.KeyEvent (VOLUME_UP 24, VOLUME_DOWN 25, POWER 26). */
    void onKey(int keyCode, boolean down);
}
