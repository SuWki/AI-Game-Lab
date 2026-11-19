import {useState} from "react";
import {useQuery} from "react-query";
import {Link} from "react-router-dom";
import {api, type ProductionArtifact} from "@/api";

export function DesignerSpecHandoff({taskId, onSaved}: {
  taskId: number;
  onSaved: (spec: ProductionArtifact) => Promise<void>;
}) {
  const [run, setRun] = useState("");
  const [source, setSource] = useState("");
  const [busy, setBusy] = useState(false);
  const [error, setError] = useState("");
  const [message, setMessage] = useState("");
  const drafts = useQuery(["designer-drafts", taskId], () => api.designerDrafts(taskId));

  async function save() {
    setBusy(true);
    setError("");
    setMessage("");
    try {
      const output = JSON.parse(source);
      if (!output || typeof output !== "object" || !output.brief || !output.spec) {
        throw new Error("请补齐 JSON 中的 brief 和 spec 对象");
      }
      const saved = await api.importDesignerDraft(Number(run), {taskId, brief: output.brief, spec: output.spec});
      setMessage(`已从 Run #${run} 保存规格 r${saved.revision}`);
      await onSaved(saved);
    } catch (err) {
      setError(String(err));
    } finally {
      setBusy(false);
    }
  }

  return <section aria-label="Designer 规格草稿">
    <h2>导入 Designer 规格草稿</h2>
    {drafts.isLoading && <p>正在读取设计结果</p>}
    {drafts.data?.length === 0 && <p>设计任务完成后刷新结果。在 <Link to="/workflow">Workflow</Link> 可以查看或启动任务。</p>}
    <button type="button" disabled={drafts.isFetching} onClick={() => void drafts.refetch()}>刷新设计结果</button>
    <fieldset disabled={busy}>
      <label>Designer 执行 <select value={run} onChange={event => {
        const id = event.target.value;
        setRun(id);
        setError("");
        setMessage("");
        const result = drafts.data?.find(item => String(item.runId) === id)?.result ?? "";
        setSource(result.trim().replace(/^\x60\x60\x60(?:json)?\s*([\s\S]*?)\s*\x60\x60\x60$/i, "$1"));
      }}>
        <option value="">选择已完成的执行</option>
        {drafts.data?.map(draft => <option key={draft.runId} value={draft.runId}>Run #{draft.runId}</option>)}
      </select></label>
      {run && <>
        <p>检查玩法、参数和验收项。模型遗漏的字段可以直接在草稿中补齐。未解决的问题保留在 openQuestions 中。</p>
        <label>模型规格 JSON <textarea rows={15} value={source} onChange={event => setSource(event.target.value)}
          style={{display:"block", width:"100%", boxSizing:"border-box"}} /></label>
        <button type="button" onClick={() => void save()}>保存为规格草稿</button>
      </>}
    </fieldset>
    {(error || drafts.isError) && <p role="alert">{error || String(drafts.error)}</p>}
    {message && <p role="status">{message}</p>}
  </section>;
}
