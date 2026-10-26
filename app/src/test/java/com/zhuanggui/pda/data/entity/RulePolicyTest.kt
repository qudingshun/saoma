package com.zhuanggui.pda.data.entity

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class RulePolicyTest {

    private fun normal(name: String, prefix: String = "COMMON") = RuleLibEntry(
        prefix = prefix, name = name
    )

    private fun special(name: String, prefix: String = "COMMON") = RuleLibEntry(
        prefix = prefix, name = name, allowDuplicate = true, ignoreRepeat = true
    )

    @Test
    fun onlyPlainCountedRuleIsCountOnly() {
        assertTrue(isCountOnlyRule(false, true, false, false))
        assertFalse(isCountOnlyRule(true, true, false, false))
        assertFalse(isCountOnlyRule(false, false, false, false))
        assertFalse(isCountOnlyRule(false, true, true, false))
        assertFalse(isCountOnlyRule(false, true, false, true))
    }

    @Test
    fun plainCountedConflictsWithOtherModelPlainCounted() {
        val owners = normalCountedConflictOwners(
            prefix = "COMMON",
            targetName = "B",
            currentIsCountOnly = true,
            existing = listOf(normal("A"))
        )
        assertEquals(listOf("A"), owners.map { it.name })
    }

    @Test
    fun specialRuleCanReusePrefixAcrossModels() {
        assertTrue(
            normalCountedConflictOwners(
                prefix = "COMMON",
                targetName = "B",
                currentIsCountOnly = false,
                existing = listOf(normal("A"))
            ).isEmpty()
        )
    }

    @Test
    fun existingSpecialCodeDoesNotBlockPlainCountedRule() {
        assertTrue(
            normalCountedConflictOwners(
                prefix = "COMMON",
                targetName = "B",
                currentIsCountOnly = true,
                existing = listOf(special("A"))
            ).isEmpty()
        )
    }

    @Test
    fun sameModelIsNotTreatedAsCrossModelConflict() {
        assertTrue(
            normalCountedConflictOwners(
                prefix = "COMMON",
                targetName = "A",
                currentIsCountOnly = true,
                existing = listOf(normal("A"))
            ).isEmpty()
        )
    }
}
