import "@testing-library/jest-dom";
import { describe, it, expect, beforeEach, vi } from "vitest";
import { render, screen, waitFor } from "@testing-library/react";
import { QueryClient, QueryClientProvider } from "react-query";
import { MemoryRouter, Route, Routes } from "react-router-dom";
import { TestPlanPage } from "@/pages/TestPlanPage";
import type { Finding } from "@/types";

/**
 * QA P1-5 — Tester 前端隔离回归。
 *
 * TestPlanPage 是纯 Tester 场景页面。P1-4 Closure 已确认：
 *   - `api.listFindings()`（无参）→ GET /api/findings（Tester 3 参端点）
 *   - 关联 Findings 组件（AssociatedFindings）客户端按 testPlanId 过滤
 *
 * 本文件证明 Tester 前端绝不调用 /api/qa/findings（QA 端点），
 * 即 P1-5 §12 前端隔离边界。
 */

function makeWrapper() {
  const qc = new QueryClient({ defaultOptions: { queries: { retry: false } } });
  return ({ children }: { children: React.ReactNode }) => (
    <QueryClientProvider client={qc}>
      <MemoryRouter initialEntries={["/test-plans/1"]}>
        <Routes>
          <Route path="/test-plans/:id" element={children} />
        </Routes>
      </MemoryRouter>
    </QueryClientProvider>
  );
}

const PLAN = {
  id: 1,
  taskId: 7,
  configVersion: "v001",
  status: "ACTIVE" as const,
  scenariosJson: JSON.stringify([{ id: "sc-1", type: "BALANCE", goal: "验证护甲曲线", seed: 42, repeat: 1, playerProfile: "test", invariants: ["hp>=0"], overrides: {} }]),
  createdAt: "2026-09-01T10:00:00",
};

function stubFetch(findings: Finding[] = []) {
  const calls: string[] = [];
  vi.stubGlobal("fetch", vi.fn(async (input: RequestInfo | URL) => {
    const url = String(input);
    calls.push(url);
    let data: unknown = [];
    if (url.startsWith("/api/test-plans/")) {
      data = PLAN;
    } else if (url.startsWith("/api/findings")) {
      data = findings;
    }
    return {
      ok: true,
      statusText: "OK",
      json: async () => ({ code: 0, message: "ok", data }),
      text: async () => "",
    };
  }));
  return calls;
}

beforeEach(() => {
  vi.restoreAllMocks();
  vi.unstubAllGlobals();
});

describe("TestPlanPage Tester 端点隔离（P1-5）", () => {
  it("关联 Findings 走 GET /api/findings（Tester 端点），绝不调用 /api/qa/findings", async () => {
    const calls = stubFetch();
    render(<TestPlanPage />, { wrapper: makeWrapper() });

    // 等 Findings 请求完成：空 findings → 空态文案
    await screen.findByText(/暂无 Finding 与此 TestPlan 关联/);

    const findingCalls = calls.filter((c) => c.includes("/api/findings"));
    expect(findingCalls.length).toBeGreaterThan(0);
    findingCalls.forEach((c) => expect(c).not.toContain("/api/qa/findings"));
  });

  it("页面正确渲染 Tester TestPlan 元信息 + scenarios", async () => {
    stubFetch();
    render(<TestPlanPage />, { wrapper: makeWrapper() });

    await screen.findByText("Test Plan #1");
    expect(screen.getByText("v001")).toBeInTheDocument();
    expect(screen.getByText("ACTIVE")).toBeInTheDocument();
    expect(screen.getByText(/Scenarios（1）/)).toBeInTheDocument();
    expect(screen.getByText("验证护甲曲线")).toBeInTheDocument();
  });

  it("有 testPlanId 匹配的 Finding 时渲染关联表格（客户端过滤）", async () => {
    const matched: Finding = {
      id: 1,
      runId: 7,
      testPlanId: 1,
      taskId: 7,
      scenarioId: "sc-1",
      configVersion: "v001",
      severity: "HIGH",
      category: "CRASH",
      source: "INVARIANT",
      title: "hp 越界",
      expected: "hp>0",
      actual: "hp=-3",
      reproSteps: "seed=42 → start",
      evidenceJson: "{\"sessionId\":\"s1\"}",
      reproductionRate: 1,
      status: "OPEN",
      createdAt: "2026-09-01T10:00:00",
    };
    // testPlanId=999 的 Finding 不应出现在本 TestPlan(1) 下
    const other: Finding = { ...matched, id: 2, testPlanId: 999, title: "其他 TestPlan 的 Finding" };
    stubFetch([matched, other]);
    render(<TestPlanPage />, { wrapper: makeWrapper() });

    await waitFor(() => expect(screen.getByText("hp 越界")).toBeInTheDocument());
    // 客户端按 testPlanId 过滤：只显示匹配本 TestPlan 的记录
    expect(screen.queryByText("其他 TestPlan 的 Finding")).not.toBeInTheDocument();
  });
});