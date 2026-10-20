# PC 端同步服务（随仓库副本）

实际运行目录建议：`E:\saoma-sync`（仓库外，避免把 `.venv` / 数据提交进 git）。
本目录是源码副本，部署时整体复制到电脑即可。

## 1. 首次部署（只需一次）

1. 安装 Python 3.11（勾选 Add to PATH）。
2. 把本目录复制到 `E:\saoma-sync`，在该目录执行：

```powershell
python -m venv .venv
.\.venv\Scripts\python.exe -m pip install -i https://pypi.tuna.tsinghua.edu.cn/simple -r requirements.txt
```

> `requirements.txt` 含 `openpyxl`（网页导入/导出规则库 Excel 用）。

3. 启动服务（前台）：

```powershell
.\.venv\Scripts\python.exe -m uvicorn server:app --host 0.0.0.0 --port 8000
```

4. 浏览器验证：`http://127.0.0.1:8000/api/v1/health` 返回 `{"ok":true,...}`。
5. 放行防火墙（管理员 PowerShell，一次）：

```powershell
New-NetFirewallRule -DisplayName "装柜PDA同步 8000" -Direction Inbound `
  -Protocol TCP -LocalPort 8000 -Action Allow -Profile Private
```

> 电脑网络必须是「专用网络」；公用网络会被防火墙拦截。

## 2. 配对

```powershell
.\.venv\Scripts\python.exe qr_tool.py
start pair.png
```

- 二维码内容：`SAOMASYNC1|ip=...|port=8000|token=...|ver=1`
- PDA：首页 → WiFi 同步 → 扫描配对码（用扫码枪扫屏幕）
- 扫码枪不支持二维码时，手输：`电脑IP:8000#令牌`
- 令牌保存在 `data\token.txt`

## 3. 查看 / 下发

```powershell
# 看 PDA 已上传的记录（可带柜号）
.\.venv\Scripts\python.exe show_scans.py
.\.venv\Scripts\python.exe show_scans.py SYNC01

# 下发装柜任务（P2：柜号 计划数量 前缀列表 --note 备注）
.\.venv\Scripts\python.exe send_task.py ABCD1234567 1200 ASV12,XQB80 --note "夜班"
```

数据文件：`data\sync.db`（SQLite，备份时停服务后复制）。

### 3.1 电脑端记录表格（v1.3：按 PDA 分目录）

服务收到记录后自动按「PDA 名称 / 日期 / 柜号」生成 TSV（UTF-8 BOM，Excel/WPS 直接打开）：

```
data\装柜记录\一号机\2026-10-20\柜号.tsv     ← 一号机的未删除记录
data\装柜记录\二号机\2026-10-20\柜号.tsv     ← 二号机的记录，互不混合
data\回收站\一号机\2026-10-20\柜号.tsv       ← 一号机的删除/撤回记录
```

- 列：时间、柜号、条码、完整码、结果、操作员（与 PDA 本地 TSV 相同）。
- 启动时按数据库自动重建全部文件；手工删掉表格后重启即可恢复。
- 手动重建接口：`POST /api/v1/rebuild-exports`（带 `X-Sync-Token`）。
- 交付包内「打开数据文件夹（记录表格）.bat」可直接打开表格目录。

### 3.2 规则库同步（v1.9.0）

PDA 的「WiFi 同步 → 规则库同步」：
- **上传本机**：把本机规则库整库存到电脑端，`rev+1`。
- **下载并覆盖本机**：用电脑端规则库整库覆盖本机；覆盖前自动备份为
  `/sdcard/ZhuangGui/规则库/规则库_同步前备份_日期时间.xlsx`，备份失败会取消。
- 已装柜的柜子使用各自的规则快照，不受影响。

电脑网页（推荐在电脑上改）：
1. 打开 `http://127.0.0.1:8000/`，找到「规则库」卡片。
2. 点「下载 Excel（当前规则库）」，用 Excel/WPS 修改型号、前缀、四个开关。
3. 点「上传覆盖电脑端」选择改好的 XLSX；电脑端 `rev+1`，旧快照自动备份到
   `data\规则库备份\规则库_覆盖前_日期时间.xlsx`。
4. 各 PDA 在 WiFi 同步页点「刷新信息」→「下载并覆盖本机规则库」。

接口：
- `GET /api/v1/rulelib`（PDA 下载快照）
- `POST /api/v1/rulelib`（PDA 上传）
- `GET /api/v1/rulelib.xlsx?token=...`（网页下载 Excel）
- `POST /api/v1/rulelib/import`（网页上传 Excel，原始字节 + `X-Sync-Token`）

数据：`data\sync.db` 的 `rulelib` 表（单行快照：rev / models_json / rules_json / updated_at / updated_by）；每次覆盖前的旧快照另存 `data\规则库备份\规则库_覆盖前_日期时间.xlsx`。

### 3.3 多台 PDA：命名 / 筛选 / 下载（v1.3）

- PDA 首次同步自动登记，默认名称为设备号（如 `PDA-DFE666`）。
- 电脑网页「PDA 设备」卡片：
  - 「改名」：改成 一号机 / 二号机 / 张三；**已有记录文件夹会一起改名**。
  - 显示每台最近同步时间、记录数。
  - 「下载记录 ZIP」：打包该 PDA 的 `装柜记录/` + `回收站/`（ZIP 内以 PDA 名称作为顶层目录）。
- 「最近收到的扫码记录」右侧可按 PDA 筛选。
- 接口：
  - `GET /api/v1/devices`
  - `POST /api/v1/devices/{deviceId}/name`（JSON `{"name":"一号机"}`）
  - `GET /api/v1/devices/{deviceId}/records.zip?token=...`
  - `GET /api/v1/scans?deviceId=...`

> 建议一个柜号固定由一台 PDA 扫描；同一柜分两台扫时记录会分别在两台 PDA 的文件夹，电脑端不会自动合并。
> 规则库目前仍是所有 PDA 共用一份。

## 4. 开机自启（可选）

用「任务计划程序」创建任务：触发器 = 登录时，操作 = 启动
`E:\saoma-sync\run_server.bat`，勾选「隐藏」、最高权限。
免安装 EXE 版本可用同目录的「开机自启（可选）.bat」创建启动快捷方式。

## 4.1 打包成 EXE（换电脑/客户机免装 Python）

开发机执行 `build_exe.ps1`（需要 Python 3.11，仅打包机需要）：

```powershell
.\build_exe.ps1
```

产物 `dist\ZhuangGuiSyncServer.exe`，改名为 `装柜同步服务.exe` 后与
`使用说明.txt`、`防火墙放行（管理员）.bat`、`开机自启（可选）.bat`、
`打开数据文件夹（记录表格）.bat` 一起压缩交付。
EXE 首次运行会在**自身目录**创建 `data\`（token + SQLite + 记录表格），不依赖 Python 环境。
Web 管理页：`http://127.0.0.1:8000/`（配对二维码、令牌、最近记录、记录目录）。

## 5. 现场网络

- PDA 与电脑连同一个 WiFi / 路由器；关闭 AP 隔离。
- 建议在路由器给电脑做 DHCP 保留（固定 IP）。
- 没有路由器时：电脑开移动热点（默认 192.168.137.1），PDA 连热点。
- 服务只监听局域网，不需要外网；不需要云服务。

## 6. 验证过的功能（P0+P1，v1.3 增强）

- /health、配对（二维码/手输，token 校验）
- 扫码记录自动上传（5 秒合并；断网缓存后补传）
- 软删除 / 撤回状态同步
- uid 幂等（重复上传不翻倍）
- 401 token 失效提示
- EXE 免安装包：双击运行、浏览器自动打开管理页（配对二维码/令牌/最近记录），
  数据存 exe 同目录 `data\`；已在全新目录解压运行验证
- 电脑端 TSV 落盘：按 PDA 名称/日期/柜号分目录，删除进回收站，启动重建
- 多 PDA：自动登记、网页改名（文件夹迁移）、按 PDA 筛选、下载记录 ZIP（v1.3 实测）
- 规则库同步：PDA 上传/下载、电脑网页 Excel 下载/导入、rev 递增、下载前备份（v1.9.0 实测）
