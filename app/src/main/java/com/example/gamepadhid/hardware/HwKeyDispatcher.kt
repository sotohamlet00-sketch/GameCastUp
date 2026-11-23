package com.example.gamepadhid.hardware

import android.os.Handler
import android.os.Looper
import android.view.KeyEvent
import com.example.gamepadhid.hid.GamepadDescriptor
import com.example.gamepadhid.hid.GamepadState
import com.example.gamepadhid.lobby.LobbyInput
import com.example.gamepadhid.mapping.ControlIds
import com.example.gamepadhid.shizuku.GameCastClient
import java.util.concurrent.Executors

/** Traduce una acción de botón físico al destino: mando HID, Lobby o teclas de la pantalla virtual. */
object HwKeyDispatcher {

    private val io = Executors.newSingleThreadExecutor()
    private val main = Handler(Looper.getMainLooper())

    /** Pulsa una tecla de Android en una pantalla (displayId < 0 = principal) con permisos de shell. */
    fun sendKey(displayId: Int, keyCode: Int) {
        io.execute {
            try { GameCastClient.service?.keyEvent(displayId, keyCode) } catch (e: Throwable) { /* sin servicio */ }
        }
    }

    /** Encendido apaga la pantalla aunque lo observemos: la volvemos a encender un instante después. */
    fun wakeAfterPower() {
        main.postDelayed({ sendKey(-1, KeyEvent.KEYCODE_WAKEUP) }, 150)
    }

    /** Modo HID: la acción pulsa un botón del gamepad virtual. Las acciones de la app (cerrar, atrás) no aplican. */
    fun toHid(pad: GamepadState, action: String, down: Boolean) {
        when (action) {
            ControlIds.A -> pad.setButton(GamepadDescriptor.BTN_A, down)
            ControlIds.B -> pad.setButton(GamepadDescriptor.BTN_B, down)
            ControlIds.X -> pad.setButton(GamepadDescriptor.BTN_X, down)
            ControlIds.Y -> pad.setButton(GamepadDescriptor.BTN_Y, down)
            ControlIds.L1 -> pad.setButton(GamepadDescriptor.BTN_L1, down)
            ControlIds.R1 -> pad.setButton(GamepadDescriptor.BTN_R1, down)
            ControlIds.START -> pad.setButton(GamepadDescriptor.BTN_START, down)
            ControlIds.SELECT -> pad.setButton(GamepadDescriptor.BTN_SELECT, down)
            ControlIds.L2 -> pad.setTrigger(left = true, value = if (down) 255 else 0)
            ControlIds.R2 -> pad.setTrigger(left = false, value = if (down) 255 else 0)
            ControlIds.D_UP -> pad.setDpad(GamepadState.DPAD_UP, down)
            ControlIds.D_DOWN -> pad.setDpad(GamepadState.DPAD_DOWN, down)
            ControlIds.D_LEFT -> pad.setDpad(GamepadState.DPAD_LEFT, down)
            ControlIds.D_RIGHT -> pad.setDpad(GamepadState.DPAD_RIGHT, down)
        }
    }

    /** Lobby: A selecciona, B vuelve, L1/R1 saltan y la cruceta mueve. */
    fun toLobby(action: String, down: Boolean) {
        val key = when (action) {
            ControlIds.A -> LobbyInput.Key.SELECT
            ControlIds.B -> LobbyInput.Key.BACK
            ControlIds.L1 -> LobbyInput.Key.PAGE_UP
            ControlIds.R1 -> LobbyInput.Key.PAGE_DOWN
            ControlIds.D_UP -> LobbyInput.Key.UP
            ControlIds.D_DOWN -> LobbyInput.Key.DOWN
            ControlIds.D_LEFT -> LobbyInput.Key.LEFT
            ControlIds.D_RIGHT -> LobbyInput.Key.RIGHT
            else -> return
        }
        if (down) LobbyInput.press(key) else LobbyInput.release(key)
    }
}
