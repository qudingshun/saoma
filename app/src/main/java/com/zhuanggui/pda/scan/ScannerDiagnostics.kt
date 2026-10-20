package com.zhuanggui.pda.scan

import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.provider.Settings
import android.util.Log

/**
 * 扫描设置诊断（v1.9.1）：新 PDA 装机后的一键引导。
 *
 * 背景：扫码解码由 PDA 系统自带的「扫描配置 / 扫描工具」完成，需要把条码
 * 通过广播发给本 App（com.zhuanggui.pda），这一步在系统应用里设置，
 * App 无法直接改写系统配置，因此本工具做三件事：
 *  1) 检查系统扫描服务是否安装 / 启用；
 *  2) 一键打开系统「扫描配置」应用（不用到处找图标）；
 *  3) 扫码自检（模拟广播 + 显示最近收到的真实扫码），确认链路是否打通。
 */
data class ScannerAppStatus(
    val packageName: String,
    val label: String,
    val installed: Boolean,
    val enabled: Boolean,
    val launchable: Boolean
)

object ScannerDiagnostics {
    private const val TAG = "ScannerDiag"

    /** 常见机型上的扫描相关系统包（按优先级） */
    private val CANDIDATES = listOf(
        "com.seuic.scanner.activity" to "扫描配置 / 扫描工具（东集）",
        "com.seuic.scanservice" to "扫描服务（东集）",
        "com.android.server.scannerservice" to "系统扫码服务",
        "com.seuic.scanner" to "扫描设置（部分固件）"
    )

    /** 现场照着做的配置步骤（可一键复制） */
    val SETUP_STEPS: String = """
【PDA 端扫描设置（东集 CRUISE2 等）】

1. 打开 PDA 自带的「扫描配置」应用（App 内「扫描设置」页可一键打开）。
2. 进入「应用扫描配置」→「已配置应用」→ 选择「成品出库」。
3. 条码发送方式：广播
4. 广播名称：${ScannerManager.PRIMARY_ACTION}
   （本机实际值可在「扫描工具 → 设置 → 开发者选项」查看）
5. 键值（extra）：${ScannerManager.PRIMARY_EXTRA}
6. 结束符：ENTER（或不填）
7. 保存。

方式 B（键盘输出）：不配置广播，扫码内容直接输入到当前聚焦的输入框；
   本 App 扫码页的输入框会自动聚焦并接收（HID 兜底），但只有扫码页能接收。

注意：
- 不要停用/卸载「扫描工具 / 扫描服务」，否则完全扫不出码。
- 若有「禁止将扫描数据发送到第三方应用 / PDA 特色设置」之类选项，需要关闭。
- 广播输出与键盘输出二选一，同时开启同一枪会产生两条数据。
- 配置按「包名」生效，重装本 App 不会丢失。
""".trimIndent()

    /** 扫描相关系统应用的安装 / 启用状态 */
    fun status(context: Context): List<ScannerAppStatus> {
        val pm = context.packageManager
        return CANDIDATES.map { (pkg, label) ->
            var installed = false
            var enabled = false
            var launchable = false
            runCatching {
                val info = pm.getPackageInfo(pkg, 0)
                installed = true
                enabled = info.applicationInfo?.enabled == true
                launchable = pm.getLaunchIntentForPackage(pkg) != null
            }.onFailure { Log.d(TAG, "package $pkg not found") }
            ScannerAppStatus(pkg, label, installed, enabled, launchable)
        }
    }

    /** 显式 Activity 兜底：部分固件的扫描配置没有启动图标，只能按类名启动 */
    private val EXPLICIT_ACTIVITIES = listOf(
        "com.seuic.scanner.activity.MainActivity" to "com.seuic.scanner.activity",
        "com.seuic.scanner.activity.SettingActivity" to "com.seuic.scanner.activity",
        "com.seuic.scanner.activity.ScannerSettingActivity" to "com.seuic.scanner.activity",
        "com.seuic.scanner.activity.ScanSettingActivity" to "com.seuic.scanner.activity",
        "com.seuic.scanservice.MainActivity" to "com.seuic.scanservice",
        "com.seuic.scanservice.SettingActivity" to "com.seuic.scanservice"
    )

    /** 一键打开系统「扫描配置」；成功返回 true */
    fun openScannerConfig(context: Context): Boolean {
        val pm = context.packageManager
        for ((pkg, _) in CANDIDATES) {
            val intent = runCatching { pm.getLaunchIntentForPackage(pkg) }.getOrNull() ?: continue
            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            runCatching { context.startActivity(intent) }
                .onSuccess { return true }
                .onFailure { Log.d(TAG, "start $pkg failed: ${it.message}") }
        }
        // 兜底：按类名启动
        for ((cls, pkg) in EXPLICIT_ACTIVITIES) {
            val intent = Intent().apply {
                component = android.content.ComponentName(pkg, cls)
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            runCatching { context.startActivity(intent) }
                .onSuccess { return true }
                .onFailure { Log.d(TAG, "start $cls failed: ${it.message}") }
        }
        return false
    }

    /** 打开某个系统应用的「应用信息」页（用于启用被停用的扫描服务） */
    fun openAppDetails(context: Context, packageName: String): Boolean = runCatching {
        val intent = Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS).apply {
            data = Uri.parse("package:$packageName")
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        context.startActivity(intent)
        true
    }.getOrElse {
        Log.d(TAG, "open app details failed: ${it.message}")
        false
    }

    /**
     * 模拟一次扫码广播（只发给本 App），用于验证「广播 → App」这段链路。
     * 真实扫码枪请用「扫码自检」里的实时显示确认。
     */
    fun selfTest(context: Context) {
        val intent = Intent(ScannerManager.PRIMARY_ACTION).apply {
            setPackage(context.packageName)
            putExtra(ScannerManager.PRIMARY_EXTRA, "自检测试-OK-${System.currentTimeMillis() % 100000}")
        }
        context.sendBroadcast(intent)
    }
}
