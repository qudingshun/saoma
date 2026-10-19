package com.zhuanggui.pda.scan

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.Build
import android.util.Log
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.asSharedFlow

/**
 * 扫码数据采集。
 *
 * 东集 CRUISE2 工业 PDA 数据来源：
 *  1. 官方配置广播（本项目采用，实机验证）：系统“扫描配置”为应用下发广播，
 *     实测 action = com.android.server.scannerservice.broadcast，extra = scannerdata。
 *     注意：广播由“系统扫码服务”这个外部 App 发出，必须用 RECEIVER_EXPORTED 注册才能收到。
 *     广播来源无法校验（系统限制，任何应用都可伪造同名广播）——接受为已知限制：收到即用并记 Log 留痕。
 *  2. HID 键盘模拟（备选兜底）：扫描内容以键盘输入进入焦点控件（扫码页录入框自动获得焦点）。
 *
 * 新机型/广播动作只需在 LISTEN_ACTIONS / EXTRA_KEYS 中追加（放第一优先）。
 */
class ScannerManager(private val context: Context) {

    private val _scans = MutableSharedFlow<String>(extraBufferCapacity = 64)
    val scans = _scans.asSharedFlow()

    /** 防重复注册：重复注册会让同一次扫码被处理多遍 */
    private var registered = false

    private val receiver = object : BroadcastReceiver() {
        override fun onReceive(_ctx: Context?, intent: Intent?) {
            intent ?: return
            val action = intent.action ?: return
            if (action !in LISTEN_ACTIONS) return
            // 不同固件的 extra 字段名不同，依次尝试
            val data = EXTRA_KEYS.firstNotNullOfOrNull { key ->
                intent.getStringExtra(key)?.takeIf { it.isNotBlank() }
            }
            if (data != null) {
                Log.d(TAG, "scan received via $action: $data")
                _scans.tryEmit(data)
            } else {
                Log.w(TAG, "broadcast $action has no known extra key")
            }
        }
    }

    fun register() {
        if (registered) return
        val filter = IntentFilter().apply { LISTEN_ACTIONS.forEach { action -> addAction(action) } }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            // 必须 EXPORTED：扫码结果来自外部系统服务，NOT_EXPORTED 会收不到
            context.registerReceiver(receiver, filter, Context.RECEIVER_EXPORTED)
        } else {
            @Suppress("DEPRECATION")
            context.registerReceiver(receiver, filter)
        }
        registered = true
        Log.d(TAG, "ScannerManager registered: $LISTEN_ACTIONS")
    }

    fun unregister() {
        if (!registered) return
        runCatching { context.unregisterReceiver(receiver) }
        registered = false
    }

    /**
     * 主动触发一次扫描（“扫描”按钮调用）。
     * 依次向各厂商扫码服务发 start 广播；没装对应服务的动作会被系统忽略，无副作用。
     * 若在真机上无效，请向东集索取 CRUISE2 扫码 SDK 文档，把正确的动作名加到 TRIGGER_ACTIONS。
     */
    fun triggerScan() {
        TRIGGER_ACTIONS.forEach { action ->
            runCatching { context.sendBroadcast(Intent(action)) }
                .onFailure { Log.w(TAG, "trigger $action failed: ${it.message}") }
        }
        Log.d(TAG, "trigger scan broadcast sent: $TRIGGER_ACTIONS")
    }

    companion object {
        private const val TAG = "ScannerManager"

        /** 接收扫码结果的广播动作（CRUISE2 实机第一条；换机型可追加） */
        private val LISTEN_ACTIONS = listOf(
            "com.android.server.scannerservice.broadcast", // 东集 CRUISE2【本机实测配置】扫描配置应用的默认广播名
            "android.intent.action.SCANRESULT",      // 东集/多数国产 PDA 通用
            "android.intent.action.BARCODE_CAPTURE", // 部分机型
            "urovo.rcv.message",                     // 优博讯
            "com.android.scanbroadcast.SCAN"         // 部分机型
        )

        /** 扫码结果的 extra 字段名（CRUISE2 实机第一条；换机型可追加） */
        private val EXTRA_KEYS = listOf(
            "scannerdata",                           // 东集 CRUISE2【本机实测配置】扫描配置应用的默认键值
            "value", "barcode", "barcode_value", "scan_value", "data", "result", "BarcodeStr"
        )

        /** 主动触发扫描的广播动作（无效动作会被系统忽略，可按 SDK 文档追加） */
        private val TRIGGER_ACTIONS = listOf(
            "android.intent.action.SCAN_START",
            "android.intent.action.START_SCAN",
            "android.intent.action.SCAN",
            "com.android.scanbroadcast.START_SCAN"
        )
    }
}
