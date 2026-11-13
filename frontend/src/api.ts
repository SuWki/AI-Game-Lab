import type { ApiResp, Finding, TestPlan, Experiment, ExperimentPair, RunRow, RunTrace, ConfigVersion, ChangeProposal, FindingRegression, ResearchSource, ResearchClaim, ResearchReport, ClaimSources, TaskRow } from "@/types";

const BASE = "/api";
export interface ProductionArtifact {artifactId:string;revision:number;kind:string;taskId:number;contentHash:string;content:{title?:string}}
export interface ProductionJob {id:string;taskId:number;specId:string;specRevision:number;specHash:string;status:string;localRunId?:string;error?:string;result?:{workspace:string;build:{buildId:string;files:Record<string,string>}}}

export interface LocalTestTarget {
  id: string; name: string; workingDirectory: string; command: string[];
  timeoutSeconds: number; resultPath: string | null;
  versionFiles?: string[]; artifactFiles?: string[];
}
export interface LocalBinding { versioned:boolean;version:string|null;source:Record<string,string>;artifacts?:Record<string,string>;taskId?:number;testPlanId?:number;findingIds?:number[] }
export interface LocalAssessment { localTestRunId:string;decision:"PASS"|"FAIL"|"INCONCLUSIVE";reason?:string;totalChecks?:number;failedChecks?:number;binding:LocalBinding }
export interface LocalEdit {path:string;beforeSha256:string|null;beforeText?:string|null;afterText:string}
export interface LocalChange {id:string;status:string;baseRunId:string;title:string;edits:LocalEdit[];workspace:string|null;validationRunId:string|null;error:string|null}
export interface LocalTestRun {
  id: string; target: LocalTestTarget; status: string; startedAt: string;
  finishedAt: string | null; exitCode: number | null; output: string;
  evidence: unknown; error: string | null;
}

async function unwrap<T>(res: Promise<Response>): Promise<T> {
  const r = await res;
  if (!r.ok) {
    const text = await r.text().catch(() => "");
    throw new Error(`HTTP ${r.status}: ${text || r.statusText}`);
  }
  const body = (await r.json()) as ApiResp<T>;
  if (body.code !== 0) {
    throw new Error(`API ${body.code}: ${body.message}`);
  }
  return body.data;
}

export interface FindingFilter {
  configVersion?: string;
  severity?: string;
  runId?: number;
}

/**
 * QA Finding 8 维过滤（GET /api/qa/findings，全维度 AND，runIds 多值 IN）。
 * 与 Tester 的 {@link FindingFilter}（3 参 /api/findings）分离——同一领域，
 * 不同 endpoint，互不污染。
 */
export interface QaFindingFilter {
  configVersion?: string;
  severity?: string;
  category?: string;
  source?: string;
  status?: string;
  runIds?: number[];
  testPlanId?: number;
  taskId?: number;
}

export const api = {
  productionArtifacts:(taskId:number)=>unwrap<ProductionArtifact[]>(fetch(`${BASE}/production-artifacts?taskId=${taskId}`)),
  productionJobs:()=>unwrap<ProductionJob[]>(fetch(`${BASE}/productions`)),
  createBuilderTask:(body:{taskId:number;specId:string;specRevision:number;goal:string})=>unwrap<TaskRow>(fetch(`${BASE}/productions/agent-task`,{method:"POST",headers:{"Content-Type":"application/json","X-AGL-Local-Test":"1"},body:JSON.stringify(body)})),
  startProduction:(body:{taskId:number;specId:string;specRevision:number;requestKey:string})=>unwrap<ProductionJob>(fetch(`${BASE}/productions`,{method:"POST",headers:{"Content-Type":"application/json","X-AGL-Local-Test":"1"},body:JSON.stringify(body)})),
  cancelProduction:(id:string)=>unwrap<ProductionJob>(fetch(`${BASE}/productions/${encodeURIComponent(id)}/cancel`,{method:"POST",headers:{"X-AGL-Local-Test":"1"}})),
  localAssessment: (id:string)=>unwrap<LocalAssessment>(fetch(`${BASE}/qa/local-tests/${encodeURIComponent(id)}`)),
  createLocalAgentTask:(id:string,agentType:"DESIGNER"|"TESTER",goal:string)=>unwrap<TaskRow>(fetch(`${BASE}/local-tests/runs/${encodeURIComponent(id)}/agent-task`,{method:"POST",headers:{"Content-Type":"application/json","X-AGL-Local-Test":"1"},body:JSON.stringify({agentType,goal})})),
  importLocalChecks: (id:string)=>unwrap<LocalBinding>(fetch(`${BASE}/local-tests/runs/${encodeURIComponent(id)}/import`,{method:"POST",headers:{"X-AGL-Local-Test":"1"}})),
  localSource: (id:string,path:string)=>unwrap<{path:string;sha256:string;text:string}>(fetch(`${BASE}/local-tests/runs/${encodeURIComponent(id)}/source?path=${encodeURIComponent(path)}`)),
  localChanges: ()=>unwrap<LocalChange[]>(fetch(`${BASE}/local-tests/changes`)),
  createLocalChange: (body:{baseRunId:string;title:string;edits:LocalEdit[]})=>unwrap<LocalChange>(fetch(`${BASE}/local-tests/changes`,{method:"POST",headers:{"Content-Type":"application/json","X-AGL-Local-Test":"1"},body:JSON.stringify(body)})),
  decideLocalChange: (id:string,decision:"approve"|"reject")=>unwrap<LocalChange>(fetch(`${BASE}/local-tests/changes/${encodeURIComponent(id)}/${decision}`,{method:"POST",headers:{"X-AGL-Local-Test":"1"}})),
  localTestTargets: () => unwrap<LocalTestTarget[]>(fetch(`${BASE}/local-tests/targets`)),
  localTestRuns: () => unwrap<LocalTestRun[]>(fetch(`${BASE}/local-tests/runs`)),
  localTestRun: (id: string) => unwrap<LocalTestRun>(fetch(`${BASE}/local-tests/runs/${encodeURIComponent(id)}`)),
  startLocalTest: (id: string) => unwrap<LocalTestRun>(fetch(`${BASE}/local-tests/targets/${encodeURIComponent(id)}/runs`, { method: "POST", headers:{"X-AGL-Local-Test":"1"} })),
  cancelLocalTest: (id: string) => unwrap<LocalTestRun>(fetch(`${BASE}/local-tests/runs/${encodeURIComponent(id)}/cancel`, { method: "POST", headers:{"X-AGL-Local-Test":"1"} })),
  /** Tester Finding 列表（GET /api/findings，3 参：configVersion/severity/runId）。 */
  listFindings(filter: FindingFilter = {}): Promise<Finding[]> {
    const qs = new URLSearchParams();
    if (filter.configVersion) qs.set("configVersion", filter.configVersion);
    if (filter.severity) qs.set("severity", filter.severity);
    if (filter.runId != null) qs.set("runId", String(filter.runId));
    const q = qs.toString();
    return unwrap<Finding[]>(fetch(`${BASE}/findings${q ? `?${q}` : ""}`));
  },

  /**
   * QA Finding 列表（GET /api/qa/findings，已获人工批准的既有 API）。
   * 8 维 AND 过滤；runIds 多值重复参数（后端 IN 语义）；空过滤返回全部。
   */
  listQaFindings(filter: QaFindingFilter = {}): Promise<Finding[]> {
    const qs = new URLSearchParams();
    if (filter.configVersion) qs.set("configVersion", filter.configVersion);
    if (filter.severity) qs.set("severity", filter.severity);
    if (filter.category) qs.set("category", filter.category);
    if (filter.source) qs.set("source", filter.source);
    if (filter.status) qs.set("status", filter.status);
    (filter.runIds ?? []).forEach((id) => qs.append("runIds", String(id)));
    if (filter.testPlanId != null) qs.set("testPlanId", String(filter.testPlanId));
    if (filter.taskId != null) qs.set("taskId", String(filter.taskId));
    const q = qs.toString();
    return unwrap<Finding[]>(fetch(`${BASE}/qa/findings${q ? `?${q}` : ""}`));
  },

  getFinding(id: number): Promise<Finding> {
    return unwrap<Finding>(fetch(`${BASE}/findings/${id}`));
  },

  updateFindingStatus(id: number, status: string, comment?: string): Promise<Finding> {
    return unwrap<Finding>(
      fetch(`${BASE}/findings/${id}/status`, {
        method: "POST",
        headers: { "Content-Type": "application/json" },
        body: JSON.stringify({ status, comment: comment ?? "" }),
      })
    );
  },

  getTestPlan(id: number): Promise<TestPlan> {
    return unwrap<TestPlan>(fetch(`${BASE}/test-plans/${id}`));
  },

  // 工具：解析 scenariosJson
  parseScenarios(plan: TestPlan) {
    try {
      return JSON.parse(plan.scenariosJson) as import("@/types").Scenario[];
    } catch {
      return [];
    }
  },

  // 工具：解析 evidenceJson
  parseEvidence(f: Finding) {
    try {
      return JSON.parse(f.evidenceJson) as import("@/types").FindingEvidence;
    } catch {
      return null;
    }
  },

  // ==================== P7-4 Experiment ====================
  listExperiments(filter: { configVersion?: string; scenario?: string } = {}): Promise<Experiment[]> {
    const qs = new URLSearchParams();
    if (filter.configVersion) qs.set("configVersion", filter.configVersion);
    if (filter.scenario) qs.set("scenario", filter.scenario);
    const q = qs.toString();
    return unwrap<Experiment[]>(fetch(`${BASE}/experiments${q ? `?${q}` : ""}`));
  },

  getExperiment(id: number): Promise<ExperimentDetail> {
    return unwrap(fetch(`${BASE}/experiments/${id}`));
  },

  runExperiment(body: { configVersion?: string; seed?: number; count?: number; scenario?: string; runId?: number }): Promise<{ status: string; message: string }> {
    return unwrap(fetch(`${BASE}/experiments/run`, {
      method: "POST",
      headers: { "Content-Type": "application/json" },
      body: JSON.stringify(body),
    }));
  },

  compareExperiments(ids: number[]): Promise<ExperimentPair[]> {
    return unwrap(fetch(`${BASE}/experiments/compare`, {
      method: "POST",
      headers: { "Content-Type": "application/json" },
      body: JSON.stringify({ ids }),
    }));
  },

  // 工具：解析 experiment.result_json
  parseSimulationResult(e: Experiment): SimulationResultLike | null {
    try {
      return JSON.parse(e.resultJson ?? "null") as SimulationResultLike;
    } catch {
      return null;
    }
  },

  // ==================== R2-3 Task / 工作流驱动 ====================
  listTasks(): Promise<TaskRow[]> {
    return unwrap<TaskRow[]>(fetch(`${BASE}/tasks`));
  },

  createTask(body: {
    title: string;
    goal: string;
    agentType: string; // DESIGNER | TESTER | RESEARCH
    researchSourceTaskId?: number | null;
    maxSteps?: number;
  }): Promise<TaskRow> {
    return unwrap<TaskRow>(fetch(`${BASE}/tasks`, {
      method: "POST",
      headers: { "Content-Type": "application/json" },
      body: JSON.stringify(body),
    }));
  },

  startRun(taskId: number): Promise<number> {
    return unwrap<number>(fetch(`${BASE}/tasks/${taskId}/runs`, { method: "POST",headers:{"X-AGL-Local-Test":"1"} }));
  },
  controlRun:(id:number,action:"cancel"|"resume"|"retry")=>unwrap<number|void>(fetch(`${BASE}/runs/${id}/${action}`,{method:"POST",headers:{"X-AGL-Local-Test":"1"}})),

  // 工具：解析 task.constraintsJson.agent_type（无/解析失败 → "UNKNOWN"）
  parseTaskAgentType(task: TaskRow): string {
    try {
      const c = JSON.parse(task.constraintsJson || "{}");
      return typeof c.agent_type === "string" ? c.agent_type : "UNKNOWN";
    } catch {
      return "UNKNOWN";
    }
  },

  // 工具：解析 task.constraintsJson.researchSourceTaskId（研究→设计链引用，无则 null）
  parseResearchSourceTaskId(task: TaskRow): number | null {
    try {
      const c = JSON.parse(task.constraintsJson || "{}");
      return typeof c.researchSourceTaskId === "number" ? c.researchSourceTaskId : null;
    } catch {
      return null;
    }
  },

  // ==================== P7-3 Run Trace ====================
  listRuns(): Promise<RunRow[]> {
    return unwrap<RunRow[]>(fetch(`${BASE}/runs`));
  },

  getRunTrace(id: number): Promise<RunTrace> {
    return unwrap<RunTrace>(fetch(`${BASE}/runs/${id}`));
  },

  // ==================== P7-5 Config & Diff ====================
  listConfigVersions(): Promise<ConfigVersion[]> {
    return unwrap<ConfigVersion[]>(fetch(`${BASE}/config/versions`));
  },

  listProposals(status?: string): Promise<ChangeProposal[]> {
    const qs = new URLSearchParams();
    if (status) qs.set("status", status);
    const q = qs.toString();
    return unwrap<ChangeProposal[]>(fetch(`${BASE}/config/proposals${q ? `?${q}` : ""}`));
  },

  approveProposal(id: number, comment?: string): Promise<ConfigVersion> {
    return unwrap<ConfigVersion>(
      fetch(`${BASE}/config/proposals/${id}/approve`, {
        method: "POST",
        headers: { "Content-Type": "application/json" },
        body: JSON.stringify({ comment: comment ?? null }),
      })
    );
  },

  rejectProposal(id: number, comment?: string): Promise<void> {
    return unwrap<void>(
      fetch(`${BASE}/config/proposals/${id}/reject`, {
        method: "POST",
        headers: { "Content-Type": "application/json" },
        body: JSON.stringify({ comment: comment ?? null }),
      })
    );
  },

  // ==================== T7-6 Finding Regression ====================
  getFindingRegression(oldVersion: string, newVersion: string): Promise<FindingRegression> {
    const qs = new URLSearchParams({ oldVersion, newVersion });
    return unwrap<FindingRegression>(fetch(`${BASE}/findings/regression?${qs}`));
  },

  // ==================== R1-2 Research ====================
  listResearchSources(runId?: number, taskId?: number): Promise<ResearchSource[]> {
    const qs = new URLSearchParams();
    if (runId != null) qs.set("runId", String(runId));
    if (taskId != null) qs.set("taskId", String(taskId));
    const q = qs.toString();
    return unwrap<ResearchSource[]>(fetch(`${BASE}/research/sources${q ? `?${q}` : ""}`));
  },

  listResearchClaims(runId?: number, taskId?: number, status?: string): Promise<ResearchClaim[]> {
    const qs = new URLSearchParams();
    if (runId != null) qs.set("runId", String(runId));
    if (taskId != null) qs.set("taskId", String(taskId));
    if (status) qs.set("status", status);
    const q = qs.toString();
    return unwrap<ResearchClaim[]>(fetch(`${BASE}/research/claims${q ? `?${q}` : ""}`));
  },

  // TD：断言 → 支持/反驳来源 drill-down（GET /api/research/claims/{id}/sources）
  getClaimSources(claimId: number): Promise<ClaimSources> {
    return unwrap<ClaimSources>(fetch(`${BASE}/research/claims/${claimId}/sources`));
  },

  listResearchReports(runId?: number, taskId?: number): Promise<ResearchReport[]> {
    const qs = new URLSearchParams();
    if (runId != null) qs.set("runId", String(runId));
    if (taskId != null) qs.set("taskId", String(taskId));
    const q = qs.toString();
    return unwrap<ResearchReport[]>(fetch(`${BASE}/research/reports${q ? `?${q}` : ""}`));
  },

  // 工具：解析 proposal.diff_json（unified-diff 文本）→ DiffLine[]
  // 后端 ConfigDiffer.toUnifiedText() 为每个字段输出独立的 "- path: val" / "+ path: val" 行；
  // 如果同一 path 出现了 - 和 +，合并为一条 ~ 修改行（before → after）。
  parseDiff(diffJson: string | null): import("@/types").DiffLine[] {
    if (!diffJson) return [];
    const rawLines = diffJson.split("\n").filter((l) => l.length > 0);
    // 先收集所有增/删行，按 path 分组
    const deletes = new Map<string, string>(); // path → before text
    const adds = new Map<string, string>();    // path → after text
    const pathOrder: string[] = [];            // 保持出现顺序

    for (const line of rawLines) {
      const idx = line.indexOf(": ");
      if (line.startsWith("- ") && idx >= 0) {
        const path = line.slice(2, idx);
        const val = line.slice(idx + 2);
        deletes.set(path, val);
        if (!pathOrder.includes(path)) pathOrder.push(path);
      } else if (line.startsWith("+ ") && idx >= 0) {
        const path = line.slice(2, idx);
        const val = line.slice(idx + 2);
        adds.set(path, val);
        if (!pathOrder.includes(path)) pathOrder.push(path);
      }
    }

    const out: import("@/types").DiffLine[] = [];
    for (const path of pathOrder) {
      const before = deletes.get(path) ?? null;
      const after = adds.get(path) ?? null;
      if (before != null && after != null) {
        out.push({ op: "~", path, before, after });
      } else if (before != null) {
        out.push({ op: "-", path, before, after: null });
      } else {
        out.push({ op: "+", path, before: null, after });
      }
    }
    return out;
  },
};

/**
 * 后端 GET /api/experiments/{id} 的返回 data 形状
 * （ExperimentController 解析 result_json 为 Map）。
 */
export interface ExperimentDetail {
  id: number;
  runId: number | null;
  configVersion: string;
  seed: number;
  simulationCount: number;
  scenario: string;
  status: string;
  winRate: number | null;
  avgDps: number | null;
  avgGameTimeS: number | null;
  durationMs: number | null;
  createdAt: string | null;
  result?: SimulationResultLike;
}

/**
 * 后端 GET /api/experiments/{id}.data.result 的形状
 * （ExperimentController 解析 result_json 为 Map）。字段与 SimulationResult 对齐。
 */
export interface SimulationResultLike {
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
  buildDistribution: Record<string, { games: number; wins: number; winRate: number; ciLow: number; ciHigh: number }>;
  upgradePickRate: Record<string, number>;
  upgradePickRateInWins: Record<string, number>;
}
