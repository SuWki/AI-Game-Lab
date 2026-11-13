import fs from 'node:fs';
import path from 'node:path';
import { fileURLToPath } from 'node:url';
import { spawn } from 'node:child_process';

const root=path.resolve(process.argv[2]||'');
const port=Number(process.argv[3]||4174);
if(!process.argv[2]||!Number.isInteger(port)||port<1024||port>65535)throw new Error('Usage: node local-suite.mjs <game-directory> [port 1024..65535]');
const scripts=path.dirname(fileURLToPath(import.meta.url));
const out=path.join(root,'output/qa/local-suite',new Date().toISOString().replace(/[:.]/g,'-'));
fs.mkdirSync(out,{recursive:true});
const url=`http://127.0.0.1:${port}/game-demo/`;
const report={url,startedAt:new Date().toISOString(),status:'RUNNING',mode:'Browser DOM handlers and QA fixtures; not natural gameplay',checks:[]};
const server=spawn(process.execPath,[path.join(root,'node_modules/vite/bin/vite.js'),'preview','--host','127.0.0.1','--port',String(port),'--strictPort'],{cwd:root,windowsHide:true,stdio:['ignore','pipe','pipe']});
let serverLog='',serverError;
server.stdout.on('data',d=>{serverLog+=d});server.stderr.on('data',d=>{serverLog+=d});
server.on('error',e=>{serverError=e});
async function waitReady(){
 const deadline=Date.now()+20000;
 while(Date.now()<deadline){
  if(serverError)throw serverError;
  if(server.exitCode!==null)throw new Error('Preview exited: '+serverLog);
  // Require this process to announce readiness; never test another server on a busy port.
  if(serverLog.includes(`127.0.0.1:${port}`)){
   const response=await fetch(url,{signal:AbortSignal.timeout(3000)});
   if(response.ok)return;
  }
  await new Promise(resolve=>setTimeout(resolve,100));
 }
 throw new Error('Preview startup timeout: '+serverLog);
}
async function runScript(file,env){
 const child=spawn(process.execPath,[path.join(root,'tools',file)],{cwd:root,windowsHide:true,env:{...process.env,...env},stdio:['ignore','pipe','pipe']});
 let log='';child.stdout.on('data',d=>{log+=d});child.stderr.on('data',d=>{log+=d});
 const timer=setTimeout(()=>child.kill(),180000);
 try{return await new Promise((resolve,reject)=>{
  child.on('error',reject);child.on('close',(code,signal)=>{
   fs.writeFileSync(path.join(out,file+'.log'),log);
   if(code===0)resolve();else reject(new Error(`${file} failed: exit=${code} signal=${signal}; see ${out}`));
  });
 });}finally{clearTimeout(timer);}
}
try{
 for(const file of ['agl-browser-check.mjs','agl-local-results.mjs'])fs.copyFileSync(path.join(scripts,file),path.join(root,'tools',file));
 await waitReady();
 for(const [file,subdir,key] of [['agl-browser-check.mjs','interaction','AGL_BROWSER_OUT'],['agl-local-results.mjs','results','AGL_RESULTS_OUT']]){
  const directory=path.join(out,subdir);
  await runScript(file,{AGL_GAME_URL:url,AGL_EXPECT_REVIEW:process.argv[4]==='--expect-review'?'1':'0',[key]:directory});
  const result=JSON.parse(fs.readFileSync(path.join(directory,'summary.json'),'utf8'));
  if(result.failure||result.errors?.length||result.checks?.length!==6||result.checks.some(c=>!c.pass))throw new Error('Incomplete browser evidence: '+file);
  for(const check of result.checks)report.checks.push({
   id:`${subdir}:${check.name||`${check.routeId}:${check.resultMode}`}`,
   title:check.name||`${check.routeId} ${check.resultMode}`,
   status:'PASS',expected:'Browser scenario assertions pass',actual:'Assertions passed',
   suite:subdir,evidence:`${subdir}/summary.json`,
  });
 }
 report.status='PASS';report.coverageComplete=true;
}catch(e){report.status='FAIL';report.failure=String(e);process.exitCode=1;
}finally{
 server.kill();
 if(server.exitCode===null)await Promise.race([new Promise(resolve=>server.once('exit',resolve)),new Promise(resolve=>setTimeout(resolve,3000))]);
 report.finishedAt=new Date().toISOString();
 fs.writeFileSync(path.join(out,'preview.log'),serverLog);
 fs.writeFileSync(path.join(out,'summary.json'),JSON.stringify(report,null,2));
 if(process.env.AGL_TEST_RESULT_PATH){
  fs.mkdirSync(path.dirname(process.env.AGL_TEST_RESULT_PATH),{recursive:true});
  fs.writeFileSync(process.env.AGL_TEST_RESULT_PATH,JSON.stringify({...report,runId:process.env.AGL_TEST_RUN_ID,reportPath:path.join(out,'summary.json')},null,2));
 }
 const escape=value=>String(value).replace(/[&<>"']/g,c=>({'&':'&amp;','<':'&lt;','>':'&gt;','"':'&quot;',"'":'&#39;'}[c]));
 const rows=report.checks.map(c=>`<tr><td>${escape(c.id)}</td><td>${escape(c.status)}</td><td><a href="${escape(c.evidence)}">JSON 证据</a></td></tr>`).join('');
 fs.writeFileSync(path.join(out,'report.html'),`<!doctype html><html lang="zh-CN"><meta charset="utf-8"><title>game-demo 本地测试结果</title><style>body{font:16px/1.7 system-ui;max-width:900px;margin:48px auto;padding:0 24px;color:#172033}table{border-collapse:collapse;width:100%}td,th{text-align:left;border-bottom:1px solid #ddd;padding:12px}pre{white-space:pre-wrap;background:#f3f5f8;padding:16px}a{color:#245fc7}</style><h1>game-demo 本地测试：${escape(report.status)}</h1><p>${escape(report.startedAt)} → ${escape(report.finishedAt)}</p><p>本次检查浏览器 DOM 事件和强制 QA 场景，不代表自然通关、键鼠命中或性能验收。</p><table><tr><th>检查组</th><th>通过项</th><th>证据</th></tr>${rows}</table>${report.failure?`<pre>${escape(report.failure)}</pre>`:''}<p><a href="summary.json">完整结果</a> · <a href="preview.log">启动日志</a></p></html>`);
 console.log(JSON.stringify({...report,reportPath:path.join(out,'summary.json')}));
}
