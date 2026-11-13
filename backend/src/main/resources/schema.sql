-- AI Game Lab Phase 1 schema (SQLite)
-- 依据 docs/projects/01_AI_Game_Designer/技术设计.md §7
-- 全部使用 IF NOT EXISTS：spring.sql.init.mode=always 下重复启动安全

-- 用户目标
CREATE TABLE IF NOT EXISTS task (
    id INTEGER PRIMARY KEY AUTOINCREMENT,
    title TEXT NOT NULL,
    goal TEXT NOT NULL,
    constraints_json TEXT NOT NULL DEFAULT '{}',
    max_steps INTEGER NOT NULL DEFAULT 30,
    approval_required INTEGER NOT NULL DEFAULT 1,
    status TEXT NOT NULL DEFAULT 'ACTIVE',
    created_at TEXT NOT NULL DEFAULT (datetime('now','localtime'))
);

-- 一次 Agent 执行
CREATE TABLE IF NOT EXISTS run (
    id INTEGER PRIMARY KEY AUTOINCREMENT,
    task_id INTEGER NOT NULL REFERENCES task(id),
    status TEXT NOT NULL DEFAULT 'PENDING',
        -- PENDING/RUNNING/WAITING_APPROVAL/COMPLETED/FAILED/CANCELLED/TIMEOUT
    prompt_version_id INTEGER REFERENCES prompt_version(id),
    model TEXT NOT NULL DEFAULT 'deepseek-chat',
    current_step INTEGER NOT NULL DEFAULT 0,
    max_steps INTEGER NOT NULL DEFAULT 30,
    token_budget INTEGER NOT NULL DEFAULT 200000,
    tokens_used INTEGER NOT NULL DEFAULT 0,
    error_message TEXT,
    final_result TEXT,
    started_at TEXT,
    finished_at TEXT,
    created_at TEXT NOT NULL DEFAULT (datetime('now','localtime'))
);
CREATE INDEX IF NOT EXISTS idx_run_task_status ON run(task_id, status);

-- 一次 LLM 循环（一次 LLM 调用 = 一个 Step）
CREATE TABLE IF NOT EXISTS step (
    id INTEGER PRIMARY KEY AUTOINCREMENT,
    run_id INTEGER NOT NULL REFERENCES run(id),
    seq INTEGER NOT NULL,
    phase TEXT NOT NULL,               -- LLM_CALL / FINAL_ANSWER / ERROR
    llm_content TEXT,                  -- 模型文本输出（决策摘要，非隐藏 CoT）
    prompt_tokens INTEGER NOT NULL DEFAULT 0,
    completion_tokens INTEGER NOT NULL DEFAULT 0,
    duration_ms INTEGER,
    created_at TEXT NOT NULL DEFAULT (datetime('now','localtime')),
    UNIQUE(run_id, seq)
);
CREATE INDEX IF NOT EXISTS idx_step_run ON step(run_id);

-- 工具调用（独立表：一个 Step 可含多个 tool call；支持审批与精确 Resume）
CREATE TABLE IF NOT EXISTS tool_call (
    id INTEGER PRIMARY KEY AUTOINCREMENT,
    step_id INTEGER NOT NULL REFERENCES step(id),
    run_id INTEGER NOT NULL REFERENCES run(id),
    tool_name TEXT NOT NULL,
    arguments_json TEXT,
    result_json TEXT,
    status TEXT NOT NULL DEFAULT 'STARTED',          -- STARTED/COMPLETED/FAILED
    approval_status TEXT NOT NULL DEFAULT 'NOT_REQUIRED', -- NOT_REQUIRED/PENDING/APPROVED/REJECTED
    started_at TEXT,
    ended_at TEXT,
    error TEXT
);
CREATE INDEX IF NOT EXISTS idx_tc_run ON tool_call(run_id);

-- 可复现实验（结果内嵌 result_json；冗余指标列供列表过滤排序）
CREATE TABLE IF NOT EXISTS experiment (
    id INTEGER PRIMARY KEY AUTOINCREMENT,
    run_id INTEGER REFERENCES run(id),
    config_version TEXT NOT NULL,
    seed INTEGER NOT NULL,
    simulation_count INTEGER NOT NULL,
    scenario TEXT NOT NULL DEFAULT 'ALL_BUILDS',
    status TEXT NOT NULL DEFAULT 'RUNNING',          -- RUNNING/DONE/FAILED
    win_rate REAL,
    avg_dps REAL,
    avg_game_time_s REAL,
    result_json TEXT,
    duration_ms INTEGER,
    created_at TEXT NOT NULL DEFAULT (datetime('now','localtime')),
    UNIQUE(config_version, seed, simulation_count, scenario)
);
CREATE INDEX IF NOT EXISTS idx_exp_cfg ON experiment(config_version);

-- 游戏配置版本（append-only 全量快照）
CREATE TABLE IF NOT EXISTS game_config_version (
    version TEXT PRIMARY KEY,            -- 'v001'
    config_json TEXT NOT NULL,
    parent_version TEXT,
    change_proposal_id INTEGER,
    comment TEXT,
    created_at TEXT NOT NULL DEFAULT (datetime('now','localtime'))
);

-- 变更提案（Human-in-the-loop 核心）
CREATE TABLE IF NOT EXISTS change_proposal (
    id INTEGER PRIMARY KEY AUTOINCREMENT,
    run_id INTEGER REFERENCES run(id),
    base_version TEXT NOT NULL,
    target_version TEXT,                 -- approve 后回填
    title TEXT NOT NULL,
    reason TEXT NOT NULL,
    evidence_experiment_ids TEXT NOT NULL DEFAULT '[]',
    diff_json TEXT NOT NULL,             -- [{path,before,after}]
    after_config_json TEXT NOT NULL,     -- 批准后落库的全量快照
    status TEXT NOT NULL DEFAULT 'PENDING', -- PENDING/APPROVED/REJECTED
    decided_at TEXT,
    decided_comment TEXT,
    created_at TEXT NOT NULL DEFAULT (datetime('now','localtime'))
);
CREATE INDEX IF NOT EXISTS idx_proposal_status ON change_proposal(status);

-- 事件流（持久化 + SSE 数据源）
CREATE TABLE IF NOT EXISTS agent_event (
    id INTEGER PRIMARY KEY AUTOINCREMENT,
    event_id TEXT NOT NULL UNIQUE,
    type TEXT NOT NULL,
    run_id TEXT,
    task_id TEXT,
    payload_json TEXT NOT NULL DEFAULT '{}',
    created_at TEXT NOT NULL DEFAULT (datetime('now','localtime'))
);
CREATE INDEX IF NOT EXISTS idx_event_run ON agent_event(run_id, id);

-- Agent 产出的设计文档
CREATE TABLE IF NOT EXISTS design_document (
    id INTEGER PRIMARY KEY AUTOINCREMENT,
    run_id INTEGER REFERENCES run(id),
    task_id INTEGER REFERENCES task(id),
    title TEXT NOT NULL,
    content_md TEXT NOT NULL,
    created_at TEXT NOT NULL DEFAULT (datetime('now','localtime'))
);

-- System Prompt 版本
CREATE TABLE IF NOT EXISTS prompt_version (
    id INTEGER PRIMARY KEY AUTOINCREMENT,
    version TEXT NOT NULL UNIQUE,        -- 'p001'
    system_prompt TEXT NOT NULL,
    description TEXT,
    is_active INTEGER NOT NULL DEFAULT 0,
    created_at TEXT NOT NULL DEFAULT (datetime('now','localtime'))
);

-- ============================================================
-- AI Game Tester（T-DB1，已批准 DDL：docs/projects/02_AI_Game_Tester/T-DB1_DDL_Review.md）
-- 明确不新增 tester_run/tester_step/tester_event/tester_project 等其他 Tester 表
-- ============================================================

-- 测试计划（一次测试任务的计划实体；DRAFT → ACTIVE → COMPLETED）
CREATE TABLE IF NOT EXISTS test_plan (
    id INTEGER PRIMARY KEY AUTOINCREMENT,
    task_id INTEGER NOT NULL REFERENCES task(id),
    config_version TEXT NOT NULL,
    status TEXT NOT NULL DEFAULT 'DRAFT',   -- DRAFT/ACTIVE/COMPLETED
    scenarios_json TEXT NOT NULL,           -- 场景数组 JSON（技术设计 §5 结构）
    created_at TEXT NOT NULL DEFAULT (datetime('now','localtime'))
);
CREATE INDEX IF NOT EXISTS idx_tp_task ON test_plan(task_id);

-- 缺陷/发现（Tester 的一等产出，供 Designer/Builder 消费）
CREATE TABLE IF NOT EXISTS finding (
    id INTEGER PRIMARY KEY AUTOINCREMENT,
    run_id INTEGER REFERENCES run(id),
    test_plan_id INTEGER REFERENCES test_plan(id),
    task_id INTEGER REFERENCES task(id),
    scenario_id TEXT NOT NULL,
    config_version TEXT NOT NULL,           -- 冗余列：回归对比核心查询路径
    severity TEXT NOT NULL,                 -- BLOCKER/HIGH/MEDIUM/LOW/INFO
    category TEXT NOT NULL,                 -- CRASH/SOFT_LOCK/INVARIANT_VIOLATION/UNEXPECTED_STATE/BALANCE_ANOMALY/OTHER
    source TEXT NOT NULL DEFAULT 'LLM',     -- INVARIANT/LLM/MANUAL（检测来源，与 category 正交）
    title TEXT NOT NULL,
    expected TEXT,
    actual TEXT,
    repro_steps TEXT NOT NULL,              -- Markdown 步骤：必含 seed + scenarioId + 动作序列恢复路径(toolCallId 区间)
    evidence_json TEXT NOT NULL DEFAULT '{}',
    reproduction_rate REAL,
    status TEXT NOT NULL DEFAULT 'OPEN',    -- OPEN/CONFIRMED/DISMISSED/FIXED
    created_at TEXT NOT NULL DEFAULT (datetime('now','localtime'))
);
CREATE INDEX IF NOT EXISTS idx_finding_ver_sev ON finding(config_version, severity);
CREATE INDEX IF NOT EXISTS idx_finding_run ON finding(run_id);

-- ============================================================
-- Research Agent（R0-3）
-- ============================================================

-- 研究来源（R0-3：Research Agent 搜索到的每个网页/文献来源）
CREATE TABLE IF NOT EXISTS research_source (
    id INTEGER PRIMARY KEY AUTOINCREMENT,
    run_id INTEGER NOT NULL REFERENCES run(id),
    task_id INTEGER NOT NULL REFERENCES task(id),
    url TEXT NOT NULL,
    source_type TEXT NOT NULL,       -- Official/Developer/Academic/Community/Speculation
    title TEXT NOT NULL,
    content_summary TEXT,            -- 页面正文摘要（fetch_page 提取后截断存储）
    reliability REAL,                -- 0.0-1.0，来源可靠性评分
    fetched_at TEXT,
    created_at TEXT NOT NULL DEFAULT (datetime('now','localtime'))
);
CREATE INDEX IF NOT EXISTS idx_rs_run ON research_source(run_id);
CREATE INDEX IF NOT EXISTS idx_rs_task ON research_source(task_id);

-- 研究断言（R0-3：Research Agent 从来源中提取的 Claims）
CREATE TABLE IF NOT EXISTS research_claim (
    id INTEGER PRIMARY KEY AUTOINCREMENT,
    run_id INTEGER NOT NULL REFERENCES run(id),
    task_id INTEGER NOT NULL REFERENCES task(id),
    claim TEXT NOT NULL,
    confidence REAL NOT NULL DEFAULT 0.0,  -- 0.0-1.0，置信度
    supporting_source_ids TEXT NOT NULL DEFAULT '[]',   -- JSON array of research_source.id
    contradicting_source_ids TEXT NOT NULL DEFAULT '[]', -- JSON array of research_source.id
    status TEXT NOT NULL DEFAULT 'DRAFT',  -- DRAFT/VALIDATED/DISPUTED
    created_at TEXT NOT NULL DEFAULT (datetime('now','localtime'))
);
CREATE INDEX IF NOT EXISTS idx_rc_run ON research_claim(run_id);
CREATE INDEX IF NOT EXISTS idx_rc_task ON research_claim(task_id);
CREATE INDEX IF NOT EXISTS idx_rc_confidence ON research_claim(confidence);

-- ============================================================
-- QA Phase 2（R5）—— 新增 2 张表（ADD-only，不改既有 14 表）
-- 依据：docs/QA_ARCHITECTURE_FREEZE.md §14 DDL（AF-001 人工批准）+ R4 Schema Decision
-- ============================================================

-- QA 角色分配（编排事务实体，父子 run 关联权威；不得在 run 表加 parent_run_id）
CREATE TABLE IF NOT EXISTS qa_role_assignment (
    id INTEGER PRIMARY KEY AUTOINCREMENT,
    parent_run_id INTEGER NOT NULL REFERENCES run(id),   -- Lead Run
    lead_task_id INTEGER NOT NULL REFERENCES task(id),   -- Lead Task（R5 §4 必含）
    sub_task_id INTEGER REFERENCES task(id),             -- 子角色 Task（qa_start_subrun 建）
    sub_run_id INTEGER REFERENCES run(id),               -- 子 Run（普通 run 行）
    role TEXT NOT NULL,                -- PLAYER_AGENT/COMBAT_ANALYST/NUMERICAL_ANALYST/EXPLOIT_HUNTER/UX_REVIEWER
    player_profile TEXT,               -- NOVICE/AGGRESSIVE/BUILD_SPECIALIST
    status TEXT NOT NULL DEFAULT 'PENDING',
        -- PENDING/RUNNING/COMPLETED/FAILED/TIMEOUT/CANCELLED   （DB3 6 态，A7 裁决）
    order_no INTEGER NOT NULL DEFAULT 0,                  -- 顺序编排号（Lead=0，子角色 1..7）
    result_summary TEXT,               -- 子 Run 完成摘要
    error_message TEXT,                -- 失败/超时原因（R5 §4 必要错误信息）
    sub_run_steps_used INTEGER,        -- 子 Run current_step
    sub_run_tokens_used INTEGER,       -- 子 Run tokens_used
    detail_json TEXT DEFAULT '{}',     -- 扩展信息（evidence 摘要等）
    created_at TEXT NOT NULL DEFAULT (datetime('now','localtime')),
    updated_at TEXT
);
CREATE INDEX IF NOT EXISTS idx_qra_parent ON qa_role_assignment(parent_run_id);
CREATE INDEX IF NOT EXISTS idx_qra_lead ON qa_role_assignment(lead_task_id);
CREATE INDEX IF NOT EXISTS idx_qra_subrun ON qa_role_assignment(sub_run_id);
CREATE INDEX IF NOT EXISTS idx_qra_subtask ON qa_role_assignment(sub_task_id);
CREATE UNIQUE INDEX IF NOT EXISTS idx_qra_lead_role ON qa_role_assignment(lead_task_id, role);

-- Release Gate（每棵 QA 树 / 每 target config 一条门禁结论）
CREATE TABLE IF NOT EXISTS qa_release_gate (
    id INTEGER PRIMARY KEY AUTOINCREMENT,
    lead_task_id INTEGER NOT NULL REFERENCES task(id),   -- 被测 QA Lead Task（R5 §4）
    run_id INTEGER NOT NULL REFERENCES run(id),          -- Lead Run
    config_version TEXT NOT NULL,                        -- 被测版本（带索引，DB4）
    status TEXT NOT NULL DEFAULT 'PENDING',      -- PENDING/PASS/CONDITIONAL_PASS/FAIL（DB1 4 态）
    automatic_result TEXT,                        -- PASS/CONDITIONAL_PASS/FAIL/INCONCLUSIVE（服务端自动）
    rule_version TEXT,                            -- 判定公式版本
    lead_decision TEXT,                           -- QA Lead LLM 判定（证据，非权威）
    lead_decision_evidence_json TEXT DEFAULT '{}',-- lead_decision 佐证
    final_result TEXT,                            -- 人工确认后的权威结果（PENDING/PASS/CONDITIONAL_PASS/FAIL）
    is_consistent INTEGER NOT NULL DEFAULT 1,     -- auto vs lead 一致（审计）
    inconclusive_count INTEGER NOT NULL DEFAULT 0,-- 关键 evidence 缺失计数
    manual_override INTEGER NOT NULL DEFAULT 0,   -- 1=人工覆盖自动判定（审计 DB2）
    override_reason TEXT,
    override_by TEXT,
    override_at TEXT,
    critical_bugs INTEGER NOT NULL DEFAULT 0,
    high_bugs INTEGER NOT NULL DEFAULT 0,
    medium_bugs INTEGER NOT NULL DEFAULT 0,
    soft_lock_count INTEGER NOT NULL DEFAULT 0,
    crash_rate REAL,
    dominant_build_share REAL,
    stats_json TEXT NOT NULL DEFAULT '{}',        -- 完整统计快照（gate 计算留痕）
    report_doc_id INTEGER REFERENCES design_document(id),  -- Release Report→design_document 复用
    created_at TEXT NOT NULL DEFAULT (datetime('now','localtime')),
    decided_at TEXT
);
CREATE INDEX IF NOT EXISTS idx_qrg_task ON qa_release_gate(lead_task_id);
CREATE INDEX IF NOT EXISTS idx_qrg_config ON qa_release_gate(config_version);  -- DB4
CREATE INDEX IF NOT EXISTS idx_qrg_status ON qa_release_gate(status);

-- Local command test executions. Independent of Agent Run protocol; shared database.
CREATE TABLE IF NOT EXISTS local_test_run (
    id TEXT PRIMARY KEY,
    record_json TEXT NOT NULL
);

-- Additive runtime protocol persistence: old tool_call / run layouts remain readable.
CREATE TABLE IF NOT EXISTS runtime_context (
    run_id INTEGER PRIMARY KEY REFERENCES run(id) ON DELETE CASCADE,
    context_json TEXT NOT NULL
);
CREATE TABLE IF NOT EXISTS runtime_turn (
    step_id INTEGER PRIMARY KEY REFERENCES step(id) ON DELETE CASCADE,
    run_id INTEGER NOT NULL REFERENCES run(id) ON DELETE CASCADE,
    assistant_content TEXT,
    calls_json TEXT NOT NULL
);
CREATE TABLE IF NOT EXISTS local_test_context (
    run_id TEXT PRIMARY KEY REFERENCES local_test_run(id) ON DELETE CASCADE,
    binding_json TEXT NOT NULL
);
CREATE TABLE IF NOT EXISTS production_artifact (
    artifact_id TEXT NOT NULL,
    revision INTEGER NOT NULL,
    task_id INTEGER NOT NULL REFERENCES task(id),
    kind TEXT NOT NULL,
    document_id INTEGER NOT NULL REFERENCES design_document(id),
    envelope_json TEXT NOT NULL,
    PRIMARY KEY(artifact_id, revision)
);

CREATE TABLE IF NOT EXISTS production_job (
    id TEXT PRIMARY KEY,
    task_id INTEGER NOT NULL REFERENCES task(id),
    request_key TEXT NOT NULL,
    record_json TEXT NOT NULL,
    UNIQUE(task_id, request_key)
);

CREATE TABLE IF NOT EXISTS local_change_proposal (
    id TEXT PRIMARY KEY,
    status TEXT NOT NULL,
    proposal_json TEXT NOT NULL
);
