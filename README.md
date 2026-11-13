# AI Game Lab

AI Game Lab 是本地运行的游戏 AI 工作平台：Designer 通过工具和数值模拟提出设计修改，Tester 执行测试，Research 整理外部资料，QA 团队编排角色任务并生成 Release Gate 裁决。所有模块共用一个后端、一个前端和一个 SQLite 数据库。

产品方向（2026-11-12 更新）：支持**已有游戏的分析改进**与**同类型新游戏的设计制造**。平台支持本地命令测试、源码/产物版本证据、TestPlan/Finding 导入、受限 Agent 工具，以及人工批准后的独立工作区修改与回归。game-demo 是验证样例。见 [三批开发交付与审查](docs/reports/THREE_BATCH_DELIVERY_2026-11-12.md) 和 [后续开发路线](docs/design/DEVELOPMENT_ROADMAP.md)。

> 文档更新：2026-11-12；当前开发基于 `724f01a` 的工作区变更。进度与验收记录见 [task-state.md](docs/state/task-state.md)，完整导航见 [文档索引](docs/index.md)。

## 当前能力与边界

整体范围与五模块职责见 [产品总设计](docs/architecture/GAME_LAB_PRODUCT_DESIGN.md)，生产执行见 [Builder 设计](docs/projects/05_AI_Auto_Game_Developer/README.md)。下一步重点是 [独立验收与构建来源](docs/platform/IMPROVEMENT_VALIDATION_DESIGN.md)：当前注册检查通过不能单独证明优化目标达成，这部分为待实施设计。

| 模块 | 仓库中的实现 |
|---|---|
| Designer | P0–P8：读取设计与配置、模拟实验、结果分析、修改提案、人工审批、版本快照与报告 |
| Tester | T1–T7：Test Plan、GameAdapter、FakeGameEnvironment、Invariant、Finding 与配置回归 |
| Research | R0–R2：搜索、页面抓取、Source/Claim、报告；用户触发研究 → 设计工作流 |
| QA | Phase 1–3：Finding 过滤、QA_LEAD 与角色子任务、Release Gate、dominantBuildShare 派生 |
| 中台 | Dashboard、Run Trace、Experiment Center、Config & Diff、Findings、Test Plans、Regression、Research、Workflow |
| 本地游戏接入 | 通用命令、版本/产物哈希、结构化 QA、导入 TestPlan/Finding、受限分析/测试任务；页面 `/local-tests` |
| 受控修改 | 前后文本审查、人工批准/拒绝、隔离工作区应用、保留基线检查的回归；页面 `/local-tests/changes` |
| 共享 Runtime | 合法函数协议、冻结授权与参数、整批持久化/历史回放、审批恢复、有界队列、取消与重试 |
| 模板生产 | `/productions` 选择精确规格，后端任务生成独立项目，保存构建/模板指纹与日志；支持幂等提交、取消、失败和重启留存 |

QA Phase 3 已有实现和 closure audit（16/16 PASS）；审计通过与人工验收是不同状态。Builder 已有受限模板生产身份与中台，通用源码制造/自动修复尚未实现；当前数据模型未引入 `project_id`，仍是单项目运行。角色范围见 [Builder Runtime](docs/platform/BUILDER_RUNTIME.md)。

配置采用 append-only 全量快照，最新版本即当前版本；现有代码未提供历史版本激活或一键回滚接口。

接入自己的本地游戏测试程序，见 [本地测试执行协议](docs/platform/LOCAL_GAME_TEST_EXECUTION.md) 和 [目标配置示例](backend/local-test-targets.example.json)。共享执行恢复见 [Runtime 契约](docs/platform/AGENT_RUNTIME_RECOVERY.md)。单机可信目录运行，独立工作区不是操作系统沙箱，验证通过后仍需人工决定合并。

## 技术栈与目录

| 层 | 当前实现 |
|---|---|
| 后端 | Java 21 字节码目标、Spring Boot 3.3.5、Maven |
| 前端 | React 18、TypeScript、Vite 5、React Query 3、React Router 6 |
| 持久化 | SQLite、JdbcTemplate、WAL、单连接池 |
| AI | 统一 LlmGateway，REST 调用 DeepSeek（OpenAI 兼容协议） |
| 搜索 | SerpApi；页面解析使用 Jsoup |
| 实时 | Spring 事件、SSE、SQLite 事件表 |

```text
AI-Game-Designer/
├── backend/       # Spring Boot；resources 包含配置、Schema 和 Prompt
├── frontend/      # React 中台
├── scripts/       # 启动、环境自检、数据库重置
├── docs/          # 架构、Project 设计、状态与历史报告
└── data/          # 根目录数据目录；实际数据库位置取决于后端工作目录
```

## 快速开始

环境要求与排障见 [BUILD_ENVIRONMENT.md](docs/BUILD_ENVIRONMENT.md)：JDK 21+、Maven 3.9.x、Node.js 18+、npm。先确认 `mvn -v` 实际使用的 Java 版本。

### 1. 安装依赖

首次使用需联网下载依赖；在项目根目录执行：

```powershell
Push-Location backend
mvn test
Pop-Location
Push-Location frontend
npm ci
Pop-Location
```

### 2. 配置凭据

启动后端前在对应终端设置环境变量（PowerShell 示例，替换为自己的凭据）：

```powershell
$env:DEEPSEEK_API_KEY = "<你的 DeepSeek API Key>"
$env:SERPAPI_API_KEY = "<你的 SerpApi API Key>"
```

- `DEEPSEEK_API_KEY`：执行真实 Agent Run 必需。未配置时可启动中台并使用独立 Experiment API，但 Agent 无可用 LlmGateway。
- `SERPAPI_API_KEY`：使用 `web_search` 时必需；未配置则该工具返回 BLOCKED。
- 默认模型为 `deepseek-chat`，其他配置见 [application.yml](backend/src/main/resources/application.yml)。本项目未配置自动加载 `.env`，请使用进程环境变量。

### 3. 启动服务

从项目根目录执行：

```powershell
.\scripts\dev.bat
```

Unix/macOS 使用 `bash scripts/dev.sh`。两个脚本均使用 `mvn -o spring-boot:run`，需先缓存 Maven 依赖和启动插件；若提示离线依赖缺失，先按下面方式在线启动一次。

手动启动（分别在两个终端执行）：

```bash
# 终端 1
cd backend
mvn spring-boot:run
```

```bash
# 终端 2
cd frontend
npm run dev
```

打开 [中台](http://localhost:5173)。后端默认 `http://localhost:8080`；Vite 开发代理转发 `/api` 和 `/sse` 到后端。

Windows 脚本在独立窗口启动两项服务，停止时分别关闭对应窗口；Unix/macOS 脚本在当前终端后台启动，使用 Ctrl+C 停止。

## 页面入口

| 路径 | 用途 |
|---|---|
| `/` | Dashboard |
| `/workflow` | 创建 Task、显式启动 Run、观察任务状态 |
| `/runs` | Run Trace |
| `/experiments` | 实验结果与对比 |
| `/config-diff` | 配置版本、字段 Diff、提案审批 |
| `/test-plans` | 测试计划 |
| `/findings` | 测试发现 |
| `/regression` | 配置回归 |
| `/research` | 研究来源、主张、报告及研究 → 设计入口 |

## 运行任务与模拟

通过 `/workflow` 创建任务后，还需点击启动 Run；`POST /api/tasks` 只创建 Task，不会自动执行。API 对应步骤为：

```bash
curl -X POST http://localhost:8080/api/tasks \
  -H 'Content-Type: application/json' \
  -d '{"title":"Build 平衡分析","goal":"比较三个 Build，提出配置修改建议","agentType":"DESIGNER"}'

# 把 TASK_ID 替换为上一步响应 data.id
curl -X POST http://localhost:8080/api/tasks/TASK_ID/runs
```

上述 curl 示例使用 Bash 续行语法。PowerShell 可使用 `Invoke-RestMethod` 提交同样的 JSON。

需要审批的修改会产生 PENDING 提案并暂停 Run；在 `/config-diff` 批准或拒绝后，后端恢复关联 Run。运行状态可在 `/runs` 或 `GET /api/runs/{id}` 查看。

独立模拟不需要创建 Task 或调用 LLM：

```bash
curl -X POST http://localhost:8080/api/experiments/run \
  -H 'Content-Type: application/json' \
  -d '{"configVersion":"v001","seed":12345,"count":1000,"scenario":"ALL_BUILDS"}'
```

接口返回 HTTP 202 表示异步提交；随后在 `/experiments` 或 `GET /api/experiments` 查看结果。`count` 为每个 Build 的局数，ALL_BUILDS 对三个 Build 分别执行；相同版本、seed、count、scenario 的已完成实验会复用。

QA Phase 3 的 `dominantBuildShare` 从同一配置版本最后一个 DONE 的 ALL_BUILDS 实验派生，计算 `max(games) / sum(games)`，表示模拟局数占比。当前三个 Build 等量模拟时为 1/3，不能据此衡量玩家偏好或 DPS 平衡。缺失 share 会在 Gate step 4c 得到 INCONCLUSIVE（更高优先级条件仍先判断）。详见 [Phase 3 设计](docs/QA_PHASE3_ARCHITECTURE_DESIGN.md) 与 [实验选择契约审计](docs/QA_PHASE3_EXPERIMENT_SELECTION_CONTRACT_AUDIT.md)。

## 数据与重置

SQLite 路径为相对后端进程工作目录的 `./data/agl.db`。以上脚本和手动命令均在 `backend/` 中启动，因此实际使用 `backend/data/agl.db`；从根目录运行 Jar 则使用根目录 `data/agl.db`。

如需清空本地数据，先停止后端并备份数据库，再运行 `scripts/reset-db.bat`。该脚本删除 `backend/data/agl.db` 及其 WAL/SHM 文件，下一次启动自动重建并导入种子；它不会清理其他工作目录下的数据库。

## 构建与验证

```bash
# 后端（从 backend/ 执行；默认排除 perf 标签）
mvn clean test
mvn test -Dtest="P8E2E*"          # Designer Golden / Exception Path

# 前端（从 frontend/ 执行）
npm run typecheck
npm test
npm run build
```

性能测试 `SimulationPerfTest` 带有 `perf` 标签；运行前需移除或覆盖 `backend/pom.xml` 中 Surefire 的 `excludedGroups=perf` 排除配置，再执行 `mvn test -Dtest="SimulationPerfTest" -Dgroups=perf`，并确认测试报告显示实际执行了用例。仅指定测试类不能解除排除。性能依赖机器环境，历史基准见 [P8-1 报告](docs/reports/Sprint_P8-1_Checkpoint_Report.md)。历史测试数与验收结果均按各报告的日期和基线理解，本次文档同步不代表重新执行全量回归。

## 文档入口

- [项目整体评估](docs/reports/PROJECT_REVIEW_2026-11-11.md) / [后续开发路线](docs/design/DEVELOPMENT_ROADMAP.md)：当前实现存在真实模型协议、历史回放与执行授权等缺口，已实现模块不等于完整自主流程已验收。
- [文档索引与阅读顺序](docs/index.md)
- [当前状态与历史记录](docs/state/task-state.md)
- [技术债登记](docs/state/tech-debt.md)
- [架构红线](docs/architecture/ARCHITECTURE_RULES.md) / [文档治理](docs/architecture/DOCUMENTATION_RULES.md)
- [QA Phase 3 收口审计](docs/reports/QA_PHASE3_CLOSURE_AUDIT.md)
