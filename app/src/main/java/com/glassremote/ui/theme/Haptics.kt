package com.glassremote.ui.theme

import android.content.Context
import android.os.Build
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager
import androidx.compose.runtime.staticCompositionLocalOf
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton

val LocalHaptics = staticCompositionLocalOf<Haptics?> { null }

@Singleton
class Haptics @Inject constructor(@ApplicationContext ctx: Context) {

    private val vibrator: Vibrator? =
        if (Build.VERSION.SDK_INT >= 31) ctx.getSystemService(VibratorManager::class.java)?.defaultVibrator
        else legacyVibrator(ctx)

    @Suppress("DEPRECATION")
    private fun legacyVibrator(ctx: Context): Vibrator? =
        ctx.getSystemService(Context.VIBRATOR_SERVICE) as? Vibrator

    private fun play(effect: VibrationEffect) {
        val v = vibrator ?: return
        if (v.hasVibrator()) runCatching { v.vibrate(effect) }
    }

    private fun predefined(id: Int, fallbackMs: Long, amplitude: Int): VibrationEffect =
        if (Build.VERSION.SDK_INT >= 29) VibrationEffect.createPredefined(id)
        else VibrationEffect.createOneShot(fallbackMs, amplitude)

    /** Clean, short tick: keyboard taps and slider steps. */
    fun tick() = play(predefined(VibrationEffect.EFFECT_TICK, 8, 60))

    fun click() = play(predefined(VibrationEffect.EFFECT_CLICK, 15, 120))

    /** Heavy click: slider reaches either end. */
    fun heavyClick() = play(predefined(VibrationEffect.EFFECT_HEAVY_CLICK, 30, 255))

    /** Minor rumble while the slider is held at its limit. */
    fun rumble() = play(
        VibrationEffect.createWaveform(
            longArrayOf(0, 25, 15, 25, 15, 25),
            intArrayOf(0, 90, 0, 90, 0, 90),
            -1,
        )
    )
}
