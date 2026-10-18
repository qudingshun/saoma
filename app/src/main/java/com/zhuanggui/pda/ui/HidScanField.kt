package com.zhuanggui.pda.ui

import android.os.SystemClock
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Button
import androidx.compose.material3.OutlinedTextField
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
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.delay

/**
 * HID 扫码枪录入框（与 CRUISE2 真机实测配置同步，2026-10-18 adb 读取）：
 *
 * 真机当前配置：
 *  - 输出方式 = 输入法(HID)模式，默认输入法 com.dwime.lds_for_seuic/.XFMIme
 *  - config_enable_scanner_auto_commit = 0 → 扫描后【不自动回车】，无任何后缀
 *  - 无广播输出（系统里没有任何扫码广播发出）
 *
 * 因此提交策略：
 *  1. 回车（ImeAction.Done）→ 提交（兼容以后在扫描设置里开了自动回车的机器）
 *  2. 没有回车时按“击键速度”判定：整串字符平均间隔 <60ms 且 ≥4 字符 = 扫码枪输入，
 *     停止输入 150ms 后自动提交；人工打字平均 >80ms/字符，不会误触发
 *  3. 过滤 \r \n \t（部分机型配置了回车后缀，字符会被打进文本而不是触发回车）
 *  4. 进页面自动聚焦；每次提交后清空并重新聚焦 —— 连续扫码全程不用碰屏幕
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
    LaunchedEffect(Unit) {
        runCatching { focusRequester.requestFocus() }
    }

    fun submit() {
        val v = code.trim()
        code = ""
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
        horizontalArrangement = Arrangement.spacedBy(6.dp)
    ) {
        OutlinedTextField(
            value = code,
            onValueChange = { v ->
                detector.onKey()
                code = v.filter { it != '\r' && it != '\n' && it != '\t' }
            },
            label = { Text(label) },
            singleLine = true,
            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
            keyboardActions = KeyboardActions(onDone = { submit() }),
            modifier = Modifier.weight(1f).focusRequester(focusRequester)
        )
        Button(
            onClick = { submit() },
            enabled = code.isNotBlank()
        ) { Text("录入", fontSize = 16.sp) }
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
}
