import "@testing-library/jest-dom";
import { describe, it, expect, beforeEach, vi } from "vitest";
import { render, screen, fireEvent, waitFor } from "@testing-library/react";
import { QueryClient, QueryClientProvider } from "react-query";
import { MemoryRouter, Routes, Route } from "react-router-dom";
import { ResearchPage } from "@/pages/ResearchPage";
import { api } from "@/api";
import { MockEventSource } from "@/test/mockEventSource";
import type { RunRow, ResearchSource, ResearchClaim, ResearchReport } from "@/types";

// ResearchPage 使用 useGlobalSse（依赖 useQueryClient）→ 必须包 QueryClientProvider。
function makeWrapper(initialEntries = ["/research"]) {
  const qc = new QueryClient({ defaultOptions: { queries: { retry: false } } });
  return {
    qc,
    wrapper: ({ children }: { children: React.ReactNode }) => (
      <QueryClientProvider client={qc}>
        <MemoryRouter initialEntries={initialEntries}>{children}</MemoryRouter>
      </QueryClientProvider>
    ),
  };
}

/** 带 /workflow 兜底路由的 Router（验证"以该研究发起 Designer"导航目标）。 */
function RouterHarness({ children }: { children: React.ReactNode }) {
  return (
    <MemoryRouter initialEntries={["/research"]}>
      <Routes>
        <Route path="/research" element={children} />
        <Route path="/workflow" element={<div>WORKFLOW_PAGE</div>} />
      </Routes>
    </MemoryRouter>
  );
}

const RUNS: RunRow[] = [
  {
    id: 1, taskId: 2, status: "COMPLETED", promptVersionId: 1, model: "gpt-4o",
    currentStep: 3, maxSteps: 5, tokenBudget: 1000, tokensUsed: 100,
    errorMessage: null, finalResult: null,
    startedAt: "2026-09-01T10:00:00", finishedAt: "2026-09-01T10:05:00", createdAt: "2026-09-01T10:00:00",
  },
];

const SOURCES: ResearchSource[] = [
  {
    id: 1, runId: 1, taskId: 2, url: "https://example.com/official",
    sourceType: "Official", title: "官方数值公告", contentSummary: "数值摘要",
    reliability: 0.9, fetchedAt: "2026-09-01T10:01:00", createdAt: "2026-09-01T10:01:00",
  },
];

const CLAIMS: ResearchClaim[] = [
  {
    id: 1, runId: 1, taskId: 2, claim: "当前版本英雄 A 过强", confidence: 0.8,
    supportingSourceIds: "[1]", contradictingSourceIds: "[]",
    status: "VALIDATED", createdAt: "2026-09-01T10:02:00",
  },
];

const REPORTS: ResearchReport[] = [
  {
    id: 1, runId: 1, taskId: 2, title: "版本研究报告", contentMd: "# 摘要",
    createdAt: "2026-09-01T10:03:00",
  },
];

/** 全 API mock 一次配齐（listRuns + 研究三表）。 */
function stubApi(over: Partial<{
  runs: RunRow[];
  sources: ResearchSource[];
  claims: ResearchClaim[];
  reports: ResearchReport[];
  sourcesError: boolean;
}> = {}) {
  const runs = over.runs ?? RUNS;
  const sources = over.sources ?? SOURCES;
  const claims = over.claims ?? CLAIMS;
  const reports = over.reports ?? REPORTS;
  vi.spyOn(api, "listRuns").mockResolvedValue(runs);
  if (over.sourcesError) {
    vi.spyOn(api, "listResearchSources").mockRejectedValue(new Error("boom"));
  } else {
    vi.spyOn(api, "listResearchSources").mockResolvedValue(sources);
  }
  vi.spyOn(api, "listResearchClaims").mockResolvedValue(claims);
  vi.spyOn(api, "listResearchReports").mockResolvedValue(reports);
}

beforeEach(() => {
  MockEventSource.reset();
  vi.restoreAllMocks();
});

describe("R3-C2 ResearchPage 核心页 Smoke（6 点契约）", () => {
  it("1) 页面渲染：h1 Research + 三 Tab + Run 选择器存在", async () => {
    stubApi();
    render(<ResearchPage />, { wrapper: makeWrapper().wrapper });

    expect(screen.getByRole("heading", { name: "Research" })).toBeInTheDocument();
    expect(screen.getByText(/Sources/)).toBeInTheDocument();
    expect(screen.getByText(/Claims/)).toBeInTheDocument();
    expect(screen.getByText(/Reports/)).toBeInTheDocument();
    expect(screen.getByText("研究 Run：")).toBeInTheDocument();
  });

  it("2) 初始 API 交互：listRuns + 最近 Run 的研究三表各请求一次", async () => {
    const runsSpy = vi.spyOn(api, "listRuns").mockResolvedValue(RUNS);
    const sourcesSpy = vi.spyOn(api, "listResearchSources").mockResolvedValue(SOURCES);
    const claimsSpy = vi.spyOn(api, "listResearchClaims").mockResolvedValue(CLAIMS);
    const reportsSpy = vi.spyOn(api, "listResearchReports").mockResolvedValue(REPORTS);

    render(<ResearchPage />, { wrapper: makeWrapper().wrapper });

    await waitFor(() => expect(runsSpy).toHaveBeenCalledTimes(1));
    // 默认选中最近一个 Run（id=1）→ 三表带 runId 拉取
    await waitFor(() => expect(sourcesSpy).toHaveBeenCalledWith(1));
    await waitFor(() => expect(claimsSpy).toHaveBeenCalledWith(1));
    await waitFor(() => expect(reportsSpy).toHaveBeenCalledWith(1));
  });

  it("3) happy-path：Sources 表格渲染研究来源（标题/类型/URL/Run）", async () => {
    stubApi();
    render(<ResearchPage />, { wrapper: makeWrapper().wrapper });

    await waitFor(() => expect(screen.getByText("官方数值公告")).toBeInTheDocument());
    expect(screen.getByText("Official")).toBeInTheDocument();
    expect(screen.getByText("0.90")).toBeInTheDocument();
    expect(screen.getByText("https://example.com/official")).toBeInTheDocument();
    expect(screen.getByText("#1")).toBeInTheDocument(); // Run 链接
  });

  it("4) empty state：无来源 → 表格显示空态文案", async () => {
    stubApi({ sources: [] });
    render(<ResearchPage />, { wrapper: makeWrapper().wrapper });

    await waitFor(() => expect(screen.getByText("无研究来源")).toBeInTheDocument());
  });

  it("5) error state：研究来源接口失败 → 加载失败文案，不静默", async () => {
    stubApi({ sourcesError: true });
    render(<ResearchPage />, { wrapper: makeWrapper().wrapper });

    await waitFor(() =>
      expect(screen.getByText(/加载失败（网络\/服务错误），点击「刷新」重试/)).toBeInTheDocument()
    );
  });

  it("6) 交互：Tab 切换渲染 Claims/Reports + 「以该研究发起 Designer」导航到 /workflow", async () => {
    const qc = new QueryClient({ defaultOptions: { queries: { retry: false } } });
    const harness = ({ children }: { children: React.ReactNode }) => (
      <QueryClientProvider client={qc}>
        <RouterHarness>{children}</RouterHarness>
      </QueryClientProvider>
    );

    stubApi();
    render(<ResearchPage />, { wrapper: harness });

    // 默认 Sources Tab
    await waitFor(() => expect(screen.getByText("官方数值公告")).toBeInTheDocument());

    // 切到 Claims → 断言渲染
    fireEvent.click(screen.getByText(/^Claims/));
    await waitFor(() => expect(screen.getByText("当前版本英雄 A 过强")).toBeInTheDocument());
    expect(screen.getByText("VALIDATED")).toBeInTheDocument();

    // 切到 Reports → 断言渲染
    fireEvent.click(screen.getByText(/^Reports/));
    await waitFor(() => expect(screen.getByText("版本研究报告")).toBeInTheDocument());

    // 回 Sources → 「以该研究发起 Designer」导航到 /workflow?researchSourceTaskId=2
    fireEvent.click(screen.getByText(/^Sources/));
    const designerBtn = screen.getByRole("button", { name: "以该研究发起 Designer" });
    expect(designerBtn).toBeEnabled(); // 已选中 Run → 可发起
    fireEvent.click(designerBtn);
    await waitFor(() => expect(screen.getByText("WORKFLOW_PAGE")).toBeInTheDocument());
  });

  it("6b) 交互：无 Run 时「以该研究发起 Designer」禁用（不可导航）", async () => {
    stubApi({ runs: [] });
    render(<ResearchPage />, { wrapper: makeWrapper().wrapper });

    await waitFor(() =>
      expect(screen.getByText(/请选择研究 Run/)).toBeInTheDocument()
    );
    expect(screen.getByRole("button", { name: "以该研究发起 Designer" })).toBeDisabled();
  });
});
