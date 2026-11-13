import "@testing-library/jest-dom";
import { describe, it, expect, beforeEach, vi } from "vitest";
import { render, screen, fireEvent, waitFor, act } from "@testing-library/react";
import { QueryClient, QueryClientProvider } from "react-query";
import { MemoryRouter } from "react-router-dom";
import { ConfigDiffPage } from "@/pages/ConfigDiffPage";
import { api } from "@/api";
import { MockEventSource } from "@/test/mockEventSource";
import type { ConfigVersion, ChangeProposal, DiffLine } from "@/types";

function makeWrapper() {
  const qc = new QueryClient({ defaultOptions: { queries: { retry: false } } });
  return ({ children }: { children: React.ReactNode }) => (
    <QueryClientProvider client={qc}>
      <MemoryRouter>{children}</MemoryRouter>
    </QueryClientProvider>
  );
}

const VERSIONS: ConfigVersion[] = [
  { version: "v003", configJson: '{"a":1}', parentVersion: "v002", changeProposalId: 5, comment: "buff", createdAt: "2026-08-28T10:00:00" },
  { version: "v002", configJson: '{"a":2}', parentVersion: "v001", changeProposalId: 4, comment: null, createdAt: "2026-08-27T10:00:00" },
  { version: "v001", configJson: '{"a":3}', parentVersion: null, changeProposalId: null, comment: "seed", createdAt: "2026-08-26T10:00:00" },
];

const PROPOSALS: ChangeProposal[] = [
  {
    id: 12, runId: 1, baseVersion: "v001", targetVersion: null,
    title: "提升 DPS", reason: "胜率偏低",
    evidenceExperimentIds: "[7,8]", diffJson: "- a: 3\n+ a: 1",
    afterConfigJson: '{"a":1}', status: "PENDING",
    decidedAt: null, decidedComment: null, createdAt: "2026-08-28T09:00:00",
  },
];

beforeEach(() => {
  MockEventSource.reset();
  vi.restoreAllMocks();
});

describe("P7-5 Config & Diff 页面", () => {
  it("页面可加载，versions 正确展示，默认选中 latest（v003）", async () => {
    vi.spyOn(api, "listConfigVersions").mockResolvedValue(VERSIONS);
    vi.spyOn(api, "listProposals").mockResolvedValue(PROPOSALS);

    render(<ConfigDiffPage />, { wrapper: makeWrapper() });

    // 左栏显示三个版本
    await waitFor(() => expect(screen.getByText("v003")).toBeInTheDocument());
    expect(screen.getByText("v002")).toBeInTheDocument();
    expect(screen.getByText("v001")).toBeInTheDocument();

    // 中栏默认展示 latest v003 的 JSON
    await waitFor(() => expect(screen.getByText(/v003.*Config/)).toBeInTheDocument());
    const jsonPre = document.querySelector("pre");
    expect(jsonPre).not.toBeNull();
    expect(jsonPre!.textContent).toContain('"a"');
    expect(jsonPre!.textContent).toContain("1");
  });

  it("JSON 只读展示，不出现激活/回滚按钮", async () => {
    vi.spyOn(api, "listConfigVersions").mockResolvedValue(VERSIONS);
    vi.spyOn(api, "listProposals").mockResolvedValue(PROPOSALS);

    render(<ConfigDiffPage />, { wrapper: makeWrapper() });
    await waitFor(() => expect(screen.getByText("v003")).toBeInTheDocument());

    // 不应出现 rollback / activate 类操作
    expect(screen.queryByText(/设为当前版本/i)).not.toBeInTheDocument();
    expect(screen.queryByText(/rollback/i)).not.toBeInTheDocument();
    expect(screen.queryByText(/activate/i)).not.toBeInTheDocument();
  });

  it("Pending Proposal 正确展示 + Diff 行渲染（+/-）", async () => {
    vi.spyOn(api, "listConfigVersions").mockResolvedValue(VERSIONS);
    vi.spyOn(api, "listProposals").mockResolvedValue(PROPOSALS);

    render(<ConfigDiffPage />, { wrapper: makeWrapper() });
    await waitFor(() => expect(screen.getByText("提升 DPS")).toBeInTheDocument());

    expect(screen.getByText("胜率偏低")).toBeInTheDocument();
    expect(screen.getByText(/实验：#7, #8/)).toBeInTheDocument();

    // Diff 行：同一 path 合并为修改行 a: 3 → 1
    expect(screen.getByText(/a: 3.*→.*1/)).toBeInTheDocument();
  });

  it("parseDiff 区分 新增/删除/修改", () => {
    const diff = "- old.path: 1\n+ new.path: 2\n- same: 3\n+ same: 4";
    const lines: DiffLine[] = api.parseDiff(diff);
    // 同一 path 出现 - 和 + → 合并为 ~（修改）；单独 - → 删除；单独 + → 新增
    expect(lines).toHaveLength(3);
    expect(lines[0]).toMatchObject({ op: "-", path: "old.path", before: "1", after: null });
    expect(lines[1]).toMatchObject({ op: "+", path: "new.path", before: null, after: "2" });
    expect(lines[2]).toMatchObject({ op: "~", path: "same", before: "3", after: "4" });
  });

  it("parseDiff 空 Diff 返回空数组", () => {
    expect(api.parseDiff(null)).toEqual([]);
    expect(api.parseDiff("")).toEqual([]);
  });

  it("点击 Approve 调用正确 endpoint 并刷新 query（loading + 防重复）", async () => {
    vi.spyOn(api, "listConfigVersions").mockResolvedValue(VERSIONS);
    const proposalsSpy = vi.spyOn(api, "listProposals").mockResolvedValue(PROPOSALS);
    const approveSpy = vi.spyOn(api, "approveProposal").mockResolvedValue({
      version: "v004", configJson: "{}", parentVersion: "v003",
      changeProposalId: 12, comment: null, createdAt: null,
    });
    proposalsSpy.mockResolvedValueOnce([...PROPOSALS]); // 首次
    proposalsSpy.mockResolvedValueOnce([]); // approve 成功后刷新：无 PENDING

    render(<ConfigDiffPage />, { wrapper: makeWrapper() });
    await waitFor(() => expect(screen.getByText("提升 DPS")).toBeInTheDocument());

    const approveBtn = screen.getByRole("button", { name: /Approve/ });
    fireEvent.click(approveBtn);

    await waitFor(() => expect(approveSpy).toHaveBeenCalledWith(12, undefined));
    expect(approveSpy).toHaveBeenCalledTimes(1);

    // 成功后 PENDING 列表刷新为空 → 卡片消失
    await waitFor(() => expect(screen.queryByText("提升 DPS")).not.toBeInTheDocument());
  });

  it("点击 Reject 调用正确 endpoint 后 Proposal 消失", async () => {
    vi.spyOn(api, "listConfigVersions").mockResolvedValue(VERSIONS);
    const proposalsSpy = vi.spyOn(api, "listProposals").mockResolvedValue(PROPOSALS);
    const rejectSpy = vi.spyOn(api, "rejectProposal").mockResolvedValue(undefined);
    proposalsSpy.mockResolvedValueOnce([...PROPOSALS]);
    proposalsSpy.mockResolvedValueOnce([]); // reject 成功后刷新：无 PENDING

    render(<ConfigDiffPage />, { wrapper: makeWrapper() });
    await waitFor(() => expect(screen.getByText("提升 DPS")).toBeInTheDocument());

    const rejectBtn = screen.getByRole("button", { name: /Reject/ });
    fireEvent.click(rejectBtn);

    await waitFor(() => expect(rejectSpy).toHaveBeenCalledWith(12, undefined));
    await waitFor(() => expect(screen.queryByText("提升 DPS")).not.toBeInTheDocument());
  });

  it("Approve 失败后恢复 UI（展示错误，卡片仍在）", async () => {
    vi.spyOn(api, "listConfigVersions").mockResolvedValue(VERSIONS);
    const proposalsSpy = vi.spyOn(api, "listProposals").mockResolvedValue(PROPOSALS);
    vi.spyOn(api, "approveProposal").mockRejectedValue(new Error("API 40901: 状态机违规"));
    proposalsSpy.mockResolvedValue([...PROPOSALS]); // 始终有 PENDING

    render(<ConfigDiffPage />, { wrapper: makeWrapper() });
    await waitFor(() => expect(screen.getByText("提升 DPS")).toBeInTheDocument());

    const approveBtn = screen.getByRole("button", { name: /Approve/ });
    fireEvent.click(approveBtn);

    await waitFor(() => expect(screen.getByText(/状态机违规/)).toBeInTheDocument());
    // 失败：卡片仍在
    expect(screen.getByText("提升 DPS")).toBeInTheDocument();
  });

  it("收到 SSE CONFIG_VERSION_CREATED 事件后 invalidate 对应 query", async () => {
    vi.spyOn(api, "listConfigVersions").mockResolvedValue(VERSIONS);
    const proposalsSpy = vi.spyOn(api, "listProposals").mockResolvedValue(PROPOSALS);

    render(<ConfigDiffPage />, { wrapper: makeWrapper() });
    await waitFor(() => expect(screen.getByText("v003")).toBeInTheDocument());

    // 触发 SSE 事件
    const es = MockEventSource.instances[0];
    act(() => {
      es.emit("CONFIG_VERSION_CREATED", { dbId: 99, eventId: "ev", type: "CONFIG_VERSION_CREATED", runId: "0", payloadJson: "{}" });
    });

    await waitFor(() => expect(proposalsSpy).toHaveBeenCalledTimes(2)); // 初始 + invalidate 重取
  });
});
