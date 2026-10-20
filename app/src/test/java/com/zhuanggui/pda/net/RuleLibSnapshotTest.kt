package com.zhuanggui.pda.net

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** 规则库同步 JSON 解析/上传体（v1.9.0）。 */
class RuleLibSnapshotTest {

    @Test
    fun parseServerSnapshot() {
        val json = JSONObject(
            """
            {"ok":true,"hasData":true,"rev":3,
             "models":["T70","T45LW","T45LW"],
             "rules":[
               {"prefix":"311","name":"T70","countEnabled":true,"checkHistory":true},
               {"prefix":"411","name":"T45LW","allowDuplicate":true,"countEnabled":false}
             ],
             "updatedAt":"2026-10-20T17:00:00.000+08:00","updatedBy":"PDA-DFE666"}
            """.trimIndent()
        )
        val snap = RuleLibSnapshot.fromJson(json)
        assertTrue(snap.hasData)
        assertEquals(3, snap.rev)
        assertEquals(listOf("T45LW", "T70"), snap.models)
        assertEquals(2, snap.rules.size)
        val r311 = snap.rules.first { it.prefix == "311" }
        assertTrue(r311.countEnabled)
        assertTrue(r311.checkHistory)
        assertFalse(r311.allowDuplicate)
        val r411 = snap.rules.first { it.prefix == "411" }
        assertTrue(r411.allowDuplicate)
        assertFalse(r411.countEnabled)
        assertEquals("PDA-DFE666", snap.updatedBy)
    }

    @Test
    fun emptyServerSnapshot() {
        val snap = RuleLibSnapshot.fromJson(
            JSONObject("""{"ok":true,"hasData":false,"rev":0,"models":[],"rules":[]}""")
        )
        assertFalse(snap.hasData)
        assertEquals(0, snap.rev)
        assertTrue(snap.rules.isEmpty())
    }

    @Test
    fun uploadBodyHasFlagsAndDevice() {
        val snap = RuleLibSnapshot(
            hasData = true, rev = 0,
            models = listOf("M1"),
            rules = listOf(
                RuleLibRule("411", "M1", allowDuplicate = true, countEnabled = false,
                    checkHistory = true, ignoreRepeat = true)
            ),
            updatedAt = "", updatedBy = ""
        )
        val body = JSONObject(snap.toUploadBody("PDA-123456"))
        assertEquals("PDA-123456", body.getString("deviceId"))
        assertEquals(1, body.getJSONArray("models").length())
        val r = body.getJSONArray("rules").getJSONObject(0)
        assertEquals("411", r.getString("prefix"))
        assertTrue(r.getBoolean("allowDuplicate"))
        assertFalse(r.getBoolean("countEnabled"))
        assertTrue(r.getBoolean("checkHistory"))
        assertTrue(r.getBoolean("ignoreRepeat"))
    }
}
