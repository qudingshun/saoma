@echo off
chcp 65001 >nul
net session >nul 2>&1
if not %errorlevel%==0 (
  echo 请右键此文件，选择「以管理员身份运行」。
  pause
  exit /b
)
netsh advfirewall firewall add rule name="装柜PDA同步 8000" dir=in action=allow protocol=TCP localport=8000 profile=private
echo.
echo 已放行 8000 端口（专用网络）。
pause
