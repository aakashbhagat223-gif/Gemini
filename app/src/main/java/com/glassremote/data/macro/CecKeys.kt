package com.glassremote.data.macro

import android.view.KeyEvent

/** HDMI-CEC "User Control Code" values (CEC specification), plus Android key -> CEC mapping. */
object CecKeys {
    const val SELECT = 0x00
    const val UP = 0x01
    const val DOWN = 0x02
    const val LEFT = 0x03
    const val RIGHT = 0x04
    const val ROOT_MENU = 0x09
    const val SETUP_MENU = 0x0A
    const val CONTENTS_MENU = 0x0B
    const val EXIT = 0x0D
    const val NUM_0 = 0x20 // digits are NUM_0 + n
    const val CHANNEL_UP = 0x30
    const val CHANNEL_DOWN = 0x31
    const val DISPLAY_INFO = 0x35
    const val POWER = 0x40
    const val EPG = 0x53

    /** Returns the CEC code for a key on the Jio layout, or null if it has none. */
    fun fromAndroidKey(code: Int): Int? = when (code) {
        KeyEvent.KEYCODE_DPAD_CENTER, KeyEvent.KEYCODE_ENTER -> SELECT
        KeyEvent.KEYCODE_DPAD_UP -> UP
        KeyEvent.KEYCODE_DPAD_DOWN -> DOWN
        KeyEvent.KEYCODE_DPAD_LEFT -> LEFT
        KeyEvent.KEYCODE_DPAD_RIGHT -> RIGHT
        KeyEvent.KEYCODE_BACK -> EXIT
        KeyEvent.KEYCODE_MENU -> CONTENTS_MENU
        KeyEvent.KEYCODE_HOME -> ROOT_MENU
        KeyEvent.KEYCODE_GUIDE -> EPG
        KeyEvent.KEYCODE_CHANNEL_UP -> CHANNEL_UP
        KeyEvent.KEYCODE_CHANNEL_DOWN -> CHANNEL_DOWN
        KeyEvent.KEYCODE_POWER -> POWER
        in KeyEvent.KEYCODE_0..KeyEvent.KEYCODE_9 -> NUM_0 + (code - KeyEvent.KEYCODE_0)
        else -> null
    }
}
