# BUILD ENVIRONMENT — 构建与开发环境

> 由 **R8-A7** 建立（2026-09-17），目标：让任何新开发机器能明确知道本项目的
> **Required JDK / Maven** 配置并成功执行 `mvn clean test` 与前端构建。
> 本文档只描述**构建环境事实**，不修改任何生产代码 / 依赖版本 / pom.xml。

> 2026-11-11 文档核对：首次安装与启动步骤见 [README](../README.md)。下文 R8 的机器路径、测试数为 **2026-09-17 历史实测**，不代表当前机器探测或本次回归结果。

---

## 1. Required 工具链（硬性要求）

| 工具 | 版本要求 | 说明 |
|---|---|---|
| **JDK** | **21+**（本项目以 `--release 21` 编译，产物字节码 major 65 = Java 21） | `pom.xml`: `java.version=21` + `maven.compiler.release=21`。历史实测含 JDK 24.0.1；其他版本需在实际环境验证依赖与工具链兼容性 |
| **Maven** | 3.9.x（实测 3.9.10） | 仓库实测路径 `D:\apache-maven-3.9.10`（非系统默认路径，见 TD-002） |
| **Node.js** | 18+（当前前端 Vite 5 工具链） | `package.json` 未声明 engines；具体依赖约束以锁文件和安装输出为准 |
| **npm** | 随 Node.js | 前端依赖管理 |

> `maven.compiler.release=21` 指定成功构建时的 Java 21 编译目标；它不保证所有未来 JDK 与现有插件、依赖兼容。可先使用独立 JDK 21，并以 `mvn -v` 确认 Maven 实际选择的 Java。

---

## 2. JAVA_HOME 设置（本项目最重要的一条）

`JAVA_HOME` 必须指向 **JDK 21+** 的安装根目录。**历史开发机（2026-09-17）系统级
`JAVA_HOME` 错误地指向 JDK 8**（`C:\Program Files\Java\jdk-1.8`），直接使用会导致
Maven 编译失败（"invalid target release: 21"）。

**历史开发机 JDK 位置**（2026-09-17 实测；新机器请替换为自己的安装路径）：

```
C:\Users\Administrator\.jdks\openjdk-24.0.1   # JDK 24.0.1，--release 21 构建
```

### 如何确认当前 JAVA_HOME

```bash
echo $JAVA_HOME          # Windows Git Bash 下
echo %JAVA_HOME%         # Windows cmd 下
java -version            # 应显示版本号 ≥21
mvn -v                   # 确认 Maven 实际使用的 Java 与 Java home
```

PowerShell 使用 `$env:JAVA_HOME` 查看变量；若 `java -version` 与 `mvn -v` 不一致，应同时检查 `PATH` 与 `JAVA_HOME`。

### 临时设置（当前终端生效）

```bash
# Windows cmd
set JAVA_HOME=C:\Users\Administrator\.jdks\openjdk-24.0.1

# Git Bash / Unix
export JAVA_HOME=~/.jdks/openjdk-24.0.1
```

```powershell
# Windows PowerShell（按实际 JDK 路径修改）
$env:JAVA_HOME = "C:\Users\Administrator\.jdks\openjdk-24.0.1"
$env:Path = "$env:JAVA_HOME\bin;$env:Path"
mvn -v
```

> **建议**：把系统级 `JAVA_HOME` 修正为 JDK 21+（或从 `C:\Program Files\Java\jdk-1.8`
> 改指到 `~/.jdks/openjdk-24.0.1`），避免每个终端都要手动 export。该修正属于机器环境
> 配置，不涉及仓库代码。

---

## 3. Maven 本地仓库（TD-002）

历史开发机 Maven 本地仓库位于非默认路径（TD-002，长期技术债）。2026-09-17 实测：

```
Maven 安装：D:\apache-maven-3.9.10
本地仓库  ：（Maven settings.xml 中配置的 repo，非 ~/.m2 默认位置）
```

后端命令默认使用 `mvn`（离线 `-o` 或不离线均可；R6/R8 实测用 `mvn clean test` 非离线
构建通过）。若新机器首次构建需联网拉依赖，去掉 `-o` 即可。

---

## 4. 后端构建与测试

```bash
cd backend
mvn clean test          # 全量功能测试（默认排除 perf，耗时依机器而异）
```

- 产出字节码：Java 21（major 65），可用 `javap -v` 验证任意 `.class`。
- 性能测试默认跳过（`@Tag("perf")`，Surefire 在 `pom.xml` 中配置 `excludedGroups=perf`）。运行前需移除或覆盖该排除配置，再执行 `mvn test -Dtest="SimulationPerfTest" -Dgroups=perf`；仅指定 `-Dtest` 不能解除标签排除，请核对报告中的实际执行数。
- 单独跑某个测试：`mvn test -Dtest="ClassName"`。

---

## 5. 前端构建与测试

```bash
cd frontend
npm ci                  # 按已提交的 package-lock.json 安装依赖
npm test                # Vitest 单次运行
npm run build           # 生产构建（tsc -b && vite build）
npm run typecheck       # tsc -b --noEmit
```

---

## 6. 一键环境自检（scripts/check-env）

仓库提供跨平台环境自检脚本，检查 JAVA_HOME / JDK 版本 / Maven / Node：

```bash
bash scripts/check-env.sh     # Unix / Git Bash
scripts/check-env.bat         # Windows cmd
```

退出码 0 表示脚本覆盖的检查通过；非 0 表示存在未满足项。脚本检查 Java 主版本，但对 Maven / Node / npm 主要检查命令是否存在，不完整校验它们的版本要求，也不验证 API 凭据或服务启动。仍需自行查看 `mvn -v`、`node -v`、安装输出，并执行构建。

---

## 7. 历史开发机已验证事实（2026-09-17，R8 实测）

| 项 | 值 |
|---|---|
| 构建 JDK | `C:\Users\Administrator\.jdks\openjdk-24.0.1`（JDK 24.0.1） |
| 系统 JAVA_HOME | ⚠️ `C:\Program Files\Java\jdk-1.8`（**错误**，须指向 JDK 21+） |
| Maven | 3.9.10（`D:\apache-maven-3.9.10`） |
| 字节码版本 | major 65（Java 21，`--release 21` 生效） |
| 后端回归 | 620 tests / 0 failures / 0 errors / BUILD SUCCESS |
| 前端回归 | 57 tests passed + tsc + build 全过 |
| 操作系统 | Windows 10 Pro 10.0.19045 |
