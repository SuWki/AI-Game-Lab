#!/usr/bin/env bash
# P8-1: 一键启动脚本（Unix/macOS）
# 同时启动后端（:8080）与前端（:5173）
# 用法：bash scripts/dev.sh

set -e

SCRIPT_DIR="$(cd "$(dirname "$0")" && pwd)"
PROJECT_ROOT="$(cd "$SCRIPT_DIR/.." && pwd)"
BACKEND_DIR="$PROJECT_ROOT/backend"
FRONTEND_DIR="$PROJECT_ROOT/frontend"

echo "============================================"
echo "  AI Game Lab - Dev Startup"
echo "  Backend  : http://localhost:8080"
echo "  Frontend : http://localhost:5173"
echo "  SSE      : /sse/global"
echo "============================================"
echo ""

# 启动后端
echo "[1/2] Starting backend (Spring Boot, port 8080)..."
cd "$BACKEND_DIR"
mvn -o spring-boot:run &
BACKEND_PID=$!

# 等待后端启动
echo "    Waiting 8s for backend to initialize..."
sleep 8

# 启动前端
echo "[2/2] Starting frontend (Vite dev server, port 5173)..."
cd "$FRONTEND_DIR"
npm run dev &
FRONTEND_PID=$!

echo ""
echo "Both services started."
echo "  Backend PID  : $BACKEND_PID"
echo "  Frontend PID : $FRONTEND_PID"
echo "Press Ctrl+C to stop both."
echo ""

# 捕获 Ctrl+C 并清理
trap "echo ''; echo 'Stopping...'; kill $BACKEND_PID $FRONTEND_PID 2>/dev/null; exit 0" INT TERM

wait
