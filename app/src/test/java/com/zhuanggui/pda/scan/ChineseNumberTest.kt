package com.zhuanggui.pda.scan

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * 语音报数「自然读法」单测（v1.7.1）。
 * 约定：10 = 十、25 = 二十五、105 = 一百零五、110 = 一百一十、256 = 二百五十六、300 = 三百。
 */
class ChineseNumberTest {

    private fun d(n: Int) = "voice/d$n.wav"
    private val shi = "voice/shi.wav"
    private val bai = "voice/bai.wav"
    private val ling = "voice/ling.wav"

    @Test
    fun singleDigits() {
        assertEquals(listOf(d(0)), chineseNumberClips(0))
        assertEquals(listOf(d(7)), chineseNumberClips(7))
        assertEquals(listOf(d(9)), chineseNumberClips(9))
    }

    @Test
    fun teensReadAsTenPlusDigit() {
        assertEquals(listOf(shi), chineseNumberClips(10))
        assertEquals(listOf(shi, d(1)), chineseNumberClips(11))
        assertEquals(listOf(shi, d(5)), chineseNumberClips(15))
        assertEquals(listOf(shi, d(9)), chineseNumberClips(19))
    }

    @Test
    fun tensReadAsDigitTenDigit() {
        assertEquals(listOf(d(2), shi), chineseNumberClips(20))
        assertEquals(listOf(d(2), shi, d(5)), chineseNumberClips(25))
        assertEquals(listOf(d(9), shi, d(9)), chineseNumberClips(99))
    }

    @Test
    fun hundreds() {
        assertEquals(listOf(d(1), bai), chineseNumberClips(100))
        assertEquals(listOf(d(3), bai), chineseNumberClips(300))
        assertEquals(listOf(d(2), bai), chineseNumberClips(200))
    }

    @Test
    fun hundredsWithZeroUnit() {
        assertEquals(listOf(d(1), bai, ling, d(1)), chineseNumberClips(101))
        assertEquals(listOf(d(1), bai, ling, d(5)), chineseNumberClips(105))
        assertEquals(listOf(d(2), bai, ling, d(5)), chineseNumberClips(205))
    }

    @Test
    fun hundredsWithTeens() {
        assertEquals(listOf(d(1), bai, d(1), shi), chineseNumberClips(110))
        assertEquals(listOf(d(1), bai, d(1), shi, d(5)), chineseNumberClips(115))
        assertEquals(listOf(d(2), bai, d(1), shi, d(1)), chineseNumberClips(211))
    }

    @Test
    fun hundredsWithTens() {
        assertEquals(listOf(d(1), bai, d(2), shi), chineseNumberClips(120))
        assertEquals(listOf(d(2), bai, d(5), shi, d(6)), chineseNumberClips(256))
    }

    @Test
    fun aboveNineHundredNinetyNineFallsBackToDigits() {
        assertEquals(listOf(d(1), d(2), d(3), d(4)), chineseNumberClips(1234))
    }
}
