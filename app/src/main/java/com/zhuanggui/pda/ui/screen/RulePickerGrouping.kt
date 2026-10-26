package com.zhuanggui.pda.ui.screen

import com.zhuanggui.pda.data.entity.RuleLibEntry

/**
 * 「从规则库选择」按型号整组选择（v1.11.2）：
 * 同一型号下的多条前缀规则必须作为一组一起加入本柜，不能拆成单条规则勾选。
 */
internal data class RulePickGroup(
    val key: String,
    val name: String,
    val rules: List<RuleLibEntry>,
    val defaultQuantity: Int,
    val inContainerCount: Int
) {
    val allInContainer: Boolean get() = rules.isNotEmpty() && inContainerCount == rules.size
    val missingCount: Int get() = (rules.size - inContainerCount).coerceAtLeast(0)
}

/**
 * 把规则库按型号分组，并支持按型号名称 / 任一前缀过滤。
 * - 有型号名称：同一 name 合并为一组；
 * - 无型号名称：按前缀各自成组，避免把无关规则误合并。
 * - 已在本柜的规则只影响提示与「剩余条数」，不影响整组展示。
 */
internal fun buildRulePickGroups(
    library: List<RuleLibEntry>,
    modelDefaults: Map<String, Int>,
    alreadyIn: Set<String>,
    filter: String
): List<RulePickGroup> {
    val keyword = filter.trim()
    val grouped = library.groupBy { entry ->
        entry.name.ifBlank { "prefix:${entry.prefix}" }
    }

    return grouped.map { (key, entries) ->
        val rules = entries.sortedBy { it.prefix }
        val name = rules.first().name.ifBlank { rules.first().prefix }
        RulePickGroup(
            key = key,
            name = name,
            rules = rules,
            defaultQuantity = modelDefaults[name] ?: 0,
            inContainerCount = rules.count { it.prefix in alreadyIn }
        )
    }.filter { group ->
        keyword.isBlank() ||
            group.name.contains(keyword, ignoreCase = true) ||
            group.rules.any { it.prefix.contains(keyword, ignoreCase = true) }
    }.sortedWith(
        compareBy<RulePickGroup> { it.allInContainer }
            .thenBy { it.name }
    )
}

/** 当前勾选且仍有规则需要加入本柜的型号组（完全已在本柜的组不计入）。 */
internal fun selectedPickGroups(
    groups: List<RulePickGroup>,
    checkedGroupKeys: Set<String>
): List<RulePickGroup> = groups.filter { it.key in checkedGroupKeys && it.missingCount > 0 }

/** 勾选型号后实际要补加的前缀：同型号多条规则一起返回，已在本柜的自动排除。 */
internal fun selectedMissingPrefixes(
    groups: List<RulePickGroup>,
    checkedGroupKeys: Set<String>,
    alreadyIn: Set<String>
): List<String> = selectedPickGroups(groups, checkedGroupKeys)
    .flatMap { group -> group.rules.map { it.prefix }.filterNot { it in alreadyIn } }
    .distinct()

/**
 * v1.12.0：按规则 id 返回要补加的规则。
 * 同一前缀可能同时属于多个型号（特殊码），不能再只按前缀从库里取开关。
 */
internal fun selectedMissingRuleIds(
    groups: List<RulePickGroup>,
    checkedGroupKeys: Set<String>,
    alreadyIn: Set<String>
): List<Long> = selectedPickGroups(groups, checkedGroupKeys)
    .flatMap { group -> group.rules.filterNot { it.prefix in alreadyIn }.map { it.id } }
    .distinct()
