@echo off
cd /d "%~dp0"
python -X utf8 scripts\portfolio_runtime.py inbox
if errorlevel 1 pause
