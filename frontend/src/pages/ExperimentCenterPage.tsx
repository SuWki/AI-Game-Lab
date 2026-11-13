import { useQuery, useQueryClient } from "react-query";
import { useState } from "react";
import { api } from "@/api";
import type { Experiment, SimulationResult, ExperimentPair } from "@/types";
import { Badge } from "@/components/Badge";

/**
 * P7-4 Experiment Center（03 文档 P7-4）。
 * 复用 P5-3 已有 Experiment REST：list / detail / run / compare。
 * 图表忠实反映 P5 实现（含 TD-006：平衡度用胜率方差代理，不伪装成真实 DPS 方差）。
 */
export function ExperimentCenterPage() {
  const qc = useQueryClient();
  const [configVersion, setConfigVersion] = useState("");
  const [scenario, setScenario] = useState("");
  const [selected, setSelected] = useState<number[]>([]);

  const { data: experiments = [], isLoading, error } = useQuery({
    queryKey: ["experiments", { configVersion, scenario }],
    queryFn: () => api.listExperiments({ configVersion: configVersion || undefined, scenario: scenario || undefined }),
  });

  const { data: compare, refetch: runCompare } = useQuery({
    queryKey: ["compare", selected],
    queryFn: () => api.compareExperiments(selected),
    enabled: false,
  });

  function toggleSelect(id: number) {
    setSelected((prev) =>
      prev.includes(id)
        ? prev.filter((x) => x !== id)
        : prev.length >= 4
          ? prev
          : [...prev, id],
    );
  }

  async function runNew() {
    await api.runExperiment({ count: 1000 });
    qc.invalidateQueries({ queryKey: ["experiments"] });
  }

  return (
    <div style={{ padding: 24 }}>
      <h1 style={{ fontSize: 22, marginTop: 8 }}>Experiment Center</h1>

      <div style={{ display: "flex", gap: 12, margin: "12px 0 16px", flexWrap: "wrap", alignItems: "center" }}>
        <input placeholder="configVersion (e.g. v001)" value={configVersion}
          onChange={(e) => setConfigVersion(e.target.value)} style={inputStyle} />
        <input placeholder="scenario (crit/penetration/attack_speed/ALL_BUILDS)" value={scenario}
          onChange={(e) => setScenario(e.target.value)} style={inputStyle} />
        <button onClick={() => qc.invalidateQueries({ queryKey: ["experiments"] })} style={btnStyle}>刷新</button>
        <button onClick={runNew} style={btnStyle}>运行新实验 (count=1000)</button>
        <span style={{ fontSize: 12, color: "#6b7280" }}>已选 {selected.length}/4</span>
        <button disabled={selected.length < 2} onClick={() => runCompare()} style={{ ...btnStyle, opacity: selected.length < 2 ? 0.5 : 1 }}>
          对比
        </button>
      </div>

      {isLoading && <p>加载中…</p>}
      {error ? <p style={{ color: "#b91c1c" }}>加载失败：{(error as Error).message}</p> : null}

      <table style={{ width: "100%", borderCollapse: "collapse", fontSize: 13 }}>
        <thead>
          <tr style={{ textAlign: "left", borderBottom: "2px solid #e5e7eb" }}>
            <Th>选</Th><Th>ID</Th><Th>Config</Th><Th>Scenario</Th><Th>Seed</Th><Th>Count</Th><Th>WinRate</Th><Th>Status</Th>
          </tr>
        </thead>
        <tbody>
          {experiments.map((e) => (
            <tr key={e.id} onClick={() => toggleSelect(e.id)}
              style={{ cursor: "pointer", borderBottom: "1px solid #f3f4f6", background: selected.includes(e.id) ? "#eff6ff" : undefined }}>
              <Td><input type="checkbox" readOnly checked={selected.includes(e.id)} /></Td>
              <Td>{e.id}</Td>
              <Td>{e.configVersion}</Td>
              <Td>{e.scenario}</Td>
              <Td>{e.seed}</Td>
              <Td>{e.simulationCount}</Td>
              <Td>{e.winRate != null ? `${(e.winRate * 100).toFixed(2)}%` : "—"}</Td>
              <Td><Badge tone={e.status === "DONE" ? "ok" : e.status === "FAILED" ? "danger" : "warn"}>{e.status}</Badge></Td>
            </tr>
          ))}
          {experiments.length === 0 && !isLoading && (
            <tr><Td colSpan={8} style={{ padding: 24, textAlign: "center", color: "#9ca3af" }}>无实验（运行一次以生成数据）</Td></tr>
          )}
        </tbody>
      </table>

      {selected.length >= 2 && compare && (
        <CompareSection pairs={compare} />
      )}

      {experiments.length > 0 && (
        <Charts experiments={experiments} selected={selected} />
      )}
    </div>
  );
}

function CompareSection({ pairs }: { pairs: ExperimentPair[] }) {
  return (
    <div style={{ marginTop: 24 }}>
      <h2 style={{ fontSize: 16 }}>对比结果（Δ 胜率 + z 显著性，阈值 |z|&gt;1.96）</h2>
      <table style={{ width: "100%", borderCollapse: "collapse", fontSize: 13 }}>
        <thead>
          <tr style={{ textAlign: "left", borderBottom: "2px solid #e5e7eb" }}>
            <Th>A</Th><Th>B</Th><Th>ΔwinRate</Th><Th>z</Th><Th>显著</Th>
          </tr>
        </thead>
        <tbody>
          {pairs.map((p, i) => (
            <tr key={i} style={{ borderBottom: "1px solid #f3f4f6" }}>
              <Td>#{p.experimentA}</Td>
              <Td>#{p.experimentB}</Td>
              <Td>{p.deltaWinRate.toFixed(4)}</Td>
              <Td>{p.zScore.toFixed(3)}</Td>
              <Td><Badge tone={p.significant ? "ok" : "default"}>{p.significant ? "显著" : "不显著"}</Badge></Td>
            </tr>
          ))}
        </tbody>
      </table>
    </div>
  );
}

function Charts({ experiments, selected }: { experiments: Experiment[]; selected: number[] }) {
  // 优先可视化已选实验；无选中时显示全部有结果的实验
  const visible = selected.length > 0
    ? experiments.filter((e) => selected.includes(e.id))
    : experiments.filter((e) => e.resultJson);
  // 取第一个有 result_json 的实验作为图表样本（忠实 P5 数据；不合并/不伪造）
  const sample = visible.find((e) => e.resultJson) ?? experiments[0];
  const result: SimulationResult | null = sample ? api.parseSimulationResult(sample) : null;

  if (!result) {
    return (
      <div style={{ marginTop: 24, color: "#9ca3af", fontSize: 13 }}>
        选中实验尚无 result_json（模拟未完成或已拒绝），图表待数据就绪后渲染。
      </div>
    );
  }

  const builds = Object.entries(result.buildDistribution);
  const pickRates = Object.entries(result.upgradePickRate ?? {});

  return (
    <div style={{ marginTop: 24, display: "grid", gridTemplateColumns: "repeat(auto-fit, minmax(340px, 1fr))", gap: 20 }}>
      <Panel title="指标柱状图（各实验胜率）">
        <BarChart
          labels={visible.slice(0, 8).map((e) => `#${e.id}`)}
          values={visible.slice(0, 8).map((e) => (e.winRate ?? 0) * 100)}
          unit="%"
          color="#1e40af"
        />
      </Panel>

      <Panel title="Build 分布堆叠（胜率 × games 占比）">
        <StackedBarChart builds={builds} />
      </Panel>

      <Panel title="平衡度雷达（各 build 胜率，0–100%）">
        <RadarChart builds={builds} />
      </Panel>

      <Panel title="升级选取 PickRate（Top，0–100%）">
        <BarChart
          labels={pickRates.slice(0, 8).map(([k]) => k)}
          values={pickRates.slice(0, 8).map(([, v]) => v * 100)}
          unit="%"
          color="#0f766e"
          horizontal
        />
      </Panel>

      <Panel title="伤害构成（damageHistogram 24 桶）">
        <Histogram data={result.damageHistogram ?? []} />
      </Panel>
    </div>
  );
}

// ==================== 通用图表组件（纯 SVG，无第三方依赖） ====================

function Panel({ title, children }: { title: string; children: React.ReactNode }) {
  return (
    <div style={{ border: "1px solid #e5e7eb", borderRadius: 8, padding: 16, background: "#fff" }}>
      <h3 style={{ fontSize: 14, margin: "0 0 12px", color: "#374151" }}>{title}</h3>
      {children}
    </div>
  );
}

function BarChart({ labels, values, unit = "", color = "#1e40af", horizontal = false }: {
  labels: string[]; values: number[]; unit?: string; color?: string; horizontal?: boolean;
}) {
  const max = Math.max(1, ...values);
  if (!horizontal) {
    const W = 300, H = 180, bw = labels.length ? W / labels.length : W;
    return (
      <svg viewBox={`0 0 ${W} ${H}`} width="100%" role="img" aria-label="bar chart">
        {values.map((v, i) => {
          const h = (v / max) * (H - 30);
          return (
            <g key={i}>
              <rect x={i * bw + 4} y={H - 20 - h} width={bw - 8} height={h} fill={color} rx={2} />
              <text x={i * bw + bw / 2} y={H - 8} fontSize={9} textAnchor="middle" fill="#6b7280">{labels[i]}</text>
              <text x={i * bw + bw / 2} y={H - 22 - h} fontSize={9} textAnchor="middle" fill="#374151">{v.toFixed(1)}{unit}</text>
            </g>
          );
        })}
      </svg>
    );
  }
  const rowH = 22, W = 300, labelW = 90;
  const H = values.length * rowH + 10;
  return (
    <svg viewBox={`0 0 ${W} ${H}`} width="100%" role="img" aria-label="horizontal bar chart">
      {values.map((v, i) => {
        const w = (v / max) * (W - labelW - 10);
        return (
          <g key={i}>
            <text x={0} y={i * rowH + 14} fontSize={9} fill="#374151">{trunc(labels[i], 13)}</text>
            <rect x={labelW} y={i * rowH + 4} width={w} height={rowH - 8} fill={color} rx={2} />
            <text x={labelW + w + 4} y={i * rowH + 14} fontSize={9} fill="#374151">{v.toFixed(1)}{unit}</text>
          </g>
        );
      })}
    </svg>
  );
}

function StackedBarChart({ builds }: { builds: Array<[string, { winRate: number; games: number }]> }) {
  const W = 300, H = 180;
  const total = builds.reduce((s, [, b]) => s + (b.games || 1), 0) || 1;
  let x = 0;
  return (
    <svg viewBox={`0 0 ${W} ${H}`} width="100%" role="img" aria-label="stacked bar">
      {builds.map(([name, b], i) => {
        const w = ((b.games || 1) / total) * (W - 20);
        const rect = <rect key={i} x={x} y={50} width={w} height={H - 90} fill={CATEGORICAL[i % CATEGORICAL.length]} />;
        const label = <text key={`l${i}`} x={x + w / 2} y={H - 18} fontSize={9} textAnchor="middle" fill="#374151">{trunc(name, 8)}</text>;
        x += w;
        return <g key={i}>{rect}{label}</g>;
      })}
      <text x={10} y={44} fontSize={9} fill="#6b7280">games 占比（堆叠）</text>
    </svg>
  );
}

function RadarChart({ builds }: { builds: Array<[string, { winRate: number }]> }) {
  const cx = 130, cy = 95, R = 70;
  const n = Math.max(builds.length, 3);
  const pts = builds.map(([name, b], i) => {
    const ang = (Math.PI * 2 * i) / n - Math.PI / 2;
    const r = (b.winRate ?? 0) * R; // winRate 0–1 → 半径
    return { name, x: cx + r * Math.cos(ang), y: cy + r * Math.sin(ang), ang };
  });
  const poly = pts.map((p) => `${p.x.toFixed(1)},${p.y.toFixed(1)}`).join(" ");
  return (
    <svg viewBox="0 0 260 190" width="100%" role="img" aria-label="radar chart">
      {[0.25, 0.5, 0.75, 1].map((f) => (
        <circle key={f} cx={cx} cy={cy} r={R * f} fill="none" stroke="#e5e7eb" />
      ))}
      <polygon points={poly} fill="rgba(30,64,175,0.25)" stroke="#1e40af" strokeWidth={1.5} />
      {pts.map((p, i) => (
        <g key={i}>
          <circle cx={p.x} cy={p.y} r={2.5} fill={CATEGORICAL[i % CATEGORICAL.length]} />
          <text x={cx + (R + 8) * Math.cos(p.ang)} y={cy + (R + 8) * Math.sin(p.ang)} fontSize={8}
            textAnchor="middle" fill="#374151">{trunc(p.name, 6)}</text>
        </g>
      ))}
      <text x={cx} y={cy + R + 14} fontSize={8} fill="#6b7280">胜率 0–100%（半径）</text>
    </svg>
  );
}

function Histogram({ data }: { data: number[] }) {
  if (!data.length) return <div style={{ color: "#9ca3af", fontSize: 12 }}>无伤害数据</div>;
  const max = Math.max(1, ...data);
  const W = 300, H = 160, bw = W / data.length;
  return (
    <svg viewBox={`0 0 ${W} ${H}`} width="100%" role="img" aria-label="histogram">
      {data.map((v, i) => {
        const h = (v / max) * (H - 20);
        return <rect key={i} x={i * bw} y={H - 16 - h} width={bw - 1} height={h} fill="#7c3aed" rx={1} />;
      })}
      <text x={4} y={H - 4} fontSize={9} fill="#6b7280">伤害区间（24 桶）</text>
    </svg>
  );
}

// ==================== 小工具 ====================

// 胜利/穿透/攻速 三 build 固定色序（dataviz：categorical 固定顺序，不循环）
const CATEGORICAL = ["#1e40af", "#0f766e", "#b45309"];

function trunc(s: string, n: number): string {
  return s.length > n ? s.slice(0, n - 1) + "…" : s;
}

const inputStyle: React.CSSProperties = { padding: "6px 10px", border: "1px solid #d1d5db", borderRadius: 6, fontSize: 13 };
const btnStyle: React.CSSProperties = { padding: "6px 12px", border: "1px solid #d1d5db", borderRadius: 6, background: "#fff", cursor: "pointer", fontSize: 13 };
const Th = ({ children }: { children: React.ReactNode }) => <th style={{ padding: "8px 10px", fontWeight: 600, color: "#6b7280" }}>{children}</th>;
const Td = ({ children, colSpan, style }: { children?: React.ReactNode; colSpan?: number; style?: React.CSSProperties }) => (
  <td colSpan={colSpan} style={{ padding: "8px 10px", ...style }}>{children}</td>
);
