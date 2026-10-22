package com.zhuanggui.pda.ui

/**
 * 扫码双通道去重（v1.10.2）。
 *
 * 背景：部分 PDA 的「扫描配置」可同时输出「广播 + 键盘」。一次扳机会先到广播、再以键盘
 * 注入到达，同一条码在极短时间内出现两遍，导致一枪两条记录。
 *
 * 规则：同一串码在 [windowMs] 内只接收一次；人工复扫通常 >1s，不受影响。
 * 纯逻辑，便于单测（时间由调用方传入，不依赖 SystemClock）。
 */
class ScanDeduper(private val windowMs: Long = 800L) {

    private var lastCode: String = ""
    private var lastAtMs: Long = 0L

    /** true = 判定为双通道重复，应忽略；false = 正常接收（并记录本次） */
    fun isDuplicate(code: String, nowMs: Long): Boolean {
        val dup = code == lastCode && nowMs - lastAtMs in 0 until windowMs
        if (!dup) {
            lastCode = code
            lastAtMs = nowMs
        }
        return dup
    }

    fun reset() {
        lastCode = ""
        lastAtMs = 0L
    }
}
