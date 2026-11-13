import type { ReactNode } from "react";

/** 右侧抽屉（详情视图），复用中台通用组件模式。 */
export function Drawer({ open, title, onClose, children }: {
  open: boolean;
  title: string;
  onClose: () => void;
  children: ReactNode;
}) {
  if (!open) return null;
  return (
    <div
      onClick={onClose}
      style={{ position: "fixed", inset: 0, background: "rgba(0,0,0,0.3)", display: "flex", justifyContent: "flex-end", zIndex: 1000 }}
    >
      <div
        onClick={(e) => e.stopPropagation()}
        style={{ width: 560, maxWidth: "92vw", background: "#fff", height: "100%", overflow: "auto", padding: 24, boxShadow: "-4px 0 16px rgba(0,0,0,0.15)" }}
      >
        <div style={{ display: "flex", justifyContent: "space-between", alignItems: "center", marginBottom: 16 }}>
          <h2 style={{ margin: 0, fontSize: 18 }}>{title}</h2>
          <button onClick={onClose} style={{ border: "none", background: "transparent", fontSize: 20, cursor: "pointer" }}>×</button>
        </div>
        {children}
      </div>
    </div>
  );
}
