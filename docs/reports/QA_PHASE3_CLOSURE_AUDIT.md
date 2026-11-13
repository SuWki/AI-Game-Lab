# QA Phase 3 — Closure Audit

**Date:** 2026-09-22
**Auditor:** Claude Code (independent closure pass)
**Audited commits:** `e335bb7` → `3291be5` → `f2b2d2c` → `8445328`
**Baseline HEAD:** `8445328` (Experiment Selection Contract Audit)
**Working tree:** clean
**Method:** read-only verification — direct reads of every Phase 3 production file, every Phase 3 test file, design/report/audit docs, `git diff` against the Phase 2 boundary `813e33b`, and two live regression runs (backend `mvn clean test`, frontend `tsc + vitest + build`).
**Scope:** closure audit only. **No code change, no redesign, no fix, no new feature.**

---

## 1. Verdict

> **QA PHASE 3 CLOSURE AUDIT — PASS (16/16 dimensions).**
> Every frozen decision (HD-1..7), every element of the dominantBuildShare chain, the experiment-selection contract proof, gate step 4c semantics and priority ordering, regression tests, frozen-file boundary, schema boundary, git ownership, and documentation consistency all verify clean against the audited commits. No blocking findings. No next phase auto-started.

---

## 2. Audit Target Recap

QA Phase 3 wired `dominantBuildShare` computation into the QA Release Gate (Architecture A / Derived / Existing Data). The deliverable chain:

```
experiment.result_json.buildDistribution
  → ExperimentRepository.findByFilter(configVersion, "ALL_BUILDS")   [ORDER BY id]
  → QaBuildShareDeriver.deriveDominantBuildShare                      [last DONE]
  → computeShare: max(games)/Σ(games)                                 [HD-3]
  → QaOrchestratorService.completeOrchestration                       [HD-4, evaluate-time]
  → QaReleaseGateService.evaluate 6-arg overload                      [step 4c HD-5]
  → GateResult.dominantBuildShare → gateRepo.decide                    [persist dominant_build_share]
```

---

## 3. HD-1..7 Cross-Validation (design doc × code × tests)

| HD | Frozen decision | Code evidence | Test evidence | Result |
|----|----------------|---------------|---------------|--------|
| **HD-1** | Architecture A (Derived / Existing Data, 0 new tables); implementation = A2 (orchestrator computes, gate stays pure) | `QaBuildShareDeriver` (new `@Service`); injected as 7th ctor param into [QaOrchestratorService.java:98-109](backend/src/main/java/com/aigamelab/qa/QaOrchestratorService.java#L98-L109); share computed at [QaOrchestratorService.java:251](backend/src/main/java/com/aigamelab/qa/QaOrchestratorService.java#L251) then passed as 6th arg to 6-arg `evaluate` | `QaPhase2OrchestrationTest` O1–O6 (e2e); `QaBuildShareDeriverTest` D1–D3 | ✅ |
| **HD-2** | 0.75 threshold retained; `isBuildShareImbalanced` unchanged (strict `>`); not configurable | [QaReleaseGateService.java:60](backend/src/main/java/com/aigamelab/qa/QaReleaseGateService.java#L60) `DOMINANT_BUILD_IMBALANCE_THRESHOLD = 0.75`; [QaReleaseGateService.java:200-202](backend/src/main/java/com/aigamelab/qa/QaReleaseGateService.java#L200-L202) `share != null && share > 0.75` | H1 (0.80 → FAIL), H2 (0.333 → PASS), H3 (0.75 exact → PASS, strict-greater boundary) | ✅ |
| **HD-3** | games-share semantics: `max(games)/Σ(games)`, build-name agnostic | [QaBuildShareDeriver.java:75-98](backend/src/main/java/com/aigamelab/qa/QaBuildShareDeriver.java#L75-L98) — iterates `buildDistribution` values by `games` only, no build name referenced | D1 (40/35/25 → 0.4) | ✅ |
| **HD-4** | Real-time derived at evaluate time; no cache/async/SSE/API | synchronous call [QaOrchestratorService.java:251](backend/src/main/java/com/aigamelab/qa/QaOrchestratorService.java#L251); deriver is stateless, no cache field | O1–O6 compute fresh per `completeOrchestration` | ✅ |
| **HD-5** | Missing share → step 4c **INCONCLUSIVE** (never PASS); aggregation priority unchanged | [QaReleaseGateService.java:151-153](backend/src/main/java/com/aigamelab/qa/QaReleaseGateService.java#L151-L153) `if (dominantBuildShare == null) return buildResult("INCONCLUSIVE", false, 1, 0, stats)` placed after 4b, before CONDITIONAL_PASS/PASS | H4 (5-arg null → INCONCLUSIVE, count=1), H7 (`"dominantBuildShare":null` in statsJson), O3 (e2e no experiment → INCONCLUSIVE) | ✅ |
| **HD-6** | No project_id introduced this round; isolation via configVersion chain | no `project_id` anywhere in schema (16 tables) or Phase 3 code; deriver scopes by `configVersion` equality | D3 (v001/v002 isolation), O6 (cross-config gate isolation) | ✅ |
| **HD-7** | No snapshot table; gate persistence unchanged | `git diff 813e33b..HEAD -- schema.sql` = **empty**; `qa_release_gate.dominant_build_share` column pre-existed Phase 3 | zero-DDL verified (see §7) | ✅ |

**Result: 7/7 HDs cross-validate clean (design doc ↔ code ↔ tests).**

---

## 4. dominantBuildShare Complete Chain Trace

| # | Layer | Evidence | Result |
|---|-------|----------|--------|
| 1 | Source | `experiment.result_json.buildDistribution` (SchemaResult; `Aggregator` accumulates games/wins) | present pre-Phase-3 |
| 2 | Query | [ExperimentRepositoryImpl.java:85-98](backend/src/main/java/com/aigamelab/repository/ExperimentRepositoryImpl.java#L85-L98) `findByFilter`: `WHERE 1=1 [AND config_version=?] [AND scenario=?] ORDER BY id` — deterministic, id-asc | present pre-Phase-3, **unchanged** |
| 3 | Derivation | [QaBuildShareDeriver.java:52-73](backend/src/main/java/com/aigamelab/qa/QaBuildShareDeriver.java#L52-L73) — blank/null config → null; repo exception → null; iterate id-asc keep last `DONE`; no DONE or blank result_json → null; [computeShare:75-98](backend/src/main/java/com/aigamelab/qa/QaBuildShareDeriver.java#L75-L98) parse-fail → null, totalGames≤0 → null | Phase 3 new |
| 4 | Evaluate-time wiring | [QaOrchestratorService.java:251](backend/src/main/java/com/aigamelab/qa/QaOrchestratorService.java#L251) `Double dominantBuildShare = buildShareDeriver.deriveDominantBuildShare(configVersion)` → 6-arg `evaluate` | Phase 3 |
| 5 | Gate | [QaReleaseGateService.java:106-167](backend/src/main/java/com/aigamelab/qa/QaReleaseGateService.java#L106-L167) 6-arg evaluate consumes share; step 1 FAIL uses `isBuildShareImbalanced`; step 4c null → INCONCLUSIVE | Phase 3 |
| 6 | Persistence | [QaOrchestratorService.java:264-269](backend/src/main/java/com/aigamelab/qa/QaOrchestratorService.java#L264-L269) `gateRepo.decide(..., result.dominantBuildShare(), ...)` → `qa_release_gate.dominant_build_share REAL` (pre-existing column, schema.sql) | column pre-existing; write now real |
| 7 | API/Frontend | `QaController` returns `QaReleaseGate` record as-is (field serialized); frontend consumes no gate field (Phase 2 §16 frozen: no gate page) | zero change |

**E2E proof (one integration run covers steps 1→6):**
- O1: balanced experiment (100/100/100) → `PASS` + `dominantBuildShare=100/300≈0.333` persisted + `statsJson` contains `"dominantBuildShare":0.333333`
- O2: imbalanced experiment (90/10/10) → `FAIL` + `0.818182` persisted
- O3: no experiment → `INCONCLUSIVE` + `null` persisted + `"dominantBuildShare":null`
- O4: RUNNING (non-DONE) experiment → treated as missing → `INCONCLUSIVE` + null
- O5: same config, two DONE experiments → latest DONE (id-asc last) wins
- O6: cross-config isolation — imbalanced `v001-o6` does not pollute `v001-o6b`

**Result: chain complete end-to-end with real persistence + real JSON payloads.**

---

## 5. Experiment Selection Contract (proof cross-check)

Prior audit deliverable `QA_PHASE3_EXPERIMENT_SELECTION_CONTRACT_AUDIT.md` (commit `8445328`) proved the "latest DONE + configVersion isolation" selection is **NO_CONTRACT** — a Phase-3-introduced derived semantics built exclusively on pre-existing primitives, not an inherited business contract.

Closure re-verification (spot-checks performed independently here):
- Schema `UNIQUE(config_version, seed, simulation_count, scenario)` permits multiple DONE per configVersion (schema.sql:83) — no "one latest per config" constraint.
- `findByFilter` is query surface only (`ORDER BY id`, no LIMIT, no status filter).
- Design doc [QA_PHASE3_ARCHITECTURE_DESIGN.md:88](docs/QA_PHASE3_ARCHITECTURE_DESIGN.md#L88) explicitly flags the time-window as "需设计决策" and records "取最后一行为最新" as the chosen default (:132/:146/:156).
- The only "latest" arithmetic that pre-exists in the codebase operates on runs-per-task / config-version entities, never on experiments by recency+status.

**Result: contract audit conclusion (NO_CONTRACT) remains valid and consistent with the code as it stands at HEAD. No refutation survived; no code impact.**

---

## 6. Gate Step 4c Priority Ordering Proof

Actual code path ([QaReleaseGateService.java:118-167](backend/src/main/java/com/aigamelab/qa/QaReleaseGateService.java#L118-L167)):

| Step | Condition | Result | Line |
|------|-----------|--------|------|
| 1 | critical>0 ∨ high≥3 ∨ softLock>0 ∨ `isBuildShareImbalanced(share)` | **FAIL** | :119-123 |
| 2 | any TIMEOUT | INCONCLUSIVE (red line) | :126-129 |
| 3 | any FAILED/CANCELLED | INCONCLUSIVE | :132-135 |
| 4 | missing role count > 0 | INCONCLUSIVE | :138-140 |
| 4b | key role (EXPLOIT/UX) not COMPLETED | INCONCLUSIVE | :143-145 |
| **4c** | **`dominantBuildShare == null`** | **INCONCLUSIVE** (count=1) | **:151-153** |
| 5 | mediumBugs > 0 + lead CONDITIONAL_PASS | CONDITIONAL_PASS | :156-162 |
| 6 | all thresholds pass + key roles complete | PASS | :164-166 |

**Priority chain fully preserved:** FAIL (step 1) > TIMEOUT (step 2) > FAILED/CANCELLED (step 3) > missing role (step 4) > key role incomplete (step 4b) > **share=null (4c)** > CONDITIONAL_PASS > PASS.

Dedicated ordering tests: **H5** (TIMEOUT wins over null share → INCONCLUSIVE, not FAIL/PASS), **H8** (BLOCKER FAIL wins over null share → FAIL), **H9** (CONDITIONAL_PASS still reachable with balanced share — 4c placement does not shadow it). E2E: O3 (clean tree + null share → INCONCLUSIVE).

**Result: 4c is correctly ordered; no evidence-gap or FAIL path was shadowed.**

---

## 7. Real-Data 1/3 Behavior (seed-independence bound)

[ExperimentService.java:119-129](backend/src/main/java/com/aigamelab/experiment/ExperimentService.java#L119-L129) `runAllBuilds`: for each of the 3 builds (`crit`, `penetration`, `attack_speed`) it runs exactly `count` games into one shared `Aggregator` (capacity `count*3`). Therefore every real `ALL_BUILDS` experiment has `buildDistribution` = 3×`count` games with each build contributing exactly `count` → share = `count/(3·count) = 1/3`, **regardless of seed**.

- This is a direct consequence of pre-existing uniform allocation semantics, **not** a hardcode in the deriver (deriver computes from whatever `games` are present).
- Consequence: the step-1 imbalance FAIL branch (`> 0.75`) is mathematically dormant for real data today, but correctly wired (O2 proves it fires for a non-uniform distribution).
- Implication for the contract audit: even with multiple DONE experiments per config, whichever "latest" row is picked yields the same 1/3 — the selection tie-break is immaterial for real data.

**Result: 1/3 behavior confirmed; FAIL branch latent-but-wired; no correctness risk.**

---

## 8. Gate Priority + Phase 3 Test Coverage Inventory

`QaReleaseGateServiceTest` (28 tests): A1–A3 (PASS), B1–B4 (FAIL: critical / high≥3 / below-threshold / soft-lock), C1 (TIMEOUT red line), D1–D4 (FAILED / missing role / key role not complete / R6 closure red line), E1–E2 (CONDITIONAL_PASS with/without lead signal), F1–F2 (consistency + normalization), G1 (statsJson), **H1–H9** (Phase 3: threshold boundaries, 4c, priority intact, statsJson null/6-decimal, delegate).

`QaBuildShareDeriverTest` (10 tests): D1–D3 (semantics/latest-DONE/isolation), **E1–E7** (all failure modes → null: empty repo, non-DONE, malformed JSON, missing buildDistribution, zero games, blank config, repo throws).

`QaPhase2OrchestrationTest` (O1–O6 added; F seeded balanced experiment; `clean()` FK-order fix `experiment` before `run`): end-to-end PASS/FAIL/INCONCLUSIVE/isolation.

**Every priority step has ≥1 dedicated test; step 4c has 4 (H4, H7, H8-contact, O3).**

---

## 9. HD-5 Special Audit (no "null = skip" reintroduction)

The implementation report records that a parallel session's A2 skeleton initially read HD-5 as "null = skip, gate may PASS", which conflicts with the frozen decision. This closure audit confirms the correction is complete and no "null = skip" path survives:

- 5-arg `evaluate()` (the 16 pre-existing call sites) delegates to 6-arg with `null` → step 4c fires → INCONCLUSIVE, never PASS ([QaReleaseGateService.java:90-93](backend/src/main/java/com/aigamelab/qa/QaReleaseGateService.java#L90-L93)).
- Integration test F explicitly seeds a balanced experiment so the PASS path requires share evidence — proving 4c is not bypassable in the happy path.
- `statsJson` records `null` (not a fabricated number) — auditable.
- `GateResult.dominantBuildShare` flows through unchanged; `isBuildShareImbalanced(null)` still returns `false` (step 1 cannot misfire into FAIL because of a null share).

**Result: HD-5 (missing share never PASS) is airtight; "null = skip" is fully extinguished.**

---

## 10. Frozen 8 Zero-Diff

`git diff 813e33b..HEAD` over the 8 frozen files = **EMPTY**:

```
AgentLoop / RunStateMachine / BudgetGuard / LoopDetector
ToolExecutor / ToolResult / ErrorCodes / AgentEventType
```

No Phase 3 commit touches any frozen contract file.

---

## 11. Schema Boundary

- `schema.sql` still declares **16 tables**; `git diff 813e33b..HEAD -- schema.sql` = **EMPTY** (zero DDL in Phase 3).
- `qa_release_gate.dominant_build_share REAL` and `experiment.result_json` both pre-exist; HD-7 (no snapshot table) holds.

---

## 12. Experiment Ownership Boundary (future concern)

`experiment` rows are global-shared: no `project_id` column anywhere, so experiments are visible across lead-tasks/config-chains. Under the current single-project repository this is not a defect (HD-6), but it is a **documented future gap** (R7-P4 Project Isolation candidate) — flagged in both the design doc (§3.3, HD-6) and the implementation report. **Deferred, not fixed; no schema drift introduced.**

---

## 13. Regression Runs (live, 2026-09-22)

| Suite | Result |
|-------|--------|
| Backend `mvn clean test` | **653 run / 0 failures / 0 errors / 0 skipped — BUILD SUCCESS** |
| Frontend `npx tsc --noEmit` | 0 errors |
| Frontend `vitest run` | **9 files, 57/57 passed** |
| Frontend `npm run build` | vite production build ✓ |

Phase 3 delta: **628 → 653 (+25 backend tests)**; frontend count unchanged at 57.

---

## 14. Git Ownership & Baseline Chronology

```
e7e18cd R3-A1 git baseline
...
813e33b QA Phase 2 Implementation (converge/harden)     ← Phase 2 boundary
388503a QA Phase 2 report backfill                       ← Phase 2
8746217 R8 doc/infra housekeeping                        ← R8 (pre-Phase-3)
3b8763f R8 QA report                                     ← R8
e335bb7 QA Phase 3: wire dominantBuildShare (Arch A)     ← Phase 3 impl
3291be5 QA Phase 3: implementation report
f2b2d2c QA Phase 3: reconcile design doc + supplement report
8445328 QA Phase 3: Experiment Selection Contract Audit   ← HEAD
```

- Phase 3 = exactly 4 commits (`e335bb7`, `3291be5`, `f2b2d2c`, `8445328`).
- `813e33b` remains a **Phase 2** commit (its message and file set are Phase 2 runtime hardening; frozen/schema diffs between `813e33b` and HEAD are empty, so Phase 3 introduced no regressions into Phase 2 surfaces).
- Baseline progression: **R8 = 620 → Phase 2 hardening = 628 → Phase 3 = 653** (each verified by its own report + memory record).

---

## 15. Documentation Consistency

| Doc | Consistency check | Result |
|-----|-------------------|--------|
| `QA_PHASE3_ARCHITECTURE_DESIGN.md` | HD-1..7 frozen table matches implemented code (deriver/orchestrator/6-arg/4c/RULE_VERSION); selection default "取 ORDER BY id 最末行" matches deriver loop; :88 records "需设计决策" (consistent with NO_CONTRACT) | ✅ |
| `QA_PHASE3_IMPLEMENTATION_REPORT.md` | deliverables table matches the 3 production files + 3 test files; RULE_VERSION bump `QA-S3-MVP-20260910 → QA-S3-20260917` matches code line 54; 628→653 delta matches live run | ✅ |
| `QA_PHASE3_EXPERIMENT_SELECTION_CONTRACT_AUDIT.md` | NO_CONTRACT verdict (5/5 channels, 0 refutations) unchanged; §5 consequences table consistent with code state at HEAD | ✅ |
| Docs ↔ schema/code | No doc claims a table, column, API, or frozen change that does not exist; no code behavior contradicts a doc | ✅ |

**Result: 4 Phase 3 docs mutually consistent and consistent with the tree.**

---

## 16. Final Closure Matrix

| # | Dimension | Verdict |
|---|-----------|---------|
| 1 | HD-1 Architecture A / A2 wiring | **PASS** |
| 2 | HD-2 0.75 threshold (strict >, single threshold) | **PASS** |
| 3 | HD-3 games-share semantics (max/Σ, build-name agnostic) | **PASS** |
| 4 | HD-4 evaluate-time real-time derived (no cache/async/SSE/API) | **PASS** |
| 5 | HD-5 missing share → 4c INCONCLUSIVE, never PASS | **PASS** |
| 6 | HD-6 configVersion-chain isolation (no project_id) | **PASS** |
| 7 | HD-7 no snapshot table / zero DDL | **PASS** |
| 8 | dominantBuildShare chain (source→persist→API) complete | **PASS** |
| 9 | Experiment selection contract proof (NO_CONTRACT) valid | **PASS** |
| 10 | Gate step 4c ordering (FAIL>TIMEOUT>FAILED>4>4b>4c>COND>PASS) | **PASS** |
| 11 | Priority chain unshadowed (H5/H8/H9/O3) | **PASS** |
| 12 | Real-data 1/3 seed-independence (dormant FAIL, correctly wired) | **PASS** |
| 13 | Test coverage (D/E/H/O + gate matrix) — no gap per step | **PASS** |
| 14 | Frozen 8 zero-diff | **PASS** |
| 15 | Schema 16 tables zero-DDL; ownership gap documented only | **PASS** |
| 16 | Git ownership (4 Phase 3 commits; 813e33b stays Phase 2) | **PASS** |
| — | Backend regression 653/0/0 | **PASS** |
| — | Frontend regression 57/0/0 + tsc 0 + build ✓ | **PASS** |
| — | Documentation consistency (4 docs) | **PASS** |

**No blocking findings. Zero code changes required.**

---

## 17. Audit Findings Register

| # | Severity | Finding | Disposition |
|---|----------|---------|-------------|
| CLA-01 | INFO | FAIL imbalance branch mathematically dormant for real data (uniform 1/3 allocation) — latent by design, proven wired by O2 | Documented (impl report §Findings); no change |
| CLA-02 | INFO | experiment rows global-shared (no project_id) — future R7-P4 concern | Documented (design §3.3/HD-6, impl report); deferred |
| CLA-03 | INFO | 0.75 threshold business basis remains UNKNOWN (design §3.4) — Human-callable for QA-S3 calibration | Pre-existing; recorded, not changed |

---

## 18. Statement for the Record

> **Audited at HEAD `8445328` (working tree clean). All seven frozen Human Decisions (HD-1..7) are implemented exactly as frozen and cross-validated across design doc, production code, and tests. The dominantBuildShare chain is complete from `experiment.result_json` to the persisted gate row, proven end-to-end by O1–O6 with real JSON payloads. Gate step 4c (HD-5) is correctly placed in the priority chain (FAIL > TIMEOUT > FAILED/CANCELLED > missing role > key-role incomplete > 4c > CONDITIONAL_PASS > PASS), with dedicated ordering tests. The experiment-selection contract audit (NO_CONTRACT) holds. The 8 frozen files and the 16-table schema are byte-identical to the Phase 2 boundary. Regression: backend 653/0/0, frontend 57/0/0 + tsc 0 + production build ✓. Git ownership is clean: exactly four Phase 3 commits, with `813e33b` remaining a Phase 2 commit. Documentation is consistent across all four Phase 3 docs. No redesign, no fix, no new feature was introduced by this audit.**

---

**QA PHASE 3 CLOSURE AUDIT — COMPLETE: PASS (16/16). WAITING FOR HUMAN ACCEPTANCE. No further phase auto-started.**
