# 在开发机打包 Windows EXE（免安装 Python）
# 产物：dist\ZhuangGuiSyncServer.exe（复制/改名为 装柜同步服务.exe）
# 运行环境：Windows + Python 3.11（仅打包机需要；目标电脑不需要）

$ErrorActionPreference = "Stop"
Set-Location $PSScriptRoot

if (-not (Test-Path ".venv\Scripts\python.exe")) {
    python -m venv .venv
}
.\.venv\Scripts\python.exe -m pip install -i https://pypi.tuna.tsinghua.edu.cn/simple -r requirements.txt pyinstaller

.\.venv\Scripts\pyinstaller.exe --noconfirm --clean --onefile `
    --name ZhuangGuiSyncServer `
    --icon app.ico `
    --collect-submodules uvicorn `
    --collect-all qrcode `
    --collect-all openpyxl `
    --collect-all et_xmlfile `
    --hidden-import uvicorn.logging `
    --hidden-import uvicorn.loops.auto `
    --hidden-import uvicorn.protocols.http.auto `
    --hidden-import uvicorn.protocols.websockets.auto `
    --hidden-import uvicorn.lifespan.on `
    server.py

Write-Host ""
Write-Host "打包完成：dist\ZhuangGuiSyncServer.exe"
Write-Host "发布时改名为：装柜同步服务.exe（与 使用说明.txt、防火墙/自启 bat 一起打包）"
