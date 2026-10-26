package com.zhuanggui.pda.ui.screen

import com.zhuanggui.pda.data.entity.RuleLibEntry
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class RulePickerGroupingTest {

    private fun rule(prefix: String, name: String) = RuleLibEntry(prefix = prefix, name = name)

    @Test
    fun sameModelRulesStayInOneGroup() {
        val library = listOf(
            rule("AAA", "T45LW"),
            rule("BBB", "T45LW"),
            rule("CCC", "OTHER")
        )
        val groups = buildRulePickGroups(
            library = library,
            modelDefaults = mapOf("T45LW" to 12),
            alreadyIn = emptySet(),
            filter = ""
        )

        assertEquals(2, groups.size)
        val t45 = groups.first { it.name == "T45LW" }
        assertEquals(listOf("AAA", "BBB"), t45.rules.map { it.prefix })
        assertEquals(12, t45.defaultQuantity)
        assertEquals(2, t45.missingCount)
        assertFalse(t45.allInContainer)
    }

    @Test
    fun filterByOnePrefixKeepsWholeModel() {
        val groups = buildRulePickGroups(
            library = listOf(
                rule("AAA", "T45LW"),
                rule("BBB", "T45LW"),
                rule("CCC", "OTHER")
            ),
            modelDefaults = emptyMap(),
            alreadyIn = emptySet(),
            filter = "BBB"
        )

        assertEquals(1, groups.size)
        assertEquals(listOf("AAA", "BBB"), groups.single().rules.map { it.prefix })
    }

    @Test
    fun partiallyAddedModelOnlyAddsMissingRules() {
        val groups = buildRulePickGroups(
            library = listOf(
                rule("AAA", "T45LW"),
                rule("BBB", "T45LW")
            ),
            modelDefaults = emptyMap(),
            alreadyIn = setOf("AAA"),
            filter = ""
        )

        val group = groups.single()
        assertEquals(1, group.inContainerCount)
        assertEquals(1, group.missingCount)
        assertFalse(group.allInContainer)
    }

    @Test
    fun fullyAddedModelIsDisabledState() {
        val groups = buildRulePickGroups(
            library = listOf(
                rule("AAA", "T45LW"),
                rule("BBB", "T45LW")
            ),
            modelDefaults = emptyMap(),
            alreadyIn = setOf("AAA", "BBB"),
            filter = ""
        )

        assertTrue(groups.single().allInContainer)
        assertEquals(0, groups.single().missingCount)
    }

    @Test
    fun selectingOneModelReturnsAllItsMissingRules() {
        val groups = buildRulePickGroups(
            library = listOf(
                rule("AAA", "T45LW"),
                rule("BBB", "T45LW"),
                rule("CCC", "OTHER")
            ),
            modelDefaults = emptyMap(),
            alreadyIn = setOf("AAA"),
            filter = ""
        )
        val t45 = groups.first { it.name == "T45LW" }

        assertEquals(
            listOf("BBB"),
            selectedMissingPrefixes(groups, setOf(t45.key), setOf("AAA"))
        )
        assertEquals(
            listOf("AAA", "BBB"),
            selectedMissingPrefixes(groups, setOf(t45.key), emptySet())
        )
    }

    @Test
    fun unnamedRulesAreSeparateGroups() {
        val groups = buildRulePickGroups(
            library = listOf(rule("AAA", ""), rule("BBB", "")),
            modelDefaults = emptyMap(),
            alreadyIn = emptySet(),
            filter = ""
        )

        assertEquals(2, groups.size)
        assertEquals(setOf("AAA", "BBB"), groups.map { it.name }.toSet())
    }
}
