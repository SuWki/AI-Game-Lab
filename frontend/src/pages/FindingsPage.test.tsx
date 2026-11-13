import "@testing-library/jest-dom";
import { describe, it, expect, beforeEach, vi } from "vitest";
import { render, screen, fireEvent, waitFor } from "@testing-library/react";
import { QueryClient, QueryClientProvider } from "react-query";
import { MemoryRouter } from "react-router-dom";
import { FindingsPage } from "@/pages/FindingsPage";
import type { Finding } from "@/types";

function makeWrapper() {
  const qc = new QueryClient({ defaultOptions: { queries: { retry: false } } });
  return ({ children }: { children: React.ReactNode }) => (
    <QueryClientProvider client={qc}>
      <MemoryRouter>{children}</MemoryRouter>
    </QueryClientProvider>
  );
}

const FINDING: Finding = {
  id: 1,
  runId: 7,
  testPlanId: null,
  taskId: 3,
  scenarioId: "sc-1",
  configVersion: "v001",
  severity: "HIGH",
  category: "CRASH",
  source: "INVARIANT",
  title: "hp 越界",
  expected: "hp>0",
  actual: "hp=-3",
  reproSteps: "seed=1 → start",
  evidenceJson: "{\"sessionId\":\"s1\"}",
  reproductionRate: 1,
  status: "OPEN",
  createdAt: "2026-09-01T10:00:00",
};

/** 捕获 fetch 调用 URL + 返回指定 findings 列表。 */
function stubFetch(findings: Finding[] = []) {
  const calls: string[] = [];
  vi.stubGlobal("fetch", vi.fn(async (input: RequestInfo | URL) => {
    calls.push(String(input));
    return {
      ok: true,
      statusText: "OK",
      json: async () => ({ code: 0, message: "ok", data: findings }),
      text: async () => "",
    };
  }));
  return calls;
}

function stubFetchError(code: number, message: string) {
  const calls: string[] = [];
  vi.stubGlobal("fetch", vi.fn(async (input: RequestInfo | URL) => {
    calls.push(String(input));
    return {
      ok: true,
      statusText: "OK",
      json: async () => ({ code, message, data: null }),
      text: async () => "",
    };
  }));
  return calls;
}

beforeEach(() => {
  vi.restoreAllMocks();
  vi.unstubAllGlobals();
});

describe("FindingsPage QA 集成（P1-4）", () => {
  it("首屏请求走已批准的 GET /api/qa/findings", async () => {
    const calls = stubFetch([FINDING]);
    render(<FindingsPage />, { wrapper: makeWrapper() });

    await waitFor(() => expect(calls.length).toBeGreaterThan(0));
    expect(calls[0]).toContain("/api/qa/findings");
    // 空过滤：无 query 参数
    expect(calls[0]).not.toContain("?");
  });

  it("filter 正确转换为 query parameters（configVersion + severity）", async () => {
    const calls = stubFetch([FINDING]);
    render(<FindingsPage />, { wrapper: makeWrapper() });

    fireEvent.change(screen.getByPlaceholderText("configVersion"), {
      target: { value: "v001" },
    });
    fireEvent.change(screen.getByDisplayValue("全部 severity"), {
      target: { value: "HIGH" },
    });

    await waitFor(() => {
      const last = calls[calls.length - 1];
      expect(last).toContain("/api/qa/findings?");
      expect(last).toContain("configVersion=v001");
      expect(last).toContain("severity=HIGH");
    });
  });

  it("多过滤条件保留（AND 全维度同传）", async () => {
    const calls = stubFetch([FINDING]);
    render(<FindingsPage />, { wrapper: makeWrapper() });

    fireEvent.change(screen.getByDisplayValue("全部 category"), {
      target: { value: "CRASH" },
    });
    fireEvent.change(screen.getByDisplayValue("全部 source"), {
      target: { value: "INVARIANT" },
    });
    fireEvent.change(screen.getByDisplayValue("全部 status"), {
      target: { value: "OPEN" },
    });
    fireEvent.change(screen.getByPlaceholderText("Run IDs（逗号分隔）"), {
      target: { value: "1,2" },
    });
    fireEvent.change(screen.getByPlaceholderText("testPlanId"), {
      target: { value: "5" },
    });
    fireEvent.change(screen.getByPlaceholderText("taskId"), {
      target: { value: "3" },
    });

    await waitFor(() => {
      const last = calls[calls.length - 1];
      expect(last).toContain("category=CRASH");
      expect(last).toContain("source=INVARIANT");
      expect(last).toContain("status=OPEN");
      expect(last).toContain("runIds=1");
      expect(last).toContain("runIds=2"); // 多值 IN 语义
      expect(last).toContain("testPlanId=5");
      expect(last).toContain("taskId=3");
    });
  });

  it("列表渲染既有 Finding 表格 + 详情抽屉", async () => {
    stubFetch([FINDING]);
    render(<FindingsPage />, { wrapper: makeWrapper() });

    await waitFor(() => expect(screen.getByText("hp 越界")).toBeInTheDocument());
    fireEvent.click(screen.getByText("hp 越界"));
    expect(screen.getByText("Expected")).toBeInTheDocument();
    expect(screen.getByText("hp>0")).toBeInTheDocument();
    expect(screen.getByText("Actual")).toBeInTheDocument();
    expect(screen.getByText("hp=-3")).toBeInTheDocument();
  });

  it("empty state：无 Finding 时显示空态", async () => {
    stubFetch([]);
    render(<FindingsPage />, { wrapper: makeWrapper() });

    await waitFor(() =>
      expect(screen.getByText(/无 Finding/)).toBeInTheDocument()
    );
  });

  it("error state：后端错误码包络显示加载失败，不静默", async () => {
    stubFetchError(40001, "severity 非法");
    render(<FindingsPage />, { wrapper: makeWrapper() });

    await waitFor(() =>
      expect(screen.getByText(/加载失败/)).toBeInTheDocument()
    );
  });
});
