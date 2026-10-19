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
 * 开关（v1.5.8，扫码页「报警设置」可调，持久化在本类持有的 SharedPreferences，默认都开）：
 *  - [beepEnabled]：蜂鸣开关；报警时循环蜂鸣、正常扫码短响一声，关闭后完全静音
 *  - [vibrateEnabled]：振动开关；报警时循环振动，关闭后不振动
 *  - 两者都关 = 纯界面报警（颜色 + 图标 + 弹窗文字），行为仍然可靠
 *
 * 振动策略（v1.4.0）：
 *  - 重复 / 串号 / 超量：连续循环振动，直到 [stop] 被调用
 *    （触屏、弹窗确认、下一枪扫码、删除记录、退出扫码页都会调用）；
 *    另有 [AUTO_STOP_MS] 毫秒兜底自动停止，防止马达长时间空转
 *  - 正常：仅短促蜂鸣，不振动
 */
class AlarmManager(private val context: Context) {

    /** 报警反馈开关持久化（SharedPreferences 内存缓存，读取开销可忽略） */
    private val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    var beepEnabled: Boolean
        get() = prefs.getBoolean(KEY_BEEP, true)
        set(value) = prefs.edit().putBoolean(KEY_BEEP, value).apply()

    var vibrateEnabled: Boolean
        get() = prefs.getBoolean(KEY_VIBRATE, true)
        set(value) = prefs.edit().putBoolean(KEY_VIBRATE, value).apply()

    // 蜂鸣器初始化兜底：个别机型/极端场景可能失败 → 失败则只保留振动反馈，绝不崩溃
    private val toneGenerator: ToneGenerator? = runCatching {
        ToneGenerator(AudioManager.STREAM_MUSIC, 100)
    }.getOrNull()
    private val handler = Handler(Looper.getMainLooper())
    private val autoStop = Runnable { stop() }

    // 报警循环蜂鸣：当前音型与重复节奏（由 startAlarm 设置）
    private var alarmTone = ToneGenerator.TONE_SUP_ERROR
    private var alarmToneMs = 350
    private var alarmGapMs = 450L
    private val beepLoop = object : Runnable {
        override fun run() {
            toneGenerator?.startTone(alarmTone, alarmToneMs)
            handler.postDelayed(this, alarmToneMs + alarmGapMs)
        }
    }

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
        stop() // 正常扫：顺手停掉上一枪可能未停的报警反馈
        if (beepEnabled) toneGenerator?.startTone(ToneGenerator.TONE_PROP_BEEP, 80)
    }

    fun error() {
        // 错误音：350ms 哔 + 450ms 静音，循环（蜂鸣开关关闭则跳过）
        startAlarm(ToneGenerator.TONE_SUP_ERROR, 350, 450L, longArrayOf(0, 400, 250))
    }

    fun overLimit() {
        // 超量音：250ms 短促高频 + 350ms 静音，循环（节奏比错误音更急）
        startAlarm(ToneGenerator.TONE_DTMF_9, 250, 350L, longArrayOf(0, 200, 150))
    }

    /** 停止当前蜂鸣/振动与兜底定时（幂等；触屏/确认/切页等任何“处理动作”都应调用） */
    fun stop() {
        handler.removeCallbacks(autoStop)
        handler.removeCallbacks(beepLoop)
        runCatching { vibrator.cancel() }.onFailure { Log.w(TAG, "cancel skipped: ${it.message}") }
    }

    /** 循环报警：蜂鸣 + 振动按各自开关并行；任一开启则武装 [AUTO_STOP_MS] 兜底自动停 */
    private fun startAlarm(tone: Int, toneMs: Int, gapMs: Long, vibratePattern: LongArray) {
        stop()
        var active = false
        if (beepEnabled) {
            active = true
            alarmTone = tone
            alarmToneMs = toneMs
            alarmGapMs = gapMs
            handler.post(beepLoop)
        }
        if (vibrateEnabled) {
            active = true
            // 兜底：无振动权限/无振动器时静默跳过，报警绝不导致崩溃
            runCatching {
                if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.O) {
                    vibrator.vibrate(VibrationEffect.createWaveform(vibratePattern, 0)) // repeat=0：从头循环
                } else {
                    @Suppress("DEPRECATION")
                    vibrator.vibrate(vibratePattern, 0)
                }
            }.onFailure { Log.w(TAG, "vibrate skipped: ${it.message}") }
        }
        if (active) handler.postDelayed(autoStop, AUTO_STOP_MS)
    }

    fun release() {
        stop()
        toneGenerator?.release()
    }

    companion object {
        private const val TAG = "AlarmManager"

        /** 报警反馈开关的 SharedPreferences 文件名与键（扫码页「报警设置」共用） */
        private const val PREFS_NAME = "alarm_prefs"
        private const val KEY_BEEP = "beep_enabled"
        private const val KEY_VIBRATE = "vibrate_enabled"

        /** 循环报警的兜底自动停止时间（毫秒） */
        const val AUTO_STOP_MS = 10_000L
    }
}
