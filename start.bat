@echo off
REM kubastion - double-click me, or run me from a terminal.
REM
REM Everything real happens in start.ps1, next to this file: batch cannot wait
REM on a TCP port or kill a process tree reliably, and a .exe would have to be
REM compiled and signed to get past SmartScreen - in an open source repo you
REM should be able to read what you are about to run.
powershell -NoProfile -ExecutionPolicy Bypass -File "%~dp0start.ps1" %*
if errorlevel 1 pause
