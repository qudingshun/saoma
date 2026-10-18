package com.zhuanggui.pda.ui.screen

import android.app.Activity
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.zhuanggui.pda.BuildConfig
import com.zhuanggui.pda.export.FileExporter

/**
 * 首页：两个入口
 *  1. 出库扫码（主流程：柜号/数量/条码规则 → 装柜扫码 → 自动落盘）
 *  2. Excel 导入工单（老功能：清单校验防错流程）
 */
@Composable
fun HomeScreen(
    onOutbound: () -> Unit,
    onLegacy: () -> Unit,
    onRecords: () -> Unit
) {
    Scaffold { pad ->
        val context = LocalContext.current
        Column(
            Modifier
                .padding(pad)
                .fillMaxSize()
                .padding(24.dp),
            verticalArrangement = Arrangement.Center,
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Text("装柜防错", fontSize = 40.sp, fontWeight = FontWeight.Bold)
            Text(
                "v${BuildConfig.VERSION_NAME}",
                fontSize = 16.sp,
                color = Color.Gray
            )
            Spacer(Modifier.height(48.dp))

            Button(
                onClick = {
                    // 首次进入引导存储授权（记录自动落盘要用）
                    if (!FileExporter.hasPermission(context)) {
                        (context as? Activity)?.let { FileExporter.requestPermission(it) }
                    }
                    onOutbound()
                },
                modifier = Modifier.fillMaxWidth().height(88.dp)
            ) {
                Text("出库扫码", fontSize = 30.sp, fontWeight = FontWeight.Bold)
            }

            Spacer(Modifier.height(20.dp))

            OutlinedButton(
                onClick = onLegacy,
                modifier = Modifier.fillMaxWidth().height(72.dp)
            ) {
                Text("Excel导入工单（防错校验）", fontSize = 20.sp)
            }

            Spacer(Modifier.height(16.dp))

            OutlinedButton(
                onClick = onRecords,
                modifier = Modifier.fillMaxWidth().height(64.dp)
            ) {
                Text("扫码记录查看", fontSize = 20.sp)
            }

            Spacer(Modifier.height(40.dp))
            Text(
                "记录自动保存：/sdcard/ZhuangGui/装柜记录/",
                fontSize = 13.sp,
                color = Color.Gray
            )
        }
    }
}
