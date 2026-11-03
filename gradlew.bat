@echo off
powershell -NoProfile -ExecutionPolicy Bypass -File "%~dp0tools\gradle-bootstrap.ps1" %*
exit /b %ERRORLEVEL%
