# 成品出库（手持 PDA 装柜防错）

运行在 **东集 CRUISE2 安卓工业 PDA（Android 11）** 的离线装柜扫码工具：一柜一档、扫码即校验（串号 / 重复 / 超量）、实时落盘、删除可撤回。

**当前版本：v1.5.3（versionCode 10，数据库 v8）** ｜ 状态：已装机运行，待按文末清单现场验收

## 功能与操作流程

```
首页（成品出库）
 ├─【出库扫码】★主流程
 │   └─ 设置页：柜号 + 本柜数量（窄框并列）+ 条码规则（前缀，可多条）
 │        ├─ 规则录入：扫真码自动填入（人工删减成前缀）/ 手动录入 / 从规则库选择（多选）
 │        ├─ 每条规则可勾：可重复扫（双码机固定码）/ 计入统计（不勾 = 辅助码，只留痕不计数）/ 查历史串号
 │        ├─ 同柜号「今天」已有装柜记录（不含已删除）→ 自动载入原规则 + 本柜数量填入今天已装数
 │        │    → 进入扫码页再扫即提示超量（闭环防同号重复装；补装手动改大本柜数量）
 │        └─【装柜】→ 扫码页
 │             ├─ 输出格式：【柜号，条码】大字；计数卡：正常/串号/重复/超量 + 进度 x/数量
 │             ├─ 每扫一码：入库 + TSV 落盘（实时 ✓/⚠）+ 报警反馈
 │             ├─ 装齐本柜数量 → 弹「装柜完成」（返回设置后规则清空；该柜号当天再进入填已装数 → 超量）
 │             ├─ 记录删除：重复/串号/辅助码/超量一步确认；正常记录三步确认（均进回收站）
 │             ├─ 撤回 / 前进：本柜操作栈（撤销扫码 / 恢复删除，上限 100 步）
 │             └─【返回设置】柜号 / 数量 / 规则原样保留；从首页进入则为整页空白
 ├─【扫码记录查看】按「日期 + 柜号」聚合（两级）；单组 / 本日 / 批量删除（三步确认）
 │    └─ 长按本页标题「扫码记录」进「回收站」：逐条 / 全部撤回、清空（三次确认）
 └─【规则库】规则源导入（.xlsx/.xls/.csv/.txt）+ 规则编辑；供设置页多选
```

## 校验与报警逻辑（出库扫码）

校验顺序：**规则前缀（串号）→ 同柜重复 → 查历史串号 → 辅助码 → 超量 → 正常**

| 情况 | 结果 | 反馈 |
|---|---|---|
| 条码不以任何规则前缀开头 | 串号 SERIAL_MISMATCH | 红屏 + 蜂鸣 + 循环振动 |
| 同柜已扫过的条码（该规则未勾「可重复扫」） | 重复 DUPLICATE | 红屏 + 蜂鸣 + 循环振动 |
| 勾「查历史串号」的规则：条码已在其他柜记录里出现过 | 串号 SERIAL_MISMATCH | 红屏 + 蜂鸣 + 循环振动；提示"已于 xx 在柜 xxx 录入过" |
| 命中「不计入统计」规则的条码 | 辅助 AUX | 蓝屏 + 嘀声，只留痕不计数（可反复扫） |
| 正常件数 ≥ 本柜数量 | 超量 OVER_LIMIT | 黄屏 + 提示音 + 循环振动 |
| 正常（计入统计的规则） | OK | 绿屏 + 嘀声，计数 +1 |

- 报警弹窗（重复 / 串号 / 超量）：点确认停振；重复 / 串号 / 超量弹窗还可一步删除该条
- 振动停止：弹窗确认 / 任意触屏 / 扫下一枪 / 删除记录 / 离开扫码页，另有 10 秒兜底自动停
- **计数口径**：正常 / 串号 / 重复 / 超量均按「今天」（设备本地日期）统计；已删除记录不参与；跨天再装不继承昨天计数

## 删除 / 撤回 / 回收站

- 删除一律**软删除**（`scan_records.deletedAt`），清空回收站前都可恢复
- 确认分级：重复 / 串号 / 辅助码 / 超量 = 1 步；**正常记录 = 3 步**；记录页单组 / 本日 / 批量 = 3 步；清空回收站 = 3 步
- 扫码页「撤回 / 前进」：撤销上一枪（软删）/ 恢复删除；换柜自动清空操作栈
- 回收站（记录页长按标题进入）：逐条撤回 / 全部撤回（恢复后重新计入统计）；清空 = 永久删除
- 落盘文件与 App 动作同步：整组删空 → 文件从「装柜记录」挪到「回收站」目录；撤回 → 挪回；部分删除 → 两侧各留一份

## 自动落盘（TSV）

- 路径：`/sdcard/ZhuangGui/装柜记录/yyyy-MM-dd/柜号.tsv`（按日期分目录、按柜号分文件；已删除的进 `/sdcard/ZhuangGui/回收站/`）
- 列（Tab 分隔）：`时间 ⇥ 柜号 ⇥ 条码 ⇥ 完整码 ⇥ 结果`（结果 = 正常/串号/重复/超量/辅助）
- UTF-8 带 BOM（Excel 打开中文不乱码）；同柜号回来继续追加；**每扫一码立即写入**
- 权限：Android 11+ 需「所有文件访问」（`MANAGE_EXTERNAL_STORAGE`，首次点【出库扫码】自动引导授权）
- v1.5.0 之前的旧 .csv 文件：同步时自动挪进文件回收站归档，不再参与新逻辑

## 扫码对接（设备端配置，实机验证可用）

- 解码由系统「扫描工具」（`com.seuic.scanner.activity`）完成——**不要禁用/停用它**（禁用 = 完全不能扫码）
- 系统「扫描配置」→ 应用扫描配置 → 已配置应用（**按包名生效，重装 App 不丢失**）：

| 配置项 | 值 |
|---|---|
| 选择应用 | 成品出库（com.zhuanggui.pda） |
| 条码发送方式 | 广播 |
| 广播名称 | `com.android.server.scannerservice.broadcast` |
| 键值（extra） | `scannerdata` |
| 结束符 | ENTER |

实机链路：物理扳机 → 系统解码 → 广播送达 → App 收码 → 界面反馈 + TSV 落盘（全通）

App 端兼容（`scan/ScannerManager.kt`，换机型只改这两个列表，新值放第一优先）：
- 监听动作：`com.android.server.scannerservice.broadcast`｜`android.intent.action.SCANRESULT`｜`android.intent.action.BARCODE_CAPTURE`｜`urovo.rcv.message`｜`com.android.scanbroadcast.SCAN`
- extra 键名：`scannerdata`｜`value`｜`barcode`｜`barcode_value`｜`scan_value`｜`data`｜`result`｜`BarcodeStr`
- 兜底：扫码页 HID 键盘模拟录入框（`HidScanField.kt`）

收不到码排查：①「扫描配置 → 已配置应用」里配置是否还在；②App 是否在前台（或进程存活）；③`adb logcat -s ScannerManager`

常用调试命令：

```
adb logcat -s ScannerManager                                        # App 收码日志
adb shell am broadcast -a com.android.server.scannerservice.broadcast --es scannerdata "TEST123"   # 模拟扫码广播
adb install -r app\build\outputs\apk\debug\app-debug.apk            # 覆盖安装（数据不丢）
```

## 构建与装机（本机环境已配好）

- **JDK 17**：`C:\Users\qudin\.jdks\jdk-17.0.20.1+1`（仅经 `gradle.properties` 的 `org.gradle.java.home` 生效，不影响系统 JDK 8）
- Gradle 8.9 + AGP；wrapper 走腾讯镜像；Maven 依赖走阿里云；SDK 组件手动安装，`android.builder.sdkDownload=false` 禁止联网下载
- 构建：`.\gradlew.bat assembleDebug`（工作目录 `E:\saoma`）
- 测试：`.\gradlew.bat testDebugUnitTest`（扫码判定顺序与边界，8 项单测）
- 产物：`app\build\outputs\apk\debug\app-debug.apk`（debug 免签名）
- 装机：`adb install -r app-debug.apk`；桌面名「成品出库」
- Room schema 快照导出在 `app\schemas\`（随版本提交，便于迁移核对）
- 远程仓库（私有）：`https://github.com/qudingshun/saoma` —— 推送 `git push origin main`（旧 v1.2.0 快照保留在 `legacy-v1.2.0` 标签）
- 仓库编码约定：全部文本 **UTF-8（无 BOM）+ LF**（`.gitattributes` 锁定 `eol=lf`）——任何机器 clone / pull 与本地逐字节一致，中文不乱码

## 技术栈与数据库

- Kotlin + Jetpack Compose（单 Activity 导航）；Room（带版本迁移）；Apache POI（仅规则源导入解析 .xlsx/.xls 用）；无后端、无网络，多台 PDA 各自独立

数据库 v8（`data/AppDatabase.kt`）：

```
load_containers  装柜会话（柜号唯一：同号柜永远复用同一行；id 关联规则与记录）
load_rules       本柜条码规则快照（prefix / allowDuplicate / countEnabled / checkHistory）
scan_records     扫码记录（每扫一码一条，含串号/重复/超量/辅助完整留痕；deletedAt = 软删除）
rule_library     规则库（prefix 唯一 + name）
```

```
迁移：v1→v2 工单表加备注；v2→v3 建出库三表；v3→v4 空操作（Excel 工单下架，旧表保留）；
     v4→v5 建规则库表；v5→v6 scan_records 加 deletedAt；v6→v7 load_rules 加双码机选项；
     v7→v8 load_rules 加 checkHistory
```

`ScanResult`：`OK / NOT_IN_LIST（历史遗留）/ OVER_LIMIT / SERIAL_MISMATCH / DUPLICATE / AUX`

## 目录结构

```
app/src/main/java/com/zhuanggui/pda/
├── data/        实体 LoadEntities.kt / ScanResult.kt、DAO LoadDaos.kt、AppDatabase.kt（v8 + 全部迁移）
├── scan/        AlarmManager（循环振动 + 10s 兜底）、ScannerManager（广播采集，多厂商兼容）
├── importer/    RuleSourceImporter.kt（规则源解析）
├── export/      FileExporter.kt（TSV 落盘 + 文件回收站 + 存储权限）
└── ui/          AppRoot.kt（导航）、AppViewModel.kt（状态机）、HidScanField.kt（HID 兜底）、
                 screen/（Home / OutboundSetup / OutboundScan / Records / Rules / Trash / ConfirmDialogs）、theme/
```

## 规则源导入格式

- 支持 .xlsx / .xls / .csv / .txt；第一列 = 条码前缀（**仅字母 + 数字**），第二列可选 = 名称
- 首行若是表头（barcode / prefix / 条码 等）自动跳过；前缀去空格转大写；同前缀去重（保留首个名称）
- 回导 = 覆盖更新（按前缀），方便用 Excel 改完整表再导入

## 版本历史

- **v1.5.3（versionCode 10，DB v8）**：子项目波次 1（审计 9 项收尾）——扫码页记录头改「共 N 条 · 最近 N 条可点按删除」；勾选框触控目标恢复 48dp；报警底色覆盖状态栏；落盘路径文案统一取自 FileExporter；时间格式化统一 java.time；首页返回自动复检存储授权（未授权红字提示）；规则库新增重名提示阻止；Manifest 清理（删相机特征 / READ 权限、关备份）；仓库清理（删测试截图与旧 zip）
- **v1.5.2（versionCode 9，DB v8）**：超量记录也可删除——报警弹窗新增「删除这条超量记录」；扫码页记录列表超量行支持点按一步删除（超量计数同步 -1，进回收站可撤回）
- **v1.5.1（versionCode 8，DB v8）**：代码审计 P1 修复批次——HID 兜底输入框仅「追加字符」计击键、删除即重置（杜绝退格连删被误判成扫码、自动提交残码）；扫码事件流 / 单枪处理 / 报警初始化加异常兜底（异常不崩溃、不断链）；落盘时间改线程安全格式化；删除与回收站操作并入扫码互斥；记录页二级改「柜号 + 当日」精确口径、进入自动回一级；报警弹窗确认状态支持重建恢复；启用 Room schema 导出；扫码判定抽纯函数 + 8 项单测；剔除 3 个未用依赖
- **v1.5.0（versionCode 7，DB v8）**：删除体系——正常记录三步删除 / 重复·串号·辅助一步删除 / 撤回·前进操作栈 / 软删除回收站 / TSV 落盘与文件回收站同步；规则支持扫码填入；双码机支持（可重复扫 / 计入统计 / 辅助码）；查历史串号；装柜完成弹窗；**同号柜今天再进入闭环**（自动载入规则 + 填今天已装数 → 新扫提示超量）；修复同号柜重复进入崩溃；统计按天且排除已删除；首页进入设置页整页空白；应用更名「成品出库」
- **v1.4.0（versionCode 6）**：规则库（导入 / 编辑 / 装柜多选）；扫码记录按「日期 + 柜号」聚合 + 单组 / 本日 / 批量删除；重复 / 串号可删；报警改循环振动（可停 + 10s 兜底）；柜号与本柜数量窄框并列
- **v1.3.x（4-5）**：Excel 工单功能下架存档（`archive/excel-workorder-v1.2.0/`）；扫码串行化等 P0 修复；logo 应用图标
- **v1.0 - v1.2（1-3，早期）**：Excel 工单导入 + 清单校验防错起步；后改为「出库扫码 + 记录查看」全新流程；扫码广播对接打通（VIBRATE 权限 / singleTask / 防重复注册修复）

## 已知现象与注意事项

- 覆盖安装 = 应用进程重启（界面状态清空），**数据不丢**；卸载重装会清空 App 数据库，但 `/sdcard/ZhuangGui/` 下的文件不受影响
- 冷启动约 4~7 秒（PDA 性能所限）
- 偶尔系统残留 "Application Error" 残影窗口（旧进程痕迹）：重启设备即消失，不影响使用
- App 内「扫描」按钮为多厂商软触发兼容（CRUISE2 未验证；以物理扳机为准）

## 待办与验收

- [ ] 现场验收清单（v1.5.x）：
  - 快速连扫 10 枪 → 计数正好 +10；同一码两枪 → 第二枪必报重复
  - HID 兜底输入框：连按退格 / 删除不再触发自动提交（v1.5.1 修复点）；手输完整码点「录入」正常
  - 超量删除：扫到超量 → 报警弹窗「删除这条超量记录」/ 列表点按超量行 → 超量计数 -1，可撤回（v1.5.2 新增）
  - 同号柜闭环：输今天已装过的柜号 → 数量自动填已装数 + 载入规则 → 新扫弹超量；补装改大数量可继续
  - 装柜完成：扫满本柜数量弹「装柜完成」→ 返回设置后规则清空
  - 删除 / 撤回 / 回收站：正常三步删、重复·串号一步删、撤回·前进、回收站恢复与清空
  - 落盘：TSV 内容与界面一致；删除后进文件回收站、撤回后挪回
  - 规则扫码填入（扫真码 → 删减前缀 → 添加）；双码机两条勾选；查历史串号报警
  - 记录页：进入总是从分组列表开始；点进分组只显示当天该柜的条码（v1.5.1 修复点）
  - 首页存储提示：未授权显示红字；去系统设置授权后返回首页自动消失（v1.5.3）；设置页勾选框点按更宽松（48dp 触控，v1.5.3）
- [ ] 规则库扩展：料号 / 型号 / 装柜量自动带出、导出 Excel 回导
- [ ] 记录归档清理（完成柜 N 天后清理库内数据，落盘文件已全量留痕）
- [ ] 装柜完成汇总导出；操作员工号维度；多 PDA 落盘文件电脑端合并工具
- 代码层优化建议见 `代码审计与优化建议.md`；未完结事项的并行推进安排见 `子项目计划.md`
