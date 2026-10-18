# Excel 导入工单（清单校验）功能存档 — v1.2.0

> - **存档日期**：2026-10-18
> - **来源版本**：装柜防错 v1.2.0（versionCode 3）
> - **存档原因**：需求调整 —— 该功能暂时下架，后期再**专门开发**（可能重新设计）；
>   当前版本只保留「出库扫码」主流程 + 「扫码记录查看」。
> - **下架版本**：v1.3.0（versionCode 4），见主 README 版本历史。

## 这是什么功能

老流程（与出库扫码并行的第二条业务线）：

1. Excel 导入工单清单（表头：条码/条形码 | 数量，或仅一列条码）
2. 给工单维护柜号/备注
3. 扫码逐件校验：不在清单 = 混料报警（红+蜂鸣+震动）、超数量 = 超量报警（黄）
4. 双模式：计数保存（不校验清单）/ 校验防错（需导入清单）
5. 导出扫码日志 Excel（条码|产品名称|结果|时间）

## 下架方式（v1.3.0 中的处理）

- 源码文件从项目中**物理移除**（本存档保留完整源码，即 `files/` 目录）
- 首页入口按钮、导航路由（WORK_ORDERS / SCAN）、AppViewModel 中老流程逻辑一并移除
- 数据库：`work_orders` / `work_order_items` / `scan_logs` 三张表**未删除，数据保留**
  （v3→v4 迁移为空操作），后期恢复开发可直接衔接旧数据
- 构建依赖：Apache POI（`poi` / `poi-ooxml` / `commons-compress`）已从 `build.gradle.kts` 移除
- 共享组件不受影响：`ScanResult` 枚举、`HidScanField`（HID 录入兜底）、`AlarmManager`、
  `ScannerManager` 均保留在项目中（出库扫码在用）

## 文件清单

### files/ —— 完整下架的源码（按原项目相对路径）

```
app/src/main/java/com/zhuanggui/pda/
├── ui/screen/WorkOrderListScreen.kt     工单列表页（Excel 导入入口 + 建单）
├── ui/screen/ScanScreen.kt              老流程扫码页（双模式 / 手动录入 / 导出报表）
├── excel/ExcelService.kt                Excel 导入解析 + 日志导出（Apache POI）
├── scan/ScanEngine.kt                   清单校验引擎（不在清单 / 超量判定）
├── data/WorkOrderRepository.kt          工单数据仓库
├── data/entity/WorkOrder.kt             工单实体
├── data/entity/WorkOrderItem.kt         清单条目实体（条码 + 允许数量 + 已扫数量）
├── data/entity/ScanLog.kt               扫码日志实体
├── data/dao/WorkOrderDao.kt             工单 DAO
├── data/dao/WorkOrderItemDao.kt         清单条目 DAO
└── data/dao/ScanLogDao.kt               扫码日志 DAO
```

### files-reference/ —— 改动前共享文件的 v1.2.0 快照（仅供恢复参考，勿直接覆盖新版本文件）

| 文件 | 恢复时涉及的老流程内容 |
|---|---|
| ui/AppViewModel.kt | 老流程状态机：ScanUiState / ScanMode / handleScan / selectOrder / manualScan / updateOrderInfo / finishOrder / createOrderAndLoad / setMode / exportLogs |
| ui/AppRoot.kt | WORK_ORDERS / SCAN 两条路由 |
| ui/screen/HomeScreen.kt | 「Excel导入工单（防错校验）」按钮 |
| data/AppDatabase.kt | 三实体注册（v3 版本号与迁移列表） |
| ZhuangGuiApp.kt | `repository` 属性（WorkOrderRepository） |
| app/build.gradle.kts | POI / commons-compress 依赖 |
| app/proguard-rules.pro | POI keep 规则 |

## 恢复清单（后期专门开发时的参考步骤）

1. 把 `files/` 下文件按原相对路径复制回项目 `app/src/main/java/...`
2. 参照 `files-reference/` 恢复共享文件中的老流程代码：
   首页按钮、路由、AppViewModel 逻辑、AppDatabase 实体与 DAO 注册、ZhuangGuiApp.repository
3. `app/build.gradle.kts` 重新添加依赖：

   ```kotlin
   implementation("org.apache.poi:poi:5.2.5")
   implementation("org.apache.poi:poi-ooxml:5.2.5")
   implementation("org.apache.commons:commons-compress:1.26.2")
   ```

4. `app/proguard-rules.pro` 恢复 POI keep 规则（见文件快照）
5. 数据库：v4 起三张表仍在库中（数据保留）；若届时版本已继续升高，需写新迁移把表结构对齐
6. **专门开发时请注意旧版已知问题**（见主 README「开发建议」P0-3）：
   - POI 全内存 DOM 解析，大文件有 OOM 风险 → 建议改流式解析（XSSF SAX）或轻量库
   - `autoSizeColumn` 依赖 java.awt，Android 上可能运行时崩溃 → 改固定列宽
   - Excel 导出曾跑在主线程 → 需放 `Dispatchers.IO`
   - `exportSchema=false` 无迁移测试 → 建议开启 schema 导出并补迁移测试

## 备注

- 同名 zip 打包：`archive/excel-workorder-v1.2.0.zip`（本文件夹的压缩版，便于整体备份）
- 扫码广播对接（v1.2.0 已完成，见主 README「扫码对接」章节）**不受本次下架影响**
- 设备端"应用扫描配置"按包名生效（com.zhuanggui.pda），重装/升级不丢失
