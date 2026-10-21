package com.zhuanggui.pda.ui.screen

import android.app.Activity
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.LifecycleResumeEffect
import com.zhuanggui.pda.AppConfig
import com.zhuanggui.pda.BuildConfig
import com.zhuanggui.pda.R
import com.zhuanggui.pda.export.FileExporter

/**
 * 首页：顶部品牌 LOGO + 入口
 *  1. 出库扫码（主流程：柜号/数量/条码规则 → 装柜扫码 → 自动落盘）
 *  2. 继续装柜（快捷入口：最近有扫码记录时出现，一键带出最近的柜号续扫）
 *  3. 扫码记录查看
 *  4. WiFi 同步（v1.8.0：局域网配对/上传；未配对不影响扫码）
 *  5. 扫描设置（v1.9.1：新 PDA 首次装机引导 + 扫码自检）
 *
 * 注：老的「Excel 导入工单（防错校验）」已下架，源码存档见 archive/excel-workorder-v1.2.0/
 */
@Composable
fun HomeScreen(
    lastContainerNo: String?,
    syncPending: Int,
    onOutbound: () -> Unit,
    onContinueLast: (String) -> Unit,
    onRecords: () -> Unit,
    onSync: () -> Unit,
    onScannerSetup: () -> Unit
) {
    Scaffold { pad ->
        val context = LocalContext.current
        // 存储授权状态：进入/回到首页时复检（去系统设置授权完返回 → 提示自动消失）
        var hasStorage by remember { mutableStateOf(FileExporter.hasPermission(context)) }
        var storageStatus by remember { mutableStateOf(FileExporter.storageStatus()) }
        LifecycleResumeEffect(Unit) {
            hasStorage = FileExporter.hasPermission(context)
            storageStatus = FileExporter.storageStatus()
            onPauseOrDispose { }
        }
        Column(
            Modifier
                .padding(pad)
                .fillMaxSize()
                .padding(24.dp),
            verticalArrangement = Arrangement.Center,
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            // logo 为透明底黑字图：套白色圆角底卡（固定浅色主题见 Theme.kt）
            Image(
                painter = painterResource(R.drawable.logo),
                contentDescription = "惠而浦 Whirlpool",
                modifier = Modifier
                    .width(270.dp)
                    .background(Color.White, RoundedCornerShape(16.dp))
                    .padding(horizontal = 14.dp, vertical = 12.dp),
                contentScale = ContentScale.Fit
            )
            Spacer(Modifier.height(18.dp))
            Text("成品出库", fontSize = 40.sp, fontWeight = FontWeight.Bold)
            Text(
                "v${BuildConfig.VERSION_NAME}",
                fontSize = 16.sp,
                color = Color.Gray
            )
            Spacer(Modifier.height(26.dp))

            Button(
                onClick = {
                    // 首次进入引导存储授权（记录自动落盘要用）
                    if (!FileExporter.hasPermission(context)) {
                        (context as? Activity)?.let { FileExporter.requestPermission(it) }
                    }
                    onOutbound()
                },
                // v1.8.1：去掉默认阴影，避免大按钮投影压到下一个按钮的边框
                elevation = ButtonDefaults.buttonElevation(defaultElevation = 0.dp),
                modifier = Modifier.fillMaxWidth().height(88.dp)
            ) {
                Text("出库扫码", fontSize = 30.sp, fontWeight = FontWeight.Bold)
            }
            if (!hasStorage) {
                Text(
                    "⚠ 无法写入 /sdcard/zhuanggui：记录不会自动落盘；点上方按钮按提示授权后返回本页即消失",
                    fontSize = 12.sp, color = Color(0xFFD32F2F),
                    modifier = Modifier.padding(top = 6.dp)
                )
            } else if (storageStatus == FileExporter.StorageStatus.FALLBACK) {
                Text(
                    "⚠ 主目录不可写，记录已暂存到 App 私有目录；点此重新导出到 ${FileExporter.displayPath()}/",
                    fontSize = 12.sp, color = Color(0xFFF57F17),
                    modifier = Modifier
                        .clickable {
                            FileExporter.exportFallbackToPrimary()
                            storageStatus = FileExporter.storageStatus()
                            hasStorage = FileExporter.hasPermission(context)
                        }
                        .padding(top = 6.dp)
                )
            }

            Spacer(Modifier.height(18.dp))

            if (lastContainerNo != null) {
                // 继续装柜快捷入口：带出最近柜号进入设置页（自动载入原规则 + 已扫码数量）
                OutlinedButton(
                    onClick = { onContinueLast(lastContainerNo) },
                    modifier = Modifier.fillMaxWidth().height(64.dp)
                ) {
                    Text("继续装柜：$lastContainerNo", fontSize = 20.sp)
                }
                Spacer(Modifier.height(20.dp))
            }

            OutlinedButton(
                onClick = onRecords,
                modifier = Modifier.fillMaxWidth().height(64.dp)
            ) {
                Text("扫码记录查看", fontSize = 20.sp)
            }

            // v1.10.0：WiFi 同步默认下线（AppConfig.SYNC_ENABLED），代码保留，改常量即可恢复
            if (AppConfig.SYNC_ENABLED) {
                Spacer(Modifier.height(12.dp))
                OutlinedButton(
                    onClick = onSync,
                    modifier = Modifier.fillMaxWidth().height(56.dp)
                ) {
                    Text(
                        if (syncPending > 0) "WiFi 同步（$syncPending 条待传）" else "WiFi 同步",
                        fontSize = 18.sp
                    )
                }
            }

            Spacer(Modifier.height(10.dp))
            Text(
                "记录自动保存：${FileExporter.displayPath()}/",
                fontSize = 13.sp,
                color = Color.Gray
            )
            Text(
                "扫描设置（新 PDA 装机必看 / 扫码自检）",
                fontSize = 13.sp,
                color = Color(0xFF1976D2),
                modifier = Modifier
                    .clickable { onScannerSetup() }
                    .padding(top = 6.dp)
            )
        }
    }
}
