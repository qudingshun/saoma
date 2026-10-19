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
}
