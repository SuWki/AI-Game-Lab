import {useState} from "react";
import {useQuery,useQueryClient} from "react-query";
import {Link} from "react-router-dom";
import {api} from "@/api";
export function LocalChangesPage(){
 const qc=useQueryClient();const [busy,setBusy]=useState(false);const [error,setError]=useState("");
 const proposals=useQuery(["local-changes"],api.localChanges,{refetchInterval:data=>data?.some(p=>["APPLYING","VALIDATING"].includes(p.status))?2000:false});
 async function decide(id:string,decision:"approve"|"reject"){setBusy(true);setError("");try{await api.decideLocalChange(id,decision);await qc.invalidateQueries(["local-changes"]);await qc.invalidateQueries(["local-test-runs"]);}catch(e){setError(String(e));}finally{setBusy(false);}}
 return <main style={{maxWidth:1100,margin:"24px auto"}}><h1>修改提案与回归</h1><Link to="/local-tests">返回本地测试</Link><p>批准后将修改应用到独立工作区并执行注册的测试命令。查看完整差异后再批准；验证通过的工作区可用于后续人工合并。</p>
  <button onClick={()=>void proposals.refetch()}>刷新提案</button>{Boolean(error||proposals.error)&&<p role="alert">{error||String(proposals.error)}</p>}
  {proposals.data?.length===0&&<p>暂无修改提案。先从已绑定版本的测试结果中提出修改。</p>}
  {proposals.data?.map(p=><section key={p.id} style={{border:"1px solid #ddd",padding:16,marginTop:16}}><h2>{p.title} · {p.status}</h2><p>基线测试：<Link to={`/local-tests?runId=${p.baseRunId}`}>{p.baseRunId}</Link></p>
   {p.edits.map(edit=><details key={edit.path} open><summary>{edit.path} · 基础 SHA256：{edit.beforeSha256??"新增文件"}</summary><div style={{display:"grid",gridTemplateColumns:"1fr 1fr",gap:12}}><pre style={{whiteSpace:"pre-wrap",overflowWrap:"anywhere",background:"#fff1f2"}}>{edit.beforeText??"（新文件）"}</pre><pre style={{whiteSpace:"pre-wrap",overflowWrap:"anywhere",background:"#ecfdf5"}}>{edit.afterText}</pre></div></details>)}
   {p.status==="PENDING"&&<><button disabled={busy} onClick={()=>void decide(p.id,"approve")}>批准并隔离验证</button><button disabled={busy} onClick={()=>void decide(p.id,"reject")}>拒绝提案</button></>}
   {p.workspace&&<p style={{overflowWrap:"anywhere"}}>验证工作区：{p.workspace}</p>}{p.validationRunId&&<p>回归测试：<Link to={`/local-tests?runId=${p.validationRunId}`}>{p.validationRunId}</Link></p>}{p.error&&<p role="alert">{p.error}</p>}
  </section>)}
 </main>;
}
