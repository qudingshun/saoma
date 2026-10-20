@echo off
chcp 65001 >nul
powershell -NoProfile -Command "$ws=New-Object -ComObject WScript.Shell; $lnk=$env:APPDATA + '\Microsoft\Windows\Start Menu\Programs\Startup\装柜同步服务.lnk'; $s=$ws.CreateShortcut($lnk); $s.TargetPath='%~dp0装柜同步服务.exe'; $s.WorkingDirectory='%~dp0'; $s.Save()"
echo.
echo 已创建开机自启快捷方式：%%APPDATA%%\Microsoft\Windows\Start Menu\Programs\Startup\装柜同步服务.lnk
pause
