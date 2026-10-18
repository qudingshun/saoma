package com.zhuanggui.pda.ui.screen

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.zhuanggui.pda.ui.OutboundSetupState

/**
 * 出库扫码设置页：
 *  - 柜号（字母+数字；已有柜号自动载入规则 = 继续装柜）
 *  - 本柜数量（留空/0 = 不限）
 *  - 条码规则（前缀，可添加多条 —— 拼装柜 2 个型号 = 2 条规则）
 *  - 【装柜】进入扫码界面
 *
 * 装柜中可返回本页追加第二条规则，再点【装柜】继续（同柜号规则增量同步）。
 */
@Composable
fun OutboundSetupScreen(
    state: OutboundSetupState,
    onContainerNo: (String) -> Unit,
    onTotal: (String) -> Unit,
    onRuleInput: (String) -> Unit,
    onAddRule: () -> Unit,
    onRemoveRule: (String) -> Unit,
    onStart: () -> Boolean,
    onBack: () -> Unit
) {
    Scaffold { pad ->
        Column(
            Modifier
                .padding(pad)
                .fillMaxSize()
                .padding(16.dp)
                .verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Row(
                Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text("出库扫码设置", fontSize = 26.sp, fontWeight = FontWeight.Bold)
                OutlinedButton(onClick = onBack) { Text("返回", fontSize = 14.sp) }
            }

            if (state.resumed) {
                Card(Modifier.fillMaxWidth(), colors = androidx.compose.material3.CardDefaults.cardColors(containerColor = Color(0xFFFFF9C4))) {
                    Text(
                        "该柜号已有装柜记录，将【继续装柜】（累计计数与文件都接着保存）",
                        Modifier.padding(10.dp),
                        fontSize = 15.sp
                    )
                }
            }

            OutlinedTextField(
                value = state.containerNo,
                onValueChange = onContainerNo,
                label = { Text("柜号（字母+数字）") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth()
            )

            OutlinedTextField(
                value = state.totalQuantity,
                onValueChange = onTotal,
                label = { Text("本柜数量（留空或0=不限）") },
                singleLine = true,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                modifier = Modifier.fillMaxWidth()
            )

            // ---------- 条码规则 ----------
            Text("本柜条码规则（前缀）", fontSize = 18.sp, fontWeight = FontWeight.Bold)
            Text(
                "扫出的条码必须以规则前缀开头，否则报“串号”；同柜条码不允许重复扫",
                fontSize = 13.sp,
                color = Color.Gray
            )

            Row(
                Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                OutlinedTextField(
                    value = state.ruleInput,
                    onValueChange = onRuleInput,
                    label = { Text("录入规则（字母+数字）") },
                    singleLine = true,
                    modifier = Modifier.weight(1f)
                )
                Button(onClick = onAddRule, enabled = state.ruleInput.isNotBlank()) {
                    Text("添加", fontSize = 16.sp)
                }
            }

            if (state.rules.isEmpty()) {
                Text("尚未添加规则（拼装柜可加多条，如型号A前缀 + 型号B前缀）",
                    fontSize = 14.sp, color = Color(0xFFD32F2F))
            } else {
                state.rules.forEachIndexed { i, rule ->
                    Row(
                        Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        AssistChip(
                            onClick = {},
                            label = { Text("规则${i + 1}：$rule", fontSize = 15.sp) }
                        )
                        Text(
                            "删除", fontSize = 14.sp, color = Color(0xFFD32F2F),
                            modifier = Modifier
                                .clickable { onRemoveRule(rule) }
                                .padding(start = 12.dp, top = 8.dp, bottom = 8.dp)
                        )
                    }
                }
            }

            Spacer(Modifier.height(8.dp))

            Button(
                onClick = { onStart() },
                enabled = state.containerNo.isNotBlank() && state.rules.isNotEmpty(),
                modifier = Modifier.fillMaxWidth().height(72.dp)
            ) {
                Text("装柜", fontSize = 28.sp, fontWeight = FontWeight.Bold)
            }
            if (state.containerNo.isBlank() || state.rules.isEmpty()) {
                Text("请先填写柜号并至少添加 1 条条码规则", fontSize = 13.sp, color = Color.Gray)
            }
        }
    }
}
