# QA Phase 3 Implementation Report

**Date:** 2026-09-17
**Commit:** `e335bb7`
**Baseline:** Backend 628/0/0 → **653/0/0** | Frontend 57/0/0 + tsc 0 (unchanged)

---

## What Was Done

Wired `dominantBuildShare` computation into the QA Release Gate so it is no
longer always null at gate-evaluation time.

### Architecture A (Derived / Existing Data) — HD-1

**`QaBuildShareDeriver`** (new `@Service`):
- `deriveDominantBuildShare(configVersion)` → `ExperimentRepository.findByFilter(configVersion, "ALL_BUILDS")`
  → latest DONE row (ORDER BY id ASC → last) → parse `result_json.buildDistribution`
  → `max(games) / sum(games)` (HD-3, build-name agnostic).
- Every failure mode degrades to `null`, never throws: empty repo, non-DONE,
  malformed JSON, missing buildDistribution, zero total games, blank/null
  configVersion, repo exception.

**`QaOrchestratorService`** (modified):
- Constructor gains `QaBuildShareDeriver` (7th param).
- `completeOrchestration` computes share at evaluate time (HD-4, no cache /
  async / SSE / API) and passes it as the 6th arg to the 6-arg `evaluate`.

**`QaReleaseGateService`** (modified):
- 5-arg `evaluate()` delegates to 6-arg with `null` — **the 16 existing call
  sites compile untouched** (mandatory method-overload constraint).
- 6-arg `evaluate()` consumes `Double dominantBuildShare`.
- **Step 4c (HD-5)**: `if (dominantBuildShare == null) return buildResult("INCONCLUSIVE", false, 1, 0, stats)` —
  placed after 4b, before CONDITIONAL_PASS/PASS. Never PASS; hard FAIL (step 1)
  and existing evidence-gap steps (2/3/4/4b) keep their original priority —
  rule INCONCLUSIVE ≠ force whole gate INCONCLUSIVE.
- `isBuildShareImbalanced` reused unchanged: `share > 0.75` strict greater
  (HD-2, single threshold).
- `RULE_VERSION` bumped `QA-S3-MVP-20260910` → `QA-S3-20260917`.
- `GateResult` record adds `dominantBuildShare`; `statsJson` records it as
  `0.333333` (6-decimal) or `null` — never fabricated.

### Frozen Constraints Verified

| HD | Requirement | Status |
|----|-------------|--------|
| HD-1 | Architecture A, compute from existing Experiment data at gate time | ✅ deriver + orchestrator wiring |
| HD-2 | 0.75 threshold, reuse `isBuildShareImbalanced`, no second threshold | ✅ H2/H3 boundary tests |
| HD-3 | `max(games)/sum(games)`, no build-name hardcoding | ✅ D1 test (40/35/25 → 0.4) |
| HD-4 | Real-time derived at evaluate, no cache/async/SSE/API | ✅ synchronous call in completeOrchestration |
| HD-5 | Uncomputable → step 4c INCONCLUSIVE, never PASS; priority chain intact | ✅ 4c branch + H4/H5/H8 |
| HD-6 | No project_id schema; isolation via configVersion chain | ✅ D3 + O6 cross-config tests |
| HD-7 | No snapshot table, no schema modification | ✅ zero DDL changes |

---

## Test Coverage Added

### `QaBuildShareDeriverTest` — D1–D3 semantics, E1–E7 degrade (10 tests)
- D1 games-share semantics; D2 latest-DONE-wins; D3 config isolation.
- E1 empty repo, E2 non-DONE ignored, E3 malformed JSON, E4 missing
  buildDistribution, E5 zero games, E6 blank configVersion, E7 repo throws
  → all `null`.

### `QaReleaseGateServiceTest` — H1–H9 (6-arg overload)
- H1 0.80 → FAIL (imbalance), H2 0.333 → PASS, H3 0.75 exact → PASS
  (strict-greater boundary).
- H4 5-arg delegate null → INCONCLUSIVE (4c, inconclusiveCount=1),
  H5 TIMEOUT wins over null share, H8 BLOCKER FAIL wins over null share —
  priority chain intact.
- H6 share in statsJson as `0.333333`, H7 null → `"dominantBuildShare":null`.
- H9 CONDITIONAL_PASS unaffected by 4c placement.

### `QaPhase2OrchestrationTest` — O1–O6 end-to-end (+ cleanup fix)
- `clean()` now deletes `experiment` before `run` (FK order — removes
  cross-test contamination); Test F seeds a balanced experiment.
- O1 balanced experiment → PASS + share≈0.333; O2 imbalanced → FAIL + share≈0.818;
  O3 no experiment → INCONCLUSIVE + null; O4 RUNNING experiment → INCONCLUSIVE;
  O5 latest DONE wins; O6 cross-config isolation (v001-o6b PASS, v001-o6 FAIL).

---

## Regression Results

```
Backend  mvn -o clean test   → Tests run: 653, Failures: 0, Errors: 0, Skipped: 0  (BUILD SUCCESS)
Frontend npx tsc --noEmit     → 0 errors
Frontend npm test             → 9 files, 57/57 passed
```

**Δ = +25 backend tests** (628 → 653). One interim failure during the run was a
test-authoring error in the deriver test (`90.0/110.0` miswritten as `0.9`);
fixed to `90.0 / 110.0`, full suite green.

---

## Deliverables

| File | Change |
|------|--------|
| `backend/.../qa/QaBuildShareDeriver.java` | new — share derivation (HD-1/3/4) |
| `backend/.../qa/QaReleaseGateService.java` | 5-arg→6-arg overload, 4c branch, RULE_VERSION bump |
| `backend/.../qa/QaOrchestratorService.java` | deriver injection + evaluate-time share |
| `backend/.../qa/QaReleaseGateServiceTest.java` | H1–H9 group |
| `backend/.../qa/QaBuildShareDeriverTest.java` | new — D1–D3, E1–E7 |
| `backend/.../qa/QaPhase2OrchestrationTest.java` | O1–O6 + FK-order cleanup |
| `docs/QA_PHASE3_ARCHITECTURE_DESIGN.md` | design record (frozen HD-1..7 annotated) |

---

## Findings & Observations

### FAIL 分支数学上休眠但接线正确

`ExperimentService.runAllBuilds`（:119-129）对 3 个 build（crit / penetration / attack_speed）
各串行跑 `count` 局，统一喂入一个 `Aggregator`。因此**所有真实 ALL_BUILDS 实验的 build share
恒为 1/3**（3 × count 局，每个 build 恰 count 局），`isBuildShareImbalanced(1/3) = false`，
step 1 的 share 失衡分支在真实数据下**数学上不可触发**。

这不是实现缺陷——它是 `ExperimentService.runAllBuilds` 均匀分配局数这一既有语义的直接推论。
一旦实验分布偏离均匀（例如未来按 build 加权局数、或场景为单 build），该分支即生效。

### "实验先行"从弱提示升级为 Gate 硬前置

QA Phase 2 时 share 恒 null，"实验先行"仅是文档层面弱提示。HD-5 将其升级为
`step 4c INCONCLUSIVE` —— 无 ALL_BUILDS 实验 → share=null → never PASS。
QA Lead 必须先完成 `runExperiment(scenario=ALL_BUILDS)` 并等待 DONE，Gate 才可能产出
PASS / CONDITIONAL_PASS。这使 QA 流程与实验运行形成更强前置依赖。

### experiment 行全仓共享（HD-6 gap）

`experiment` 表无 `project_id` 列（schema.sql 16 表均无），跨 lead-task 共享。
当前全仓单 project 现状下不构成破坏；若未来实现 Project Isolation（R7-P4 候选），
实验查询须按 project 作用域收敛。本轮按 HD-6/HD-7 不引入 project_id，仅如实文档化。

### 平行 Session 协调

本实现涉及两个并行 Session 协调（同 R8 先例，commit `813e33b` 吸收并行变更）。
平行 Session 完成 A2 架构骨架（QaBuildShareDeriver + orchestrator 接线 + 6-arg evaluate），
本 Session 发现其 HD-5 解读（"null=skip，Gate 可 PASS"）与冻结决策冲突，完成 step 4c
INCONCLUSIVE 语义修正，修正测试矩阵（H4/H7/O3/O4/F），并产出本报告补充内容。

**QA PHASE 3 COMPLETE — WAITING FOR HUMAN ACCEPTANCE. No further phase is
auto-started; commit `e335bb7` is the acceptance checkpoint.**
