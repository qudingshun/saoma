package com.zhuanggui.pda.ui

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ScanDeduperTest {

    @Test
    fun `same code within 800ms is duplicate`() {
        val d = ScanDeduper()
        assertFalse(d.isDuplicate("EJ18011139E", 1000))
        assertTrue(d.isDuplicate("EJ18011139E", 1000))
        assertTrue(d.isDuplicate("EJ18011139E", 1799))
    }

    @Test
    fun `same code after window is accepted`() {
        val d = ScanDeduper()
        assertFalse(d.isDuplicate("EJ18011139E", 1000))
        assertFalse(d.isDuplicate("EJ18011139E", 1800))
    }

    @Test
    fun `different code is always accepted`() {
        val d = ScanDeduper()
        assertFalse(d.isDuplicate("A001", 1000))
        assertFalse(d.isDuplicate("A002", 1001))
        assertFalse(d.isDuplicate("A001", 1002))
    }

    @Test
    fun `duplicate does not extend the window`() {
        val d = ScanDeduper()
        assertFalse(d.isDuplicate("A001", 1000))
        assertTrue(d.isDuplicate("A001", 1500))
        // 第二次被忽略不应把计时重置到 1500；1800 已超过首个时间 800ms
        assertFalse(d.isDuplicate("A001", 1800))
    }
}
