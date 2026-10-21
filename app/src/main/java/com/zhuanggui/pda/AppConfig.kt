package com.zhuanggui.pda

/**
 * 功能开关（v1.10.0 新增）：现场按需上下线，**代码全部保留**，改一个常量即可恢复。
 *
 * - [SYNC_ENABLED]：WiFi 同步（扫码记录回传电脑 + 规则库同步）。
 *   v1.10.0 起默认关闭：多台 PDA 现场只做「本机 /sdcard/zhuanggui 取文件」，
 *   减少操作步骤与后台耗电；需要时改回 true 并重装即可（配对信息仍在 sync_state 里）。
 */
object AppConfig {
    /** WiFi 同步总开关（首页入口 + 后台周期同步一起受控） */
    const val SYNC_ENABLED: Boolean = false
}
