@echo off
rem Bấm đúp file này để đóng gói: tự tăng version, build APK release, cài lên máy.
powershell -NoProfile -ExecutionPolicy Bypass -File "%~dp0release.ps1" -Bump -Install
pause
