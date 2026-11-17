import {useState,useEffect} from "react";
import {useQuery,useQueryClient} from "react-query";
import {api,type ProductionJob,type CandidatePreview} from "@/api";

export function ProductionCandidatePanel({job}:{job:ProductionJob}){
  const qc=useQueryClient(),list=useQuery(["production-candidates",job.id],()=>api.productionCandidates(job.id));
  const [busy,setBusy]=useState(false),[error,setError]=useState(""),[message,setMessage]=useState("");
  const [previews,setPreviews]=useState<Record<string,CandidatePreview>>({});
  useEffect(()=>{
    if(!Object.keys(previews).length)return;
    const timer=setInterval(()=>setPreviews(previous=>{
      const entries=Object.entries(previous).filter(([,p])=>Date.parse(p.expiresAt??"")>Date.now());
      return entries.length===Object.keys(previous).length?previous:Object.fromEntries(entries);
    }),1000);return()=>clearInterval(timer);
  },[previews]);
  async function action(run:()=>Promise<void>){setBusy(true);setError("");try{await run();}catch(e){setError(String(e));}finally{setBusy(false);}}
  async function pack(){const id=job.iteration?.currentRunId;if(!id)return;await api.packageCandidate(job.id,id);await qc.invalidateQueries(["production-candidates",job.id]);setMessage("固定候选已打包，未公开发布，也未记录人类质量接受。");}
  async function download(id:string){const blob=await api.downloadCandidate(id),url=URL.createObjectURL(blob);const a=document.createElement("a");a.href=url;a.download=`agl-candidate-${id}.zip`;a.click();setTimeout(()=>URL.revokeObjectURL(url),1000);}
  async function preview(id:string){const result=await api.startCandidatePreview(id);setPreviews(previous=>({...previous,[id]:result}));}
  async function stop(id:string){await api.stopCandidatePreview(id);setPreviews(previous=>{const next={...previous};delete next[id];return next;});}
  return <section aria-label="固定候选与交付" style={{borderTop:"1px solid #ddd",marginTop:16,paddingTop:12}}>
    <h3>固定候选与交付</h3><p>打包当前通过引擎验收的源码、实际构建与证据。下载和预览读取固定副本，原项目保持不变。</p>
    {job.iteration?.phase==="REVIEW"&&job.iteration.currentRunId&&<button disabled={busy} onClick={()=>void action(pack)}>固定当前候选并打包</button>}
    {Boolean(error||list.error)&&<p role="alert">{error||String(list.error)}</p>}{message&&<p role="status">{message}</p>}
    {list.data?.map(candidate=><article key={candidate.id} style={{padding:12,marginTop:12,background:"#f8faf9"}}>
      <h4>候选 {candidate.id} · {candidate.status}</h4><p>验收执行：{candidate.validationRunId}</p>
      {candidate.error&&<p role="alert">{candidate.error}</p>}
      {candidate.status==="READY"&&<><p>范围：{candidate.manifest?.scope} · 仅完成引擎验收，人类接受尚未记录。</p><p>固定构建：{candidate.manifest?.buildId}</p>
        <details><summary>清单与归档指纹</summary><p style={{overflowWrap:"anywhere"}}>Manifest SHA256：{candidate.manifestHash}</p><p style={{overflowWrap:"anywhere"}}>ZIP SHA256：{candidate.archiveSha256} · {candidate.archiveBytes} bytes</p></details>
        <button disabled={busy} onClick={()=>void action(()=>download(candidate.id))}>下载候选 ZIP</button>{" "}<button disabled={busy} onClick={()=>void action(()=>preview(candidate.id))}>启动限时预览</button>
        {previews[candidate.id]?.url&&<p><a href={previews[candidate.id].url} target="_blank" rel="noopener noreferrer">打开候选预览</a> · 到期：{new Date(previews[candidate.id].expiresAt!).toLocaleTimeString()} <button disabled={busy} onClick={()=>void action(()=>stop(candidate.id))}>停止预览</button></p>}
      </>}
    </article>)}
  </section>;
}
