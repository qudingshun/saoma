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
`使用说明.txt`、`防火墙放行（管理员）.bat`、`开机自启（可选）.bat` 一起压缩交付。
EXE 首次运行会在**自身目录**创建 `data\`（token + SQLite），不依赖 Python 环境。
Web 管理页：`http://127.0.0.1:8000/`（配对二维码、令牌、最近记录）。

## 5. 现场网络

- PDA 与电脑连同一个 WiFi / 路由器；关闭 AP 隔离。
- 建议在路由器给电脑做 DHCP 保留（固定 IP）。
- 没有路由器时：电脑开移动热点（默认 192.168.137.1），PDA 连热点。
- 服务只监听局域网，不需要外网；不需要云服务。

## 6. 验证过的功能（v1.8.0 P0+P1）

- /health、配对（二维码/手输，token 校验）
- 扫码记录自动上传（5 秒合并；断网缓存后补传）
- 软删除 / 撤回状态同步
- uid 幂等（重复上传不翻倍）
- 401 token 失效提示
- EXE 免安装包：双击运行、浏览器自动打开管理页（配对二维码/令牌/最近记录），
  数据存 exe 同目录 `data\`；已在全新目录解压运行验证
