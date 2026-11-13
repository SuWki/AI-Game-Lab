import {useState,useRef} from "react";
import {useQuery,useQueryClient} from "react-query";
import {Link} from "react-router-dom";
import {api} from "@/api";
const active=(s:string)=>["PREPARING","QUEUED","RUNNING"].includes(s);
export function ProductionsPage(){
 const qc=useQueryClient();const [taskId,setTaskId]=useState("");const [selection,setSelection]=useState("");const [error,setError]=useState("");const [busy,setBusy]=useState(false);
 const requestKey=useRef<string|null>(null);
 const [goal,setGoal]=useState("生成所选规格支持的模板切片，并如实报告产物、未实现需求和验收边界。");const [message,setMessage]=useState("");
 const tasks=useQuery(["tasks"],api.listTasks);
 const artifacts=useQuery(["production-artifacts",taskId],()=>api.productionArtifacts(Number(taskId)),{enabled:!!taskId});
 const jobs=useQuery(["production-jobs"],api.productionJobs,{refetchInterval:data=>data?.some(j=>active(j.status))?1000:false});
 const specs=artifacts.data?.filter(a=>a.kind==="GameSpec")??[];
 async function start(){const spec=specs.find(s=>`${s.artifactId}:${s.revision}`===selection);if(!spec)return;setBusy(true);setError("");requestKey.current??=crypto.randomUUID();try{await api.startProduction({taskId:spec.taskId,specId:spec.artifactId,specRevision:spec.revision,requestKey:requestKey.current});requestKey.current=null;await qc.invalidateQueries(["production-jobs"]);}catch(e){setError(String(e));}finally{setBusy(false);}}
 async function cancel(id:string){setBusy(true);setError("");try{await api.cancelProduction(id);await qc.invalidateQueries(["production-jobs"]);}catch(e){setError(String(e));}finally{setBusy(false);}}
 async function createBuilder(){const spec=specs.find(s=>`${s.artifactId}:${s.revision}`===selection);if(!spec)return;setBusy(true);setError("");try{const t=await api.createBuilderTask({taskId:spec.taskId,specId:spec.artifactId,specRevision:spec.revision,goal});setMessage(`已创建 Builder 任务 #${t.id}，可进入 Workflow 启动。`);await qc.invalidateQueries(["tasks"]);}catch(e){setError(String(e));}finally{setBusy(false);}}
 return <main style={{maxWidth:1100,margin:"24px auto"}}><h1>游戏生产</h1><p>选择已保存的游戏规格，使用 survival-web 模板生成独立项目。当前支持热量爆发与护盾冲刺。</p><p>BUILT 表示模板构建完成；完整规格验收与模型自主实现尚未接入。</p>
 {Boolean(error||tasks.error||artifacts.error||jobs.error)&&<p role="alert">{error||String(tasks.error||artifacts.error||jobs.error)}</p>}
 <label>任务 <select aria-label="任务" value={taskId} onChange={e=>{setTaskId(e.target.value);setSelection("");requestKey.current=null;}}><option value="">选择任务</option>{tasks.data?.map(t=><option value={t.id} key={t.id}>{t.title} · #{t.id}</option>)}</select></label>
 <p><label>规格版本 <select aria-label="规格版本" value={selection} onChange={e=>{setSelection(e.target.value);requestKey.current=null;}}><option value="">选择精确版本</option>{specs.map(s=><option key={`${s.artifactId}:${s.revision}`} value={`${s.artifactId}:${s.revision}`}>{s.content.title??s.artifactId} · r{s.revision}</option>)}</select></label></p>
 {taskId&&!artifacts.isLoading&&specs.length===0&&<p>此任务暂无 GameSpec。请先保存支持模板参数的规格。</p>}
 <button disabled={busy||!selection} onClick={()=>void start()}>生成游戏项目</button> <button onClick={()=>void jobs.refetch()}>刷新生产记录</button>
 <p><label>Builder 目标 <input value={goal} onChange={e=>setGoal(e.target.value)}/></label> <button disabled={busy||!selection||!goal.trim()} onClick={()=>void createBuilder()}>创建 Builder 任务</button></p><p>Builder 目前使用受控模板工具，需要配置模型后在 Workflow 启动；不开放任意文件修改。</p>{message&&<p role="status">{message} <Link to="/workflow">进入 Workflow</Link></p>}
 {jobs.data?.map(j=><section key={j.id} style={{border:"1px solid #ddd",padding:16,marginTop:16}}><h2>生产任务 · {j.status}</h2><p>{j.id} · 任务 #{j.taskId}</p><p>规格 {j.specId} · r{j.specRevision}</p>{j.error&&<p role="alert">{j.error}</p>}{active(j.status)&&<button disabled={busy} onClick={()=>void cancel(j.id)}>取消生产</button>}{j.localRunId&&<p><Link to={`/local-tests?runId=${j.localRunId}`}>查看执行日志与证据</Link></p>}{j.result&&<><p>项目目录：<code style={{overflowWrap:"anywhere"}}>{j.result.workspace}</code></p><p>构建：{j.result.build.buildId}</p><p>在该目录执行 <code>node serve-project.mjs 4180</code> 后打开 http://127.0.0.1:4180 试玩。</p><details><summary>构建指纹</summary><pre style={{whiteSpace:"pre-wrap",overflowWrap:"anywhere"}}>{JSON.stringify(j.result.build.files,null,2)}</pre></details></>}</section>)}</main>;
}
