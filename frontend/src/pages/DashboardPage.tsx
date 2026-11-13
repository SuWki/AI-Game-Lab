import { useQuery, useQueryClient } from "react-query";
import { useTesterEventSignal } from "@/useGlobalSse";
import type { DashboardStats } from "@/types";

const BASE = "/api";

interface ApiResp<T> { code: number; message: string; data: T }

async function fetchStats(): Promise<DashboardStats> {
  const r = await fetch(`${BASE}/dashboard/stats`);
  if (!r.ok) throw new Error(`HTTP ${r.status}`);
  const body = await r.json() as ApiResp<DashboardStats>;
  if (body.code !== 0) throw new Error(`API ${body.code}: ${body.message}`);
  return body.data;
}

/**
 * P7-1 Dashboard（03 文档 P7-1）。
 * 复用 P7-2 SSE Hook 驱动的 invalidate；SSE 降级时自动使用 cache 的轮询回退。
 * 统计数据来源：/api/dashboard/stats（P7-1 补齐的真实聚合）。
 */
export function DashboardPage() {
  const qc = useQueryClient();

  const { data: stats, isLoading, error } = useQuery({
    queryKey: ["dashboard-stats"],
    queryFn: fetchStats,
    staleTime: 30000,
  });

  // 收到任何 Run 相关事件后刷新 Dashboard 统计
  const { connected } = useTesterEventSignal(() => {
    qc.invalidateQueries({ queryKey: ["dashboard-stats"] });
  });

  return (
    <div style={{ padding: 24 }}>
      <h1 style={{ fontSize: 22, marginTop: 8 }}>AI Game Lab · Dashboard</h1>

      {isLoading && <p>加载中…</p>}
      {error ? <p style={{ color: "#b91c1c" }}>加载失败：{(error as Error).message}</p> : null}
      {stats && (
        <>
          <div style={{ display: "grid", gridTemplateColumns: "repeat(auto-fit, minmax(130px, 1fr))", gap: 12, marginTop: 16 }}>
            <StatTile label="Total Runs" value={stats.totalRuns} />
            <StatTile label="Completed" value={stats.completedRuns} tone="ok" />
            <StatTile label="Failed" value={stats.failedRuns} tone="danger" />
            <StatTile label="Running" value={stats.runningRuns} tone="warn" />
            <StatTile label="Waiting Approval" value={stats.waitingApprovalRuns} tone="info" />
            <StatTile label="Cancelled" value={stats.cancelledRuns} tone="default" />
            <StatTile label="Timeout" value={stats.timeoutRuns} tone="danger" />
            <StatTile label="Experiments" value={stats.experimentCount} tone="info" />
            <StatTile label="Done Experiments" value={stats.doneExperiments} tone="ok" />
            <StatTile label="Total Tokens" value={stats.totalTokens} />
            <StatTile label="Success Rate" value={`${stats.successRate}%`} tone={stats.successRate >= 80 ? "ok" : stats.successRate >= 50 ? "warn" : "danger"} />
            <StatTile label="Avg Duration" value={stats.avgDurationMs != null ? `${Math.round(stats.avgDurationMs)} ms` : "—"} />
          </div>

          <p style={{ marginTop: 12, fontSize: 12, color: "#6b7280" }}>
            SSE 实时推送{connected ? "已就绪" : " → 当前降级轮询（断线时 refetchInterval）"}
          </p>
        </>
      )}
    </div>
  );
}

function StatTile({ label, value, tone = "default" }: {
  label: string; value: number | string; tone?: "default" | "ok" | "warn" | "danger" | "info";
}) {
  const bg: Record<string, string> = {
    default: "#f3f4f6", ok: "#dcfce7", warn: "#fef3c7",
    danger: "#fee2e2", info: "#dbeafe",
  };
  return (
    <div style={{
      border: "1px solid #e5e7eb", borderRadius: 8, padding: 16, background: bg[tone],
    }}>
      <div style={{ fontSize: 12, color: "#6b7280", marginBottom: 4 }}>{label}</div>
      <div style={{ fontSize: 20, fontWeight: 600, color: "#111827" }}>{value}</div>
    </div>
  );
}
