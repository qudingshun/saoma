package com.zhuanggui.pda.scan

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.Build
import android.util.Log
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow

/** 最近一次收到的扫码（v1.9.1：供「扫描设置」自检页显示来源与内容） */
data class ScanEvent(val action: String, val data: String, val at: Long)

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

    /** 最近一次收到的扫码（自检页用） */
    private val _lastScan = MutableStateFlow<ScanEvent?>(null)
    val lastScan: StateFlow<ScanEvent?> = _lastScan.asStateFlow()

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
                _lastScan.value = ScanEvent(action, data, System.currentTimeMillis())
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

    companion object {
        private const val TAG = "ScannerManager"

        /** 首选广播动作与 extra 键名（东集 CRUISE2 实测；自检页用同一组值模拟扫码） */
        const val PRIMARY_ACTION = "com.android.server.scannerservice.broadcast"
        const val PRIMARY_EXTRA = "scannerdata"

        /** 接收扫码结果的广播动作（CRUISE2 实机第一条；换机型可追加） */
        private val LISTEN_ACTIONS = listOf(
            PRIMARY_ACTION,                             // 东集 CRUISE2【本机实测配置】扫描配置应用的默认广播名
            "android.intent.action.SCANRESULT",      // 东集/多数国产 PDA 通用
            "android.intent.action.BARCODE_CAPTURE", // 部分机型
            "urovo.rcv.message",                     // 优博讯
            "com.android.scanbroadcast.SCAN"         // 部分机型
        )

        /** 扫码结果的 extra 字段名（CRUISE2 实机第一条；换机型可追加） */
        private val EXTRA_KEYS = listOf(
            PRIMARY_EXTRA,                             // 东集 CRUISE2【本机实测配置】扫描配置应用的默认键值
            "value", "barcode", "barcode_value", "scan_value", "data", "result", "BarcodeStr"
        )
    }
}
