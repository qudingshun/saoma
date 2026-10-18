package com.zhuanggui.pda

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import com.zhuanggui.pda.ui.AppRoot
import com.zhuanggui.pda.ui.theme.ZhuangGuiTheme

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            ZhuangGuiTheme {
                AppRoot()
            }
        }
    }
}
