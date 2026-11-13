import "@testing-library/jest-dom";
import { describe, it, expect, beforeEach, vi } from "vitest";
import { render, screen, fireEvent, waitFor, within } from "@testing-library/react";
import { QueryClient, QueryClientProvider } from "react-query";
import { MemoryRouter } from "react-router-dom";
import { WorkflowPage } from "@/pages/WorkflowPage";
import { api } from "@/api";
import { MockEventSource } from "@/test/mockEventSource";
import type { RunRow, TaskRow } from "@/types";

function makeWrapper() {
  const qc = new QueryClient({ defaultOptions: { queries: { retry: false } } });
  return ({ children }: { children: React.ReactNode }) => (
    <QueryClientProvider client={qc}>
      <MemoryRouter>{children}</MemoryRouter>
    </QueryClientProvider>
  );
}

const TASKS: TaskRow[] = [
  { id: 1, title: "研究任务", goal: "g", constraintsJson: '{"agent_type":"RESEARCH"}', maxSteps: 50, approvalRequired: false, status: "ACTIVE", createdAt: "2026-08-28T10:00:00" },
  { id: 2, title: "设计任务", goal: "g", constraintsJson: '{"agent_type":"DESIGNER"}', maxSteps: 50, approvalRequired: false, status: "ACTIVE", createdAt: "2026-08-28T10:00:00" },
  { id: 3, title: "测试任务", goal: "g", constraintsJson: '{"agent_type":"TESTER"}', maxSteps: 50, approvalRequired: false, status: "ACTIVE", createdAt: "2026-08-28T10:00:00" },
  { id: 4, title: "QA 回归", goal: "g", constraintsJson: '{"agent_type":"QA"}', maxSteps: 50, approvalRequired: false, status: "ACTIVE", createdAt: "2026-08-28T10:00:00" },
];

const RUNS: RunRow[] = [];

beforeEach(() => {
  MockEventSource.reset();
  vi.restoreAllMocks();
});

describe("R3-B2 WorkflowPage QA 入口", () => {
  it("Agent 类型下拉包含 QA 团队选项（与后端 AgentType 枚举一致，共 5 个人工可建类型）", async () => {
    vi.spyOn(api, "listTasks").mockResolvedValue([]);
    vi.spyOn(api, "listRuns").mockResolvedValue(RUNS);

    render(<WorkflowPage />, { wrapper: makeWrapper() });

    const select = screen.getByLabelText("Agent 类型");
    const options = Array.from((select as HTMLSelectElement).options).map((o) => o.value);
    // 后端 AgentType 枚举 8 值；人工可建 5 值（RESEARCH/DESIGNER/TESTER/QA/QA_LEAD），
    // QA_TESTER/QA_ANALYST/QA_RESEARCHER 由 QA_LEAD 编排内部创建，不在此下拉。
    // 前端单一事实来源，不得出现第二套枚举。
    expect(options).toEqual(["RESEARCH", "DESIGNER", "TESTER", "QA", "QA_LEAD"]);
  });

  it("选择 QA_LEAD 提交 → createTask payload agentType=QA_LEAD，researchSourceTaskId=null", async () => {
    vi.spyOn(api, "listTasks").mockResolvedValue([]);
    vi.spyOn(api, "listRuns").mockResolvedValue(RUNS);
    const createSpy = vi.spyOn(api, "createTask").mockResolvedValue(TASKS[3]);

    render(<WorkflowPage />, { wrapper: makeWrapper() });

    fireEvent.change(screen.getByLabelText("Agent 类型"), { target: { value: "QA_LEAD" } });
    fireEvent.change(screen.getByLabelText(/标题/), { target: { value: "QA Phase2 编排" } });
    fireEvent.change(screen.getByLabelText(/目标/), { target: { value: "多角色回归" } });
    fireEvent.click(screen.getByRole("button", { name: "创建任务" }));

    await waitFor(() => expect(createSpy).toHaveBeenCalledTimes(1));
    expect(createSpy).toHaveBeenCalledWith(
      expect.objectContaining({ title: "QA Phase2 编排", goal: "多角色回归", agentType: "QA_LEAD", researchSourceTaskId: null })
    );
  });

  it("选择 QA 提交 → createTask payload agentType=QA，researchSourceTaskId=null", async () => {
    vi.spyOn(api, "listTasks").mockResolvedValue([]);
    vi.spyOn(api, "listRuns").mockResolvedValue(RUNS);
    const createSpy = vi.spyOn(api, "createTask").mockResolvedValue(TASKS[3]);

    render(<WorkflowPage />, { wrapper: makeWrapper() });

    fireEvent.change(screen.getByLabelText("Agent 类型"), { target: { value: "QA" } });
    fireEvent.change(screen.getByLabelText(/标题/), { target: { value: "QA 全量回归" } });
    fireEvent.change(screen.getByLabelText(/目标/), { target: { value: "全量维度回归" } });
    fireEvent.click(screen.getByRole("button", { name: "创建任务" }));

    await waitFor(() => expect(createSpy).toHaveBeenCalledTimes(1));
    expect(createSpy).toHaveBeenCalledWith(
      expect.objectContaining({ title: "QA 全量回归", goal: "全量维度回归", agentType: "QA", researchSourceTaskId: null })
    );
  });

  it("RESEARCH/DESIGNER/TESTER 提交 payload 不变（既有行为回归）", async () => {
    vi.spyOn(api, "listTasks").mockResolvedValue([]);
    vi.spyOn(api, "listRuns").mockResolvedValue(RUNS);
    const createSpy = vi.spyOn(api, "createTask").mockResolvedValue(TASKS[0]);

    render(<WorkflowPage />, { wrapper: makeWrapper() });

    const select = screen.getByLabelText("Agent 类型");
    fireEvent.change(screen.getByLabelText(/标题/), { target: { value: "t" } });
    fireEvent.change(screen.getByLabelText(/目标/), { target: { value: "g" } });

    // 逐类型提交：等待上一次 submit 结束（按钮文字从"创建中…"恢复）→ 重新填写
    // title/goal（submit 成功后表单清空，按钮因此会禁用）→ 再点下一次。
    const submitWith = async (agent: string, expectedCalls: number) => {
      await waitFor(() => expect(screen.getByRole("button", { name: "创建任务" })).toBeInTheDocument());
      fireEvent.change(select, { target: { value: agent } });
      fireEvent.change(screen.getByLabelText(/标题/), { target: { value: "t" } });
      fireEvent.change(screen.getByLabelText(/目标/), { target: { value: "g" } });
      fireEvent.click(screen.getByRole("button", { name: "创建任务" }));
      await waitFor(() => expect(createSpy).toHaveBeenCalledTimes(expectedCalls));
    };

    await submitWith("RESEARCH", 1);
    expect(createSpy).toHaveBeenLastCalledWith(expect.objectContaining({ agentType: "RESEARCH", researchSourceTaskId: null }));

    await submitWith("TESTER", 2);
    expect(createSpy).toHaveBeenLastCalledWith(expect.objectContaining({ agentType: "TESTER", researchSourceTaskId: null }));

    await submitWith("DESIGNER", 3);
    expect(createSpy).toHaveBeenLastCalledWith(expect.objectContaining({ agentType: "DESIGNER", researchSourceTaskId: null }));

    expect(createSpy).toHaveBeenCalledTimes(3);
  });

  it("仅 DESIGNER 可填研究引用；QA/TESTER/RESEARCH 该输入禁用且不携带 researchSourceTaskId", async () => {
    vi.spyOn(api, "listTasks").mockResolvedValue([]);
    vi.spyOn(api, "listRuns").mockResolvedValue(RUNS);
    const createSpy = vi.spyOn(api, "createTask").mockResolvedValue(TASKS[0]);

    render(<WorkflowPage />, { wrapper: makeWrapper() });

    const researchInput = screen.getByLabelText("研究任务引用（仅 DESIGNER 可携带）");
    const select = screen.getByLabelText("Agent 类型");

    // 默认 RESEARCH：禁用
    expect(researchInput).toBeDisabled();
    // QA：禁用
    fireEvent.change(select, { target: { value: "QA" } });
    expect(researchInput).toBeDisabled();
    // DESIGNER：启用并可携带
    fireEvent.change(select, { target: { value: "DESIGNER" } });
    expect(researchInput).toBeEnabled();
    fireEvent.change(researchInput, { target: { value: "7" } });
    fireEvent.change(screen.getByLabelText(/标题/), { target: { value: "t" } });
    fireEvent.change(screen.getByLabelText(/目标/), { target: { value: "g" } });
    fireEvent.click(screen.getByRole("button", { name: "创建任务" }));

    await waitFor(() => expect(createSpy).toHaveBeenCalledWith(
      expect.objectContaining({ agentType: "DESIGNER", researchSourceTaskId: 7 })
    ));
  });

  it("QA 任务在列表中正确渲染（badge 显示 QA，不崩溃）", async () => {
    vi.spyOn(api, "listTasks").mockResolvedValue([TASKS[3]]);
    vi.spyOn(api, "listRuns").mockResolvedValue(RUNS);

    render(<WorkflowPage />, { wrapper: makeWrapper() });

    await waitFor(() => expect(screen.getByText("QA 回归")).toBeInTheDocument());
    const row = screen.getByText("QA 回归").closest("tr")!;
    expect(within(row).getByText("QA")).toBeInTheDocument();
  });

  it("未知/非法 agent type 数据安全渲染为 UNKNOWN（只读展示路径不受影响）", async () => {
    vi.spyOn(api, "listTasks").mockResolvedValue([
      { ...TASKS[0], title: "非法 Agent 数据", constraintsJson: "{}" },
    ]);
    vi.spyOn(api, "listRuns").mockResolvedValue(RUNS);

    render(<WorkflowPage />, { wrapper: makeWrapper() });

    await waitFor(() => expect(screen.getByText("非法 Agent 数据")).toBeInTheDocument());
    expect(screen.getByText("UNKNOWN")).toBeInTheDocument();
  });
});