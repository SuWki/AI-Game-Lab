import { useMemo, useState } from "react";
import { useQuery } from "react-query";
import { api } from "@/api";
import type { ConfigVersion, FindingRegression, FindingRegressionItem } from "@/types";
import { Badge, severityTone } from "@/components/Badge";

const STATUS_TONE: Record<string, "danger" | "warn" | "ok" | "default"> = {
  NEW: "danger",
  FIXED: "ok",
  PERSISTED: "default",
  CHANGED: "warn",
};

const STATUS_LABEL: Record<string, string> = {
  NEW: "新增",
  FIXED: "已修复",
  PERSISTED: "持续",
  CHANGED: "严重程度变化",
};

const STAT_BG: Record<string, string> = {
  NEW: "#fee2e2",
  FIXED: "#dcfce7",
  PERSISTED: "#f3f4f6",
  CHANGED: "#fef3c7",
};
const STAT_FG: Record<string, string> = {
  NEW: "#dc2626",
  FIXED: "#16a34a",
  PERSISTED: "#6b7280",
  CHANGED: "#d97706",
};

export function ConfigRegressionPage() {
  const versions = useQuery<ConfigVersion[]>(["configVersions"], api.listConfigVersions);
  const versionList = useMemo(() => (versions.data ?? []).map((v: ConfigVersion) => v.version), [versions.data]);

  const [oldVer, setOldVer] = useState("");
  const [newVer, setNewVer] = useState("");

  const regression = useQuery<FindingRegression>(
    ["findingRegression", oldVer, newVer],
    () => api.getFindingRegression(oldVer, newVer),
    { enabled: !!oldVer && !!newVer && oldVer !== newVer }
  );

  const data: FindingRegression | null = regression.data ?? null;

  return (
    <div style={{ padding: "16px 24px" }}>
      <h2 style={{ fontSize: 18, fontWeight: 600, marginBottom: 12 }}>版本间 Finding 回归对比</h2>

      <div style={{ display: "flex", gap: 12, marginBottom: 16, alignItems: "center" }}>
        <label style={{ fontSize: 14 }}>
          基线版本：
          <select
            value={oldVer}
            onChange={(e) => setOldVer(e.target.value)}
            style={{ marginLeft: 4, padding: "4px 8px", fontSize: 14 }}
          >
            <option value="">选择版本</option>
            {versionList.map((v) => (
              <option key={v} value={v}>{v}</option>
            ))}
          </select>
        </label>
        <span style={{ fontSize: 18, color: "#9ca3af" }}>→</span>
        <label style={{ fontSize: 14 }}>
          被测版本：
          <select
            value={newVer}
            onChange={(e) => setNewVer(e.target.value)}
            style={{ marginLeft: 4, padding: "4px 8px", fontSize: 14 }}
          >
            <option value="">选择版本</option>
            {versionList.map((v) => (
              <option key={v} value={v}>{v}</option>
            ))}
          </select>
        </label>
        {oldVer && newVer && oldVer === newVer && (
          <span style={{ color: "#dc2626", fontSize: 13 }}>基线与被测版本不能相同</span>
        )}
      </div>

      {regression.isLoading && oldVer && newVer && <p style={{ color: "#6b7280" }}>加载中…</p>}
      {regression.isError && <p style={{ color: "#dc2626" }}>查询失败：{(regression.error as Error).message}</p>}

      {data && (
        <>
          <div style={{ display: "flex", gap: 16, marginBottom: 16, flexWrap: "wrap" }}>
            <StatBadge label="NEW（新增）" count={data.newCount} type="NEW" />
            <StatBadge label="FIXED（已修复）" count={data.fixedCount} type="FIXED" />
            <StatBadge label="PERSISTED（持续）" count={data.persistedCount} type="PERSISTED" />
            <StatBadge label="CHANGED（严重程度变化）" count={data.changedCount} type="CHANGED" />
          </div>

          {data.items.length === 0 ? (
            <p style={{ color: "#6b7280", fontStyle: "italic" }}>两版本间无 Finding 差异。</p>
          ) : (
            <table style={{ width: "100%", borderCollapse: "collapse", fontSize: 14 }}>
              <thead>
                <tr style={{ borderBottom: "2px solid #e5e7eb", textAlign: "left" }}>
                  <th style={{ padding: "8px 12px" }}>状态</th>
                  <th style={{ padding: "8px 12px" }}>场景</th>
                  <th style={{ padding: "8px 12px" }}>分类</th>
                  <th style={{ padding: "8px 12px" }}>标题</th>
                  <th style={{ padding: "8px 12px" }}>旧 severity</th>
                  <th style={{ padding: "8px 12px" }}>新 severity</th>
                </tr>
              </thead>
              <tbody>
                {data.items.map((item: FindingRegressionItem, idx: number) => (
                  <tr key={idx} style={{ borderBottom: "1px solid #f3f4f6" }}>
                    <td style={{ padding: "8px 12px" }}>
                      <Badge tone={STATUS_TONE[item.status] ?? "default"}>
                        {STATUS_LABEL[item.status] ?? item.status}
                      </Badge>
                    </td>
                    <td style={{ padding: "8px 12px", fontFamily: "monospace" }}>
                      {item.scenarioId || item.fingerprint.scenarioId}
                    </td>
                    <td style={{ padding: "8px 12px" }}>
                      {item.category || item.fingerprint.category}
                    </td>
                    <td style={{ padding: "8px 12px" }}>
                      {item.title || item.fingerprint.normalizedTitle}
                    </td>
                    <td style={{ padding: "8px 12px" }}>
                      {item.oldSeverity ? <Badge tone={severityTone(item.oldSeverity)}>{item.oldSeverity}</Badge> : "—"}
                    </td>
                    <td style={{ padding: "8px 12px" }}>
                      {item.newSeverity ? <Badge tone={severityTone(item.newSeverity)}>{item.newSeverity}</Badge> : "—"}
                    </td>
                  </tr>
                ))}
              </tbody>
            </table>
          )}
        </>
      )}
    </div>
  );
}

function StatBadge({ label, count, type }: { label: string; count: number; type: string }) {
  return (
    <div style={{
      display: "flex", alignItems: "center", gap: 8,
      padding: "6px 14px", borderRadius: 6,
      background: STAT_BG[type] ?? "#f3f4f6", border: `1px solid ${STAT_FG[type] ?? "#d1d5db"}40`,
    }}>
      <span style={{ fontSize: 20, fontWeight: 700, color: STAT_FG[type] ?? "#6b7280" }}>{count}</span>
      <span style={{ fontSize: 13, color: "#374151" }}>{label}</span>
    </div>
  );
}
