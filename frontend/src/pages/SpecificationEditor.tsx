import {useState} from "react";
import {api,type ProductionArtifact} from "@/api";

type Criterion={id:string;requirementId:string;expected:string;method:string};
type Requirement={id:string;description:string;acceptanceIds:string[]};
type SpecContent=Record<string,unknown>&{title:string;coreLoop:string;endConditions:string;restartBehavior:string;requirements:Requirement[];acceptanceCriteria:Criterion[];systems:{survivalWeb:{mechanic:string;duration:number;enemySpeed:number;spawnInterval:number;seed:number};[key:string]:unknown}};
function initial():SpecContent{return {title:"",genre:"survival",targetPlatform:"web",coreLoop:"移动、自动射击并存活到回合结束",endConditions:"达到时限获胜，生命耗尽失败",restartBehavior:"重开后生命、得分和时间恢复初始值",controls:["WASD / 方向键移动","空格使用技能"],states:["playing","victory","defeat"],contentScope:["单场景可玩切片"],assetNeeds:[],exclusions:["存档、多人和正式美术"],openQuestions:[],systems:{survivalWeb:{mechanic:"heat-burst",duration:20,enemySpeed:70,spawnInterval:1,seed:42}},requirements:[{id:"R1",description:"游戏能开始、结束并重新开始",acceptanceIds:["A1"]}],acceptanceCriteria:[{id:"A1",requirementId:"R1",expected:"可自然结算；点击重新开始后生命为100，得分和时间清零",method:"browser"}]};}
function editable(artifact:ProductionArtifact){const c=artifact.content as SpecContent;return c.targetPlatform==="web"&&["heat-burst","shield-dash"].includes(c.systems?.survivalWeb?.mechanic)&&Array.isArray(c.requirements)&&Array.isArray(c.acceptanceCriteria);}

export function SpecificationEditor({taskId,selected,onSaved}:{taskId:number;selected?:ProductionArtifact;onSaved:(artifact:ProductionArtifact)=>Promise<void>}){
 const [content,setContent]=useState<SpecContent>(initial),[base,setBase]=useState<ProductionArtifact|null>(null),[scope,setScope]=useState("单场景浏览器生存游戏，先验证可玩切片"),[player,setPlayer]=useState("休闲玩家");
 const [busy,setBusy]=useState(false),[error,setError]=useState(""),[message,setMessage]=useState("");
 const [editing,setEditing]=useState(false);
 function change(patch:Partial<SpecContent>){setContent(c=>({...c,...patch}));}
 function load(){if(!selected||!editable(selected)){setError("此规格不支持当前 survival-web 表单，请保留原版本使用已有接口编辑。");return;}setBase(selected);setContent(structuredClone(selected.content) as SpecContent);setEditing(true);setError("");setMessage("");}
 async function save(){setBusy(true);setError("");setMessage("");try{
  if(!content.requirements.length||!content.acceptanceCriteria.length)throw Error("至少需要一个需求及验收项");
  let refs=base?.inputRefs??[],briefRef=content.briefRef;
  if(!base){const brief=await api.saveProductionArtifact({taskId,kind:"ProductionBrief",content:{title:content.title,mode:"NEW",genre:content.genre,targetPlatform:"web",targetPlayer:player,scope},inputRefs:[]});briefRef=brief.artifactId;refs=[{id:brief.artifactId,revision:brief.revision,hash:brief.contentHash}];}
  const saved=await api.saveProductionArtifact({taskId,artifactId:base?.artifactId,expectedRevision:base?.revision??0,kind:"GameSpec",content:{...content,briefRef},inputRefs:refs});
  setBase(saved);setContent(structuredClone(saved.content) as SpecContent);setMessage(`已保存规格 r${saved.revision}。保存不代表已验收；生产只使用明确选中的版本。`);await onSaved(saved);
 }catch(e){setError(String(e));}finally{setBusy(false);}}
 function addRequirement(){let n=1;while(content.requirements.some(r=>r.id===`R${n}`)||content.acceptanceCriteria.some(a=>a.id===`A${n}`))n++;change({requirements:[...content.requirements,{id:`R${n}`,description:"",acceptanceIds:[`A${n}`]}],acceptanceCriteria:[...content.acceptanceCriteria,{id:`A${n}`,requirementId:`R${n}`,expected:"",method:"browser"}]});}
 return <section aria-label="规格编辑器" style={{border:"1px solid #ddd",padding:16,marginTop:16}}><h2>编写游戏规格</h2>
 <p>支持 survival-web 的热量爆发与护盾冲刺。需求是验收目标，模板不会自动实现所有描述；保存后可交给 Builder，产物仍需逐项验收。</p>
 {!editing&&<><button onClick={()=>{setContent(initial());setBase(null);setEditing(true);setError("");setMessage("");}}>新建规格</button>{" "}<button disabled={!selected} onClick={load}>编辑选中规格</button></>}
 {error&&<p role="alert">{error} 编辑内容已保留。</p>}{message&&<p role="status">{message}</p>}
 {editing&&<form onSubmit={e=>{e.preventDefault();void save();}}><fieldset disabled={busy} style={{minWidth:0}}><legend>{base?`修订 ${base.artifactId} · 基于 r${base.revision}`:"新建原创规格"}</legend>
 <p>修订会追加版本，不替换已有生产使用的规格。保存冲突后请保留内容，在关闭编辑后重新选择最新版本载入。</p>
 {([['title','游戏名称'],['coreLoop','核心玩法'],['endConditions','结束条件'],['restartBehavior','重开预期']] as const).map(([key,label])=><p key={key}><label>{label} <input aria-label={label} required maxLength={key==='title'?120:4000} value={content[key]} onChange={e=>change({[key]:e.target.value})} style={{width:"100%",boxSizing:"border-box"}}/></label></p>)}
 {!base&&<><p><label>目标玩家 <input aria-label="目标玩家" required value={player} onChange={e=>setPlayer(e.target.value)}/></label></p><p><label>首轮范围 <textarea aria-label="首轮范围" required value={scope} onChange={e=>setScope(e.target.value)}/></label></p></>}
 <p><label>核心机制 <select aria-label="核心机制" value={content.systems.survivalWeb.mechanic} onChange={e=>change({systems:{...content.systems,survivalWeb:{...content.systems.survivalWeb,mechanic:e.target.value}}})}><option value="heat-burst">热量爆发</option><option value="shield-dash">护盾冲刺</option></select></label></p>
 {([['duration','回合时长（秒）',1,300,1],['enemySpeed','敌人速度',20,180,1],['spawnInterval','生成间隔（秒）',.3,3,.1],['seed','随机种子',0,4294967295,1]] as const).map(([key,label,min,max,step])=><p key={key}><label>{label} <input aria-label={label} type="number" required min={min} max={max} step={step} value={Number.isFinite(content.systems.survivalWeb[key])?content.systems.survivalWeb[key]:""} onChange={e=>change({systems:{...content.systems,survivalWeb:{...content.systems.survivalWeb,[key]:e.target.value===''?NaN:Number(e.target.value)}}})}/></label></p>)}
 <p><label>待解决问题（每行一项） <textarea rows={3}
   style={{display:"block",width:"100%",boxSizing:"border-box"}}
   value={Array.isArray(content.openQuestions)?content.openQuestions.map(item=>typeof item==="string"?item:JSON.stringify(item)).join("\n"):""}
   onChange={event=>change({openQuestions:event.target.value===""?[]:event.target.value.split("\n")})}/></label></p>
 <h3>需求与验收</h3>{content.requirements.map(r=><section key={r.id} style={{padding:12,borderTop:"1px solid #ddd"}}><p><label>需求 {r.id} <input aria-label={`需求 ${r.id}`} style={{width:"100%",boxSizing:"border-box"}} required value={r.description} onChange={e=>change({requirements:content.requirements.map(row=>row.id===r.id?{...row,description:e.target.value}:row)})}/></label></p>
 {content.acceptanceCriteria.filter(a=>a.requirementId===r.id).map(a=><p key={a.id}><label>验收 {a.id} 预期 <textarea aria-label={`验收 ${a.id} 预期`} rows={3} style={{display:"block",width:"100%",boxSizing:"border-box"}} required value={a.expected} onChange={e=>change({acceptanceCriteria:content.acceptanceCriteria.map(row=>row.id===a.id?{...row,expected:e.target.value}:row)})}/></label> · 方法：{a.method}</p>)}
 <button type="button" disabled={content.requirements.length===1} onClick={()=>change({requirements:content.requirements.filter(row=>row.id!==r.id),acceptanceCriteria:content.acceptanceCriteria.filter(a=>a.requirementId!==r.id)})}>删除需求 {r.id}</button></section>)}
 <p><button type="button" onClick={addRequirement}>添加需求及验收项</button></p><button type="submit">保存规格版本</button>{" "}<button type="button" onClick={()=>setEditing(false)}>关闭编辑</button>
 </fieldset></form>}</section>;
}
