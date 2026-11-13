import { useQuery, useQueryClient } from "react-query";
import { useCallback, useState } from "react";
import { useNavigate } from "react-router-dom";
import { api } from "@/api";
import type { ResearchClaim, ResearchReport, ResearchSource, RunRow } from "@/types";
import { Badge } from "@/components/Badge";
import { Drawer } from "@/components/Drawer";
import { DefinitionList } from "@/components/DefinitionList";
import { useGlobalSse } from "@/useGlobalSse";

type Tab = "sources" | "claims" | "reports";

const RUN_TONES: Record<string, "ok" | "warn" | "danger" | "default" | "info"> = {
  COMPLETED: "ok",
  RUNNING: "info",
  FAILED: "danger",
  ERROR: "danger",
};

const CLAIM_TONES: Record<string, "ok" | "warn" | "danger" | "default"> = {
  VALIDATED: "ok",
  DRAFT: "warn",
  DISPUTED: "danger",
};

const SOURCE_TONES: Record<string, "info" | "default"> = {
  Official: "info",
  Developer: "info",
  Academic: "info",
};

/** Research 事件判定：仅研究域事件触发刷新。 */
function isResearchEvent(type: string): boolean {
  return (
    type === "SOURCE_FETCHED" ||
    type === "CLAIM_EXTRACTED" ||
    type === "CLAIM_VALIDATED" ||
    type === "RESEARCH_REPORT_CREATED"
  );
}

export function ResearchPage() {
  const qc = useQueryClient();
  const navigate = useNavigate();
  const [tab, setTab] = useState<Tab>("sources");
  const [runId, setRunId] = useState<number | null>(null);
  const [selectedSource, setSelectedSource] = useState<number | null>(null);
  const [selectedReport, setSelectedReport] = useState<number | null>(null);
  const [selectedClaim, setSelectedClaim] = useState<number | null>(null);

  // 全部 Run（供研究 Run 选择）
  const { data: runs = [] } = useQuery({
    queryKey: ["runs"],
    queryFn: () => api.listRuns(),
  });

  // 默认选中最近一个 Run（便于直接观测研究产出）
  const effectiveRunId = runId ?? runs[runs.length - 1]?.id ?? null;
  const effectiveRun = runs.find((r) => r.id === effectiveRunId) ?? null;

  // R2-2：研究三表刷新统一入口（前缀匹配 ["research-*", runId] 各变体）。
  // react-query v3 以数组前缀匹配，["research-sources"] 能命中 ["research-sources", runId]；
  // 不能用字面量 ["research-"]（不匹配任何已注册 key）。
  const invalidateResearch = useCallback(() => {
    qc.invalidateQueries({ queryKey: ["research-sources"] });
    qc.invalidateQueries({ queryKey: ["research-claims"] });
    qc.invalidateQueries({ queryKey: ["research-reports"] });
  }, [qc]);

  // R1-3：SSE 研究事件驱动刷新（复用 P7-2 useGlobalSse；只创建一条全局连接）
  // TE：捕获 SSE 连接状态（connected/degraded），断线连续失败时自动降级为轮询
  const { connected, degraded } = useGlobalSse((e) => {
    if (isResearchEvent(e.type)) {
      invalidateResearch();
    }
  });

  // TE：SSE 降级 → refetchInterval 轮询兜底；SSE 恢复 → 停止轮询（undefined 关闭）。
  const pollMs = degraded ? 5000 : undefined;

  // 研究数据：首屏 REST + SSE 实时增量（复用既有 useSseDrivenRefresh）
  // TE：degraded → refetchInterval 轮询兜底；error → 表格展示错误态
  const { data: sources = [], isLoading: sourcesLoading, isError: sourcesError } = useQuery({
    queryKey: ["research-sources", effectiveRunId],
    queryFn: () => api.listResearchSources(effectiveRunId ?? undefined),
    enabled: effectiveRunId != null,
    refetchInterval: pollMs,
  });
  const { data: claims = [], isLoading: claimsLoading, isError: claimsError } = useQuery({
    queryKey: ["research-claims", effectiveRunId],
    queryFn: () => api.listResearchClaims(effectiveRunId ?? undefined),
    enabled: effectiveRunId != null,
    refetchInterval: pollMs,
  });
  const { data: reports = [], isLoading: reportsLoading, isError: reportsError } = useQuery({
    queryKey: ["research-reports", effectiveRunId],
    queryFn: () => api.listResearchReports(effectiveRunId ?? undefined),
    enabled: effectiveRunId != null,
    refetchInterval: pollMs,
  });

  const selectedSourceRow = sources.find((s) => s.id === selectedSource) ?? null;
  const selectedReportRow = reports.find((r) => r.id === selectedReport) ?? null;

  return (
    <div style={{ padding: 24 }}>
      <h1 style={{ fontSize: 22, marginTop: 8 }}>Research</h1>
      <p style={{ fontSize: 13, color: "#6b7280", marginTop: 4 }}>
        Research Agent 研究产出（来源 / 断言 / 报告）——R1-2 API + R1-3 页面
      </p>

      {/* Run 选择 */}
      <div style={{ display: "flex", gap: 12, alignItems: "center", margin: "12px 0 16px", flexWrap: "wrap" }}>
        <label style={{ fontSize: 13, color: "#374151" }}>研究 Run：</label>
        <select
          value={effectiveRunId ?? ""}
          onChange={(e) => setRunId(e.target.value ? Number(e.target.value) : null)}
          style={inputStyle}
        >
          {runs.length === 0 && <option value="">（暂无 Run）</option>}
          {runs.map((r: RunRow) => (
            <option key={r.id} value={r.id}>
              #{r.id} · {r.status} · model={r.model}
            </option>
          ))}
        </select>
        {effectiveRun && (
          <Badge tone={RUN_TONES[effectiveRun.status] ?? "default"}>{effectiveRun.status}</Badge>
        )}
        <button onClick={invalidateResearch} style={btnStyle}>
          刷新
        </button>
        <button
          disabled={effectiveRun == null}
          onClick={() =>
            navigate(
              `/workflow?researchSourceTaskId=${effectiveRun?.taskId ?? ""}`,
            )
          }
          style={{
            ...btnStyle,
            background: "#1e40af",
            color: "#fff",
            borderColor: "#1e40af",
          }}
        >
          以该研究发起 Designer
        </button>
        <span style={{ fontSize: 12, color: "#9ca3af" }}>
          {degraded ? (
            <span style={{ color: "#b45309" }}>SSE 连接降级 → 每 5s 轮询兜底</span>
          ) : connected ? (
            "SSE 实时事件（SOURCE_FETCHED / CLAIM_EXTRACTED / CLAIM_VALIDATED / RESEARCH_REPORT_CREATED）"
          ) : (
            "SSE 连接建立中…"
          )}
        </span>
      </div>

      {/* Tab */}
      <div style={{ display: "flex", gap: 8, marginBottom: 16 }}>
        {(["sources", "claims", "reports"] as Tab[]).map((t) => (
          <button
            key={t}
            onClick={() => setTab(t)}
            style={{
              ...btnStyle,
              background: tab === t ? "#1e40af" : "#fff",
              color: tab === t ? "#fff" : "#374151",
              fontWeight: 600,
            }}
          >
            {t === "sources" ? `Sources (${sources.length})` : t === "claims" ? `Claims (${claims.length})` : `Reports (${reports.length})`}
          </button>
        ))}
      </div>

      {effectiveRunId == null ? (
        <p style={{ color: "#9ca3af" }}>请选择研究 Run（或创建 RESEARCH 类型 Task 启动 Run 后回来自动加载）</p>
      ) : (
        <>
          {tab === "sources" && (
            <SourcesTable
              sources={sources}
              loading={sourcesLoading}
              error={sourcesError}
              onSelect={setSelectedSource}
            />
          )}
          {tab === "claims" && (
            <ClaimsTable
              claims={claims}
              loading={claimsLoading}
              error={claimsError}
              onSelect={setSelectedClaim}
            />
          )}
          {tab === "reports" && (
            <ReportsTable
              reports={reports}
              loading={reportsLoading}
              error={reportsError}
              onSelect={setSelectedReport}
            />
          )}
        </>
      )}

      {/* 详情抽屉：Source */}
      <Drawer
        open={!!selectedSourceRow}
        title={`Source #${selectedSourceRow?.id ?? ""}`}
        onClose={() => setSelectedSource(null)}
      >
        {selectedSourceRow && <SourceDetail s={selectedSourceRow} />}
      </Drawer>

      {/* TD：详情抽屉：Claim 证据链（支持/反驳来源 drill-down） */}
      <Drawer
        open={selectedClaim != null}
        title={`Claim #${selectedClaim ?? ""} 证据链`}
        onClose={() => setSelectedClaim(null)}
      >
        <ClaimDetail claimId={selectedClaim} claims={claims} />
      </Drawer>

      {/* 详情抽屉：Report */}
      <Drawer
        open={!!selectedReportRow}
        title={`Report #${selectedReportRow?.id ?? ""}`}
        onClose={() => setSelectedReport(null)}
      >
        {selectedReportRow && <ReportDetail r={selectedReportRow} />}
      </Drawer>
    </div>
  );
}

// ==================== Sources ====================

function SourcesTable({
  sources,
  loading,
  error,
  onSelect,
}: {
  sources: ResearchSource[];
  loading: boolean;
  error?: boolean;
  onSelect: (id: number) => void;
}) {
  return (
    <table style={{ width: "100%", borderCollapse: "collapse", fontSize: 13 }}>
      <thead>
        <tr style={{ textAlign: "left", borderBottom: "2px solid #e5e7eb" }}>
          <Th>ID</Th>
          <Th>标题</Th>
          <Th>类型</Th>
          <Th>可靠性</Th>
          <Th>URL</Th>
          <Th>Run</Th>
        </tr>
      </thead>
      <tbody>
        {sources.map((s) => (
          <tr
            key={s.id}
            onClick={() => onSelect(s.id)}
            style={{ cursor: "pointer", borderBottom: "1px solid #f3f4f6" }}
          >
            <Td>{s.id}</Td>
            <Td>{s.title}</Td>
            <Td>
              <Badge tone={SOURCE_TONES[s.sourceType] ?? "default"}>{s.sourceType}</Badge>
            </Td>
            <Td>{s.reliability != null ? s.reliability.toFixed(2) : "—"}</Td>
            <Td>
              <a href={s.url} target="_blank" rel="noreferrer" style={{ color: "#1e40af" }}>
                {s.url}
              </a>
            </Td>
            <Td>
              <a href={`/runs/${s.runId}`}>#{s.runId}</a>
            </Td>
          </tr>
        ))}
        {sources.length === 0 && !loading && (
          <tr>
            <Td colSpan={6} style={{ padding: 24, textAlign: "center", color: "#9ca3af" }}>
              {error ? "加载失败（网络/服务错误），点击「刷新」重试" : "无研究来源"}
            </Td>
          </tr>
        )}
      </tbody>
    </table>
  );
}

function SourceDetail({ s }: { s: ResearchSource }) {
  return (
    <div style={{ display: "flex", flexDirection: "column", gap: 16 }}>
      <DefinitionList
        items={[
          ["标题", s.title],
          ["类型", <Badge key="t" tone={SOURCE_TONES[s.sourceType] ?? "default"}>{s.sourceType}</Badge>],
          ["可靠性", s.reliability != null ? s.reliability.toFixed(2) : "—"],
          ["Run", <a key="run" href={`/runs/${s.runId}`}>#{s.runId}</a>],
          ["Task", s.taskId != null ? `#${s.taskId}` : "—"],
          ["URL", <a key="url" href={s.url} target="_blank" rel="noreferrer">{s.url}</a>],
          ["抓取时间", s.fetchedAt ?? "—"],
        ]}
      />
      <Section title="内容摘要">{s.contentSummary ?? "—"}</Section>
    </div>
  );
}

// ==================== Claims ====================

function ClaimsTable({
  claims,
  loading,
  error,
  onSelect,
}: {
  claims: ResearchClaim[];
  loading: boolean;
  error?: boolean;
  onSelect: (id: number) => void;
}) {
  return (
    <table style={{ width: "100%", borderCollapse: "collapse", fontSize: 13 }}>
      <thead>
        <tr style={{ textAlign: "left", borderBottom: "2px solid #e5e7eb" }}>
          <Th>ID</Th>
          <Th>断言</Th>
          <Th>置信度</Th>
          <Th>状态</Th>
          <Th>支持来源</Th>
          <Th>反对来源</Th>
        </tr>
      </thead>
      <tbody>
        {claims.map((c) => (
          <tr
            key={c.id}
            onClick={() => onSelect(c.id)}
            style={{ cursor: "pointer", borderBottom: "1px solid #f3f4f6" }}
            title="点击查看证据链（支持/反驳来源）"
          >
            <Td>{c.id}</Td>
            <Td>{c.claim}</Td>
            <Td>{c.confidence.toFixed(2)}</Td>
            <Td>
              <Badge tone={CLAIM_TONES[c.status] ?? "default"}>{c.status}</Badge>
            </Td>
            <Td>{countJsonArray(c.supportingSourceIds)}</Td>
            <Td>{countJsonArray(c.contradictingSourceIds)}</Td>
          </tr>
        ))}
        {claims.length === 0 && !loading && (
          <tr>
            <Td colSpan={6} style={{ padding: 24, textAlign: "center", color: "#9ca3af" }}>
              {error ? "加载失败（网络/服务错误），点击「刷新」重试" : "无研究断言"}
            </Td>
          </tr>
        )}
      </tbody>
    </table>
  );
}

/** TD：Claim 证据链详情（支持/反驳来源列表，来自 GET /claims/{id}/sources）。 */
function ClaimDetail({
  claimId,
  claims,
}: {
  claimId: number | null;
  claims: ResearchClaim[];
}) {
  const claim = claims.find((c) => c.id === claimId) ?? null;
  const { data: sources, isLoading } = useQuery({
    queryKey: ["research-claim-sources", claimId],
    queryFn: () => api.getClaimSources(claimId as number),
    enabled: claimId != null,
  });
  if (claimId == null) return null;

  return (
    <div style={{ display: "flex", flexDirection: "column", gap: 16 }}>
      {claim && (
        <DefinitionList
          items={[
            ["断言", claim.claim],
            ["置信度", claim.confidence.toFixed(2)],
            ["状态", <Badge key="s" tone={CLAIM_TONES[claim.status] ?? "default"}>{claim.status}</Badge>],
            ["Task", `#${claim.taskId}`],
          ]}
        />
      )}
      {isLoading ? (
        <p style={{ color: "#9ca3af", fontSize: 13 }}>加载证据链…</p>
      ) : (
        <>
          <Section title={`支持来源（${sources?.supporting.length ?? 0}）`}>
            <SourceMiniList sources={sources?.supporting ?? []} />
          </Section>
          <Section title={`反驳来源（${sources?.contradicting.length ?? 0}）`}>
            <SourceMiniList sources={sources?.contradicting ?? []} />
          </Section>
        </>
      )}
    </div>
  );
}

function SourceMiniList({ sources }: { sources: ResearchSource[] }) {
  if (sources.length === 0) {
    return <p style={{ color: "#9ca3af", fontSize: 13 }}>无</p>;
  }
  return (
    <div style={{ display: "flex", flexDirection: "column", gap: 8 }}>
      {sources.map((s) => (
        <div
          key={s.id}
          style={{
            border: "1px solid #e5e7eb",
            borderRadius: 6,
            padding: "8px 10px",
            fontSize: 12,
          }}
        >
          <div style={{ fontWeight: 600, marginBottom: 4 }}>
            #{s.id} · {s.title}
            <span style={{ marginLeft: 8 }}>
              <Badge tone={SOURCE_TONES[s.sourceType] ?? "default"}>{s.sourceType}</Badge>
            </span>
          </div>
          <div>
            <a href={s.url} target="_blank" rel="noreferrer" style={{ color: "#1e40af" }}>
              {s.url}
            </a>
            {s.reliability != null && (
              <span style={{ marginLeft: 8, color: "#6b7280" }}>
                可靠性 {s.reliability.toFixed(2)}
              </span>
            )}
          </div>
        </div>
      ))}
    </div>
  );
}

// ==================== Reports ====================

function ReportsTable({
  reports,
  loading,
  error,
  onSelect,
}: {
  reports: ResearchReport[];
  loading: boolean;
  error?: boolean;
  onSelect: (id: number) => void;
}) {
  return (
    <table style={{ width: "100%", borderCollapse: "collapse", fontSize: 13 }}>
      <thead>
        <tr style={{ textAlign: "left", borderBottom: "2px solid #e5e7eb" }}>
          <Th>ID</Th>
          <Th>标题</Th>
          <Th>Run</Th>
          <Th>创建时间</Th>
        </tr>
      </thead>
      <tbody>
        {reports.map((r) => (
          <tr
            key={r.id}
            onClick={() => onSelect(r.id)}
            style={{ cursor: "pointer", borderBottom: "1px solid #f3f4f6" }}
          >
            <Td>{r.id}</Td>
            <Td>{r.title}</Td>
            <Td>{r.runId != null ? <a href={`/runs/${r.runId}`}>#{r.runId}</a> : "—"}</Td>
            <Td>{r.createdAt ?? "—"}</Td>
          </tr>
        ))}
        {reports.length === 0 && !loading && (
          <tr>
            <Td colSpan={4} style={{ padding: 24, textAlign: "center", color: "#9ca3af" }}>
              {error ? "加载失败（网络/服务错误），点击「刷新」重试" : "无研究报告"}
            </Td>
          </tr>
        )}
      </tbody>
    </table>
  );
}

function ReportDetail({ r }: { r: ResearchReport }) {
  return (
    <div style={{ display: "flex", flexDirection: "column", gap: 16 }}>
      <DefinitionList
        items={[
          ["标题", r.title],
          ["Run", r.runId != null ? <a key="run" href={`/runs/${r.runId}`}>#{r.runId}</a> : "—"],
          ["Task", r.taskId != null ? `#${r.taskId}` : "—"],
          ["创建时间", r.createdAt ?? "—"],
        ]}
      />
      <Section title="报告内容（Markdown）">
        <pre
          style={{
            whiteSpace: "pre-wrap",
            fontSize: 12,
            background: "#f9fafb",
            padding: 12,
            borderRadius: 6,
          }}
        >
          {r.contentMd}
        </pre>
      </Section>
    </div>
  );
}

// ==================== 工具 ====================

/** 解析 "JSON array of ids" 文本为数量；无法解析返回 0。TD-R2-002: 导出供前端单元测试。 */
export function countJsonArray(text: string): number {
  try {
    const arr = JSON.parse(text || "[]");
    return Array.isArray(arr) ? arr.length : 0;
  } catch {
    return 0;
  }
}

function Section({ title, children }: { title: string; children: React.ReactNode }) {
  return (
    <div>
      <h3 style={{ fontSize: 14, margin: "0 0 6px", color: "#374151" }}>{title}</h3>
      <div style={{ fontSize: 13 }}>{children}</div>
    </div>
  );
}

const inputStyle: React.CSSProperties = {
  padding: "6px 10px",
  border: "1px solid #d1d5db",
  borderRadius: 6,
  fontSize: 13,
};
const btnStyle: React.CSSProperties = {
  padding: "6px 12px",
  border: "1px solid #d1d5db",
  borderRadius: 6,
  background: "#fff",
  cursor: "pointer",
  fontSize: 13,
};
const Th = ({ children }: { children: React.ReactNode }) => (
  <th style={{ padding: "8px 10px", fontWeight: 600, color: "#6b7280" }}>{children}</th>
);
const Td = ({
  children,
  colSpan,
  style,
}: {
  children?: React.ReactNode;
  colSpan?: number;
  style?: React.CSSProperties;
}) => (
  <td colSpan={colSpan} style={{ padding: "8px 10px", ...style }}>
    {children}
  </td>
);
