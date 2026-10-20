package com.zhuanggui.pda.net

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import java.util.Date

/** 配对码解析与 ISO 时间单测（v1.8.0） */
class SyncPairingTest {

    @Test
    fun qrPayloadParsed() {
        val info = PairingInfo.parse("SAOMASYNC1|ip=192.168.1.100|port=8000|token=K7F2QW9M|ver=1")
        assertEquals("http://192.168.1.100:8000", info?.baseUrl)
        assertEquals("K7F2QW9M", info?.token)
    }

    @Test
    fun manualPayloadParsed() {
        val info = PairingInfo.parse("192.168.1.100:8000#K7F2QW9M")
        assertEquals("http://192.168.1.100:8000", info?.baseUrl)
        assertEquals("K7F2QW9M", info?.token)
    }

    @Test
    fun manualWithSchemeParsed() {
        val info = PairingInfo.parse("http://10.0.0.5:8123#ABCD2345")
        assertEquals("http://10.0.0.5:8123", info?.baseUrl)
        assertEquals("ABCD2345", info?.token)
    }

    @Test
    fun invalidPayloadsRejected() {
        assertNull(PairingInfo.parse(""))
        assertNull(PairingInfo.parse("商品条码123456"))
        assertNull(PairingInfo.parse("SAOMASYNC1|ip=192.168.1.100|port=abc|token=X"))
        assertNull(PairingInfo.parse("192.168.1.100:8000"))
        assertNull(PairingInfo.parse("192.168.1.100:99999#TOKEN"))
        assertNull(PairingInfo.parse("192.168.1.100:8000#"))
    }

    @Test
    fun isoRoundTrip() {
        val now = Date(1792470000123)
        val text = now.toIso()
        val back = text.toDateOrNull()
        assertEquals(now.time, back?.time)
    }
}
