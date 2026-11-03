@echo off
cd /d "%~dp0"
python -X utf8 scripts\portfolio_runtime.py accounts
if errorlevel 1 pause
