@echo off
setlocal
cd /d "%~dp0"
powershell.exe -NoLogo -NoProfile -ExecutionPolicy Bypass -File "%~dp0scripts\start-local.ps1" %*
set "startupExit=%errorlevel%"
if not "%startupExit%"=="0" pause
exit /b %startupExit%
