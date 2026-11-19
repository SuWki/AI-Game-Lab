import {ProductionCandidatePanel} from "./ProductionCandidatePanel";
import {SpecificationEditor} from "./SpecificationEditor";
import {DesignerSpecHandoff} from "./DesignerSpecHandoff";
import {BuilderBudgetPanel} from "@/BuilderBudgetPanel";
import {useState,useRef,useEffect} from "react";
import {useQuery,useQueryClient} from "react-query";
import {Link,useSearchParams} from "react-router-dom";
import {api, type ProductionArtifact,type ProductionJob} from "@/api";
const active=(s:string)=>["PREPARING","QUEUED","RUNNING"].includes(s);
const running=(job:ProductionJob)=>active(job.status)||active(job.validationStatus??"")
  ||job.iteration?.attempts.some(p=>["APPLYING","VALIDATING"].includes(p.status));
export function ProductionsPage(){
 const qc=useQueryClient();const [params,setParams]=useSearchParams();
 const taskId=params.get("taskId")??"",selection=params.get("spec")??"";
 const productionId=params.get("productionId"),candidateId=params.get("candidateId");
 const [error,setError]=useState("");const [busy,setBusy]=useState(false);
 const requestKey=useRef<string|null>(null);
 const taskRef=useRef(taskId);
 useEffect(()=>{taskRef.current=taskId;},[taskId]);
 const [designGoal,setDesignGoal]=useState("");
 const [goal,setGoal]=useState("生成所选规格支持的模板切片，并如实报告产物、未实现需求和验收边界。");const [message,setMessage]=useState("");
 const [workflowTask,setWorkflowTask]=useState<number>();
 const tasks=useQuery(["tasks"],api.listTasks);
 const artifacts=useQuery(["production-artifacts",taskId],()=>api.productionArtifacts(Number(taskId)),{enabled:!!taskId});
 const jobs=useQuery(["production-jobs",productionId],async()=>productionId?[await api.productionJob(productionId)]:api.productionJobs(),
   {refetchInterval:data=>data?.some(running)?1000:false});
 const specs=artifacts.data?.filter(a=>a.kind==="GameSpec")??[];
 const selected=specs.find(s=>`${s.artifactId}:${s.revision}`===selection);
 const scopeKey=["manufacturing-scope",selected?.artifactId,selected?.revision];
 const scope=useQuery(scopeKey,()=>selected?api.manufacturingScope(selected.artifactId,selected.revision):null,{enabled:!!selected});
 const questions=Array.isArray(selected?.content.openQuestions)?selected.content.openQuestions:[];
 const selectedTask=tasks.data?.find(task=>String(task.id)===taskId);
 const designerDraft=selectedTask && (()=>{try{return JSON.parse(selectedTask.constraintsJson || "{}").productionDraft===true;}catch{return false;}})();
 const confirmed=!!selected && scope.data?.contentHash===selected.contentHash && !scope.isError;

 function chooseTask(id:string){
   taskRef.current=id;
   setParams(id?{taskId:id}:{});
   requestKey.current=null;
 }

 async function savedSpec(saved:ProductionArtifact){
   await qc.invalidateQueries(["production-artifacts",String(saved.taskId)]);
   if(taskRef.current===String(saved.taskId)){
     setParams({taskId:String(saved.taskId),spec:`${saved.artifactId}:${saved.revision}`});
     requestKey.current=null;
   }
 }

 async function startDesign(){
   const design=designGoal.trim();
   if(!design)return;
   setBusy(true);setError("");setMessage("");
   try{
     const task=await api.createTask({title:"同类型游戏设计草稿",goal:design,agentType:"DESIGNER",productionDraft:true});
     chooseTask(String(task.id));
     await qc.invalidateQueries(["tasks"]);
     const runId=await api.startRun(task.id);
     setWorkflowTask(task.id);
     setMessage(`已启动 Designer Run #${runId}，执行完成后刷新设计结果。`);
     setDesignGoal("");
   }catch(e){setError(String(e));}finally{setBusy(false);}
 }

 async function confirmScope(){
   if(!selected || questions.length)return;
   setBusy(true);setError("");
   try{
     const receipt=await api.confirmManufacturingScope({
       taskId:selected.taskId,specId:selected.artifactId,revision:selected.revision,contentHash:selected.contentHash,
     });
     qc.setQueryData(scopeKey,receipt);
   }catch(e){setError(String(e));}finally{setBusy(false);}
 }
 function focusJob(job:ProductionJob){
   return {taskId:String(job.taskId),spec:`${job.specId}:${job.specRevision}`,productionId:job.id};
 }
 async function start(){if(!selected)return;setBusy(true);setError("");requestKey.current??=crypto.randomUUID();try{const job=await api.startProduction({taskId:selected.taskId,specId:selected.artifactId,specRevision:selected.revision,requestKey:requestKey.current});requestKey.current=null;setParams(focusJob(job));await qc.invalidateQueries(["production-jobs"]);}catch(e){setError(String(e));}finally{setBusy(false);}}
 async function refreshProduction(){await qc.invalidateQueries(["production-jobs"]);await qc.invalidateQueries(["candidate-review"]);}
 async function cancel(id:string){setBusy(true);setError("");try{await api.cancelProduction(id);await refreshProduction();}catch(e){setError(String(e));}finally{setBusy(false);}}
 async function validate(id:string){setBusy(true);setError("");try{await api.validateProduction(id);await qc.invalidateQueries(["production-jobs"]);}catch(e){setError(String(e));}finally{setBusy(false);}}
 async function createBuilder(){const spec=selected;if(!spec||!confirmed)return;setBusy(true);setError("");try{const t=await api.createBuilderTask({taskId:spec.taskId,specId:spec.artifactId,specRevision:spec.revision,goal});setWorkflowTask(t.id);setMessage(`已创建 Builder 任务 #${t.id}，可进入 Workflow 启动。`);await qc.invalidateQueries(["tasks"]);}catch(e){setError(String(e));}finally{setBusy(false);}}
 return <main style={{maxWidth:1100,margin:"24px auto"}}><h1>游戏生产</h1><p>选择已保存的游戏规格，使用 survival-web 模板生成独立项目。当前支持热量爆发与护盾冲刺。</p><p>BUILT 表示模板构建完成。独立验收覆盖六项引擎检查，不包含浏览器界面或全部规格。</p>
 {Boolean(error||tasks.error||artifacts.error||jobs.error)&&<p role="alert">{error||String(tasks.error||artifacts.error||jobs.error)}</p>}
 <form onSubmit={event=>{event.preventDefault();void startDesign();}}>
   <label>设计目标 <input value={designGoal} maxLength={6000} onChange={event=>setDesignGoal(event.target.value)}/></label>{" "}
   <button disabled={busy||!designGoal.trim()}>启动 Designer 规格任务</button>
 </form>
 <label>任务 <select aria-label="任务" value={taskId} disabled={busy} onChange={e=>chooseTask(e.target.value)}><option value="">选择任务</option>{tasks.data?.map(t=><option value={t.id} key={t.id}>{t.title} · #{t.id}</option>)}</select></label>
 <p><label>规格版本 <select aria-label="规格版本" value={selection} disabled={busy} onChange={e=>{setParams({taskId,spec:e.target.value});requestKey.current=null;}}><option value="">选择精确版本</option>{specs.map(s=><option key={`${s.artifactId}:${s.revision}`} value={`${s.artifactId}:${s.revision}`}>{s.content.title??s.artifactId} · r{s.revision}</option>)}</select></label></p>
 {taskId&&!artifacts.isLoading&&specs.length===0&&<p>此任务暂无 GameSpec。请先保存支持模板参数的规格。</p>}
 <button disabled={busy||!selected} onClick={()=>void start()}>生成游戏项目</button> <button onClick={()=>void refreshProduction()}>刷新生产记录</button>
 {productionId&&<p><Link to={{pathname:"/productions",search:new URLSearchParams({taskId,spec:selection}).toString()}}>查看所有生产记录</Link></p>}
 <p>直接生成游戏项目用于模板试做。启动 Builder 前需要确认制造范围。</p>
 {designerDraft&&<DesignerSpecHandoff key={`designer-${taskId}`} taskId={Number(taskId)} onSaved={savedSpec}/>}
 {taskId?<SpecificationEditor key={`specification-${taskId}`} taskId={Number(taskId)} selected={selected} onSaved={savedSpec}/>:<p>先选择任务，再编写规格。</p>}
 {selected&&<section aria-label="制造范围">
   <h2>制造范围 · r{selected.revision}</h2>
   <details><summary>查看选中规格的完整范围</summary><pre style={{whiteSpace:"pre-wrap",overflowWrap:"anywhere"}}>{JSON.stringify(selected.content,null,2)}</pre></details>
   {questions.length>0&&<p>还有 {questions.length} 项待解决问题。修订规格后可以确认。</p>}
   {scope.isError&&<p role="alert">读取制造确认失败。<button onClick={()=>void scope.refetch()}>重试</button></p>}
   {confirmed?<p>已确认此版本的制造范围。候选包仍需另外验收。</p>:
     <button disabled={busy||!scope.isSuccess||questions.length>0} onClick={()=>void confirmScope()}>确认制造范围 r{selected.revision}</button>}
 </section>}
 <p><label>Builder 目标 <input value={goal} onChange={e=>setGoal(e.target.value)}/></label> <button disabled={busy||!confirmed||!goal.trim()} onClick={()=>void createBuilder()}>创建 Builder 任务</button></p><p>Builder 支持受控模板、独立验收及需人工批准的源码提案，需要配置模型后在 Workflow 启动。</p>{message&&<p role="status">{message} <Link to={workflowTask ? `/workflow?taskId=${workflowTask}` : "/workflow"}>进入 Workflow</Link></p>}
 {jobs.data?.map(j=><section key={j.id} style={{border:"1px solid #ddd",padding:16,marginTop:16}}><h2>生产任务 · {j.status}</h2><p>{j.id} · 任务 #{j.taskId}</p><p>规格 {j.specId} · r{j.specRevision} · <Link to={{pathname:"/productions",search:new URLSearchParams(focusJob(j)).toString()}}>打开此生产</Link></p>{j.error&&<p role="alert">{j.error}</p>}{(active(j.status)||active(j.validationStatus??"")||(j.iteration&&!['CANCELLED','FAILED','INTERRUPTED'].includes(j.iteration.phase)))&&<button disabled={busy} onClick={()=>void cancel(j.id)}>取消生产</button>}{j.localRunId&&<p><Link to={`/local-tests?runId=${j.localRunId}`}>查看执行日志与证据</Link></p>}{j.status==="BUILT"&&(!j.validationRunId||j.validationStatus==="INTERRUPTED")&&j.iteration?.phase!=="CANCELLED"&&<button disabled={busy} onClick={()=>void validate(j.id)}>{j.validationStatus==="INTERRUPTED"?"重试中断的独立验收":"执行独立验收"}</button>}{j.validationRunId&&<p>独立引擎验收：{j.iteration?.currentDecision??j.validationDecision??j.validationStatus} · <Link to={`/local-tests?runId=${j.iteration?.currentRunId||j.validationRunId}`}>查看验收与修改源码</Link></p>}{j.budget&&<BuilderBudgetPanel budget={j.budget}/>}{j.iteration&&<section><h3>修复阶段 · {j.iteration.phase}</h3><p>提案次数：{j.iteration.repairCount} / {j.iteration.repairLimit}</p><p>{j.iteration.reason}</p>{j.iteration.currentWorkspace&&<p>当前候选目录：<code style={{overflowWrap:"anywhere"}}>{j.iteration.currentWorkspace}</code></p>}{j.iteration.attempts.map((p,i)=><p key={p.proposalId}>#{i+1} · {p.status} · <Link to={`/local-tests/changes?proposalId=${encodeURIComponent(p.proposalId)}`}>审阅本轮提案</Link> · {p.validationRunId&&<Link to={`/local-tests?runId=${p.validationRunId}`}>查看本轮回归</Link>}</p>)}</section>}<ProductionCandidatePanel job={j} selectedCandidateId={candidateId} candidateLink={id=>({pathname:"/productions",search:new URLSearchParams({...focusJob(j),candidateId:id}).toString()})} onPack={id=>setParams({...focusJob(j),candidateId:id})}/>{j.result&&<><p>初始项目目录：<code style={{overflowWrap:"anywhere"}}>{j.result.workspace}</code></p><p>初始构建：{j.result.build.buildId}</p><p>在该目录执行 <code>node serve-project.mjs 4180</code> 后打开 http://127.0.0.1:4180 试玩。</p><details><summary>构建指纹</summary><pre style={{whiteSpace:"pre-wrap",overflowWrap:"anywhere"}}>{JSON.stringify(j.result.build.files,null,2)}</pre></details></>}</section>)}</main>;
}
