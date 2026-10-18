package com.zhuanggui.pda.scan

import android.content.Context
import android.media.AudioManager
import android.media.ToneGenerator
import android.os.Handler
import android.os.Looper
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager
import android.util.Log

/**
 * 报警反馈：蜂鸣 + 振动。
 *
 * 振动策略（v1.4.0）：
 *  - 重复 / 串号 / 超量：连续循环振动，直到 [stop] 被调用
 *    （触屏、弹窗确认、下一枪扫码、删除记录、退出扫码页都会调用）；
 *    另有 [AUTO_STOP_MS] 毫秒兜底自动停止，防止马达长时间空转
 *  - 正常：仅短促蜂鸣，不振动
 */
class AlarmManager(private val context: Context) {

    private val toneGenerator: ToneGenerator = ToneGenerator(AudioManager.STREAM_MUSIC, 100)
    private val handler = Handler(Looper.getMainLooper())
    private val autoStop = Runnable { stop() }

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
        stop() // 正常扫：顺手停掉上一枪可能未停的报警振动
        toneGenerator.startTone(ToneGenerator.TONE_PROP_BEEP, 80)
    }

    fun error() {
        toneGenerator.startTone(ToneGenerator.TONE_SUP_ERROR, 500)
        vibrateLoop(longArrayOf(0, 400, 250))
    }

    fun overLimit() {
        toneGenerator.startTone(ToneGenerator.TONE_DTMF_9, 300)
        vibrateLoop(longArrayOf(0, 200, 150))
    }

    /** 停止当前振动与兜底定时（幂等；触屏/确认/切页等任何"处理动作"都应调用） */
    fun stop() {
        handler.removeCallbacks(autoStop)
        runCatching { vibrator.cancel() }.onFailure { Log.w(TAG, "cancel skipped: ${it.message}") }
    }

    private fun vibrateLoop(pattern: LongArray) {
        // 兜底：无振动权限/无振动器时静默跳过，报警绝不导致崩溃
        runCatching {
            if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.O) {
                vibrator.vibrate(VibrationEffect.createWaveform(pattern, 0)) // repeat=0：从头循环
            } else {
                @Suppress("DEPRECATION")
                vibrator.vibrate(pattern, 0)
            }
            handler.removeCallbacks(autoStop)
            handler.postDelayed(autoStop, AUTO_STOP_MS)
        }.onFailure { Log.w(TAG, "vibrate skipped: ${it.message}") }
    }

    fun release() {
        stop()
        toneGenerator.release()
    }

    companion object {
        private const val TAG = "AlarmManager"

        /** 循环振动的兜底自动停止时间（毫秒） */
        const val AUTO_STOP_MS = 10_000L
    }
}
