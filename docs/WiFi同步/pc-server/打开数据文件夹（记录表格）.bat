@echo off
chcp 65001 >nul
if not exist "%~dp0data\装柜记录" mkdir "%~dp0data\装柜记录"
start "" explorer "%~dp0data\装柜记录"
