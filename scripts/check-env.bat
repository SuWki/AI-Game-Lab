@echo off
REM R8-A7: 构建环境自检（Windows cmd）
REM 检查 JAVA_HOME / JDK 版本 / Maven / Node 是否满足 docs/BUILD_ENVIRONMENT.md 要求。
REM 退出码 0 = 全部满足；非 0 = 有项不满足（打印缺失项）。
REM 用法：scripts\check-env.bat

echo ============================================
echo   AI Game Lab - Build Environment Check
echo   (要求见 docs/BUILD_ENVIRONMENT.md)
echo ============================================

set FAIL=0

REM ---- 1. JAVA_HOME ----
echo.
if "%JAVA_HOME%"=="" (
  echo   [FAIL] JAVA_HOME 未设置（必须指向 JDK 21+ 安装根目录）
  set FAIL=1
) else (
  echo   [ OK ] JAVA_HOME=%JAVA_HOME%
)

REM ---- 2. JDK 版本 ----
where java >nul 2>nul
if %errorlevel%==0 (
  java -version 2>&1 | findstr /b "java version openjdk" >nul
  echo   [ OK ] 已找到 java（运行 java -version 查看版本，需 ^>=21）
  echo.
  java -version 2>&1 | findstr /b "java version openjdk"
) else (
  echo   [FAIL] 未找到 java 命令（请先安装 JDK 21+ 并配置 PATH/JAVA_HOME）
  set FAIL=1
)

REM ---- 3. Maven ----
echo.
where mvn >nul 2>nul
if %errorlevel%==0 (
  for /f "tokens=1-3" %%a in ('mvn -v 2^>nul ^| findstr /b "Apache Maven"') do echo   [ OK ] %%a %%b %%c
) else (
  echo   [FAIL] 未找到 mvn 命令（请安装 Maven 3.9.x）
  set FAIL=1
)

REM ---- 4. Node.js ----
echo.
where node >nul 2>nul
if %errorlevel%==0 (
  echo   [ OK ] node
  node -v
) else (
  echo   [FAIL] 未找到 node 命令（前端需要 Node 18+）
  set FAIL=1
)

REM ---- 5. npm ----
echo.
where npm >nul 2>nul
if %errorlevel%==0 (
  echo   [ OK ] npm
  npm -v
) else (
  echo   [FAIL] 未找到 npm 命令
  set FAIL=1
)

echo.
echo ============================================
if %FAIL%==0 (
  echo   RESULT: PASS - 构建环境满足要求
  exit /b 0
) else (
  echo   RESULT: FAIL - 见上方 [FAIL] 项，参考 docs/BUILD_ENVIRONMENT.md
  exit /b 1
)
