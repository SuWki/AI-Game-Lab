import {useState} from "react";
import {useQuery,useQueryClient} from "react-query";
import {Link} from "react-router-dom";
import {api,type LocalTestRun} from "@/api";

export function LocalEvidencePanel({run}:{run:LocalTestRun}) {
  const qc=useQueryClient();
  const review=useQuery(["local-assessment",run.id,run.status],()=>api.localAssessment(run.id),{enabled:!["QUEUED","RUNNING"].includes(run.status)});
  const [path,setPath]=useState("");const [original,setOriginal]=useState<{text:string;sha256:string}|null>(null);
  const [after,setAfter]=useState("");const [title,setTitle]=useState("");const [message,setMessage]=useState("");const [error,setError]=useState("");const [busy,setBusy]=useState(false);
  const [agentGoal,setAgentGoal]=useState("");
  async function loadSource() {setBusy(true);setError("");try{const file=await api.localSource(run.id,path);setOriginal(file);setAfter(file.text);}catch(e){setError(String(e));}finally{setBusy(false);}}
  async function importChecks(){setBusy(true);setError("");try{await api.importLocalChecks(run.id);await qc.invalidateQueries(["local-assessment",run.id]);await qc.invalidateQueries(["findings"]);setMessage("测试计划与 Finding 已导入。");}catch(e){setError(String(e));}finally{setBusy(false);}}
  async function propose(){if(!original)return;setBusy(true);setError("");try{const p=await api.createLocalChange({baseRunId:run.id,title,edits:[{path,beforeSha256:original.sha256,afterText:after}]});setMessage(`修改提案已生成：${p.id}，等待审阅批准。`);setOriginal(null);await qc.invalidateQueries(["local-changes"]);}catch(e){setError(String(e));}finally{setBusy(false);}}
  const assessment=review.data;
  async function createAgent(type:"DESIGNER"|"TESTER") {setBusy(true);setError("");try{const task=await api.createLocalAgentTask(run.id,type,agentGoal);setMessage(`已创建 ${type} 任务 #${task.id}，可在 Workflow 中启动。`);await qc.invalidateQueries(["tasks"]);}catch(e){setError(String(e));}finally{setBusy(false);}}
  return <section style={{border:"1px solid #ddd",padding:16,marginTop:16}}>
    <h3>QA 证据与被测版本</h3>
    {Boolean(error||review.error)&&<p role="alert">{error||String(review.error)}</p>}{message&&<p role="status">{message}</p>}
    {assessment&&<><p>判定：<strong>{assessment.decision}</strong> · 失败检查：{assessment.failedChecks??"未知"} / {assessment.totalChecks??"未知"}</p>
      <p>{assessment.reason}</p><p style={{overflowWrap:"anywhere"}}>版本：{assessment.binding.version??"未绑定源码版本"}</p>
      <details><summary>源码与产物指纹</summary><pre style={{whiteSpace:"pre-wrap",overflowWrap:"anywhere"}}>{JSON.stringify({source:assessment.binding.source,artifacts:assessment.binding.artifacts},null,2)}</pre></details>
      <button disabled={busy||assessment.decision==="INCONCLUSIVE"} onClick={()=>void importChecks()}>导入测试计划与 Finding</button>
      <h4>交给 AI 分析或测试</h4><p>任务只授权当前目标和本次证据。修改仍需人工批准。</p>
      <label>任务目标 <input value={agentGoal} onChange={e=>setAgentGoal(e.target.value)}/></label>
      <button disabled={busy||!agentGoal.trim()} onClick={()=>void createAgent("DESIGNER")}>创建 AI 分析任务</button>
      <button disabled={busy||!agentGoal.trim()} onClick={()=>void createAgent("TESTER")}>创建 AI 测试任务</button><p><Link to="/workflow">进入 Workflow 启动任务</Link></p>
      {assessment.binding.findingIds?.map(id=><p key={id}><Link to={`/findings/${id}`}>查看 Finding #{id}</Link></p>)}
      {assessment.decision!=="INCONCLUSIVE"&&<><h4>提出文件修改</h4><p>读取本次测试的源文件，生成修改提案。批准后在独立工作区验证。</p>
        <label>源文件 <select value={path} onChange={e=>{setPath(e.target.value);setOriginal(null);}}><option value="">选择文件</option>{Object.keys(assessment.binding.source).map(name=><option key={name}>{name}</option>)}</select></label>
        <button disabled={busy||!path} onClick={()=>void loadSource()}>读取源文件</button>
        {original&&<><p><label>提案标题 <input value={title} onChange={e=>setTitle(e.target.value)}/></label></p>
          <div style={{display:"grid",gridTemplateColumns:"1fr 1fr",gap:12}}><label>修改前<textarea readOnly value={original.text} style={{width:"100%",height:240}}/></label><label>修改后<textarea value={after} onChange={e=>setAfter(e.target.value)} style={{width:"100%",height:240}}/></label></div>
          <button disabled={busy||!title.trim()||after===original.text} onClick={()=>void propose()}>生成修改提案</button></>}
      </>}
    </>}
    <p><Link to="/local-tests/changes">审阅修改提案与回归结果</Link></p>
  </section>;
}
