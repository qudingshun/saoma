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
 * 报警反馈：蜂鸣 + 振动 + 语音朗读（v1.7.0）。
 *
 * 设置（v1.7.0，扫码页「震动和声音设置」可调，持久化在本类持有的 SharedPreferences）：
 *  - 分「正常扫码」（含辅助码）与「非正常扫码」（串号/重复/超量）两组，各自独立配置：
 *    · [soundOkEnabled] / [soundErrorEnabled]：蜂鸣开关；报警时循环蜂鸣，正常扫码短响一声
 *    · [vibrateOkLevel] / [vibrateErrorLevel]：振动强度（0=关，1=弱，2=中，3=强）
 *  - [speakEnabled]：语音朗读开关（装柜成功后朗读；内置语音片段，不依赖系统 TTS 引擎）
 *  - 两组都可关（纯界面报警：颜色 + 图标 + 弹窗文字），行为仍然可靠
 *  - 升级兼容：旧版开关 beep_enabled / vibrate_enabled（v1.5.8）作为默认值读取，不丢用户设置
 *
 * 振动策略：
 *  - 重复 / 串号 / 超量：按「非正常」强度连续循环振动，直到 [stop] 被调用
 *    （触屏、弹窗确认、下一枪扫码、删除记录、退出扫码页都会调用）；
 *    另有 [AUTO_STOP_MS] 毫秒兜底自动停止，防止马达长时间空转
 *  - 正常：短促蜂鸣 + 一次短振（强度按「正常」配置，默认关）
 */
class AlarmManager(private val context: Context) {

    /** 报警反馈设置持久化（SharedPreferences 内存缓存，读取开销可忽略） */
    private val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    /** 正常扫码蜂鸣开关（默认沿用旧版 beep_enabled，无旧值时默认开） */
    var soundOkEnabled: Boolean
        get() = prefs.getBoolean(KEY_SOUND_OK, prefs.getBoolean(KEY_BEEP_LEGACY, true))
        set(value) = prefs.edit().putBoolean(KEY_SOUND_OK, value).apply()

    /** 非正常扫码蜂鸣开关（默认沿用旧版 beep_enabled，无旧值时默认开） */
    var soundErrorEnabled: Boolean
        get() = prefs.getBoolean(KEY_SOUND_ERROR, prefs.getBoolean(KEY_BEEP_LEGACY, true))
        set(value) = prefs.edit().putBoolean(KEY_SOUND_ERROR, value).apply()

    /** 正常扫码振动强度（0=关…3=强；默认关：旧版正常扫码不振动） */
    var vibrateOkLevel: Int
        get() = prefs.getInt(KEY_VIB_OK, 0).coerceIn(0, LEVEL_MAX)
        set(value) = prefs.edit().putInt(KEY_VIB_OK, value.coerceIn(0, LEVEL_MAX)).apply()

    /** 非正常扫码振动强度（0=关…3=强；默认沿用旧版 vibrate_enabled=true → 中） */
    var vibrateErrorLevel: Int
        get() = prefs.getInt(
            KEY_VIB_ERROR,
            if (prefs.getBoolean(KEY_VIBRATE_LEGACY, true)) LEVEL_MEDIUM else LEVEL_OFF
        ).coerceIn(0, LEVEL_MAX)
        set(value) = prefs.edit().putInt(KEY_VIB_ERROR, value.coerceIn(0, LEVEL_MAX)).apply()

    /** 语音朗读开关（v1.7.0：装柜成功后朗读；默认开，可在「震动和声音设置」关闭） */
    var speakEnabled: Boolean
        get() = prefs.getBoolean(KEY_SPEAK, true)
        set(value) {
            prefs.edit().putBoolean(KEY_SPEAK, value).apply()
            if (!value) voice.stop() // 关闭后立即停掉正在播报的语音
        }

    // 语音朗读（内置语音片段，不依赖系统 TTS 引擎；异常时静默降级，绝不崩溃）
    private val voice = VoicePlayer(context)

    /** 正常扫码朗读「已扫码，N」（N = 当前已扫码数量，逐位数字播报） */
    fun speakOk(count: Int) {
        if (speakEnabled) voice.speakOk(count)
    }

    /** 辅助码 / 忽略的条码：朗读「辅助码」 */
    fun speakAux() {
        if (speakEnabled) voice.speakAux()
    }

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
        if (soundOkEnabled) toneGenerator?.startTone(ToneGenerator.TONE_PROP_BEEP, 80)
        val level = vibrateOkLevel
        if (level > 0) vibrateOneShot(level)
    }

    fun error() {
        // 错误音：350ms 哔 + 450ms 静音，循环（声音开关关闭则跳过）
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
        voice.stop() // 同时停掉语音播报（报警/新一枪/离开页面时避免语音盖住报警声）
    }

    /** 循环报警：蜂鸣 + 振动按「非正常」组的开关/强度并行；任一开启则武装 [AUTO_STOP_MS] 兜底自动停 */
    private fun startAlarm(tone: Int, toneMs: Int, gapMs: Long, vibratePattern: LongArray) {
        stop()
        var active = false
        if (soundErrorEnabled) {
            active = true
            alarmTone = tone
            alarmToneMs = toneMs
            alarmGapMs = gapMs
            handler.post(beepLoop)
        }
        val level = vibrateErrorLevel
        if (level > 0) {
            active = true
            // 兜底：无振动权限/无振动器时静默跳过，报警绝不导致崩溃
            // 波形振幅按强度映射（timings/amplitudes 等长；偶数段 = 静音段）
            runCatching {
                val amplitudes = IntArray(vibratePattern.size) { if (it % 2 == 1) amplitudeOf(level) else 0 }
                vibrator.vibrate(VibrationEffect.createWaveform(vibratePattern, amplitudes, 0)) // repeat=0：从头循环
            }.onFailure { Log.w(TAG, "vibrate skipped: ${it.message}") }
        }
        if (active) handler.postDelayed(autoStop, AUTO_STOP_MS)
    }

    /** 正常扫码短振一次（强度按「正常」组配置） */
    private fun vibrateOneShot(level: Int) {
        runCatching {
            vibrator.vibrate(VibrationEffect.createOneShot(OK_VIBRATE_MS, amplitudeOf(level)))
        }.onFailure { Log.w(TAG, "vibrate skipped: ${it.message}") }
    }

    fun release() {
        stop()
        voice.release()
        toneGenerator?.release()
    }

    companion object {
        private const val TAG = "AlarmManager"

        /** 报警反馈设置持久化的 SharedPreferences 文件名（扫码页「震动和声音设置」共用） */
        private const val PREFS_NAME = "alarm_prefs"
        private const val KEY_SOUND_OK = "sound_ok"
        private const val KEY_SOUND_ERROR = "sound_error"
        private const val KEY_VIB_OK = "vibrate_ok_level"
        private const val KEY_VIB_ERROR = "vibrate_error_level"
        private const val KEY_SPEAK = "speak_enabled"

        /** 旧版开关键（v1.5.8）：仅作为默认值读取，升级不丢用户设置 */
        private const val KEY_BEEP_LEGACY = "beep_enabled"
        private const val KEY_VIBRATE_LEGACY = "vibrate_enabled"

        /** 振动强度档位：0=关，1=弱，2=中，3=强（界面「震动和声音设置」四选一） */
        const val LEVEL_OFF = 0
        const val LEVEL_WEAK = 1
        const val LEVEL_MEDIUM = 2
        const val LEVEL_STRONG = 3
        const val LEVEL_MAX = LEVEL_STRONG

        /** 循环报警的兜底自动停止时间（毫秒） */
        const val AUTO_STOP_MS = 10_000L

        /** 正常扫码短振时长（毫秒） */
        private const val OK_VIBRATE_MS = 60L

        /** 强度档位 → 振幅（VibrationEffect 振幅 1..255） */
        private fun amplitudeOf(level: Int): Int = when (level) {
            LEVEL_WEAK -> 110
            LEVEL_MEDIUM -> 180
            else -> 255
        }
    }
}
