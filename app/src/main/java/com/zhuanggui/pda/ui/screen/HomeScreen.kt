package com.zhuanggui.pda.ui.screen

import android.app.Activity
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
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
import com.zhuanggui.pda.BuildConfig
import com.zhuanggui.pda.R
import com.zhuanggui.pda.export.FileExporter

/**
 * 首页：顶部品牌 LOGO + 两个入口
 *  1. 出库扫码（主流程：柜号/数量/条码规则 → 装柜扫码 → 自动落盘）
 *  2. 扫码记录查看
 *
 * 注：老的「Excel 导入工单（防错校验）」已下架，源码存档见 archive/excel-workorder-v1.2.0/
 */
@Composable
fun HomeScreen(
    onOutbound: () -> Unit,
    onRecords: () -> Unit
) {
    Scaffold { pad ->
        val context = LocalContext.current
        // 存储授权状态：进入/回到首页时复检（去系统设置授权完返回 → 提示自动消失）
        var hasStorage by remember { mutableStateOf(FileExporter.hasPermission(context)) }
        LifecycleResumeEffect(Unit) {
            hasStorage = FileExporter.hasPermission(context)
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
            // logo 为透明底黑字图：套白色圆角底卡，深色模式下也清晰
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
            Spacer(Modifier.height(44.dp))

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
            if (!hasStorage) {
                Text(
                    "⚠ 未授权「所有文件访问」：记录不会自动落盘；点上方按钮按提示授权后返回本页即消失",
                    fontSize = 12.sp, color = Color(0xFFD32F2F),
                    modifier = Modifier.padding(top = 6.dp)
                )
            }

            Spacer(Modifier.height(20.dp))

            OutlinedButton(
                onClick = onRecords,
                modifier = Modifier.fillMaxWidth().height(64.dp)
            ) {
                Text("扫码记录查看", fontSize = 20.sp)
            }

            Spacer(Modifier.height(40.dp))
            Text(
                "记录自动保存：${FileExporter.displayPath()}/",
                fontSize = 13.sp,
                color = Color.Gray
            )
        }
    }
}
