import { useState,useEffect } from "react";
import { useQuery, useQueryClient } from "react-query";
import { api } from "@/api";
import { LocalEvidencePanel } from "./LocalEvidencePanel";
import { Link,useSearchParams } from "react-router-dom";

export function LocalTestsPage() {
  const qc=useQueryClient();
  const [params]=useSearchParams();const requestedId=params.get("runId");
  const [selected,setSelected]=useState<string>();
  useEffect(()=>{if(requestedId)setSelected(requestedId);},[requestedId]);
  const [busy,setBusy]=useState(false);
  const [error,setError]=useState("");
  const targets=useQuery(["local-test-targets"],api.localTestTargets);
  const runs=useQuery(["local-test-runs"],api.localTestRuns,{refetchInterval:data=>data?.some(r=>["QUEUED","RUNNING"].includes(r.status))?2000:false});
  const detail=useQuery(["local-test-run",selected],()=>api.localTestRun(selected!),{enabled:!!selected,refetchInterval:data=>data&&["QUEUED","RUNNING"].includes(data.status)?2000:false});
  async function action(id:string,cancel=false) {
    setBusy(true);setError("");
    try {
      const result=await (cancel?api.cancelLocalTest(id):api.startLocalTest(id));
      setSelected(result.id);
      await qc.invalidateQueries(["local-test-runs"]);
      await qc.invalidateQueries(["local-test-run",result.id]);
    }catch(e){setError(String(e));}finally{setBusy(false);}
  }
  const run=detail.data;
  return <main style={{maxWidth:1100,margin:"24px auto"}}>
    <h1>本地游戏测试</h1>
    <p><Link to="/local-tests/changes">修改提案与回归</Link></p>
    <button onClick={()=>{void targets.refetch();void runs.refetch();}}>刷新目标和任务</button>
    <p>选择已注册的游戏测试目标，执行后查看状态、日志和结果证据。COMPLETED 表示命令结束；PASSED 才表示结果文件报告通过。</p>
    {Boolean(error||targets.error||runs.error||detail.error)&&<p role="alert" style={{color:"#b91c1c"}}>{error||String(targets.error||runs.error||detail.error)}</p>}
    <h2>测试目标</h2>
    {targets.isLoading&&<p>加载中…</p>}
    {targets.data?.length===0&&<p>尚未注册目标。请在后端配置的 local-tests.catalog 文件中添加工作目录、命令和可选结果路径。</p>}
    {targets.data?.map(t=><section key={t.id} style={{border:"1px solid #ddd",padding:16,marginBottom:12}}>
      <strong>{t.name}</strong><p>{t.workingDirectory}</p>
      <code>{t.command.join(" ")}</code><p>超时：{t.timeoutSeconds} 秒 · 结果文件：{t.resultPath||"未配置"}</p>
      <button disabled={busy} onClick={()=>void action(t.id)}>运行测试</button>
    </section>)}
    <h2>最近任务</h2>
    <table style={{width:"100%",textAlign:"left"}}><thead><tr><th>目标</th><th>状态</th><th>提交时间</th><th>操作</th></tr></thead>
      <tbody>{runs.data?.map(r=><tr key={r.id}><td><button onClick={()=>setSelected(r.id)}>{r.target.name}</button></td><td>{r.status}</td><td>{r.startedAt}</td>
        <td>{["QUEUED","RUNNING"].includes(r.status)&&<button disabled={busy} onClick={()=>void action(r.id,true)}>取消</button>}</td></tr>)}</tbody></table>
    {run&&<section><h2>任务详情 · {run.status}</h2><p>{run.id} · 退出码：{run.exitCode??"无"}</p>
      {run.error&&<p role="alert">{run.error}</p>}
      <h3>输出日志（结束后可见，最多保留末尾 64K 字符）</h3><pre style={{whiteSpace:"pre-wrap",maxHeight:320,overflow:"auto",background:"#f3f4f6",padding:12}}>{run.output||"暂无输出"}</pre>
      <h3>结果证据</h3><pre style={{whiteSpace:"pre-wrap",maxHeight:500,overflow:"auto"}}>{run.evidence?JSON.stringify(run.evidence,null,2):"未提供有效结果证据"}</pre>
      {!!run.target.versionFiles?.length&&<LocalEvidencePanel key={run.id} run={run}/>}
    </section>}
  </main>;
}
