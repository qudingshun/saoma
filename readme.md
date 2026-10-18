# 装柜防错系统（手持工业PDA装柜防错）

针对外贸集装箱现场人工装货的防混款系统。运行在 **东集 CRUISE2 安卓工业 PDA** 上。

**当前版本：v1.2.0（versionCode 3）** ｜ 状态：功能可用，扫码广播对接已完成（见下方"扫码对接"章节）

## 功能总览

首页三个入口：

```
首页
 ├─ 【出库扫码】★主流程（无清单模式）
 │    └─ 设置页：柜号(字母数字) + 本柜数量 + 条码规则(前缀，可多条)
 │         ├─ 规则可添加/删除；拼装柜加 2 条规则 = 2 个型号同柜
 │         ├─ 柜号已存在 → 提示"继续装柜"，自动载入原规则与计数
 │         └─ 点【装柜】→ 扫码页
 │              ├─ 输出格式：【柜号，条码】（逗号拼接，大字显示）
 │              ├─ 计数卡：正常 / 串号 / 重复 / 超量 + 进度 x/数量
 │              ├─ 每扫一码 → 先入库、再自动追加写 CSV（实时显示 ✓/⚠）
 │              ├─ 手动录入框自动聚焦（HID 扫码枪回车即提交）+ 扫描按钮
 │              ├─ 本柜记录滚动明细（最近 100 条，带结果标签）
 │              └─ 【返回设置】可追加规则后再装柜（同柜号计数不丢）
 ├─ 【Excel导入工单（防错校验）】← 老功能保留：清单校验模式
 └─ 【扫码记录查看】← 全部扫码记录，按柜号/条码过滤
```

### 报警逻辑（出库扫码）

| 情况 | 结果 | 反馈 |
|---|---|---|
| 条码不以任何规则前缀开头 | 串号 SERIAL_MISMATCH | 红屏 + 蜂鸣 + 震动 |
| 同柜已扫过的条码 | 重复 DUPLICATE | 红屏 + 蜂鸣 + 震动 |
| 正常件数 ≥ 本柜数量 | 超量 OVER_LIMIT | 黄屏 + 提示音 |
| 正常 | OK | 绿屏 + 嘀声，计数 +1 |

### 自动落盘（每次扫码立即写入）

- 路径：`/sdcard/ZhuangGui/装柜记录/yyyy-MM-dd/柜号.csv`（按日期分文件夹、按柜号分文件）
- 列：`时间,柜号,条码,完整码(柜号，条码),结果`
- UTF-8 带 BOM（Excel 打开中文不乱码）；换柜号=新文件；同柜号回来=原文件继续追加
- 权限：Android 11+ 需"所有文件访问"（`MANAGE_EXTERNAL_STORAGE`），首次点【出库扫码】引导授权

## 技术栈

- Kotlin + Jetpack Compose（UI，单 Activity 导航）
- Room（本地数据库，单机离线，带版本迁移）
- Apache POI（Excel 导入/导出）
- 无后端、无网络依赖，多台 PDA 各自独立工作

## 数据库结构（v3）

```
v1 → v2：work_orders 加 remark 字段
v2 → v3：新增三张表（出库扫码）
  load_containers  装柜会话（柜号唯一 = 同柜号继续装柜，换柜号新会话）
  load_rules       条码规则（前缀，一柜多条 = 拼装柜多型号）
  scan_records     扫码记录（每扫一码一条，含串号/重复/超量完整留痕）
```

扫码结果枚举 `ScanResult`：`OK / NOT_IN_LIST / OVER_LIMIT / SERIAL_MISMATCH / DUPLICATE`

## 目录结构

```
app/src/main/java/com/zhuanggui/pda/
├── data/
│   ├── entity/        WorkOrder / WorkOrderItem / ScanLog（老流程）
│   │                  LoadEntities.kt：LoadContainer / LoadRule / ScanRecord（出库流程）
│   ├── dao/           LoadDaos.kt 等
│   ├── AppDatabase.kt 数据库 v3 + Migration 1→2→3
│   └── WorkOrderRepository.kt
├── scan/              ScanEngine（校验）/ AlarmManager（报警）/ ScannerManager（广播采集）
├── excel/ExcelService.kt
├── export/FileExporter.kt   CSV 自动落盘 + 存储权限
└── ui/
    ├── AppRoot.kt          导航（HOME / OUTBOUND_SETUP / OUTBOUND_SCAN / RECORDS / WORK_ORDERS / SCAN）
    ├── AppViewModel.kt     双流程状态机（老工单流程 + 出库扫码流程）
    └── screen/             HomeScreen / OutboundSetupScreen / OutboundScanScreen /
                            RecordsScreen / WorkOrderListScreen / ScanScreen
```

## 构建环境（本机已配好）

- **JDK 17**：`C:\Users\qudin\.jdks\jdk-17.0.20.1+1`（仅通过 `gradle.properties` 的 `org.gradle.java.home` 生效，不动系统 JDK 8）
- **Gradle 8.9** + AGP，wrapper 分发走腾讯镜像
- SDK 组件手动从腾讯镜像 `mirrors.cloud.tencent.com/AndroidSDK/` 安装；`gradle.properties` 设 `android.builder.sdkDownload=false` 禁止 AGP 联网下载
- Maven 依赖走阿里云镜像（`settings.gradle.kts` 已配）；GitHub / dl.google.com / services.gradle.org 在本网络均不通
- 构建命令：`.\gradlew.bat assembleDebug`（工作目录 `E:\saoma`）
- APK 输出：`app\build\outputs\apk\debug\app-debug.apk`（debug 免签名）
- 装机：USB 调试 `adb install -r app-debug.apk`，桌面名"装柜防错 v1.2.0"，界面内也显示版本号

## Excel 格式约定（老流程）

- 第一行为表头，需含"条码/条形码/Barcode"和（可选）"数量/Quantity"
- 从第二行起读取；同一条码自动去重取最大数量；支持 .xlsx / .xls

---

## ✅ 扫码对接：广播方式（2026-10-18 实机验证完成）

**结论**：设备解码由系统“扫描工具”（com.seuic.scanner.activity）完成；通过“扫描配置”给本应用下发**广播**，App 端 `ScannerManager.kt` 监听。物理扳机实测链路已打通。

**设备端配置**（系统“扫描配置”App → 应用扫描配置 → 已配置应用；按包名生效，**重装 App 不会丢失**）：

| 配置项 | 值 |
|---|---|
| 选择应用 | 装柜防错 v1.2.0（com.zhuanggui.pda） |
| 条码发送方式 | 广播 |
| 广播名称 | `com.android.server.scannerservice.broadcast` |
| 键值（extra） | `scannerdata` |
| 结束符 | ENTER |

**实机验证链路**（物理扳机扫码 → logcat 实录）：

```
ScanService: onKeyDown → startScan
scanner-jni: start decode successfully, barcode: 84455470714280306471(20) - Code128
ScannerManager: scan received via com.android.server.scannerservice.broadcast   ← App 收到
→ 界面反馈 + CSV 落盘（装柜记录/日期/柜号.csv）
```

**App 端监听**（`scan/ScannerManager.kt`；换机型/固件时只改这两个列表，新动作/键名放第一优先）：

- 监听动作：`com.android.server.scannerservice.broadcast`（CRUISE2 实测）｜`android.intent.action.SCANRESULT`｜`android.intent.action.BARCODE_CAPTURE`｜`urovo.rcv.message`｜`com.android.scanbroadcast.SCAN`
- extra 键名：`scannerdata`（CRUISE2 实测）｜`value`｜`barcode`｜`barcode_value`｜`scan_value`｜`data`｜`result`｜`BarcodeStr`
- `RECEIVER_EXPORTED` + 多动作多键名兼容；防重复注册；`triggerScan()` 主动触发（扫码页“扫描”按钮）

**注意事项**：

- 不要禁用/停用“扫描工具”（com.seuic.scanner.activity）——解码链路依赖它
- App 收不到码时依次查：①“扫描配置→已配置应用”里装柜防错的配置是否还在；②App 是否在前台（或进程存活）；③`adb logcat -s ScannerManager`
- 兜底方案（HID 键盘模拟）保留在扫码页：录入框自动聚焦 + `HidScanField` 击键速度检测

**调试常用命令**：

```
adb logcat -s ScannerManager                      # App 收码日志
adb logcat | findstr onDecodeComplete             # 硬件解码日志
adb shell am broadcast -a com.android.server.scannerservice.broadcast --es scannerdata "TEST123"   # 模拟设备广播
adb shell am start -n com.seuic.scanner.activity/.MainActivity   # 打开扫描工具
```

## 版本历史

- **v1.2.0（versionCode 3）**：全新界面流程（出库扫码主流程 + 扫码记录查看）；柜号/数量/多条码规则设置页；输出"柜号，条码"；每扫一码自动落盘 CSV；Room v3 三表 + 迁移
- **v1.2.0 修复（2026-10-18）**：补 VIBRATE 权限（此前重复/串号报警振动必崩溃）；接收器防重复注册；MainActivity 改 singleTask（防双实例重复收码）；扫码广播对接完成（CRUISE2 实机，见上方"扫码对接"章节）
- **v1.1.0（versionCode 2）**：竖屏锁定；扫码页改版（工单卡/计数卡/手动录入/明细列表）；双模式（计数保存/校验防错）；广播接收修复（RECEIVER_EXPORTED）；版本号显示
- **v1.0（versionCode 1）**：Excel 导入工单 + 清单校验防错 + 计数 + 报警 + 日志导出

## 二次开发计划：规则库（规则导入与维护）

### 背景与目标

当前出库扫码的"条码规则、本柜数量"靠每次在设置页手工录入。产品料号/型号是相对稳定的，
后期需反复更新维护一套**规则库**：

- 维护字段：**料号、型号、装柜量、条码规则（条码中固定的部分/前缀）**、备注
- 使用方式：装柜时输入/选择料号或型号 → 检索规则库 → **自动带出条码规则和装柜量**，
  不用每次手抄；也可按型号、料号筛选后批量勾选（拼装柜多型号）
- 数据来源：Excel 批量导入（沿用现有 POI 方案），支持导出当前库做修改后回导

### 数据设计（Room v3 → v4）

新增实体 `RuleItem`（表 `rule_items`）：

| 字段 | 类型 | 说明 |
|---|---|---|
| id | Long PK | 自增 |
| materialNo | String | 料号（建唯一索引，同料号重复导入=覆盖更新） |
| model | String | 型号（检索用，可空） |
| loadQuantity | Int | 装柜量（默认值，装柜时可改） |
| barcodePrefix | String | 条码规则=固定部分前缀（一个料号可多条，支持拼装柜） |
| remark | String | 备注 |
| updatedAt | Date | 最后更新时间 |

- Migration 3→4 建表；导入用 `@Insert(onConflict = REPLACE)` 按料号去重覆盖，
  以后更新 Excel 回导即完成规则维护，不产生重复行
- 一个料号多条规则的需求：`materialNo + barcodePrefix` 联合唯一，或首期单规则、
  拼装柜场景用"多选料号"组合（推荐首期后者，简单够用）

### Excel 模板（导入/导出共用）

```
料号,型号,装柜量,条码规则,备注
MH-2608A,型A-双开门,144,920459912,木柜
MH-2608B,型B-单开门,72,920459913,
```

- 表头按名称匹配（容忍顺序变化），校验：料号必填、装柜量正整数、料号重复自动覆盖
- 导入结果提示：新增 X 条 / 更新 Y 条 / 跳过 Z 条（含原因），沿用 ExcelService 现有 resultText 风格
- 导出：一键导出全库为 xlsx，便于在电脑上批量修改

### 功能分期

**第一期：规则库管理页（基础可用）**

1. 数据层：`RuleItem` 实体 + `RuleDao`（查询/插入/删除/按料号或型号模糊搜索）+ Migration 3→4
2. 界面：首页增加第 4 个入口【规则库】
   - 列表：全部规则，顶部搜索框（料号/型号模糊匹配）
   - 增删改：手动加一条 / 点行编辑 / 长按删除
   - 【导入Excel】【导出Excel】按钮（复用 FileExporter 的存储权限与目录方案，
     存 `/sdcard/ZhuangGui/规则库/规则库.xlsx`）
3. 装柜设置页联动：加"从规则库选择"入口
   - 弹出检索页（按料号/型号过滤）→ 点选 → 自动填入【本柜数量】+【条码规则】
   - 拼装柜：可连续多选，每选一个追加一条规则

**第二期：体验增强（可选）**

- 装柜设置页直接显示所选料号/型号信息卡（当前只显示规则前缀）
- 扫码页顶部显示本柜料号/型号，防拿错单
- 规则库按"最近使用"排序，常用料号置顶
- 导入时校验条码规则合法性（与真实条码比对提示前缀过长/过短）
- 规则库云端备份（当前定位纯离线，需另评估）

### 预估改动面

```
data/entity/RuleItem.kt          新增
data/dao/RuleDao.kt              新增
data/AppDatabase.kt              v3→v4 + Migration
excel/ExcelService.kt            规则库导入/导出方法
ui/screen/RuleLibraryScreen.kt   新增（列表+搜索+编辑+导入导出）
ui/screen/OutboundSetupScreen.kt 加"从规则库选择"
ui/AppViewModel.kt               规则库状态与检索
ui/AppRoot.kt                    新路由 RULES
```

工作量约 1~1.5 天（含真机验证），第一期做完即可日常使用。

## 开发建议（代码体检 + 行业实践，2026-10-18）

> 基于全量代码通读（28 个 Kotlin 文件 ~2900 行）与同类产品调研
> （Zebra DataWedge / 优博讯 ScanManager / Cleverence 仓储方案 / RuggedLayer 集成指南）整理。

### 一、优先修复项（按危害排序）

#### P0 —— 数据正确性 / 稳定性

| # | 问题 | 位置 | 后果 |
|---|---|---|---|
| 1 | **快速连扫丢计数**：扫码处理基于 StateFlow 旧快照 `copy()` 更新，两枪间隔小于一次数据库操作时会互相覆盖 | `ui/AppViewModel.kt`（handleOutboundScan / handleScan） | 工人快速连扫 → 计数少于实际 → 防错系统自己数错 |
| 2 | **重复/超量校验非原子**：查重计数与插入不在同一事务，快速连扫同一码两枪都判 OK | 同上 | 重复码漏报 |
| 3 | **Excel 全内存 DOM（POI）+ 部分在主线程**：大文件 OOM、日志导出 ANR；`autoSizeColumn` 依赖 java.awt，Android 上有运行时崩溃风险 | `excel/ExcelService.kt`、`AppViewModel.exportLogs` | 清单一大就闪退/卡死 |
| 4 | **扫码记录全表常驻内存**：`observeAll()` 无分页无上限，记录只增不减 | `data/dao/LoadDaos.kt`、`RecordsScreen` | 使用数月后界面越来越卡 |

修复方向：

1. 状态更新改 `MutableStateFlow.update { }` 原子更新；「校验 + 插入」合并进 Room 事务
2. POI 调用统一移入 `Dispatchers.IO`；导入改流式解析或轻量库；导出避免 `autoSizeColumn`
3. 记录查询改分页（LIMIT/OFFSET）或按柜号/日期筛选 + COUNT 聚合；配合「记录归档清理」（见下文三-3）

#### P1 —— 健壮性

- **FileExporter**：共享 `SimpleDateFormat` 线程不安全（换 `DateTimeFormatter`）；表头并发竞态；CSV 字段未转义（条码含英文逗号会错列）
- **异常兜底**：`handleOutboundScan` 全程无 try/catch，数据库异常直接崩溃
- **迁移零测试**：`exportSchema=false` + 手写迁移 SQL → 建议开启 schema 导出 + 补 MigrationTest
- **广播无来源校验**：任何三方 App 可伪造扫码广播注入数据 → 对防错系统是可信性问题
- **输入防抖**：`onContainerNoChange` 每按键查库，无 debounce、旧 Job 未取消 → 状态可能乱序
- **零测试**：优先补最小集 —— 校验逻辑单测（串号/重复/超量）+ 迁移测试 + CSV 写入测试

### 二、行业同类项目做法（值得借鉴）

**1.「一条数据通路」原则**（Zebra / TheFlex 官方推荐）

> "use either the barcode APIs or DataWedge but NOT both at the same time"
> "When using intent-based scanning, always disable keyboard output to avoid duplicate inputs"

广播与键盘模拟只能同时开一个，两个都开必然双份数据。建议：设置页显示当前生效的扫码方式；App 内做「设备配置引导页」，把本次 CRUISE2 的配置过程（应用扫描配置 → 广播 → 动作/键值）产品化成图文步骤，换新 PDA 照做即可。

**2.「一次扫码 = 一次业务事件」验证清单**（RuggedLayer 生产环境测试清单）

- [ ] 冷启动 / 从后台恢复 / 锁屏解锁后扫码 → 都能进
- [ ] 连续快速扫 10 枪 → 计数正好 +10，不多不少
- [ ] 同一码连扫两枪 → 第二枪必报重复
- [ ] 扫码数据不会串到其他输入框
- [ ] 异常恢复不需要重启设备

**3. 厂商适配层**：`ScannerManager` 现有「多动作 + 多键名探测」已是雏形；建议把两个列表做成设置页可配置（不改代码加新厂商），并加「开机自检」——检测设备扫描服务/型号后自动提示配置指引。

**4. 现场稳定性三件套（PDA 特有，同类方案共识）**

- 电池优化白名单：防 Doze 延迟/丢弃扫码广播（首次启动引导加入）
- Kiosk 模式（Lock Task）：防工人误触 Home 退出作业界面
- 重启后自动回到工作状态（配合开机自启动白名单）

**5. 防错反馈升级**：颜色 + 图标 + 文字三重编码（当前仅靠颜色区分正常/串号/超量，色弱或强光下不可靠）。

### 三、功能演进方向（按价值排序）

1. **规则库**（已定计划，见上一章节）——省每次手抄，核心需求
2. **装柜完成收尾**：加「本柜完成」按钮 → 置 FINISHED 状态 → 生成该柜汇总（总件数 / 串号 / 重复）→ 一键导出报告
3. **记录归档清理**：完成的柜 N 天后归档到 CSV 后从库删除（CSV 已全量留痕），库里只留近 30 天 —— 同时解决 P0-4
4. **操作员维度**：记录加「工号」，索赔/追溯可用
5. **多 PDA 汇总**：电脑端小工具读各机 CSV 合并，比 App 内做同步轻量，符合离线定位

### 四、建议行动顺序

| 阶段 | 内容 | 说明 |
|---|---|---|
| 1 | 真机全功能回归（扫码链路已打通） | 按「一次扫码=一次业务事件」清单验证 |
| 2 | P0-1/2 计数竞态 + 校验事务化 | 改动集中在 AppViewModel，约半天 |
| 3 | P0-3/4 Excel 线程 + 记录查询优化 | 约 1 天 |
| 4 | 设备配置引导页 + 规则库第一期 | 部署成本 + 核心需求 |

## 待完善

- [ ] 真机全功能回归测试（按"开发建议"验收清单）
- [ ] 快速连扫计数竞态修复（P0-1/2，见"开发建议"）
- [ ] Excel 导入导出改 IO 线程 + 流式解析（P0-3）
- [ ] 扫码记录分页/聚合 + 归档清理（P0-4）
- [ ] 装柜完成判定与收尾（FINISHED 状态已有字段，UI 未接）
- [ ] 报表导出入口 UI（数据层已有 `exportLogs`）
- [ ] 规则库功能（见"二次开发计划"，待排期）
