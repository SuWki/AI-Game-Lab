import "@testing-library/jest-dom";
import { describe, it, expect, beforeEach, vi } from "vitest";
import { api } from "@/api";

/**
 * QA P1-4 Closure — Findings API Compatibility：
 * 验证 Tester 与 QA 的 Finding 列表查询互不污染——
 *   - api.listFindings    → GET /api/findings（Tester 原 3 参端点，回归保障）
 *   - api.listQaFindings  → GET /api/qa/findings（QA 8 维端点，已获人工批准）
 * 两者参数独立：runId（Tester 单值）vs runIds（QA 多值 IN）。
 */

function captureFetch() {
  const calls: string[] = [];
  vi.stubGlobal("fetch", vi.fn(async (input: RequestInfo | URL) => {
    calls.push(String(input));
    return {
      ok: true,
      statusText: "OK",
      json: async () => ({ code: 0, message: "ok", data: [] }),
      text: async () => "",
    };
  }));
  return calls;
}

beforeEach(() => {
  vi.restoreAllMocks();
  vi.unstubAllGlobals();
});

describe("api.listFindings（Tester，GET /api/findings）", () => {
  it("无参 → /api/findings（无 query）", async () => {
    const calls = captureFetch();
    await api.listFindings();
    expect(calls[0]).toBe("/api/findings");
  });

  it("configVersion + severity + runId → 3 参 query", async () => {
    const calls = captureFetch();
    await api.listFindings({ configVersion: "v001", severity: "HIGH", runId: 7 });
    expect(calls[0]).toBe("/api/findings?configVersion=v001&severity=HIGH&runId=7");
  });
});

describe("api.listQaFindings（QA，GET /api/qa/findings）", () => {
  it("空过滤 → /api/qa/findings（无 query）", async () => {
    const calls = captureFetch();
    await api.listQaFindings();
    expect(calls[0]).toBe("/api/qa/findings");
  });

  it("8 维过滤 → 全维度 AND query，runIds 多值 IN", async () => {
    const calls = captureFetch();
    await api.listQaFindings({
      configVersion: "v001",
      severity: "HIGH",
      category: "CRASH",
      source: "INVARIANT",
      status: "OPEN",
      runIds: [1, 2],
      testPlanId: 5,
      taskId: 3,
    });
    const url = calls[0];
    expect(url).toContain("/api/qa/findings?");
    expect(url).toContain("configVersion=v001");
    expect(url).toContain("severity=HIGH");
    expect(url).toContain("category=CRASH");
    expect(url).toContain("source=INVARIANT");
    expect(url).toContain("status=OPEN");
    expect(url).toContain("runIds=1");
    expect(url).toContain("runIds=2");
    expect(url).toContain("testPlanId=5");
    expect(url).toContain("taskId=3");
  });

  it("两个方法互不污染：listFindings 绝不携带 QA 8 维参数", async () => {
    const calls = captureFetch();
    // 先调 QA 方法，再调 Tester 方法——Tester URL 不得出现 QA 维度参数
    await api.listQaFindings({ category: "CRASH", status: "OPEN", runIds: [1] });
    await api.listFindings({ configVersion: "v001", severity: "HIGH", runId: 7 });
    const testerUrl = calls[1];
    expect(testerUrl.startsWith("/api/findings?")).toBe(true);
    expect(testerUrl).not.toContain("category=");
    expect(testerUrl).not.toContain("status=");
    expect(testerUrl).not.toContain("runIds=");
    expect(testerUrl).not.toContain("testPlanId=");
    expect(testerUrl).not.toContain("taskId=");
  });
});
