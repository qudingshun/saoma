package com.zhuanggui.pda.scan

import android.content.Context
import android.media.AudioAttributes
import android.media.MediaPlayer
import android.util.Log

private const val TAG = "VoicePlayer"
private const val OK_CLIP = "voice/ok.wav"
private const val AUX_CLIP = "voice/auxcode.wav"
private const val DIGIT_DIR = "voice/d"
private const val SHI_CLIP = "voice/shi.wav"
private const val BAI_CLIP = "voice/bai.wav"
private const val LING_CLIP = "voice/ling.wav"

/**
 * 语音播报（v1.7.0 第 7 条）：装柜扫码成功后朗读提示。
 *
 * 现场 PDA（CRUISE2 / Android 11）**没有系统 TTS 引擎**，因此不依赖 android.speech.tts，
 * 改为内置语音片段（assets/voice 目录，16kHz 单声道 PCM，用微软 Huihui 中文语音离线生成）：
 *  - 正常扫码：[ok.wav]「已扫码」+ 数字按自然读法拼接（v1.7.1：256 → 二百五十六、25 → 二十五、10 → 十）
 *  - 辅助码 / 忽略：[auxcode.wav]「辅助码」
 *  - 片段按顺序用 MediaPlayer 播放（onCompletion 串行推进），新一枪开始前先停掉上一段
 *  - 任何异常（音频设备忙 / 片段缺失）只记日志并跳过，绝不影响扫码主流程
 */
class VoicePlayer(private val context: Context) {

    private var player: MediaPlayer? = null

    /** 当前播放序号：新播报/停止时 +1，旧序列的回调据此自动作废 */
    private var token = 0

    /** 正常扫码：朗读「已扫码」+ 当前已扫码数量（自然读法：10 读「十」、25 读「二十五」） */
    fun speakOk(count: Int) {
        speak(listOf(OK_CLIP) + chineseNumberClips(count.coerceAtLeast(0)))
    }

    /** 辅助码 / 忽略的条码：朗读「辅助码」 */
    fun speakAux() = speak(listOf(AUX_CLIP))

    /** 停止当前朗读（新一枪 / 报警 / 离开扫码页 / 关闭开关时调用） */
    fun stop() {
        token++
        val p = player
        player = null
        if (p != null) {
            runCatching { p.stop() }
            runCatching { p.release() }
        }
    }

    fun release() = stop()

    private fun speak(files: List<String>) {
        if (files.isEmpty()) return
        stop()
        playNext(files, 0, token)
    }

    private fun playNext(files: List<String>, index: Int, myToken: Int) {
        if (myToken != token) return
        if (index >= files.size) return

        val path = files[index]
        val mp = MediaPlayer()
        try {
            context.assets.openFd(path).use { afd ->
                mp.setAudioAttributes(
                    AudioAttributes.Builder()
                        .setUsage(AudioAttributes.USAGE_MEDIA)
                        .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                        .build()
                )
                mp.setDataSource(afd.fileDescriptor, afd.startOffset, afd.length)
                mp.prepare()
            }
            mp.setOnCompletionListener { done ->
                if (player === done) player = null
                runCatching { done.release() }
                playNext(files, index + 1, myToken)
            }
            player = mp
            mp.start()
            Log.d(TAG, "voice clip: $path")
        } catch (t: Throwable) {
            Log.w(TAG, "voice clip failed ($path): ${t.message}")
            player = null
            runCatching { mp.release() }
            playNext(files, index + 1, myToken) // 跳过坏片段，继续后续片段
        }
    }
}

/**
 * 数字 → 语音片段（自然读法，现场预估最大 300；纯函数，单测覆盖）：
 *  - 0..9 → 数字片段
 *  - 10..19 → 十 [+ 位]（10 = 十、15 = 十五）
 *  - 20..99 → 十位 + 十 [+ 位]（25 = 二十五）
 *  - 100..999 → 百位 + 百 [+ 零 + 位 | 一十 [+ 位] | 十位 + 十 [+ 位]]
 *    （105 = 一百零五、110 = 一百一十、115 = 一百一十五、256 = 二百五十六、300 = 三百）
 *  - 超过 999 → 退回逐位读法，保证不越界
 */
internal fun chineseNumberClips(n: Int): List<String> {
    if (n > 999) {
        return n.toString().mapNotNull { c ->
            (c - '0').takeIf { it in 0..9 }?.let { digitClip(it) }
        }
    }
    if (n == 0) return listOf(digitClip(0))

    val out = mutableListOf<String>()
    val hundreds = n / 100
    val rem = n % 100
    if (hundreds > 0) {
        out.add(digitClip(hundreds))
        out.add(BAI_CLIP)
    }
    when {
        rem == 0 -> Unit
        rem < 10 -> {
            if (hundreds > 0) out.add(LING_CLIP)   // 一百零五
            out.add(digitClip(rem))
        }
        rem < 20 -> {
            if (hundreds > 0) out.add(digitClip(1)) // 一百一十（不读「一百十」）
            out.add(SHI_CLIP)
            if (rem > 10) out.add(digitClip(rem - 10))
        }
        else -> {
            out.add(digitClip(rem / 10))
            out.add(SHI_CLIP)
            if (rem % 10 != 0) out.add(digitClip(rem % 10))
        }
    }
    return out
}

private fun digitClip(d: Int) = "$DIGIT_DIR$d.wav"
