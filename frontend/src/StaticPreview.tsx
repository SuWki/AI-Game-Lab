import {useEffect, useState} from "react";
import protectedInput from "../../docs/reports/evidence/production-source-2026-11-16/protected-input.png";
import regression from "../../docs/reports/evidence/production-source-2026-11-16/isolated-regression.png";
import game from "../../docs/reports/evidence/production-source-2026-11-16/game/settlement.png";
import "./StaticPreview.css";

const repository="https://github.com/SuWki/AI-Game-Lab";
const modules=[
  {id:"designer",name:"Designer",label:"游戏设计",summary:"从目标到可审阅的设计变更。",features:["设计与配置读取、模拟实验","修改提案、人工审批与版本快照"],boundary:"现有模拟指标不等于真实游戏质量。",document:"docs/projects/01_AI_Game_Designer/README.md"},
  {id:"tester",name:"Tester",label:"测试与证据",summary:"让每个问题都能追溯到被测版本。",features:["本地命令执行、源码与产物指纹","Test Plan、Finding 与隔离回归"],boundary:"通用目标仍需配置对应的可信测试程序。",document:"docs/projects/02_AI_Game_Tester/README.md"},
  {id:"research",name:"Research",label:"资料研究",summary:"将外部资料整理成有来源的设计输入。",features:["搜索、页面抓取与来源记录","Claim、研究报告与设计交接"],boundary:"在线搜索与模型调用需要在本地配置服务。",document:"docs/projects/03_AI_Research_Agent/README.md"},
  {id:"qa",name:"QA Team",label:"质量评估",summary:"汇总不同角色的观察，保留裁决依据。",features:["QA_LEAD 与角色任务编排","Finding 过滤与 Release Gate"],boundary:"自动建议和人类最终接受是两个不同状态。",document:"docs/projects/04_AI_Game_Agent_Team/README.md"},
  {id:"builder",name:"Builder",label:"游戏生产",summary:"从固定规格生产，再通过受控提案迭代。",features:["survival-web 模板与两种资源机制","独立引擎验收、审批和隔离源码回归","会话预算、固定候选 ZIP 与限时预览"],boundary:"完整规格验收、人类质量接受和正式发布流程仍在开发。",document:"docs/projects/05_AI_Auto_Game_Developer/README.md"},
];
function selectedFromHash(){return modules.some(m=>"#"+m.id===window.location.hash)?window.location.hash.slice(1):"builder";}

/** Pages serves this explicit preview; it never mounts live API queries or mutation controls. */
export function StaticPreview(){
  const [selected,setSelected]=useState(selectedFromHash);
  useEffect(()=>{const update=()=>setSelected(selectedFromHash());window.addEventListener("hashchange",update);return()=>window.removeEventListener("hashchange",update);},[]);
  const current=modules.find(m=>m.id===selected)!;
  return <main className="static-preview">
    <header className="preview-header"><a href="#builder" className="preview-brand">AGL <span>AI Game Lab</span></a><span className="preview-tag">静态前端预览</span><a href={repository}>查看源码 ↗</a></header>
    <section className="preview-hero"><p className="preview-eyebrow">DESIGN · TEST · ITERATE</p><h1>理解游戏，<br/>再把它做得更好。</h1><p>一个共用证据、审批与执行流程的游戏 AI 工作平台。分析已有游戏，也从模板设计和制造同类型作品。</p><div className="preview-actions"><a className="preview-primary" href="http://127.0.0.1:5173">打开本地平台 ↗</a><a href={repository+"#readme"}>本地启动说明 ↗</a></div></section>
    <aside className="preview-notice"><strong>这里是只读能力与界面预览。</strong> GitHub Pages 仅托管静态文件。任务、模型调用、测试执行与数据库由本地 Java 后端提供；本页不连接后端，也不展示虚构的运行数据。</aside>
    <section aria-labelledby="preview-modules"><div className="preview-section-title"><h2 id="preview-modules">五个模块，一条迭代流程</h2><span>当前能力与边界</span></div>
      <nav aria-label="模块预览" className="preview-modules">{modules.map(m=><a key={m.id} href={"#"+m.id} aria-current={selected===m.id?"page":undefined}><span>{m.name}</span><strong>{m.label}</strong></a>)}</nav>
      <article className="preview-detail"><div><p className="preview-eyebrow">{current.name}</p><h3>{current.summary}</h3><ul>{current.features.map(f=><li key={f}>{f}</li>)}</ul></div><div className="preview-boundary"><strong>实施边界</strong><p>{current.boundary}</p><a href={repository+"/blob/main/"+current.document}>阅读模块文档 ↗</a></div></article>
    </section>
    <section><div className="preview-section-title"><h2>从故障到修复的真实记录</h2><span>2026-11-16 · 本地受控实验</span></div><p>在生成的临时游戏中刻意注入移动故障，独立验收发现失败；人工批准修复提案后，在隔离工作区回归通过。以下是操作截图，不是在线任务。</p>
      <ol className="preview-steps"><li>生成项目</li><li>独立验收与 Finding</li><li>审阅并批准提案</li><li>隔离回归</li></ol>
      <div className="preview-gallery"><figure><a href={protectedInput}><img src={protectedInput} alt="本地测试失败证据与只读的受保护配置文件" loading="lazy"/></a><figcaption>固定基线与受保护输入</figcaption></figure><figure><a href={regression}><img src={regression} alt="源码修改提案在独立工作区验证通过" loading="lazy"/></a><figcaption>人工审批后的源码回归</figcaption></figure><figure><a href={game}><img src={game} alt="修复候选在实际浏览器中自然结算" loading="lazy"/></a><figcaption>候选游戏的浏览器烟测</figcaption></figure></div>
      <a href={repository+"/blob/main/docs/reports/PRODUCTION_SOURCE_REVIEW_2026-11-16.md"}>查看完整验证记录与限制 ↗</a>
    </section>
    <footer>AI Game Lab · 单机平台，持续开发中 <a href={repository+"/blob/main/docs/design/DEVELOPMENT_ROADMAP.md"}>开发路线 ↗</a></footer>
  </main>;
}
