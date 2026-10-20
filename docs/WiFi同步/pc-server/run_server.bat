@echo off
cd /d E:\saoma-sync
if not exist logs mkdir logs
".venv\Scripts\python.exe" -m uvicorn server:app --host 0.0.0.0 --port 8000 >> logs\server.log 2>&1
