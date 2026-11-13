import { useQuery, useQueryClient } from "react-query";
import { useMemo, useState } from "react";
import { useSearchParams } from "react-router-dom";
import { api } from "@/api";
import type { RunRow, TaskRow } from "@/types";
import { useGlobalSse } from "@/useGlobalSse";

const AGENT_TYPES = ["RESEARCH", "DESIGNER", "TESTER", "QA", "QA_LEAD"] as const;

const RUN_TONES: Record<string, "ok" | "info" | "danger" | "warn" | "default"> = {
  COMPLETED: "ok",
  RUNNING: "info",
  PENDING: "warn",
  FAILED: "danger",
  CANCELLED: "warn",
  TIMEOUT: "warn",
};

/**
 * R2-3：工作流驱动页 —— 用户驱动的跨 Agent 协作（Research → Designer）。
 *
 * - 创建 Task（agentType + 可选 researchSourceTaskId）；
 * - 启动 Run（POST /api/tasks/{id}/runs，异步执行）；
 * - 观测 Run 状态（join /api/runs 最新状态 + SSE 实时刷新）。
 *
 * 人类在环：不自主编排，只提供 UI 触发。从 ResearchPage「以该研究发起 Designer」
 * 跳转时 URL 携带 ?researchSourceTaskId=X，自动预填 DESIGNER 模式，不复制 ID。
 */
export function WorkflowPage() {
  const qc = useQueryClient();
  const [params] = useSearchParams();
  const urlResearchId = params.get("researchSourceTaskId");

  const [title, setTitle] = useState("");
  const [goal, setGoal] = useState("");
  const [agentType, setAgentType] = useState<string>(urlResearchId ? "DESIGNER" : "RESEARCH");
  const [researchSourceTaskId, setResearchSourceTaskId] = useState<number | null>(
    urlResearchId ? Number(urlResearchId) : null
  );
  const [submitting, setSubmitting] = useState(false);

  const { data: tasks = [], isLoading: tasksLoading } = useQuery({
    queryKey: ["tasks"],
    queryFn: () => api.listTasks(),
  });
  const { data: runs = [] } = useQuery({
    queryKey: ["runs"],
    queryFn: () => api.listRuns(),
  });

  // R2-2/R2-3：SSE 实时刷新任务与 Run 状态（任务创建 / Run 启动 / 终态）
  useGlobalSse((e) => {
    if (e.type === "TASK_CREATED") {
      qc.invalidateQueries({ queryKey: ["tasks"] });
    }
    if (
      e.type === "RUN_STARTED" ||
      e.type === "RUN_COMPLETED" ||
      e.type === "RUN_FAILED" ||
      e.type === "RUN_CANCELLED"
    ) {
      qc.invalidateQueries({ queryKey: ["runs"] });
    }
  });

  const latestRunOf = (taskId: number): RunRow | null => {
    let latest: RunRow | null = null;
    for (const r of runs) {
      if (r.taskId !== taskId) continue;
      if (latest == null || r.id > latest.id) latest = r;
    }
    return latest;
  };

  const researchTaskTitle = useMemo(() => {
    if (researchSourceTaskId == null) return null;
    return tasks.find((t) => t.id === researchSourceTaskId)?.title ?? null;
  }, [researchSourceTaskId, tasks]);

  const submit = async () => {
    setSubmitting(true);
    try {
      await api.createTask({
        title,
        goal,
        agentType,
        researchSourceTaskId: agentType === "DESIGNER" ? researchSourceTaskId : null,
      });
      setTitle("");
      setGoal("");
      if (!urlResearchId) {
        setResearchSourceTaskId(null);
      }
      qc.invalidateQueries({ queryKey: ["tasks"] });
    } finally {
      setSubmitting(false);
    }
  };

  const startRun = async (taskId: number) => {
    await api.startRun(taskId);
    qc.invalidateQueries({ queryKey: ["runs"] });
  };

  return (
    <div style={{ padding: 24 }}>
      <h1 style={{ fontSize: 22, marginTop: 8 }}>Workflow</h1>
      <p style={{ fontSize: 13, color: "#6b7280", marginTop: 4 }}>
        R2-3 任务创建 / Run 启动 / 状态观测 —— 人类驱动的 Research → Designer 协作链
      </p>

      {/* ===== 创建表单 ===== */}
      <div style={cardStyle}>
        <h3 style={{ fontSize: 14, margin: "0 0 12px", color: "#111827" }}>
          {researchSourceTaskId != null ? "以该研究发起 Designer" : "创建 Agent 任务"}
        </h3>
        {researchSourceTaskId != null && (
          <p style={{ fontSize: 12, color: "#1e40af", margin: "0 0 12px" }}>
            关联研究任务 #{researchSourceTaskId}（{researchTaskTitle ?? "…"}）——
            设计产物将以该研究为证据基础（constraintsJson.researchSourceTaskId）
          </p>
        )}
        <div style={{ display: "grid", gap: 10, gridTemplateColumns: "1fr 1fr", maxWidth: 720 }}>
          <label style={{ display: "flex", flexDirection: "column", gap: 4, fontSize: 13 }}>
            Agent 类型
            <select
              value={agentType}
              onChange={(e) => setAgentType(e.target.value)}
              style={inputStyle}
            >
              {AGENT_TYPES.map((t) => (
                <option key={t} value={t}>
                  {t}
                </option>
              ))}
            </select>
          </label>
          <label style={{ display: "flex", flexDirection: "column", gap: 4, fontSize: 13 }}>
            研究任务引用（仅 DESIGNER 可携带）
            <input
              type="number"
              value={researchSourceTaskId ?? ""}
              disabled={agentType !== "DESIGNER"}
              onChange={(e) =>
                setResearchSourceTaskId(e.target.value ? Number(e.target.value) : null)
              }
              placeholder={agentType === "DESIGNER" ? "研究 Task id（如 3）" : "仅 DESIGNER 可用"}
              style={{ ...inputStyle, background: agentType !== "DESIGNER" ? "#f3f4f6" : "#fff" }}
            />
          </label>
          <label style={{ display: "flex", flexDirection: "column", gap: 4, fontSize: 13 }}>
            标题 *
            <input
              value={title}
              onChange={(e) => setTitle(e.target.value)}
              placeholder="如：设计一套格斗数值"
              style={inputStyle}
            />
          </label>
          <label style={{ display: "flex", flexDirection: "column", gap: 4, fontSize: 13 }}>
            目标 *
            <input
              value={goal}
              onChange={(e) => setGoal(e.target.value)}
              placeholder="如：基于研究结论调整平衡性"
              style={inputStyle}
            />
          </label>
        </div>
        <div style={{ marginTop: 12 }}>
          <button
            onClick={submit}
            disabled={submitting || !title.trim() || !goal.trim()}
            style={{
              ...btnStyle,
              background: "#1e40af",
              color: "#fff",
              borderColor: "#1e40af",
            }}
          >
            {submitting ? "创建中…" : "创建任务"}
          </button>
        </div>
      </div>

      {/* ===== 任务列表 ===== */}
      <h3 style={{ fontSize: 14, margin: "20px 0 10px", color: "#111827" }}>
        任务（{tasks.length}）
      </h3>
      <table style={{ width: "100%", borderCollapse: "collapse", fontSize: 13 }}>
        <thead>
          <tr style={{ textAlign: "left", borderBottom: "2px solid #e5e7eb" }}>
            <Th>ID</Th>
            <Th>标题</Th>
            <Th>目标</Th>
            <Th>Agent</Th>
            <Th>研究引用</Th>
            <Th>最新 Run</Th>
            <Th>操作</Th>
          </tr>
        </thead>
        <tbody>
          {tasks.map((t: TaskRow) => {
            const run = latestRunOf(t.id);
            const researchRef = api.parseResearchSourceTaskId(t);
            return (
              <tr key={t.id} style={{ borderBottom: "1px solid #f3f4f6" }}>
                <Td>{t.id}</Td>
                <Td>{t.title}</Td>
                <Td>{t.goal}</Td>
                <Td>
                  <Badge tone={agentTone(api.parseTaskAgentType(t))}>
                    {api.parseTaskAgentType(t)}
                  </Badge>
                </Td>
                <Td>
                  {researchRef != null ? (
                    <a href={`/research`} style={{ color: "#1e40af" }}>
                      #{researchRef}
                    </a>
                  ) : (
                    "—"
                  )}
                </Td>
                <Td>
                  {run == null ? (
                    "（无）"
                  ) : (
                    <a href={`/runs/${run.id}`} style={{ color: "#1e40af", marginRight: 6 }}>
                      #{run.id}
                    </a>
                  )}
                  {run != null && (
                    <Badge tone={RUN_TONES[run.status] ?? "default"}>{run.status}</Badge>
                  )}
                </Td>
                <Td>
                  <button
                    onClick={() => startRun(t.id)}
                    disabled={run?.status === "RUNNING" || run?.status === "PENDING"}
                    style={btnStyle}
                  >
                    启动 Run
                  </button>
                </Td>
              </tr>
            );
          })}
          {tasks.length === 0 && !tasksLoading && (
            <tr>
              <Td colSpan={7} style={{ padding: 24, textAlign: "center", color: "#9ca3af" }}>
                暂无任务 —— 先创建一个 Research 任务收集证据，再以研究发起 Designer
              </Td>
            </tr>
          )}
        </tbody>
      </table>
    </div>
  );
}

function agentTone(agentType: string): "info" | "ok" | "warn" | "default" {
  switch (agentType) {
    case "RESEARCH":
      return "info";
    case "DESIGNER":
      return "ok";
    case "TESTER":
      return "warn";
    case "QA":
      return "info";
    // R5 Phase 2：QA 团队类型
    case "QA_LEAD":
      return "warn";
    case "QA_TESTER":
    case "QA_ANALYST":
    case "QA_RESEARCHER":
      return "info";
    default:
      return "default";
  }
}

// ==================== 基础组件（与其他页面一致的内联风格） ====================

function Badge({
  tone,
  children,
}: {
  tone: "ok" | "info" | "warn" | "danger" | "default";
  children: React.ReactNode;
}) {
  const colors: Record<string, { bg: string; fg: string }> = {
    ok: { bg: "#ecfdf5", fg: "#047857" },
    info: { bg: "#eff6ff", fg: "#1d4ed8" },
    warn: { bg: "#fffbeb", fg: "#b45309" },
    danger: { bg: "#fef2f2", fg: "#b91c1c" },
    default: { bg: "#f3f4f6", fg: "#374151" },
  };
  const c = colors[tone] ?? colors.default;
  return (
    <span
      style={{
        display: "inline-block",
        padding: "2px 8px",
        borderRadius: 999,
        fontSize: 12,
        fontWeight: 600,
        background: c.bg,
        color: c.fg,
      }}
    >
      {children}
    </span>
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
const cardStyle: React.CSSProperties = {
  border: "1px solid #e5e7eb",
  borderRadius: 8,
  padding: 16,
  background: "#f9fafb",
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
