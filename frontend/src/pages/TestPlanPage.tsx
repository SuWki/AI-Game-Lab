import { useQuery, useQueryClient } from "react-query";
import { useParams } from "react-router-dom";
import { useState } from "react";
import { Link } from "react-router-dom";
import { api } from "@/api";
import type { Scenario } from "@/types";
import { Badge, severityTone } from "@/components/Badge";
import { DefinitionList } from "@/components/DefinitionList";
import { useTesterEventSignal } from "@/useGlobalSse";

/** TestPlan 查看页（T5-2）：展示 TestPlan 元信息 + scenarios + invariants + 关联 Findings。 */
export function TestPlanPage() {
  const { id } = useParams();
  const qc = useQueryClient();
  const planId = id ? Number(id) : null;

  // 首屏：REST 拉取 TestPlan（SSE 语义 = 首屏 REST + 实时增量）
  const { data: plan, isLoading, error } = useQuery({
    queryKey: ["testPlan", planId],
    queryFn: () => api.getTestPlan(planId!),
    enabled: planId != null,
  });

  // 实时：收到 tester 相关事件后刷新
  useTesterEventSignal(() => {
    if (planId != null) qc.invalidateQueries({ queryKey: ["testPlan", planId] });
  });

  if (planId == null) return <div style={pagePad}><p style={{ color: "#6b7280" }}>请从左侧导航选择一个 Test Plan，或通过 Finding 详情中的链接跳转。</p></div>;
  if (isLoading) return <div style={pagePad}><p>加载中…</p></div>;
  if (error) return <div style={pagePad}><p style={{ color: "#b91c1c" }}>加载失败：{(error as Error).message}</p></div>;
  if (!plan) return <div style={pagePad}><p>Test Plan #{planId} 不存在。</p></div>;

  const scenarios = api.parseScenarios(plan);

  return (
    <div style={pagePad}>
      <h1 style={{ fontSize: 22, marginTop: 8 }}>Test Plan #{plan.id}</h1>

      <DefinitionList items={[
        ["Task", plan.taskId != null ? <Link to={`/findings`}>#{plan.taskId}</Link> : "—"],
        ["Config Version", plan.configVersion],
        ["Status", <Badge tone={plan.status === "ACTIVE" ? "ok" : plan.status === "COMPLETED" ? "info" : "warn"}>{plan.status}</Badge>],
        ["Created", new Date(plan.createdAt).toLocaleString()],
      ]} />

      {/* Scenarios */}
      <h2 style={{ fontSize: 16, marginTop: 24, marginBottom: 8 }}>Scenarios（{scenarios.length}）</h2>
      {scenarios.length === 0 ? (
        <p style={{ color: "#9ca3af" }}>无 scenario（TestPlan 未填充 scenariosJson）</p>
      ) : (
        <div style={{ display: "flex", flexDirection: "column", gap: 12 }}>
          {scenarios.map((sc, idx) => <ScenarioCard key={sc.id ?? idx} scenario={sc} index={idx} />)}
        </div>
      )}

      {/* 关联 Findings（无 testPlanId 过滤端点，客户端按 testPlanId 过滤） */}
      <h2 style={{ fontSize: 16, marginTop: 24, marginBottom: 8 }}>关联 Findings</h2>
      <AssociatedFindings planId={plan.id} />
    </div>
  );
}

function ScenarioCard({ scenario, index }: { scenario: Scenario; index: number }) {
  const [expanded, setExpanded] = useState(false);
  return (
    <div style={cardStyle}>
      <div style={{ display: "flex", justifyContent: "space-between", alignItems: "center", cursor: "pointer" }} onClick={() => setExpanded((v) => !v)}>
        <span style={{ fontWeight: 600, fontSize: 14 }}>
          #{index + 1} <span style={{ color: "#6b7280", fontWeight: 400 }}>{scenario.id}</span>
        </span>
        <Badge tone="info">{scenario.type}</Badge>
      </div>
      <p style={{ margin: "6px 0 0", fontSize: 13, color: "#374151" }}>{scenario.goal}</p>

      {expanded && (
        <div style={{ marginTop: 10, fontSize: 13 }}>
          <dl style={{ display: "grid", gridTemplateColumns: "100px 1fr", rowGap: 4, columnGap: 8, margin: 0 }}>
            <dt style={{ color: "#6b7280" }}>seed</dt><dd style={{ margin: 0 }}>{scenario.seed}</dd>
            <dt style={{ color: "#6b7280" }}>repeat</dt><dd style={{ margin: 0 }}>{scenario.repeat}</dd>
            <dt style={{ color: "#6b7280" }}>playerProfile</dt><dd style={{ margin: 0 }}>{scenario.playerProfile}</dd>
            <dt style={{ color: "#6b7280" }}>overrides</dt><dd style={{ margin: 0 }}><code style={{ fontSize: 12 }}>{JSON.stringify(scenario.overrides)}</code></dd>
          </dl>

          {scenario.invariants.length > 0 && (
            <div style={{ marginTop: 8 }}>
              <strong>Invariants:</strong>
              <ul style={{ margin: "4px 0 0 18px", padding: 0 }}>
                {scenario.invariants.map((inv, i) => <li key={i} style={{ fontSize: 12 }}>{inv}</li>)}
              </ul>
            </div>
          )}
        </div>
      )}
    </div>
  );
}

function AssociatedFindings({ planId }: { planId: number }) {
  const qc = useQueryClient();

  // 获取全部 findings，客户端按 testPlanId 过滤（无后端 testPlanId 过滤端点）
  const { data: allFindings = [], isLoading } = useQuery({
    queryKey: ["findings", "all"],
    queryFn: () => api.listFindings(),
  });

  useTesterEventSignal(() => {
    qc.invalidateQueries({ queryKey: ["findings", "all"] });
  });

  const matched = allFindings.filter((f) => f.testPlanId === planId);

  if (isLoading) return <p style={{ color: "#9ca3af" }}>加载关联 Findings…</p>;
  if (matched.length === 0) return <p style={{ color: "#9ca3af" }}>暂无 Finding 与此 TestPlan 关联。</p>;

  return (
    <table style={{ width: "100%", borderCollapse: "collapse", fontSize: 13 }}>
      <thead>
        <tr style={{ textAlign: "left", borderBottom: "2px solid #e5e7eb" }}>
          <th style={thStyle}>ID</th>
          <th style={thStyle}>标题</th>
          <th style={thStyle}>Severity</th>
          <th style={thStyle}>Status</th>
          <th style={thStyle}>Run</th>
        </tr>
      </thead>
      <tbody>
        {matched.map((f) => (
          <tr key={f.id} style={{ borderBottom: "1px solid #f3f4f6" }}>
            <td style={tdStyle}><Link to={`/findings/${f.id}`}>{f.id}</Link></td>
            <td style={tdStyle}>{f.title}</td>
            <td style={tdStyle}><Badge tone={severityTone(f.severity)}>{f.severity}</Badge></td>
            <td style={tdStyle}><Badge tone={f.status === "OPEN" ? "warn" : "ok"}>{f.status}</Badge></td>
            <td style={tdStyle}>{f.runId != null ? <a href={`/runs/${f.runId}`}>#{f.runId}</a> : "—"}</td>
          </tr>
        ))}
      </tbody>
    </table>
  );
}

const pagePad: React.CSSProperties = { padding: 24 };
const cardStyle: React.CSSProperties = { border: "1px solid #e5e7eb", borderRadius: 8, padding: "12px 16px", background: "#fafafa" };
const thStyle: React.CSSProperties = { padding: "8px 10px", fontWeight: 600, color: "#6b7280" };
const tdStyle: React.CSSProperties = { padding: "8px 10px" };
