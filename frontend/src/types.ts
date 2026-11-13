// 后端响应包络（蓝图 §8 / ApiResp）：{code, message, data}
export interface ApiResp<T> {
  code: number;
  message: string;
  data: T;
}

export type Severity = "BLOCKER" | "HIGH" | "MEDIUM" | "LOW" | "INFO";
export type Category =
  | "CRASH"
  | "SOFT_LOCK"
  | "INVARIANT_VIOLATION"
  | "UNEXPECTED_STATE"
  | "BALANCE_ANOMALY"
  | "OTHER";
export type FindingStatus = "OPEN" | "CONFIRMED" | "DISMISSED" | "FIXED";
export type TestPlanStatus = "DRAFT" | "ACTIVE" | "COMPLETED";

export interface FindingEvidence {
  sessionId: string;
  runId: number | null;
  toolCallIds: number[];
  observationSnapshot?: Record<string, unknown>;
  tick?: number;
  seed?: number;
  overrides?: Record<string, unknown>;
}

export interface Finding {
  id: number;
  runId: number | null;
  testPlanId: number | null;
  taskId: number | null;
  scenarioId: string;
  configVersion: string;
  severity: Severity;
  category: Category;
  source: "INVARIANT" | "LLM" | "MANUAL";
  title: string;
  expected: string | null;
  actual: string | null;
  reproSteps: string;
  evidenceJson: string; // JSON 字符串，解析为 FindingEvidence
  reproductionRate: number | null;
  status: FindingStatus;
  createdAt: string;
}

export interface Scenario {
  id: string;
  type: string;
  goal: string;
  seed: number;
  repeat: number;
  playerProfile: string;
  invariants: string[];
  overrides: Record<string, unknown>;
}

export interface TestPlan {
  id: number;
  taskId: number;
  configVersion: string;
  status: TestPlanStatus;
  scenariosJson: string; // JSON 字符串，解析为 Scenario[]
  createdAt: string;
}

// SSE 负载（SsePayload）：前端按 id 去重、按 type 分发
export interface SsePayload {
  dbId: number;
  eventId: string | null;
  type: string;
  runId: string;
  payloadJson: string;
}

// ==================== P7-4 Experiment 类型 ====================

/** 后端 Experiment record（GET /api/experiments 返回） */
export interface Experiment {
  id: number;
  runId: number | null;
  configVersion: string;
  seed: number;
  simulationCount: number;
  scenario: string;
  status: string; // RUNNING | DONE | FAILED
  winRate: number | null;
  avgDps: number | null;
  avgGameTimeS: number | null;
  resultJson: string | null;
  durationMs: number | null;
  createdAt: string;
}

/** SimulationResult（experiment.result_json 解析后） */
export interface SimulationResult {
  totalGames: number;
  winCount: number;
  deathCount: number;
  winRate: number;
  deathRate: number;
  avgGameTime: number;
  medianGameTime: number;
  p90GameTime: number;
  avgDps: number;
  avgTtk: number;
  gameTimeHistogram: number[];
  damageHistogram: number[];
  buildDistribution: Record<string, BuildDistribution>;
  upgradePickRate: Record<string, number>;
  upgradePickRateInWins: Record<string, number>;
}

export interface BuildDistribution {
  games: number;
  wins: number;
  winRate: number;
  ciLow: number;
  ciHigh: number;
}

/** compare 返回的对比对 */
export interface ExperimentPair {
  experimentA: number;
  experimentB: number;
  winRateA: number;
  winRateB: number;
  deltaWinRate: number;
  zScore: number;
  significant: boolean;
}

// ==================== P7-1 Dashboard 类型 ====================

export interface DashboardStats {
  totalRuns: number;
  completedRuns: number;
  failedRuns: number;
  runningRuns: number;
  waitingApprovalRuns: number;
  cancelledRuns: number;
  timeoutRuns: number;
  avgDurationMs: number | null;
  totalTokens: number;
  experimentCount: number;
  doneExperiments: number;
  successRate: number;
  designDocumentCount: number;
}

// ==================== P7-3 Run Trace 类型 ====================

/** 后端 Run record（GET /api/runs 列表项） */
export interface RunRow {
  id: number;
  taskId: number;
  status: string;
  promptVersionId: number | null;
  model: string;
  currentStep: number;
  maxSteps: number;
  tokenBudget: number;
  tokensUsed: number;
  errorMessage: string | null;
  finalResult: string | null;
  startedAt: string | null;
  finishedAt: string | null;
  createdAt: string | null;
}

/** 后端 ToolCall record（Step 内嵌） */
export interface ToolCallRow {
  id: number;
  stepId: number;
  runId: number;
  toolName: string;
  argumentsJson: string | null;
  resultJson: string | null;
  status: string;
  approvalStatus: string;
  startedAt: string | null;
  endedAt: string | null;
  error: string | null;
}

/** 后端 Step record（Run 详情内嵌） */
export interface StepRow {
  id: number;
  runId: number;
  seq: number;
  phase: string;
  llmContent: string | null;
  promptTokens: number;
  completionTokens: number;
  durationMs: number | null;
  createdAt: string | null;
}

/** Run 详情：run + steps（每 step 含 toolCalls 数组） */
export interface RunTrace {
  run: RunRow;
  steps: Array<{ step: StepRow; toolCalls: ToolCallRow[] }>;
}

// ==================== P7-5 Config & Diff 类型 ====================

/** 后端 ConfigVersion record（GET /api/config/versions 列表项） */
export interface ConfigVersion {
  version: string;
  configJson: string | null;
  parentVersion: string | null;
  changeProposalId: number | null;
  comment: string | null;
  createdAt: string | null;
}

/** 后端 ChangeProposal record（GET /api/config/proposals 列表项） */
export interface ChangeProposal {
  id: number;
  runId: number | null;
  baseVersion: string | null;
  targetVersion: string | null;
  title: string;
  reason: string;
  evidenceExperimentIds: string | null;
  diffJson: string | null;
  afterConfigJson: string | null;
  status: string;
  decidedAt: string | null;
  decidedComment: string | null;
  createdAt: string | null;
}

/** Diff 行展示模型（由后端 diff_json unified 文本解析） */
export interface DiffLine {
  op: "+" | "-" | "~";
  path: string;
  before: string | null;
  after: string | null;
}

// ==================== T7 Finding Regression 类型 ====================

/** FindingIdentity：跨版本归一化逻辑键 */
export interface FindingIdentity {
  scenarioId: string;
  category: string;
  normalizedTitle: string;
}

/** 单条 Finding 回归对比结果 */
export interface FindingRegressionItem {
  fingerprint: FindingIdentity;
  status: "NEW" | "FIXED" | "PERSISTED" | "CHANGED";
  oldSeverity: string | null;
  newSeverity: string | null;
  title: string;
  scenarioId: string;
  category: string;
}

/** 版本间 Finding 回归对比汇总 */
export interface FindingRegression {
  oldConfigVersion: string;
  newConfigVersion: string;
  items: FindingRegressionItem[];
  newCount: number;
  fixedCount: number;
  persistedCount: number;
  changedCount: number;
}

// ==================== R2-3 Task / 工作流类型 ====================

/** 后端 Task record（GET /api/tasks 列表项；constraintsJson 含 agent_type / researchSourceTaskId） */
export interface TaskRow {
  id: number;
  title: string;
  goal: string;
  constraintsJson: string;
  maxSteps: number;
  approvalRequired: boolean;
  status: string; // ACTIVE/…
  createdAt: string | null;
}

// ==================== R1-2 Research Agent 类型 ====================

/** 研究来源（后端 ResearchSource record，GET /api/research/sources） */
export interface ResearchSource {
  id: number;
  runId: number;
  taskId: number;
  url: string;
  sourceType: string; // Official/Developer/Academic/Community/Speculation
  title: string;
  contentSummary: string | null;
  reliability: number | null;
  fetchedAt: string | null;
  createdAt: string | null;
}

/** 研究断言（后端 ResearchClaim record，GET /api/research/claims） */
export interface ResearchClaim {
  id: number;
  runId: number;
  taskId: number;
  claim: string;
  confidence: number;
  supportingSourceIds: string; // JSON array of research_source.id
  contradictingSourceIds: string; // JSON array of research_source.id
  status: string; // DRAFT/VALIDATED/DISPUTED
  createdAt: string | null;
}

/** 研究报告（design_document 中 title 前缀 [RESEARCH REPORT] 的行，GET /api/research/reports） */
export interface ResearchReport {
  id: number;
  runId: number | null;
  taskId: number | null;
  title: string;
  contentMd: string;
  createdAt: string | null;
}

/** TD：断言证据链 drill-down（GET /api/research/claims/{id}/sources） */
export interface ClaimSources {
  claim: ResearchClaim;
  supporting: ResearchSource[];
  contradicting: ResearchSource[];
}

