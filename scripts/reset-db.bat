@echo off
REM P8-1: 重置数据库（Windows）
REM 删除 SQLite 数据库文件，下次启动时 schema.sql 自动重建 + v001 种子导入
REM 用法：scripts\reset-db.bat

setlocal

set PROJECT_ROOT=%~dp0..
set DB_DIR=%PROJECT_ROOT%\backend\data

echo ============================================
echo   AI Game Lab - Reset Database
echo   Target: %DB_DIR%\agl.db*
echo ============================================
echo.

if not exist "%DB_DIR%\agl.db" (
    echo No database found at %DB_DIR%\agl.db
    echo Nothing to reset.
    pause
    exit /b 0
)

echo Deleting agl.db files...
del /q "%DB_DIR%\agl.db"      2>nul
del /q "%DB_DIR%\agl.db-shm"  2>nul
del /q "%DB_DIR%\agl.db-wal"  2>nul

echo Database reset complete.
echo Next startup will recreate from schema.sql with v001 seed.
echo.
pause
