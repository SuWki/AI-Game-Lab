# QA Phase 3 — Experiment Selection Contract Audit

**Date:** 2026-09-22
**Audited commits:** `e335bb7`（implementation）/ `3291be5`（report）/ `f2b2d2c`（design reconciliation）
**Method:** adversarial audit — 5 independent refuter agents (one per evidence channel) tried to *disprove* the claim that the selection strategy is not a pre-existing contract; each agent's verdict independently corroborated by direct reads of the underlying files.
**Scope:** This is a proof deliverable. **No code change, no redesign, no new snapshot/table/API.**

---

## 1. Verdict

> **“latest DONE experiment + configVersion isolation” is NOT a pre-existing business contract.**
> It is a **new derived semantics introduced by QA Phase 3** (commit `e335bb7`, class `QaBuildShareDeriver`), consistent with — and built entirely out of — pre-existing contract primitives. It violates nothing, but it was not inherited from any earlier contract.

**Audit outcome: `NO_CONTRACT` (5/5 channels; 0 refutations succeeded).**

---

## 2. The Question Being Proved

`QaBuildShareDeriver.deriveDominantBuildShare(configVersion)` selects the build-share evidence via:

1. `experimentRepo.findByFilter(configVersion, "ALL_BUILDS")` — configVersion equality + scenario filter
2. keep the **last** row whose `status == "DONE"` (findByFilter returns `ORDER BY id ASC`)
3. compute `max(buildDistribution[*].games) / Σ(games)` from that row's `result_json`

The audit question: **does this selection strategy belong to an existing business contract** — i.e., was it already established (in code, prompts, frontend, schema, or frozen docs) *before* Phase 3, such that the deriver merely executes a rule the system already owned? Or is it an artifact Phase 3 introduced?

---

## 3. Evidence Chain (by channel)

### 3.1 Database schema — `backend/src/main/resources/schema.sql`

| Evidence | Location | Verdict |
|---|---|---|
| `UNIQUE(config_version, seed, simulation_count, scenario)` — the idempotency key **permits multiple DONE experiments per configVersion** (no partial unique index, no CHECK, no trigger) | :83 | **refutes** a “one latest per config” contract |
| `id INTEGER PRIMARY KEY AUTOINCREMENT` — generic ordering mechanism shared by every table, never tied to an experiment “latest wins” rule | :70 | not a contract |
| `status TEXT ... -- RUNNING/DONE/FAILED` — only enumerates the value domain; no DONE-cardinality constraint | :76 | not a contract |
| `idx_exp_cfg ON experiment(config_version)` — plain equality-filter access path, no recency/ordering semantics | :85 | not a contract |
| Table comment: “可复现实验（结果内嵌 result_json；冗余指标列供列表过滤排序）” — frames experiment as a **LIST entity** to filter/sort, the opposite of a per-config singleton | :68 | **refutes** a singleton contract |
| `qa_release_gate` “每棵 QA 树 / 每 target config 一条门禁结论” — closest singleton semantic, but governs the **gate** entity (not experiment selection) and is an **unenforced comment** (idx_qrg_config non-unique) | :253 | context, not a counter-example |

**Channel verdict: NO_CONTRACT. The schema affirmatively permits multiple DONE experiments per configVersion; nothing imposes "the latest DONE one."**

### 3.2 Backend main code — services / DAO / tools

| Evidence | Location | Verdict |
|---|---|---|
| `findByFilter` — `WHERE 1=1 [AND config_version=?] [AND scenario=?] ORDER BY id`, no `LIMIT 1`, no status filter | [ExperimentRepositoryImpl.java:85-98](backend/src/main/java/com/aigamelab/repository/ExperimentRepositoryImpl.java#L85-L98) | query surface only, **no selection rule** |
| `ExperimentService.create` idempotent reuse — matches on full `(config_version, seed, simulation_count, scenario)` tuple + `DONE`; `hit.get(hit.size()-1)` is de-dup fallback for a **specific idempotency key**, not a generic “latest experiment” rule | [ExperimentService.java:72-89](backend/src/main/java/com/aigamelab/experiment/ExperimentService.java#L72-L89) | the only pre-existing “last row” arithmetic, but scoped to an exact key match — **not a selection contract** |
| `ExperimentController.list/detail/compare` — list returns all rows; detail/compare by explicit `id`; no “current/latest experiment” endpoint | [ExperimentController.java:54-141](backend/src/main/java/com/aigamelab/api/ExperimentController.java#L54-L141) | no selection rule |
| `SimulateGameTool` — creates + returns the experiment it just ran; consumer uses the returned `experimentId` | [SimulateGameTool.java:100-119](backend/src/main/java/com/aigamelab/tool/SimulateGameTool.java#L100-L119) | id-based, no selection rule |
| `AnalyzeExperimentTool` / `CompareExperimentsTool` — take explicit `experimentId`/`ids` from the agent | | id-based, no selection rule |
| `QaBuildShareDeriver` itself — the “latest DONE + configVersion isolation” arithmetic lives **entirely inside the Phase-3 class**, self-documented as derived consumer-side logic | [QaBuildShareDeriver.java:63-67](backend/src/main/java/com/aigamelab/qa/QaBuildShareDeriver.java#L63-L67) | **the introduced rule, not evidence of a pre-existing one** |

**Channel verdict: NO_CONTRACT. No pre-Phase-3 Java path selects an experiment by recency+status for a configVersion.**

### 3.3 Agent prompts — `backend/src/main/resources/prompts/*.md`

| Evidence | Location | Verdict |
|---|---|---|
| `maxDominantBuildShare` — appears only as a game-balance **target metric** the designer iterates toward; no selection instruction | designer_p001.md:10 | not a selection contract |
| `config_version` — required only as a Finding **citation field** (traceability); not a selection directive | qa_numerical_analyst_p001.md:8 | not a contract |
| `experimentId` — required only as an evidence-citation field | qa_combat_analyst_p001.md:8 | not a contract |
| Global grep for `latest|newest|DONE|最新|最近|最后` across all 13 prompts: **zero matches**; all prompt files byte-identical since pre-Phase-3 commits (R3 `e7e18cd` / R5 `bf351e1`) | whole dir | **absence confirmed** |

**Channel verdict: NO_CONTRACT. Agents are told to cite `experimentId`/`config_version` on findings — the opposite of “select the latest one.”**

### 3.4 Frontend UI — `frontend/src/**`

| Evidence | Location | Verdict |
|---|---|---|
| Only experiment-selection logic: chart sample = **FIRST** experiment with `result_json` — the **opposite direction** of “latest DONE” | [ExperimentCenterPage.tsx:131-133](frontend/src/pages/ExperimentCenterPage.tsx#L131-L133) | **refutes** a “latest experiment” convention |
| Lists all experiments under user-entered filters; compare by manual multi-select | ExperimentCenterPage.tsx:18-56 | no default-newest logic |
| “latest by max id” arithmetic exists but **over RUNS per TASK** | [WorkflowPage.tsx:66-73](frontend/src/pages/WorkflowPage.tsx#L66-L73) | near-miss, different entity |
| “default select newest” — **over RUNS** for research observation | [ResearchPage.tsx:57-58](frontend/src/pages/ResearchPage.tsx#L57-L58) | near-miss, different entity |
| “default select latest” — **over ConfigVersion** entities | [ConfigDiffPage.tsx:49-53](frontend/src/pages/ConfigDiffPage.tsx#L49-L53) | near-miss, different entity |

**Channel verdict: NO_CONTRACT. The only pre-existing “latest” patterns operate on runs/config-versions, never on experiments; the one experiment-picking heuristic picks the FIRST, not the latest DONE.**

### 3.5 Pre-existing docs — `docs/**` (excluding Phase-3 docs)

| Evidence | Location | Verdict |
|---|---|---|
| Design doc frames the time-window as **“需设计决策”** (needs a design decision): “取该 config_version 最近一次 ALL_BUILDS 实验（快照语义）vs n 次/窗口聚合” — i.e., *Phase 3 itself* flagged the selection rule as undecided, and the design **chose** “last row” as a feasible default | [QA_PHASE3_ARCHITECTURE_DESIGN.md:88](docs/QA_PHASE3_ARCHITECTURE_DESIGN.md#L88) | **direct confirmation of new introduction** |
| Design: “取 `ORDER BY id` 最末行（Impl 已存在，零新 SQL）” / “过滤只考虑 DONE（ExperimentService 复用判断同款语义）” | [QA_PHASE3_ARCHITECTURE_DESIGN.md:146,156](docs/QA_PHASE3_ARCHITECTURE_DESIGN.md#L146) | design-blessed default, but a *new* rule |
| No pre-Phase-3 doc (freeze/R-series/P-series) defines experiment selection | whole `docs/` | **absence confirmed** |

**Channel verdict: NO_CONTRACT. The most decisive single artifact is the design doc's own admission (`:88`) that the time-window selection was an open design question — proving it was never a pre-existing business contract.**

---

## 4. Strongest Attempted Refutation — and Why It Fails

**Attempt:** Combine the frontend's pre-existing “latest wins” arithmetic (latest run per task; newest run for observation; latest config version) with the schema's one-per-config singleton comment on `qa_release_gate` (schema.sql:253) → read liberally as an existing “pick the newest per target config” business rule that Phase 3 merely applied to experiments.

**Outcome: FAILS.** Every candidate operates on a **different entity** — runs per task, runs for observation, config versions, `qa_release_gate` — and none instructs any consumer to select an **experiment** by recency + status + configVersion. The gate singleton is an unenforced comment backed by a non-unique index. And schema:83's idempotency key plus the line-68 list-entity comment affirmatively demonstrate that **multiple DONE experiments per configVersion are legal**, so a “latest DONE” rule cannot be schema- or prompt-imposed — it must be consumer-side arithmetic, which is exactly what `QaBuildShareDeriver.java:63-67` introduces.

---

## 5. But Mean: Does “Not a Pre-existing Contract” Undermine Phase 3?

**No.** The audit conclusion is a classification fact, not a defect:

| Property | Assessment |
|---|---|
| **Frozen-decision compliance** | The selection rule is exactly what the **frozen design prescribed** as the default (design doc §5 :132/:146/:156/:158; HD-1 = A2 orchestrator-side derivation; HD-4 = read-only reuse; HD-6 = configVersion-chain isolation). Phase 3 *implemented* the designed rule; it did not improvise one. |
| **Zero new contract surface** | No schema change, no new table, no new API, no new columns, no frontend change. The strategy consumed **only** pre-existing query surface (`findByFilter`) and pre-existing data fields (`result_json`, `status`, `config_version`, `scenario`). |
| **Determinism** | `ORDER BY id` is a pre-existing deterministic order; SQLite AUTOINCREMENT never reuses ids, so id-order = insertion order. Same input config → same selected row → same share. |
| **Degradation safety** | On any selection/parse failure the deriver returns `null` → step 4c INCONCLUSIVE (never a wrong PASS) — so even a degenerate multiple-DONE ambiguity cannot produce a false gate result. |
| **Seed-independence (strong safety bound)** | Every real `ALL_BUILDS` experiment runs `count` games per build via `runAllBuilds` ([ExperimentService.java:119-129](backend/src/main/java/com/aigamelab/experiment/ExperimentService.java#L119-L129)) → `buildDistribution` is 3×`count` games, share = `count/(3×count) = 1/3` **regardless of seed**. So even if multiple DONE experiments exist for a configVersion, whichever “latest” row is picked yields the **same** share — the selection tie-break is mathematically immaterial for real data. |
| **Future divergence** | If a future change makes per-build game counts unequal (weighted allocation, single-build scenario), “latest DONE” becomes a real tie-break. That is the moment a contract-level decision is required — today it is inert. |

**Conclusion: not an inherited contract, but a contract-consistent, design-mandated derived semantics that cannot corrupt gate outcomes.**

---

## 6. Audit Finding Register

| # | Severity | Finding | Disposition |
|---|---|---|---|
| CA-01 | INFO | Selection strategy is Phase-3-introduced, not pre-existing — but design-prescribed and contract-consistent | Documented here; no code change |
| CA-02 | INFO | Multiple DONE experiments per configVersion are legal (schema UNIQUE lacks status) — today immaterial because ALL_BUILDS share is seed-independent (1/3) | Documented; future work if build weighting diverges |
| CA-03 | INFO | `findByFilter` ordering (`ORDER BY id`) is relied upon but is a pre-existing stable contract; id = insertion order under AUTOINCREMENT | No change; documented in deriver Javadoc |
| CA-04 | INFO | “latest-DONE” tie-break has no explicit owner contract — the design doc's “快照语义” note is the only written basis | Design doc already records it (:88/:158); deemed sufficient for MVP |

**No blocking findings. No code/design changes required for Phase 3 closure on this point.**

---

## 7. Statement for the Closure Record

> **Proven:** The `QaBuildShareDeriver` experiment-selection strategy (“latest DONE experiment + configVersion isolation”) is **not** an existing business contract inherited from the pre-Phase-3 system. It is a **new derived semantics** introduced by Phase 3, built exclusively on pre-existing contract primitives (configVersion-scoped `findByFilter` query surface, `DONE` status domain, `scenario='ALL_BUILDS'`, deterministic `ORDER BY id`), was **prescribed as the implementation default by the frozen Phase-3 design doc** (§5; HD-1 A2 / HD-4 / HD-6), and is **mathematically inert for real data** (ALL_BUILDS share is seed-independent = 1/3), degrading safely to INCONCLUSIVE on any ambiguity. **This is the authoritative answer to the Experiment Selection Contract audit: the strategy contributes no new contract surface and requires no redesign.**

---

**QA PHASE 3 — Experiment Selection Contract Audit: PASS (NO_CONTRACT, 0 refutations). Acceptance checkpoint continues to wait on Human; no further phase auto-started.**