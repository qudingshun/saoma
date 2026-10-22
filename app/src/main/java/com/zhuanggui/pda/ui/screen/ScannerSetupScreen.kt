package com.zhuanggui.pda.ui.screen

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.zhuanggui.pda.scan.ScanEvent
import com.zhuanggui.pda.scan.ScannerAppStatus
import com.zhuanggui.pda.scan.ScannerDiagnostics
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * 扫描设置（新 PDA 装机必看，v1.9.1）
 *
 * 作用：把「PDA 端扫描设置」这一步做成 App 内的引导 + 自检
 *  1) 检查系统扫描服务（扫描配置 / 扫描服务）是否安装、是否启用；
 *  2) 一键打开系统「扫描配置」应用（按包名直接启动，不用翻桌面）；
 *  3) 扫码自检：模拟广播 + 显示最近收到的真实扫码，确认「扳机 → 系统解码 → 广播 → App」通；
 *  4) 现场照抄的配置步骤（可复制）。
 */
@Composable
fun ScannerSetupScreen(
    lastScan: ScanEvent?,
    onBack: () -> Unit
) {
    val context = LocalContext.current
    var status by remember { mutableStateOf<List<ScannerAppStatus>>(emptyList()) }
    var message by remember { mutableStateOf<String?>(null) }

    // 进入/回到本页时复查系统扫描服务状态
    LaunchedEffect(Unit) {
        status = ScannerDiagnostics.status(context)
    }

    Scaffold { pad ->
        Column(
            Modifier
                .padding(pad)
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 12.dp, vertical = 6.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            Row(
                Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text("扫描设置", fontSize = 20.sp, fontWeight = FontWeight.Bold)
                OutlinedButton(
                    onClick = onBack,
                    contentPadding = PaddingValues(horizontal = 12.dp),
                    modifier = Modifier.height(36.dp)
                ) { Text("返回", fontSize = 12.sp) }
            }

            // ---------- 系统扫描服务状态 ----------
            Card(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(horizontal = 12.dp, vertical = 8.dp)) {
                    Text("① 系统扫描服务", fontSize = 15.sp, fontWeight = FontWeight.Bold)
                    Text(
                        "扫码解码由 PDA 自带的「扫描配置 / 扫描工具」完成，必须保持启用。",
                        fontSize = 11.sp, color = Color.Gray, modifier = Modifier.padding(top = 2.dp)
                    )
                    status.forEach { s -> ScannerRow(s, context, onMessage = { message = it }) }
                    Spacer(Modifier.height(4.dp))
                    Button(
                        onClick = {
                            val ok = ScannerDiagnostics.openScannerConfig(context)
                            message = if (ok) "已打开系统扫描配置，请按下方步骤设置"
                            else "未找到系统扫描配置应用，请在桌面手动打开「扫描配置/扫描设置」"
                        },
                        contentPadding = PaddingValues(horizontal = 10.dp),
                        modifier = Modifier.fillMaxWidth().height(46.dp)
                    ) { Text("一键打开「扫描配置」", fontSize = 14.sp) }
                    Spacer(Modifier.height(4.dp))
                    OutlinedButton(
                        onClick = {
                            val ok = ScannerDiagnostics.openScannerDevOptions(context)
                            message = if (ok) "已打开扫描工具的开发者选项：那里显示本机实际的广播名称与键值（多数机型不需要密码）"
                            else "该机型没有开发者选项入口，可看下方默认参数"
                        },
                        contentPadding = PaddingValues(horizontal = 10.dp),
                        modifier = Modifier.fillMaxWidth().height(40.dp)
                    ) { Text("打开开发者选项（查看本机广播名/键值）", fontSize = 12.sp) }
                }
            }

            // ---------- 输出方式（v1.10.2：只认广播） ----------
            Card(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(horizontal = 12.dp, vertical = 8.dp)) {
                    Text("② 输出方式：广播（唯一记录通道）", fontSize = 15.sp, fontWeight = FontWeight.Bold)
                    Text(
                        "「扫描配置 → 输出方式」请选「广播」。v1.10.2 起 App 只认广播，" +
                            "键盘注入不参与记录；同一次扳机若因双通道在 800ms 内到达两遍，也只记一条。",
                        fontSize = 11.sp, color = Color.Gray, modifier = Modifier.padding(top = 2.dp)
                    )
                }
            }

            // ---------- 扫码自检 ----------
            Card(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(horizontal = 12.dp, vertical = 8.dp)) {
                    Text("③ 扫码自检", fontSize = 15.sp, fontWeight = FontWeight.Bold)
                    Text(
                        "用扫码枪扫任意条码，这里能显示内容就说明链路已通（无需进入装柜页）。",
                        fontSize = 11.sp, color = Color.Gray, modifier = Modifier.padding(top = 2.dp)
                    )
                    Spacer(Modifier.height(6.dp))
                    val ev = lastScan
                    if (ev == null) {
                        Text(
                            "还没有收到扫码数据",
                            fontSize = 13.sp, color = Color(0xFFF57F17)
                        )
                    } else {
                        Text(
                            "最近收到：${ev.data}",
                            fontSize = 13.sp, color = Color(0xFF2E7D32)
                        )
                        Text(
                            "来源广播：${ev.action}\n时间：${fmtTime(ev.at)}",
                            fontSize = 11.sp, color = Color.Gray
                        )
                    }
                    Spacer(Modifier.height(6.dp))
                    Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        Button(
                            onClick = {
                                ScannerDiagnostics.selfTest(context)
                                message = "已发送测试广播：上方若出现「自检测试-OK…」说明 App 收码正常；再用扫码枪实测一次"
                            },
                            contentPadding = PaddingValues(horizontal = 8.dp),
                            modifier = Modifier.weight(1f).height(44.dp)
                        ) { Text("模拟扫码（自检）", fontSize = 12.sp) }
                        OutlinedButton(
                            onClick = { copyText(context, ScannerDiagnostics.SETUP_STEPS); message = "配置步骤已复制，可粘贴给现场人员" },
                            contentPadding = PaddingValues(horizontal = 8.dp),
                            modifier = Modifier.weight(1f).height(44.dp)
                        ) { Text("复制配置步骤", fontSize = 12.sp) }
                    }
                }
            }

            // ---------- 配置步骤 ----------
            Card(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(horizontal = 12.dp, vertical = 8.dp)) {
                    Text("④ 扫描设置步骤（照着填）", fontSize = 15.sp, fontWeight = FontWeight.Bold)
                    HorizontalDivider(Modifier.padding(vertical = 4.dp))
                    ScannerDiagnostics.SETUP_STEPS.lines().forEach { line ->
                        Text(line, fontSize = 11.sp, color = Color(0xFF333333))
                    }
                }
            }

            message?.let {
                Card(Modifier.fillMaxWidth()) {
                    Column(Modifier.padding(horizontal = 12.dp, vertical = 8.dp)) {
                        Text(it, fontSize = 12.sp, color = Color(0xFF1976D2))
                        TextButton(onClick = { message = null }) { Text("知道了", fontSize = 12.sp) }
                    }
                }
            }

            Text(
                "提示：扫描设置按「包名」绑定，重装 App 不会丢失；如果换了新的 PDA，" +
                    "请在这台机器上重新做一次上面的设置。",
                fontSize = 11.sp, color = Color.Gray
            )
        }
    }
}

@Composable
private fun ScannerRow(s: ScannerAppStatus, context: Context, onMessage: (String) -> Unit) {
    Column(Modifier.fillMaxWidth().padding(top = 6.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(s.label, fontSize = 13.sp, modifier = Modifier.weight(1f))
            val (text, color) = when {
                !s.installed -> "未安装" to Color(0xFF9E9E9E)
                !s.enabled -> "已停用" to Color(0xFFD32F2F)
                s.launchable -> "已启用" to Color(0xFF2E7D32)
                else -> "已启用（无启动图标）" to Color(0xFF2E7D32)
            }
            Text(text, fontSize = 12.sp, color = color)
        }
        Text(s.packageName, fontSize = 10.sp, color = Color.Gray)
        if (s.installed && !s.enabled) {
            OutlinedButton(
                onClick = {
                    val ok = ScannerDiagnostics.openAppDetails(context, s.packageName)
                    onMessage(if (ok) "已打开系统应用信息页，请把「扫描服务」启用"
                    else "无法打开系统设置，请手动到 设置 → 应用 → 找到该扫描服务并启用")
                },
                contentPadding = PaddingValues(horizontal = 8.dp),
                modifier = Modifier.height(34.dp)
            ) { Text("去启用该服务", fontSize = 12.sp) }
        }
    }
}

private fun fmtTime(ms: Long): String =
    SimpleDateFormat("MM-dd HH:mm:ss", Locale.CHINA).format(Date(ms))

private fun copyText(context: Context, text: String) {
    runCatching {
        val cm = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        cm.setPrimaryClip(ClipData.newPlainText("扫描设置步骤", text))
    }
}
