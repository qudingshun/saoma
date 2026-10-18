package com.zhuanggui.pda.ui.screen

import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp

/**
 * 三次确认删除弹窗（防误触）：依次弹出 3 个确认框，全部确认后才执行删除。
 * 用于：扫码页删除「正常」记录、扫码记录页删除分组/本日/批量、清空回收站。
 *
 * [steps] 依次为三次确认的说明文案（不足时重复最后一条）。
 */
@Composable
fun TripleConfirmDialog(
    title: String,
    steps: List<String>,
    confirmLabel: String = "确认删除",
    onConfirmed: () -> Unit,
    onDismiss: () -> Unit
) {
    var step by remember { mutableStateOf(1) }
    val total = 3

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("$title（第 $step / $total 次确认）", fontSize = 17.sp, fontWeight = FontWeight.Bold) },
        text = { Text(steps.getOrElse(step - 1) { steps.lastOrNull().orEmpty() }, fontSize = 13.sp) },
        confirmButton = {
            TextButton(onClick = {
                if (step >= total) onConfirmed() else step += 1
            }) {
                Text(if (step >= total) confirmLabel else "继续确认", fontSize = 13.sp)
            }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("取消", fontSize = 13.sp) } }
    )
}
