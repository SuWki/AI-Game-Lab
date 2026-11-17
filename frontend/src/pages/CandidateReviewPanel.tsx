import {useEffect,useState} from "react";
import {useQuery,useQueryClient} from "react-query";
import {api,type ProductionCandidate,type CandidateReview,type CandidateReviewInput,type CandidateReviewRecord,type ManualOutcome} from "@/api";

type Draft={revision:number;manifestHash:string;reviewer:string;summary:string;checks:CandidateReviewInput["checks"]};
function draftFrom(view:CandidateReview):Draft{return {revision:view.revision,manifestHash:view.manifestHash,reviewer:view.latestReview?.reviewer??"",summary:view.latestReview?.summary??"",checks:view.checks.map(c=>({criterionId:c.criterionId,outcome:c.outcome,evidenceNote:c.evidenceNote}))};}

export function CandidateReviewPanel({candidate}:{candidate:ProductionCandidate}){
  const qc=useQueryClient();const query=useQuery(["candidate-review",candidate.id],()=>api.candidateReview(candidate.id));
  const [draft,setDraft]=useState<Draft|null>(null),[dirty,setDirty]=useState(false),[busy,setBusy]=useState(false);
  const [error,setError]=useState(""),[message,setMessage]=useState("");
  const [history,setHistory]=useState<CandidateReviewRecord[]>([]),[historyOpen,setHistoryOpen]=useState(false),[more,setMore]=useState(false);
  useEffect(()=>{if(query.data&&!dirty)setDraft(draftFrom(query.data));},[query.data,dirty]);
  async function action(work:()=>Promise<void>){setBusy(true);setError("");try{await work();}catch(e){setError(String(e));}finally{setBusy(false);}}
  function edit(change:Partial<Draft>){if(draft){setDirty(true);setDraft({...draft,...change});}}
  function criterion(id:string,change:Partial<CandidateReviewInput["checks"][number]>){if(draft)edit({checks:draft.checks.map(c=>c.criterionId===id?{...c,...change}:c)});}
  async function save(decision:CandidateReviewInput["decision"]){
    if(!draft)return;
    const response=await api.saveCandidateReview(candidate.id,{expectedRevision:draft.revision,manifestHash:draft.manifestHash,reviewer:draft.reviewer,summary:draft.summary,decision,checks:draft.checks});
    qc.setQueryData(["candidate-review",candidate.id],response);setDraft(draftFrom(response));setDirty(false);setHistoryOpen(false);setMessage(`已记录人工评审 r${response.revision} · ${response.decision}，原 ZIP 未改写。`);
  }
  async function reload(){const response=await query.refetch();if(response.error)throw response.error;if(response.data){setDraft(draftFrom(response.data));setDirty(false);}}
  async function loadHistory(earlier=false){const before=earlier?history[history.length-1]?.revision:undefined;const rows=await api.candidateReviewHistory(candidate.id,before);setHistory(earlier?[...history,...rows]:rows);setMore(rows.length===100);setHistoryOpen(true);}
  async function receipt(){const blob=await api.downloadReviewReceipt(candidate.id),url=URL.createObjectURL(blob),link=document.createElement("a");link.href=url;link.download=`agl-review-${candidate.id}.json`;link.click();setTimeout(()=>URL.revokeObjectURL(url),1000);}
  const view=query.data;
  const ready=Boolean(draft?.reviewer.trim()&&draft.summary.trim()&&draft.checks.every(c=>c.outcome==="NOT_TESTED"||Boolean(c.evidenceNote.trim())));
  const allPassed=Boolean(draft?.checks.length&&draft.checks.every(c=>c.outcome==="PASS"));
  return <section aria-label={`候选人工评审 ${candidate.id}`} style={{borderTop:"1px solid #ddd",marginTop:16,paddingTop:12}}>
    <h5>规格覆盖与人工评审</h5>
    <p>引擎检查不自动覆盖规格项。填写对这份固定构建的人工观察；接受、拒绝和修订都会留痕，原 ZIP 保持不变。</p>
    {Boolean(error||query.error)&&<p role="alert">{error||String(query.error)}</p>}{message&&<p role="status">{message}</p>}
    {query.isLoading&&<p>读取固定规格与评审记录…</p>}
    {view&&<>
      <p>已记录意见：{view.decision} · r{view.revision} · 人工规格项通过 {view.coverage.passed} / {view.coverage.total}，未测 {view.coverage.untested}，失败 {view.coverage.failed}</p>
      <p><strong>{view.gate.eligibleForDelivery?"门禁查询：此版本可交付":"门禁查询：此版本尚不可交付"}</strong></p>
      {!view.gate.candidateCurrent&&<p>该包已是历史候选，或生产存在未决工作；过去接受不代表当前版本通过。</p>}
      <details><summary>按需求查看覆盖</summary>{view.coverage.requirements.map(r=><p key={r.id}>{r.id} · {r.description} · {r.status}</p>)}</details>
      {draft&&<fieldset disabled={busy||Boolean(query.error)} style={{marginTop:12}}>
        <legend>编辑人工意见（基于 r{draft.revision}）</legend>
        <p><label>评审者 <input aria-label="评审者" value={draft.reviewer} maxLength={120} onChange={e=>edit({reviewer:e.target.value})}/></label></p>
        {view.checks.map(c=>{const current=draft.checks.find(row=>row.criterionId===c.criterionId);if(!current)return null;return <section key={c.criterionId} style={{padding:12,background:"#fff",marginBottom:12}}>
          <p><strong>{c.criterionId}</strong> · 需求 {c.requirementId} · 规格方法：{c.method}</p><p>{c.expected}</p>
          <label>验收 {c.criterionId} 结论 <select aria-label={`验收 ${c.criterionId} 结论`} value={current.outcome} onChange={e=>criterion(c.criterionId,{outcome:e.target.value as ManualOutcome})}><option value="NOT_TESTED">未测试</option><option value="PASS">人工观察通过</option><option value="FAIL">人工观察失败</option></select></label>
          <p><label>验收 {c.criterionId} 观察说明 <textarea aria-label={`验收 ${c.criterionId} 观察说明`} value={current.evidenceNote} maxLength={4000} onChange={e=>criterion(c.criterionId,{evidenceNote:e.target.value})} style={{display:"block",width:"100%",minHeight:80}}/></label></p>
        </section>;})}
        <p><label>评审总结 <textarea aria-label="评审总结" value={draft.summary} maxLength={4000} onChange={e=>edit({summary:e.target.value})} style={{display:"block",width:"100%",minHeight:80}}/></label></p>
        <button disabled={!ready} onClick={()=>void action(()=>save("DRAFT"))}>保存评审草稿</button>{" "}
        <button disabled={!ready||!allPassed||!view.gate.candidateCurrent} onClick={()=>void action(()=>save("ACCEPTED"))}>接受此固定候选</button>{" "}
        <button disabled={!ready} onClick={()=>void action(()=>save("REJECTED"))}>拒绝此固定候选</button>
      </fieldset>}
      <p>评审者为本机操作者填写的标签，观察说明不等于自动测试证据；门禁以服务端重验为准，接受不会自动公开发布。</p>
      <button disabled={busy} onClick={()=>void action(reload)}>重新载入最新评审</button>{" "}
      {view.revision>0&&<><button disabled={busy} onClick={()=>void action(receipt)}>下载评审回执</button>{" "}<button disabled={busy} onClick={()=>historyOpen?setHistoryOpen(false):void action(()=>loadHistory())}>{historyOpen?"收起评审历史":"查看评审历史"}</button></>}
      {historyOpen&&<section aria-label="评审历史">{history.map(record=><details key={record.revision}><summary>r{record.revision} · {record.decision} · {record.reviewer}</summary><p>{record.recordedAt}</p><p>{record.summary}</p><p style={{overflowWrap:"anywhere"}}>记录指纹：{record.reviewHash}</p></details>)}{more&&<button disabled={busy} onClick={()=>void action(()=>loadHistory(true))}>加载更早记录</button>}</section>}
    </>}
  </section>;
}
