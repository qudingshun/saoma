package com.zhuanggui.pda.ui.theme

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

private val LightColors = lightColorScheme(
    primary = Color(0xFF1565C0),
    secondary = Color(0xFF00695C),
    background = Color(0xFFF5F5F5)
)

/**
 * 固定浅色主题（审计 23）：现场界面大量按浅色硬编码（仓储光线充足场景），
 * 原先跟随系统深色只会得到「半深半浅」的不可用界面。如需深色，
 * 需先把各屏硬编码色全量并入 colorScheme 再放开。
 */
@Composable
fun ZhuangGuiTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = LightColors,
        content = content
    )
}
