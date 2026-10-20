package com.zhuanggui.pda.ui

import com.zhuanggui.pda.data.entity.ScanResult
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * decideScanResult 判定顺序与边界单测。
 * 顺序契约：规则串号 → 同柜重复 → 历史串号 → 辅助码 → 超量 → 正常。
 */
class ScanDeciderTest {

    private fun decide(
        matched: Boolean = true,
        dupCount: Int = 0,
        historyHit: Boolean = false,
        countEnabled: Boolean = true,
        total: Int = 0,
        okCount: Int = 0
    ): ScanResult = decideScanResult(matched, dupCount, historyHit, countEnabled, total, okCount)

    /** 规则不匹配压过重复/历史串号/超量等一切条件 */
    @Test
    fun notMatched_winsOverEverything() {
        assertEquals(
            ScanResult.SERIAL_MISMATCH,
            decide(matched = false, dupCount = 3, historyHit = true, total = 1, okCount = 99)
        )
    }

    /** 同柜重复压过历史串号、辅助、超量 */
    @Test
    fun duplicate_winsOverHistoryAuxAndOverLimit() {
        assertEquals(
            ScanResult.DUPLICATE,
            decide(dupCount = 1, historyHit = true, countEnabled = false, total = 5, okCount = 5)
        )
    }

    /** 历史串号压过辅助与超量 */
    @Test
    fun historyHit_winsOverAuxAndOverLimit() {
        assertEquals(
            ScanResult.SERIAL_MISMATCH,
            decide(historyHit = true, countEnabled = false, total = 5, okCount = 5)
        )
    }

    /** 辅助码必须先于超量判定（辅助码不应被超量拦截） */
    @Test
    fun aux_beforeOverLimitCheck() {
        assertEquals(ScanResult.AUX, decide(countEnabled = false, total = 5, okCount = 5))
    }

    /** 已装正常数 ≥ 计划装柜数量 → 超量（含恰好达量后再扫） */
    @Test
    fun overLimit_whenOkCountReachedTotal() {
        assertEquals(ScanResult.OVER_LIMIT, decide(total = 5, okCount = 5))
        assertEquals(ScanResult.OVER_LIMIT, decide(total = 5, okCount = 6))
    }

    /** 未达量 → 正常 */
    @Test
    fun ok_whenBelowTotal() {
        assertEquals(ScanResult.OK, decide(total = 5, okCount = 4))
    }

    /** 未设置数量（total ≤ 0）→ 永不超量 */
    @Test
    fun ok_whenTotalUnset() {
        assertEquals(ScanResult.OK, decide(total = 0, okCount = 999))
        assertEquals(ScanResult.OK, decide(total = -1, okCount = 999))
    }

    /** 允许重复的规则：调用方已把 dupCount 归零 → 正常 */
    @Test
    fun ok_whenDupAllowed_dupCountZero() {
        assertEquals(ScanResult.OK, decide(dupCount = 0))
    }

    // ---------- serialInRange（v1.7.0 流水号范围） ----------

    /** 上下界都留空 → 不校验流水号（旧行为不变） */
    @Test
    fun serial_noRange_alwaysInRange() {
        assertEquals(true, serialInRange("0001", "", ""))
        assertEquals(true, serialInRange("", "", ""))
        assertEquals(true, serialInRange("ABC", "", ""))
    }

    /** 设了范围 → 前缀后必须是纯数字，且落在闭区间内 */
    @Test
    fun serial_withRange_checksNumericBounds() {
        assertEquals(true, serialInRange("1", "1", "5"))
        assertEquals(true, serialInRange("0001", "1", "5"))   // 前导零按数值比较
        assertEquals(true, serialInRange("5", "1", "5"))      // 闭区间
        assertEquals(false, serialInRange("6", "1", "5"))
        assertEquals(false, serialInRange("0", "1", "5"))
    }

    /** 只设一侧：另一侧不限 */
    @Test
    fun serial_oneSidedRange() {
        assertEquals(true, serialInRange("999", "1", ""))
        assertEquals(false, serialInRange("0", "1", ""))
        assertEquals(true, serialInRange("0", "", "5"))
        assertEquals(false, serialInRange("6", "", "5"))
    }

    /** 前缀后不是纯数字 / 为空 → 不匹配（只有设了范围时才生效） */
    @Test
    fun serial_nonNumericOrEmpty_rejected_whenRangeSet() {
        assertEquals(false, serialInRange("", "1", "5"))       // 条码 = 前缀本身
        assertEquals(false, serialInRange("A001", "1", "5"))
        assertEquals(false, serialInRange("001A", "1", "5"))
        assertEquals(false, serialInRange("1.5", "1", "5"))
    }

    /** 超长流水号不溢出（BigInteger 比较） */
    @Test
    fun serial_veryLongNumbers_compareCorrectly() {
        val big = "123456789012345678901234567890"
        val bigger = "123456789012345678901234567891"
        assertEquals(true, serialInRange(big, big, bigger))
        assertEquals(false, serialInRange(bigger, big, big))
    }

    // ---------- 末尾 N 位流水号（v1.7.2） ----------

    /** 用户实例：前缀 ASV12，条码 ASV12SDFWG05455056$，末尾 3 位 = 056 */
    @Test
    fun serialTail_extractsLastDigits() {
        val rest = "SDFWG05455056$"
        assertEquals(true, serialInRange(rest, "1", "300", 3))     // 056 = 56
        assertEquals(false, serialInRange(rest, "100", "300", 3))   // 56 < 100
        assertEquals(true, serialInRange(rest, "1", "56", 3))
    }

    /** 末尾有无符号都取同样的数字（056 / 056$ / 056ABC） */
    @Test
    fun serialTail_ignoresTrailingSymbolsAndLetters() {
        assertEquals(true, serialInRange("056", "1", "300", 3))
        assertEquals(true, serialInRange("056$", "1", "300", 3))
        assertEquals(true, serialInRange("056ABC", "1", "300", 3))
        assertEquals(true, serialInRange("ABC-999-056$", "1", "300", 3))
    }

    /** 末尾位数不够 / 末尾不是数字 → 不匹配 */
    @Test
    fun serialTail_tooShortOrNoDigits_rejected() {
        assertEquals(false, serialInRange("56", "1", "300", 3))
        assertEquals(false, serialInRange("$", "1", "300", 3))
        assertEquals(false, serialInRange("", "1", "300", 3))
        assertEquals(false, serialInRange("05A", "1", "300", 3))
    }

    /** tailLen=0 保持旧语义（前缀后整段必须是数字） */
    @Test
    fun serialTail_zero_keepsLegacyBehavior() {
        assertEquals(false, serialInRange("SDFWG05455056$", "1", "300", 0))
        assertEquals(true, serialInRange("056", "1", "300", 0))
    }
}
