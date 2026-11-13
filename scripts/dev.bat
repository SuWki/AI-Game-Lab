@echo off
REM P8-1: 一键启动脚本（Windows）
REM 同时启动后端（:8080）与前端（:5173）
REM 用法：在项目根目录双击运行，或 scripts\dev.bat

setlocal

set PROJECT_ROOT=%~dp0..
set BACKEND_DIR=%PROJECT_ROOT%\backend
set FRONTEND_DIR=%PROJECT_ROOT%\frontend

echo ============================================
echo   AI Game Lab - Dev Startup
echo   Backend  : http://localhost:8080
echo   Frontend : http://localhost:5173
echo   SSE      : /sse/global
echo ============================================
echo.

REM 启动后端（新窗口）
echo [1/2] Starting backend (Spring Boot, port 8080)...
start "AGL-Backend" cmd /k "cd /d %BACKEND_DIR% && mvn -o spring-boot:run"

REM 等待后端启动
echo     Waiting 8s for backend to initialize...
timeout /t 8 /nobreak >nul

REM 启动前端（新窗口）
echo [2/2] Starting frontend (Vite dev server, port 5173)...
start "AGL-Frontend" cmd /k "cd /d %FRONTEND_DIR% && npm run dev"

echo.
echo Both services started. Close this window or press Ctrl+C to stop.
echo.
pause
