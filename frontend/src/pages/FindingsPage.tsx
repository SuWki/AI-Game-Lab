import { useQuery, useQueryClient } from "react-query";
import { useParams } from "react-router-dom";
import { useState } from "react";
import { api, type QaFindingFilter } from "@/api";
import type { Finding, FindingStatus } from "@/types";
import { Badge, severityTone } from "@/components/Badge";
import { Drawer } from "@/components/Drawer";
import { DefinitionList } from "@/components/DefinitionList";
import { useTesterEventSignal } from "@/useGlobalSse";

const SEVERITIES = ["", "BLOCKER", "HIGH", "MEDIUM", "LOW", "INFO"];
const CATEGORIES = ["", "CRASH", "SOFT_LOCK", "INVARIANT_VIOLATION", "UNEXPECTED_STATE", "BALANCE_ANOMALY", "OTHER"];
const SOURCES = ["", "INVARIANT", "LLM", "MANUAL"];
const STATUSES: FindingStatus[] = ["OPEN", "CONFIRMED", "DISMISSED", "FIXED"];

export function FindingsPage() {
  const qc = useQueryClient();
  const { id } = useParams();
  const [filter, setFilter] = useState<QaFindingFilter>({});
  const [selected, setSelected] = useState<number | null>(id ? Number(id) : null);

  // 首屏数据：QA REST list 接口拉取（SSE 语义：首屏 REST + 实时增量）
  const { data: findings = [], isLoading, error } = useQuery({
    queryKey: ["findings", filter],
    queryFn: () => api.listQaFindings(filter),
  });

  // 实时增量：收到 TEST_* 相关事件后触发 refetch（不修改后端 SSE）
  useTesterEventSignal(() => {
    qc.invalidateQueries({ queryKey: ["findings"] });
  });

  const selectedFinding = findings.find((f) => f.id === selected) ?? null;

  async function changeStatus(f: Finding, status: FindingStatus) {
    await api.updateFindingStatus(f.id, status);
    qc.invalidateQueries({ queryKey: ["findings"] });
    if (selected === f.id) qc.invalidateQueries({ queryKey: ["finding", f.id] });
  }

  return (
    <div style={{ padding: 24 }}>
      <h1 style={{ fontSize: 22, marginTop: 8 }}>Findings</h1>

      {/* 筛选 — 8 维 AND：configVersion/severity/category/source/status/runIds/testPlanId/taskId */}
      <div style={{ display: "flex", gap: 12, margin: "12px 0 16px", flexWrap: "wrap" }}>
        <input
          placeholder="configVersion"
          value={filter.configVersion ?? ""}
          onChange={(e) => setFilter((f) => ({ ...f, configVersion: e.target.value || undefined }))}
          style={inputStyle}
        />
        <select value={filter.severity ?? ""} onChange={(e) => setFilter((f) => ({ ...f, severity: e.target.value || undefined }))} style={inputStyle}>
          {SEVERITIES.map((s) => (
            <option key={s} value={s}>{s || "全部 severity"}</option>
          ))}
        </select>
        <select value={filter.category ?? ""} onChange={(e) => setFilter((f) => ({ ...f, category: e.target.value || undefined }))} style={inputStyle}>
          {CATEGORIES.map((c) => (
            <option key={c} value={c}>{c || "全部 category"}</option>
          ))}
        </select>
        <select value={filter.source ?? ""} onChange={(e) => setFilter((f) => ({ ...f, source: e.target.value || undefined }))} style={inputStyle}>
          {SOURCES.map((s) => (
            <option key={s} value={s}>{s || "全部 source"}</option>
          ))}
        </select>
        <select value={filter.status ?? ""} onChange={(e) => setFilter((f) => ({ ...f, status: e.target.value || undefined }))} style={inputStyle}>
          {["", ...STATUSES].map((s) => (
            <option key={s} value={s}>{s || "全部 status"}</option>
          ))}
        </select>
        <input
          placeholder="Run IDs（逗号分隔）"
          value={(filter.runIds ?? []).join(",")}
          onChange={(e) => {
            const ids = e.target.value.split(",").map((s) => s.trim()).filter(Boolean).map(Number).filter((n) => !Number.isNaN(n));
            setFilter((f) => ({ ...f, runIds: ids.length > 0 ? ids : undefined }));
          }}
          style={inputStyle}
        />
        <input
          placeholder="testPlanId"
          type="number"
          value={filter.testPlanId ?? ""}
          onChange={(e) => setFilter((f) => ({ ...f, testPlanId: e.target.value ? Number(e.target.value) : undefined }))}
          style={inputStyle}
        />
        <input
          placeholder="taskId"
          type="number"
          value={filter.taskId ?? ""}
          onChange={(e) => setFilter((f) => ({ ...f, taskId: e.target.value ? Number(e.target.value) : undefined }))}
          style={inputStyle}
        />
        <button onClick={() => qc.invalidateQueries({ queryKey: ["findings"] })} style={btnStyle}>刷新</button>
      </div>

      {isLoading && <p>加载中…</p>}
      {error ? <p style={{ color: "#b91c1c" }}>加载失败：{(error as Error).message}</p> : null}

      {/* 列表 */}
      <table style={{ width: "100%", borderCollapse: "collapse", fontSize: 13 }}>
        <thead>
          <tr style={{ textAlign: "left", borderBottom: "2px solid #e5e7eb" }}>
            <Th>ID</Th><Th>标题</Th><Th>Severity</Th><Th>Category</Th><Th>Config</Th><Th>Scenario</Th><Th>Status</Th><Th>Run</Th>
          </tr>
        </thead>
        <tbody>
          {findings.map((f) => (
            <tr key={f.id} onClick={() => setSelected(f.id)} style={{ cursor: "pointer", borderBottom: "1px solid #f3f4f6" }}>
              <Td>{f.id}</Td>
              <Td>{f.title}</Td>
              <Td><Badge tone={severityTone(f.severity)}>{f.severity}</Badge></Td>
              <Td>{f.category}</Td>
              <Td>{f.configVersion}</Td>
              <Td>{f.scenarioId}</Td>
              <Td><Badge tone={f.status === "OPEN" ? "warn" : "ok"}>{f.status}</Badge></Td>
              <Td>{f.runId != null ? <a href={`/runs/${f.runId}`}>#{f.runId}</a> : "—"}</Td>
            </tr>
          ))}
          {findings.length === 0 && !isLoading && (
            <tr><Td colSpan={8} style={{ padding: 24, textAlign: "center", color: "#9ca3af" }}>无 Finding（健康构建或尚未运行测试）</Td></tr>
          )}
        </tbody>
      </table>

      {/* 详情抽屉 */}
      <Drawer open={!!selectedFinding} title={`Finding #${selectedFinding?.id ?? ""}`} onClose={() => setSelected(null)}>
        {selectedFinding && <FindingDetail f={selectedFinding} onStatus={(s) => changeStatus(selectedFinding, s)} />}
      </Drawer>
    </div>
  );
}

function FindingDetail({ f, onStatus }: { f: Finding; onStatus: (s: FindingStatus) => void }) {
  const evidence = api.parseEvidence(f);
  return (
    <div style={{ display: "flex", flexDirection: "column", gap: 16 }}>
      <DefinitionList items={[
        ["Severity", <Badge tone={severityTone(f.severity)}>{f.severity}</Badge>],
        ["Category", f.category],
        ["Source", f.source],
        ["Status", <Badge tone={f.status === "OPEN" ? "warn" : "ok"}>{f.status}</Badge>],
        ["Config", f.configVersion],
        ["Scenario", f.scenarioId],
        ["Task", f.taskId != null ? `#${f.taskId}` : "—"],
        ["TestPlan", f.testPlanId != null ? <a href={`/test-plans/${f.testPlanId}`}>#{f.testPlanId}</a> : "—"],
        ["Run", f.runId != null ? <a href={`/runs/${f.runId}`}>#{f.runId}（Run Trace）</a> : "—"],
      ]} />

      <Section title="Expected">{f.expected ?? "—"}</Section>
      <Section title="Actual">{f.actual ?? "—"}</Section>
      <Section title="Repro Steps">
        <pre style={{ whiteSpace: "pre-wrap", fontSize: 12, background: "#f9fafb", padding: 12, borderRadius: 6 }}>{f.reproSteps}</pre>
      </Section>

      {evidence && (
        <Section title="Evidence">
          <DefinitionList items={[
            ["sessionId", evidence.sessionId],
            ["runId", evidence.runId != null ? `#${evidence.runId}` : "—"],
            ["seed", evidence.seed != null ? String(evidence.seed) : "—"],
            ["tick", evidence.tick != null ? String(evidence.tick) : "—"],
            ["toolCallIds", (evidence.toolCallIds ?? []).join(", ") || "—"],
          ]} />
          {f.runId != null && (
            <p style={{ marginTop: 12 }}>
              <a href={`/runs/${f.runId}`}>→ 在 Run Trace 中查看执行轨迹（tool_call / step / event）</a>
            </p>
          )}
        </Section>
      )}

      <Section title="状态流转">
        <div style={{ display: "flex", gap: 8, flexWrap: "wrap" }}>
          {STATUSES.filter((s) => s !== f.status).map((s) => (
            <button key={s} onClick={() => onStatus(s)} style={btnStyle}>{s}</button>
          ))}
        </div>
      </Section>
    </div>
  );
}

function Section({ title, children }: { title: string; children: React.ReactNode }) {
  return (
    <div>
      <h3 style={{ fontSize: 14, margin: "0 0 6px", color: "#374151" }}>{title}</h3>
      <div style={{ fontSize: 13 }}>{children}</div>
    </div>
  );
}

const inputStyle: React.CSSProperties = { padding: "6px 10px", border: "1px solid #d1d5db", borderRadius: 6, fontSize: 13 };
const btnStyle: React.CSSProperties = { padding: "6px 12px", border: "1px solid #d1d5db", borderRadius: 6, background: "#fff", cursor: "pointer", fontSize: 13 };
const Th = ({ children }: { children: React.ReactNode }) => <th style={{ padding: "8px 10px", fontWeight: 600, color: "#6b7280" }}>{children}</th>;
const Td = ({ children, colSpan, style }: { children?: React.ReactNode; colSpan?: number; style?: React.CSSProperties }) => (
  <td colSpan={colSpan} style={{ padding: "8px 10px", ...style }}>{children}</td>
);
