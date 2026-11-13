import { Fragment, useEffect, useState } from "react";
import { useQuery, useQueryClient } from "react-query";
import { api } from "@/api";
import { useTesterEventSignal } from "@/useGlobalSse";
import type { RunRow, RunTrace, ToolCallRow } from "@/types";

const STATUS_TONE: Record<string, string> = {
  PENDING: "#f3f4f6",
  RUNNING: "#dbeafe",
  WAITING_APPROVAL: "#fef3c7",
  COMPLETED: "#dcfce7",
  FAILED: "#fee2e2",
  CANCELLED: "#e5e7eb",
  TIMEOUT: "#fee2e2",
};

function StatusBadge({ status }: { status: string }) {
  const bg = STATUS_TONE[status] ?? "#f3f4f6";
  return (
    <span style={{
      background: bg, borderRadius: 6, padding: "2px 8px",
      fontSize: 12, fontWeight: 600, color: "#111827",
    }}>
      {status}
    </span>
  );
}

/**
 * P7-3 Run Trace（03 文档 P7-3）。
 * 左栏：Run 列表（GET /api/runs，倒序）；右栏：选中 Run 的时间线 + ToolCall Drawer。
 * 复用 P7-2 SSE Hook 驱动列表/详情刷新；SSE 降级时走 cache。
 */
export function RunTracePage() {
  const qc = useQueryClient();
  const { connected } = useTesterEventSignal(() => {
    qc.invalidateQueries({ queryKey: ["runs"] });
    qc.invalidateQueries({ queryKey: ["run-trace"] });
  });

  const runsQ = useQuery({ queryKey: ["runs"], queryFn: () => api.listRuns() });
  const [selectedId, setSelectedId] = useState<number | null>(null);
  const [busy,setBusy]=useState(false);const [controlError,setControlError]=useState("");
  async function control(action:"cancel"|"resume"|"retry") {
    if(selectedId==null)return;setBusy(true);setControlError("");
    try{const id=await api.controlRun(selectedId,action);await qc.invalidateQueries(["runs"]);await qc.invalidateQueries(["run-trace"]);if(action==="retry"&&typeof id==="number")setSelectedId(id);}catch(e){setControlError(String(e));}finally{setBusy(false);}
  }

  // 列表首次加载后默认选中第一个
  useEffect(() => {
    if (selectedId == null && runsQ.data && runsQ.data.length > 0) {
      setSelectedId(runsQ.data[0].id);
    }
  }, [selectedId, runsQ.data]);

  const traceQ = useQuery({
    queryKey: ["run-trace", selectedId],
    queryFn: () => api.getRunTrace(selectedId!),
    enabled: selectedId != null,
  });

  return (
    <div style={{ padding: 24 }}>
      <h1 style={{ fontSize: 22, marginTop: 8 }}>AI Game Lab · Run Trace</h1>
      {controlError&&<p role="alert">{controlError}</p>}
      {traceQ.data&&<p>
        {["PENDING","RUNNING","WAITING_APPROVAL"].includes(traceQ.data.run.status)&&<button disabled={busy} onClick={()=>void control("cancel")}>取消运行</button>}
        {traceQ.data.run.status==="WAITING_APPROVAL"&&<button disabled={busy} onClick={()=>void control("resume")}>审批后恢复</button>}
        {["COMPLETED","FAILED","CANCELLED","TIMEOUT"].includes(traceQ.data.run.status)&&<button disabled={busy} onClick={()=>void control("retry")}>新建运行并重试</button>}
      </p>}
      <p style={{ marginTop: 4, fontSize: 12, color: "#6b7280" }}>
        SSE 实时推送{connected ? "已就绪" : " → 当前降级轮询"}
      </p>

      <div style={{ display: "grid", gridTemplateColumns: "320px 1fr", gap: 20, marginTop: 16, alignItems: "start" }}>
        {/* 左栏：Run 列表 */}
        <div style={{ border: "1px solid #e5e7eb", borderRadius: 8, overflow: "hidden" }}>
          <div style={{ padding: "10px 12px", borderBottom: "1px solid #e5e7eb", fontWeight: 600, fontSize: 14 }}>
            Runs
          </div>
          {runsQ.isLoading && <div style={{ padding: 16, color: "#6b7280" }}>加载中…</div>}
          {runsQ.isError && (
            <div style={{ padding: 16, color: "#b91c1c" }}>加载失败：{(runsQ.error as Error).message}</div>
          )}
          <ul style={{ listStyle: "none", margin: 0, padding: 0, maxHeight: "70vh", overflowY: "auto" }}>
            {(runsQ.data ?? []).map((r) => (
              <li key={r.id}>
                <button
                  onClick={() => setSelectedId(r.id)}
                  style={{
                    width: "100%", textAlign: "left", border: "none",
                    borderBottom: "1px solid #f3f4f6", padding: "10px 12px",
                    cursor: "pointer", background: selectedId === r.id ? "#eff6ff" : "transparent",
                    display: "flex", flexDirection: "column", gap: 4,
                  }}
                >
                  <div style={{ display: "flex", justifyContent: "space-between", alignItems: "center" }}>
                    <strong style={{ fontSize: 13 }}>#{r.id}</strong>
                    <StatusBadge status={r.status} />
                  </div>
                  <div style={{ fontSize: 11, color: "#6b7280" }}>
                    {r.model} · step {r.currentStep}/{r.maxSteps} · {r.tokensUsed}/{r.tokenBudget} tok
                  </div>
                </button>
              </li>
            ))}
          </ul>
        </div>

        {/* 右栏：时间线 + Drawer */}
        <div>
          {selectedId == null && (
            <div style={{ color: "#6b7280", padding: 16 }}>从左侧选择一个 Run 查看执行时间线。</div>
          )}
          {selectedId != null && traceQ.isLoading && (
            <div style={{ color: "#6b7280", padding: 16 }}>加载 Run #{selectedId}…</div>
          )}
          {selectedId != null && traceQ.isError && (
            <div style={{ color: "#b91c1c", padding: 16 }}>加载失败：{(traceQ.error as Error).message}</div>
          )}
          {selectedId != null && traceQ.data && (
            <RunTimeline trace={traceQ.data} />
          )}
        </div>
      </div>
    </div>
  );
}

function RunTimeline({ trace }: { trace: RunTrace }) {
  const [openCall, setOpenCall] = useState<ToolCallRow | null>(null);
  return (
    <div>
      <div style={{ marginBottom: 12 }}>
        <RunMeta run={trace.run} />
      </div>
      <div style={{ border: "1px solid #e5e7eb", borderRadius: 8 }}>
        <div style={{ padding: "10px 12px", borderBottom: "1px solid #e5e7eb", fontWeight: 600, fontSize: 14 }}>
          Timeline（{trace.steps.length} steps）
        </div>
        <ol style={{ listStyle: "none", margin: 0, padding: 0 }}>
          {trace.steps.length === 0 && (
            <li style={{ padding: 16, color: "#6b7280" }}>无 Step 记录。</li>
          )}
          {trace.steps.map(({ step, toolCalls }) => (
            <li key={step.id} style={{ padding: "12px", borderBottom: "1px solid #f3f4f6" }}>
              <div style={{ display: "flex", gap: 8, alignItems: "baseline" }}>
                <span style={{ fontSize: 12, color: "#6b7280", fontWeight: 600 }}>#{step.seq}</span>
                <span style={{ fontWeight: 600, fontSize: 13 }}>{step.phase}</span>
                <span style={{ fontSize: 11, color: "#9ca3af" }}>
                  prompt {step.promptTokens} / compl {step.completionTokens}
                  {step.durationMs != null ? ` · ${step.durationMs}ms` : ""}
                </span>
              </div>
              {step.llmContent && (
                <pre style={{
                  margin: "6px 0 0", padding: 8, background: "#f9fafb",
                  borderRadius: 6, fontSize: 12, whiteSpace: "pre-wrap", maxHeight: 160, overflowY: "auto",
                }}>{step.llmContent}</pre>
              )}
              {toolCalls.length > 0 && (
                <div style={{ marginTop: 8, display: "flex", flexDirection: "column", gap: 4 }}>
                  {toolCalls.map((c) => (
                    <ToolCallRowItem key={c.id} call={c} onOpen={() => setOpenCall(c)} />
                  ))}
                </div>
              )}
            </li>
          ))}
        </ol>
      </div>

      {openCall && <ToolCallDrawer call={openCall} onClose={() => setOpenCall(null)} />}
    </div>
  );
}

function RunMeta({ run }: { run: RunRow }) {
  const rows: Array<[string, string | number | null]> = [
    ["Status", run.status],
    ["Model", run.model],
    ["Steps", `${run.currentStep}/${run.maxSteps}`],
    ["Tokens", `${run.tokensUsed}/${run.tokenBudget}`],
    ["Started", run.startedAt ?? "—"],
    ["Finished", run.finishedAt ?? "—"],
    ["Error", run.errorMessage ?? "—"],
  ];
  return (
    <div style={{ display: "grid", gridTemplateColumns: "120px 1fr", gap: "2px 12px", fontSize: 12 }}>
      {rows.map(([k, v]) => (
        <Fragment key={k}>
          <div style={{ color: "#6b7280" }}>{k}</div>
          <div style={{ color: "#111827", wordBreak: "break-word" }}>{v}</div>
        </Fragment>
      ))}
    </div>
  );
}

function ToolCallRowItem({ call, onOpen }: { call: ToolCallRow; onOpen: () => void }) {
  const tone = call.status === "COMPLETED" ? "#dcfce7" : call.status === "FAILED" ? "#fee2e2" : "#f3f4f6";
  return (
    <button
      onClick={onOpen}
      style={{
        textAlign: "left", border: "1px solid #e5e7eb", borderRadius: 6,
        padding: "6px 8px", background: tone, cursor: "pointer",
        display: "flex", justifyContent: "space-between", gap: 8, alignItems: "center",
      }}
    >
      <span style={{ fontSize: 12, fontWeight: 600 }}>{call.toolName}</span>
      <span style={{ fontSize: 11, color: "#6b7280" }}>
        {call.status} · {call.approvalStatus}
      </span>
    </button>
  );
}

function ToolCallDrawer({ call, onClose }: { call: ToolCallRow; onClose: () => void }) {
  return (
    <div
      onClick={onClose}
      style={{
        position: "fixed", inset: 0, background: "rgba(0,0,0,0.3)",
        display: "flex", justifyContent: "flex-end", zIndex: 50,
      }}
    >
      <div
        onClick={(e) => e.stopPropagation()}
        style={{
          width: "min(560px, 90vw)", background: "#fff", height: "100%",
          overflowY: "auto", padding: 24, boxShadow: "-4px 0 16px rgba(0,0,0,0.15)",
        }}
      >
        <div style={{ display: "flex", justifyContent: "space-between", alignItems: "center" }}>
          <h3 style={{ margin: 0, fontSize: 16 }}>Tool Call #{call.id}</h3>
          <button onClick={onClose} style={{ border: "none", background: "transparent", fontSize: 18, cursor: "pointer" }}>×</button>
        </div>
        <div style={{ marginTop: 12, display: "flex", gap: 8, flexWrap: "wrap" }}>
          <StatusBadge status={call.status} />
          <span style={{ fontSize: 12, color: "#6b7280" }}>approval: {call.approvalStatus}</span>
        </div>
        <Section title="Tool" value={call.toolName} />
        <Section title="Arguments" value={pretty(call.argumentsJson)} code />
        <Section title="Result" value={pretty(call.resultJson)} code />
        {call.error && <Section title="Error" value={call.error} code />}
        <div style={{ marginTop: 8, fontSize: 11, color: "#9ca3af" }}>
          started {call.startedAt ?? "—"} · ended {call.endedAt ?? "—"}
        </div>
      </div>
    </div>
  );
}

function Section({ title, value, code }: { title: string; value: string; code?: boolean }) {
  return (
    <div style={{ marginTop: 12 }}>
      <div style={{ fontSize: 12, color: "#6b7280", marginBottom: 4 }}>{title}</div>
      <pre style={{
        margin: 0, padding: 10, background: "#f9fafb", borderRadius: 6,
        fontSize: 12, whiteSpace: "pre-wrap", wordBreak: "break-word",
        fontFamily: code ? "monospace" : "inherit",
      }}>{value || "—"}</pre>
    </div>
  );
}

function pretty(json: string | null): string {
  if (!json) return "";
  try {
    return JSON.stringify(JSON.parse(json), null, 2);
  } catch {
    return json;
  }
}
