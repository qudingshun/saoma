package com.zhuanggui.pda.ui

import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/**
 * 紧凑单行输入框（v1.7.1：设置页 / 扫码页空间紧张，替代 Material OutlinedTextField）。
 *
 * 与 M3 OutlinedTextField 的区别：
 *  - 固定 42dp 高（OutlinedTextField 最小 56dp，且强制压缩高度会出现标签/边框画到框外的问题）
 *  - 标签固定在框内左上角（不再浮动），焦点/输入时位置稳定，适合现场快速扫录
 *  - 文字 14sp、标签 9sp、占位 10sp，边框 1dp
 *
 * @param numeric 数字键盘（流水号 / 数量）
 * @param trailing 右侧附加控件（如工号下拉箭头），占 30dp
 * @param onFocusChanged 焦点回调（设置页用于工号栏的扫码路由）
 */
@Composable
fun CompactTextField(
    value: String,
    onValueChange: (String) -> Unit,
    label: String,
    modifier: Modifier = Modifier,
    placeholder: String? = null,
    readOnly: Boolean = false,
    enabled: Boolean = true,
    numeric: Boolean = false,
    textSize: TextUnit = 14.sp,
    textColor: Color = Color(0xFF1B1B1B),
    trailing: (@Composable () -> Unit)? = null,
    onFocusChanged: ((Boolean) -> Unit)? = null,
    onDone: (() -> Unit)? = null,
    focusRequester: FocusRequester? = null
) {
    val borderColor = if (enabled) Color(0xFF8A8A8A) else Color(0xFFBDBDBD)
    Box(
        modifier
            .fillMaxWidth()
            .height(42.dp)
            .border(1.dp, borderColor, RoundedCornerShape(4.dp))
    ) {
        Column(
            Modifier
                .fillMaxSize()
                .padding(start = 8.dp, end = if (trailing != null) 32.dp else 8.dp, top = 2.dp, bottom = 2.dp)
        ) {
            Text(
                label,
                fontSize = 9.sp,
                lineHeight = 10.sp,
                color = if (enabled) Color(0xFF757575) else Color(0xFFAAAAAA),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            Box(Modifier.weight(1f).fillMaxWidth(), contentAlignment = Alignment.CenterStart) {
                if (value.isEmpty() && placeholder != null) {
                    Text(
                        placeholder,
                        fontSize = 10.sp,
                        color = Color(0xFF9E9E9E),
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }
                BasicTextField(
                    value = value,
                    onValueChange = onValueChange,
                    readOnly = readOnly,
                    enabled = enabled,
                    singleLine = true,
                    textStyle = TextStyle(fontSize = textSize, color = textColor),
                    cursorBrush = SolidColor(Color(0xFF1976D2)),
                    keyboardOptions = KeyboardOptions(
                        keyboardType = if (numeric) KeyboardType.Number else KeyboardType.Text,
                        imeAction = if (onDone != null) ImeAction.Done else ImeAction.Default
                    ),
                    keyboardActions = KeyboardActions(onDone = { onDone?.invoke() }),
                    modifier = Modifier
                        .fillMaxSize()
                        .then(if (focusRequester != null) Modifier.focusRequester(focusRequester) else Modifier)
                        .then(
                            if (onFocusChanged != null)
                                Modifier.onFocusChanged { onFocusChanged(it.isFocused) }
                            else Modifier
                        )
                )
            }
        }
        if (trailing != null) {
            Box(
                Modifier
                    .align(Alignment.CenterEnd)
                    .size(30.dp),
                contentAlignment = Alignment.Center
            ) { trailing() }
        }
    }
}
