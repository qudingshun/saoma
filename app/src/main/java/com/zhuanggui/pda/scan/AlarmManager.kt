package com.zhuanggui.pda.scan

import android.content.Context
import android.media.AudioManager
import android.media.ToneGenerator
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager
import android.util.Log

class AlarmManager(private val context: Context) {

    private val toneGenerator: ToneGenerator = ToneGenerator(AudioManager.STREAM_MUSIC, 100)
    private val vibrator: Vibrator by lazy {
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.S) {
            val vm = context.getSystemService(Context.VIBRATOR_MANAGER_SERVICE) as VibratorManager
            vm.defaultVibrator
        } else {
            @Suppress("DEPRECATION")
            context.getSystemService(Context.VIBRATOR_SERVICE) as Vibrator
        }
    }

    fun success() {
        toneGenerator.startTone(ToneGenerator.TONE_PROP_BEEP, 80)
    }

    fun error() {
        toneGenerator.startTone(ToneGenerator.TONE_SUP_ERROR, 500)
        vibrate(longArrayOf(0, 350, 150, 350), 0)
    }

    fun overLimit() {
        toneGenerator.startTone(ToneGenerator.TONE_DTMF_9, 300)
        vibrate(longArrayOf(0, 200, 120, 200), 0)
    }

    private fun vibrate(pattern: LongArray, repeat: Int) {
        // 兜底：无振动权限/无振动器时静默跳过，报警绝不导致崩溃
        runCatching {
            if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.O) {
                vibrator.vibrate(VibrationEffect.createWaveform(pattern, repeat))
            } else {
                @Suppress("DEPRECATION")
                vibrator.vibrate(pattern, repeat)
            }
        }.onFailure { Log.w(TAG, "vibrate skipped: ${it.message}") }
    }

    fun release() {
        toneGenerator.release()
    }

    companion object {
        private const val TAG = "AlarmManager"
    }
}
