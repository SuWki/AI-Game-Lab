#!/usr/bin/env bash
# R8-A7: 构建环境自检（Unix / Git Bash）
# 检查 JAVA_HOME / JDK 版本 / Maven / Node 是否满足 BUILD_ENVIRONMENT.md 要求。
# 退出码 0 = 全部满足；非 0 = 有项不满足（打印缺失项）。
# 用法：bash scripts/check-env.sh

set -u

FAIL=0

echo "============================================"
echo "  AI Game Lab - Build Environment Check"
echo "  (要求见 docs/BUILD_ENVIRONMENT.md)"
echo "============================================"

# ---- 1. JAVA_HOME ----
echo ""
if [ -z "${JAVA_HOME:-}" ]; then
  echo "  [FAIL] JAVA_HOME 未设置（必须指向 JDK 21+ 安装根目录）"
  FAIL=1
else
  echo "  [ OK ] JAVA_HOME=$JAVA_HOME"
fi

# ---- 2. JDK 版本 ----
if command -v java >/dev/null 2>&1; then
  JAVA_VERSION_OUT="$(java -version 2>&1 | head -1)"
  echo "  [ OK ] java -version: $JAVA_VERSION_OUT"
  # 提取主版本号
  VER="$(echo "$JAVA_VERSION_OUT" | sed -E 's/.*version "([0-9]+).*/\1/')"
  if [ -n "$VER" ] && [ "$VER" -ge 21 ] 2>/dev/null; then
    echo "  [ OK ] JDK 主版本 $VER（>=21 满足）"
  else
    echo "  [FAIL] JDK 主版本 ${VER:-?} 低于 21（需 JDK 21+）"
    FAIL=1
  fi
else
  echo "  [FAIL] 未找到 java 命令（请先安装 JDK 21+ 并配置 PATH/JAVA_HOME）"
  FAIL=1
fi

# ---- 3. Maven ----
if command -v mvn >/dev/null 2>&1; then
  MVN_OUT="$(mvn -v 2>&1 | head -1)"
  echo "  [ OK ] $MVN_OUT"
else
  echo "  [FAIL] 未找到 mvn 命令（请安装 Maven 3.9.x）"
  FAIL=1
fi

# ---- 4. Node.js ----
if command -v node >/dev/null 2>&1; then
  echo "  [ OK ] node $(node -v)"
else
  echo "  [FAIL] 未找到 node 命令（前端需要 Node 18+）"
  FAIL=1
fi

# ---- 5. npm ----
if command -v npm >/dev/null 2>&1; then
  echo "  [ OK ] npm $(npm -v)"
else
  echo "  [FAIL] 未找到 npm 命令"
  FAIL=1
fi

echo ""
echo "============================================"
if [ "$FAIL" -eq 0 ]; then
  echo "  RESULT: PASS — 构建环境满足要求"
  exit 0
else
  echo "  RESULT: FAIL — 见上方 [FAIL] 项，参考 docs/BUILD_ENVIRONMENT.md"
  exit 1
fi
