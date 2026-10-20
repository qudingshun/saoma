package com.zhuanggui.pda.ui.screen

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.zhuanggui.pda.ui.SyncUiState
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * WiFi 同步设置（v1.8.0）：配对 / 测试连接 / 立即同步 / 待传条数。
 * 局域网 HTTP，纯旁挂；未配对时不影响任何扫码功能。
 */
@Composable
fun SyncSettingsScreen(
    state: SyncUiState,
    pending: Int,
    onBack: () -> Unit,
    onStartPairing: () -> Unit,
    onCancelPairing: () -> Unit,
    onPairManual: (String) -> Unit,
    onTest: () -> Unit,
    onSyncNow: () -> Unit,
    onUnpair: () -> Unit,
    onClearMessage: () -> Unit,
    onRefresh: () -> Unit
) {
    var manualDialog by remember { mutableStateOf(false) }

    // 待传条数变化（自动同步完成 / 新扫码）时刷新「上次同步/错误」等状态
    androidx.compose.runtime.LaunchedEffect(pending) { onRefresh() }

    state.message?.let { msg ->
        AlertDialog(
            onDismissRequest = onClearMessage,
            title = { Text("WiFi 同步") },
            text = { Text(msg) },
            confirmButton = { TextButton(onClick = onClearMessage) { Text("知道了") } }
        )
    }

    if (manualDialog) {
        var text by remember { mutableStateOf("") }
        AlertDialog(
            onDismissRequest = { manualDialog = false },
            title = { Text("手动输入配对信息", fontSize = 17.sp) },
            text = {
                Column {
                    OutlinedTextField(
                        value = text,
                        onValueChange = { text = it },
                        label = { Text("IP:端口#令牌", fontSize = 12.sp) },
                        placeholder = { Text("192.168.1.100:8000#K7F2QW9M", fontSize = 12.sp) },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth()
                    )
                    Text(
                        "格式：电脑IP:端口#令牌（令牌见电脑端 data\\token.txt 或配对二维码）",
                        fontSize = 11.sp, color = Color.Gray,
                        modifier = Modifier.padding(top = 4.dp)
                    )
                }
            },
            confirmButton = {
                TextButton(
                    onClick = { manualDialog = false; onPairManual(text) },
                    enabled = text.isNotBlank()
                ) { Text("连接") }
            },
            dismissButton = { TextButton(onClick = { manualDialog = false }) { Text("取消") } }
        )
    }

    Scaffold { pad ->
        Column(
            Modifier
                .padding(pad)
                .fillMaxSize()
                .padding(horizontal = 12.dp, vertical = 6.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            Row(
                Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text("WiFi 同步", fontSize = 20.sp, fontWeight = FontWeight.Bold)
                OutlinedButton(
                    onClick = onBack,
                    contentPadding = PaddingValues(horizontal = 12.dp),
                    modifier = Modifier.height(36.dp)
                ) { Text("返回", fontSize = 12.sp) }
            }

            Card(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(horizontal = 12.dp, vertical = 8.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(
                            if (state.paired) "已配对" else "未配对",
                            fontSize = 16.sp, fontWeight = FontWeight.Bold,
                            color = if (state.paired) Color(0xFF2E7D32) else Color(0xFFD32F2F)
                        )
                        Spacer(Modifier.weight(1f))
                        Text("待传 $pending 条", fontSize = 13.sp,
                            color = if (pending > 0) Color(0xFFF57F17) else Color.Gray)
                    }
                    HorizontalDivider(Modifier.padding(vertical = 4.dp))
                    InfoLine("服务器", state.baseUrl.ifBlank { "—" })
                    InfoLine("设备号", state.deviceId.ifBlank { "—" })
                    InfoLine("服务器时间", state.serverTime.ifBlank { "—" })
                    InfoLine("上次同步", fmtTime(state.lastSyncAt))
                    if (state.lastError.isNotBlank()) {
                        Text(
                            state.lastError,
                            fontSize = 11.sp, color = Color(0xFFD32F2F),
                            modifier = Modifier.padding(top = 3.dp)
                        )
                    }
                    Text(
                        "扫码后 5 秒自动上传；后台每 15 分钟补传一次；未配对时不影响扫码。",
                        fontSize = 10.sp, color = Color.Gray,
                        modifier = Modifier.padding(top = 4.dp)
                    )
                }
            }

            if (state.pairing) {
                Text(
                    "配对模式：请用扫码枪扫描电脑屏幕上的配对二维码（或点「取消」）",
                    fontSize = 12.sp, color = Color(0xFF1976D2),
                    modifier = Modifier
                        .fillMaxWidth()
                        .background(Color(0xFFE3F2FD), RoundedCornerShape(4.dp))
                        .padding(8.dp)
                )
            }

            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                Button(
                    onClick = if (state.pairing) onCancelPairing else onStartPairing,
                    enabled = !state.busy,
                    contentPadding = PaddingValues(horizontal = 8.dp),
                    modifier = Modifier.weight(1f).height(44.dp)
                ) {
                    Text(if (state.pairing) "取消配对模式" else "扫描配对码", fontSize = 13.sp)
                }
                OutlinedButton(
                    onClick = { manualDialog = true },
                    enabled = !state.busy,
                    contentPadding = PaddingValues(horizontal = 8.dp),
                    modifier = Modifier.weight(1f).height(44.dp)
                ) { Text("手动输入", fontSize = 13.sp) }
            }

            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                OutlinedButton(
                    onClick = onTest,
                    enabled = !state.busy && state.paired,
                    contentPadding = PaddingValues(horizontal = 8.dp),
                    modifier = Modifier.weight(1f).height(44.dp)
                ) { Text(if (state.busy) "处理中…" else "测试连接", fontSize = 13.sp) }
                Button(
                    onClick = onSyncNow,
                    enabled = !state.busy && state.paired,
                    contentPadding = PaddingValues(horizontal = 8.dp),
                    modifier = Modifier.weight(1f).height(44.dp)
                ) { Text("立即同步", fontSize = 13.sp) }
            }

            if (state.paired) {
                OutlinedButton(
                    onClick = onUnpair,
                    enabled = !state.busy,
                    contentPadding = PaddingValues(horizontal = 8.dp),
                    modifier = Modifier.fillMaxWidth().height(40.dp)
                ) { Text("解除配对", fontSize = 12.sp, color = Color(0xFFD32F2F)) }
            }

            Text(
                "说明：电脑端运行「装柜 PDA 同步服务」后，用二维码或手动输入配对；" +
                    "同步只走局域网，不访问外网。所有扫码记录先落本地，断网不影响使用。",
                fontSize = 11.sp, color = Color.Gray
            )
        }
    }
}

@Composable
private fun InfoLine(label: String, value: String) {
    Row(Modifier.fillMaxWidth().padding(vertical = 1.dp)) {
        Text(label, fontSize = 12.sp, color = Color.Gray, modifier = Modifier.weight(1f))
        Text(value, fontSize = 12.sp, color = Color(0xFF333333))
    }
}

private fun fmtTime(ms: Long): String =
    if (ms <= 0) "—" else SimpleDateFormat("MM-dd HH:mm:ss", Locale.CHINA).format(Date(ms))
