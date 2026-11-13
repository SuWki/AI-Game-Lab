import { useEffect, useState } from "react";
import { useQuery, useQueryClient } from "react-query";
import { api } from "@/api";
import { useGlobalSse } from "@/useGlobalSse";
import type { ConfigVersion, ChangeProposal, DiffLine, SsePayload } from "@/types";

// ─── SSE Config/Proposal 相关事件 ───────────────────────────────────────
const CONFIG_EVENTS = new Set([
  "CONFIG_VERSION_CREATED",
  "PROPOSAL_CREATED",
  "APPROVAL_REQUIRED",
  "PROPOSAL_REJECTED",
]);

/**
 * P7-5 Config & Diff（03 文档 P7-5）。
 *
 * 三栏布局：
 *  - 左：ConfigVersion 列表（最新优先，默认选中 latest）
 *  - 中：选中版本的 JSON + Diff 视图
 *  - 右：待审批 Proposal 卡片 + Approve / Reject 操作
 *
 * 复用 P7-2 SSE Hook：收到 CONFIG_VERSION_CREATED / PROPOSAL_CREATED 等事件后
 * invalidate 对应 React Query；SSE 降级时走 cache。
 * 禁止实现 rollback / activate / active 字段；ConfigVersion 只读。
 */

export function ConfigDiffPage() {
  const qc = useQueryClient();

  // SSE 驱动刷新：收到相关事件后 invalidate versions / proposals
  useGlobalSse((e: SsePayload) => {
    if (CONFIG_EVENTS.has(e.type)) {
      qc.invalidateQueries({ queryKey: ["config-versions"] });
      qc.invalidateQueries({ queryKey: ["config-proposals"] });
    }
  });

  // 查询
  const versionsQ = useQuery({ queryKey: ["config-versions"], queryFn: () => api.listConfigVersions() });
  const proposalsQ = useQuery({
    queryKey: ["config-proposals"],
    queryFn: () => api.listProposals("PENDING"),
  });

  // 本地状态
  const [selectedVersion, setSelectedVersion] = useState<string | null>(null);

  // 默认选中 latest
  useEffect(() => {
    if (selectedVersion == null && versionsQ.data && versionsQ.data.length > 0) {
      setSelectedVersion(versionsQ.data[0].version); // 后端已按最新优先排序
    }
  }, [selectedVersion, versionsQ.data]);

  const selectedCfg = versionsQ.data?.find((v) => v.version === selectedVersion) ?? null;

  return (
    <div style={{ padding: 24 }}>
      <h1 style={{ fontSize: 22, marginTop: 8 }}>AI Game Lab · Config & Diff</h1>
      <p style={{ marginTop: 4, fontSize: 12, color: "#6b7280" }}>
        SSE 实时推送（CONFIG_VERSION_CREATED / PROPOSAL_CREATED 等事件触发刷新）
      </p>

      <div style={{ display: "grid", gridTemplateColumns: "200px 1fr 320px", gap: 16, marginTop: 16, alignItems: "start" }}>
        {/* 左栏：版本列表 */}
        <VersionsPanel
          versions={versionsQ.data ?? []}
          isLoading={versionsQ.isLoading}
          selected={selectedVersion}
          onSelect={setSelectedVersion}
        />

        {/* 中栏：JSON / Diff */}
        <ConfigPanel version={selectedCfg} isLoading={versionsQ.isLoading} />

        {/* 右栏：待审批 Proposal */}
        <ProposalsPanel proposals={proposalsQ.data ?? []} isLoading={proposalsQ.isLoading} />
      </div>
    </div>
  );
}

// ─── 左栏：版本列表 ─────────────────────────────────────────────────────

function VersionsPanel({
  versions,
  isLoading,
  selected,
  onSelect,
}: {
  versions: ConfigVersion[];
  isLoading: boolean;
  selected: string | null;
  onSelect: (v: string) => void;
}) {
  return (
    <div style={{ border: "1px solid #e5e7eb", borderRadius: 8, overflow: "hidden" }}>
      <div style={{ padding: "10px 12px", borderBottom: "1px solid #e5e7eb", fontWeight: 600, fontSize: 14 }}>
        Versions
      </div>
      {isLoading && <div style={{ padding: 12, color: "#6b7280" }}>加载中…</div>}
      <ul style={{ listStyle: "none", margin: 0, padding: 0, maxHeight: "70vh", overflowY: "auto" }}>
        {versions.map((v) => (
          <li key={v.version}>
            <button
              onClick={() => onSelect(v.version)}
              style={{
                width: "100%", textAlign: "left", border: "none",
                borderBottom: "1px solid #f3f4f6", padding: "10px 12px",
                cursor: "pointer", background: selected === v.version ? "#eff6ff" : "transparent",
                display: "flex", flexDirection: "column", gap: 2,
              }}
            >
              <strong style={{ fontSize: 13, color: "#111827" }}>{v.version}</strong>
              <span style={{ fontSize: 11, color: "#6b7280" }}>
                {v.createdAt ? new Date(v.createdAt).toLocaleString() : "—"}
              </span>
              {v.comment && (
                <span style={{ fontSize: 11, color: "#9ca3af", fontStyle: "italic" }}>{v.comment}</span>
              )}
            </button>
          </li>
        ))}
      </ul>
    </div>
  );
}

// ─── 中栏：JSON 查看 + Diff ─────────────────────────────────────────────

function ConfigPanel({
  version,
  isLoading,
}: {
  version: ConfigVersion | null;
  isLoading: boolean;
}) {
  if (isLoading) {
    return (
      <div style={{ border: "1px solid #e5e7eb", borderRadius: 8, padding: 20, color: "#6b7280" }}>
        加载中…
      </div>
    );
  }
  if (!version) {
    return (
      <div style={{ border: "1px solid #e5e7eb", borderRadius: 8, padding: 20, color: "#6b7280" }}>
        从左侧选择一个版本查看配置。
      </div>
    );
  }

  let configObj: unknown = null;
  try {
    configObj = JSON.parse(version.configJson ?? "{}");
  } catch {
    // JSON 解析失败：展示原文
  }

  return (
    <div style={{ border: "1px solid #e5e7eb", borderRadius: 8, overflow: "hidden" }}>
      <div style={{ padding: "10px 12px", borderBottom: "1px solid #e5e7eb", display: "flex", justifyContent: "space-between", alignItems: "center" }}>
        <span style={{ fontWeight: 600, fontSize: 14 }}>
          {version.version} — Config
        </span>
        {version.parentVersion && (
          <span style={{ fontSize: 11, color: "#6b7280" }}>parent: {version.parentVersion}</span>
        )}
      </div>

      {/* JSON 只读展示 */}
      <pre style={{
        margin: 0, padding: 16, fontSize: 12, fontFamily: "monospace",
        background: "#f9fafb", whiteSpace: "pre-wrap", wordBreak: "break-word",
        maxHeight: "50vh", overflowY: "auto",
      }}>
        {configObj ? JSON.stringify(configObj, null, 2) : (version.configJson ?? "—")}
      </pre>
    </div>
  );
}

// ─── 右栏：待审批 Proposal ──────────────────────────────────────────────

function ProposalsPanel({
  proposals,
  isLoading,
}: {
  proposals: ChangeProposal[];
  isLoading: boolean;
}) {
  return (
    <div style={{ border: "1px solid #e5e7eb", borderRadius: 8, overflow: "hidden" }}>
      <div style={{ padding: "10px 12px", borderBottom: "1px solid #e5e7eb", fontWeight: 600, fontSize: 14 }}>
        Pending Proposals
      </div>
      {isLoading && <div style={{ padding: 12, color: "#6b7280" }}>加载中…</div>}
      {proposals.length === 0 && !isLoading && (
        <div style={{ padding: 12, color: "#6b7280" }}>无待审批提案。</div>
      )}
      <div style={{ padding: 12, display: "flex", flexDirection: "column", gap: 12 }}>
        {proposals.map((p) => (
          <ProposalCard key={p.id} proposal={p} />
        ))}
      </div>
    </div>
  );
}

// ─── 单个 Proposal 卡片 ────────────────────────────────────────────────

function ProposalCard({ proposal }: { proposal: ChangeProposal }) {
  const qc = useQueryClient();
  const [comment, setComment] = useState("");
  const [action, setAction] = useState<"approve" | "reject" | null>(null);
  const [error, setError] = useState<string | null>(null);

  const doAction = async (type: "approve" | "reject") => {
    setAction(type);
    setError(null);
    try {
      if (type === "approve") {
        await api.approveProposal(proposal.id, comment || undefined);
      } else {
        await api.rejectProposal(proposal.id, comment || undefined);
      }
      // 成功后：invalidate 两个 query
      await qc.invalidateQueries({ queryKey: ["config-proposals"] });
      await qc.invalidateQueries({ queryKey: ["config-versions"] });
    } catch (err) {
      setError(err instanceof Error ? err.message : String(err));
    } finally {
      setAction(null);
    }
  };

  const diffLines = api.parseDiff(proposal.diffJson);

  let evidenceIds: string[] = [];
  try {
    evidenceIds = JSON.parse(proposal.evidenceExperimentIds ?? "[]");
  } catch {
    // ignore
  }

  return (
    <div style={{
      border: "1px solid #e5e7eb", borderRadius: 8, padding: 12,
      background: "#fff", display: "flex", flexDirection: "column", gap: 8,
    }}>
      <div style={{ fontWeight: 600, fontSize: 13 }}>{proposal.title}</div>
      <div style={{ fontSize: 12, color: "#6b7280" }}>{proposal.reason}</div>

      <div style={{ display: "flex", gap: 8, flexWrap: "wrap", fontSize: 11 }}>
        {proposal.baseVersion && <span style={{ color: "#9ca3af" }}>base: {proposal.baseVersion}</span>}
        {proposal.targetVersion && <span style={{ color: "#9ca3af" }}>target: {proposal.targetVersion}</span>}
        {proposal.runId != null && <span style={{ color: "#9ca3af" }}>run: #{proposal.runId}</span>}
      </div>

      {evidenceIds.length > 0 && (
        <div style={{ fontSize: 11, color: "#9ca3af" }}>
          实验：{evidenceIds.map((id) => `#${id}`).join(", ")}
        </div>
      )}

      {/* Diff 行 */}
      {diffLines.length > 0 && <DiffLinesView lines={diffLines} />}

      {/* 审批操作 */}
      <div style={{ marginTop: 4 }}>
        <input
          type="text"
          placeholder="审批意见（可选）"
          value={comment}
          onChange={(e) => setComment(e.target.value)}
          style={{
            width: "100%", padding: "6px 8px", fontSize: 12, border: "1px solid #e5e7eb",
            borderRadius: 4, boxSizing: "border-box",
          }}
        />
      </div>
      <div style={{ display: "flex", gap: 8 }}>
        <button
          onClick={() => doAction("approve")}
          disabled={action != null}
          style={{
            flex: 1, padding: "6px 0", fontSize: 12, fontWeight: 600, cursor: "pointer",
            border: "1px solid #16a34a", borderRadius: 4, background: "#dcfce7", color: "#15803d",
            opacity: action != null ? 0.6 : 1,
          }}
        >
          {action === "approve" ? "审批中…" : "Approve"}
        </button>
        <button
          onClick={() => doAction("reject")}
          disabled={action != null}
          style={{
            flex: 1, padding: "6px 0", fontSize: 12, fontWeight: 600, cursor: "pointer",
            border: "1px solid #dc2626", borderRadius: 4, background: "#fee2e2", color: "#b91c1c",
            opacity: action != null ? 0.6 : 1,
          }}
        >
          {action === "reject" ? "操作中…" : "Reject"}
        </button>
      </div>
      {error && <div style={{ fontSize: 11, color: "#b91c1c" }}>{error}</div>}
    </div>
  );
}

// ─── Diff 行展示 ────────────────────────────────────────────────────────

function DiffLinesView({ lines }: { lines: DiffLine[] }) {
  return (
    <pre style={{
      margin: 0, padding: 8, fontSize: 11, fontFamily: "monospace",
      background: "#f9fafb", borderRadius: 6, whiteSpace: "pre-wrap",
      maxHeight: 200, overflowY: "auto",
    }}>
      {lines.map((l, i) => {
        const bg = l.op === "+" ? "#dcfce7" : l.op === "-" ? "#fee2e2" : "transparent";
        const prefix = l.op === "~" ? "±" : l.op;
        const label = l.op === "~"
          ? `${l.path}: ${l.before} → ${l.after}`
          : `${l.path}: ${l.op === "+" ? l.after : l.before}`;
        return (
          <div key={i} style={{ background: bg, padding: "1px 4px", borderLeft: `3px solid ${bg === "#dcfce7" ? "#16a34a" : bg === "#fee2e2" ? "#dc2626" : "transparent"}` }}>
            <span style={{ color: "#6b7280", marginRight: 4 }}>{prefix}</span>
            <span>{label}</span>
          </div>
        );
      })}
    </pre>
  );
}
