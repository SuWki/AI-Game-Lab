import type { ReactNode } from "react";

export function Badge({ children, tone = "default" }: { children: ReactNode; tone?: "default" | "danger" | "warn" | "ok" | "info" }) {
  const map: Record<string, string> = {
    default: "#e5e7eb",
    danger: "#fee2e2",
    warn: "#fef3c7",
    ok: "#dcfce7",
    info: "#dbeafe",
  };
  const color: Record<string, string> = {
    default: "#374151",
    danger: "#b91c1c",
    warn: "#92400e",
    ok: "#166534",
    info: "#1e40af",
  };
  return (
    <span
      style={{
        background: map[tone],
        color: color[tone],
        borderRadius: 4,
        padding: "1px 6px",
        fontSize: 12,
        fontWeight: 600,
        whiteSpace: "nowrap",
      }}
    >
      {children}
    </span>
  );
}

export function severityTone(s: string): "danger" | "warn" | "info" | "default" {
  if (s === "BLOCKER" || s === "HIGH") return "danger";
  if (s === "MEDIUM") return "warn";
  if (s === "LOW" || s === "INFO") return "info";
  return "default";
}
