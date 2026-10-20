package com.zhuanggui.pda.ui

import android.os.SystemClock
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.material3.Button
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.delay

/**
 * HID 扫码枪录入框（兜底通道）。
 *
 * 设备端「扫描配置」当前为【广播】输出（主通道 = ScannerManager，见 readme）；
 * 本输入框用于：把扫描配置改回「输入法(HID)模式」（默认输入法
 * com.dwime.lds_for_seuic/.XFMIme，且不自动回车）时的键盘模拟录入。
 *
 * 提交策略：
 *  1. 回车（ImeAction.Done）→ 提交（兼容在扫描设置里开了自动回车的机器）
 *  2. 没有回车时按“击键速度”判定：整串字符平均间隔 <60ms 且 ≥4 字符 = 扫码枪输入，
 *     停止输入 150ms 后自动提交；人工打字平均 >80ms/字符，不会误触发
 *  3. 过滤 \r \n \t（部分机型配置了回车后缀，字符会被打进文本而不是触发回车）
 *  4. 进页面自动聚焦；每次提交后清空并重新聚焦 —— 连续扫码全程不用碰屏幕
 *  5. v1.5.1 起：只有“追加字符”才计击键，删除/替换会重置判定（杜绝退格连删被误判成扫码）
 */
@Composable
fun HidScanField(
    onSubmit: (String) -> Unit,
    modifier: Modifier = Modifier,
    label: String = "扫码 / 手动录入"
) {
    val keyboard = LocalSoftwareKeyboardController.current
    var code by remember { mutableStateOf("") }
    val focusRequester = remember { FocusRequester() }
    val detector = remember { BurstDetector() }

    // 进入页面自动聚焦：扫码枪的字符只能进入“聚焦中”的输入框
    // 但不主动弹软键盘：把屏幕留给计数/记录区（点输入框仍可手动录入）
    LaunchedEffect(Unit) {
        runCatching { focusRequester.requestFocus() }
        delay(150)
        keyboard?.hide()
    }

    fun submit() {
        val v = code.trim()
        code = ""
        detector.reset()
        if (v.isNotEmpty()) onSubmit(v)
        // 保持聚焦（点了别处也能扫下一枪），收起软键盘腾出屏幕
        runCatching { focusRequester.requestFocus() }
        keyboard?.hide()
    }

    // 扫码枪 burst 收尾检测：停止输入 150ms 且确认是快速 burst → 自动提交
    LaunchedEffect(code) {
        if (code.isBlank()) return@LaunchedEffect
        val snapshot = code
        delay(150)
        // 150ms 内没有新字符（code 未变）且判定为扫码枪输入
        if (code == snapshot && detector.isScannerBurst) submit()
    }

    Row(
        modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(4.dp)
    ) {
        CompactTextField(
            value = code,
            onValueChange = { v ->
                val filtered = v.filter { it != '\r' && it != '\n' && it != '\t' }
                when {
                    // 仅“追加字符”算扫码枪击键（真机 HID 输入实际只走这条路径）
                    filtered.length > code.length && filtered.startsWith(code) -> detector.onKey()
                    // 退格 / 删除（长度变短）→ 程序化修改，重置判定，防止连续删除被误判为扫码
                    filtered.length < code.length -> detector.reset()
                    // 等长（替换）→ 中性，不影响当前判定
                }
                code = filtered
            },
            label = label,
            onDone = { submit() },
            focusRequester = focusRequester,
            modifier = Modifier.weight(1f)
        )
        Button(
            onClick = { submit() },
            enabled = code.isNotBlank(),
            contentPadding = PaddingValues(horizontal = 14.dp),
            modifier = Modifier.height(38.dp)
        ) { Text("录入", fontSize = 13.sp) }
    }
}

/**
 * 击键速度检测：扫码枪 HID 注入每字符间隔 <30ms，人工打字平均 >80ms。
 * 连续 ≥4 个字符且平均间隔 <60ms → 判定为扫码枪输入。
 */
private class BurstDetector {
    private var lastTs = 0L
    private var burstStart = 0L
    private var count = 0

    var isScannerBurst = false
        private set

    fun onKey() {
        val now = SystemClock.elapsedRealtime()
        // 间隔 >200ms 视为新一段输入
        if (now - lastTs > 200) {
            burstStart = now
            count = 1
        } else {
            count++
        }
        lastTs = now
        isScannerBurst = count >= 4 && (now - burstStart) / count < 60
    }

    /** 复位（删除字符 / 提交完成 / 输入被程序化修改时调用） */
    fun reset() {
        lastTs = 0L
        burstStart = 0L
        count = 0
        isScannerBurst = false
    }
}
